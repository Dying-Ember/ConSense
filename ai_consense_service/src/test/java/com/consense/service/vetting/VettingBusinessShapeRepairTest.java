package com.consense.service.vetting;
import com.consense.ai.LlmClient;
import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.consense.web.dto.VettingDtos.SemanticAttemptVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same real orchestration and existing schema/source gates with an authored provider. */
class VettingBusinessShapeRepairTest {
 @Test void aPendingFactActionMismatchCanUseTheExistingSecondAttemptAndPreservesTheFirstRaw() {
  Fixture f=new Fixture();String first=f.raw("amend",true,f.source.getContent()),second=f.raw("review",true,f.source.getContent());
  VettingResponsesTransportTest.Http http=f.wire(first,second);VettingSemanticReview.Result result=f.review(http);
  assertEquals(2,result.getActualGatewayCallCount(),"Business-shape failure should share the existing MAX2 attempt loop, not lose a supported record after one decoded answer");
  assertEquals(1,result.getCandidates().size());SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
  assertEquals(2,packet.getAttempts().size());assertEquals(first,packet.getAttempts().get(0).getRawResponse());assertEquals(second,packet.getAttempts().get(1).getRawResponse());
  JsonNode oldInput=packet.getAttempts().get(0).getInput(),newInput=packet.getAttempts().get(1).getInput();String oldUser=user(oldInput),newUser=user(newInput);
  assertTrue(newUser.endsWith(oldUser));assertTrue(newUser.contains(JsonUtils.write(first)));assertTrue(newUser.contains("pending_facts_require_review"),"Repair must receive the existing exact application validation reason");
  assertNotEquals(oldInput.path("serializedInputSha256"),newInput.path("serializedInputSha256"));assertEquals(newInput.path("serializedInputSha256"),packet.getAttempts().get(1).getInputBudget().path("serializedInputSha256"));
  assertEquals(oldInput.path("serializedInputSha256").asText(),packet.getPromptEnvelopeSha256());assertEquals(oldInput.path("schema"),newInput.path("schema"));
  assertEquals(oldInput.path("sourceSnapshotSha256"),newInput.path("sourceSnapshotSha256"));assertEquals(4,http.endpoints.stream().filter(e->e.endsWith("/input_tokens")).count());
 }
 @Test void twoBusinessShapeFailuresRetainBothRawArraysAndNeverReceiveAThirdGeneration() {
  Fixture f=new Fixture();String invalid=f.raw("amend",true,f.source.getContent());VettingResponsesTransportTest.Http http=f.wire(invalid,invalid);VettingSemanticReview.Result result=f.review(http);
  assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
  assertEquals(2,packet.getAttempts().size());for(SemanticAttemptVO attempt:packet.getAttempts())assertEquals(invalid,attempt.getRawResponse());
  assertEquals(2,http.endpoints.stream().filter(e->e.endsWith("/responses")).count());assertNull(result.getModelFailure());assertEquals("failed",packet.getStatus());assertEquals("business_shape_invalid",packet.getFailureKind());assertTrue(result.getReviewedChunkIds().isEmpty());
 }
 @Test void aLiteralSourceEvidenceRejectionDoesNotBecomeAnAutomaticBusinessShapeRepair() {
  Fixture f=new Fixture();String invalidQuote=f.raw("review",false,"This invented quotation never appears in the supplied source.");VettingResponsesTransportTest.Http http=f.wire(invalidQuote,"[]");VettingSemanticReview.Result result=f.review(http);
  assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertEquals(1,result.getTopicAudits().get(0).getPacketAudits().get(0).getAttempts().size());
 }
 @Test void unknownOrOverBudgetBusinessRepairIsRejectedBeforeSecondReservationAndGeneration() {
  for(boolean unknown:Arrays.asList(false,true)) {
   Fixture f=new Fixture();String invalid=f.raw("amend",true,f.source.getContent());VettingResponsesTransportTest.Http http=f.wire(invalid,"[]");
   http.counterHook=()->{if(http.bodies.get(http.bodies.size()-1).contains("BUSINESS_SHAPE_MISMATCH")){if(unknown)http.counterOverride="{}";else http.count=262144;}};
   VettingSemanticReview.Result result=f.review(http);assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getTopicAudits().get(0).getPacketAudits().get(0).getAttempts().size());
   assertTrue(result.getCandidates().isEmpty());assertEquals(1,http.endpoints.stream().filter(e->e.endsWith("/responses")).count());assertTrue(http.bodies.stream().anyMatch(b->b.contains("BUSINESS_SHAPE_MISMATCH")));
  }
 }
 @Test void nativeChatBusinessRepairUsesTheSameWholeInputFeedbackAndFreshBoundBudget() {
  Fixture f=new Fixture();String first=f.raw("amend",true,f.source.getContent()),second=f.raw("review",true,f.source.getContent());LlmClient provider=mock(LlmClient.class);
  java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  when(provider.chatStructured(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{String user=call.<List<LlmClient.ChatTurn>>getArgument(0).get(1).getContent();assertEquals("R1",VettingTokenFixture.reference(user,f.source));return calls.getAndIncrement()==0?first:second;});List<VettingInputBudget.Input> counts=new ArrayList<>();
  VettingSemanticReview.Result result=ReflectionTestUtils.invokeMethod(new VettingSemanticPacketHarnessTest(),"review",f.corpus,provider,(VettingInputBudget.Observer)(in->{counts.add(in);return VettingTokenFixture.observe(in);}),false);
  assertEquals(2,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());assertEquals(4,counts.size());
  String repair=user(result.getTopicAudits().get(0).getPacketAudits().get(0).getAttempts().get(1).getInput());assertTrue(repair.contains(JsonUtils.write(first)));assertTrue(repair.contains("pending_facts_require_review"));
 }
 @Test void sourceGuardChangeAfterBusinessFailureCannotReserveOrGenerateTheRepair() {
  Fixture f=new Fixture();String first=f.raw("amend",true,f.source.getContent());java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
  VettingResponsesTransportTest.Http http=new VettingResponsesTransportTest.Http(){@Override public VettingResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){if(endpoint.endsWith("/responses")){generation=VettingResponsesTransportTest.completed(first);changed.set(true);}return super.post(endpoint,body,timeout,cap,auth);}};
  VettingSemanticReview.Result result=f.review(http,()->{if(changed.get())throw new IllegalStateException("AUTHORED source identity changed TEST ONLY");});
  assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getTopicAudits().get(0).getPacketAudits().get(0).getAttempts().size());assertTrue(result.getCandidates().isEmpty());assertNotNull(result.getModelFailure());
 }
 @Test void businessShapeRepairStillCannotPublishAnInventedQuoteOrReceiveAThirdAttempt() {
  Fixture f=new Fixture();String first=f.raw("amend",true,f.source.getContent()),second=f.raw("review",true,"This invented quotation never appears in the supplied source.");VettingResponsesTransportTest.Http http=f.wire(first,second);VettingSemanticReview.Result result=f.review(http);
  assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertEquals(2,http.endpoints.stream().filter(e->e.endsWith("/responses")).count());
 }
 static String user(JsonNode input){return input.path("messages").get(1).path("content").asText();}
 static class Fixture {
  final VettingResponsesSemanticHarnessTest harness=new VettingResponsesSemanticHarnessTest();
  final List<Chunk> corpus;final Chunk source;final ConsenseProperties props;
  Fixture(){List<Chunk> all=ReflectionTestUtils.invokeMethod(harness,"corpus");source=all.get(0);corpus=Collections.singletonList(source);props=ReflectionTestUtils.invokeMethod(harness,"props",corpus);}
  // This fixture has one source; each wire caller verifies its actual submitted R1 before returning these exact bytes.
  String raw(String action,boolean pending,String quote){Map<String,Object> r=new LinkedHashMap<>();r.put("assessment","issue");r.put("type","requirement");r.put("severity","medium");r.put("title","AUTHORED shape-repair boundary TEST ONLY");r.put("comment","AUTHORED source-supported obligation requires review TEST ONLY.");r.put("impact","AUTHORED impact TEST ONLY.");r.put("suggestion","AUTHORED verify the intended requirement TEST ONLY.");r.put("subDimension","AUTHORED requirement boundary");r.put("factBasis",source.getContent());r.put("pendingFacts",pending?Collections.singletonList("AUTHORED intended application remains unconfirmed TEST ONLY."):Collections.emptyList());r.put("suggestedAction",action);r.put("checklistAssessments",Collections.emptyList());r.put("evidence",Collections.singletonList(Map.of("chunkId","R1","side","source","quote",quote)));return JsonUtils.write(Collections.singletonList(r));}
  VettingResponsesTransportTest.Http wire(String first,String second){return new VettingResponsesTransportTest.Http(){int generations;@Override public VettingResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){if(endpoint.endsWith("/responses")){String user=JsonUtils.parse(body).path("input").get(0).path("content").asText();assertEquals("R1",VettingTokenFixture.reference(user,source));generation=VettingResponsesTransportTest.completed(++generations==1?first:second);}return super.post(endpoint,body,timeout,cap,auth);}};}
  VettingSemanticReview.Result review(VettingResponsesTransportTest.Http http){return ReflectionTestUtils.invokeMethod(harness,"review",corpus,props,http,mock(LlmClient.class));}
  VettingSemanticReview.Result review(VettingResponsesTransportTest.Http http,Runnable guard){VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);when(retrieval.retrieve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("tender"),org.mockito.ArgumentMatchers.anyList())).thenReturn(corpus);VettingSemanticReview service=new VettingSemanticReview(new AiGateway(mock(LlmClient.class)),props,retrieval);service.setResponsesTransport(new VettingResponsesTransport(props,http));return service.reviewWithProgress(null,"AUTHORED guard TEST ONLY",corpus,"en",(n,total,message)->{},null,guard);}
 }
}
