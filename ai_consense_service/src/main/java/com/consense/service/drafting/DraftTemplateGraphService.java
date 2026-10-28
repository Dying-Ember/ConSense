package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.*;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Source-reviewed graph revisions and project adoption; the normal parser and document chain remain owners. */
@Service @RequiredArgsConstructor
public class DraftTemplateGraphService {
    private final ProjectService projects;
    private final SourceDocumentRepository sources;
    private final DraftTemplateGraphRevisionRepository revisions;
    private final DraftTemplateGraphHeadRepository heads;
    private final DraftGraphInputBindingRepository bindings;
    private final DraftVariableRepository variables;
    private final DraftExtractionRunRepository runs;
    private final DraftReviewSources sourceReader;
    private final AiGateway ai;
    private final DraftExtractionHarness extractionHarness;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private DraftPdfSccReference pdfReference;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private DraftTemplateBaselineRepository templateBaselines;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private DraftTemplateBaselineSourceRepository templateBaselineSources;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private DraftAutomationRunRepository automationRuns;

    public boolean hasConfirmed(String projectId) {
        return heads.findById(projectId).map(h->h.getCurrentRevisionId()!=null).orElse(false);
    }
    public boolean hasDraft(String projectId) {return heads.findById(projectId).map(h->h.getDraftRevisionId()!=null).orElse(false);}
    @Transactional public void lockForGeneration(String projectId) { lock(projectId); }
    public Map<String,Object> current(String projectId) {
        projects.require(projectId);DraftTemplateGraphHead head=head(projectId);
        Map<Long,DraftReviewSources.CheckedSource> checkedSources=new HashMap<>();
        String working=head.getCurrentRevisionId()==null&&head.getDraftRevisionId()==null?null:workingRevision(projectId).getId();
        return map("currentRevisionId",head.getCurrentRevisionId(),"draftRevisionId",head.getDraftRevisionId(),"workingRevisionId",working,
                "current",head.getCurrentRevisionId()==null?null:revisionVO(requireRevision(projectId,head.getCurrentRevisionId()),true,checkedSources),
                "draft",head.getDraftRevisionId()==null?null:revisionVO(requireRevision(projectId,head.getDraftRevisionId()),true,checkedSources),"sources",JsonUtils.mapper().convertValue(sourceReader.sources(sources.findDraftReviewSourcesByProjectId(projectId),checkedSources),List.class));
    }
    public List<Map<String,Object>> history(String projectId) {
        projects.require(projectId);List<Map<String,Object>> result=new ArrayList<>();
        for(DraftTemplateGraphRevision revision:revisions.findByProjectIdOrderByCreatedAtDesc(projectId))result.add(revisionVO(revision,false));return result;
    }
    public Map<String,Object> revision(String projectId,String revisionId) { projects.require(projectId);return revisionVO(requireRevision(projectId,revisionId),true); }
    public List<Map<String,Object>> sources(String projectId) {
        projects.require(projectId);return JsonUtils.mapper().convertValue(sourceReader.sources(sources.findDraftReviewSourcesByProjectId(projectId)),List.class);
    }
    public JsonNode reading(String projectId,Long sourceId) { return sourceReader.reading(sources.findById(sourceId).filter(source->projectId.equals(source.getProjectId())).orElseThrow(()->new BizException(4011,"SOURCE_NOT_FOUND"))); }
    public JsonNode reading(String projectId,Long sourceId,boolean compact) {
        if(!compact)return reading(projectId,sourceId);
        SourceDocument source=sources.findDraftReviewSourceByProjectIdAndId(projectId,sourceId).orElseThrow(()->new BizException(4011,"SOURCE_NOT_FOUND"));
        return sourceReader.readingCompact(source);
    }
    public Map<String,Object> bootstrap(String projectId) {
        projects.require(projectId);List<Map<String,Object>> available=templateIdentities(projectId);
        Map<String,Object> graph=map("kind","consense-template-graph-v1","graph",map("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true,"guidanceMode","minimum"),"sources",available,"selectedDocuments",selected(available));
        graph=DraftFixedBaseline.restore(restoreEditionBaseline(graph));if(!DraftFixedBaseline.installed(graph)&&!"complete_requires_review".equals(asMap(state(graph).get("baselineRestoration")).get("status")))graph=DraftReviewDiscovery.seed(graph,templateReadings(projectId));
        return map("graph",graph,"sources",available,"currentRevisionId",head(projectId).getCurrentRevisionId(),"draftRevisionId",head(projectId).getDraftRevisionId());
    }
    public Map<String,Object> discover(String projectId,String revisionId,String expectedCurrent,int maxItems){
        if(maxItems<1||maxItems>10)throw new BizException(4007,"DISCOVERY_BUDGET_MUST_BE_1_TO_10");projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);
        if(!"DRAFT".equals(revision.getStatus())||!Objects.equals(head(projectId).getDraftRevisionId(),revisionId))throw conflict("Only the selected draft can receive discovery suggestions.");expected(head(projectId).getCurrentRevisionId(),expectedCurrent);requireSourcesCurrent(revision);
        Map<String,Object> graph=DraftReviewDiscovery.discover(ai,JsonUtils.readMap(revision.getGraphJson()),templateReadings(projectId),maxItems);
        return new TransactionTemplate(transactionManager).execute(status->{lockedHead(projectId);requireSourcesCurrent(revision);return save(projectId,graph,expectedCurrent,revisionId,true);});
    }
    @Transactional
    public Map<String,Object> save(String projectId,Map<String,Object> proposed,String expectedCurrent) { return save(projectId,proposed,expectedCurrent,null,false); }
    @Transactional
    public Map<String,Object> save(String projectId,Map<String,Object> proposed,String expectedCurrent,String expectedDraft,boolean checkDraft) {
        DraftTemplateGraphHead head=lockedHead(projectId);expected(head.getCurrentRevisionId(),expectedCurrent);
        if(checkDraft)expected(head.getDraftRevisionId(),expectedDraft);
        Map<String,Object> graph=pinGraph(projectId,proposed);String previous=head.getDraftRevisionId()!=null?head.getDraftRevisionId():head.getCurrentRevisionId();Map<String,Object> prior=previous==null?Collections.emptyMap():JsonUtils.readMap(requireRevision(projectId,previous).getGraphJson());
        DraftPdfSccAlignment.reconcile(graph,prior,pdfReference);reconcileRelationReviews(graph,prior);preserveDefinitionGraphReview(projectId,head,graph);DraftReviewGraph.validate(graph);stampDefinitionReviews(graph);
        for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if("contractTitle".equals(id(node)))throw new BizException(4007,"PROJECT_IDENTITY_KEY_RESERVED: Use the fixed project identity input.");
        List<Object> archived=archives(projectId,graph);
        DraftTemplateGraphRevision saved=create(projectId,head.getDraftRevisionId()==null?head.getCurrentRevisionId():head.getDraftRevisionId(),"DRAFT",graph,archived);
        head.setDraftRevisionId(saved.getId());heads.save(head);return revisionVO(saved,true);
    }
    @Transactional
    public Map<String,Object> confirmDefinitions(String projectId,String draftId,String expectedCurrent,String reason,boolean acknowledgeUnresolved) {
        return confirmDefinitions(projectId,draftId,expectedCurrent,reason,acknowledgeUnresolved,null);
    }
    @Transactional
    public Map<String,Object> confirmDefinitions(String projectId,String draftId,String expectedCurrent,String reason,boolean acknowledgeUnresolved,List<String> selectedIds) {
        return confirmDefinitions(projectId,draftId,expectedCurrent,reason,acknowledgeUnresolved,selectedIds,false);
    }
    @Transactional
    public Map<String,Object> confirmDefinitions(String projectId,String draftId,String expectedCurrent,String reason,boolean acknowledgeUnresolved,List<String> selectedIds,boolean reuseForEdition) {
        return confirmDefinitions(projectId,draftId,expectedCurrent,reason,acknowledgeUnresolved,selectedIds,reuseForEdition,false);
    }
    @Transactional
    public Map<String,Object> confirmDefinitions(String projectId,String draftId,String expectedCurrent,String reason,boolean acknowledgeUnresolved,List<String> selectedIds,boolean reuseForEdition,boolean completeEdition) {
        DraftTemplateGraphHead head=lockedHead(projectId);expected(head.getCurrentRevisionId(),expectedCurrent);
        DraftTemplateGraphRevision draft=requireRevision(projectId,draftId);
        if(!"DRAFT".equals(draft.getStatus())||!Objects.equals(head.getDraftRevisionId(),draftId))throw conflict("Only the current saved draft can confirm the variable graph.");
        if(reason==null||reason.trim().isEmpty())throw new BizException(4007,"GRAPH_DEFINITION_CONFIRMATION_REASON_REQUIRED");
        requireSourcesCurrent(draft);Map<String,Object> graph=JsonUtils.readMap(draft.getGraphJson());DraftReviewGraph.validate(graph);
        if(reuseForEdition&&state(graph).containsKey("fixedRuleBaseline"))throw new BizException(4007,"GRAPH_FIXED_CATALOG_REUSE_NOT_REQUIRED: Built-in definitions are restored automatically; this draft cannot be registered as a complete source-bound baseline.");
        if(selectedIds!=null&&DraftFixedBaseline.installed(graph)){
            List<String> fixed=new ArrayList<>();for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(selectedIds.contains(id(field))&&DraftFixedBaseline.exactSource(graph,field))fixed.add(id(field));
            if(!fixed.isEmpty())graph=DraftFixedBaseline.confirmGroup(graph,fixed,reason);
        }
        if(selectedIds!=null){boolean currentCandidates=false;for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(selectedIds.contains(id(field))&&DraftKnownRootReview.candidate(graph,field)){currentCandidates=true;break;}
            if(currentCandidates)graph=DraftKnownRootReview.confirmGroup(graph,templateReadings(projectId),selectedIds,reason);
        }
        List<Map<String,Object>> fields=DraftReviewGraph.fields(graph);if(fields.isEmpty())throw new BizException(4007,"GRAPH_HAS_NO_CONFIRMED_DEFINITIONS");
        int unresolved=unresolvedGraphRelations(graph);if(unresolved>0&&!acknowledgeUnresolved)throw new BizException(4007,"GRAPH_UNRESOLVED_RELATION_ACK_REQUIRED: "+unresolved);
        Map<String,Object> review;
        if(selectedIds==null){
            for(Map<String,Object> field:fields){String problem=definitionProblem(projectId,graph,field,true);if(problem!=null)throw new BizException(4007,"GRAPH_DEFINITION_REVIEW_REQUIRED: "+id(field)+" · "+problem);}
            String fingerprint=definitionGraphFingerprint(graph);if(fingerprint==null)throw new BizException(4007,"GRAPH_DEFINITION_FINGERPRINT_UNAVAILABLE");
            review=map("confirmed",true,"fingerprint",fingerprint);
        }else{
            Set<String> selected=new LinkedHashSet<>(selectedIds);if(selected.isEmpty()||selected.size()!=selectedIds.size()||selected.contains(null))throw new BizException(4007,"GRAPH_DEFINITION_SELECTION_REQUIRED");
            Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> field:fields)byId.put(id(field),field);
            if(!byId.keySet().containsAll(selected))throw new BizException(4007,"GRAPH_DEFINITION_SELECTION_INVALID");
            Map<String,Map<String,Object>> nodes=new HashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(id(node),node);
            for(String key:selected)prepareSelectedDefinition(byId.get(key),nodes);stampDefinitionReviews(graph);byId.clear();for(Map<String,Object> field:DraftReviewGraph.fields(graph))byId.put(id(field),field);
            Set<String> confirmed=new HashSet<>(selected);for(Object key:list(definitionGraphReview(draft,graph,false).get("confirmedFieldIds")))confirmed.add(String.valueOf(key));
            for(String key:selected)requireConfirmedDependencies(byId.get(key),nodes,byId,confirmed);
            review=new LinkedHashMap<>(asMap(state(graph).get("definitionGraphReview")));Map<String,Object> scopes=new LinkedHashMap<>(asMap(review.get("fieldScopes")));
            for(String key:selected){Map<String,Object> field=byId.get(key);String problem=definitionProblem(projectId,graph,field,true);if(problem!=null)throw new BizException(4007,"GRAPH_DEFINITION_REVIEW_REQUIRED: "+key+" · "+problem);scopes.put(key,definitionScopeFingerprint(graph,field));}
            review.put("fieldScopes",scopes);
        }
        review.put("reason",reason.trim());review.put("acknowledgedUnresolved",acknowledgeUnresolved);review.put("confirmedAt",Instant.now().toString());state(graph).put("definitionGraphReview",review);
        if(completeEdition&&!reuseForEdition)throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_REQUIRES_SHARING");
        if(reuseForEdition&&(selectedIds==null||templateBaselines==null||templateBaselineSources==null||editionFingerprint(graph)==null))throw new BizException(4007,"GRAPH_TEMPLATE_BASELINE_IDENTITY_REQUIRED");
        DraftTemplateGraphRevision confirmed=create(projectId,draftId,"DRAFT",graph,JsonUtils.readList(draft.getSourcesJson(),Object.class));head.setDraftRevisionId(confirmed.getId());heads.save(head);
        if(reuseForEdition){String edition=editionFingerprint(graph);Set<String> fieldsForReuse=new LinkedHashSet<>(selectedIds);DraftTemplateBaseline prior=templateBaselines.findFirstByEditionSha256AndOriginProjectIdOrderByCreatedAtDescIdDesc(edition,projectId).orElse(null);
            if(prior!=null){DraftTemplateGraphRevision old=revisions.findByIdAndProjectId(prior.getOriginRevisionId(),projectId).orElse(null);if(old!=null){Map<String,Object> oldGraph=JsonUtils.readMap(old.getGraphJson());Map<String,Map<String,Object>> oldFields=new HashMap<>(),currentFields=new HashMap<>();for(Map<String,Object> field:DraftReviewGraph.fields(oldGraph))oldFields.put(id(field),field);for(Map<String,Object> field:DraftReviewGraph.fields(graph))currentFields.put(id(field),field);Set<Object> confirmedIds=new HashSet<>(list(definitionGraphReview(confirmed,graph,false).get("confirmedFieldIds")));for(String key:JsonUtils.readList(prior.getFieldIdsJson(),String.class))if(confirmedIds.contains(key)&&oldFields.containsKey(key)&&currentFields.containsKey(key)&&Objects.equals(definitionScopeFingerprint(oldGraph,oldFields.get(key)),definitionScopeFingerprint(graph,currentFields.get(key))))fieldsForReuse.add(key);}}
            Map<String,Object> trustedOrigin=null;if(completeEdition){trustedOrigin=requireCompleteEdition(confirmed,graph);fieldsForReuse.clear();for(Map<String,Object> field:DraftReviewGraph.fields(graph))fieldsForReuse.add(id(field));}
            else trustedOrigin=trustedPartialOrigin(confirmed,graph);
            Map<String,Object> snapshot=DraftBaselineRestore.snapshot(graph,new ArrayList<>(fieldsForReuse),completeEdition,trustedOrigin);if(snapshot==null)throw new BizException(4007,"GRAPH_TEMPLATE_BASELINE_UNSAFE_TO_SHARE");
            String json=JsonUtils.write(snapshot),baselineId=UUID.randomUUID().toString();templateBaselines.save(new DraftTemplateBaseline(baselineId,edition,projectId,confirmed.getId(),JsonUtils.write(fieldsForReuse),json,DraftAdoption.hash(json),completeEdition,Instant.now()));
            for(Map.Entry<String,Object> entry:asMap(graph.get("selectedDocuments")).entrySet())for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);if(Objects.equals(string(source.get("id")),string(entry.getValue())))templateBaselineSources.save(new DraftTemplateBaselineSource(baselineId,entry.getKey(),string(source.get("sourceSha256"))));}}
        return revisionVO(confirmed,true);
    }
    private Map<String,Object> requireCompleteEdition(DraftTemplateGraphRevision revision,Map<String,Object> graph){
        Map<String,Object> review=definitionGraphReview(revision,graph,false);
        if(!list(review.get("pendingFieldIds")).isEmpty()||unresolvedGraphRelations(graph)>0)throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_REVIEW_REQUIRED");
        Map<String,Map<String,Object>> identities=new LinkedHashMap<>();List<Object> reviewedSources=new ArrayList<>();Set<String> selected=new HashSet<>();
        for(Object value:asMap(graph.get("selectedDocuments")).values())selected.add(String.valueOf(value));
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);String id=string(source.get("id"));if(!selected.contains(id))continue;identities.put(id,source);Map<String,Object> identity=new LinkedHashMap<>();for(String key:Arrays.asList("id","fileKey","sourceSha256","analysisRevision","analysisInputSha256","analysisSha256"))identity.put(key,source.get(key));reviewedSources.add(identity);}
        Map<String,DraftReviewSources.BoundSource> bindings=new HashMap<>();Map<String,List<Map<String,Object>>> passages=new HashMap<>();Set<String> active=new HashSet<>();
        for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if(!"excluded".equals(node.get("status"))){
            active.add(id(node));if(!"confirmed".equals(node.get("status"))||!DraftCatalogAlignment.reviewed(node)||DraftReviewGraph.ruleNeedsReview(node))throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_REVIEW_REQUIRED: "+id(node));
            if(list(node.get("sourceAnchors")).isEmpty())throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_SOURCE_REQUIRED: "+id(node));
            for(Object raw:list(node.get("sourceAnchors")))if(!completePassageBound(revision.getProjectId(),identities,bindings,asMap(raw),false))throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_SOURCE_REQUIRED: "+id(node));
            for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=asMap(raw);String action=string(target.get("action"));if(Boolean.TRUE.equals(target.get("needsRebind"))||"check".equals(action)||!completePassageBound(revision.getProjectId(),identities,bindings,target,true))throw new BizException(4007,"GRAPH_COMPLETE_BASELINE_TARGET_REQUIRED: "+id(node));if(Arrays.asList("replace","fill").contains(action)&&!DraftReviewGraph.hasReplacementPlacement(target)||"insert".equals(action)&&!DraftReviewGraph.hasInsertionPoint(target))throw new BizException(4007,"GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+id(node));}
            for(String group:Arrays.asList("sourceAnchors","editTargets"))for(Object raw:list(node.get(group))){Map<String,Object> passage=asMap(raw);passages.computeIfAbsent(string(passage.get("docId")),key->new ArrayList<>()).add(passage);}
        }
        Map<String,Object> trusted=trustedPartialOrigin(revision,graph);if(trusted==null)trusted=trustedSharedRequirements(graph);
        Map<String,Map<String,Object>> requirements=new HashMap<>();Set<String> duplicate=new HashSet<>();if(trusted!=null)for(Object raw:list(state(trusted).get("requirements"))){Map<String,Object> item=asMap(raw);String id=string(item.get("id"));if(id==null||requirements.put(id,item)!=null)duplicate.add(id);}
        Map<String,Object> omitted=new LinkedHashMap<>();List<Object> retained=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object raw:list(state(graph).get("requirements"))){Map<String,Object> item=asMap(raw);String key=string(item.get("id")),sourceId=string(item.get("sourceId"));Map<String,Object> identity=identities.get(sourceId);String problem=null;Map<String,Object> proof=key==null?null:requirements.get(key);
            if(key==null||key.trim().isEmpty()||!seen.add(key)||duplicate.contains(key))problem="invalid_or_duplicate_id";
            if(identity==null||!Objects.equals(item.get("sourceSha256"),identity.get("sourceSha256")))problem="source_not_current";
            if(identity!=null)for(String field:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))if(identity.get(field)==null||!Objects.equals(item.get(field),identity.get(field)))problem="source_not_current";
            for(Object ref:list(item.get("nodeIds")))if(!active.contains(String.valueOf(ref)))problem="excluded_or_missing_node";
            boolean inherited=proof!=null;for(String field:Arrays.asList("sourceId","sourceSha256","sourceQuote","kind","sourceQuoteEvidence","analysisRevision","analysisInputSha256","analysisSha256"))if(proof==null||!Objects.equals(item.get(field),proof.get(field)))inherited=false;
            Map<String,Object> evidence=inherited?asMap(proof.get("sourceQuoteEvidence")):asMap(item.get("sourceQuoteEvidence"));String quote=string(item.get("sourceQuote"));Map<String,Object> verified=null;
            if(problem==null){if(quote==null||quote.trim().isEmpty()||!Arrays.asList("input","choice","amendment","reference","verification").contains(item.get("kind")))problem="source_evidence_unverified";
                else{List<Map<String,Object>> candidates=new ArrayList<>();candidates.add(asMap(evidence.get("anchor")));candidates.addAll(passages.getOrDefault(sourceId,Collections.emptyList()));candidates.add(map("docId",sourceId,"sourceDocumentId",identity.get("sourceDocumentId"),"sourceSha256",identity.get("sourceSha256"),"text",quote));
                    for(Map<String,Object> candidate:candidates){if(!Objects.equals(sourceId,string(candidate.get("docId"))))continue;verified=completeRequirementAnchor(revision.getProjectId(),identities,bindings,candidate,quote);if(verified!=null)break;}if(verified==null)problem="source_evidence_unverified";}}
            if(problem!=null){omitted.put(problem,((Number)omitted.getOrDefault(problem,0)).intValue()+1);continue;}
            Map<String,Object> copy=new LinkedHashMap<>();for(String field:Arrays.asList("id","sourceId","sourceSha256","sourceQuote","kind","analysisRevision","analysisInputSha256","analysisSha256"))copy.put(field,item.get(field));copy.put("nodeIds",new ArrayList<>(list(item.get("nodeIds"))));copy.put("status","pending");copy.put("reason","Source evidence retained from the manually reviewed template.");copy.put("sourceQuoteEvidence",map("kind","manual_source","anchor",verified));retained.add(copy);
        }
        // The explicit whole-edition review qualifies the graph; historical model failures are not rewritten.
        Map<String,Object> basis=map("basis","manual_complete_edition","revisionId",revision.getId(),"graphSha256",revision.getGraphSha256(),"sources",reviewedSources,"reason",asMap(state(graph).get("definitionGraphReview")).get("reason"),"confirmedAt",Instant.now().toString(),"retainedRequirements",retained.size(),"omittedHistoricalRequirements",omitted);
        return map("graph",map("requirements",retained,"baselineCompletenessReview",basis));
    }
    private boolean completePassageBound(String projectId,Map<String,Map<String,Object>> identities,Map<String,DraftReviewSources.BoundSource> bindings,Map<String,Object> passage,boolean editable){
        DraftReviewSources.BoundSource source=completeBoundSource(projectId,identities,bindings,string(passage.get("docId")));if(source==null)return false;JsonNode anchor=JsonUtils.mapper().valueToTree(passage);
        return "bound".equals(source.bind(anchor).path("status").asText())||!editable&&"bound".equals(source.bindObservation(anchor).path("status").asText());
    }
    private DraftReviewSources.BoundSource completeBoundSource(String projectId,Map<String,Map<String,Object>> identities,Map<String,DraftReviewSources.BoundSource> bindings,String id){
        Map<String,Object> identity=identities.get(id);if(identity==null||!"bound".equals(identity.get("evidenceStatus"))||!(identity.get("sourceDocumentId") instanceof Number))return null;return bindings.computeIfAbsent(id,key->sourceReader.bindSource(requireSource(projectId,((Number)identity.get("sourceDocumentId")).longValue())));
    }
    private Map<String,Object> completeRequirementAnchor(String projectId,Map<String,Map<String,Object>> identities,Map<String,DraftReviewSources.BoundSource> bindings,Map<String,Object> passage,String quote){
        String text=string(passage.get("text")),id=string(passage.get("docId"));if(text==null||!text.contains(quote))return null;DraftReviewSources.BoundSource source=completeBoundSource(projectId,identities,bindings,id);if(source==null)return null;
        JsonNode anchor=JsonUtils.mapper().valueToTree(passage),nativeBinding=source.bind(anchor);Map<String,Object> identity=identities.get(id),verified=map("docId",id,"sourceDocumentId",identity.get("sourceDocumentId"),"sourceSha256",identity.get("sourceSha256"),"text",text);
        // A server run may contain an earlier client draft: every carried quotation is rebound to current source bytes.
        if("bound".equals(nativeBinding.path("status").asText())){verified.put("native",map("sourceSha256",identity.get("sourceSha256"),"spans",JsonUtils.mapper().convertValue(nativeBinding.path("spans"),List.class)));return verified;}
        if(!"bound".equals(source.bindObservation(anchor).path("status").asText()))return null;for(String field:Arrays.asList("page","sourceObservationId","sourceObservation","observedRanges","analysisRevision","analysisInputSha256","analysisSha256"))if(passage.containsKey(field))verified.put(field,passage.get(field));return verified;
    }
    private Map<String,Object> trustedSharedRequirements(Map<String,Object> graph){
        String id=string(asMap(state(graph).get("baselineRestoration")).get("baselineId"));if(id==null||templateBaselines==null)return null;DraftTemplateBaseline baseline=templateBaselines.findById(id).orElse(null);if(baseline==null||baseline.getGraphJson()==null||!Objects.equals(baseline.getGraphSha256(),DraftAdoption.hash(baseline.getGraphJson())))return null;
        Map<String,Object> saved=JsonUtils.readMap(baseline.getGraphJson());return sameCompleteSourceAnalyses(saved,graph)?DraftBaselineRestore.restore(saved,graph,JsonUtils.readList(baseline.getFieldIdsJson(),String.class),false):null;
    }
    private Map<String,Object> trustedPartialOrigin(DraftTemplateGraphRevision revision,Map<String,Object> graph){
        if(automationRuns==null)return null;Set<String> ancestors=new HashSet<>();DraftTemplateGraphRevision cursor=revision;
        while(cursor!=null&&ancestors.add(cursor.getId()))cursor=cursor.getParentRevisionId()==null?null:revisions.findByIdAndProjectId(cursor.getParentRevisionId(),revision.getProjectId()).orElse(null);
        List<DraftAutomationRun> candidates=new ArrayList<>(automationRuns.findByProjectIdAndStatusIn(revision.getProjectId(),Arrays.asList("COMPLETED","PARTIAL")));candidates.sort(Comparator.comparing(DraftAutomationRun::getCreatedAt).reversed());
        for(DraftAutomationRun run:candidates){if(!ancestors.contains(run.getResultRevisionId()))continue;DraftTemplateGraphRevision origin=revisions.findByIdAndProjectId(run.getResultRevisionId(),revision.getProjectId()).orElse(null);if(origin==null)continue;
            try{Map<String,Object> original=JsonUtils.readMap(origin.getGraphJson());if(sameCompleteSourceAnalyses(original,graph))return original;}
            catch(RuntimeException invalid){/* Unverifiable historical runs cannot supply shared requirement evidence. */}
        }return null;
    }
    private String editionFingerprint(Map<String,Object> graph){
        Map<String,Object> selected=asMap(graph.get("selectedDocuments"));SortedMap<String,String> identities=new TreeMap<>();if(selected.isEmpty())return null;
        for(Map.Entry<String,Object> entry:selected.entrySet()){Map<String,Object> match=null;for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);if(Objects.equals(string(source.get("id")),string(entry.getValue()))){if(match!=null)return null;match=source;}}
            if(match==null||!entry.getKey().equals(match.get("fileKey"))||!"bound".equals(match.get("evidenceStatus"))||!String.valueOf(match.get("sourceSha256")).matches("[0-9a-f]{64}"))return null;identities.put(entry.getKey(),String.valueOf(match.get("sourceSha256")));}
        return DraftAdoption.hash(JsonUtils.write(identities));
    }
    @Transactional
    public Map<String,Object> confirm(String projectId,String draftId,String expectedCurrent) {
        DraftTemplateGraphHead head=lockedHead(projectId);expected(head.getCurrentRevisionId(),expectedCurrent);
        DraftTemplateGraphRevision draft=requireRevision(projectId,draftId);
        if(!"DRAFT".equals(draft.getStatus())||!Objects.equals(head.getDraftRevisionId(),draftId))throw conflict("Only the current saved draft can be confirmed.");
        requireSourcesCurrent(draft);Map<String,Object> graph=JsonUtils.readMap(draft.getGraphJson());DraftReviewGraph.validate(graph);
        int active=0;for(Object raw:list(state(graph).get("variables"))) {
            Map<String,Object> node=asMap(raw);if("excluded".equals(node.get("status")))continue;
            active++;
            if(!"confirmed".equals(node.get("status")))throw new BizException(4007,"GRAPH_REVIEW_INCOMPLETE: "+node.get("id"));
            if(!DraftCatalogAlignment.reviewed(node))throw new BizException(4007,"GRAPH_CATALOG_REVIEW_INCOMPLETE: "+node.get("id"));
            requireReviewedSource(projectId,graph,node);
            if(DraftReviewGraph.ruleNeedsReview(node))throw new BizException(4007,"GRAPH_RULE_REBIND_REQUIRED: "+node.get("id"));
            for(Object rawTarget:list(node.get("editTargets"))) {
                Map<String,Object> target=asMap(rawTarget);String action=string(target.get("action"));
                if(Boolean.TRUE.equals(target.get("needsRebind"))||"check".equals(action))throw new BizException(4007,"GRAPH_TARGET_REVIEW_REQUIRED: "+node.get("id"));
                if(Arrays.asList("replace","fill").contains(action)&&!DraftReviewGraph.hasReplacementPlacement(target)||"insert".equals(action)&&!DraftReviewGraph.hasInsertionPoint(target))throw new BizException(4007,"GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+node.get("id"));
            }
        }
        if(active==0)throw new BizException(4007,"GRAPH_HAS_NO_REVIEWED_ITEMS");
        DraftTemplateGraphRevision confirmed=create(projectId,draftId,"CONFIRMED",graph,JsonUtils.readList(draft.getSourcesJson(),Object.class));
        head.setCurrentRevisionId(confirmed.getId());heads.save(head);ensureInputs(projectId,confirmed,graph);restoreDraftCandidates(projectId,confirmed,graph);return revisionVO(confirmed,true);
    }
    public Map<String,Object> graph(String projectId) { return JsonUtils.readMap(workingRevision(projectId).getGraphJson()); }
    public SourceDocument selectedTemplate(String projectId,String fileKey){DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());String selected=string(asMap(graph.get("selectedDocuments")).get(fileKey));
        if(selected==null)throw new BizException(4011,"GRAPH_TEMPLATE_NOT_SELECTED: "+fileKey);SourceDocument source;try{source=requireSource(projectId,Long.valueOf(selected));}catch(NumberFormatException invalid){throw new BizException(4007,"GRAPH_TEMPLATE_ID_INVALID");}
        if(!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(source.getCategory())||!fileKey.equals(source.getFileKey()))throw conflict("Selected source category or family changed.");return source;}
    public JsonNode selectedTemplateEvidence(String projectId,String fileKey){return sourceReader.evidence(selectedTemplate(projectId,fileKey));}
    public Map<String,Object> archivedSource(String projectId,String revisionId,String sourceId){projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);for(JsonNode manifest:JsonUtils.parse(revision.getSourcesJson()))if(sourceId.equals(manifest.path("id").asText())||sourceId.equals(manifest.path("sourceDocumentId").asText()))return map("fileName",manifest.path("fileName").asText(),"format",manifest.path("format").asText(),"bytes",sourceReader.archivedBytes(manifest));throw new BizException(4011,"GRAPH_REVISION_SOURCE_NOT_FOUND");}
    public Map<String,Object> candidates(String projectId,String revisionId) {
        projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),trace=latestCandidateTrace(projectId,revision,graph);Map<String,Object> found=asMap(trace.get("candidatesByKey"));List<VariableVO> result=new ArrayList<>();
        boolean evidenceStale=!trace.isEmpty()&&!Objects.equals(trace.get("evidenceRevision"),evidenceRevision(projectId));
        DefinitionCache originalGraphs=new DefinitionCache();Map<String,List<CandidateVO>> retainedByKey=new LinkedHashMap<>();
        for(Map<String,Object> field:extractionFields(graph)){List<CandidateVO> values=JsonUtils.readList(JsonUtils.write(list(found.get(id(field)))),CandidateVO.class);
            if(values.stream().anyMatch(candidate->!candidateDefinitionCurrent(projectId,revision,field,candidate,originalGraphs))){retainedByKey.put(id(field),values);values=Collections.emptyList();}
            VariableVO item=candidateVariableVO(revision,field,values);if(!evidenceStale)try{item.setCompositeBillSuggestion(compositeBill(projectId,revision,graph,field,values,trace));}catch(RuntimeException unavailable){/* Individual candidates remain visible for review. */}result.add(item);}
        Set<String> activeKeys=new HashSet<>();for(Map<String,Object> field:extractionFields(graph))activeKeys.add(id(field));
        List<VariableVO> unassigned=new ArrayList<>();for(Map<String,Object> old:DraftReviewGraph.nodes(graph)){
            List<CandidateVO> retained=activeKeys.contains(id(old))?retainedByKey.getOrDefault(id(old),Collections.emptyList()):JsonUtils.readList(JsonUtils.write(list(found.get(id(old)))),CandidateVO.class);
            if(!retained.isEmpty()){VariableVO item=candidateVariableVO(revision,old,retained);item.setReviewRequired(true);unassigned.add(item);}
        }
        return map("runId",trace.get("runId"),"graphRevisionId",revisionId,"evidenceRevision",trace.get("evidenceRevision"),"stale",evidenceStale,"variables",result,"unassignedVariables",unassigned,"catalog",DraftReviewGraph.catalog(graph),"candidateDefinitions",candidateDefinitions(projectId,found));
    }
    private Map<String,Object> candidateDefinitions(String projectId,Map<String,Object> found) {
        Map<String,Set<String>> requested=new LinkedHashMap<>();
        for(Object raw:found.values())for(Object entry:list(raw)){Map<String,Object> candidate=asMap(entry);String revision=string(candidate.get("graphRevisionId")),hash=string(candidate.get("fieldDefinitionHash"));if(revision!=null&&hash!=null)requested.computeIfAbsent(revision,key->new HashSet<>()).add(hash);}
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Set<String>> entry:requested.entrySet()){
            DraftTemplateGraphRevision original=revisions.findById(entry.getKey()).filter(value->projectId.equals(value.getProjectId())).orElse(null);if(original==null)continue;
            Map<String,Object> graph=JsonUtils.readMap(original.getGraphJson());Map<String,String> hashes=new HashMap<>();
            for(Map<String,Object> field:extractionFields(graph)){String hash=DraftReviewGraph.fieldHash(field);if(entry.getValue().contains(hash))hashes.put(id(field),hash);}
            for(Object raw:list(DraftReviewGraph.catalog(graph).get("groups")))for(Object field:list(asMap(raw).get("fields"))){String hash=hashes.get(string(asMap(field).get("key")));if(hash!=null)result.put(hash,field);}
        }
        return result;
    }
    @Transactional
    public Map<String,Object> correctCandidate(String projectId,String revisionId,String key,Map<String,Object> body) {
        DraftTemplateGraphHead head=lockedHead(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);
        if(!revisionId.equals(head.getCurrentRevisionId())&&!revisionId.equals(head.getDraftRevisionId()))throw conflict("Review revision is no longer selected.");requireSourcesCurrent(revision);
        Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),trace=latestCandidateTrace(projectId,revision,graph);expected(string(trace.get("runId")),string(body.get("expectedRunId")));
        if(trace.isEmpty()||!Objects.equals(trace.get("evidenceRevision"),evidenceRevision(projectId)))throw conflict("Candidate evidence changed; identify again.");
        Map<String,Object> found=copy(asMap(trace.get("candidatesByKey")));List<Object> origin=new ArrayList<>(list(found.get(key)));Object rawIndex=body.get("candidateIndex");
        if(!(rawIndex instanceof Number)||((Number)rawIndex).doubleValue()!=((Number)rawIndex).intValue())throw new BizException(4007,"CANDIDATE_INDEX_REQUIRED");int index=((Number)rawIndex).intValue();if(index<0||index>=origin.size())throw conflict("Candidate no longer exists.");
        String target=body.get("targetKey")==null?key:string(body.get("targetKey"));Map<String,Object> targetField=field(graph,target);CandidateVO old=JsonUtils.mapper().convertValue(origin.get(index),CandidateVO.class);verifyCandidateSource(projectId,old);
        String quote=body.containsKey("sourceQuote")?string(body.get("sourceQuote")):old.getSourceQuote();SourceDocument source=requireSource(projectId,old.getSourceDocumentId());
        if(quote==null||quote.trim().isEmpty()||!source.getTextContent().contains(quote))throw new BizException(4007,"CANDIDATE_QUOTE_NOT_IN_SOURCE");
        Object raw=body.get("value");String normalized=normalizeCandidate(targetField,raw==null?"":raw instanceof String?(String)raw:JsonUtils.write(raw));
        Map<String,Object> changed=JsonUtils.mapper().convertValue(old,Map.class);changed.put("value",normalized);changed.put("sourceQuote",quote);changed.put("graphRevisionId",revisionId);changed.put("fieldDefinitionHash",DraftReviewGraph.fieldHash(targetField));changed.put("reason",body.getOrDefault("reason","Human-corrected candidate; not adopted."));
        origin.remove(index);found.put(key,origin);List<Object> destination=new ArrayList<>(list(found.get(target)));destination.add(changed);found.put(target,destination);
        String runId=UUID.randomUUID().toString();Map<String,Object> corrected=copy(trace);corrected.put("runId",runId);corrected.put("graphRevisionId",revisionId);corrected.put("graphSha256",revision.getGraphSha256());corrected.put("parentRunId",trace.get("runId"));corrected.put("actor","manual_correction");corrected.put("candidatesByKey",found);corrected.put("finishedAt",Instant.now().toString());
        Map<String,Object> definitions=new LinkedHashMap<>(asMap(trace.get("fieldDefinitions"))),scopes=new LinkedHashMap<>(asMap(trace.get("fieldScopes")));if(destination.stream().allMatch(value->Objects.equals(DraftReviewGraph.fieldHash(targetField),asMap(value).get("fieldDefinitionHash")))){definitions.put(target,DraftReviewGraph.fieldHash(targetField));scopes.put(target,definitionScopeFingerprint(graph,targetField));}else{definitions.remove(target);scopes.remove(target);}corrected.put("fieldDefinitions",definitions);corrected.put("fieldScopes",scopes);
        runs.save(new DraftExtractionRun(runId,projectId,Instant.now(),JsonUtils.write(corrected)));
        if(revisionId.equals(head.getCurrentRevisionId())){ensureInputs(projectId,revision,graph);for(String affected:new LinkedHashSet<>(Arrays.asList(key,target))){if(extractionFields(graph).stream().noneMatch(item->affected.equals(id(item))))continue;DraftVariable variable=variable(projectId,affected);boolean definitionMatches=Objects.equals(DraftReviewGraph.fieldHash(field(graph,affected)),definitions.get(affected));variable.setCandidatesJson(JsonUtils.write(definitionMatches?list(found.get(affected)):Collections.emptyList()));if(!definitionMatches||Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited()))variable.setReviewRequired(true);variables.save(variable);if(!"contractTitle".equals(affected)){DraftGraphInputBinding binding=binding(projectId,affected);binding.setCandidateRevisionId(revisionId);bindings.save(binding);}}}
        return candidates(projectId,revisionId);
    }
    private Map<String,Object> latestCandidateTrace(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){
        return findCandidateTrace(projectId,trace->{
            if(!(trace.get("candidatesByKey") instanceof Map))return false;
            if(Objects.equals(revision.getId(),trace.get("graphRevisionId")))return true;
            for(Map<String,Object> old:DraftReviewGraph.nodes(graph))if(!list(asMap(trace.get("candidatesByKey")).get(id(old))).isEmpty())return true;
            for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(Objects.equals(DraftReviewGraph.fieldHash(field),asMap(trace.get("fieldDefinitions")).get(id(field))))return true;
            return false;
        });
    }
    private Map<String,Object> findCandidateTrace(String projectId,java.util.function.Predicate<Map<String,Object>> accepts){
        for(int page=0;;page++){
            List<DraftExtractionRun> batch=runs.findByProjectIdOrderByFinishedAtDescIdDesc(projectId,org.springframework.data.domain.PageRequest.of(page,1));
            if(batch.isEmpty())return Collections.emptyMap();
            Map<String,Object> trace=JsonUtils.readMap(batch.get(0).getTraceJson());
            if(Arrays.asList("completed","partial").contains(trace.get("status"))&&accepts.test(trace))return trace;
        }
    }
    public Map<String,Object> catalog(String projectId) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> result=new LinkedHashMap<>(DraftReviewGraph.catalog(JsonUtils.readMap(revision.getGraphJson())));
        if(overrideField(JsonUtils.readMap(revision.getGraphJson()))!=null){List<Object> system=new ArrayList<>(list(result.get("systemFields")));system.add(map("key","targetOverrides","hidden",true,"kind","list","label",LocalizedText.same("Target amendments")));result.put("systemFields",system);}
        result.put("graphRevisionId",revision.getId());result.put("reviewStatus",revision.getStatus());return result;
    }
    @Transactional
    public List<VariableVO> variables(String projectId) {
        lockedHead(projectId);
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());ensureInputs(projectId,revision,graph);
        if("DRAFT".equals(revision.getStatus()))restoreDraftCandidates(projectId,revision,graph);
        boolean sourceStale=stale(revision);
        DefinitionCache candidateGraphs=new DefinitionCache();List<VariableVO> result=new ArrayList<>();for(Map<String,Object> field:allFields(graph))result.add(variableVO(projectId,revision,field,sourceStale,candidateGraphs));return result;
    }
    @Transactional
    public VariableVO updateVariable(String projectId,String key,VariablePatch patch) {
        lockedHead(projectId);DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);
        Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),field="targetOverrides".equals(key)?overrideField(graph):field(graph,key),body=JsonUtils.mapper().convertValue(patch,Map.class);
        if(field==null)throw new BizException(4007,"Unknown reviewed drafting input: "+key);
        expected(revision.getId(),string(body.get("graphRevisionId")));expected(DraftReviewGraph.fieldHash(field),string(body.get("fieldDefinitionHash")));
        ensureInputs(projectId,revision,graph);DraftVariable variable=variable(projectId,key);DraftGraphInputBinding binding=binding(projectId,key);
        String raw=patch.getValue()!=null?patch.getValue():patch.getChoice();boolean explicitEmpty=Boolean.TRUE.equals(patch.getExplicitEmpty());
        if(explicitEmpty&&(!"text".equals(asMap(field.get("type")).get("kind"))||!"".equals(raw)||patch.getCandidateIndex()!=null))throw new BizException(4007,"EXPLICIT_EMPTY_TEXT_REQUIRED");List<CandidateVO> candidates=DraftAdoption.candidates(variable);
        if(patch.getCompositeBill()!=null) {
            CompositeBillRequest request=patch.getCompositeBill();if(raw!=null||patch.getCandidateIndex()!=null||patch.getCandidateSnapshot()!=null||patch.getSuggestionSnapshot()!=null||Boolean.TRUE.equals(patch.getConfirmed())||Boolean.TRUE.equals(patch.getReviewed()))throw new BizException(4007,"COMPOSITE_BILL_ACTION_CONFLICT");
            if(!Objects.equals(request.getExpectedValue(),variable.getValueText())||!Objects.equals(request.getExpectedConfirmed(),Boolean.TRUE.equals(variable.getConfirmed()))||!Objects.equals(request.getExpectedManuallyEdited(),Boolean.TRUE.equals(variable.getManuallyEdited()))||!Objects.equals(request.getExpectedReviewRequired(),Boolean.TRUE.equals(variable.getReviewRequired())))throw conflict("The adopted value changed; review it before applying the combined candidate.");
            Map<String,Object> trace=latestCandidateTrace(projectId,revision,graph);if(!Objects.equals(request.getRunId(),trace.get("runId"))||!Objects.equals(trace.get("evidenceRevision"),evidenceRevision(projectId)))throw conflict("Candidate run or correspondence changed; review the combined candidate again.");
            List<CandidateVO> members=JsonUtils.readList(JsonUtils.write(list(asMap(trace.get("candidatesByKey")).get(key))),CandidateVO.class);CompositeBillSuggestion suggestion=compositeBill(projectId,revision,graph,field,members,trace);
            if(suggestion==null||!Objects.equals(request.getMemberIndexes(),suggestion.getMemberIndexes())||!Objects.equals(request.getMergeHash(),suggestion.getMergeHash()))throw conflict("Combined candidate or one of its sources changed; review it again.");
            raw=suggestion.getValue();variable.setManuallyEdited(false);variable.setSourceRef(null);variable.setAdoptedSourcesJson(JsonUtils.write(members));binding.setAdoptionOrigin("candidate_merge");binding.setAdoptionReason(null);
        } else if(patch.getCandidateIndex()!=null) {
            int index=patch.getCandidateIndex();if(index<0||index>=candidates.size())throw conflict("Candidate no longer exists.");
            CandidateVO candidate=candidates.get(index);
            if("needs_review".equals(candidate.getCompletenessStatus()))throw new BizException(4007,"CANDIDATE_COMPLETENESS_REVIEW_REQUIRED: Complete the retained source text manually before adoption.");
            if(!candidateDefinitionCurrent(projectId,revision,field,candidate,new DefinitionCache()))throw conflict("Candidate definition changed; review or reassign it before adoption.");
            if(patch.getCandidateSnapshot()==null||!JsonUtils.write(candidate).equals(JsonUtils.write(patch.getCandidateSnapshot())))throw conflict("Candidate changed since it was displayed.");
            if(!revision.getId().equals(binding.getCandidateRevisionId()))throw conflict("Candidate belongs to another graph revision; identify again.");
            verifyCandidateSource(projectId,candidate);raw=candidate.getValue();variable.setManuallyEdited(false);variable.setSourceRef(candidate.getSourceQuote());
            variable.setAdoptedSourcesJson(JsonUtils.write(Collections.singletonList(candidate)));binding.setAdoptionOrigin("candidate");binding.setAdoptionReason(null);
        } else if(raw!=null) {
            variable.setManuallyEdited(!JsonUtils.isBlankText(raw)||explicitEmpty);variable.setSourceRef(null);variable.setAdoptedSourcesJson("[]");binding.setAdoptionOrigin("manual");
            binding.setAdoptionReason(string(body.get("reason"))==null?patch.getNote():string(body.get("reason")));
            if("targetOverrides".equals(key)&&JsonUtils.isBlankText(binding.getAdoptionReason()))binding.setAdoptionReason("Explicitly adopted target amendment.");
            if((!JsonUtils.isBlankText(raw)||explicitEmpty)&&JsonUtils.isBlankText(binding.getAdoptionReason()))throw new BizException(4007,"MANUAL_REASON_REQUIRED");
        } else if(Boolean.TRUE.equals(patch.getReviewed())||Boolean.TRUE.equals(patch.getConfirmed())) {
            DefinitionCache candidateGraphs=new DefinitionCache();for(CandidateVO candidate:candidates){if("needs_review".equals(candidate.getCompletenessStatus()))throw new BizException(4007,"CANDIDATE_COMPLETENESS_REVIEW_REQUIRED: Complete the retained source text manually before adoption.");if(!candidateDefinitionCurrent(projectId,revision,field,candidate,candidateGraphs))throw conflict("Candidate definition changed; review or reassign it before adoption.");}
            SuggestionSnapshot displayed=patch.getSuggestionSnapshot();
            if(displayed==null||!Objects.equals(displayed.getValue(),variable.getValueText())||!Objects.equals(displayed.getReviewRequired(),variable.getReviewRequired())||!JsonUtils.write(displayed.getCandidates()).equals(JsonUtils.write(candidates)))throw conflict("Suggestion changed since it was displayed.");
            for(CandidateVO candidate:DraftAdoption.adoptedSources(variable))verifyCandidateSource(projectId,candidate);
            raw=variable.getValueText();explicitEmpty="text".equals(asMap(field.get("type")).get("kind"))&&"".equals(raw)&&Boolean.TRUE.equals(variable.getConfirmed());
        }
        if(raw!=null) {
            String normalized="targetOverrides".equals(key)?normalizeOverrides(revision,graph,raw,variable.getValueText()):DraftReviewGraph.normalize(field,raw);variable.setValueText(normalized);variable.setConfirmed(!JsonUtils.isBlankText(normalized)||explicitEmpty);
            variable.setReviewRequired(false);variable.setConfirmedFrom("INPUT");binding.setAdoptedRevisionId(revision.getId());binding.setAdoptedDefinitionSha256(DraftReviewGraph.fieldHash(field));
        } else if(Boolean.FALSE.equals(patch.getConfirmed())) { variable.setConfirmed(false);variable.setManuallyEdited(false); }
        if(patch.getNote()!=null)variable.setNoteText(patch.getNote());variable.setUpdatedAt(Instant.now());variables.save(variable);bindings.save(binding);return variableVO(projectId,revision,field);
    }
    @Transactional
    public List<VariableVO> confirmAll(String projectId) {
        lockedHead(projectId);DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);
        // Bulk confirmation can preserve existing adoptions only; displaying a list is not consent to candidate values.
        return variables(projectId);
    }
    public Map<String,Object> plan(String projectId,Map<String,String> patches) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());Map<String,Object> adopted=effective(projectId,revision,graph),visible=visibleValues(projectId,graph);
        if(patches!=null)for(Map.Entry<String,String> patch:patches.entrySet()){if("targetOverrides".equals(patch.getKey()))continue;Map<String,Object> field=field(graph,patch.getKey());String normalized=DraftReviewGraph.normalize(field,patch.getValue());Object value=decode(field,normalized);visible.put(patch.getKey(),value);if(JsonUtils.isBlankText(normalized))adopted.remove(patch.getKey());else adopted.put(patch.getKey(),value);}
        List<String> sourceReviewRequired=new ArrayList<>();
        if("DRAFT".equals(revision.getStatus()))for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if("confirmed".equals(node.get("status"))) {
            try{requireReviewedSource(projectId,graph,node);}catch(BizException missing){node.put("status","pending");sourceReviewRequired.add(id(node));}
        }
        Map<String,Object> historical=historicalProjectionValues(projectId,graph);
        Map<String,Object> result=new LinkedHashMap<>(DraftReviewGraph.plan(graph,adopted,historical,true));result.put("inputValues",visible);
        Map<String,Object> historySummary=new LinkedHashMap<>();historical.forEach((key,raw)->{Map<String,Object> item=asMap(raw);historySummary.put(key,map("value",item.get("value"),"status",item.get("status"),"reviewRequired",item.get("reviewRequired"),"evidenceFingerprint",item.get("evidenceFingerprint")));});result.put("historicalProjectionValues",historySummary);
        if("CONFIRMED".equals(revision.getStatus()))applyOverrides(projectId,revision,graph,result,patches);
        else if(!JsonUtils.isBlankText(string(visible.get("targetOverrides")))&&!"[]".equals(string(visible.get("targetOverrides"))))addIssue(result,"draft_target_overrides","请先在图谱中核对修改规则；未完成草稿不执行脱离规则的目标覆盖。 / Review the amendment in the graph; target overrides are not applied to an unconfirmed graph.");
        for(String id:sourceReviewRequired)addIssue(result,"source_review:"+id,"原文依据尚未核实，相关修改保留原文。 / Source evidence needs review; related passages remain unchanged.");
        DraftReviewGraph.deferConflictingActions(result);
        result.put("graphRevisionId",revision.getId());result.put("ruleVersion",revision.getGraphSha256());result.put("reviewStatus",revision.getStatus());
        result.put("snapshotId",snapshotId(projectId,patches,historical));result.put("savedSnapshotId",snapshotId(projectId,null,historical));result.put("preview",patches!=null);
        result.put("sourceIdentities",JsonUtils.readMap(revision.getGraphJson()).get("sources"));result.put("evidenceRevision",evidenceRevision(projectId));
        if(stale(revision))addIssue(result,"graph_source_changed","Template source changed; review and confirm a new graph.");
        if(state(graph).containsKey("fixedRuleBaseline"))result.put("fixedRuleBaselinePresent",true);return result;
    }
    public String snapshotId(String projectId,Map<String,String> patches) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());
        return snapshotId(projectId,patches,historicalProjectionValues(projectId,graph));
    }
    private String snapshotId(String projectId,Map<String,String> patches,Map<String,Object> historical) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());
        Map<String,DraftVariable> byKey=new HashMap<>();for(DraftVariable variable:variables.findByProjectIdOrderBySortOrderAsc(projectId))if(byKey.put(variable.getVarKey(),variable)!=null)throw new IllegalStateException("Duplicate drafting input key.");
        Map<String,DraftGraphInputBinding> byBindingId=new HashMap<>();for(DraftGraphInputBinding binding:bindings.findByProjectId(projectId))if(byBindingId.put(binding.getId(),binding)!=null)throw new IllegalStateException("Duplicate drafting binding identity.");
        List<Object> inputs=new ArrayList<>();for(Map<String,Object> field:allFields(graph)){DraftVariable variable=byKey.get(id(field));DraftGraphInputBinding binding=byBindingId.get(bindingId(projectId,id(field)));inputs.add(Arrays.asList(id(field),variable==null?null:variable.getValueText(),variable==null?null:variable.getConfirmed(),variable==null?null:variable.getReviewRequired(),binding==null?null:binding.getAdoptedDefinitionSha256(),variable==null?null:variable.getAdoptedSourcesJson()));}
        Map<String,Object> identity=map("graphRevisionId",revision.getId(),"graphSha256",revision.getGraphSha256(),"sourceCurrent",!stale(revision),"evidenceRevision",evidenceRevision(projectId),"inputs",inputs,"preview",patches);
        if(!historical.isEmpty())identity.put("historicalProjectionValues",historical);return hash(identity);
    }
    public DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> prepared,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        Map<String,Object> graph=compilationGraph(prepared);
        return state(graph).containsKey("fixedRuleBaseline")?DraftExecutionBridge.compile(original,fileKey,graph,prepared,bookmarks,layout):DraftReviewGraph.compile(original,fileKey,graph,prepared,bookmarks,layout,!list(prepared.get("unresolved")).isEmpty());
    }
    Map<String,Object> compilationGraph(Map<String,Object> prepared) {
        String revisionId=string(prepared.get("graphRevisionId"));DraftTemplateGraphRevision revision=revisions.findById(revisionId).orElseThrow(()->conflict("The graph revision is unavailable."));
        if(!Objects.equals(workingRevision(revision.getProjectId()).getId(),revisionId))throw conflict("Graph changed during generation.");requireSourcesCurrent(revision);
        return JsonUtils.readMap(revision.getGraphJson());
    }

    /** Model execution is outside project write locks; only the final matching graph/source result may publish candidates. */
    public List<VariableVO> extract(String projectId) { return extract(projectId,null); }
    public List<VariableVO> extract(String projectId,String requestedRevisionId) {
        return extract(projectId,requestedRevisionId,new ExtractionMonitor(){});
    }
    public interface ExtractionMonitor {
        default boolean beforeCall(String system,String user,String fileName) { return true; }
        default void completed(String raw) { }
        default void failed(RuntimeException failure) { }
        default void finished(boolean partial) { }
    }
    public List<VariableVO> extract(String projectId,String requestedRevisionId,ExtractionMonitor monitor) {
        projects.require(projectId);DraftTemplateGraphRevision revision=requestedRevisionId==null?workingRevision(projectId):requireRevision(projectId,requestedRevisionId);
        boolean draft="DRAFT".equals(revision.getStatus());DraftTemplateGraphHead selectedHead=head(projectId);
        if(draft?!Objects.equals(selectedHead.getDraftRevisionId(),revision.getId()):!"CONFIRMED".equals(revision.getStatus())||!Objects.equals(selectedHead.getCurrentRevisionId(),revision.getId()))throw conflict("The selected graph changed before extraction.");
        String expectedCurrent=selectedHead.getCurrentRevisionId(),expectedDraft=selectedHead.getDraftRevisionId();
        requireSourcesCurrent(revision);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());
        if(draft&&!Boolean.TRUE.equals(definitionGraphReview(revision,graph,false).get("valid")))throw new BizException(4007,"GRAPH_DEFINITIONS_CONFIRMATION_REQUIRED: Confirm the saved variable and relation graph before extracting project values.");
        Map<String,Object> graphReview=definitionGraphReview(revision,graph,false);Set<Object> confirmedFields=new HashSet<>(list(graphReview.get("confirmedFieldIds")));
        List<Map<String,Object>> fields=new ArrayList<>();for(Map<String,Object> field:DraftReviewGraph.fields(graph))if((!draft||confirmedFields.contains(id(field)))&&definitionReady(projectId,graph,field,draft))fields.add(field);
        if(!draft)fields.add(identityField());
        if(fields.isEmpty())throw new BizException(4007,"GRAPH_HAS_NO_CONFIRMED_DEFINITIONS: Confirm and save at least one source-bound variable definition before extracting project values.");
        String evidence=evidenceRevision(projectId),runId=UUID.randomUUID().toString();
        Map<String,Object> previous=latestCandidateTrace(projectId,revision,graph);boolean sameEvidence=Objects.equals(evidence,previous.get("evidenceRevision"));
        if(draft&&sameEvidence){
            Map<String,Object> oldDefinitions=asMap(previous.get("fieldDefinitions")),oldScopes=asMap(previous.get("completedFieldScopes"));DefinitionCache previousGraphs=new DefinitionCache();List<Map<String,Object>> newFields=new ArrayList<>();
            for(Map<String,Object> field:fields){String key=id(field),oldScope=string(oldScopes.get(key));if(oldScope==null&&"completed".equals(previous.get("status"))&&!previous.containsKey("completedFieldScopes")&&previous.get("graphRevisionId") instanceof String)oldScope=storedDefinitionScope(projectId,string(previous.get("graphRevisionId")),key,previousGraphs);
                if(!Objects.equals(DraftReviewGraph.fieldHash(field),oldDefinitions.get(key))||!Objects.equals(definitionScopeFingerprint(graph,field),oldScope))newFields.add(field);}
            if(!newFields.isEmpty()){fields.clear();fields.addAll(newFields);}
        }
        Map<String,List<CandidateVO>> found=new LinkedHashMap<>();String system="Extract only source-supported project candidate values for the supplied reviewed fields. Templates are not project facts. Return only a strict JSON array of {key,value,sourceQuote,reason,confidence}, without markdown fences. confidence must be a JSON number from 0 to 1, for example 0.90; never use strings or labels such as high/medium/low. key must exactly match a supplied field id. Match value to type.kind: boolean uses JSON true/false, number uses a finite JSON number, and choice uses an exact options[].value. sourceQuote must copy the exact supporting passage from this project correspondence. Unknown is null, never false or an empty collection. A project decision requires an explicit correspondence statement; do not infer a clause keep/delete decision only from a template rule. Omit fields without a supported non-null answer; do not emit null placeholder rows. Do not adopt values.";
        List<Map<String,Object>> promptFields=new ArrayList<>();for(Map<String,Object> field:fields)promptFields.add(extractionPromptField(field));
        List<List<Map<String,Object>>> fieldBatches=DraftReviewGraph.extractionDefinitionBatches(promptFields);List<String> fieldPrompts=new ArrayList<>();for(List<Map<String,Object>> batch:fieldBatches)fieldPrompts.add(system+" Reviewed fields: "+JsonUtils.write(batch));
        ExtractTraceVO trace=new ExtractTraceVO(ai.chatModel(),null,system,null,new ArrayList<>());trace.setRunId(runId);trace.setHarnessVersion("reviewed-graph-v3-partial-typed-confidence");trace.setEvidenceRevision(evidence);trace.setStartedAt(Instant.now().toString());trace.setModelIdentity(ai.modelIdentity());
        Map<String,Object> definitions=new LinkedHashMap<>(),scopes=new LinkedHashMap<>();for(Map<String,Object> field:fields){definitions.put(id(field),DraftReviewGraph.fieldHash(field));scopes.put(id(field),definitionScopeFingerprint(graph,field));}
        Map<String,Object> traceExtra=map("graphRevisionId",revision.getId(),"graphSha256",revision.getGraphSha256(),"fieldDefinitions",definitions,"fieldScopes",scopes);traceExtra.put("fieldPromptBatches",fieldPrompts);List<String> prompts=new ArrayList<>();
        try {
            int totalParts=0,consecutiveFailures=0,waterproofRecalls=0;Set<String> recalledWaterproof=new HashSet<>();boolean interrupted=false;parts:for(SourceDocument source:correspondence(projectId)) {
                if(!"PARSED".equals(source.getParseStatus())||JsonUtils.isBlankText(source.getTextContent()))continue;
                String originalSha=sourceReader.archive(source).path("sourceSha256").asText();int start=0,index=0;for(String chunk:DraftClauseRules.chunks(source.getTextContent(),3000)) {
                    ExtractionPartVO part=new ExtractionPartVO(source.getId()+":"+index,source.getId(),source.getFileName(),DraftAdoption.sourceHash(source),index++,chunk,new ArrayList<>());
                    part.setContext(DraftSourceContext.primary(source.getTextContent(),start,start+chunk.length()));start+=chunk.length();trace.getParts().add(part);totalParts++;
                    String user="Project correspondence "+source.getFileName()+"\n"+part.getContext().getSourceText();prompts.add(user);
                    for(int batchIndex=0;batchIndex<fieldPrompts.size();batchIndex++){String requestSystem=fieldPrompts.get(batchIndex);Set<String> requestedKeys=new HashSet<>();for(Map<String,Object> requested:fieldBatches.get(batchIndex))requestedKeys.add(id(requested));
                    ExtractionAttemptVO attempt=new ExtractionAttemptVO(batchIndex,"primary-fields-"+batchIndex,null,user,null,"running",null,part.getContext());part.getAttempts().add(attempt);
                    String raw;
                    if(requestSystem.length()+user.length()>64000){attempt.setStatus("unexecuted");attempt.setErrorCode("request_budget_exceeded");interrupted=true;break parts;}
                    if(!monitor.beforeCall(requestSystem,user,source.getFileName())){attempt.setStatus("unexecuted");attempt.setErrorCode("cancelled_or_budget_reached");interrupted=true;break parts;}
                    try {raw=ai.complete(requestSystem,user);consecutiveFailures=0;monitor.completed(raw);attempt.setRawResponse(raw);trace.getRawResponses().add(raw);}
                    catch(RuntimeException failed){monitor.failed(failed);if(!DraftExtractionHarness.recordFailure(trace,attempt,failed)){if(++consecutiveFailures>=3){interrupted=true;break parts;}continue;}consecutiveFailures=0;raw=attempt.getRawResponse();}
                    try {
                        JsonNode items;
                        try {items=JsonUtils.parse(JsonUtils.extractJson(raw));if(!items.isArray())throw new BizException(4007,"Expected candidate array");}
                        catch(RuntimeException incomplete){items=DraftJsonItems.completePrefix(raw);attempt.setStatus("partial");if(attempt.getProviderFailureKind()==null)attempt.setErrorCode("incomplete_json");}
                        int itemIndex=0;Set<String> seenWaterproof=new HashSet<>();
                        for(JsonNode item:items) {
                            String key=item.path("key").asText(),quote=item.path("sourceQuote").asText();Map<String,Object> field=optionalField(graph,key);JsonNode value=item.get("value");
                            ExtractionDecisionVO decision=new ExtractionDecisionVO(part.getPartId(),batchIndex,itemIndex++,key,value,null,quote,item.path("reason").asText(),item.path("confidence").isNumber()?item.path("confidence").asDouble():null,"rejected",new ArrayList<>());trace.getDecisions().add(decision);
                            if(field==null||!requestedKeys.contains(key)){decision.getCodes().add("unknown_key");continue;}if(value==null||value.isNull()){decision.setStatus("unanswered");continue;}
                            if(quote.trim().isEmpty()||!part.getContext().getSourceText().contains(quote)||!source.getTextContent().contains(quote)){decision.getCodes().add("quote_not_in_source");continue;}
                            Double confidence=decision.getConfidence();if(confidence==null||!Double.isFinite(confidence)||confidence<0.70||confidence>1){decision.getCodes().add("confidence_invalid");continue;}
                            if("context_insufficient".equals(part.getContext().getStopReason())&&Arrays.asList("list","record","multiselect").contains(asMap(field.get("type")).get("kind"))){decision.getCodes().add("context_incomplete");continue;}
                            try {
                                if(confirmedWaterproofField(field)){
                                    CandidateVO initial=waterproofCandidate(revision,field,item,part,attempt,source,originalSha,runId,decision);if(initial==null)continue;
                                    found.computeIfAbsent(key,k->new ArrayList<>()).add(initial);
                                    seenWaterproof.add(key);String recallKey=source.getId()+":"+key;
                                    if("needs_review".equals(initial.getCompletenessStatus())&&waterproofRecalls<8&&recalledWaterproof.add(recallKey)){
                                        waterproofRecalls++;recallWaterproof(revision,field,part,source,originalSha,runId,system,user,monitor,trace,found,initial);
                                    }
                                    continue;
                                }
                                Map<String,String> billColumns=confirmedBillColumns(field);
                                String rawValue=value.isTextual()?value.asText():JsonUtils.write(value);
                                if(billColumns!=null){
                                    com.fasterxml.jackson.databind.node.ObjectNode fixedItem=item.deepCopy();fixedItem.put("key","billNos");fixedItem.set("value",JsonUtils.mapper().valueToTree(mapBillRows(value,billColumns,true)));
                                    ExtractionDecisionVO fixed=extractionHarness.reviewCandidate(part,attempt,decision.getItemIndex(),fixedItem,source.getTextContent());decision.getCodes().addAll(fixed.getCodes());
                                    if(!"accepted".equals(fixed.getStatus()))continue;
                                    quote=fixed.getSourceQuote();if(quote==null||!part.getContext().getSourceText().contains(quote)||!source.getTextContent().contains(quote)){decision.getCodes().add("quote_not_in_source");continue;}
                                    decision.setSourceQuote(quote);rawValue=JsonUtils.write(mapBillRows(JsonUtils.parse(fixed.getNormalizedValue()),billColumns,false));
                                }
                                String normalized=normalizeCandidate(field,rawValue,quote,part.getContext().getSourceText(),source.getTextContent());if(JsonUtils.isBlankText(normalized)){decision.setStatus("unanswered");continue;}
                                Map<String,Object> candidate=map("value",normalized,"sourceDocumentId",source.getId(),"fileName",source.getFileName(),"sourceHash",DraftAdoption.sourceHash(source),"sourceQuote",quote,"reason",decision.getReason(),"confidence",confidence,"originalSha256",originalSha,"extractionRunId",runId,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field));
                                found.computeIfAbsent(key,k->new ArrayList<>()).add(JsonUtils.mapper().convertValue(candidate,CandidateVO.class));decision.setNormalizedValue(normalized);decision.setStatus("accepted");
                            }catch(RuntimeException invalid){decision.getCodes().add("invalid_type");}
                        }
                        for(Map<String,Object> field:fields){String key=id(field),recallKey=source.getId()+":"+key;
                            if(requestedKeys.contains(key)&&confirmedWaterproofField(field)&&!seenWaterproof.contains(key)&&waterproofRecalls<8&&hasWaterproofScope(source.getTextContent(),part.getContext().getSourceText())&&recalledWaterproof.add(recallKey)){
                                waterproofRecalls++;recallWaterproof(revision,field,part,source,originalSha,runId,system,user,monitor,trace,found,null);
                            }
                        }
                        if(!"partial".equals(attempt.getStatus()))attempt.setStatus("completed");
                    }catch(RuntimeException invalid){attempt.setStatus("failed");attempt.setErrorCode("invalid_json");}
                    }
                }
            }
            if(totalParts==0)throw new BizException(4005,"Upload successfully parsed project correspondence before extraction.");
            boolean partial=interrupted||trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->!"completed".equals(attempt.getStatus()))||trace.getDecisions().stream().anyMatch(item->"candidate_needs_review".equals(item.getStatus()));
            monitor.finished(partial);
            trace.setUserPrompt(String.join("\n\n",prompts));trace.setStatus(partial?"partial":"completed");trace.setFinishedAt(Instant.now().toString());
            if(partial){boolean failedCall=trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->"model_call_failed".equals(attempt.getErrorCode()));boolean incompleteAttempt=trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->!"completed".equals(attempt.getStatus()));trace.setFailureCode(failedCall?"partial_source_failed":incompleteAttempt?"incomplete_json":"candidate_scope_needs_review");trace.setFailureMessage("Source-checked and incomplete candidates remain available for manual review; incomplete scopes are not adopted automatically.");}
            return new TransactionTemplate(transactionManager).execute(status->{DraftTemplateGraphHead currentHead=lockedHead(projectId);if(!Objects.equals(expectedCurrent,currentHead.getCurrentRevisionId())||!Objects.equals(expectedDraft,currentHead.getDraftRevisionId())||!evidence.equals(evidenceRevision(projectId)))throw conflict("Graph or correspondence changed during extraction; existing adoptions were retained.");
                requireSourcesCurrent(revision);
                Map<String,Object> combined=sameEvidence?new LinkedHashMap<>(asMap(previous.get("candidatesByKey"))):new LinkedHashMap<>();Map<String,Object> combinedDefinitions=sameEvidence?new LinkedHashMap<>(asMap(previous.get("fieldDefinitions"))):new LinkedHashMap<>(),combinedScopes=sameEvidence?new LinkedHashMap<>(asMap(previous.get("fieldScopes"))):new LinkedHashMap<>(),completedScopes=sameEvidence?new LinkedHashMap<>(asMap(previous.get("completedFieldScopes"))):new LinkedHashMap<>();
                if(sameEvidence&&"completed".equals(previous.get("status"))&&!previous.containsKey("completedFieldScopes")){DefinitionCache previousGraphs=new DefinitionCache();for(String key:combinedDefinitions.keySet()){String oldScope=string(combinedScopes.get(key));if(oldScope==null&&previous.get("graphRevisionId") instanceof String)oldScope=storedDefinitionScope(projectId,string(previous.get("graphRevisionId")),key,previousGraphs);if(oldScope!=null)completedScopes.put(key,oldScope);}}
                for(Map<String,Object> field:fields){String key=id(field);List<CandidateVO> latest=found.getOrDefault(key,Collections.emptyList());boolean keepPrevious=partial&&latest.isEmpty()&&Objects.equals(combinedScopes.get(key),scopes.get(key));if(!keepPrevious)combined.put(key,latest);combinedDefinitions.put(key,definitions.get(key));combinedScopes.put(key,scopes.get(key));if(!partial)completedScopes.put(key,scopes.get(key));else if(!Objects.equals(completedScopes.get(key),scopes.get(key)))completedScopes.remove(key);}
                traceExtra.put("candidatesByKey",combined);traceExtra.put("fieldDefinitions",combinedDefinitions);traceExtra.put("fieldScopes",combinedScopes);traceExtra.put("completedFieldScopes",completedScopes);saveTrace(projectId,trace,traceExtra);
                if(draft){List<VariableVO> proposed=new ArrayList<>();for(Map<String,Object> field:fields)proposed.add(candidateVariableVO(revision,field,found.getOrDefault(id(field),Collections.emptyList())));return proposed;}
                ensureInputs(projectId,revision,graph);for(Map<String,Object> field:fields){String key=id(field);DraftVariable variable=variable(projectId,key);List<CandidateVO> previousCandidates=DraftAdoption.candidates(variable),latest=found.getOrDefault(key,Collections.emptyList());boolean changed=!candidateIdentity(previousCandidates).equals(candidateIdentity(latest));
                    if(partial&&latest.isEmpty()&&!previousCandidates.isEmpty())variable.setReviewRequired(true);
                    else {variable.setCandidatesJson(JsonUtils.write(latest));if(Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited())){if(changed)variable.setReviewRequired(true);}else{Set<String> values=new LinkedHashSet<>();for(CandidateVO candidate:latest)if(!"needs_review".equals(candidate.getCompletenessStatus()))values.add(candidate.getValue());variable.setValueText(values.size()==1?values.iterator().next():"");variable.setSourceRef(values.size()==1?latest.stream().filter(candidate->!"needs_review".equals(candidate.getCompletenessStatus())).findFirst().map(CandidateVO::getSourceQuote).orElse(null):null);variable.setConfirmed(false);variable.setManuallyEdited(false);}}
                    variable.setUpdatedAt(Instant.now());variables.save(variable);if(!"contractTitle".equals(key)){DraftGraphInputBinding binding=binding(projectId,key);binding.setCandidateRevisionId(revision.getId());bindings.save(binding);}}
                return variables(projectId);});
        }catch(RuntimeException failure){trace.setStatus("failed");trace.setFinishedAt(Instant.now().toString());trace.setUserPrompt(String.join("\n\n",prompts));trace.setFailureCode("graph_extraction_failed");trace.setFailureMessage(failure.getMessage());new TransactionTemplate(transactionManager).execute(status->{saveTrace(projectId,trace,traceExtra);return null;});throw failure;}
    }

    private void saveTrace(String projectId,ExtractTraceVO trace,Map<String,Object> extra){Map<String,Object> payload=JsonUtils.mapper().convertValue(trace,Map.class);payload.putAll(extra);runs.save(new DraftExtractionRun(trace.getRunId(),projectId,Instant.now(),JsonUtils.write(payload)));}

    /** One source read per worker preparation; the compact identities bind the eventual single save. */
    Map<String,Object> automationInput(String projectId,String expectedCurrent,String expectedDraft,List<JsonNode> readings) {
        projects.require(projectId);DraftTemplateGraphHead selected=head(projectId);
        expected(selected.getCurrentRevisionId(),expectedCurrent);expected(selected.getDraftRevisionId(),expectedDraft);
        List<Map<String,Object>> identities=new ArrayList<>();
        for(SourceDocument source:templateSources(projectId)) {
            JsonNode reading=sourceReader.readingCompact(source);readings.add(reading);
            Map<String,Object> identity=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","sourceDocumentId","category","fileKey","fileName","parseStatus","sourceSha256","sourceHash","format","canEdit","docId","editionId","coverage","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus","catalogueSourceVerified","editableCoordinates"))
                if(reading.has(key))identity.put(key,JsonUtils.mapper().convertValue(reading.get(key),Object.class));
            identities.add(identity);
        }
        if(identities.isEmpty())throw new BizException(4007,"请先上传并识别模板文件，再开始识别变量。");
        String baseId=expectedDraft==null?expectedCurrent:expectedDraft;
        if(baseId!=null){DraftTemplateGraphRevision base=requireRevision(projectId,baseId);requireSourcesCurrent(base);Map<String,Object> graph=JsonUtils.readMap(base.getGraphJson());state(graph).remove("sourceTaskCoverage");return graph;}
        return DraftFixedBaseline.restore(restoreEditionBaseline(map("kind","consense-template-graph-v1","graph",map("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true,"guidanceMode","recognized"),"sources",identities,"selectedDocuments",selected(identities))));
    }
    private Map<String,Object> restoreEditionBaseline(Map<String,Object> current){
        if(templateBaselines==null||templateBaselineSources==null)return current;Set<String> found=new LinkedHashSet<>();String edition=editionFingerprint(current);org.springframework.data.domain.PageRequest page=org.springframework.data.domain.PageRequest.of(0,20);
        if(edition!=null)found.addAll(templateBaselines.findEditionIds(edition,page));
        for(Map.Entry<String,Object> entry:asMap(current.get("selectedDocuments")).entrySet())for(Object raw:list(current.get("sources"))){Map<String,Object> source=asMap(raw);if(Objects.equals(string(source.get("id")),string(entry.getValue()))&&"bound".equals(source.get("evidenceStatus"))&&String.valueOf(source.get("sourceSha256")).matches("[0-9a-f]{64}"))found.addAll(templateBaselines.findMatchingSourceIds(entry.getKey(),string(source.get("sourceSha256")),page));}
        Map<String,Object> best=null;int bestFields=0;boolean bestComplete=false;for(String baselineId:found)try{
            DraftTemplateBaseline baseline=templateBaselines.findById(baselineId).orElse(null);if(baseline==null)continue;
            String original=baseline.getGraphJson();if(original==null||!Objects.equals(baseline.getGraphSha256(),DraftAdoption.hash(original)))continue;
            List<String> fields=JsonUtils.readList(baseline.getFieldIdsJson(),String.class);if(fields.isEmpty())continue;
            Map<String,Object> saved=JsonUtils.readMap(original);boolean complete=baseline.isCompleteEdition()&&edition!=null&&edition.equals(baseline.getEditionSha256())&&sameCompleteSourceAnalyses(saved,current);
            Map<String,Object> restored=DraftBaselineRestore.restore(saved,current,fields,complete);if(restored==null&&complete){complete=false;restored=DraftBaselineRestore.restore(saved,current,fields,false);}
            if(restored!=null){Map<String,Object> restoration=asMap(state(restored).get("baselineRestoration"));int count=list(restoration.get("fieldIds")).size();if(complete&&!bestComplete||complete==bestComplete&&count>bestFields){restoration.put("baselineId",baseline.getId());if(complete)restoration.put("status","complete_requires_review");best=restored;bestFields=count;bestComplete=complete;}}
        }catch(RuntimeException invalid){/* Other source-matched reviewed baselines can still be used. */}
        if(best!=null)return best;
        if(!found.isEmpty())state(current).put("baselineRestoration",map("status","unavailable_requires_review","projectAnswersImported",false));return current;
    }
    private boolean sameCompleteSourceAnalyses(Map<String,Object> saved,Map<String,Object> current){
        Map<String,Object> before=asMap(saved.get("selectedDocuments")),after=asMap(current.get("selectedDocuments"));if(!before.keySet().equals(after.keySet()))return false;
        for(String file:before.keySet()){Map<String,Object> left=Collections.emptyMap(),right=Collections.emptyMap();for(Object raw:list(saved.get("sources"))){Map<String,Object> source=asMap(raw);if(Objects.equals(string(source.get("id")),string(before.get(file))))left=source;}for(Object raw:list(current.get("sources"))){Map<String,Object> source=asMap(raw);if(Objects.equals(string(source.get("id")),string(after.get(file))))right=source;}
            for(String key:Arrays.asList("sourceSha256","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(JsonUtils.isBlankText(string(left.get(key)))||!Objects.equals(left.get(key),right.get(key)))return false;
            if(!"bound".equals(left.get("evidenceStatus")))return false;
        }return true;
    }
    private void restoreDraftCandidates(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){
        String evidence=evidenceRevision(projectId);
        Map<String,Object> trace=latestCandidateTrace(projectId,revision,graph);if(!Objects.equals(evidence,trace.get("evidenceRevision")))trace=Collections.emptyMap();
        if(!trace.isEmpty()) {
            DefinitionCache candidateGraphs=new DefinitionCache();Map<String,Object> found=asMap(trace.get("candidatesByKey"));for(Map<String,Object> field:extractionFields(graph)){
                String key=id(field);if(!Objects.equals(DraftReviewGraph.fieldHash(field),asMap(trace.get("fieldDefinitions")).get(key)))continue;
                DraftVariable variable=variable(projectId,key);List<CandidateVO> latest=JsonUtils.readList(JsonUtils.write(list(found.get(key))),CandidateVO.class);
                latest.removeIf(candidate->!candidateDefinitionCurrent(projectId,revision,field,candidate,candidateGraphs));
                if((Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited()))&&!candidateIdentity(DraftAdoption.candidates(variable)).equals(candidateIdentity(latest)))variable.setReviewRequired(true);
                String candidateJson=JsonUtils.write(latest);
                if(!Objects.equals(variable.getCandidatesJson(),candidateJson)){variable.setCandidatesJson(candidateJson);variables.save(variable);}
                if(!"contractTitle".equals(key)){DraftGraphInputBinding binding=binding(projectId,key);if(!Objects.equals(binding.getCandidateRevisionId(),revision.getId())){binding.setCandidateRevisionId(revision.getId());bindings.save(binding);}}
            }
        }
    }
    private VariableVO candidateVariableVO(DraftTemplateGraphRevision revision,Map<String,Object> field,List<CandidateVO> candidates){Set<String> values=new LinkedHashSet<>();boolean partial=false;for(CandidateVO candidate:candidates)if("needs_review".equals(candidate.getCompletenessStatus()))partial=true;else values.add(candidate.getValue());return JsonUtils.mapper().convertValue(map("key",id(field),"scope","INPUT","label",LocalizedText.of(string(field.get("nameZh")),string(field.get("nameZh")),string(field.get("nameEn"))),"kind",asMap(field.get("type")).get("kind"),"value",values.size()==1?values.iterator().next():"","confirmed",false,"manuallyEdited",false,"reviewRequired",partial,"candidates",candidates,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field),"adoptionState",partial?"needs_review":values.size()>1?"conflict":values.isEmpty()?"missing":"suggested"),VariableVO.class);}
    private String candidateIdentity(List<CandidateVO> candidates){List<Object> identity=new ArrayList<>();for(CandidateVO candidate:candidates){Map<String,Object> metadata=JsonUtils.mapper().convertValue(candidate,Map.class);identity.add(Arrays.asList(candidate.getValue(),candidate.getSourceDocumentId(),candidate.getSourceHash(),metadata.get("originalSha256"),candidate.getSourceQuote(),candidate.getCompletenessStatus(),candidate.getCompletenessIssues()));}return JsonUtils.write(identity);}
    private Map<String,Object> pinGraph(String projectId,Map<String,Object> proposed) {
        Map<String,Object> graph=copy(proposed);if(graph.containsKey("state")&&!graph.containsKey("graph"))graph.put("graph",graph.remove("state"));
        if(!graph.containsKey("graph"))graph=map("kind","consense-template-graph-v1","graph",graph);
        if(JsonUtils.write(graph).getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new BizException(4007,"GRAPH_TOO_LARGE");
        List<Map<String,Object>> actual=templateIdentities(projectId);Map<String,Map<String,Object>> byDoc=new HashMap<>();Set<String> ambiguousDocs=new HashSet<>();
        for(Map<String,Object> source:actual)byDoc.put(String.valueOf(source.get("id")),source);
        for(Map<String,Object> source:actual){String alias=string(source.get("docId"));if(alias==null||ambiguousDocs.contains(alias))continue;Map<String,Object> previous=byDoc.putIfAbsent(alias,source);if(previous!=null&&previous!=source){byDoc.remove(alias);ambiguousDocs.add(alias);}}
        for(Object raw:list(graph.get("sources"))){Map<String,Object> supplied=asMap(raw),current=byDoc.get(String.valueOf(supplied.get("id")));if(current==null)throw conflict("Graph source is no longer available.");for(String key:Arrays.asList("sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(!Objects.equals(current.get(key),supplied.get(key)))throw conflict("Graph source binding differs from current original or analysis.");}
        graph.put("kind","consense-template-graph-v1");graph.put("sources",actual);if(!(graph.get("selectedDocuments") instanceof Map))graph.put("selectedDocuments",selected(actual));
        for(Map.Entry<String,Object> entry:asMap(graph.get("selectedDocuments")).entrySet()){Map<String,Object> chosen=byDoc.get(String.valueOf(entry.getValue()));if(chosen==null||!entry.getKey().equals(chosen.get("fileKey")))throw conflict("Selected document does not belong to this template family.");entry.setValue(chosen.get("id"));}
        Map<Long,DraftReviewSources.BoundSource> boundSources=new HashMap<>();Set<String> targetIds=new HashSet<>();for(Object raw:list(state(graph).get("variables"))) {
            Map<String,Object> node=asMap(raw);String nodeId=id(node);if(nodeId.length()>64)throw new BizException(4007,"GRAPH_INPUT_ID_TOO_LONG");
            if(Arrays.asList("targetOverrides","targetEdits","_evidenceSnapshot").contains(nodeId))throw new BizException(4007,"GRAPH_RESERVED_INPUT_ID");
            List<Object> docIds=new ArrayList<>();for(Object docId:list(node.get("docIds"))){if(ambiguousDocs.contains(String.valueOf(docId)))throw conflict("Source alias is ambiguous; choose the specific uploaded original.");Map<String,Object> identity=byDoc.get(String.valueOf(docId));docIds.add(identity==null?docId:identity.get("id"));}node.put("docIds",docIds);
            for(Object evidence:list(node.get("evidence"))){Map<String,Object> item=asMap(evidence);if(ambiguousDocs.contains(String.valueOf(item.get("docId"))))throw conflict("Source alias is ambiguous; choose the specific uploaded original.");Map<String,Object> identity=byDoc.get(String.valueOf(item.get("docId")));if(identity!=null)item.put("docId",identity.get("id"));}
            for(String collection:Arrays.asList("sourceAnchors","editTargets"))for(Object anchorRaw:list(node.get(collection))) {
                Map<String,Object> anchor=asMap(anchorRaw);if(ambiguousDocs.contains(String.valueOf(anchor.get("docId"))))throw conflict("Source alias is ambiguous; choose the specific uploaded original.");Map<String,Object> identity=byDoc.get(String.valueOf(anchor.get("docId")));
                if(identity==null){anchor.put("nativeBinding",map("status","missing","reason","Source document is unavailable."));continue;}
                SourceDocument source=requireSource(projectId,((Number)identity.get("sourceDocumentId")).longValue());
                DraftReviewSources.BoundSource binding=boundSources.computeIfAbsent(source.getId(),ignored->sourceReader.bindSource(source));
                anchor.put("docId",identity.get("id"));
                if("editTargets".equals(collection)&&JsonUtils.isBlankText(string(anchor.get("id"))))anchor.put("id",UUID.randomUUID().toString());
                if("editTargets".equals(collection)&&!targetIds.add(string(anchor.get("id"))))throw new BizException(4007,"GRAPH_DUPLICATE_TARGET_ID");
                anchor.put("nativeBinding",JsonUtils.mapper().convertValue(binding.bind(JsonUtils.mapper().valueToTree(anchor)),Map.class));
                if("sourceAnchors".equals(collection))anchor.put("sourceObservationBinding",JsonUtils.mapper().convertValue(binding.bindObservation(JsonUtils.mapper().valueToTree(anchor)),Map.class));
            }
        }
        return graph;
    }
    private List<Object> archives(String projectId,Map<String,Object> graph){List<Object> result=new ArrayList<>();for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);result.add(sourceReader.archive(requireSource(projectId,((Number)source.get("sourceDocumentId")).longValue())));}return result;}
    private DraftTemplateGraphRevision create(String projectId,String parent,String status,Map<String,Object> graph,List<?> archives){String json=JsonUtils.write(graph);if(json.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new BizException(4007,"GRAPH_TOO_LARGE");return revisions.save(new DraftTemplateGraphRevision(UUID.randomUUID().toString(),projectId,parent,status,DraftAdoption.hash(json),json,JsonUtils.write(archives),Instant.now()));}
    private Map<String,Object> revisionVO(DraftTemplateGraphRevision revision,boolean content){return revisionVO(revision,content,new HashMap<>());}
    private Map<String,Object> revisionVO(DraftTemplateGraphRevision revision,boolean content,Map<Long,DraftReviewSources.CheckedSource> checkedSources){boolean sourceStale=stale(revision,checkedSources);Map<String,Object> result=map("id",revision.getId(),"parentRevisionId",revision.getParentRevisionId(),"status",revision.getStatus(),"graphSha256",revision.getGraphSha256(),"createdAt",revision.getCreatedAt(),"confirmedAt",revision.getConfirmedAt(),"sourceStale",sourceStale);if(content){Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());result.put("graph",graph);result.put("scopeObservationIntegrity",DraftScopeObservationIntegrity.inspect(graph));result.put("fixedRuleSuggestions",DraftFixedRuleSuggestions.forGraph(graph));result.put("definitionGraphReview",definitionGraphReview(revision,graph,sourceStale));}return result;}
    public boolean traceStale(String projectId,Map<String,Object> payload) {
        try{DraftTemplateGraphHead head=head(projectId);String id=string(payload.get("graphRevisionId"));if(id==null||!Objects.equals(id,head.getCurrentRevisionId())&&!Objects.equals(id,head.getDraftRevisionId()))return true;return stale(requireRevision(projectId,id))||!Objects.equals(evidenceRevision(projectId),payload.get("evidenceRevision"));}catch(RuntimeException changed){return true;}
    }
    private boolean stale(DraftTemplateGraphRevision revision){return stale(revision,new HashMap<>());}
    private boolean stale(DraftTemplateGraphRevision revision,Map<Long,DraftReviewSources.CheckedSource> checkedSources){try{requireSourcesCurrent(revision,checkedSources);return false;}catch(RuntimeException changed){return true;}}
    private void requireSourcesCurrent(DraftTemplateGraphRevision revision){requireSourcesCurrent(revision,new HashMap<>());}
    private void requireSourcesCurrent(DraftTemplateGraphRevision revision,Map<Long,DraftReviewSources.CheckedSource> checkedSources){for(JsonNode archived:JsonUtils.parse(revision.getSourcesJson())){long id=archived.path("sourceDocumentId").asLong(-1);JsonNode actual=checkedSources.computeIfAbsent(id,sourceId->{SourceDocument source=requireSource(revision.getProjectId(),sourceId);return new DraftReviewSources.CheckedSource(source,sourceReader.archive(source));}).archive;for(String key:Arrays.asList("sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(!archived.path(key).equals(actual.path(key)))throw conflict("Template source or analysis changed; confirm a new source-bound graph.");sourceReader.archivedBytes(archived);}}
    private DraftTemplateGraphRevision workingRevision(String projectId){
        projects.require(projectId);DraftTemplateGraphHead head=head(projectId);
        DraftTemplateGraphRevision current=head.getCurrentRevisionId()==null?null:requireRevision(projectId,head.getCurrentRevisionId());
        if(head.getDraftRevisionId()!=null){
            Set<String> visited=new HashSet<>();DraftTemplateGraphRevision ancestor=current;
            while(ancestor!=null){
                if(!visited.add(ancestor.getId()))throw conflict("Graph revision history contains a cycle.");
                if(Objects.equals(ancestor.getId(),head.getDraftRevisionId()))return current;
                ancestor=ancestor.getParentRevisionId()==null?null:requireRevision(projectId,ancestor.getParentRevisionId());
            }
            return requireRevision(projectId,head.getDraftRevisionId());
        }
        if(current==null)throw new BizException(4011,"GRAPH_NOT_CONFIRMED");return current;
    }
    private DraftTemplateGraphRevision requireRevision(String projectId,String revisionId){return revisions.findByIdAndProjectId(revisionId,projectId).orElseThrow(()->new BizException(4011,"GRAPH_REVISION_NOT_FOUND"));}
    private SourceDocument requireSource(String projectId,Long sourceId){return sources.findDraftReviewSourceByProjectIdAndId(projectId,sourceId).orElseThrow(()->new BizException(4011,"SOURCE_NOT_FOUND"));}
    private DraftTemplateGraphHead head(String projectId){return heads.findById(projectId).orElseGet(()->{DraftTemplateGraphHead result=new DraftTemplateGraphHead();result.setProjectId(projectId);return result;});}
    private DraftTemplateGraphHead lockedHead(String projectId){lock(projectId);DraftTemplateGraphHead head=head(projectId);if(entityManager.contains(head))entityManager.refresh(head);return head;}
    private void lock(String projectId){if(entityManager.find(Project.class,projectId,LockModeType.PESSIMISTIC_WRITE)==null)throw new BizException(4004,"Project not found.");}
    private void expected(String actual,String expected){if(!Objects.equals(actual,expected))throw conflict("The displayed graph revision changed; reload before saving or adopting.");}
    private static BizException conflict(String message){return new BizException(4090,"GRAPH_REVISION_CONFLICT: "+message);}
    private List<SourceDocument> templateSources(String projectId){
        List<SourceDocument> result=new ArrayList<>();for(SourceDocument source:sources.findDraftReviewSourcesByProjectIdAndCategory(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE))if(!DraftTemplateRouting.isUnadopted(source))result.add(source);return result;
    }
    private List<Map<String,Object>> templateIdentities(String projectId){return JsonUtils.mapper().convertValue(sourceReader.sources(templateSources(projectId)),List.class);}
    private List<JsonNode> templateReadings(String projectId){List<JsonNode> result=new ArrayList<>();for(SourceDocument source:templateSources(projectId))result.add(sourceReader.readingCompact(source));return result;}
    private Map<String,Object> selected(List<Map<String,Object>> identities){Map<String,Object> result=new LinkedHashMap<>();for(Map<String,Object> source:identities){String key=string(source.get("fileKey"));if(Arrays.asList("NTT","SCT","SCC").contains(key)&&(!result.containsKey(key)||"docx".equals(source.get("format"))))result.put(key,source.get("id"));}return result;}
    private void requireReviewedSource(String projectId,Map<String,Object> graph,Map<String,Object> node){
        if(DraftFixedBaseline.exactSource(graph,node))return;
        if(DraftCatalogAlignment.standaloneManualInput(node))return;
        boolean supported=false;for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=asMap(raw);for(Object identityRaw:list(graph.get("sources"))){Map<String,Object> identity=asMap(identityRaw);if(!Objects.equals(string(anchor.get("docId")),string(identity.get("docId")))&&!Objects.equals(string(anchor.get("docId")),string(identity.get("id"))))continue;
                SourceDocument source=requireSource(projectId,((Number)identity.get("sourceDocumentId")).longValue());String quote=string(anchor.get("text"));
                if("bound".equals(asMap(anchor.get("nativeBinding")).get("status"))||"bound".equals(asMap(anchor.get("sourceObservationBinding")).get("status"))||quote!=null&&!quote.trim().isEmpty()&&source.getTextContent()!=null&&source.getTextContent().contains(quote))supported=true;
            }}if(!supported)throw new BizException(4007,"GRAPH_SOURCE_PASSAGE_REQUIRED: "+node.get("id"));
    }
    private void stampDefinitionReviews(Map<String,Object> graph) {
        Map<String,Map<String,Object>> nodes=new HashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(id(node),node);
        for(Map<String,Object> field:DraftReviewGraph.fields(graph))stampDefinitionReview(field,nodes);
    }
    private void stampDefinitionReview(Map<String,Object> field,Map<String,Map<String,Object>> nodes) {
        Map<String,Object> node=nodes.get(id(field)),review=node==null?Collections.emptyMap():asMap(node.get("definitionReview"));
        if(Boolean.TRUE.equals(review.get("confirmed"))&&review.get("basis")==null)node.put("definitionReview",map("confirmed",true,"basis",definitionBasis(field)));
        for(Object child:list(field.get("children")))stampDefinitionReview(asMap(child),nodes);
    }
    private void prepareSelectedDefinition(Map<String,Object> field,Map<String,Map<String,Object>> nodes){
        Map<String,Object> review=asMap(field.get("definitionReview")),alignment=asMap(field.get("catalogAlignment"));
        if(!Boolean.TRUE.equals(review.get("confirmed"))||!Objects.equals(review.get("basis"),definitionBasis(field))){
            if(!"matched".equals(alignment.get("status"))||!Boolean.TRUE.equals(alignment.get("confirmed")))throw new BizException(4007,"GRAPH_DEFINITION_REVIEW_REQUIRED: "+id(field)+" · 请确认变量定义或固定变量对应后保存。 / Confirm and save the variable definition or its baseline match.");
            Map<String,Object> node=nodes.get(id(field));if(node==null)throw new BizException(4007,"GRAPH_DEFINITION_SELECTION_INVALID");node.put("definitionReview",map("confirmed",true,"basis",null));
        }
        for(Object child:list(field.get("children")))prepareSelectedDefinition(asMap(child),nodes);
    }
    private void requireConfirmedDependencies(Map<String,Object> field,Map<String,Map<String,Object>> nodes,Map<String,Map<String,Object>> fields,Set<String> confirmed){
        Deque<String> queue=new ArrayDeque<>();Set<String> visited=new HashSet<>();queue.add(id(field));
        while(!queue.isEmpty()){String key=queue.removeFirst();if(!visited.add(key))continue;Map<String,Object> node=nodes.get(key);if(node==null)throw new BizException(4007,"GRAPH_DEFINITION_DEPENDENCY_REVIEW_REQUIRED: "+key);
            String root=key;Set<String> parents=new HashSet<>();while(nodes.containsKey(root)&&!JsonUtils.isBlankText(string(nodes.get(root).get("parentId")))&&parents.add(root))root=string(nodes.get(root).get("parentId"));
            if(fields.containsKey(root)&&!confirmed.contains(root))throw new BizException(4007,"GRAPH_DEFINITION_DEPENDENCY_REVIEW_REQUIRED: "+root);
            if(!fields.containsKey(root)&&!Arrays.asList("input","decision","content").contains(asMap(node.get("role")).get("kind"))&&(!"confirmed".equals(node.get("status"))||!DraftCatalogAlignment.reviewed(node)||DraftReviewGraph.ruleNeedsReview(node)))throw new BizException(4007,"GRAPH_DEFINITION_DEPENDENCY_REVIEW_REQUIRED: "+key);
            scopeReferences(node.get("correction"),queue);scopeReferences(node.get("applicability"),queue);scopeReferences(node.get("factProjectionReview"),queue);
        }
    }
    private String definitionBasis(Map<String,Object> field) {return "v1:"+DraftAdoption.hash(JsonUtils.write(definitionMeaning(field)));}
    private void preserveDefinitionGraphReview(String projectId,DraftTemplateGraphHead head,Map<String,Object> graph) {
        Map<String,Object> current=asMap(state(graph).get("definitionGraphReview"));if(current.isEmpty())return;
        String prior=head.getDraftRevisionId()!=null?head.getDraftRevisionId():head.getCurrentRevisionId();
        Map<String,Object> old=prior==null?Collections.emptyMap():asMap(state(JsonUtils.readMap(requireRevision(projectId,prior).getGraphJson())).get("definitionGraphReview"));
        if(old.isEmpty()||!DraftScopeObservationIntegrity.sameObservation(current,old))state(graph).remove("definitionGraphReview");
    }
    static int unresolvedGraphRelations(Map<String,Object> graph) {
        int count=0;Map<String,Map<String,Object>> nodes=new HashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(id(node),node);
        for(Map<String,Object> node:nodes.values())if(!"excluded".equals(node.get("status"))&&(DraftReviewGraph.ruleNeedsReview(node)||node.containsKey("applicability")&&!DraftReviewGraph.applicabilityBindingValid(graph,node,nodes)))count++;
        RelationReviewBasis basis=new RelationReviewBasis(graph);
        for(Object raw:list(state(graph).get("relationProposals")))if(!relationReviewCurrent(asMap(raw),basis))count++;
        return count;
    }
    static void reconcileRelationReviews(Map<String,Object> graph,Map<String,Object> prior) {
        List<Object> previous=list(state(prior).get("relationProposals")),proposed=list(state(graph).get("relationProposals"));RelationReviewBasis basis=new RelationReviewBasis(graph);
        if(proposed.size()<previous.size())throw new BizException(4007,"GRAPH_RELATION_PROPOSAL_CHANGED: Original relationship proposals must be retained.");
        for(int i=0;i<proposed.size();i++){
            Map<String,Object> row=asMap(proposed.get(i)),old=i<previous.size()?asMap(previous.get(i)):Collections.emptyMap(),review=asMap(row.get("review"));
            boolean same=!old.isEmpty()&&DraftScopeObservationIntegrity.sameObservation(relationIdentity(row),relationIdentity(old));Map<String,Object> stored=same?asMap(old.get("review")):Collections.emptyMap();
            if(i<previous.size()&&!same)throw new BizException(4007,"GRAPH_RELATION_PROPOSAL_CHANGED: Reload the unchanged original relationship proposals.");
            row.put("executable",false);
            if(!review.isEmpty()&&review.get("basis")==null){
                String status=relationReviewStatus(review),reason=string(review.get("reason"));
                if(!same||status==null||JsonUtils.isBlankText(reason))throw new BizException(4007,"GRAPH_RELATION_REVIEW_INVALID: Reload the saved proposal and provide a decision and reason.");
                String currentBasis=basis.of(row);if(currentBasis==null)throw new BizException(4007,"GRAPH_RELATION_REVIEW_BASIS_UNAVAILABLE");row.put("review",map("decision",review.get("decision"),"reason",reason.trim(),"basis",currentBasis,"status","reviewed","confirmedAt",Instant.now().toString()));row.put("status",status);
            }else if(!stored.isEmpty()){
                // Undo and historical drafts may carry an older stamp; only the server's saved decision owns it.
                Map<String,Object> retained=new LinkedHashMap<>(stored);row.put("review",retained);
                if(relationReviewStatus(retained)!=null&&!JsonUtils.isBlankText(string(retained.get("reason")))&&retained.get("basis")!=null&&"reviewed".equals(retained.get("status"))&&Objects.equals(retained.get("basis"),basis.of(row)))row.put("status",relationReviewStatus(retained));
                else{retained.put("status","needs_review");row.put("status","unresolved");}
            }else if(!review.isEmpty()||Arrays.asList("confirmed","rejected","excluded").contains(row.get("status"))){
                if(!review.isEmpty()){Map<String,Object> retained=new LinkedHashMap<>(review);retained.put("status","needs_review");row.put("review",retained);}row.put("status","unresolved");
            }
        }
    }
    private static String relationReviewStatus(Map<String,Object> review){String decision=string(review.get("decision"));return "implemented".equals(decision)?"confirmed":"rejected".equals(decision)?"rejected":"not_applicable".equals(decision)?"excluded":null;}
    private static boolean relationReviewCurrent(Map<String,Object> row,RelationReviewBasis basis){Map<String,Object> review=asMap(row.get("review"));String status=relationReviewStatus(review);return status!=null&&status.equals(row.get("status"))&&Boolean.FALSE.equals(row.get("executable"))&&"reviewed".equals(review.get("status"))&&!JsonUtils.isBlankText(string(review.get("reason")))&&review.get("basis")!=null&&Objects.equals(review.get("basis"),basis.of(row));}
    private static Map<String,Object> relationIdentity(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>(row);for(String key:Arrays.asList("status","review","executable"))result.remove(key);return result;}
    private static final class RelationReviewBasis {
        final Map<String,Map<String,Object>> nodes=new HashMap<>();final Map<String,List<String>> children=new HashMap<>();final Map<String,String> own=new HashMap<>(),closures=new HashMap<>(),sources=new HashMap<>();
        RelationReviewBasis(Map<String,Object> graph){
            for(Map<String,Object> node:DraftReviewGraph.nodes(graph)){nodes.put(id(node),node);String parent=string(node.get("parentId"));if(!JsonUtils.isBlankText(parent))children.computeIfAbsent(parent,key->new ArrayList<>()).add(id(node));}
            for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw),identity=new LinkedHashMap<>();for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId","fileKey","format","sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(source.containsKey(key))identity.put(key,source.get(key));identity.put("selected",asMap(graph.get("selectedDocuments")).get(string(source.get("fileKey"))));String hash=DraftScopeObservationIntegrity.reference(identity);for(String key:Arrays.asList("id","docId","sourceDocumentId"))if(source.get(key)!=null)sources.put(string(source.get(key)),hash);}
        }
        String of(Map<String,Object> row){
            String from=closure(string(row.get("fromId"))),to=closure(string(row.get("toId")));
            return DraftScopeObservationIntegrity.reference(map("proposal",relationIdentity(row),"from",from,"to",to));
        }
        String closure(String root){
            if(root==null)return null;if(closures.containsKey(root))return closures.get(root);SortedMap<String,Object> parts=new TreeMap<>();Deque<String> queue=new ArrayDeque<>();queue.add(root);
            while(!queue.isEmpty()){String key=queue.removeFirst();if(parts.containsKey(key))continue;Map<String,Object> node=nodes.get(key);if(node==null){parts.put(key,null);continue;}
                parts.put(key,own.computeIfAbsent(key,ignored->nodeBasis(node)));queue.addAll(children.getOrDefault(key,Collections.emptyList()));
                if(!JsonUtils.isBlankText(string(node.get("parentId"))))queue.add(string(node.get("parentId")));
                for(String property:Arrays.asList("relatedInputIds","replacedBy"))for(Object ref:list(node.get(property)))if(!JsonUtils.isBlankText(string(ref)))queue.add(string(ref));
                for(String property:Arrays.asList("correction","applicability","factProjectionReview","editTargets"))scopeReferences(node.get(property),queue);
                for(Object ref:list(asMap(node.get("correction")).get("inputIds")))if(!JsonUtils.isBlankText(string(ref)))queue.add(string(ref));
                for(Object raw:list(node.get("editTargets"))){String ref=string(asMap(raw).get("valueSourceId"));if(!JsonUtils.isBlankText(ref))queue.add(ref);}
            }
            String result=DraftScopeObservationIntegrity.reference(parts);closures.put(root,result);return result;
        }
        String nodeBasis(Map<String,Object> node){
            Map<String,Object> meaning=definitionMeaning(node);meaning.put("excluded","excluded".equals(node.get("status")));for(String key:Arrays.asList("definitionKind","replacedBy","correction","splitReview","editTargets"))if(node.containsKey(key))meaning.put(key,node.get(key));
            if(node.containsKey("factProjectionReview")){Map<String,Object> projection=new LinkedHashMap<>(asMap(node.get("factProjectionReview")));projection.remove("historicalAnswerResolution");meaning.put("factProjectionReview",projection);}
            SortedMap<String,Object> identities=new TreeMap<>();for(Object doc:list(node.get("docIds")))if(doc!=null)identities.put(string(doc),sources.get(string(doc)));
            for(String property:Arrays.asList("sourceAnchors","editTargets"))for(Object raw:list(node.get(property)))for(String key:Arrays.asList("docId","sourceId")){String doc=string(asMap(raw).get(key));if(doc!=null)identities.put(doc,sources.get(doc));}
            return DraftScopeObservationIntegrity.reference(map("definition",meaning,"sources",identities));
        }
    }
    private String definitionGraphFingerprint(Map<String,Object> graph) {
        List<Map<String,Object>> nodes=new ArrayList<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if(!"excluded".equals(node.get("status"))){
            Map<String,Object> meaning=definitionMeaning(node);for(String key:Arrays.asList("correction","splitReview","definitionReview","pdfReferenceAlignment"))if(node.containsKey(key))meaning.put(key,node.get(key));
            if(node.containsKey("factProjectionReview")){Map<String,Object> review=new LinkedHashMap<>(asMap(node.get("factProjectionReview")));review.remove("historicalAnswerResolution");meaning.put("factProjectionReview",review);}nodes.add(meaning);
        }nodes.sort(Comparator.comparing(node->String.valueOf(node.get("id"))));
        List<Object> relations=new ArrayList<>();for(Object raw:list(state(graph).get("relationProposals"))){Map<String,Object> row=asMap(raw),relation=new LinkedHashMap<>();for(String key:Arrays.asList("fromId","toId","kind","status","fromQuote","toQuote","reason"))if(row.containsKey(key))relation.put(key,row.get(key));relations.add(relation);}
        List<Object> sources=new ArrayList<>();for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw),identity=new LinkedHashMap<>();for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId","fileKey","format","sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(source.containsKey(key))identity.put(key,source.get(key));sources.add(identity);}
        return DraftScopeObservationIntegrity.reference(map("nodes",nodes,"relations",relations,"sources",sources,"selectedDocuments",graph.get("selectedDocuments")));
    }
    private Map<String,Object> definitionGraphReview(DraftTemplateGraphRevision revision,Map<String,Object> graph,boolean stale) {
        Map<String,Object> stored=asMap(state(graph).get("definitionGraphReview")),scopes=asMap(stored.get("fieldScopes"));
        String wholeFingerprint=Boolean.TRUE.equals(stored.get("confirmed"))?definitionGraphFingerprint(graph):null;
        boolean whole="CONFIRMED".equals(revision.getStatus())||wholeFingerprint!=null&&wholeFingerprint.equals(stored.get("fingerprint"));
        List<Object> confirmedFields=new ArrayList<>(),pendingFields=new ArrayList<>(),fixedCandidates=new ArrayList<>();
        for(Map<String,Object> field:DraftReviewGraph.fields(graph)){
            String key=id(field);boolean accepted=whole||scopes.containsKey(key)&&definitionReviewsCurrent(field)&&Objects.equals(scopes.get(key),definitionScopeFingerprint(graph,field));
            (accepted&&!stale?confirmedFields:pendingFields).add(key);
            if(!stale&&(DraftFixedBaseline.exactSource(graph,field)||DraftKnownRootReview.candidate(graph,field)))fixedCandidates.add(key);
        }
        boolean confirmed="CONFIRMED".equals(revision.getStatus())||Boolean.TRUE.equals(stored.get("confirmed"))||!scopes.isEmpty();
        return map("confirmed",confirmed,"valid",!stale&&(whole||!confirmedFields.isEmpty()),"confirmedFieldIds",confirmedFields,"pendingFieldIds",pendingFields,"fixedCandidateFieldIds",fixedCandidates,"baselineReuseSupported",templateBaselines!=null&&templateBaselineSources!=null,"completeBaselineReuseSupported",templateBaselines!=null&&templateBaselineSources!=null,"reason",stored.get("reason"),"confirmedAt",stored.get("confirmedAt"),"unresolvedRelationCount",unresolvedGraphRelations(graph));
    }
    private boolean definitionReviewsCurrent(Map<String,Object> field){Map<String,Object> review=asMap(field.get("definitionReview"));if(!Boolean.TRUE.equals(review.get("confirmed"))||!Objects.equals(review.get("basis"),definitionBasis(field)))return false;for(Object child:list(field.get("children")))if(!definitionReviewsCurrent(asMap(child)))return false;return true;}
    private String definitionScopeFingerprint(Map<String,Object> graph,Map<String,Object> field) {
        Map<String,Map<String,Object>> nodes=new HashMap<>(),projected=new HashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(id(node),node);indexProjectedField(field,projected);
        SortedMap<String,Object> parts=new TreeMap<>(),relations=new TreeMap<>(),sources=new TreeMap<>();Set<String> visited=new HashSet<>(),sourceIds=new HashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(id(field));
        while(!queue.isEmpty()){
            String key=queue.removeFirst();if(!visited.add(key))continue;Map<String,Object> node=projected.getOrDefault(key,nodes.get(key));if(node==null){parts.put(key,null);continue;}
            Map<String,Object> meaning=definitionMeaning(node);for(String property:Arrays.asList("correction","splitReview","factProjectionReview"))if(node.containsKey(property))meaning.put(property,node.get(property));parts.put(key,meaning);
            for(Object child:list(node.get("children")))queue.add(id(asMap(child)));
            for(Object ref:list(node.get("relatedInputIds")))if(!JsonUtils.isBlankText(string(ref)))queue.add(string(ref));
            scopeReferences(node.get("correction"),queue);scopeReferences(node.get("applicability"),queue);scopeReferences(node.get("factProjectionReview"),queue);
            for(Object doc:list(node.get("docIds")))if(doc!=null)sourceIds.add(String.valueOf(doc));for(Object raw:list(node.get("sourceAnchors"))){String doc=string(asMap(raw).get("docId"));if(doc!=null)sourceIds.add(doc);}
            for(Object raw:list(state(graph).get("relationProposals"))){Map<String,Object> relation=asMap(raw);String from=string(relation.get("fromId")),to=string(relation.get("toId"));if(!Objects.equals(key,from)&&!Objects.equals(key,to))continue;
                Map<String,Object> item=new LinkedHashMap<>();for(String property:Arrays.asList("fromId","toId","kind","status","fromQuote","toQuote","reason"))if(relation.containsKey(property))item.put(property,relation.get(property));relations.put(JsonUtils.write(item),item);if("confirmed".equals(relation.get("status"))){if(from!=null&&!from.isEmpty())queue.add(from);if(to!=null&&!to.isEmpty())queue.add(to);}
            }
        }
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);if(!sourceIds.contains(string(source.get("id")))&&!sourceIds.contains(string(source.get("docId"))))continue;Map<String,Object> identity=new LinkedHashMap<>();for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId","fileKey","format","sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(source.containsKey(key))identity.put(key,source.get(key));identity.put("selected",asMap(graph.get("selectedDocuments")).get(string(source.get("fileKey"))));sources.put(String.valueOf(source.get("id")),identity);}
        return DraftAdoption.hash(JsonUtils.write(map("nodes",parts,"relations",relations,"sources",sources)));
    }
    private void indexProjectedField(Map<String,Object> field,Map<String,Map<String,Object>> result){result.put(id(field),field);for(Object child:list(field.get("children")))indexProjectedField(asMap(child),result);}
    private static void scopeReferences(Object value,Deque<String> queue){if(value instanceof Map)for(Map.Entry<String,Object> entry:asMap(value).entrySet()){if(Arrays.asList("inputId","scopeId","rootId").contains(entry.getKey())&&entry.getValue() instanceof String&&!((String)entry.getValue()).isEmpty())queue.add((String)entry.getValue());else scopeReferences(entry.getValue(),queue);}else if(value instanceof List)for(Object item:list(value))scopeReferences(item,queue);}
    private static final class DefinitionCache {final Map<String,Map<String,Object>> graphs=new HashMap<>();final Map<String,Map<String,Map<String,Object>>> fields=new HashMap<>();final Map<String,String> bases=new HashMap<>(),scopes=new HashMap<>(),fieldHashes=new HashMap<>();}
    private String storedDefinitionBasis(String projectId,String revisionId,String fieldId,DefinitionCache cache){
        String cacheKey=revisionId+"\n"+fieldId;
        if(cache.bases.containsKey(cacheKey))return cache.bases.get(cacheKey);
        Map<String,Object> original=cache.graphs.computeIfAbsent(revisionId,key->revisions.findById(key).filter(old->projectId.equals(old.getProjectId())).map(old->JsonUtils.readMap(old.getGraphJson())).orElse(Collections.emptyMap()));
        Map<String,Map<String,Object>> fields=cache.fields.computeIfAbsent(revisionId,key->{Map<String,Map<String,Object>> result=new HashMap<>();if(!original.isEmpty())for(Map<String,Object> field:extractionFields(original))result.put(id(field),field);return result;});
        Map<String,Object> field=fields.get(fieldId);String basis=field==null?null:definitionBasis(field);cache.bases.put(cacheKey,basis);return basis;
    }
    private String storedDefinitionScope(String projectId,String revisionId,String fieldId,DefinitionCache cache){
        String key=revisionId+"\n"+fieldId;if(cache.scopes.containsKey(key))return cache.scopes.get(key);storedDefinitionBasis(projectId,revisionId,fieldId,cache);
        Map<String,Object> graph=cache.graphs.get(revisionId),field=cache.fields.getOrDefault(revisionId,Collections.emptyMap()).get(fieldId);String scope=field==null?null:definitionScopeFingerprint(graph,field);cache.scopes.put(key,scope);return scope;
    }
    private boolean candidateDefinitionCurrent(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> field,CandidateVO candidate,DefinitionCache cache){
        String key=revision.getId()+"\n"+id(field),fieldHash=cache.fieldHashes.computeIfAbsent(key,ignored->DraftReviewGraph.fieldHash(field));
        if(!Objects.equals(candidate.getFieldDefinitionHash(),fieldHash)||candidate.getGraphRevisionId()==null)return false;
        if(revision.getId().equals(candidate.getGraphRevisionId()))return true;
        Map<String,Object> graph=cache.graphs.computeIfAbsent(revision.getId(),ignored->JsonUtils.readMap(revision.getGraphJson()));String current=cache.scopes.computeIfAbsent(key,ignored->definitionScopeFingerprint(graph,field));String old=storedDefinitionScope(projectId,candidate.getGraphRevisionId(),id(field),cache);return old!=null&&Objects.equals(old,current);
    }
    private static Map<String,Object> definitionMeaning(Map<String,Object> field) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","options","fixedValue","optional","guidance","unit","definitionScope","catalogAlignment","baselineDefinition","editorialReview","definitionOrigin","semanticSourceQuote","relatedInputIds","docIds","sourceUnitIds","sourceAnchors"))result.put(key,field.get(key));
        if(field.containsKey("applicability"))result.put("applicability",field.get("applicability"));
        if(field.containsKey("pdfReferenceAlignment"))result.put("pdfReferenceAlignment",DraftCatalogAlignment.meaning(field).get("pdfReferenceAlignment"));
        List<Object> children=new ArrayList<>();for(Object child:list(field.get("children")))children.add(definitionMeaning(asMap(child)));result.put("children",children);return result;
    }
    private boolean definitionReady(String projectId,Map<String,Object> graph,Map<String,Object> field,boolean draft) {return definitionProblem(projectId,graph,field,draft)==null;}
    private String definitionProblem(String projectId,Map<String,Object> graph,Map<String,Object> field,boolean draft) {
        if(!DraftCatalogAlignment.reviewed(field))return "请先核对固定变量对应。 / Review the baseline match first.";
        Map<String,Object> editorial=asMap(field.get("editorialReview"));
        if(Boolean.TRUE.equals(editorial.get("confirmed"))&&"editorial".equals(editorial.get("kind")))return "此项已标记为指导或修改事项，不是业务变量。 / This item is guidance or an edit, not a business variable.";
        if("editorial".equals(asMap(field.get("editorialSuggestion")).get("kind"))&&!(Boolean.TRUE.equals(editorial.get("confirmed"))&&"business_value".equals(editorial.get("kind"))))return "请确认此项是业务变量还是指导／修改事项。 / Review whether this is a business variable or guidance/editing.";
        if(draft){Map<String,Object> review=asMap(field.get("definitionReview"));if(!Boolean.TRUE.equals(review.get("confirmed"))||!Objects.equals(review.get("basis"),definitionBasis(field)))return "变量定义尚未确认或已变化，请重新确认并保存。 / Confirm and save the current variable definition.";}
        if(!DraftCatalogAlignment.standaloneManualInput(field))for(Object raw:list(field.get("sourceAnchors"))){
            String anchorId=string(asMap(raw).get("docId"));boolean selected=false;
            for(Object identityRaw:list(graph.get("sources"))){Map<String,Object> identity=asMap(identityRaw);String sourceId=string(identity.get("id"));
                if(!Objects.equals(anchorId,sourceId)&&!Objects.equals(anchorId,string(identity.get("docId"))))continue;
                selected=Objects.equals(sourceId,string(asMap(graph.get("selectedDocuments")).get(string(identity.get("fileKey")))));break;}
            if(!selected)return "关联原文不属于当前选用的模板，请重新核对原件。 / The linked passage is not in the selected template.";
        }
        try{requireReviewedSource(projectId,graph,field);}catch(BizException missing){return "未能在当前原件中核实关联原文，请重新选词。 / The linked passage could not be verified against the current original.";}
        for(Object child:list(field.get("children"))){String problem=definitionProblem(projectId,graph,asMap(child),draft);if(problem!=null)return problem;}
        return null;
    }
    private List<Map<String,Object>> extractionFields(Map<String,Object> graph){List<Map<String,Object>> result=new ArrayList<>(DraftReviewGraph.fields(graph));result.add(identityField());return result;}
    private Map<String,Object> extractionPromptField(Map<String,Object> field){
        Map<String,Object> result=copy(field),alignment=asMap(result.get("catalogAlignment"));String selected=string(alignment.get("baselineKey"));
        if("matched".equals(alignment.get("status"))&&Boolean.TRUE.equals(alignment.get("confirmed")))for(Object raw:list(alignment.get("proposedMatches"))){Map<String,Object> proposal=asMap(raw);if(!Objects.equals(selected,string(proposal.get("baselineKey")))||!"conflict".equals(proposal.get("semanticStatus")))continue;
            Map<String,Object> guidance=new LinkedHashMap<>(asMap(result.get("guidance")));String previous=string(guidance.get("en"));
            guidance.put("en",(previous==null?"":previous+"\n")+"The original atomic question and fixed definition were flagged as semantically conflicting. A human selected this fixed meaning after review: "+string(alignment.get("reason"))+". Use the reviewed definition and this explanation; do not assume the original question is correct or adopt an answer.");result.put("guidance",guidance);break;}
        List<Object> children=new ArrayList<>();for(Object child:list(result.get("children")))children.add(extractionPromptField(asMap(child)));if(result.containsKey("children"))result.put("children",children);return result;
    }
    private Map<String,Object> identityField(){DraftBlueprint.InputSpec spec=DraftBlueprint.find("contractTitle");List<Object> children=new ArrayList<>();
        for(String key:Arrays.asList("number","title"))children.add(map("id",key,"type",map("kind","text"),"role",map("kind","input"),"optional",false,"children",Collections.emptyList()));
        return map("id","contractTitle","nameZh",spec.labelZhHans,"nameEn",spec.labelEn,"type",map("kind","record"),"role",map("kind","input"),"children",children,"optional",false);
    }
    private String normalizeCandidate(Map<String,Object> field,String value){return "contractTitle".equals(id(field))?DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),value):DraftReviewGraph.normalize(field,value);}
    private boolean confirmedWaterproofField(Map<String,Object> field){
        Map<String,Object> alignment=asMap(field.get("catalogAlignment"));String scope=string(alignment.get("scope")),key="otherWaterproofingSpecificationAreas";
        return key.equals(alignment.get("baselineKey"))&&key.equals(asMap(field.get("baselineDefinition")).get("baselineKey"))&&"matched".equals(alignment.get("status"))&&Boolean.TRUE.equals(alignment.get("confirmed"))&&(scope==null||scope.trim().isEmpty())&&"text".equals(asMap(field.get("type")).get("kind"));
    }
    private CandidateVO waterproofCandidate(DraftTemplateGraphRevision revision,Map<String,Object> field,JsonNode item,ExtractionPartVO part,ExtractionAttemptVO attempt,SourceDocument source,String originalSha,String runId,ExtractionDecisionVO decision){
        JsonNode raw=item.get("value");if(raw==null||!raw.isTextual()||JsonUtils.isBlankText(raw.asText())){decision.getCodes().add("invalid_type");return null;}
        com.fasterxml.jackson.databind.node.ObjectNode fixed=item.deepCopy();fixed.put("key","otherWaterproofingSpecificationAreas");
        ExtractionDecisionVO checked=extractionHarness.reviewCandidate(part,attempt,decision.getItemIndex(),fixed,source.getTextContent());decision.getCodes().addAll(checked.getCodes());
        String quote=checked.getSourceQuote(),normalized=checked.getNormalizedValue();boolean complete="accepted".equals(checked.getStatus())&&quote!=null&&normalized!=null&&part.getContext().getSourceText().contains(quote)&&source.getTextContent().contains(quote)&&completeWaterproofScope(source.getTextContent(),part.getContext().getSourceText(),normalized,quote);
        if(!complete){quote=decision.getSourceQuote();normalized=raw.asText();if(!decision.getCodes().contains("waterproof_scope_unproven"))decision.getCodes().add("waterproof_scope_unproven");}
        CandidateVO candidate=new CandidateVO(normalized,source.getId(),source.getFileName(),DraftAdoption.sourceHash(source),quote,decision.getReason(),decision.getConfidence());candidate.setOriginalSha256(originalSha);candidate.setExtractionRunId(runId);candidate.setGraphRevisionId(revision.getId());candidate.setFieldDefinitionHash(DraftReviewGraph.fieldHash(field));candidate.setCompletenessStatus(complete?"complete":"needs_review");if(!complete)candidate.setCompletenessIssues(new ArrayList<>(decision.getCodes()));
        decision.setSourceQuote(quote);decision.setNormalizedValue(normalized);decision.setStatus(complete?"accepted":"candidate_needs_review");return candidate;
    }
    private void recallWaterproof(DraftTemplateGraphRevision revision,Map<String,Object> field,ExtractionPartVO part,SourceDocument source,String originalSha,String runId,String system,String user,ExtractionMonitor monitor,ExtractTraceVO trace,Map<String,List<CandidateVO>> found,CandidateVO initial){
        String key=id(field),recallSystem=system+" Reviewed fields: "+JsonUtils.write(DraftReviewGraph.extractionDefinitions(Collections.singletonList(field)))+" Return only this field; copy every original numbered area and continuation in order, with its complete original quotation. Never invent missing text.";
        String recallUser=user+"\nThe previous response did not establish the full ordered source scope. Recheck this field against the supplied original context; omit it if unsupported.";
        ExtractionAttemptVO recall=new ExtractionAttemptVO(part.getAttempts().size(),"waterproof-bounded-recall",null,recallUser,null,"running",null,part.getContext());part.getAttempts().add(recall);
        if(recallSystem.length()+recallUser.length()>64000){recall.setStatus("unexecuted");recall.setErrorCode("request_budget_exceeded");return;}
        if(!monitor.beforeCall(recallSystem,recallUser,source.getFileName())){recall.setStatus("unexecuted");recall.setErrorCode("cancelled_or_budget_reached");return;}
        try{
            String follow=ai.complete(recallSystem,recallUser);monitor.completed(follow);recall.setRawResponse(follow);trace.getRawResponses().add(follow);JsonNode additional;
            try{additional=JsonUtils.parse(JsonUtils.extractJson(follow));if(!additional.isArray())throw new IllegalArgumentException("Expected array");recall.setStatus("completed");}catch(RuntimeException incomplete){additional=DraftJsonItems.completePrefix(follow);recall.setStatus("partial");recall.setErrorCode("incomplete_json");}
            int followIndex=0,produced=0;for(JsonNode row:additional){if(!key.equals(row.path("key").asText()))continue;String quote=row.path("sourceQuote").asText();JsonNode value=row.get("value");Double confidence=row.path("confidence").isNumber()?row.path("confidence").asDouble():null;
                ExtractionDecisionVO decision=new ExtractionDecisionVO(part.getPartId(),recall.getAttemptIndex(),followIndex++,key,value,null,quote,row.path("reason").asText(),confidence,"rejected",new ArrayList<>());trace.getDecisions().add(decision);
                if(value==null||!value.isTextual()||JsonUtils.isBlankText(quote)||!part.getContext().getSourceText().contains(quote)||!source.getTextContent().contains(quote)||confidence==null||!Double.isFinite(confidence)||confidence<0.70||confidence>1){decision.getCodes().add("waterproof_recall_invalid");continue;}
                CandidateVO candidate=waterproofCandidate(revision,field,row,part,recall,source,originalSha,runId,decision);if(candidate!=null){produced++;if(initial==null||!JsonUtils.write(candidate).equals(JsonUtils.write(initial)))found.computeIfAbsent(key,k->new ArrayList<>()).add(candidate);}
            }
            if(initial==null&&produced==0){ExtractionDecisionVO missing=new ExtractionDecisionVO(part.getPartId(),recall.getAttemptIndex(),followIndex,key,null,null,null,"The source has an identifiable ordered scope, but no candidate was returned; manual review is required.",null,"candidate_needs_review",new ArrayList<>());missing.getCodes().add("waterproof_scope_unanswered");trace.getDecisions().add(missing);}
        }catch(RuntimeException failure){monitor.failed(failure);recall.setStatus("failed");recall.setErrorCode("model_call_failed");}
    }
    private boolean hasWaterproofScope(String original,String supplied){
        int matches=0;for(DraftOrderedSourceLists.Unit unit:DraftOrderedSourceLists.units(original)){String heading=original.substring(unit.start,unit.listStart).toLowerCase(Locale.ROOT),scope=original.substring(unit.start,unit.end);int terms=(heading.contains("waterproof")?1:0)+(heading.contains("specification")?1:0)+(heading.contains("area")?1:0);if(unit.validSequence&&!unit.foreignProject&&terms>=2&&supplied.contains(scope))matches++;}return matches==1;
    }
    private boolean completeWaterproofScope(String original,String supplied,String value,String quote){
        int matches=0;for(DraftOrderedSourceLists.Unit unit:DraftOrderedSourceLists.units(original)){
            String heading=original.substring(unit.start,unit.listStart).toLowerCase(Locale.ROOT),sourceList=original.substring(unit.listStart,unit.listEnd).trim(),scope=original.substring(unit.start,unit.end);
            int terms=(heading.contains("waterproof")?1:0)+(heading.contains("specification")?1:0)+(heading.contains("area")?1:0);
            if(unit.validSequence&&!unit.foreignProject&&terms>=2&&value.equals(sourceList)&&quote.contains(sourceList)&&supplied.contains(scope))matches++;
        }return matches==1;
    }
    private Map<String,String> confirmedBillColumns(Map<String,Object> field){
        Map<String,Object> alignment=asMap(field.get("catalogAlignment"));String scope=string(alignment.get("scope"));
        if(!"billNos".equals(alignment.get("baselineKey"))||!"billNos".equals(asMap(field.get("baselineDefinition")).get("baselineKey"))||!"matched".equals(alignment.get("status"))||!Boolean.TRUE.equals(alignment.get("confirmed"))||scope!=null&&!scope.trim().isEmpty())return null;
        Map<String,String> columns=new LinkedHashMap<>();
        for(Object raw:list(field.get("children"))){Map<String,Object> child=asMap(raw),mapping=asMap(child.get("catalogAlignment"));String fixed=string(mapping.get("baselineKey"));
            if(!"matched".equals(mapping.get("status"))||!Boolean.TRUE.equals(mapping.get("confirmed"))||fixed==null||!fixed.equals(asMap(child.get("baselineDefinition")).get("baselineKey"))||!fixed.startsWith("billNos.")||columns.put(id(child),fixed.substring(8))!=null)return null;}
        return !columns.containsValue("id")&&columns.values().containsAll(Arrays.asList("number","description","type"))?columns:null;
    }
    private List<Object> mapBillRows(JsonNode rows,Map<String,String> columns,boolean toFixed){
        if(!rows.isArray())throw new BizException(4007,"GRAPH_BILL_ROWS_REQUIRED");List<Object> result=new ArrayList<>();
        for(JsonNode row:rows){if(!row.isObject())throw new BizException(4007,"GRAPH_BILL_ROW_REQUIRED");Map<String,Object> mapped=new LinkedHashMap<>();Iterator<Map.Entry<String,JsonNode>> values=row.fields();
            while(values.hasNext()){Map.Entry<String,JsonNode> cell=values.next();String key=cell.getKey(),target=null;
                if(toFixed){if("_itemId".equals(key))target="id";else target=columns.get(key);}
                else if("id".equals(key))target="_itemId";else for(Map.Entry<String,String> column:columns.entrySet())if(column.getValue().equals(key)){target=column.getKey();break;}
                if(target==null)throw new BizException(4007,"GRAPH_BILL_COLUMN_UNMAPPED");mapped.put(target,JsonUtils.mapper().convertValue(cell.getValue(),Object.class));}
            result.add(mapped);}
        return result;
    }
    private CompositeBillSuggestion compositeBill(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph,Map<String,Object> field,List<CandidateVO> candidates,Map<String,Object> trace){
        Map<String,String> columns=confirmedBillColumns(field);if(columns==null||candidates.size()<2||trace.isEmpty()||!definitionReady(projectId,graph,field,"DRAFT".equals(revision.getStatus())))return null;
        Set<Long> sourceIds=new HashSet<>();for(CandidateVO candidate:candidates)if(candidate.getSourceDocumentId()!=null)sourceIds.add(candidate.getSourceDocumentId());if(sourceIds.size()<2)return null;
        DefinitionCache definitions=new DefinitionCache();Map<Long,SourceDocument> originals=new HashMap<>();Map<Long,JsonNode> manifests=new HashMap<>();List<CandidateVO> fixed=new ArrayList<>();List<Integer> indexes=new ArrayList<>();
        for(int index=0;index<candidates.size();index++){
            CandidateVO candidate=candidates.get(index);if(!candidateDefinitionCurrent(projectId,revision,field,candidate,definitions)||candidate.getSourceDocumentId()==null)return null;
            SourceDocument source=originals.computeIfAbsent(candidate.getSourceDocumentId(),key->requireSource(projectId,key));JsonNode manifest=manifests.computeIfAbsent(candidate.getSourceDocumentId(),key->sourceReader.archive(source));String sourceText=source.getTextContent(),quote=candidate.getSourceQuote();
            if(!SourceDocument.CATEGORY_PROJECT_INPUT.equals(source.getCategory())||!Objects.equals(candidate.getSourceHash(),DraftAdoption.sourceHash(source))||!Objects.equals(candidate.getOriginalSha256(),manifest.path("sourceSha256").asText())||JsonUtils.isBlankText(quote)||sourceText==null||!sourceText.contains(quote))return null;
            ExtractionContextVO context=new ExtractionContextVO("composite-proof",Collections.singletonList("billNos"),0,sourceText.length(),sourceText,"complete");
            ExtractionPartVO part=new ExtractionPartVO("composite:"+index,source.getId(),source.getFileName(),candidate.getSourceHash(),index,sourceText,new ArrayList<>());part.setContext(context);
            ExtractionAttemptVO attempt=new ExtractionAttemptVO(0,"composite-proof",null,null,null,"completed",null,context);
            Map<String,Object> fixedItem=map("key","billNos","value",mapBillRows(JsonUtils.parse(candidate.getValue()),columns,true),"sourceQuote",quote,"reason",candidate.getReason(),"confidence",candidate.getConfidence());
            ExtractionDecisionVO decision=extractionHarness.reviewCandidate(part,attempt,index,JsonUtils.mapper().valueToTree(fixedItem),sourceText);
            if(!"accepted".equals(decision.getStatus())||!Objects.equals(quote,decision.getSourceQuote()))return null;
            String returned=DraftReviewGraph.normalize(field,JsonUtils.write(mapBillRows(JsonUtils.parse(decision.getNormalizedValue()),columns,false)));
            if(!JsonUtils.parse(returned).equals(JsonUtils.parse(candidate.getValue())))return null;
            CandidateVO fixedCandidate=new CandidateVO();fixedCandidate.setValue(decision.getNormalizedValue());fixed.add(fixedCandidate);indexes.add(index);
        }
        String merged=DraftBillCandidates.merge(DraftBlueprint.find("billNos"),fixed);if(merged==null||merged.isEmpty())return null;
        String value=DraftReviewGraph.normalize(field,JsonUtils.write(mapBillRows(JsonUtils.parse(merged),columns,false)));boolean partial=!"completed".equals(trace.get("status"));
        String mergeHash=hash(map("revision",revision.getId(),"definition",definitionBasis(field),"runId",trace.get("runId"),"evidence",trace.get("evidenceRevision"),"members",candidates,"value",value,"partial",partial));
        return new CompositeBillSuggestion(value,string(trace.get("runId")),indexes,mergeHash,partial);
    }
    private String normalizeCandidate(Map<String,Object> field,String value,String quote,String supplied,String original){
        if("contractTitle".equals(id(field)))return DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),value);
        Map<String,Object> alignment=asMap(field.get("catalogAlignment"));String key=string(alignment.get("baselineKey"));
        String scope=string(alignment.get("scope"));String kind=string(asMap(field.get("type")).get("kind"));
        if("matched".equals(alignment.get("status"))&&Boolean.TRUE.equals(alignment.get("confirmed"))&&(scope==null||scope.trim().isEmpty())&&key!=null&&key.equals(asMap(field.get("baselineDefinition")).get("baselineKey"))&&!key.contains(".")&&Arrays.asList("text","date","number","boolean","choice").contains(kind)) {
            DraftBlueprint.InputSpec spec=DraftBlueprint.find(key);
            if(spec!=null){if(key.startsWith("projectArchitect")&&!DraftArchitectContactEvidence.supported(key,quote,supplied,original))throw new BizException(4007,"FIXED_SOURCE_CONTEXT_UNSUPPORTED");value=DraftInputRules.normalizeSuggestion(spec,value,quote,supplied,original);}
        }
        return normalizeCandidate(field,value);
    }
    private void ensureIdentity(String projectId){if(variables.findByProjectIdAndVarKey(projectId,"contractTitle").isPresent())return;DraftBlueprint.InputSpec spec=DraftBlueprint.find("contractTitle");DraftVariable variable=new DraftVariable();variable.setProjectId(projectId);variable.setVarKey(spec.key);variable.setScope("INPUT");variable.setKind(spec.kind);variable.setValueText("");variable.setCandidatesJson("[]");variable.setConfirmed(false);variable.setManuallyEdited(false);variable.setReviewRequired(false);variable.setSortOrder(-1);variable.setLabelZhHans(spec.labelZhHans);variable.setLabelZhHant(spec.labelZhHant);variable.setLabelEn(spec.labelEn);variable.setUpdatedAt(Instant.now());variables.save(variable);}
    private void ensureInputs(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){ensureIdentity(projectId);DefinitionCache definitionCache=new DefinitionCache();int order=0;for(Map<String,Object> field:allFields(graph)){String key=id(field),definition=DraftReviewGraph.fieldHash(field);DraftVariable variable=variables.findByProjectIdAndVarKey(projectId,key).orElse(null);DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,key)).orElse(null);
            if(variable!=null&&binding!=null&&!"targetOverrides".equals(key)&&(Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited()))&&binding.getAdoptedRevisionId()!=null&&!Objects.equals(binding.getAdoptedRevisionId(),revision.getId())){
                String old=storedDefinitionBasis(projectId,binding.getAdoptedRevisionId(),key,definitionCache);String current=definitionCache.bases.computeIfAbsent(revision.getId()+"\n"+key,ignored->definitionBasis(field));
                if((old==null||!Objects.equals(old,current))&&!Boolean.TRUE.equals(variable.getReviewRequired())){variable.setReviewRequired(true);variables.save(variable);}
            }
            if(variable!=null&&binding!=null&&Objects.equals(binding.getDefinitionSha256(),definition)
                &&Objects.equals(variable.getScope(),"INPUT")&&Objects.equals(variable.getKind(),String.valueOf(asMap(field.get("type")).get("kind")))
                &&Objects.equals(variable.getLabelZhHans(),string(field.get("nameZh")))&&Objects.equals(variable.getLabelZhHant(),string(field.get("nameZh")))
                &&Objects.equals(variable.getLabelEn(),string(field.get("nameEn")))&&Objects.equals(variable.getSortOrder(),order)
                &&Objects.equals(variable.getOptionsJson(),JsonUtils.write(field.get("options")))){order++;continue;}
            if(variable==null){variable=new DraftVariable();variable.setProjectId(projectId);variable.setVarKey(key);variable.setValueText("");variable.setCandidatesJson("[]");}
            if(binding==null){binding=new DraftGraphInputBinding();binding.setId(bindingId(projectId,key));binding.setProjectId(projectId);binding.setVarKey(key);if(!JsonUtils.isBlankText(variable.getValueText()))variable.setReviewRequired(true);}
            if(!Objects.equals(binding.getDefinitionSha256(),definition)&&!JsonUtils.isBlankText(variable.getValueText()))variable.setReviewRequired(true);
            binding.setDefinitionSha256(definition);bindings.save(binding);variable.setScope("INPUT");variable.setKind(String.valueOf(asMap(field.get("type")).get("kind")));variable.setLabelZhHans(string(field.get("nameZh")));variable.setLabelZhHant(string(field.get("nameZh")));variable.setLabelEn(string(field.get("nameEn")));variable.setSortOrder(order++);variable.setOptionsJson(JsonUtils.write(field.get("options")));variable.setUpdatedAt(Instant.now());variables.save(variable);
        }}
    private VariableVO variableVO(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> field){return variableVO(projectId,revision,field,stale(revision),new DefinitionCache());}
    private VariableVO variableVO(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> field,boolean sourceStale,DefinitionCache candidateGraphs){
        DraftVariable variable=variable(projectId,id(field));boolean adopted=Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited());
        boolean review=Boolean.TRUE.equals(variable.getReviewRequired())||sourceStale;
        DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,id(field))).orElse(null);
        if(adopted&&(binding==null||!Objects.equals(binding.getAdoptedDefinitionSha256(),DraftReviewGraph.fieldHash(field))||"targetOverrides".equals(id(field))&&!Objects.equals(binding.getAdoptedRevisionId(),revision.getId())))review=true;
        if(adopted&&"targetOverrides".equals(id(field))&&overridesNeedReview(revision,JsonUtils.readMap(revision.getGraphJson()),variable.getValueText()))review=true;
        if(adopted)try{for(CandidateVO candidate:DraftAdoption.adoptedSources(variable))verifyCandidateSource(projectId,candidate);}catch(RuntimeException changed){review=true;}
        List<CandidateVO> candidates=DraftAdoption.candidates(variable);Set<String> values=new HashSet<>();for(CandidateVO candidate:candidates){values.add(candidate.getValue());if(!adopted&&"needs_review".equals(candidate.getCompletenessStatus())||!candidateDefinitionCurrent(projectId,revision,field,candidate,candidateGraphs))review=true;}
        String adoptionState=review?"needs_review":adopted?"adopted":JsonUtils.isBlankText(variable.getValueText())?(values.size()>1?"conflict":candidates.isEmpty()?"missing":"suggested"):"suggested";
        Map<String,Object> result=map("key",id(field),"scope","INPUT","group","reviewed","label",LocalizedText.of(variable.getLabelZhHans(),variable.getLabelZhHant(),variable.getLabelEn()),"kind",variable.getKind(),"value",variable.getValueText(),"confirmed",variable.getConfirmed(),"source",variable.getSourceRef(),"note",variable.getNoteText(),"manuallyEdited",variable.getManuallyEdited(),"reviewRequired",review,"candidates",candidates,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field),"adoptionState",adoptionState);
        if(binding!=null&&"candidate_merge".equals(binding.getAdoptionOrigin()))result.put("adoptedSourceCount",DraftAdoption.adoptedSources(variable).size());
        return JsonUtils.mapper().convertValue(result,VariableVO.class);
    }
    private DraftVariable variable(String projectId,String key){return variables.findByProjectIdAndVarKey(projectId,key).orElseThrow(()->new BizException(4006,"Input not found."));}
    private DraftGraphInputBinding binding(String projectId,String key){return bindings.findById(bindingId(projectId,key)).orElseThrow(()->new BizException(4006,"Input binding not found."));}
    private String bindingId(String projectId,String key){return DraftAdoption.hash(projectId+"\n"+key);}
    private List<Map<String,Object>> allFields(Map<String,Object> graph){List<Map<String,Object>> result=new ArrayList<>(DraftReviewGraph.fields(graph));Map<String,Object> override=overrideField(graph);if(override!=null)result.add(override);return result;}
    private Map<String,Object> overrideField(Map<String,Object> graph){boolean targets=false;for(Object node:list(state(graph).get("variables")))if(!list(asMap(node).get("editTargets")).isEmpty())targets=true;if(!targets)return null;return map("id","targetOverrides","nameZh","精确目标改文","nameEn","Exact target amendments","type",map("kind","text"),"role",map("kind","system"),"questionEn",hash(graph));}
    private Map<String,Map<String,Object>> currentTargets(Map<String,Object> graph){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Object raw:list(DraftReviewGraph.plan(graph,Collections.emptyMap()).get("actions"))){Map<String,Object> target=asMap(raw);result.put(string(target.get("id")),target);}return result;}
    private Map<String,Object> targetRevision(DraftTemplateGraphRevision revision,Map<String,Object> target){return map("graphRevisionId",revision.getId(),"sourceSha256",asMap(target.get("nativeBinding")).get("sourceSha256"));}
    private Map<String,Object> targetDecision(Map<String,Object> entry){Map<String,Object> result=new LinkedHashMap<>(entry);result.remove("sourceRevision");return result;}
    private String normalizeOverrides(DraftTemplateGraphRevision revision,Map<String,Object> graph,String raw,String previous){
        if(JsonUtils.isBlankText(raw))return "[]";JsonNode json=JsonUtils.parse(raw);if(!json.isArray())throw new BizException(4007,"TARGET_OVERRIDES_ARRAY_REQUIRED");
        Map<String,Map<String,Object>> allowed=currentTargets(graph),old=new HashMap<>();if(!JsonUtils.isBlankText(previous))for(Object item:list(DraftBusinessRules.parse(previous))){Map<String,Object> entry=asMap(item);old.put(string(entry.get("actionId")),entry);}
        List<Object> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(JsonNode entry:json){if(!entry.isObject())throw new BizException(4007,"TARGET_OVERRIDE_OBJECT_REQUIRED");Map<String,Object> override=JsonUtils.mapper().convertValue(entry,Map.class);String id=string(override.get("actionId")),action=string(override.get("action"));Map<String,Object> target=allowed.get(id),existing=old.get(id);
            if(!seen.add(id)||!Arrays.asList("retain","amend","delete","not_used").contains(action))throw new BizException(4007,"TARGET_OVERRIDE_NOT_ALLOWED: "+id);
            if("amend".equals(action)&&(!(override.get("value") instanceof String)||JsonUtils.isBlankText((String)override.get("value"))))throw new BizException(4007,"TARGET_OVERRIDE_TEXT_REQUIRED: "+id);
            // Unchanged entries retain their evidence; omitting the binding explicitly re-adopts only this entry.
            if(override.get("sourceRevision")!=null&&existing!=null&&targetDecision(existing).equals(targetDecision(override))){if(!Objects.equals(override.get("sourceRevision"),existing.get("sourceRevision")))throw conflict("Target source binding is server-managed; re-adopt this target explicitly.");override.put("sourceRevision",existing.get("sourceRevision"));}
            else{
                if(target==null)throw new BizException(4007,"TARGET_OVERRIDE_NOT_ALLOWED: "+id);
                if(!"bound".equals(asMap(target.get("nativeBinding")).get("status")))throw new BizException(4012,"TARGET_OVERRIDE_SOURCE_UNBOUND: "+id);
                if("amend".equals(action)&&!canAmendTarget(target))throw new BizException(4012,"GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+id);
                if(Arrays.asList("delete","not_used").contains(action)&&DraftReviewGraph.hasInsertionPoint(target))throw new BizException(4012,"GRAPH_INSERTION_ACTION_MISMATCH: "+id);
                override.put("sourceRevision",targetRevision(revision,target));
            }
            result.add(override);
        }return JsonUtils.write(result);
    }
    private boolean canAmendTarget(Map<String,Object> action){Map<String,Object> target=asMap(action.get("target"));if(target.isEmpty())target=action;return "insert".equals(target.get("action"))?DraftReviewGraph.hasInsertionPoint(target):DraftReviewGraph.hasReplacementPlacement(target);}
    private boolean overridesNeedReview(DraftTemplateGraphRevision revision,Map<String,Object> graph,String raw){try{Map<String,Map<String,Object>> allowed=currentTargets(graph);for(Object item:list(DraftBusinessRules.parse(raw))){Map<String,Object> entry=asMap(item),target=allowed.get(string(entry.get("actionId")));if(target==null||!targetRevision(revision,target).equals(entry.get("sourceRevision"))||"amend".equals(entry.get("action"))&&!canAmendTarget(target)||Arrays.asList("delete","not_used").contains(entry.get("action"))&&DraftReviewGraph.hasInsertionPoint(target))return true;}return false;}catch(RuntimeException bad){return true;}}
    private void applyOverrides(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> patches){
        if(overrideField(graph)==null)return;DraftVariable variable=variables.findByProjectIdAndVarKey(projectId,"targetOverrides").orElse(null);String previous=variable==null?null:variable.getValueText(),raw=previous;
        boolean preview=patches!=null&&patches.containsKey("targetOverrides");if(preview){raw=normalizeOverrides(revision,graph,patches.get("targetOverrides"),previous);asMap(plan.get("inputValues")).put("targetOverrides",DraftBusinessRules.parse(raw));}else if(variable==null||!Boolean.TRUE.equals(variable.getConfirmed()))return;
        if(JsonUtils.isBlankText(raw)||"[]".equals(raw))return;
        try{Set<String> overridden=new HashSet<>();Map<String,Map<String,Object>> allowed=currentTargets(graph);for(Object item:list(DraftBusinessRules.parse(raw))){Map<String,Object> override=asMap(item);String id=string(override.get("actionId"));Map<String,Object> current=allowed.get(id);
                if(current==null||!targetRevision(revision,current).equals(override.get("sourceRevision"))){addIssue(plan,"target_override_review:"+id,"Saved target amendment requires review against this graph revision: "+id);for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(Objects.equals(action.get("id"),id))action.put("adoptionState","needs_review");}continue;}
                if("amend".equals(override.get("action"))&&!canAmendTarget(current)||Arrays.asList("delete","not_used").contains(override.get("action"))&&DraftReviewGraph.hasInsertionPoint(current)){addIssue(plan,"target_override_placement:"+id,"Choose the exact insertion point or replacement span for this saved treatment: "+id);for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(Objects.equals(action.get("id"),id)){action.put("action","pending");action.put("adoptionState","needs_review");}}continue;}
                for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(!Objects.equals(action.get("id"),id))continue;if(Boolean.TRUE.equals(action.get("historicalProjectionBlocked")))continue;action.put("action","amend".equals(override.get("action"))&&DraftReviewGraph.hasInsertionPoint(current)?"insert":override.get("action"));action.put("value","amend".equals(override.get("action"))?override.get("value"):"not_used".equals(override.get("action"))?"Not used":"delete".equals(override.get("action"))?"":null);action.put("adoptionOrigin","manual_target");overridden.add(id);}}
            List<Object> unresolved=new ArrayList<>();for(Object issue:list(plan.get("unresolved"))){Map<String,Object> value=asMap(issue);boolean resolved=false;for(Object key:list(value.get("inputKeys")))if(overridden.contains(String.valueOf(key)))resolved=true;if(!resolved)unresolved.add(issue);}plan.put("unresolved",unresolved);
        }catch(RuntimeException invalid){addIssue(plan,"target_override_invalid",invalid.getMessage());}
    }
    private Map<String,Object> historicalProjectionValues(String projectId,Map<String,Object> graph){
        Map<String,Map<String,Object>> projected=new LinkedHashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if("derived".equals(asMap(node.get("role")).get("kind"))&&node.containsKey("factProjectionReview"))projected.put(id(node),node);
        Map<String,Object> result=new LinkedHashMap<>();if(projected.isEmpty())return result;Map<Long,Map<String,Object>> currentSources=new HashMap<>();
        for(DraftVariable variable:variables.findByProjectIdOrderBySortOrderAsc(projectId)){
            Map<String,Object> node=projected.get(variable.getVarKey());if(node==null||!Boolean.TRUE.equals(variable.getConfirmed())&&!Boolean.TRUE.equals(variable.getManuallyEdited()))continue;
            Map<String,Object> original=asMap(asMap(node.get("factProjectionReview")).get("original"));Object value=decode(original,variable.getValueText());
            List<Object> details=new ArrayList<>();for(CandidateVO source:DraftAdoption.adoptedSources(variable))details.add(historicalSource(projectId,source,currentSources));
            String status=Boolean.TRUE.equals(variable.getConfirmed())?"confirmed":"manual";
            String fingerprint=hash(map("nodeId",id(node),"originalDefinition",original,"value",value,"status",status,"confirmed",variable.getConfirmed(),"manuallyEdited",variable.getManuallyEdited(),"reviewRequired",variable.getReviewRequired(),"sourceRef",variable.getSourceRef(),"sources",details));
            result.put(variable.getVarKey(),map("value",value,"status",status,"confirmed",variable.getConfirmed(),"manuallyEdited",variable.getManuallyEdited(),"reviewRequired",variable.getReviewRequired(),"sourceRef",variable.getSourceRef(),"adoptedSources",DraftAdoption.adoptedSources(variable),"sources",details,"evidenceFingerprint",fingerprint));
        }return result;
    }
    private Map<String,Object> historicalSource(String projectId,CandidateVO candidate,Map<Long,Map<String,Object>> cache){
        Long sourceId=candidate.getSourceDocumentId();Map<String,Object> current=sourceId==null?Collections.emptyMap():cache.computeIfAbsent(sourceId,key->{
            try{SourceDocument source=requireSource(projectId,key);JsonNode metadata=sourceReader.sources(Collections.singletonList(source)).path(0);
                return map("category",source.getCategory(),"fileName",source.getFileName(),"parseStatus",source.getParseStatus(),"sourceHash",DraftAdoption.sourceHash(source),"sourceSha256",metadata.path("sourceSha256").isTextual()?metadata.path("sourceSha256").asText():null,"sourceText",source.getTextContent(),"availability",metadata.path("evidenceStatus").asText());
            }catch(RuntimeException unavailable){return Collections.emptyMap();}});
        String text=string(current.get("sourceText"));boolean accessible=!current.isEmpty()&&current.get("sourceSha256")!=null;
        boolean valid=accessible&&SourceDocument.CATEGORY_PROJECT_INPUT.equals(current.get("category"))&&Objects.equals(candidate.getSourceHash(),current.get("sourceHash"))&&Objects.equals(candidate.getOriginalSha256(),current.get("sourceSha256"))&&!JsonUtils.isBlankText(candidate.getSourceQuote())&&text!=null&&text.contains(candidate.getSourceQuote());
        return map("sourceDocumentId",sourceId,"fileName",candidate.getFileName(),"sourceHash",candidate.getSourceHash(),"originalSha256",candidate.getOriginalSha256(),"sourceQuote",candidate.getSourceQuote(),"status",valid?"bound":accessible?"changed":"unavailable","currentSourceHash",current.get("sourceHash"),"currentSourceSha256",current.get("sourceSha256"),"currentFileName",current.get("fileName"),"currentParseStatus",current.get("parseStatus"),"currentAvailability",current.get("availability"));
    }
    public Map<String,Object> historicalProjectionSources(String projectId,String revisionId,String nodeId){
        DraftTemplateGraphRevision revision=workingRevision(projectId);if(!Objects.equals(revisionId,revision.getId()))throw conflict("Graph changed; reload the historical answer.");
        Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),entry=asMap(historicalProjectionValues(projectId,graph).get(nodeId));
        if(entry.isEmpty())throw new BizException(4004,"HISTORICAL_PROJECTION_ANSWER_NOT_FOUND");
        List<Object> sources=new ArrayList<>();for(Object raw:list(entry.get("sources"))){Map<String,Object> source=asMap(raw);sources.add(map("sourceDocumentId",source.get("sourceDocumentId"),"fileName",source.get("fileName"),"sourceHash",source.get("sourceHash"),"originalSha256",source.get("originalSha256"),"sourceQuote",source.get("sourceQuote"),"status",source.get("status")));}
        boolean sourceChanged=sources.stream().anyMatch(item->!"bound".equals(asMap(item).get("status")));
        return map("graphRevisionId",revisionId,"nodeId",nodeId,"value",entry.get("value"),"status",entry.get("status"),"evidenceFingerprint",entry.get("evidenceFingerprint"),"sourceStatus",sources.isEmpty()?"manual_unbound":sourceChanged?"sources_need_review":"bound_sources","unverifiedSourceRef",sources.isEmpty()?entry.get("sourceRef"):null,"sources",sources);
    }
    private Map<String,Object> visibleValues(String projectId,Map<String,Object> graph){Map<String,Object> result=new LinkedHashMap<>();for(Map<String,Object> field:allFields(graph))variables.findByProjectIdAndVarKey(projectId,id(field)).ifPresent(value->result.put(id(field),"targetOverrides".equals(id(field))?DraftBusinessRules.parse(value.getValueText()):decode(field,value.getValueText())));return result;}
    private Map<String,Object> effective(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){Map<String,Object> result=new LinkedHashMap<>();if(stale(revision))return result;DefinitionCache definitions=new DefinitionCache();for(Map<String,Object> field:DraftReviewGraph.fields(graph)){String key=id(field);DraftVariable value=variables.findByProjectIdAndVarKey(projectId,key).orElse(null);DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,key)).orElse(null);
            if(value==null||binding==null||Boolean.TRUE.equals(value.getReviewRequired())||!Boolean.TRUE.equals(value.getConfirmed())||!Objects.equals(binding.getAdoptedDefinitionSha256(),DraftReviewGraph.fieldHash(field))||(JsonUtils.isBlankText(value.getValueText())&&!"text".equals(asMap(field.get("type")).get("kind"))))continue;
            if(binding.getAdoptedRevisionId()!=null&&!Objects.equals(binding.getAdoptedRevisionId(),revision.getId())){String old=storedDefinitionBasis(projectId,binding.getAdoptedRevisionId(),key,definitions);String current=definitions.bases.computeIfAbsent(revision.getId()+"\n"+key,ignored->definitionBasis(field));if(old==null||!old.equals(current))continue;}
            try{for(CandidateVO source:DraftAdoption.adoptedSources(value))verifyCandidateSource(projectId,source);result.put(key,decode(field,DraftReviewGraph.normalize(field,value.getValueText())));}catch(RuntimeException invalid){/* Retain historical values; invalid source or type never becomes effective. */}
        }return result;}
    private void verifyCandidateSource(String projectId,CandidateVO candidate){SourceDocument source=requireSource(projectId,candidate.getSourceDocumentId());if(!SourceDocument.CATEGORY_PROJECT_INPUT.equals(source.getCategory())||!Objects.equals(candidate.getSourceHash(),DraftAdoption.sourceHash(source)))throw conflict("Candidate source changed.");Map<String,Object> metadata=JsonUtils.mapper().convertValue(candidate,Map.class);JsonNode archive=sourceReader.archive(source);if(metadata.get("originalSha256")==null||!Objects.equals(metadata.get("originalSha256"),archive.path("sourceSha256").asText()))throw conflict("Candidate original bytes changed or were not bound.");if(JsonUtils.isBlankText(candidate.getSourceQuote())||source.getTextContent()==null||!source.getTextContent().contains(candidate.getSourceQuote()))throw conflict("Candidate quotation no longer exists.");}
    private List<SourceDocument> correspondence(String projectId){return sources.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT);}
    private String evidenceRevision(String projectId){List<Object> identities=new ArrayList<>();for(SourceDocument source:correspondence(projectId)){JsonNode archived=sourceReader.archive(source);identities.add(Arrays.asList(source.getId(),archived.path("sourceSha256").asText(),DraftAdoption.sourceHash(source)));}return hash(identities);}
    private Map<String,Object> field(Map<String,Object> graph,String key){Map<String,Object> field=optionalField(graph,key);if(field==null)throw new BizException(4007,"Unknown reviewed drafting input: "+key);return field;}
    private Map<String,Object> optionalField(Map<String,Object> graph,String key){if("contractTitle".equals(key))return identityField();for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(id(field).equals(key))return field;return null;}
    private static Object decode(Map<String,Object> field,String value){String kind=string(asMap(field.get("type")).get("kind"));if("text".equals(kind))return value;if(JsonUtils.isBlankText(value))return null;return Arrays.asList("date","choice").contains(kind)?value:DraftBusinessRules.parse(value);}
    private static String id(Map<String,Object> field){return String.valueOf(field.get("id"));}
    private static String string(Object value){return value==null?null:String.valueOf(value);}
    private static String hash(Object value){return DraftAdoption.hash(JsonUtils.write(value));}
    private static Map<String,Object> copy(Map<String,Object> value){return new LinkedHashMap<>(JsonUtils.readMap(JsonUtils.write(value)));}
    private static Map<String,Object> state(Map<String,Object> graph){return asMap(graph.get("graph"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
    private static void addIssue(Map<String,Object> plan,String kind,String message){List<Object> issues=new ArrayList<>(list(plan.get("unresolved")));issues.add(map("id",kind,"kind",kind,"message",LocalizedText.same(message)));plan.put("unresolved",issues);}
}
