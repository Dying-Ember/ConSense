package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.VettingCorpus;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored source carriers only: arithmetic, exact existing identities and semantic unknowns. */
class AdviceSmallCorpusContextTest {
    private final ConsenseProperties props=new ConsenseProperties();
    private final OerBusinessEvidence canonical=spy(new OerBusinessEvidence(null,null,new CanonicalEvidenceProperties()));

    @Test void exactWholeCorpusBoundaryAppendsOneRealUnitWithoutChangingTheOriginalHitOrUnknowns() throws Exception {
        OerBusinessEvidence.Session session=session(chunk("title","TT"),chunk("body","BODY1234"));props.getVector().setStuffingLimit(10);
        VectorStore.SearchHit title=hit("title","TT","complete_observed_unit",.93),body=hit("body","BODY1234","unknown",0);String before=JsonUtils.write(Arrays.asList(title,body));
        doReturn(Arrays.asList(body,body)).when(canonical).sourceHits(eq(session),eq(Collections.singletonList("body")));
        List<VectorStore.SearchHit> result=complete(session,title);assertEquals(2,result.size());assertSame(title,result.get(0));assertSame(body,result.get(1));assertEquals(before,JsonUtils.write(result));
        ObjectNode unit=(ObjectNode)body.getPayload().get("canonicalSourceUnit");assertEquals("unknown",unit.path("status").asText());assertEquals("unknown",unit.path("qualifiersComplete").asText());assertFalse(unit.path("semanticScopeVerified").asBoolean(true));
    }
    @Test void zeroNegativeAndOneCharacterOverTheWholeCorpusCeilingDoNotResolveAnyExtraSources() throws Exception {
        OerBusinessEvidence.Session session=session(chunk("title","TT"),chunk("body","BODY1234"));VectorStore.SearchHit title=hit("title","TT","complete_observed_unit",.93);
        for(int limit:Arrays.asList(0,-1,9)){props.getVector().setStuffingLimit(limit);assertEquals(Collections.singletonList(title),complete(session,title));}verify(canonical,never()).sourceHits(any(),anyList());
    }
    @Test void repeatedLargeCorpusCannotWrapAnIntegerTotalAndReenterTheSmallCorpusPath() throws Exception {
        OerBusinessEvidence.Session session=session();VettingCorpus.Chunk repeated=chunk("body","x".repeat(1048576));
        java.lang.reflect.Field views=session.chunks().getClass().getDeclaredField("views");views.setAccessible(true);views.set(session.chunks(),Collections.nCopies(4096,repeated));props.getVector().setStuffingLimit(Integer.MAX_VALUE);
        VectorStore.SearchHit title=hit("title","TT","complete_observed_unit",.93);assertEquals(Collections.singletonList(title),complete(session,title));verify(canonical,never()).sourceHits(any(),anyList());
    }
    @Test void actualPointerCoverageAvoidsDuplicatingAnAlreadyTransportedMember() throws Exception {
        OerBusinessEvidence.Session session=session(chunk("title","TT"),chunk("body","BODY"));VectorStore.SearchHit title=hit("title","TT","complete_observed_unit",.93);
        EvidencePointer pointer=new EvidencePointer();pointer.setCanonicalChunkId("body");title.getPayload().put("canonicalEvidence",Collections.singletonList(pointer));assertEquals(Collections.singletonList(title),complete(session,title));verify(canonical,never()).sourceHits(any(),anyList());
    }
    @Test void existingRankedIncorporationTableAndFixedOrderIsImmutableAndFixedIdCannotHideAProjectOrigin() throws Exception {
        OerBusinessEvidence.Session session=session(chunk("ranked","R"),chunk("incorporation","I"),chunk("table","T"),chunk("body","B"));VectorStore.SearchHit ranked=hit("ranked","R","complete_observed_unit",.93),incorporation=hit("incorporation","I","complete_observed_unit",0),table=hit("table","T","complete_observed_unit",0),fixed=hit("body","FIXED","unknown",.81),body=hit("body","B","unknown",0);fixed.getPayload().put("fixedDevelopmentBaseline",true);fixed.getPayload().put("sourceRole","standard");List<VectorStore.SearchHit> existing=Arrays.asList(ranked,incorporation,table,fixed);String before=JsonUtils.write(existing);
        doReturn(Collections.singletonList(body)).when(canonical).sourceHits(eq(session),eq(Collections.singletonList("body")));List<VectorStore.SearchHit> result=canonical.advisoryContext(session,existing,true,props.getVector().getStuffingLimit());assertEquals(5,result.size());assertEquals(before,JsonUtils.write(result.subList(0,4)));assertSame(body,result.get(4));
    }
    @Test void requiredButUntransportedMemberIsStillResolvedAndSourceFailureCannotFallBack() throws Exception {
        OerBusinessEvidence.Session session=session(chunk("title","TT"),chunk("body","BODY"));VectorStore.SearchHit title=hit("title","TT","unknown",.93);
        ((ObjectNode)title.getPayload().get("canonicalSourceUnit")).putArray("requiredMemberIds").add("body");doThrow(new IllegalStateException("AUTHORED source stale")).when(canonical).sourceHits(eq(session),eq(Collections.singletonList("body")));
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->complete(session,title));assertEquals("AUTHORED source stale",rejected.getMessage());verify(canonical).sourceHits(eq(session),eq(Collections.singletonList("body")));
    }
    @Test void canonicalModelMaterialCannotUseASessionlessLocalFallback(){
        AdviceService service=new AdviceService(null,null,null,null,props,null,null,null,null,canonical,null);
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"renderExcerpts",Collections.singletonList(hit("title","TT","unknown",.93))));
        assertEquals("Canonical model material requires its selected source session",rejected.getMessage());verify(canonical,never()).modelMaterial(any(),anyList());
    }
    private List<VectorStore.SearchHit> complete(OerBusinessEvidence.Session s,VectorStore.SearchHit hit){return canonical.advisoryContext(s,Collections.singletonList(hit),true,props.getVector().getStuffingLimit());}
    private VettingCorpus.Chunk chunk(String id,String text){VettingCorpus.Chunk c=new VettingCorpus.Chunk();c.setId(id);c.setDocumentId("1");c.setRole("tender");c.setContent(text);return c;}
    private VectorStore.SearchHit hit(String id,String text,String status,double score){Map<String,Object> p=new LinkedHashMap<>();p.put("content",text);p.put("sourceRole","tender");ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId","unit-"+id);unit.put("status",status);unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);p.put("canonicalSourceUnit",unit);p.put("scoreKind",score==0?"unranked_context":"actual_reranked_seed");return new VectorStore.SearchHit(id,score,p);}
    private OerBusinessEvidence.Session session(VettingCorpus.Chunk... chunks) throws Exception {Map<String,VettingCorpus.Chunk> views=new LinkedHashMap<>();for(VettingCorpus.Chunk c:chunks)views.put(c.getId(),c);Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);return ctor.newInstance("AUTHORED","advisory",Collections.singletonList(1L),Collections.emptyList(),views,Collections.emptyMap());}
}
