package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalSourcePageService;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/** Shared current-source corpus, with full sealed evidence beside a model/citation read-only view. */
@Service @RequiredArgsConstructor
public class OerBusinessEvidence {
    private final CanonicalEvidenceService evidence;
    private final OerCanonicalRetrievalClient retrieval;
    private final CanonicalEvidenceProperties properties;
    @Autowired(required=false) private CanonicalSourcePageService sourcePages;
    @Autowired private CanonicalCurrentSourceReader currentSources;

    public boolean enabled(){return !JsonUtils.isBlankText(properties.getBaseUrl());}
    public static final class Session {
        public final String projectId,module,indexKey;
        public final List<Long> documentIds;
        private final String bindingFingerprint;
        private final Map<String,String> sourceRenderPins;
        private final Map<String,Chunk> byId;
        private final Map<String,String> viewPayloads;
        private final Corpus chunks;
        private volatile boolean invalidated;
        private CanonicalEvidenceService.CompactBindingScope semanticScope;
        private OerCanonicalRetrievalClient.SemanticSources semanticSources;
        private Map<Long,SourceDocument> semanticDocuments;
        private Map<String,JsonNode> semanticContexts;
        private Map<String,List<String>> semanticAnchors;
        private Map<String,String> semanticHeadingLocations=Collections.emptyMap();
        private List<VettingRuleEngine.Source> semanticRuleSources;
        private boolean scanned;
        private final Map<String,JsonNode> semanticGroups=new LinkedHashMap<>();
        private final List<JsonNode> semanticBlocked=new ArrayList<>();
        private Session(String project,String module,List<Long> ids,List<CanonicalSourceBinding> bindings,Map<String,Chunk> views,Map<String,String> pins){
            this.projectId=project;this.module=module;this.documentIds=Collections.unmodifiableList(new ArrayList<>(ids));
            this.bindingFingerprint=fingerprint(bindings);this.byId=views;
            this.sourceRenderPins=Collections.unmodifiableMap(new TreeMap<>(pins));
            this.viewPayloads=new LinkedHashMap<>();views.forEach((id,view)->viewPayloads.put(id,JsonUtils.serializedSha256(view)));
            Object projections=bindings.stream().map(CanonicalSourceBinding::getProjectionSha256).collect(Collectors.toList());
            this.indexKey=project+"::canonical::"+module+"::"+VettingCorpus.hash(JsonUtils.write(pins.isEmpty()?projections:Arrays.asList(projections,sourceRenderPins)));
            this.chunks=new Corpus(this,new ArrayList<>(views.values()));
        }
        private Session(String project,String module,CanonicalEvidenceService.CompactBindingScope scope,OerCanonicalRetrievalClient.SemanticSources sources,
                        Map<Long,SourceDocument> documents,Map<String,JsonNode> contexts,Map<String,List<String>> anchors,List<VettingRuleEngine.Source> rules,Map<String,String> pins){
            projectId=project;this.module=module;semanticScope=scope;semanticSources=sources;semanticDocuments=documents;semanticContexts=contexts;semanticAnchors=anchors;
            semanticRuleSources=rules;
            documentIds=Collections.unmodifiableList(new ArrayList<>(documents.keySet()));bindingFingerprint=fingerprint(scope.bindings());sourceRenderPins=Collections.unmodifiableMap(new TreeMap<>(pins));
            byId=new LinkedHashMap<>();viewPayloads=new LinkedHashMap<>();chunks=new Corpus(this,new ArrayList<>());indexKey=project+"::semantic::"+module+"::"+sources.identitySha256();
        }
        public List<Chunk> chunks(){return chunks;}
        public int chunkCount(){return chunks.size();}
        public String identitySha256(){return renderFingerprint(bindingFingerprint,sourceRenderPins);}
        public JsonNode semanticReadCoverage(){ObjectNode ledger=JsonUtils.mapper().createObjectNode();ledger.put("status",semanticBlocked.isEmpty()?"complete":"partial");ledger.put("totalOrigins",semanticSources.totalOrigins());ledger.put("readableOrigins",chunks.size());ledger.set("blockedOrigins",JsonUtils.mapper().valueToTree(semanticBlocked));return ledger;}
    }
    private static final class Corpus extends AbstractList<Chunk> implements VettingRetrievalSourceUnits.AttachedUnits{
        final Session session;private final List<Chunk> views,owned;
        private final List<VettingRetrievalSourceUnits.Unit> sourceUnits=new ArrayList<>();
        Corpus(Session s,List<Chunk> views){owned=views;this.views=Collections.unmodifiableList(views);session=s;}
        public Chunk get(int i){return views.get(i);}public int size(){return views.size();}
        public List<VettingRetrievalSourceUnits.Unit> sourceUnits(){return Collections.unmodifiableList(sourceUnits);}
    }
    public static boolean canonicalCorpus(List<Chunk> corpus){return corpus instanceof Corpus;}
    public static Session session(List<Chunk> corpus){if(!(corpus instanceof Corpus))throw new IllegalArgumentException("Canonical corpus session required");return ((Corpus)corpus).session;}
    public static boolean isSemanticCorpus(List<Chunk> corpus){return canonicalCorpus(corpus)&&session(corpus).semanticScope!=null;}
    public Session loadSemantic(String projectId,String module,List<SourceDocument> selected){
        require("vetting".equals(module),"semantic full-scope consumption is configured for vetting only");Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        Session result;CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));
        try(CurrentStage held=loaded){
            require(held.frame!=null,"semantic preparation requires an owned source frame");evidence.prepareCompactIdentities(held.frame,selected);held.bindings=held.frame.bindings();Session cold=loadSession(projectId,module,documents,held);held.session=cold;
            OerModelMaterial.Request material=new OerModelMaterial.Request(selected);Map<String,JsonNode> contexts=new LinkedHashMap<>();Map<String,List<String>> anchors=new LinkedHashMap<>();Map<String,String> headingLocations=new LinkedHashMap<>();
            for(Chunk chunk:cold.chunks){EvidencePointer pointer=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),EvidencePointer.class);
                anchors.put(chunk.getId(),chunk.getParts().stream().map(Part::getAnchor).collect(Collectors.toList()));
                headingLocations.put(chunk.getId(),chunk.getClauseHeadingLocation());
                JsonNode word=held.frame.wordSourceScopeContext(pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),module);if(word!=null)pointer.setNativeWordSourceScopesContext(word);
                contexts.put(chunk.getId(),VettingSourceMaterial.semantic(material.projectStandalone(pointer,chunk.getCanonicalPayload(),chunk.getRole()),Collections.emptyMap()));
            }
            OerCanonicalRetrievalClient.SemanticSources sources=retrieval.prepareSemanticSources(module,held.bindings);
            CanonicalEvidenceService.CompactBindingScope scope=evidence.compactBindings(held.frame,selected);
            Map<Long,SourceDocument> catalog=new LinkedHashMap<>();for(SourceDocument original:documents.values()){
                SourceDocument source=new SourceDocument();source.setId(original.getId());source.setProjectId(original.getProjectId());source.setFileName(original.getFileName());source.setFileKey(original.getFileKey());source.setReviewRole(original.getReviewRole());catalog.put(source.getId(),source);}
            result=new Session(projectId,module,scope,sources,catalog,contexts,anchors,buildRuleSources(cold,selected),sourceRenderPins(held.bindings));
            result.semanticHeadingLocations=Collections.unmodifiableMap(headingLocations);
        }
        current(result);return result;
    }
    public List<Chunk> scan(Session session){
        require(session!=null&&session.semanticScope!=null,"semantic scan requires its selected source session");current(session);if(session.scanned)return session.chunks;
        List<Chunk> collected=new ArrayList<>();Set<String> ids=new HashSet<>();JsonNode cursor=null;int processed=0;Map<String,JsonNode> groups=new LinkedHashMap<>();List<JsonNode> blocked=new ArrayList<>();
        do{ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","scan");demand.put("maxItems",64);demand.put("maxBytes",16777216);if(cursor!=null&&!cursor.isNull())demand.set("cursor",cursor);
            if(session.semanticSources.dictionary())demand.set("knownSemanticGroups",JsonUtils.mapper().valueToTree(groups.keySet().stream().limit(10000).collect(Collectors.toList())));
            evidence.assertCompactCurrent(session.semanticScope);JsonNode result=retrieval.readSemanticSources(session.semanticSources,demand);evidence.assertCompactCurrent(session.semanticScope);
            require(!"unit_too_large".equals(result.path("coverage").path("status").asText()),"a complete semantic source unit exceeds the bounded read budget");
            boolean dictionary=OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY.equals(result.path("semanticReadVersion").asText());
            if(dictionary){for(JsonNode row:result.path("units")){String group=row.path("groupId").asText();JsonNode prior=groups.putIfAbsent(group,row.path("unit"));require(prior==null||prior.equals(row.path("unit")),"semantic group changed during scan");}
                for(JsonNode row:result.path("blockedOrigins")){require(ids.add(row.path("id").asText()),"semantic scan repeated blocked origin");blocked.add(row.deepCopy());}}
            for(int i=0;i<result.path("origins").size();i++){JsonNode origin=result.path("origins").get(i);String group=dictionary?result.path("originUnits").path(origin.path("id").asText()).asText():null;JsonNode unit=dictionary?groups.get(group):result.path("units").get(i);require(unit!=null,"semantic scan group missing");Chunk chunk=semanticView(session,origin,unit);if(dictionary){((ObjectNode)chunk.getCanonicalPayload()).put("semanticUnitRef",group);((ObjectNode)chunk.getCanonicalPayload()).set("semanticUnit",unit);}require(ids.add(chunk.getId()),"semantic scan repeated an origin");collected.add(chunk);}
            int next=result.path("coverage").path("processedOrigins").asInt();require(next==processed+result.path("origins").size()+(dictionary?result.path("blockedOrigins").size():0),"semantic scan skipped origins");processed=next;cursor=result.path("nextCursor");
        }while(cursor!=null&&!cursor.isNull());
        require(processed==session.semanticSources.totalOrigins(),"semantic scan did not cover its declared source scope");
        Map<String,Chunk> byId=collected.stream().collect(Collectors.toMap(Chunk::getId,c->c,(a,b)->{throw new IllegalStateException("duplicate semantic origin");},LinkedHashMap::new));
        List<VettingRetrievalSourceUnits.Unit> sourceUnits=new ArrayList<>();for(Chunk chunk:collected)sourceUnits.add(semanticSourceUnit(byId,collected,chunk));current(session);
        session.semanticGroups.putAll(groups);session.semanticBlocked.addAll(blocked);
        for(Chunk chunk:collected){session.byId.put(chunk.getId(),chunk);session.viewPayloads.put(chunk.getId(),JsonUtils.serializedSha256(chunk));session.chunks.owned.add(chunk);}
        session.chunks.sourceUnits.addAll(sourceUnits);session.scanned=true;current(session);return session.chunks;
    }
    public void bindReviewEvidence(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        if(session.semanticScope==null){bindRuleEvidence(session,quotes);return;}current(session);for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes)if(quote.getFixedBaselineEvidence()==null&&quote.getCanonicalEvidence()==null)bindRuleEvidenceUnchecked(session,Collections.singletonList(quote));Map<String,EvidencePointer> originals=new HashMap<>();
        Set<com.consense.web.dto.VettingDtos.FindingEvidence> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes){if(!seen.add(quote)||quote.getFixedBaselineEvidence()!=null)continue;EvidencePointer selected=quote.getCanonicalEvidence();if(selected==null)continue;
            Chunk captured=session.byId.get(selected.getCanonicalChunkId());
            if(captured==null||!Objects.equals(captured.getCanonicalEvidence(),selected)||!Objects.equals(quote.getDocumentId(),captured.getDocumentId())
                    ||!Objects.equals(quote.getSourceHash(),selected.getJavaParsedSourceHash())||!VettingCorpus.normalize(captured.getContent()).contains(VettingCorpus.normalize(quote.getQuote()))){quote.setLocated(false);quote.setCanonicalEvidence(null);continue;}
            String key=selected.getDocumentId()+"|"+selected.getProjectionSha256()+"|"+selected.getUnitId();EvidencePointer original=originals.computeIfAbsent(key,ignored->evidence.pointer(session.projectId,selected.getDocumentId(),selected.getProjectionSha256(),selected.getUnitId(),session.module));
            String text=VettingCorpus.normalize(quote.getQuote());boolean found=!text.isEmpty()&&VettingCorpus.normalize(java.util.stream.StreamSupport.stream(original.getParts().spliterator(),false).map(part->part.path("text").asText()).collect(Collectors.joining("\n"))).contains(text);
            if(found){quote.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(original),EvidencePointer.class));quote.setSourceHash(original.getJavaParsedSourceHash());quote.setOriginalSourceSha256(original.getOriginalSourceSha256());
                List<JsonNode> parts=new ArrayList<>();for(JsonNode part:original.getParts())if(VettingCorpus.normalize(part.path("text").asText()).contains(text))parts.add(part);
                JsonNode position=parts.size()==1?VettingCorpus.nativePosition(parts.get(0).get("sourcePosition"),original.getOriginalSourceSha256()):null;
                quote.setNativePositions(position==null?null:Collections.singletonList(position));}
            else{quote.setLocated(false);quote.setCanonicalEvidence(null);}
        }current(session);
    }


    private static final class CurrentStage implements AutoCloseable {
        final CanonicalEvidenceService.VerifiedBindingFrame frame;List<CanonicalSourceBinding> bindings;Session session;
        CurrentStage(CanonicalEvidenceService.VerifiedBindingFrame frame,List<CanonicalSourceBinding> bindings){this.frame=frame;this.bindings=bindings;}
        @Override public void close(){if(frame!=null)try{frame.close();}catch(RuntimeException changed){if(session!=null)session.invalidated=true;throw changed;}}
    }
    private CurrentStage stage(String project,String module,List<Long> ids){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(project,module,ids);
        // Direct legacy test adapters expose untrusted lists, never a reusable
        // source verdict. That path retains all original fresh reads.
        if(frame==null)return new CurrentStage(null,evidence.currentBindings(project,module,ids));
        try{evidence.assertFrameOwner(frame,project,module);return new CurrentStage(frame,frame.bindings());}
        catch(RuntimeException failure){frame.close();throw failure;}
    }
    private CurrentStage stage(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        CurrentStage stage=null;
        try{stage=stage(session.projectId,session.module,session.documentIds);stage.session=session;checkViews(session,stage.bindings);return stage;}
        catch(RuntimeException changed){session.invalidated=true;if(stage!=null)stage.close();throw changed;}
    }
    private void checkViews(Session session,List<CanonicalSourceBinding> bindings){
        require(session.bindingFingerprint.equals(fingerprint(bindings)),"current bindings changed since corpus load");
        require(session.sourceRenderPins.equals(sourceRenderPins(bindings)),"source render identities changed since corpus load");
        for(Chunk view:session.chunks)require(Objects.equals(session.viewPayloads.get(view.getId()),JsonUtils.serializedSha256(view))
                &&canonicalViewStatus(view)==null,"selected canonical view changed");
    }

    public Session load(String projectId,String module,List<SourceDocument> selected) {
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));Session session;
        try(CurrentStage held=loaded){session=loadSession(projectId,module,documents,held);}
        complete(session,loaded);return session;
    }
    private Map<Long,SourceDocument> selectedDocuments(String projectId,List<SourceDocument> selected){
        if(!enabled())throw new IllegalStateException("OER canonical business backend is not configured");
        require(selected!=null&&!selected.isEmpty(),"no permitted current documents");
        Map<Long,SourceDocument> documents=new LinkedHashMap<>();for(SourceDocument d:selected)
            require(d!=null&&d.getId()!=null&&Objects.equals(projectId,d.getProjectId())&&documents.putIfAbsent(d.getId(),d)==null,"selected document identity differs");
        return documents;
    }
    private Session loadSession(String projectId,String module,Map<Long,SourceDocument> documents,CurrentStage stage){
        List<Long> ids=new ArrayList<>(documents.keySet());
        List<CanonicalSourceBinding> bindings=stage.bindings;
        Map<String,String> pins=sourceRenderPins(bindings);
        require(pins.isEmpty()||sourcePages!=null,"source page validator required for reviewed render identities");
        require(ids.equals(bindings.stream().map(CanonicalSourceBinding::getDocumentId).collect(Collectors.toList())),"selected binding order differs");
        Map<String,Chunk> views=new LinkedHashMap<>();for(CanonicalSourceBinding binding:bindings) {
            JsonNode verifiedNormal=stage.frame==null?null:stage.frame.normalSnapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
            JsonNode snapshot=verifiedNormal==null?JsonUtils.parse(binding.getSnapshotJson()):verifiedNormal;SourceDocument source=documents.get(binding.getDocumentId());
            boolean normal=NormalObservedContract.isNormal(snapshot),family=!normal&&NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!normal&&!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
            JsonNode display=family?NativePdfSourceFamilyContract.displayDeclarations(snapshot):nativePdf?NativePdfCanonicalContract.displayDeclarations(snapshot):null;
            // A large native document has one root binding. Complete batches
            // are verified by the source service; the root is not a chunk.
            JsonNode payloads="consense.native-document.v1".equals(snapshot.path("schema").asText())
                    ?(stage.frame==null?evidence.chunks(binding,module):stage.frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module).path("chunks")):snapshot.path("chunks");
            Map<String,EvidencePointer> pointers=new LinkedHashMap<>();
            for(EvidencePointer verified:stage.frame==null?evidence.pointers(projectId,binding.getDocumentId(),binding.getProjectionSha256(),module)
                    :stage.frame.pointers(binding.getDocumentId(),binding.getProjectionSha256(),module)){
                EvidencePointer pointer=sourcePages==null?verified:sourcePages.enrich(verified);
                require(Objects.equals(pins.get(binding.getProjectionSha256()),pointer.getSourceRenderEvidenceIdentitySha256()),"source render pointer differs from reviewed identity");
                require(pointers.putIfAbsent(pointer.getCanonicalChunkId(),pointer)==null,"duplicate canonical pointer");
            }
            require(payloads!=null&&payloads.isArray()&&pointers.size()==payloads.size(),"canonical pointer count differs");
            for(JsonNode payload:payloads) {
                EvidencePointer pointer=pointers.get(payload.path("id").asText());require(pointer!=null,"canonical pointer missing");
                if(family)require(display.equals(pointer.getNativePdfSourceFamilyEvidence()),"native PDF family display declarations differ");
                else if(nativePdf)require(display.equals(pointer.getNativePdfEvidence()),"native PDF display declarations differ");
                Chunk view=view(payload,pointer,source);require(views.putIfAbsent(view.getId(),view)==null,"duplicate canonical chunk");
                require(canonicalViewStatus(view)==null,"canonical pointer and selected snapshot differ");
            }
        }
        return new Session(projectId,module,ids,bindings,views,pins);
    }
    /** Scalar result only: the verified frame and its source verdict never leave this request. */
    public static final class CurrentIndexStatus {
        public final String indexKey;public final int chunks,documentCount;
        public final OerCanonicalRetrievalClient.IndexState state;
        private CurrentIndexStatus(Session session,OerCanonicalRetrievalClient.IndexState state){
            this.indexKey=session.indexKey;this.chunks=session.chunkCount();this.documentCount=session.documentIds.size();this.state=state;
        }
    }
    /** A status read owns one fresh frame; the selected catalog is fetched before health and after close. */
    public CurrentIndexStatus currentIndexStatus(String projectId,String module,Supplier<List<SourceDocument>> currentSelected){
        require(currentSelected!=null,"current selected scope reader required");
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,currentSelected.get());
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));Session session;
        OerCanonicalRetrievalClient.IndexState state;
        try(CurrentStage held=loaded){
            session=loadSession(projectId,module,documents,held);held.session=session;
            assertSelectedScope(session,currentSelected.get());
            state=held.frame==null?retrieval.indexStatus(session.indexKey,module,held.bindings)
                    :retrieval.indexStatus(session.indexKey,module,held.frame);
        }
        complete(session,loaded);
        // A new eligible member can appear during the exit CAS wait as well as health.
        assertSelectedScope(session,currentSelected.get());
        return new CurrentIndexStatus(session,state);
    }
    private void complete(Session session,CurrentStage stage){
        if(stage.frame==null){current(session);return;}
        try{checkViews(session,stage.bindings);}catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void index(Session session){
        index(session,false);
    }
    public void rebuildIndex(Session session){
        index(session,true);
    }
    private void index(Session session,boolean rebuild){
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){if(rebuild){
                if(stage.frame==null)retrieval.rebuild(session.indexKey,session.module,stage.bindings);
                else retrieval.rebuild(session.indexKey,session.module,stage.frame);
            }else if(stage.frame==null)retrieval.index(session.indexKey,session.module,stage.bindings);
            else retrieval.index(session.indexKey,session.module,stage.frame);}
        complete(session,loaded);
    }
    public OerCanonicalRetrievalClient.IndexState indexStatus(Session session){
        OerCanonicalRetrievalClient.IndexState state;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){state=stage.frame==null?retrieval.indexStatus(session.indexKey,session.module,stage.bindings)
                :retrieval.indexStatus(session.indexKey,session.module,stage.frame);}
        complete(session,loaded);return state;
    }
    /** One fresh selected-scope stage directly precedes the external health wait. */
    public OerCanonicalRetrievalClient.IndexState indexStatusSelected(Session session,List<SourceDocument> selected){
        try{assertSelectedScope(session,selected);
            return indexStatus(session);
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    /** Pure selected-scope identity check; callers fetch the current catalog anew at a remote boundary. */
    public void assertSelectedScope(Session session,List<SourceDocument> selected){
        try{require(!session.invalidated,"canonical session invalidated; reload current sources");
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void ensureIndex(Session session){if(!indexStatus(session).ready)index(session);}
    public void assertCurrent(Session session){current(session);}
    public String currentIdentity(Session session){current(session);return session.identitySha256();}
    public String currentIdentity(String projectId,String module,List<SourceDocument> selected){
        List<CanonicalSourceBinding> bindings=evidence.currentBindings(projectId,module,selected.stream().map(SourceDocument::getId).collect(Collectors.toList()));
        return renderFingerprint(fingerprint(bindings),sourceRenderPins(bindings));
    }
    /** Callers obtain the complete selected scope anew; fixed IDs alone cannot detect new package members. */
    public void assertSelected(Session session,List<SourceDocument> selected){
        try {
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");current(session);
        } catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    private List<CanonicalSourceBinding> current(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        if(session.semanticScope!=null)try{
            require(session.sourceRenderPins.equals(sourceRenderPins(session.semanticScope.bindings())),"source render identities changed");
            for(Chunk view:session.chunks)require(Objects.equals(session.viewPayloads.get(view.getId()),JsonUtils.serializedSha256(view))&&canonicalViewStatus(view)==null,"selected semantic view changed");
            evidence.assertCompactCurrent(session.semanticScope);ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","catalog");retrieval.readSemanticSources(session.semanticSources,demand);evidence.assertCompactCurrent(session.semanticScope);
            return session.semanticScope.bindings();
        }catch(RuntimeException invalid){session.invalidated=true;throw invalid;}
        try {
            List<CanonicalSourceBinding> bindings=evidence.currentBindings(session.projectId,session.module,session.documentIds);
            checkViews(session,bindings);
            return bindings;
        } catch(RuntimeException invalid){session.invalidated=true;throw invalid;}
    }
    private static String fingerprint(List<CanonicalSourceBinding> bindings){
        List<List<Object>> identities=new ArrayList<>();for(CanonicalSourceBinding b:bindings)
            identities.add(Arrays.asList(b.getProjectId(),b.getDocumentId(),b.getOriginalSourceSha256(),b.getJavaParsedSourceHash(),
                    b.getRoutingSha256(),b.getSourceRevisionSha256(),b.getProjectionSha256(),b.getProjectionRevisionSha256(),
                    b.getCanonicalSourceHash(),b.getScopePolicySha256(),b.getAdoptionManifestSha256(),b.getReviewReferenceSha256(),b.getFamilyCompositionSha256(),b.getFamilyComponentSetSha256(),
                b.getDeclaredModule(),b.getEligibleModulesJson(),b.getSnapshotBytesSha256(),b.getSnapshotJson()==null?b.getSnapshotBytesSha256():VettingCorpus.hash(b.getSnapshotJson())));
        return VettingCorpus.hash(JsonUtils.write(identities));
    }
    private Map<String,String> sourceRenderPins(List<CanonicalSourceBinding> bindings){
        Map<String,String> pins=new TreeMap<>();for(CanonicalSourceBinding binding:bindings){
            String identity=properties.getSourcePageRenderEvidenceIdentities().get(binding.getProjectionSha256());
            if(identity!=null){require(identity.matches("[a-f0-9]{64}"),"reviewed source render identity invalid");pins.put(binding.getProjectionSha256(),identity);}
        }return pins;
    }
    private static String renderFingerprint(String bindings,Map<String,String> pins){
        return pins.isEmpty()?bindings:VettingCorpus.hash(JsonUtils.write(Arrays.asList(bindings,pins)));
    }
    public List<Chunk> retrieve(Session session,String query,String role){
        JsonNode response=response(session,query,role);List<Chunk> seeds=new ArrayList<>();List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        for(JsonNode hit:response.path("hits"))seeds.add(copy(session.byId.get(hit.path("id").asText())));
        for(JsonNode raw:response.path("sourceUnits").path("units")) {
            ObjectNode traceNode=raw.deepCopy();traceNode.remove("members");
            if(traceNode.path("hierarchyContext").isObject())((ObjectNode)traceNode.path("hierarchyContext")).remove("members");
            if(traceNode.path("attachmentContext").isObject())((ObjectNode)traceNode.path("attachmentContext")).remove("members");
            Map<String,Object> trace=JsonUtils.readMap(JsonUtils.write(traceNode));
            trace.put("selectionPolicy",response.path("sourceUnits").path("policy").asText());
            JsonNode transport=transportUnit(raw,canonicalParents(session));List<String> ids=new ArrayList<>();transport.path("requiredMemberIds").forEach(id->ids.add(id.asText()));
            units.add(new VettingRetrievalSourceUnits.Unit(raw.path("originIds").get(0).asText(),completeTransport(transport)?"complete_observed_unit":"unknown",
                    transport.path("reason").isNull()?null:transport.path("reason").asText(),ids,trace));
        }
        return new VettingRetrievalSourceUnits.RankedChunks(seeds,units);
    }
    public JsonNode response(Session session,String query,String role){
        JsonNode response;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){response=stage.frame==null?retrieval.retrieve(session.indexKey,session.module,query,role,stage.bindings)
                :retrieval.retrieve(session.indexKey,session.module,query,role,stage.frame);}
        complete(session,loaded);return response;
    }

    /** Selected model context only. Neither the corpus nor a source-verification verdict is retained here. */
    public Map<String,JsonNode> wordSourceScopeContexts(Session session,List<Chunk> selected){
        if(session.semanticScope!=null){current(session);Map<String,JsonNode> contexts=new LinkedHashMap<>();for(Chunk chunk:selected){
                if(FixedVettingReferences.isFixed(chunk))continue;require(session.byId.containsKey(chunk.getId())&&Objects.equals(session.viewPayloads.get(chunk.getId()),JsonUtils.serializedSha256(chunk)),"selected semantic context differs");
                JsonNode context=session.semanticContexts.get(chunk.getId());if(context!=null)contexts.put(chunk.getId(),context.deepCopy());}
            return contexts;}
        Objects.requireNonNull(selected,"Selected model chunks required");List<Chunk> words=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Chunk chunk:selected){
            if(FixedVettingReferences.isFixed(chunk))continue;
            require(chunk!=null&&session.byId.containsKey(chunk.getId())&&Objects.equals(session.viewPayloads.get(chunk.getId()),JsonUtils.serializedSha256(chunk))
                    &&canonicalViewStatus(chunk)==null,"selected model member differs from canonical session");
            if(NormalObservedContract.isNormal(chunk.getCanonicalPayload())&&chunk.getCanonicalEvidence().getParts()!=null)
                for(JsonNode part:chunk.getCanonicalEvidence().getParts())if("docx_native".equals(part.path("sourcePosition").path("kind").asText())){
                    if(ids.add(chunk.getId()))words.add(chunk);break;
                }
        }
        if(words.isEmpty())return Collections.emptyMap();
        CurrentStage loaded=stage(session);Map<String,JsonNode> contexts=new LinkedHashMap<>();
        try(CurrentStage stage=loaded){for(Chunk chunk:words){EvidencePointer pointer=chunk.getCanonicalEvidence();
            JsonNode context=stage.frame==null?evidence.wordSourceScopeContext(session.projectId,pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),session.module)
                    :stage.frame.wordSourceScopeContext(pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),session.module);
            if(context!=null)contexts.put(chunk.getId(),context);
        }}
        complete(session,loaded);return contexts;
    }

    private List<VectorStore.SearchHit> withWordSourceScopes(Session session,List<VectorStore.SearchHit> hits){
        Map<String,Chunk> selected=new LinkedHashMap<>();
        for(VectorStore.SearchHit hit:hits)for(JsonNode member:JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalMembers"))){
            Chunk chunk=session.byId.get(member.path("id").asText());require(chunk!=null&&member.equals(chunk.getCanonicalPayload()),"advisory model member differs from canonical session");selected.put(chunk.getId(),chunk);
        }
        Map<String,JsonNode> contexts=wordSourceScopeContexts(session,new ArrayList<>(selected.values()));
        for(VectorStore.SearchHit hit:hits){Object value=hit.getPayload().get("canonicalEvidence");require(value instanceof List,"advisory model pointers required");
            for(Object raw:(List<?>)value){require(raw instanceof EvidencePointer,"advisory model pointer type differs");EvidencePointer pointer=(EvidencePointer)raw;
                JsonNode context=contexts.get(pointer.getCanonicalChunkId());if(context!=null)pointer.setNativeWordSourceScopesContext(context.deepCopy());
            }
        }
        return hits;
    }

    /** One ranked hit carries the whole observed unit; its members receive no invented rerank scores. */
    public List<VectorStore.SearchHit> adviceHits(Session session,String question) {
        JsonNode result=response(session,question,null);List<VectorStore.SearchHit> out=new ArrayList<>();
        for(int i=0;i<result.path("hits").size();i++) {
            JsonNode hit=result.path("hits").get(i),unit=result.path("sourceUnits").path("units").get(i);
            out.add(businessHit(session,hit.path("id").asText(),unit,hit.path("score").asDouble(),"reranked_seed"));
        }return withWordSourceScopes(session,out);
    }

    /** Advisory declares its context needs; source selection and observed closure stay in shared OER. */
    public List<VectorStore.SearchHit> advisoryContext(Session session,List<VectorStore.SearchHit> hits,boolean includeSmallCorpus,int stuffingLimit){
        List<VectorStore.SearchHit> selected=withIncorporationContext(session,hits);
        selected=withTableContext(session,selected);
        return includeSmallCorpus?withSmallCorpusContext(session,selected,stuffingLimit):selected;
    }
    public OerModelMaterial.Request modelMaterial(Session session,List<VectorStore.SearchHit> hits){
        require(session!=null,"canonical model material requires its selected source session");
        return new OerModelMaterial.Request(materialSources(session,hits,false));
    }
    private List<VectorStore.SearchHit> withSmallCorpusContext(Session session,List<VectorStore.SearchHit> hits,int stuffingLimit){
        long remaining=stuffingLimit;if(remaining<=0)return hits;
        for(Chunk chunk:session.chunks){remaining-=text(chunk.getContent()).length();if(remaining<0)return hits;}
        Set<String> represented=new LinkedHashSet<>(),units=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits)if(!fixedBaselineHit(hit)){
            represented.add(hit.getId());JsonNode unit=canonicalUnit(hit);units.add(unit==null?hit.getId():unit.path("unitId").asText(hit.getId()));
            for(EvidencePointer pointer:canonicalPointers(hit))if(!JsonUtils.isBlankText(pointer.getCanonicalChunkId()))represented.add(pointer.getCanonicalChunkId());
        }
        List<String> origins=session.chunks.stream().map(Chunk::getId).filter(id->!represented.contains(id)).collect(Collectors.toList());
        if(origins.isEmpty())return hits;
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);
        for(VectorStore.SearchHit context:sourceHits(session,origins)){JsonNode unit=canonicalUnit(context);if(units.add(unit==null?context.getId():unit.path("unitId").asText(context.getId())))result.add(context);}
        return result;
    }
    private List<VectorStore.SearchHit> withTableContext(Session session,List<VectorStore.SearchHit> hits){
        List<SourceDocument> sources=materialSources(session,hits,true);if(sources.isEmpty())return hits;
        OerModelMaterial.Request material=new OerModelMaterial.Request(sources);
        Map<List<String>,Set<String>> members=new LinkedHashMap<>();Set<String> present=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits){
            JsonNode unit=canonicalUnit(hit);if(unit!=null)present.add(unit.path("unitId").asText());
            JsonNode payloads=JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalMembers"));int ordinal=0;
            for(EvidencePointer pointer:canonicalPointers(hit)){
                JsonNode payload=payloads.isArray()&&ordinal<payloads.size()?payloads.get(ordinal):null;ordinal++;
                if(!NormalObservedContract.PRODUCER.equals(pointer.getProducerKind()))continue;
                JsonNode selected=material.project(pointer,payload,String.valueOf(hit.getPayload().get("sourceRole")));
                for(JsonNode table:selected.path("observedSourceUnits").path("units"))
                    if(Arrays.asList("ordinary_docx_native_table_tree","ordinary_docx_table").contains(table.path("kind").asText()))
                        for(JsonNode id:table.path("requiredMemberBlockIds"))members.computeIfAbsent(tableSource(pointer,String.valueOf(hit.getPayload().get("sourceRole"))),key->new LinkedHashSet<>()).add(id.asText());
            }
        }
        if(members.isEmpty())return hits;
        List<String> origins=new ArrayList<>();
        for(Chunk chunk:session.chunks){EvidencePointer pointer=chunk.getCanonicalEvidence();if(pointer==null)continue;
            Set<String> required=members.get(tableSource(pointer,chunk.getRole()));
            if(required!=null&&chunk.getParts().stream().anyMatch(part->required.contains(part.getBlockId())))origins.add(chunk.getId());
        }
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);
        for(VectorStore.SearchHit context:sourceHits(session,origins))if(present.add(canonicalUnit(context).path("unitId").asText()))result.add(context);
        return result;
    }
    private List<SourceDocument> materialSources(Session session,List<VectorStore.SearchHit> hits,boolean normalOnly){
        Map<Long,SourceDocument> byDocument=new LinkedHashMap<>();
        for(VectorStore.SearchHit hit:hits)for(EvidencePointer pointer:canonicalPointers(hit)){
            boolean normal=NormalObservedContract.PRODUCER.equals(pointer.getProducerKind());if(normalOnly&&!normal)continue;
            require(Objects.equals(session.projectId,pointer.getProjectId())&&session.documentIds.contains(pointer.getDocumentId()),"model material pointer outside selected scope");
            SourceDocument source=byDocument.get(pointer.getDocumentId());
            if(source==null){require(currentSources!=null,"current source reader required for selected model material");source=normal?currentSources.normalDocument(session.projectId,pointer.getDocumentId(),session.module):currentSources.currentDocument(session.projectId,pointer.getDocumentId(),session.module);byDocument.put(pointer.getDocumentId(),source);}
            require(Objects.equals(source.getProjectId(),pointer.getProjectId())&&Objects.equals(source.getId(),pointer.getDocumentId())
                    &&Objects.equals(source.getSourceSha256(),pointer.getOriginalSourceSha256())&&Objects.equals(VettingCorpus.sourceHash(source),pointer.getJavaParsedSourceHash())
                    &&Objects.equals(VettingCorpus.sourceRole(source),String.valueOf(hit.getPayload().get("sourceRole"))),"model material current source differs from selected pointer");
        }
        return new ArrayList<>(byDocument.values());
    }
    private static List<String> tableSource(EvidencePointer pointer,String role){
        return Arrays.asList(pointer.getProjectId(),String.valueOf(pointer.getDocumentId()),pointer.getOriginalSourceSha256(),pointer.getJavaParsedSourceHash(),pointer.getProjectionSha256(),role);
    }
    private List<VectorStore.SearchHit> withIncorporationContext(Session session,List<VectorStore.SearchHit> hits){
        List<VectorStore.SearchHit> libraries=hits.stream().filter(h->"standard".equals(String.valueOf(h.getPayload().get("sourceRole")))).collect(Collectors.toList());if(libraries.isEmpty())return hits;
        List<String> origins=new ArrayList<>();for(Chunk chunk:session.chunks){
            if(!"tender".equals(chunk.getRole()))continue;
            String name=text(chunk.getFileName()).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
            if(name.contains("the specification")||name.contains("specification composition")||libraries.stream().anyMatch(h->referenceMention(chunk.getContent(),h.fileLabel())))origins.add(chunk.getId());
        }
        List<VectorStore.SearchHit> contexts=origins.isEmpty()?Collections.emptyList():sourceHits(session,origins);
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);Set<String> units=new LinkedHashSet<>(),representedOrigins=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits){JsonNode unit=canonicalUnit(hit);if(unit!=null){units.add(unit.path("unitId").asText());recordCompleteMembers(unit,representedOrigins);}}
        boolean complete=!origins.isEmpty();int contextChars=0;
        for(VectorStore.SearchHit context:contexts){JsonNode unit=canonicalUnit(context);if(unit==null||!"complete_observed_unit".equals(unit.path("status").asText()))complete=false;
            String unitId=unit==null?context.getId():unit.path("unitId").asText();if(units.contains(unitId))continue;
            if(contextChars+context.text().length()>12000){complete=false;continue;}
            units.add(unitId);result.add(context);contextChars+=context.text().length();recordCompleteMembers(unit,representedOrigins);
        }
        complete=complete&&representedOrigins.containsAll(origins);List<VectorStore.SearchHit> annotated=new ArrayList<>();
        for(VectorStore.SearchHit hit:result){Map<String,Object> payload=new LinkedHashMap<>(hit.getPayload());if("standard".equals(payload.get("sourceRole"))&&!fixedBaselineHit(hit))payload.put("applicabilityContextComplete",complete);annotated.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),payload));}
        return annotated;
    }
    private static void recordCompleteMembers(JsonNode unit,Set<String> representedOrigins){
        if(unit!=null&&"complete_observed_unit".equals(unit.path("status").asText()))for(JsonNode member:unit.path("requiredMemberIds"))representedOrigins.add(member.asText());
    }
    public static boolean referenceMention(String passage,String referenceLabel){
        String content=text(passage).toLowerCase(Locale.ROOT),label=text(referenceLabel).toLowerCase(Locale.ROOT);
        if(label.startsWith("sl ")||label.contains("specification library")||label.contains("general specification"))return content.contains("specification library")||content.contains("general specification")||content.matches("(?s).*\\b(?:sl[ -]?20\\d\\d|gscew\\d*|gs[ -]?20\\d\\d)\\b.*");
        if(label.startsWith("gcc ")||label.contains("general conditions of contract"))return content.contains("general conditions of contract")||content.matches("(?s).*\\bgcc\\b.*");
        String key=label.split("[ ·]",2)[0];return !key.isEmpty()&&content.matches("(?s).*\\b"+java.util.regex.Pattern.quote(key)+"\\b.*");
    }
    private static JsonNode canonicalUnit(VectorStore.SearchHit hit){Object unit=hit.getPayload().get("canonicalSourceUnit");return unit instanceof JsonNode?(JsonNode)unit:unit==null?null:JsonUtils.mapper().valueToTree(unit);}
    private static List<EvidencePointer> canonicalPointers(VectorStore.SearchHit hit){Object pointers=hit.getPayload().get("canonicalEvidence");return pointers==null?Collections.emptyList():JsonUtils.readList(JsonUtils.write(pointers),EvidencePointer.class);}
    private static boolean fixedBaselineHit(VectorStore.SearchHit hit){return hit!=null&&hit.getPayload()!=null&&Boolean.TRUE.equals(hit.getPayload().get("fixedDevelopmentBaseline"));}
    private static String text(String value){return value==null?"":value;}
    /** OER returns observed closure for explicitly requested origins in this same immutable corpus. */
    public List<VectorStore.SearchHit> sourceHits(Session session,List<String> originIds){
        require(originIds!=null&&new HashSet<>(originIds).size()==originIds.size(),"context origins invalid");
        for(String id:originIds)require(session.byId.containsKey(id),"context origin outside selected corpus");
        if(originIds.isEmpty()){current(session);return Collections.emptyList();}
        CurrentStage loaded=stage(session);String policy=null;
        List<VectorStore.SearchHit> out=new ArrayList<>();Set<String> used=new HashSet<>();
        try(CurrentStage stage=loaded){
            for(int start=0;start<originIds.size();start+=256){
                List<String> batch=originIds.subList(start,Math.min(originIds.size(),start+256));ObjectNode demand=JsonUtils.mapper().createObjectNode();
                demand.put("operation","ids");demand.put("view","canonical_units");demand.put("maxItems",batch.size());demand.put("maxBytes",16777216);demand.set("originIds",JsonUtils.mapper().valueToTree(batch));
                JsonNode result=stage.frame==null?retrieval.readSources(session.module,demand,stage.bindings):retrieval.readSources(session.module,demand,stage.frame);
                String returnedPolicy=result.path("sourceUnitPolicy").asText();require(VettingRetrievalSourceUnits.supportsPolicy(returnedPolicy)&&(policy==null||policy.equals(returnedPolicy)),"context source-unit capability is unknown or changed");policy=returnedPolicy;
                JsonNode origins=result.path("origins"),units=result.path("units");require(origins.isArray()&&units.isArray()&&origins.size()==batch.size()&&units.size()==batch.size(),"context source read omitted requested origins");
                for(int i=0;i<batch.size();i++){
                    String id=batch.get(i);require(id.equals(origins.get(i).path("id").asText()),"context source read reordered requested origins");JsonNode unit=units.get(i);
                    require(unit.path("unitId").isTextual()&&!unit.path("unitId").asText().isEmpty(),"context source-unit identity missing");
                    if(used.add(unit.path("unitId").asText()))out.add(businessHit(session,id,unit,0d,"context_only"));
                }
            }
        }
        complete(session,loaded);current(session);return withWordSourceScopes(session,out);
    }
    /** One synchronous adapter owns these lazy reads; rejected identities never parse the source. */
    private static final class RuleSourceRead {
        final SourceDocument source;
        private String sourceHash;
        private List<com.consense.document.DocumentBlock> blocks;
        private RuntimeException blocksFailure;
        private boolean blocksRead;
        private final Map<String,List<com.consense.document.DocumentBlock>> byId=new HashMap<>();
        private Map<JsonNode,Map<String,List<com.consense.document.DocumentBlock>>> headings;
        private JsonNode coverage;
        RuleSourceRead(SourceDocument source){this.source=source;}
        String sourceHash(){if(sourceHash==null)sourceHash=VettingCorpus.sourceHash(source);return sourceHash;}
        List<com.consense.document.DocumentBlock> blocks(){
            if(!blocksRead){blocksRead=true;try{blocks=VettingCorpus.blocks(source);for(com.consense.document.DocumentBlock block:blocks)byId.computeIfAbsent(block.getId(),key->new ArrayList<>()).add(block);}
                catch(RuntimeException invalid){blocksFailure=invalid;}}
            if(blocksFailure!=null)throw blocksFailure;return blocks;
        }
        List<com.consense.document.DocumentBlock> blockMatches(String id){blocks();return byId.getOrDefault(id,Collections.emptyList());}
        List<com.consense.document.DocumentBlock> headingMatches(JsonNode position,String text){
            if(headings==null){headings=new HashMap<>();for(com.consense.document.DocumentBlock block:blocks())headings.computeIfAbsent(block.getSourcePosition(),key->new HashMap<>()).computeIfAbsent(block.getEffectiveText(),key->new ArrayList<>()).add(block);}
            return headings.getOrDefault(position,Collections.emptyMap()).getOrDefault(text,Collections.emptyList());
        }
        JsonNode coverage(){
            if(coverage==null)try{coverage=JsonUtils.parse(source.getParseCoverageJson());}
                catch(RuntimeException invalid){coverage=JsonUtils.mapper().createObjectNode();}
            return coverage;
        }
    }
    /** Deterministic checks traverse only accepted observations, retaining source-bound original anchors. */
    public List<VettingRuleEngine.Source> ruleSources(Session session){
        return ruleSources(session,Collections.emptyList());
    }
    public List<VettingRuleEngine.Source> ruleSources(Session session,List<SourceDocument> capturedSources){
        current(session);if(session.semanticScope!=null){List<VettingRuleEngine.Source> copied=new ArrayList<>();for(VettingRuleEngine.Source source:session.semanticRuleSources)
            copied.add(new VettingRuleEngine.Source(source.getId(),source.getFileKey(),source.getFileName(),source.getHash(),JsonUtils.readList(JsonUtils.write(source.getBlocks()),com.consense.document.DocumentBlock.class),source.getText()));return copied;}
        return buildRuleSources(session,capturedSources);
    }
    private static List<VettingRuleEngine.Source> buildRuleSources(Session session,List<SourceDocument> capturedSources){
        Map<String,List<Chunk>> byDocument=new LinkedHashMap<>();for(Chunk c:session.chunks)
            if("tender".equals(c.getRole()))byDocument.computeIfAbsent(c.getDocumentId(),key->new ArrayList<>()).add(c);
        List<VettingRuleEngine.Source> out=new ArrayList<>();for(List<Chunk> chunks:byDocument.values()){
            Chunk first=chunks.get(0);List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();Set<String> observed=new HashSet<>();
            List<SourceDocument> matching=capturedSources==null?Collections.emptyList():capturedSources.stream().filter(d->d!=null&&Objects.equals(String.valueOf(d.getId()),first.getDocumentId())).collect(Collectors.toList());
            RuleSourceRead read=new RuleSourceRead(matching.size()==1?matching.get(0):null);
            for(Chunk c:chunks)for(Part p:c.getParts())if(observed.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset())){
                com.consense.document.DocumentBlock b=new com.consense.document.DocumentBlock();b.setId(p.getBlockId());b.setLocation(p.getAnchor());
                b.setText(p.getText());b.setOriginalText(p.getText());b.setSource(NormalObservedContract.isNormal(c.getCanonicalPayload())?"normal_observed_candidate":"source_reviewed_canonical_observation");
                if(p.getPageNo()!=null)b.setPageNo(Integer.valueOf(p.getPageNo().substring(1)));
                List<Double> box=p.getBbox();if(box!=null)b.setBbox(box.stream().mapToDouble(Double::doubleValue).toArray());
                bindRuleContext(session.projectId,read,c,p,b);blocks.add(b);
            }
            out.add(new VettingRuleEngine.Source(first.getDocumentId(),first.getFileKey(),first.getFileName(),first.getCanonicalEvidence().getJavaParsedSourceHash(),
                    blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
        }return out;
    }
    /** Copies only structure from the unique current source/range; never unselected field or cell text. */
    static void bindRuleContext(String projectId,SourceDocument source,Chunk chunk,Part part,com.consense.document.DocumentBlock target) {
        bindRuleContext(projectId,new RuleSourceRead(source),chunk,part,target);
    }
    private static void bindRuleContext(String projectId,RuleSourceRead read,Chunk chunk,Part part,com.consense.document.DocumentBlock target) {
        SourceDocument source=read.source;
        ObjectNode context=JsonUtils.mapper().createObjectNode(),binding=context.putObject(VettingPackageIdentity.CONTEXT);target.setReadingContext(context);
        binding.put("status","unknown");binding.put("reason","current_source_binding_missing");binding.put("startOffset",part.getStartOffset());binding.put("endOffset",part.getEndOffset());binding.put("offsetUnit","utf16");
        if(source==null||!Objects.equals(projectId,source.getProjectId())||!"PARSED".equals(source.getParseStatus())||chunk.getCanonicalEvidence()==null||chunk.getCanonicalPayload()==null
                ||!Objects.equals(String.valueOf(source.getId()),chunk.getDocumentId())||!Objects.equals(read.sourceHash(),chunk.getCanonicalEvidence().getJavaParsedSourceHash())
                ||source.getSourceSha256()==null||!Objects.equals(source.getSourceSha256(),chunk.getCanonicalEvidence().getOriginalSourceSha256()))return;
        List<com.consense.document.DocumentBlock> matches;
        try{matches=read.blockMatches(part.getBlockId());}
        catch(RuntimeException invalid){binding.put("reason","source_structure_unreadable");return;}
        binding.put("reason","original_block_not_unique");if(matches.size()!=1)return;com.consense.document.DocumentBlock original=matches.get(0);String text=original.getEffectiveText();int start=part.getStartOffset(),end=part.getEndOffset();
        binding.put("reason","accepted_range_differs");if(text==null||start<0||end<start||end>text.length()||!Objects.equals(text.substring(start,end),part.getText())||!Objects.equals(part.getAnchor(),original.getLocation()))return;
        List<JsonNode> accepted=new ArrayList<>();for(JsonNode raw:chunk.getCanonicalPayload().path("parts"))if(Objects.equals(raw.path("blockId").asText(),part.getBlockId())&&raw.path("startOffset").isIntegralNumber()&&raw.path("endOffset").isIntegralNumber()&&raw.path("startOffset").asInt()==start&&raw.path("endOffset").asInt()==end&&Objects.equals(raw.path("text").asText(),part.getText()))accepted.add(raw);
        binding.put("reason","accepted_part_not_unique");if(accepted.size()!=1)return;
        JsonNode position=original.getSourcePosition(),supplied=accepted.get(0).get("sourcePosition");binding.put("reason","source_position_differs");
        if(position!=null&&(!position.equals(supplied)||!source.getSourceSha256().equals(position.path("sourceSha256").asText()))||position==null&&supplied!=null&&!supplied.isNull())return;
        copyRuleContext(read,original,target,context,binding,start,end);
    }
    /** Same captured-document adapter for the existing noncanonical rule path, with no canonical pointer. */
    public static List<VettingRuleEngine.Source> ordinaryRuleSources(String projectId,List<SourceDocument> capturedSources) {
        List<VettingRuleEngine.Source> out=new ArrayList<>();if(capturedSources==null)return out;
        for(SourceDocument source:capturedSources)if(source!=null&&Objects.equals(projectId,source.getProjectId())&&"tender".equals(VettingCorpus.sourceRole(source))){
            RuleSourceRead read=new RuleSourceRead(source);List<com.consense.document.DocumentBlock> originals=read.blocks(),blocks=new ArrayList<>();Map<String,Long> counts=originals.stream().filter(b->b.getId()!=null).collect(Collectors.groupingBy(com.consense.document.DocumentBlock::getId,Collectors.counting()));
            for(com.consense.document.DocumentBlock original:originals){com.consense.document.DocumentBlock block=JsonUtils.read(JsonUtils.write(original),com.consense.document.DocumentBlock.class);ObjectNode context=JsonUtils.mapper().createObjectNode(),binding=context.putObject(VettingPackageIdentity.CONTEXT);block.setReadingContext(context);binding.put("status","unknown");binding.put("reason","ordinary_source_binding_unverified");String text=original.getEffectiveText();
                if("PARSED".equals(source.getParseStatus())&&source.getSourceSha256()!=null&&Objects.equals(text,original.getText())&&counts.getOrDefault(original.getId(),0L)==1L&&(original.getSourcePosition()==null||source.getSourceSha256().equals(original.getSourcePosition().path("sourceSha256").asText())))copyRuleContext(read,original,block,context,binding,0,text==null?0:text.length());
                blocks.add(block);
            }
            out.add(new VettingRuleEngine.Source(String.valueOf(source.getId()),source.getFileKey(),source.getFileName(),read.sourceHash(),blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
        }return out;
    }
    private static void copyRuleContext(RuleSourceRead read,com.consense.document.DocumentBlock original,com.consense.document.DocumentBlock target,ObjectNode context,ObjectNode binding,int start,int end) {
        SourceDocument source=read.source;
        JsonNode position=original.getSourcePosition();String text=original.getEffectiveText();
        JsonNode observed=original.getReadingContext();if(observed!=null){
            copyContextFields(observed,context,"sourceSha256","occurrenceId","story","role","ownerStatus","semanticStatus","adoptionStatus","containerId");
            if(observed.path("nativeDocxSection").isObject())copyContextFields(observed.path("nativeDocxSection"),context.putObject("nativeDocxSection"),"sectionId","ordinalZeroBased","inventoryPointer","membershipStatus","ownerStatus","applicabilityStatus","effectiveSectionState");
            if(observed.path("nativeDocxOutline").isObject())copyContextFields(observed.path("nativeDocxOutline"),context.putObject("nativeDocxOutline"),"ancestorOrdinals","containedHeadingOrdinals");
        }context.set(VettingPackageIdentity.CONTEXT,binding);
        target.setKind(original.getKind());target.setParagraphStyle(original.getParagraphStyle());target.setParagraphStyleName(original.getParagraphStyleName());if(position!=null)target.setSourcePosition(position.deepCopy());
        binding.put("status","source_bound");binding.put("reason","unique_current_document_block_and_accepted_range");binding.put("startOffset",start);binding.put("endOffset",end);binding.put("offsetUnit","utf16");binding.put("sourceHash",read.sourceHash());binding.put("originalSourceSha256",source.getSourceSha256());binding.put("originalBytesStatus","captured_identity_only");binding.put("role",VettingCorpus.sourceRole(source));binding.put("documentScope",source.getDocumentScope());binding.put("completeOriginalBlock",text!=null&&start==0&&end==text.length());
        binding.put("selectedStartsAtSourceLineBoundary",text!=null&&(start==0||text.charAt(start-1)=='\n'||text.charAt(start-1)=='\r'));binding.put("selectedEndsAtSourceLineBoundary",text!=null&&(end==text.length()||text.charAt(end)=='\n'||text.charAt(end)=='\r'));
        bindNativeStory(read,position,binding);
        bindScopeExclusion(read,original,binding);
    }
    private static void copyContextFields(JsonNode source,ObjectNode target,String... keys){for(String key:keys)if(source.has(key)){JsonNode value=source.path(key);boolean allowed=value.isValueNode();if(value.isArray()){allowed=true;for(JsonNode item:value)allowed&=item.isIntegralNumber();}if(allowed)target.set(key,value.deepCopy());}}
    /** Source outline edges may exclude examples/references; they never establish package-own identity. */
    private static void bindScopeExclusion(RuleSourceRead read,com.consense.document.DocumentBlock original,ObjectNode binding) {
        SourceDocument source=read.source;
        ObjectNode scope=binding.putObject("sourceScopeExclusion");scope.put("status","unknown");scope.put("reason","no_explicit_bound_scope_exclusion");
        JsonNode refs=original.getReadingContext()==null?null:original.getReadingContext().path("nativeDocxOutline").path("ancestorOrdinals"),position=original.getSourcePosition();if(refs==null||!refs.isArray()||refs.isEmpty()||position==null||!"docx_native".equals(position.path("kind").asText())||position.path("partName").asText().isEmpty()||!position.path("partSha256").asText().matches("[a-f0-9]{64}"))return;
        JsonNode coverage=read.coverage();try{read.blocks();}catch(RuntimeException invalid){return;}JsonNode headings=coverage.path("nativeDocxOutline").path("headings");if(!headings.isArray())return;
        Set<Integer> visited=new HashSet<>();Set<String> exclusions=new LinkedHashSet<>();List<ObjectNode> edges=new ArrayList<>();
        for(JsonNode ordinal:refs){
            if(!ordinal.isIntegralNumber()||!ordinal.canConvertToInt()||ordinal.asInt()<0||!visited.add(ordinal.asInt())){scope.put("reason","native_outline_scope_binding_unknown");return;}JsonNode heading=headings.get(ordinal.asInt());if(heading==null||heading.path("ordinalZeroBased").asInt(-1)!=ordinal.asInt()){scope.put("reason","native_outline_scope_binding_unknown");return;}
            JsonNode at=heading.path("sourcePosition"),observation=heading.path("textObservation");boolean member=false;for(JsonNode id:heading.path("retainedBlockIds"))if(original.getId().equals(id.asText()))member=true;
            if(!member||!source.getSourceSha256().equals(at.path("sourceSha256").asText())||!position.path("partName").equals(at.path("partName"))||!position.path("partSha256").equals(at.path("partSha256"))||!at.path("nodePath").asText().startsWith("/{")||!at.equals(observation.path("sourcePosition"))||!"observed_parser_paragraph_channels".equals(observation.path("status").asText())||!observation.path("text").isTextual()){scope.put("reason","native_outline_scope_binding_unknown");return;}
            String text=observation.path("text").asText();List<com.consense.document.DocumentBlock> matches=read.headingMatches(at,text);if(matches.size()!=1){scope.put("reason","native_outline_heading_occurrence_not_unique");return;}
            String excluded=VettingPackageIdentity.explicitScopeExclusion(text);if(excluded==null)continue;exclusions.add(excluded);ObjectNode edge=JsonUtils.mapper().createObjectNode();edge.put("ordinalZeroBased",ordinal.asInt());edge.put("sourceBlockId",matches.get(0).getId());edge.put("memberBlockId",original.getId());edge.set("sourcePosition",at.deepCopy());edge.put("sourceTextSha256",VettingCorpus.hash(text));edge.put("relation","native_outline_ancestor");edge.put("classification",excluded);edge.put("basis","exact_bound_source_heading_exclusion_label");edges.add(edge);
        }
        if(exclusions.isEmpty())return;scope.put("status","bound_native_outline_exclusion");scope.put("reason","explicit_current_source_heading_and_ancestor_membership");scope.put("classification",exclusions.size()==1?exclusions.iterator().next():"excluded_explicit_scope");for(ObjectNode edge:edges)scope.withArray("edges").add(edge);
    }
    private static void bindNativeStory(RuleSourceRead read,JsonNode position,ObjectNode binding) {
        SourceDocument source=read.source;
        binding.put("storyStatus","unknown");binding.put("physicalWordPageStatus","unknown");if(position==null||!"docx_native".equals(position.path("kind").asText())||position.path("partName").asText().isEmpty()||!position.path("partSha256").asText().matches("[a-f0-9]{64}"))return;
        JsonNode coverage=read.coverage();
        JsonNode inventory=coverage.path("nativeDocxTables");if(!source.getSourceSha256().equals(inventory.path("sourceSha256").asText()))return;
        List<JsonNode> roots=new ArrayList<>();for(JsonNode part:inventory.path("parts"))if(position.path("partName").equals(part.path("partName"))&&position.path("partSha256").equals(part.path("partSha256")))for(JsonNode root:part.path("loadedRoots")){
            String rootPath=root.path("nodePath").asText(),path=position.path("nodePath").asText();if(rootPath.startsWith("/{")&&Arrays.asList("body","header","footer","footnote","endnote").contains(root.path("kind").asText())&&(path.equals(rootPath)||path.startsWith(rootPath+"/")))roots.add(root);
        }
        if(roots.size()!=1)return;JsonNode root=roots.get(0);binding.put("storyStatus","observed_loaded_native_root");binding.put("storyKind",root.path("kind").asText());binding.set("storyRoot",root.deepCopy());
        JsonNode sections=coverage.path("nativeDocxSections");if(!source.getSourceSha256().equals(sections.path("sourcePosition").path("sourceSha256").asText()))return;
        for(JsonNode section:sections.path("sections"))for(JsonNode relation:section.path("headerFooterBindings")){
            JsonNode target=relation.path("targetPart");if(position.path("partName").equals(target.path("partName"))&&position.path("partSha256").equals(target.path("partSha256"))&&root.path("nodePath").equals(target.path("rootNodePath"))){ObjectNode linked=binding.withArray("sectionRelations").addObject();linked.put("sectionId",section.path("sectionId").asText());linked.set("binding",relation.deepCopy());}
        }
    }
    /** Rebind a literal rule quote to its accepted part; ambiguous locations do not receive a pointer. */
    public void bindRuleEvidence(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        current(session);bindRuleEvidenceUnchecked(session,quotes);
    }
    private static void bindRuleEvidenceUnchecked(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        for(com.consense.web.dto.VettingDtos.FindingEvidence e:quotes){
            String normalizedQuote=VettingCorpus.normalize(e.getQuote());
            List<Chunk> matches=session.chunks.stream().filter(c->c.getDocumentId().equals(e.getDocumentId())&&Objects.equals(c.getCanonicalEvidence().getJavaParsedSourceHash(),e.getSourceHash())&&c.getParts().stream()
                    .anyMatch(p->(Objects.equals(p.getAnchor(),e.getAnchor())||Objects.equals(c.getClauseId(),e.getAnchor()))
                            &&!normalizedQuote.isEmpty()&&VettingCorpus.normalize(p.getText()).contains(normalizedQuote))).collect(Collectors.toList());
            if(matches.size()==1){EvidencePointer p=matches.get(0).getCanonicalEvidence();e.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(p),EvidencePointer.class));e.setSourceHash(p.getJavaParsedSourceHash());e.setOriginalSourceSha256(p.getOriginalSourceSha256());}
            else {e.setLocated(false);e.setCanonicalEvidence(null);}
        }
    }
    /** UTF16 union of accepted block ranges, never whole original-document coverage. */
    public static int observedChars(List<Chunk> selected){
        Map<String,List<int[]>> groups=new LinkedHashMap<>();for(Chunk c:selected){require(canonicalViewStatus(c)==null,"canonical coverage view differs");
            for(Part p:c.getParts())groups.computeIfAbsent(c.getDocumentId()+"|"+c.getSourceHash()+"|"+p.getBlockId(),key->new ArrayList<>()).add(new int[]{p.getStartOffset(),p.getEndOffset()});
        }long total=0;for(List<int[]> ranges:groups.values()){
            ranges.sort(Comparator.comparingInt(a->a[0]));int start=-1,end=-1;for(int[] r:ranges){if(start<0){start=r[0];end=r[1];}
                else if(r[0]<=end)end=Math.max(end,r[1]);else{total+=end-start;start=r[0];end=r[1];}}if(start>=0)total+=end-start;
        }require(total<=Integer.MAX_VALUE,"canonical coverage overflow");return (int)total;
    }
    /** The new context is a separate observed transport envelope, never a ranked seed. */
    private static Map<String,JsonNode> canonicalParents(Session session){Map<String,JsonNode> parents=new LinkedHashMap<>();session.byId.forEach((id,view)->parents.put(id,view.getCanonicalPayload()));return parents;}
    private static boolean completeTransport(JsonNode unit){return Arrays.asList("complete_observed_unit","complete_observed_hierarchy_context","complete_observed_attachment_context","complete_observed_context_union").contains(unit.path("status").asText());}
    /** Same public hierarchy/attachment union as transportUnit, without reconstructing source relations. */
    private static VettingRetrievalSourceUnits.Unit semanticSourceUnit(Map<String,Chunk> byId,List<Chunk> chunks,Chunk chunk){
        JsonNode unit=chunk.getCanonicalPayload().path("semanticUnit"),hierarchy=unit.get("hierarchyContext"),attachment=unit.get("attachmentContext");
        List<JsonNode> selected=new ArrayList<>();if(attachment!=null&&attachment.isObject()){
            if(hierarchy!=null&&hierarchy.isObject())selected.add(hierarchy);selected.add(attachment);
        }else selected.add(hierarchy!=null&&hierarchy.isObject()&&completeTransport(hierarchy)?hierarchy:unit);
        Set<String> required=new LinkedHashSet<>();String reason=null;
        for(JsonNode context:selected){if(!completeTransport(context)){reason=context.path("reason").asText("public_source_context_unknown");break;}
            List<String> ids=VettingRetrievalSourceUnits.textIds(context.path("requiredMemberIds"));if(chunk.getCanonicalPayload().has("semanticUnitRef")){require(ids.contains(chunk.getId()),"semantic dictionary closure omitted its origin");for(String id:ids){Chunk member=byId.get(id);if(member!=null)require(Objects.equals(member.getDocumentId(),chunk.getDocumentId())&&Objects.equals(member.getOriginalSourceSha256(),chunk.getOriginalSourceSha256())&&Objects.equals(member.getSourceHash(),chunk.getSourceHash())&&Objects.equals(member.getRole(),chunk.getRole()),"semantic dictionary closure crosses source scope");}if(ids.stream().anyMatch(id->!byId.containsKey(id))){reason="semantic_closure_member_unavailable";break;}}for(JsonNode member:context.path("members")){
                Chunk canonical=byId.get(member.path("id").asText());require(canonical!=null,"semantic closure member outside scanned scope");ObjectNode original=canonical.getCanonicalPayload().deepCopy();original.remove("semanticUnit");require(original.equals(member),"semantic closure member differs from scanned origin");}
            required.addAll(ids);
        }
        List<String> ordered=new ArrayList<>();if(reason==null)for(Chunk member:chunks)if(required.contains(member.getId()))ordered.add(member.getId());
        ObjectNode trace=unit.deepCopy();trace.remove("members");for(String field:Arrays.asList("hierarchyContext","attachmentContext"))if(trace.path(field).isObject())((ObjectNode)trace.path(field)).remove("members");trace.put("selectionPolicy",OerCanonicalRetrievalClient.SEMANTIC_READ);
        return new VettingRetrievalSourceUnits.Unit(chunk.getId(),reason==null?"complete_observed_unit":"unknown",reason,ordered,JsonUtils.readMap(JsonUtils.write(trace)));
    }
    private static JsonNode transportUnit(JsonNode unit,Map<String,JsonNode> parents){
        JsonNode attachment=unit.path("attachmentContext");
        JsonNode context=unit.path("hierarchyContext");
        // Preserve the v2 consumer behavior when no v3 attachment is declared.
        if(!attachment.isObject())return context.isObject()&&"complete_observed_hierarchy_context".equals(context.path("status").asText())?context:unit;
        if(context.isObject()&&!completeTransport(context))return context;
        if(!completeTransport(attachment))return attachment;
        if(!context.isObject())return attachment;
        Set<String> needed=new LinkedHashSet<>();Map<String,JsonNode> supplied=new LinkedHashMap<>();
        for(JsonNode envelope:Arrays.asList(context,attachment))for(JsonNode member:envelope.path("members")){
            String id=member.path("id").asText();require(parents.containsKey(id)&&parents.get(id).equals(member),"transport context member differs from source");
            require(!supplied.containsKey(id)||supplied.get(id).equals(member),"transport contexts disagree");supplied.put(id,member);needed.add(id);
        }
        // This envelope describes the union. Keep each original edge declaration
        // in its independently validated context on the unchanged source unit.
        String unionPolicy=OerCanonicalAttachmentContext.unionPolicy(attachment.path("policy").asText());
        ObjectNode union=JsonUtils.mapper().createObjectNode();union.put("policy",unionPolicy);union.put("status","complete_observed_context_union");union.putNull("reason");
        union.set("sourceIdentity",attachment.path("sourceIdentity"));union.set("seedObservedUnitId",unit.path("unitId"));
        union.set("hierarchyContextId",context.path("contextId"));union.set("attachmentContextId",attachment.path("contextId"));
        union.put("memberOrder","canonical_source_input");union.put("derivedMembersCanBecomeOrigins",false);union.putNull("memberScores");
        union.put("qualifiersComplete","unknown");union.put("semanticScopeVerified",false);union.put("legalClosureVerified",false);
        com.fasterxml.jackson.databind.node.ArrayNode ids=union.putArray("requiredMemberIds"),members=union.putArray("members"),hashes=union.putArray("memberPayloadSha256");
        parents.forEach((id,member)->{if(needed.contains(id)){ids.add(id);members.add(member.deepCopy());hashes.addObject().put("id",id).put("sha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(member))));}});
        require(!members.isEmpty(),"complete transport union has no source members");
        SortedSet<String> blocks=new TreeSet<>();for(JsonNode member:members)for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
        com.fasterxml.jackson.databind.node.ArrayNode blockIds=union.putArray("blockIds"),ranges=union.putArray("observedRanges");
        for(String block:blocks){blockIds.add(block);ranges.add(OerCanonicalSourceUnits.blockRange(parents,OerCanonicalSourceUnits.identity(members.get(0)),block));}
        ObjectNode address=JsonUtils.mapper().createObjectNode();address.put("policy",unionPolicy);address.set("observedUnitId",unit.path("unitId"));address.set("hierarchyContextId",context.path("contextId"));address.set("attachmentContextId",attachment.path("contextId"));address.set("memberPayloadSha256",hashes);
        union.put("contextId",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(address))));return union;
    }
    private static VectorStore.SearchHit businessHit(Session session,String id,JsonNode unit,double score,String kind){
            Chunk seed=session.byId.get(id);require(seed!=null,"source-unit seed outside session");List<Chunk> members=new ArrayList<>();
            JsonNode transport=transportUnit(unit,canonicalParents(session));
            if(completeTransport(transport))for(JsonNode raw:transport.path("members")){
                Chunk member=session.byId.get(raw.path("id").asText());require(member!=null&&raw.equals(member.getCanonicalPayload()),"context member differs from selected canonical payload");members.add(member);
            }
            else members.add(seed);
            members.sort(Comparator.comparingInt(c->session.chunks.indexOf(c)));
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId",session.projectId);payload.put("documentId",seed.getDocumentId());
            payload.put("sourceHash",seed.getCanonicalEvidence().getJavaParsedSourceHash());payload.put("sourceRole",seed.getRole());
            SortedSet<Integer> pages=new TreeSet<>();for(Chunk member:members)pages.addAll(member.getCanonicalEvidence().getPhysicalPages());
            payload.put("fileLabel",seed.getFileKey()+" · "+seed.getFileName());payload.put("pageNo",pages.stream().map(p->"P"+p).collect(Collectors.joining(", ")));
            payload.put("anchor",seed.getAnchor());payload.put("canonicalPrimaryAnchorScope","ranked_seed_only");
            payload.put("content",members.stream().map(Chunk::getContent).collect(Collectors.joining("\n")));
            payload.put("canonicalEvidence",members.stream().map(c->JsonUtils.read(JsonUtils.write(c.getCanonicalEvidence()),EvidencePointer.class)).collect(Collectors.toList()));
            payload.put("canonicalMembers",members.stream().map(c->c.getCanonicalPayload().deepCopy()).collect(Collectors.toList()));
            payload.put("canonicalSourceUnit",unit.deepCopy());payload.put("applicabilityContextComplete",null);
            payload.put("selectionKind",kind);payload.put("scoreKind","context_only".equals(kind)?"unranked_context":"actual_reranked_seed");
            return new VectorStore.SearchHit(seed.getId(),score,payload);
    }

    private static Chunk view(JsonNode raw,EvidencePointer pointer,SourceDocument source) {
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(pointer.getProjectionSchema());
        boolean normal=NormalObservedContract.isNormal(raw);
        Chunk c=new Chunk();c.setId(raw.path("id").asText());c.setContent(raw.path("content").asText());c.setDocumentId(raw.path("documentId").asText());
        c.setRole(raw.path("role").asText());c.setFileKey(source.getFileKey()==null?"OTHER":source.getFileKey());c.setFileName(pointer.getSourceFilename().asText());
        c.setSourceHash(raw.path("sourceHash").asText());c.setOriginalSourceSha256(raw.path("originalSourceSha256").asText());c.setSourceSha256(raw.path("sourceSha256").asText());
        c.setSourcePurpose(raw.path("sourcePurpose").asText());c.setSourceClass(raw.path("sourceClass").asText());
        c.setEligibleModules(JsonUtils.readList(JsonUtils.write(raw.path("eligibleModules")),String.class));
        c.setClauseId(raw.path("clauseId").isTextual()?raw.path("clauseId").asText():null);
        c.setClauseHeadingLocation(raw.path("clauseHeadingLocation").isTextual()?raw.path("clauseHeadingLocation").asText():null);
        c.setMetadataVersion(raw.path("metadataVersion").asText());c.setSegmentationVersion(raw.path("segmentationVersion").asText());
        c.setNativeTableMetadataVersion(raw.path("nativeTableMetadataVersion").asText());c.setSourceQualityMetadataVersion(raw.path("sourceQualityMetadataVersion").asText());
        c.setSourceQualityHash(raw.path("sourceQualityHash").asText());
        c.setSourceQuality(unknownQuality(normal));
        List<Part> parts=new ArrayList<>();for(JsonNode p:raw.path("parts")) {
            Part part=new Part();part.setText(p.path("text").asText());part.setAnchor(p.path("anchor").asText());part.setBlockId(p.path("blockId").asText());
            part.setStartOffset(p.path("startOffset").asInt());part.setEndOffset(p.path("endOffset").asInt());
            part.setPageNo(nativeDocx?null:normal?normalPage(p):"P"+p.path("physicalPageNo").asInt());
            part.setBbox(nativeDocx||normal?null:xywh(p.path("bbox")));part.setExtractionSource(normal?"normal_observed_candidate":"source_reviewed_canonical");
            if(normal){if(p.has("sourcePosition"))part.setSourcePosition(p.get("sourcePosition").deepCopy());if(p.has("wordObservation"))part.setWordObservation(p.get("wordObservation").deepCopy());}
            parts.add(part);
        }
        c.setParts(parts);c.setAnchor(raw.path("anchor").isTextual()?raw.path("anchor").asText():parts.get(0).getAnchor());
        List<Integer> pages=pointer.getPhysicalPages();c.setPageNo(nativeDocx||normal&&pages.isEmpty()?null:pages.size()==1?"P"+pages.get(0):"P"+pages.get(0)+"–P"+pages.get(pages.size()-1));
        c.setBbox(parts.size()==1?parts.get(0).getBbox():null);c.setCanonicalPayload(raw.deepCopy());c.setCanonicalEvidence(pointer);return c;
    }
    private static Chunk semanticView(Session session,JsonNode origin,JsonNode unit){
        CanonicalSourceBinding binding=null;for(CanonicalSourceBinding candidate:session.semanticScope.bindings())if(String.valueOf(candidate.getDocumentId()).equals(origin.path("documentId").asText()))binding=candidate;
        require(binding!=null,"semantic origin outside selected sources");SourceDocument source=session.semanticDocuments.get(binding.getDocumentId());
        ObjectNode raw=origin.deepCopy();raw.set("semanticUnit",unit.deepCopy());Chunk chunk=new Chunk();chunk.setId(raw.path("id").asText());chunk.setDocumentId(raw.path("documentId").asText());chunk.setContent(raw.path("text").asText());chunk.setRole(raw.path("role").asText());
        chunk.setFileKey(source.getFileKey());chunk.setFileName(source.getFileName());chunk.setSourceHash(binding.getCanonicalSourceHash());chunk.setOriginalSourceSha256(binding.getOriginalSourceSha256());chunk.setSourceSha256(binding.getOriginalSourceSha256());
        chunk.setMetadataVersion(raw.path("metadataVersion").asText());chunk.setSegmentationVersion(raw.path("segmentationVersion").asText());chunk.setNativeTableMetadataVersion(raw.path("nativeTableMetadataVersion").asText());chunk.setSourceQualityMetadataVersion(raw.path("sourceQualityMetadataVersion").asText());chunk.setSourceQualityHash(raw.path("sourceQualityHash").asText());
        chunk.setClauseId(textOrNull(raw,"clauseId"));chunk.setClauseHeadingLocation(session.semanticHeadingLocations.get(chunk.getId()));chunk.setSourceQuality(unknownQuality(NormalObservedContract.PRODUCER.equals(raw.path("producerKind").asText())));chunk.setEligibleModules(JsonUtils.readList(JsonUtils.write(raw.path("eligibleModules")),String.class));
        List<String> anchors=session.semanticAnchors.get(chunk.getId());require(anchors!=null&&anchors.size()==raw.path("parts").size(),"semantic origin differs from cold verified parts");
        List<Part> parts=new ArrayList<>();for(JsonNode part:raw.path("parts")){Part value=new Part();value.setBlockId(part.path("blockId").asText());value.setText(part.path("text").asText());value.setAnchor(anchors.get(parts.size()));value.setStartOffset(part.path("startOffset").asInt());value.setEndOffset(part.path("endOffset").asInt());value.setExtractionSource("semantic_source_unit");parts.add(value);}chunk.setParts(parts);chunk.setAnchor(parts.isEmpty()?chunk.getClauseId():parts.get(0).getAnchor());
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(session.projectId);pointer.setDocumentId(binding.getDocumentId());pointer.setModule(session.module);pointer.setOriginalSourceSha256(binding.getOriginalSourceSha256());pointer.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());
        pointer.setSourceRevisionSha256(binding.getSourceRevisionSha256());pointer.setProjectionSha256(binding.getProjectionSha256());pointer.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());pointer.setCanonicalSourceHash(binding.getCanonicalSourceHash());pointer.setCanonicalChunkId(chunk.getId());pointer.setUnitId(raw.path("unitId").asText());
        pointer.setProjectionSchema(OerCanonicalRetrievalClient.SEMANTIC_READ);pointer.setProducerKind(raw.path("producerKind").asText());pointer.setAddressKind("semantic_source_unit");pointer.setBboxConvention(null);pointer.setOriginalGeometryStatus("unknown");pointer.setVisualReturnStatus("not_requested");pointer.setPhysicalPages(Collections.emptyList());
        pointer.setSourceFilename(JsonUtils.mapper().getNodeFactory().textNode(source.getFileName()));pointer.setSourceOrder(raw.path("sourceOrder").deepCopy());pointer.setParts(raw.path("parts").deepCopy());pointer.setEligibleModules(raw.path("eligibleModules").deepCopy());pointer.setUnknowns(raw.path("unknowns").deepCopy());pointer.setSourceQuality(raw.path("sourceQuality").deepCopy());
        chunk.setCanonicalEvidence(pointer);chunk.setCanonicalPayload(raw);return chunk;
    }
    private static List<Double> xywh(JsonNode b){if(!b.isArray()||b.size()!=4)return null;return Arrays.asList(b.get(0).asDouble(),b.get(1).asDouble(),b.get(2).asDouble()-b.get(0).asDouble(),b.get(3).asDouble()-b.get(1).asDouble());}
    private static String normalPage(JsonNode part){return part.path("physicalPageNo").isIntegralNumber()&&part.path("physicalPageNo").asInt()>0?"P"+part.path("physicalPageNo").asInt():null;}
    private static Chunk copy(Chunk c){require(c!=null,"ranked canonical chunk missing");return JsonUtils.read(JsonUtils.write(c),Chunk.class);}

    /** Checks an opaque canonical payload against the read-only model view; no legacy-version substitution. */
    static String canonicalViewStatus(Chunk c) {
        JsonNode raw=c.getCanonicalPayload();EvidencePointer p=c.getCanonicalEvidence();if(raw==null||p==null)return "canonical_view_missing";
        if(OerCanonicalRetrievalClient.SEMANTIC_READ.equals(raw.path("semanticProjectionVersion").asText())){
            if(!OerCanonicalRetrievalClient.SEMANTIC_READ.equals(p.getProjectionSchema())||!"semantic_source_unit".equals(p.getAddressKind())
                    ||!Objects.equals(c.getId(),raw.path("id").asText())||!Objects.equals(c.getId(),p.getCanonicalChunkId())||!Objects.equals(c.getContent(),raw.path("text").asText())
                    ||!Objects.equals(c.getDocumentId(),raw.path("documentId").asText())||!Objects.equals(c.getDocumentId(),String.valueOf(p.getDocumentId()))||!Objects.equals(c.getRole(),raw.path("role").asText())
                    ||!Objects.equals(c.getSourceHash(),raw.path("sourceHash").asText())||!Objects.equals(c.getSourceHash(),p.getCanonicalSourceHash())||!Objects.equals(c.getOriginalSourceSha256(),p.getOriginalSourceSha256())
                    ||!Objects.equals(p.getProjectionSha256(),raw.path("evidenceRef").path("projectionSha256").asText())||!Objects.equals(p.getParts(),raw.path("parts"))
                    ||c.getParts()==null||c.getParts().size()!=raw.path("parts").size())return "semantic_view_identity_differs";
            for(int i=0;i<c.getParts().size();i++){Part part=c.getParts().get(i);JsonNode original=raw.path("parts").get(i);if(!Objects.equals(part.getBlockId(),original.path("blockId").asText())||!Objects.equals(part.getText(),original.path("text").asText())||part.getStartOffset()!=original.path("startOffset").asInt()||part.getEndOffset()!=original.path("endOffset").asInt()||part.getEndOffset()-part.getStartOffset()!=part.getText().length())return "semantic_view_parts_differ";}
            return null;
        }
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(p.getProjectionSchema());
        boolean normal=NormalObservedContract.isNormal(raw)||NormalObservedContract.SCHEMA.equals(p.getProjectionSchema())||NormalObservedContract.PRODUCER.equals(p.getProducerKind());
        boolean family=NativePdfSourceFamilyContract.isFamily(raw)||NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind());
        boolean nativePdf=!family&&(NativePdfCanonicalContract.isNativePdf(raw)||NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind()));
        if(normal){
            try{NormalObservedContract.validateChunk(raw);}catch(RuntimeException rejected){return "canonical_view_normal_contract_differs";}
            List<Integer> pages=p.getPhysicalPages();String label=pages==null||pages.isEmpty()?null:pages.size()==1?"P"+pages.get(0):"P"+pages.get(0)+"–P"+pages.get(pages.size()-1);
            if(!NormalObservedContract.SCHEMA.equals(p.getProjectionSchema())||!NormalObservedContract.PRODUCER.equals(p.getProducerKind())
                    ||!NormalObservedContract.VERSION.equals(raw.path("projectionVersion").asText())||p.getStorageShape()!=null||p.getBboxConvention()!=null
                    ||!"unknown".equals(p.getOriginalGeometryStatus())||!"unknown".equals(p.getVisualReturnStatus())
                    ||p.getNativePdfEvidence()!=null||p.getNativePdfSourceFamilyEvidence()!=null||p.getSourceFamilyOriginRef()!=null
                    ||!Objects.equals(label,c.getPageNo())||c.getBbox()!=null)return "canonical_view_normal_contract_differs";
        }else if(family){
            try{NativePdfSourceFamilyContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfSourceFamilyContract.validateOriginDisplay(raw,p.getNativePdfSourceFamilyEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_family_contract_differs";}
            JsonNode d=p.getNativePdfSourceFamilyEvidence();JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfSourceFamilyContract.STORAGE.equals(p.getStorageShape())||!NativePdfSourceFamilyContract.REVISION.equals(p.getSourceRevisionKind())
                    ||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativePdfSourceFamilyContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())||!Objects.equals(raw.path("sourceFamilyOriginRef"),p.getSourceFamilyOriginRef())
                    ||!Objects.equals(p.getFamilyCompositionSha256(),d.path("compositionSha256").asText())||!Objects.equals(p.getFamilyComponentSetSha256(),d.path("componentSetSha256").asText())
                    ||!raw.path("sourceQuality").equals(d.path("sourceQuality"))||!NativePdfSourceFamilyContract.unknowns(d).equals(p.getUnknowns())||p.getNativePdfEvidence()!=null
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null))return "canonical_view_native_pdf_family_contract_differs";
        }else if(nativePdf){
            try{NativePdfCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfCanonicalContract.validateDisplayDeclarations(p.getNativePdfEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_contract_differs";}
            JsonNode declarations=p.getNativePdfEvidence();
            JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfCanonicalContract.STORAGE.equals(p.getStorageShape())||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())
                    ||!NativePdfCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null)
                    ||declarations==null||!declarations.isObject()||!declarations.path("semanticScopeVerified").isBoolean()||declarations.path("semanticScopeVerified").asBoolean()
                    ||!declarations.path("humanConfirmed").isBoolean()||declarations.path("humanConfirmed").asBoolean()
                    ||!"unknown".equals(declarations.path("wholeOriginalSourceCoverage").asText())
                    ||!NativePdfCanonicalContract.VISUAL.equals(declarations.path("visualReturnStatus").asText())
                    ||!declarations.path("limitations").isArray()||declarations.path("limitations").isEmpty())return "canonical_view_native_pdf_contract_differs";
        }else if(nativeDocx){
            try{NativeCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());}catch(RuntimeException rejected){return "canonical_view_native_contract_differs";}
            if(!NativeCanonicalContract.PRODUCER.equals(p.getProducerKind())||!NativeCanonicalContract.STORAGE.equals(p.getStorageShape())
                    ||!NativeCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativeCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||p.getBboxConvention()!=null||c.getPageNo()!=null||c.getBbox()!=null)return "canonical_view_native_contract_differs";
        }else if(!"consense.canonical-projection.v1".equals(p.getProjectionSchema())||raw.has("storageShape"))return "canonical_view_identity_differs";
        if(!Objects.equals(c.getId(),raw.path("id").asText())||!Objects.equals(c.getId(),p.getCanonicalChunkId())
                ||!Objects.equals(c.getSourceHash(),raw.path("sourceHash").asText())||!Objects.equals(c.getSourceHash(),p.getCanonicalSourceHash())
                ||!Objects.equals(c.getDocumentId(),raw.path("documentId").asText())||!Objects.equals(c.getDocumentId(),String.valueOf(p.getDocumentId()))||!Objects.equals(c.getContent(),raw.path("content").asText())
                ||!Objects.equals(c.getRole(),raw.path("role").asText())||!Objects.equals(c.getSourceQualityHash(),raw.path("sourceQualityHash").asText())
                ||!Objects.equals(c.getMetadataVersion(),raw.path("metadataVersion").asText())||!Objects.equals(c.getSegmentationVersion(),raw.path("segmentationVersion").asText())
                ||!Objects.equals(c.getNativeTableMetadataVersion(),raw.path("nativeTableMetadataVersion").asText())||!Objects.equals(c.getSourceQualityMetadataVersion(),raw.path("sourceQualityMetadataVersion").asText())
                ||!Objects.equals(c.getSourceSha256(),raw.path("sourceSha256").asText())||!Objects.equals(c.getOriginalSourceSha256(),raw.path("originalSourceSha256").asText())
                ||!Objects.equals(c.getOriginalSourceSha256(),p.getOriginalSourceSha256())||!Objects.equals(p.getSourceRevisionSha256(),raw.path("sourceRevisionSha256").asText())
                ||!Objects.equals(p.getProjectionRevisionSha256(),raw.path("projectionRevisionSha256").asText())||!Objects.equals(p.getUnitId(),raw.path("unitId").asText())
                ||!Objects.equals(c.getSourcePurpose(),raw.path("sourcePurpose").asText())||!Objects.equals(c.getSourceClass(),raw.path("sourceClass").asText())
                ||!Objects.equals(c.getClauseId(),textOrNull(raw,"clauseId"))||!Objects.equals(c.getClauseHeadingLocation(),textOrNull(raw,"clauseHeadingLocation"))
                ||!Objects.equals(c.getSourceQuality(),unknownQuality(normal))||!Objects.equals(c.getFileName(),p.getSourceFilename().asText())
                ||!(normal?"normal_observed_unit":"canonical_adoption_unit").equals(p.getAddressKind())||(!normal&&!nativeDocx&&!nativePdf&&!family&&!"normalized_xyxy_original_page".equals(p.getBboxConvention()))
                ||!Objects.equals(raw.path("eligibleModules"),JsonUtils.mapper().valueToTree(c.getEligibleModules()))
                ||!Objects.equals(raw.path("physicalPages"),JsonUtils.mapper().valueToTree(p.getPhysicalPages()))
                ||!raw.path("eligibleModules").equals(p.getEligibleModules())||!raw.path("parts").equals(p.getParts())
                ||!raw.path("sourceSpans").equals(p.getSourceSpans())||!Objects.equals(raw.get("tableGroup"),p.getTableGroup())
                ||!Objects.equals(raw.get("nativeTableGroupsRef"),p.getNativeTableGroupsRef())||!Objects.equals(raw.get("nativeTableSummary"),p.getNativeTableSummary())
                ||!Objects.equals(textOrNull(raw,"sourceEditionId"),p.getSourceEditionId())||!Objects.equals(textOrNull(raw,"sourceUnitId"),p.getSourceUnitId())
                ||!Objects.equals(textOrNull(raw,"slUnitScopeManifestSha256"),p.getSlUnitScopeManifestSha256())
                ||!raw.path("assetRefs").equals(p.getAssetRefs())||!raw.path("relations").equals(p.getRelations())
                ||(normal?p.getAdoption()!=null&&!p.getAdoption().isMissingNode()&&!p.getAdoption().isNull():!raw.path("adoption").equals(p.getAdoption()))||!raw.path("sourceQuality").equals(p.getSourceQuality())
                ||!raw.path("sourceOrder").equals(p.getSourceOrder())
                ||p.isHumanConfirmed()||!"unknown".equals(raw.path("sourceQuality").path("semanticQuality").asText()))return "canonical_view_identity_differs";
        if(c.getParts()==null||c.getParts().size()!=raw.path("parts").size())return "canonical_view_parts_differ";
        for(int i=0;i<c.getParts().size();i++){Part part=c.getParts().get(i);JsonNode original=raw.path("parts").get(i);
            if(!Objects.equals(part.getText(),original.path("text").asText())||!Objects.equals(part.getAnchor(),original.path("anchor").asText())
                    ||!Objects.equals(part.getBlockId(),original.path("blockId").asText())||part.getStartOffset()!=original.path("startOffset").asInt()
                    ||part.getEndOffset()!=original.path("endOffset").asInt()||part.getEndOffset()-part.getStartOffset()!=part.getText().length()
                    ||!Objects.equals(part.getBbox(),nativeDocx||normal?null:xywh(original.path("bbox")))||!Objects.equals(part.getPageNo(),nativeDocx?null:normal?normalPage(original):"P"+original.path("physicalPageNo").asInt())
                    ||!(normal?"normal_observed_candidate":"source_reviewed_canonical").equals(part.getExtractionSource())
                    ||normal&&(!Objects.equals(part.getSourcePosition(),original.get("sourcePosition"))||!Objects.equals(part.getWordObservation(),original.get("wordObservation"))))return "canonical_view_parts_differ";
        }return null;
    }
    private static String textOrNull(JsonNode raw,String field){return raw.path(field).isTextual()?raw.path(field).asText():null;}
    private static VettingSourceQuality.Info unknownQuality(boolean normal){VettingSourceQuality.Info info=new VettingSourceQuality.Info();
        info.getLimitations().add(normal?"normal_observations_are_unreviewed_source_quality_and_whole_source_coverage_unknown":"canonical_adoption_does_not_establish_whole_source_semantics_or_coverage");return info;}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Canonical business evidence rejected: "+why);}
}
