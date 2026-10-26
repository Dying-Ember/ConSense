package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Word/parser/graph/mapper/session/model, with an explicitly doubled source freshness boundary. */
class WordModelSelectionTest {
    @Test void allPointersAndSessionStayLightWhileSelectedDirectMaterialAndBudgetIncludeCompleteScopes()throws Exception{
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();String before=JsonUtils.write(session.chunks());
        for(Chunk chunk:session.chunks())assertNull(chunk.getCanonicalEvidence().getNativeWordSourceScopesContext());
        assertFalse(before.contains("observedWordSourceScopes"));assertFalse(before.contains("nativeWordSourceScopesContext"));
        Chunk seed=w.seed(session);Map<String,JsonNode> contexts=w.service.wordSourceScopeContexts(session,Collections.singletonList(seed));
        assertEquals(Collections.singleton(seed.getId()),contexts.keySet());assertFalse(contexts.get(seed.getId()).path("members").isEmpty());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(seed),FixedVettingReferences.empty(),null,contexts));
        assertTrue(material.get(0).path("canonicalStructure").path("observedWordSourceScopes").toString().contains("First clause continuation"));
        SourceJsonWireAssertions.assertRoundTrip(material);assertEquals(before,JsonUtils.write(session.chunks()));
        LlmClient local=mock(LlmClient.class);when(local.chatModel()).thenReturn("authored-uninvoked");
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local),new ConsenseProperties(),mock(VettingRetrievalClient.class));review.setOerBusinessEvidence(w.service);
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(Collections.singletonList(seed));selection.setContentChars(seed.getContent().length());
        Method complete=VettingSemanticReview.class.getDeclaredMethod("completeInput",VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,FixedVettingReferences.class,List.class,Runnable.class,List.class);complete.setAccessible(true);
        VettingInputBudget.Input input=(VettingInputBudget.Input)complete.invoke(review,selection,"authored scope probe","en",false,null,FixedVettingReferences.empty(),null,(Runnable)()->{},session.chunks());
        String prompt=JsonUtils.write(input);assertTrue(prompt.contains("observedWordSourceScopes"));assertTrue(prompt.contains("First clause continuation"));assertFalse(prompt.contains("OUTSIDE_NEXT_CHAPTER"));
        assertEquals(before,JsonUtils.write(session.chunks()));verify(local,never()).chat(anyList());verify(w.registry,atLeast(3)).revalidateFrame(any());
    }

    @Test void planningPreviewsReuseOnlySelectedSourceMaterialAndFinalInputReadsCurrentRevision()throws Exception{
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();Chunk seed=w.seed(session);
        Chunk other=session.chunks().stream().filter(c->!seed.getId().equals(c.getId())).findFirst().orElseThrow();
        OerBusinessEvidence observed=spy(w.service);LlmClient local=mock(LlmClient.class);when(local.chatModel()).thenReturn("authored-uninvoked");
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local),new ConsenseProperties(),mock(VettingRetrievalClient.class));review.setOerBusinessEvidence(observed);
        Method prepare=VettingSemanticReview.class.getDeclaredMethod("previewMaterial",List.class,List.class);prepare.setAccessible(true);
        Object preview=prepare.invoke(review,session.chunks(),Arrays.asList(seed,other));
        Method previewInput=VettingSemanticReview.class.getDeclaredMethod("completeInput",VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,FixedVettingReferences.class,List.class,Runnable.class,List.class,preview.getClass());previewInput.setAccessible(true);
        Method freshInput=VettingSemanticReview.class.getDeclaredMethod("completeInput",VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,FixedVettingReferences.class,List.class,Runnable.class,List.class);freshInput.setAccessible(true);
        VettingContextBuilder.Selection first=new VettingContextBuilder.Selection();first.setChunks(Collections.singletonList(seed));first.setContentChars(seed.getContent().length());
        VettingContextBuilder.Selection second=new VettingContextBuilder.Selection();second.setChunks(Collections.singletonList(other));second.setContentChars(other.getContent().length());
        Object[] args={first,"authored scope probe","en",false,null,FixedVettingReferences.empty(),null,(Runnable)()->{},session.chunks()};
        VettingInputBudget.Input firstPreview=(VettingInputBudget.Input)previewInput.invoke(review,first,"authored scope probe","en",false,null,FixedVettingReferences.empty(),null,(Runnable)()->{},session.chunks(),preview);
        VettingInputBudget.Input secondPreview=(VettingInputBudget.Input)previewInput.invoke(review,second,"authored scope probe","en",false,null,FixedVettingReferences.empty(),null,(Runnable)()->{},session.chunks(),preview);
        assertEquals(seed.getContent(),VettingTokenFixture.excerpts(firstPreview.getMessages().get(1).getContent()).get(0).path("content").asText());
        assertEquals(other.getContent(),VettingTokenFixture.excerpts(secondPreview.getMessages().get(1).getContent()).get(0).path("content").asText());
        verify(observed,times(1)).wordSourceScopeContexts(eq(session),anyList());verify(observed,times(1)).sourceClauseLocators(eq(session),anyList(),anyMap());
        assertEquals(JsonUtils.write(firstPreview),JsonUtils.write(freshInput.invoke(review,args)));
        verify(observed,times(2)).wordSourceScopeContexts(eq(session),anyList());verify(observed,times(2)).sourceClauseLocators(eq(session),anyList(),anyMap());
        w.binding.setSourceRevisionSha256(VettingCorpus.hash("changed after planning"));
        assertInstanceOf(IllegalStateException.class,assertThrows(InvocationTargetException.class,()->freshInput.invoke(review,args)).getCause());
    }

    @Test void advisoryRankedAndExplicitContextHitsEnrichOnlyDetachedSelectedPointers()throws Exception{
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();Chunk seed=w.seed(session);String before=JsonUtils.write(session.chunks());
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("hits").addObject().put("id",seed.getId()).put("score",0.5);
        result.putObject("sourceUnits").putArray("units").addObject().put("status","unknown").put("qualifiersComplete","unknown");
        when(w.client.retrieve(anyString(),eq("vetting"),anyString(),nullable(String.class),any(CanonicalEvidenceService.VerifiedBindingFrame.class))).thenReturn(result);
        when(w.client.sourceUnitPolicy(anyString(),anyString(),anyList())).thenReturn(VettingRetrievalSourceUnits.POLICY);
        OerBusinessEvidence textOnly=spy(w.service);
        for(List<VectorStore.SearchHit> hits:Arrays.asList(textOnly.adviceTextHits(session,"query"),textOnly.sourceTextHits(session,Collections.singletonList(seed.getId())))){
            assertEquals(1,hits.size());List<?> pointers=(List<?>)hits.get(0).getPayload().get("canonicalEvidence");assertFalse(pointers.isEmpty());
            assertNull(((EvidencePointer)pointers.get(0)).getNativeWordSourceScopesContext());
        }
        verify(textOnly,never()).wordSourceScopeContexts(any(),anyList());
        for(List<VectorStore.SearchHit> hits:Arrays.asList(w.service.adviceHits(session,"query"),w.service.sourceHits(session,Collections.singletonList(seed.getId())))){
            assertEquals(1,hits.size());List<?> pointers=(List<?>)hits.get(0).getPayload().get("canonicalEvidence");assertFalse(pointers.isEmpty());
            EvidencePointer pointer=(EvidencePointer)pointers.get(0);assertNotNull(pointer.getNativeWordSourceScopesContext());
            JsonNode members=JsonUtils.mapper().valueToTree(hits.get(0).getPayload().get("canonicalMembers"));
            JsonNode model=new OerModelMaterial.Request().project(pointer,members.get(0),"tender");assertTrue(model.path("observedWordSourceScopes").isObject());
            assertFalse(model.path("parts").get(0).path("text").asText().contains("First clause continuation"));SourceJsonWireAssertions.assertRoundTrip(model);
        }
        assertEquals(before,JsonUtils.write(session.chunks()));
    }

    @Test void changedSelectedCopiesAndChangedBindingsCannotObtainOrReuseContext()throws Exception{
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();Chunk seed=w.seed(session),changed=JsonUtils.read(JsonUtils.write(seed),Chunk.class);changed.setContent("forged");
        assertThrows(IllegalStateException.class,()->w.service.wordSourceScopeContexts(session,Collections.singletonList(changed)));
        w.binding.setSourceRevisionSha256(VettingCorpus.hash("new revision"));
        assertThrows(IllegalStateException.class,()->w.service.wordSourceScopeContexts(session,Collections.singletonList(seed)));
        assertThrows(IllegalStateException.class,()->w.service.wordSourceScopeContexts(session,Collections.singletonList(seed)));
    }

    @Test void selectedWordAccessHonorsFrameCloseModuleProjectionDocumentAndThread()throws Exception{
        Wire w=new Wire();CanonicalEvidenceService.VerifiedBindingFrame frame=w.frame();String projection=w.binding.getProjectionSha256(),chunk=w.snapshot.path("chunks").get(1).path("id").asText();
        assertNotNull(frame.wordSourceScopeContext(97L,projection,chunk,"vetting"));
        assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(97L,projection,chunk,"advisory"));
        assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(97L,VettingCorpus.hash("foreign"),chunk,"vetting"));
        assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(98L,projection,chunk,"vetting"));
        assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(97L,projection,"missing","vetting"));
        java.util.concurrent.ExecutorService other=java.util.concurrent.Executors.newSingleThreadExecutor();try{
            assertTrue(other.submit(()->{assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(97L,projection,chunk,"vetting"));return true;}).get());
        }finally{other.shutdownNow();}
        frame.close();assertThrows(RuntimeException.class,()->frame.wordSourceScopeContext(97L,projection,chunk,"vetting"));
    }

    static final class Wire {
        final SourceDocument source;final JsonNode graph;
        final CanonicalSourceBinding binding;final ObjectNode snapshot=JsonUtils.mapper().createObjectNode();
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);final OerCanonicalRetrievalClient client=mock(OerCanonicalRetrievalClient.class);final OerBusinessEvidence service;
        Wire()throws Exception{this(CanonicalWordSourceContextTest.source());}
        Wire(SourceDocument source)throws Exception{
            this.source=source;graph=NormalSourceCandidateGraph.requireBound(source);binding=CanonicalWordSourceContextTest.binding(source);
            snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("manifest").set("candidateGraph",graph);snapshot.putObject("source").put("sourceFilename",source.getFileName());snapshot.putArray("unknowns");
            com.fasterxml.jackson.databind.node.ArrayNode chunks=snapshot.putArray("chunks");for(JsonNode unit:graph.path("units"))chunks.add(CanonicalWordSourceContextTest.canonical(graph,unit));
            binding.setSnapshotJson(JsonUtils.write(snapshot));binding.setSnapshotBytesSha256(VettingCorpus.hash(binding.getSnapshotJson()));
            when(registry.verifiedBindings(eq("authored"),eq("vetting"),eq(Collections.singletonList(97L)))).thenAnswer(call->frame());
            when(registry.currentBindings(eq("authored"),eq("vetting"),eq(Collections.singletonList(97L)))).thenAnswer(call->Collections.singletonList(binding));
            when(client.readSources(anyString(),any(JsonNode.class),any(CanonicalEvidenceService.VerifiedBindingFrame.class))).thenAnswer(call->{CanonicalEvidenceService.VerifiedBindingFrame selected=call.getArgument(2);return OerBusinessEvidenceTest.authoredSourceRead(selected.bindings(),call.getArgument(1),VettingRetrievalSourceUnits.POLICY);});
            CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://authored.invalid");service=new OerBusinessEvidence(registry,client,config);
        }
        OerBusinessEvidence.Session load(){return service.load("authored","vetting",Collections.singletonList(source));}
        Chunk seed(OerBusinessEvidence.Session session){return session.chunks().stream().filter(c->c.getContent().equals("1. Body first clause")).findFirst().orElseThrow();}
        CanonicalEvidenceService.VerifiedBindingFrame frame()throws Exception{
            Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> constructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);constructor.setAccessible(true);
            return (CanonicalEvidenceService.VerifiedBindingFrame)constructor.newInstance(registry,registry,"authored","vetting",Collections.singletonList(view.newInstance(binding,JsonUtils.parse(binding.getSnapshotJson()),false)));
        }
    }
}
