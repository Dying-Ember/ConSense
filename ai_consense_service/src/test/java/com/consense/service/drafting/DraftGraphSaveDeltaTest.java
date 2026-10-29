package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.web.DraftTemplateGraphController;
import java.time.Instant;
import java.util.*;
import javax.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DraftGraphSaveDeltaTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static Map<String,Object> graph(){return obj("kind","consense-template-graph-v1","sources",Collections.singletonList(obj("id","41","sourceSha256","source-sha")),"selectedDocuments",obj("SCT","41"),"graph",obj("variables",Arrays.asList(obj("id","a","catalogAlignment",obj("confirmed",true),"sourceAnchors",Collections.singletonList(obj("text","full original","native",obj("start",1,"end",4)))),obj("id","dependent","status","confirmed")),"events",Collections.singletonList(obj("en","Retained history")),"requirements",Collections.singletonList(obj("id","unknown","nodeIds",Collections.emptyList())),"sourceScopeObservations",obj("scope",obj("text","full retained range")),"automaticDiscovery",obj("sources",Collections.singletonList(obj("memberCoverage",Collections.singletonList("original audit"))))));}
    private static Map<String,Object> delta(Map<String,Object> graph){Map<String,Object> changed=JsonUtils.readMap(JsonUtils.write(nodes(graph).get(0)));changed.remove("catalogAlignment");changed.put("nameEn","Changed");return obj("baseRevisionId","base","baseGraphSha256",SHA,"variables",Arrays.asList(changed,obj("id","dependent","status","pending")),"appendedEvents",Collections.singletonList(obj("en","Explicit shared choice")));}

    @Test void fullNodeReplacementsRetainUntouchedRangesUnknownsAndHistoryWithoutMutatingEitherInput(){
        Map<String,Object> graph=graph(),delta=delta(graph);String before=JsonUtils.write(graph),request=JsonUtils.write(delta);Map<String,Object> result=DraftGraphSaveDelta.apply(graph,delta);
        assertFalse(nodes(result).get(0).containsKey("catalogAlignment"));assertEquals("pending",nodes(result).get(1).get("status"));
        for(String key:Arrays.asList("requirements","sourceScopeObservations","automaticDiscovery"))assertEquals(state(graph).get(key),state(result).get(key));assertEquals(2,list(state(result).get("events")).size());assertEquals(before,JsonUtils.write(graph));assertEquals(request,JsonUtils.write(delta));
    }
    @Test void malformedOrOutOfScopeDeltasNeverSilentlyChangeTheGraph(){
        for(String defect:Arrays.asList("new-node","duplicate","extra-state","events-type","node-type","empty")){
            Map<String,Object> graph=graph(),delta=delta(graph);if("new-node".equals(defect))map(list(delta.get("variables")).get(0)).put("id","new");if("duplicate".equals(defect))delta.put("variables",Arrays.asList(nodes(graph).get(0),nodes(graph).get(0)));if("extra-state".equals(defect))delta.put("requirements",Collections.emptyList());if("events-type".equals(defect))delta.put("appendedEvents","wrong");if("node-type".equals(defect))delta.put("variables",Collections.singletonList("wrong"));if("empty".equals(defect))delta.put("variables",Collections.emptyList());String before=JsonUtils.write(graph);assertThrows(BizException.class,()->DraftGraphSaveDelta.apply(graph,delta),defect);assertEquals(before,JsonUtils.write(graph));
        }
    }
    @Test void responseContainsTheServersActualNormalizedNodesAndStateWithAnExplicitFullFallback(){
        Map<String,Object> original=graph(),normalized=DraftGraphSaveDelta.apply(original,delta(original));nodes(normalized).get(0).put("nameEn","Server normalized");state(normalized).put("definitionGraphReview",obj("valid",false));state(normalized).remove("automaticDiscovery");Map<String,Object> saved=obj("id","saved","graph",normalized,"graphSha256","new-sha","scopeObservationIntegrity",obj("scope","valid"));
        Map<String,Object> reply=DraftGraphSaveDelta.receipt("base",SHA,original,saved),change=map(reply.get("graphDelta"));assertFalse(reply.containsKey("graph"));assertEquals(2,list(change.get("variables")).size());assertEquals("Server normalized",map(list(change.get("variables")).get(0)).get("nameEn"));assertFalse(map(list(change.get("variables")).get(0)).containsKey("catalogAlignment"));assertEquals(obj("valid",false),map(change.get("stateChanges")).get("definitionGraphReview"));assertEquals(Collections.singletonList("automaticDiscovery"),change.get("removedStateKeys"));assertEquals(saved.get("scopeObservationIntegrity"),reply.get("scopeObservationIntegrity"));
        normalized.put("selectedDocuments",obj("SCT","different"));assertSame(saved,DraftGraphSaveDelta.receipt("base",SHA,original,saved));
    }
    @Test void serviceChecksBothHeadsAndExactBaselineBeforeCallingTheUnchangedFullSavePath(){
        Fixture f=new Fixture();Map<String,Object> delta=delta(f.graph);Map<String,Object> result=f.service.saveDelta("p",delta,null,"base");assertTrue(result.containsKey("graphDelta"));
        verify(f.service).save(eq("p"),argThat(proposed->state(proposed).get("requirements").equals(state(f.graph).get("requirements"))&&!nodes(proposed).get(0).containsKey("catalogAlignment")&&"pending".equals(nodes(proposed).get(1).get("status"))),isNull(),eq("base"),eq(true));
        for(String defect:Arrays.asList("current","draft","base-id","base-sha")){Fixture stale=new Fixture();Map<String,Object> request=delta(stale.graph);if("base-id".equals(defect))request.put("baseRevisionId","other");if("base-sha".equals(defect))request.put("baseGraphSha256","other");assertThrows(BizException.class,()->stale.service.saveDelta("p",request,"current".equals(defect)?"other":null,"draft".equals(defect)?"other":"base"),defect);verify(stale.service,never()).save(anyString(),anyMap(),nullable(String.class),nullable(String.class),anyBoolean());assertEquals("base",stale.head.getDraftRevisionId());}
    }
    @Test void fullValidationFailureIsNotSwallowedOrRetriedAsAnotherSave(){
        Fixture f=new Fixture();doThrow(new BizException(4007,"GRAPH_CYCLE_TEST")).when(f.service).save(eq("p"),anyMap(),isNull(),eq("base"),eq(true));assertTrue(assertThrows(BizException.class,()->f.service.saveDelta("p",delta(f.graph),null,"base")).getMessage().contains("GRAPH_CYCLE_TEST"));assertEquals("base",f.head.getDraftRevisionId());
    }
    @Test void controllerKeepsTheOldPostAndRequiresBothCasFieldsForEitherMode(){
        DraftTemplateGraphService service=mock(DraftTemplateGraphService.class);DraftTemplateGraphController controller=new DraftTemplateGraphController(service,mock(DraftAutomationService.class));Map<String,Object> change=delta(graph()),full=graph(),body=obj("graphDelta",change,"expectedCurrentRevisionId",null);
        assertThrows(BizException.class,()->controller.save("p",body));verifyNoInteractions(service);body.put("expectedDraftRevisionId","base");controller.save("p",body);verify(service).saveDelta(eq("p"),eq(change),isNull(),eq("base"));body.put("graph",full);assertThrows(BizException.class,()->controller.save("p",body));body.remove("graphDelta");controller.save("p",body);verify(service).save(eq("p"),eq(full),isNull(),eq("base"),eq(true));
    }
    private static final class Fixture {
        final Map<String,Object> graph=graph();final DraftTemplateGraphHead head=new DraftTemplateGraphHead();final DraftTemplateGraphService service;
        Fixture(){
            ProjectService projects=mock(ProjectService.class);DraftTemplateGraphRevisionRepository revisions=mock(DraftTemplateGraphRevisionRepository.class);DraftTemplateGraphHeadRepository heads=mock(DraftTemplateGraphHeadRepository.class);EntityManager em=mock(EntityManager.class);
            service=spy(new DraftTemplateGraphService(projects,mock(SourceDocumentRepository.class),revisions,heads,mock(DraftGraphInputBindingRepository.class),mock(DraftVariableRepository.class),mock(DraftExtractionRunRepository.class),mock(DraftReviewSources.class),mock(AiGateway.class),mock(DraftExtractionHarness.class),em,mock(PlatformTransactionManager.class)));
            Project project=new Project();project.setId("p");when(projects.require("p")).thenReturn(project);when(em.find(eq(Project.class),eq("p"),eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(project);head.setProjectId("p");head.setDraftRevisionId("base");when(heads.findById("p")).thenReturn(Optional.of(head));when(revisions.findByIdAndProjectId("base","p")).thenReturn(Optional.of(new DraftTemplateGraphRevision("base","p",null,"DRAFT",SHA,JsonUtils.write(graph),"[]",Instant.now())));
            doAnswer(invocation->obj("id","saved","graphSha256","new-sha","graph",invocation.getArgument(1))).when(service).save(eq("p"),anyMap(),isNull(),eq("base"),eq(true));
        }
    }
}
