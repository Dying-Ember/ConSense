package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.*;
import javax.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual save and revision/current views with synthetic immutable snapshots; no files, database or model. */
class DraftCurrentDefinitionReviewViewTest {
    @Test void savedViewsDistinguishCurrentReviewsFromHistoricalFlagsWithoutChangingTheGraph(){
        Fixture f=new Fixture();Map<String,Object> candidate=matched("fixed-candidate");candidate.put("definitionKind","current_fixed_reference");candidate.put("definitionReview",obj("confirmed",false));map(candidate.get("catalogAlignment")).put("confirmed",false);candidate.put("currentDefinitionReference",obj("kind","registered_current_instruction","existing",false,"ruleVersion",DraftBusinessRules.RULE_VERSION,"baselineKey","periodAtLeast39Months"));
        Map<String,Object> graph=graph(node("fresh","text"),node("new-stale","text"),node("local-stale","text"),matched("matched-stale"),node("pending","text"),candidate);
        map(nodes(graph).get(1)).put("catalogAlignment",obj("status","new","confirmed",true,"reason","Independent manual fact"));
        nodes(graph).get(4).put("definitionReview",obj("confirmed",false));
        Map<String,Object> saved=f.save(graph),historical=copy(map(saved.get("graph")));
        for(int index:Arrays.asList(1,2,3))nodes(historical).get(index).put("definitionScope","Additional source context after the old review: "+index);
        DraftTemplateGraphRevision revision=f.install("historical",historical,"DRAFT","[]");String before=revision.getGraphJson();
        Map<String,Object> view=f.service.revision(Fixture.PROJECT,revision.getId()),review=map(view.get("definitionGraphReview"));
        assertEquals(Collections.singletonList("fresh"),list(review.get("currentDefinitionReviewFieldIds")));
        assertEquals(Arrays.asList("fresh","matched-stale","fixed-candidate"),list(review.get("definitionGroupReadyFieldIds")));
        assertEquals(Collections.emptyList(),list(review.get("confirmedFieldIds")));assertEquals(Arrays.asList("fresh","new-stale","local-stale","matched-stale","pending","fixed-candidate"),list(review.get("pendingFieldIds")));assertEquals(Collections.singletonList("fixed-candidate"),list(review.get("fixedCandidateFieldIds")));
        assertEquals(review,map(map(f.service.current(Fixture.PROJECT).get("draft")).get("definitionGraphReview")));
        assertEquals(before,revision.getGraphJson());assertEquals(before,JsonUtils.write(view.get("graph")));assertEquals(true,map(nodes(map(view.get("graph"))).get(1).get("definitionReview")).get("confirmed"));
        assertTrue(assertThrows(BizException.class,()->f.service.confirmDefinitions(Fixture.PROJECT,"historical",null,"Old flag is insufficient",true,Collections.singletonList("local-stale"))).getMessage().contains("GRAPH_DEFINITION_REVIEW_REQUIRED"));
        assertEquals(before,revision.getGraphJson());verifyNoInteractions(f.ai,f.bindings,f.variables);
    }

    @Test void matchedHistoricalReviewCanStillBeExplicitlyReconfirmedThroughTheExistingGroupPath(){
        Fixture f=new Fixture();Map<String,Object> saved=f.save(graph(matched("matched"))),historical=copy(map(saved.get("graph")));
        nodes(historical).get(0).put("definitionScope","Expanded source context checked against the same fixed definition");DraftTemplateGraphRevision old=f.install("old-matched",historical,"DRAFT","[]");
        Map<String,Object> before=f.service.revision(Fixture.PROJECT,old.getId());assertTrue(list(map(before.get("definitionGraphReview")).get("currentDefinitionReviewFieldIds")).isEmpty());
        Map<String,Object> approved=f.service.confirmDefinitions(Fixture.PROJECT,old.getId(),null,"Human rechecks this current fixed correspondence",true,Collections.singletonList("matched"));Map<String,Object> review=map(approved.get("definitionGraphReview"));
        assertEquals(Collections.singletonList("matched"),list(review.get("currentDefinitionReviewFieldIds")));assertEquals(Collections.singletonList("matched"),list(review.get("confirmedFieldIds")));assertTrue(list(review.get("pendingFieldIds")).isEmpty());assertTrue(list(review.get("fixedCandidateFieldIds")).isEmpty());
        assertEquals(Collections.singletonList("matched"),list(review.get("definitionGroupReadyFieldIds")));
        assertNotEquals(old.getId(),approved.get("id"));assertEquals(true,map(nodes(map(approved.get("graph"))).get(0).get("catalogAlignment")).get("confirmed"));assertEquals(before.get("graph"),JsonUtils.readMap(old.getGraphJson()));
        verifyNoInteractions(f.ai,f.bindings,f.variables);
    }

    @Test void anOutdatedChildReviewPreventsItsOtherwiseCurrentRootFromBeingListed(){
        Fixture f=new Fixture();Map<String,Object> root=node("record","record"),child=node("child","text");child.put("parentId","record");Map<String,Object> saved=f.save(graph(root,child));
        assertEquals(Collections.singletonList("record"),list(map(saved.get("definitionGraphReview")).get("currentDefinitionReviewFieldIds")));
        Map<String,Object> historical=copy(map(saved.get("graph")));map(nodes(historical).get(1).get("definitionReview")).put("basis","old-child-review");DraftTemplateGraphRevision old=f.install("old-child",historical,"DRAFT","[]");String before=old.getGraphJson();
        Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,old.getId()).get("definitionGraphReview"));assertTrue(list(review.get("currentDefinitionReviewFieldIds")).isEmpty());assertTrue(list(review.get("definitionGroupReadyFieldIds")).isEmpty());assertEquals(Collections.singletonList("record"),list(review.get("pendingFieldIds")));assertTrue(list(review.get("confirmedFieldIds")).isEmpty());assertEquals(before,old.getGraphJson());
    }

    @Test void matchedParentsCanReprepareTheirOwnBasisButCannotHideStaleManualChildren(){
        for(String variant:Arrays.asList("parent-only","child-new","child-local")){
            Fixture f=new Fixture();Map<String,Object> root=aligned("parent","list","billNos",true),number=aligned("number","text","billNos.number",true),description=aligned("description","text","billNos.description",true),extra=node("extra","text");
            for(Map<String,Object> child:Arrays.asList(number,description,extra))child.put("parentId","parent");if("child-new".equals(variant))extra.put("catalogAlignment",obj("status","new","confirmed",true,"reason","Independent child fact"));
            Map<String,Object> saved=f.save(graph(root,number,description,extra)),historical=copy(map(saved.get("graph")));nodes(historical).get(0).put("definitionScope","Expanded parent source context");if(!"parent-only".equals(variant))nodes(historical).get(3).put("definitionScope","Child proof changed after its review");
            DraftTemplateGraphRevision old=f.install(variant,historical,"DRAFT","[]");String before=old.getGraphJson();Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,old.getId()).get("definitionGraphReview"));assertTrue(list(review.get("currentDefinitionReviewFieldIds")).isEmpty());
            if("parent-only".equals(variant)){
                assertEquals(Collections.singletonList("parent"),list(review.get("definitionGroupReadyFieldIds")));Map<String,Object> approved=f.service.confirmDefinitions(Fixture.PROJECT,old.getId(),null,"Recheck the matched parent with current children",true,Collections.singletonList("parent"));assertEquals(Collections.singletonList("parent"),list(map(approved.get("definitionGraphReview")).get("currentDefinitionReviewFieldIds")));
            }else{
                assertTrue(list(review.get("definitionGroupReadyFieldIds")).isEmpty(),variant);assertTrue(assertThrows(BizException.class,()->f.service.confirmDefinitions(Fixture.PROJECT,old.getId(),null,"A matched parent cannot approve the changed child",true,Collections.singletonList("parent"))).getMessage().contains("GRAPH_DEFINITION_REVIEW_REQUIRED: extra"));
            }
            assertEquals(before,old.getGraphJson());verifyNoInteractions(f.ai,f.bindings,f.variables);
        }
    }

    @Test void fixedCandidatePreparationIncludesOnlyItsStandardOrAlreadyReviewedChildren(){
        for(boolean staleCustom:Arrays.asList(false,true)){
            Fixture f=new Fixture();Map<String,Object> root=candidate("candidate-parent","list","billNos"),number=candidate("candidate-number","text","billNos.number"),description=candidate("candidate-description","text","billNos.description"),extra=node("extra","text");for(Map<String,Object> child:Arrays.asList(number,description,extra))child.put("parentId","candidate-parent");
            Map<String,Object> saved=f.save(graph(root,number,description,extra)),historical=copy(map(saved.get("graph")));if(staleCustom)nodes(historical).get(3).put("definitionScope","Changed custom child proof");DraftTemplateGraphRevision old=f.install("candidate-tree",historical,"DRAFT","[]");String before=old.getGraphJson();Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,old.getId()).get("definitionGraphReview"));
            assertEquals(Collections.singletonList("candidate-parent"),list(review.get("fixedCandidateFieldIds")));assertTrue(list(review.get("currentDefinitionReviewFieldIds")).isEmpty());assertEquals(staleCustom?Collections.emptyList():Collections.singletonList("candidate-parent"),list(review.get("definitionGroupReadyFieldIds")));assertEquals(before,old.getGraphJson());
            // Read readiness never substitutes for the explicit action's full current-source proof validation.
            if(!staleCustom)assertTrue(assertThrows(BizException.class,()->f.service.confirmDefinitions(Fixture.PROJECT,old.getId(),null,"Synthetic identity is only a read hint",true,Collections.singletonList("candidate-parent"))).getMessage().contains("KNOWN_ROOT_REVIEW_CURRENT_REFERENCE_REQUIRED"));
            DraftTemplateGraphRevision stale=f.install("candidate-source-stale",historical,"DRAFT",JsonUtils.write(Collections.singletonList(obj("sourceDocumentId",99L,"sourceSha256","unavailable-original"))));Map<String,Object> view=f.service.revision(Fixture.PROJECT,stale.getId()),staleReview=map(view.get("definitionGraphReview"));assertEquals(true,view.get("sourceStale"));assertTrue(list(staleReview.get("currentDefinitionReviewFieldIds")).isEmpty());assertTrue(list(staleReview.get("definitionGroupReadyFieldIds")).isEmpty());assertTrue(list(staleReview.get("fixedCandidateFieldIds")).isEmpty());assertEquals(before,old.getGraphJson());
            verify(f.reader,never()).bindSource(any());verifyNoInteractions(f.ai,f.bindings,f.variables);
        }
    }

    @Test void staleSourcesNeverExposeCurrentReviewFlagsAndConfirmedCohortsKeepTheirExistingMeaning(){
        Fixture f=new Fixture();Map<String,Object> saved=f.save(graph(node("fresh","text"))),graph=copy(map(saved.get("graph")));DraftTemplateGraphRevision confirmed=f.install("confirmed",graph,"CONFIRMED","[]");
        Map<String,Object> current=map(f.service.revision(Fixture.PROJECT,confirmed.getId()).get("definitionGraphReview"));assertEquals(Collections.singletonList("fresh"),list(current.get("currentDefinitionReviewFieldIds")));assertEquals(Collections.singletonList("fresh"),list(current.get("confirmedFieldIds")));assertTrue(list(current.get("pendingFieldIds")).isEmpty());
        Map<String,Object> oldReview=copy(graph);nodes(oldReview).get(0).put("nameEn","Historical confirmed revision with an outdated local review");DraftTemplateGraphRevision historical=f.install("confirmed-old-review",oldReview,"CONFIRMED","[]");Map<String,Object> historicalReview=map(f.service.revision(Fixture.PROJECT,historical.getId()).get("definitionGraphReview"));assertTrue(list(historicalReview.get("currentDefinitionReviewFieldIds")).isEmpty());assertEquals(Collections.singletonList("fresh"),list(historicalReview.get("confirmedFieldIds")),"The new read projection does not change existing whole-cohort authorization");
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId(Fixture.PROJECT);when(f.sources.findDraftReviewSourceByProjectIdAndId(Fixture.PROJECT,1L)).thenReturn(Optional.of(source));when(f.reader.archive(source)).thenReturn(JsonUtils.mapper().valueToTree(obj("sourceDocumentId",1L,"sourceSha256","changed-source")));
        DraftTemplateGraphRevision stale=f.install("stale-original",graph,"CONFIRMED",JsonUtils.write(Collections.singletonList(obj("sourceDocumentId",1L,"sourceSha256","reviewed-source"))));String before=stale.getGraphJson();
        Map<String,Object> view=f.service.revision(Fixture.PROJECT,stale.getId()),review=map(view.get("definitionGraphReview"));assertEquals(true,view.get("sourceStale"));assertTrue(list(review.get("currentDefinitionReviewFieldIds")).isEmpty());assertTrue(list(review.get("definitionGroupReadyFieldIds")).isEmpty());assertTrue(list(review.get("confirmedFieldIds")).isEmpty());assertEquals(Collections.singletonList("fresh"),list(review.get("pendingFieldIds")));assertTrue(list(review.get("fixedCandidateFieldIds")).isEmpty());assertEquals(false,review.get("valid"));assertEquals(before,stale.getGraphJson());
        verify(f.reader,times(1)).archive(source);verify(f.reader,never()).archivedBytes(any());verify(f.reader,never()).readingCompact(any());verify(f.reader,never()).bindSource(any());verifyNoInteractions(f.ai,f.bindings,f.variables);
    }

    @Test void probeRealFixedBaselineGroupConfirmation(){
        Fixture f=new Fixture();Map<String,Object> graph=DraftFixedBaseline.restore(fixedBlank()),root=fixedField(graph,"billNos");String rootId=text(root.get("id"));
        assertTrue(DraftFixedBaseline.installed(graph));assertTrue(DraftFixedBaseline.exactSource(graph,root));String before=JsonUtils.write(graph);
        DraftTemplateGraphRevision rev=f.install("probe-fixed",graph,"DRAFT","[]");Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,rev.getId()).get("definitionGraphReview"));
        assertTrue(list(review.get("fixedCandidateFieldIds")).contains(rootId));assertTrue(list(review.get("definitionGroupReadyFieldIds")).contains(rootId));assertFalse(list(review.get("currentDefinitionReviewFieldIds")).contains(rootId));
        Map<String,Object> direct=DraftFixedBaseline.confirmGroup(graph,Collections.singletonList(rootId),"Checked synthetic fixed group");
        assertEquals(true,map(fixedField(direct,"billNos.number").get("definitionReview")).get("confirmed"));assertEquals(true,map(fixedField(direct,"billNos.description").get("definitionReview")).get("confirmed"));
        Map<String,Object> confirmed=f.service.confirmDefinitions(Fixture.PROJECT,rev.getId(),null,"Checked synthetic fixed group",true,Collections.singletonList(rootId));
        assertTrue(list(map(confirmed.get("definitionGraphReview")).get("confirmedFieldIds")).contains(rootId));assertEquals(before,rev.getGraphJson());
        System.out.println("FIXED_PROBE complete standard children ready=true and confirmDefinitions=success");
    }
    @Test void probeFixedBaselineWithStaleCustomChild(){
        for(String variant:Arrays.asList("new","no-catalog")){
            Fixture f=new Fixture();Map<String,Object> graph=DraftFixedBaseline.restore(fixedBlank()),root=fixedField(graph,"billNos");String rootId=text(root.get("id"));
            Map<String,Object> extra=node("synthetic-custom-"+variant,"text");extra.put("parentId",rootId);extra.put("definitionReview",obj("confirmed",true,"basis","v1:stale"));
            if("new".equals(variant))extra.put("catalogAlignment",obj("status","new","confirmed",true,"reason","Independent synthetic child"));
            ((List<Object>)state(graph).get("variables")).add(extra);assertTrue(DraftFixedBaseline.exactSource(graph,root));String before=JsonUtils.write(graph);
            DraftTemplateGraphRevision rev=f.install("probe-custom-"+variant,graph,"DRAFT","[]");Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,rev.getId()).get("definitionGraphReview"));
            assertTrue(list(review.get("fixedCandidateFieldIds")).contains(rootId));assertFalse(list(review.get("definitionGroupReadyFieldIds")).contains(rootId));
            assertThrows(BizException.class,()->f.service.confirmDefinitions(Fixture.PROJECT,rev.getId(),null,"Checked synthetic fixed group",true,Collections.singletonList(rootId)));
            assertEquals(before,rev.getGraphJson());System.out.println("FIXED_PROBE custom="+variant+" stale ready=false confirmDefinitions=rejected");
        }
    }
    @Test void probeFixedBaselineInvalidStandardChildCannotConfirm(){
        for(String defect:Arrays.asList("missing","excluded","changed")){
            Fixture f=new Fixture();Map<String,Object> graph=DraftFixedBaseline.restore(fixedBlank()),root=fixedField(graph,"billNos"),child=fixedField(graph,"billNos.number");String rootId=text(root.get("id"));
            if("missing".equals(defect))list(state(graph).get("variables")).remove(child);else if("excluded".equals(defect))child.put("status","excluded");else child.put("questionEn","A changed standard child question");
            DraftTemplateGraphRevision rev=f.install("probe-defect-"+defect,graph,"DRAFT","[]");Map<String,Object> review=map(f.service.revision(Fixture.PROJECT,rev.getId()).get("definitionGraphReview"));boolean ready=list(review.get("definitionGroupReadyFieldIds")).contains(rootId);String rejection="none";
            try{DraftFixedBaseline.confirmGroup(graph,Collections.singletonList(rootId),"Checked synthetic fixed group");}catch(RuntimeException ex){rejection=ex.getClass().getSimpleName()+":"+ex.getMessage();}
            assertNotEquals("none",rejection,"invalid standard child cannot acquire fixed authority");System.out.println("FIXED_PROBE defect="+defect+" ready="+ready+" confirmGroup="+rejection);
        }
    }
    private static Map<String,Object> fixedBlank(){
        List<Object> sources=Arrays.asList(fixedOriginal("NTT","n","60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c"),fixedOriginal("SCT","t","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4"),fixedOriginal("SCC","c","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"));
        return obj("kind","consense-template-graph-v1","graph",obj("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true),"sources",sources,"selectedDocuments",obj("NTT","n","SCT","t","SCC","c"));
    }
    private static Map<String,Object> fixedOriginal(String file,String id,String hash){return obj("id",id,"docId",id,"editionId",id,"fileKey",file,"sourceSha256",hash,"format","docx","evidenceStatus","bound");}
    private static Map<String,Object> fixedField(Map<String,Object> graph,String key){return nodes(graph).stream().filter(n->key.equals(map(n.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow();}

    private static Map<String,Object> node(String id,String kind){return obj("id",id,"nameZh",id,"nameEn",id,"type",obj("kind",kind),"role",obj("kind","input"),"status","pending","docIds",Collections.emptyList(),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList(),"options",Collections.emptyList(),"definitionOrigin",obj("kind","manual","reason","Test-only independently supplied fact"),"definitionReview",obj("confirmed",true,"basis",null));}
    private static Map<String,Object> aligned(String id,String kind,String key,boolean confirmed){Map<String,Object> node=node(id,kind);node.put("catalogAlignment",obj("status","matched","confirmed",confirmed,"baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Test-only human fixed correspondence"));return node;}
    private static Map<String,Object> matched(String id){return aligned(id,"boolean","periodAtLeast39Months",true);}
    private static Map<String,Object> candidate(String id,String kind,String key){Map<String,Object> node=aligned(id,kind,key,false);node.put("definitionKind","current_fixed_reference");node.put("definitionReview",obj("confirmed",false));node.put("currentDefinitionReference",obj("kind","registered_current_instruction","existing",false,"ruleVersion",DraftBusinessRules.RULE_VERSION,"baselineKey",key));return node;}
    private static Map<String,Object> graph(Map<String,Object>... nodes){return obj("kind","consense-template-graph-v1","graph",obj("variables",new ArrayList<>(Arrays.asList(nodes)),"events",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>()),"sources",Collections.emptyList(),"selectedDocuments",Collections.emptyMap());}
    private static Map<String,Object> copy(Map<String,Object> graph){return JsonUtils.readMap(JsonUtils.write(graph));}

    private static final class Fixture {
        static final String PROJECT="current-definition-review-view";
        final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);final DraftReviewSources reader=mock(DraftReviewSources.class);
        final DraftTemplateGraphRevisionRepository revisions=mock(DraftTemplateGraphRevisionRepository.class);final DraftTemplateGraphHeadRepository heads=mock(DraftTemplateGraphHeadRepository.class);
        final DraftGraphInputBindingRepository bindings=mock(DraftGraphInputBindingRepository.class);final DraftVariableRepository variables=mock(DraftVariableRepository.class);final AiGateway ai=mock(AiGateway.class);
        final Map<String,DraftTemplateGraphRevision> stored=new LinkedHashMap<>();final DraftTemplateGraphHead head=new DraftTemplateGraphHead();final DraftTemplateGraphService service;
        Fixture(){
            ProjectService projects=mock(ProjectService.class);Project project=new Project();project.setId(PROJECT);EntityManager entityManager=mock(EntityManager.class);when(projects.require(PROJECT)).thenReturn(project);when(entityManager.find(Project.class,PROJECT,LockModeType.PESSIMISTIC_WRITE)).thenReturn(project);
            head.setProjectId(PROJECT);when(heads.findById(PROJECT)).thenReturn(Optional.of(head));when(heads.save(any())).thenAnswer(call->call.getArgument(0));when(revisions.findByIdAndProjectId(anyString(),eq(PROJECT))).thenAnswer(call->Optional.ofNullable(stored.get(call.getArgument(0))));when(revisions.save(any())).thenAnswer(call->{DraftTemplateGraphRevision revision=call.getArgument(0);stored.put(revision.getId(),revision);return revision;});
            ArrayNode empty=JsonUtils.mapper().createArrayNode();when(reader.sources(anyList())).thenReturn(empty);when(reader.sources(anyList(),anyMap())).thenReturn(empty);when(sources.findDraftReviewSourcesByProjectIdAndCategory(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE)).thenReturn(Collections.emptyList());when(sources.findDraftReviewSourcesByProjectId(PROJECT)).thenReturn(Collections.emptyList());
            service=new DraftTemplateGraphService(projects,sources,revisions,heads,bindings,variables,mock(DraftExtractionRunRepository.class),reader,ai,mock(DraftExtractionHarness.class),entityManager,mock(PlatformTransactionManager.class));
        }
        Map<String,Object> save(Map<String,Object> graph){return service.save(PROJECT,graph,null,head.getDraftRevisionId(),true);}
        DraftTemplateGraphRevision install(String id,Map<String,Object> graph,String status,String archives){String json=JsonUtils.write(graph);DraftTemplateGraphRevision revision=new DraftTemplateGraphRevision(id,PROJECT,head.getDraftRevisionId(),status,DraftAdoption.hash(json),json,archives,Instant.EPOCH);stored.put(id,revision);head.setDraftRevisionId(id);return revision;}
    }
}
