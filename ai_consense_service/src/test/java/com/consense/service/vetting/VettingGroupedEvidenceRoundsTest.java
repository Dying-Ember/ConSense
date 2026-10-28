package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.llm.*;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real direction caller, provider decode/repair and binding gates; all retrieval/provider data is authored. */
class VettingGroupedEvidenceRoundsTest {
    @ParameterizedTest @ValueSource(strings={"fast","standard","standard_editions","split"})
    void twentySevenInitialsAndOneAssessmentWithSeveralNeedsReachARealSecondRound(String mode)throws Exception {
        try(Exercise run=new Exercise(mode)){
            VettingSemanticReview.Result result=run.execute();
            assertEquals(27,run.initials.get());assertEquals(27,result.getTopicAudits().size());
            SemanticTopicVO direction=result.getTopicAudits().get(0);
            assertEquals("split".equals(mode)?4:3,direction.getPacketAudits().size(),"One grouped assessment must use bounded progressive packets, not a call per wording");
            SemanticPacketVO first=direction.getPacketAudits().get(0),second=direction.getPacketAudits().get(1),third=direction.getPacketAudits().get(2);
            assertEquals(1,first.getEvidenceNeeds().size());
            JsonNode lineage=JsonUtils.mapper().valueToTree(first.getEvidenceNeeds().get(0));
            assertTrue(lineage.path("originalQueries").size()>=3,"All exact earlier requests remain in server lineage");
            assertEquals(1,run.sctQueries.get(),"Several wordings of SCT5(4) must trigger one actual retrieval");
            assertEquals(1,run.preQueries.get());assertEquals("split".equals(mode)?2:1,run.childQueries.get());
            assertEquals(first.getPacketId(),second.getParentPacketId());assertEquals(second.getPacketId(),third.getParentPacketId());
            assertTrue(run.supplementPrompts.get(0).contains(run.method.getContent()));assertTrue(run.supplementPrompts.get(0).contains(run.heading.getContent()));
            assertTrue(run.supplementPrompts.get(2).contains(run.method.getContent()));assertTrue(run.supplementPrompts.get(2).contains(run.period.getContent()));
            assertTrue(run.supplementPrompts.get(2).contains("Retrieve "+run.methodTarget()+" to confirm the submission method."),"The final round still answers the original grouped question");
            if("split".equals(mode)){assertEquals(2,second.getVerifiedAssessments().size());assertEquals(2,second.getEvidenceNeeds().size());assertEquals(second.getPacketId(),direction.getPacketAudits().get(3).getParentPacketId());}
            assertTrue(run.supplementInitialCounts.get(0)<27);assertTrue(run.supplementInitialCounts.get(1)<=6);
            assertTrue(run.supplementPrompts.get(1).contains("CHECKLIST-4"),"Another ready direction gets its first supplement before direction1 gets its second");
            assertTrue(VettingEvidenceNeedResolution.resolve(direction.getPacketAudits()).supersedesVerified(first.getPacketId(),0),"Only the final source-bound conclusion resolves the original grouped assessment");
            assertEquals("insufficient_context",first.getVerifiedAssessments().get(0).getAssessment());
            assertTrue(run.nanos.get()<TimeUnit.MINUTES.toNanos(55));assertEquals("split".equals(mode)?31:30,run.generations.get());
            System.out.println("AUTHORED_GROUPED_SCHEDULE mode="+mode+" initials="+run.initials.get()+" supplementalGenerations="+run.supplementPrompts.size()+" generations="+run.generations.get()+" elapsedSeconds="+TimeUnit.NANOSECONDS.toSeconds(run.nanos.get()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"slow","no_new_evidence","model_failure","repeated_target","round_limit","deadline"})
    void actualCallerKeepsFairExecutionAndTruthfulFiniteTermination(String mode)throws Exception {
        try(Exercise run=new Exercise(mode)){
            VettingSemanticReview.Result result=run.execute();assertEquals(27,result.getTopicAudits().size());
            SemanticTopicVO direction=result.getTopicAudits().get(0);SemanticPacketVO parent=direction.getPacketAudits().get(0);
            if("slow".equals(mode)){
                assertTrue(run.supplementInitialCounts.size()>=2,"Slow initial passes must not starve all follow-ups after the first one");
                assertTrue(run.supplementInitialCounts.get(0)<27);assertTrue(run.supplementInitialCounts.get(1)<27);
            }else{
                assertFalse(VettingEvidenceNeedResolution.resolve(direction.getPacketAudits()).supersedesVerified(parent.getPacketId(),0));
                String expected="no_new_evidence".equals(mode)?"no_new_evidence_within_text_budget":"model_failure".equals(mode)?"model_or_evidence_failure":"repeated_target".equals(mode)?"same_target_without_new_evidence":"round_limit".equals(mode)?"round_limit":"run_time_budget";
                assertTrue(JsonUtils.mapper().valueToTree(direction.getPacketAudits()).findValues("terminalReason").stream().anyMatch(reason->expected.equals(reason.asText())),"The exact original termination reason must survive: "+expected);
            }
            assertTrue(run.childQueries.get()<=1);assertTrue(run.generations.get()<=33,"Grouped rounds and existing two-attempt repair remain finite");
            for(long started:run.started)assertTrue(started<TimeUnit.MINUTES.toNanos(55),"No new retrieval or provider request after the absolute deadline");
            assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("pending")||w.contains("incomplete")));
            System.out.println("AUTHORED_GROUPED_SCHEDULE mode="+mode+" initials="+run.initials.get()+" supplementalGenerations="+run.supplementPrompts.size()+" generations="+run.generations.get()+" elapsedSeconds="+TimeUnit.NANOSECONDS.toSeconds(run.nanos.get()));
        }
    }
    private static final class Exercise implements AutoCloseable {
        final String mode;final ConsenseProperties props=new ConsenseProperties();final AtomicLong nanos=new AtomicLong();
        final AtomicInteger initials=new AtomicInteger(),generations=new AtomicInteger(),sctQueries=new AtomicInteger(),preQueries=new AtomicInteger(),childQueries=new AtomicInteger();
        final List<Long> started=new ArrayList<>();final List<String> supplementPrompts=new ArrayList<>();final List<Integer> supplementInitialCounts=new ArrayList<>();
        final Chunk tender=chunk("a","The Tenderer shall follow the submission method and submission period specified by the project notice."),method=chunk("b","SCT5(4) requires electronic submission through the named project portal."),heading=chunk("h","PRE clause2 identifies the project submission period in the following subclause."),period=chunk("c","PRE clause2.1 requires submission within fourteen days of the stated project notice."),other=chunk("d","SCT7 requires the signed tender copy to be supplied with the electronic submission."),secondPeriod=chunk("e","PRE clause2.2 requires the signed copy within the same fourteen-day submission period.");
        final LlmProfiles profiles;final VettingSemanticReview review;final OerBusinessEvidence.Session session;final List<Chunk> corpus;
        Exercise(String mode)throws Exception {
            this.mode=mode;props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_TEST_CREDENTIAL");
            if(mode.startsWith("standard")){method.setContent(methodTarget()+" requires electronic submission through the named project portal.");heading.setContent(periodTarget()+" identifies the submission period in the following subclause.");period.setContent(periodTarget()+".1 requires submission within fourteen days of the project notice.");method.setRole("standard");heading.setRole("standard");period.setRole("standard");}
            HttpSupport http=mock(HttpSupport.class);QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);
            when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
                JsonNode request=JsonUtils.parse(call.getArgument(1));boolean preflight=request.path("max_tokens").asInt()==1;start(preflight?1:19);
                ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion");envelope.put("model","qwen3.8-27b");
                ObjectNode choice=envelope.putArray("choices").addObject().put("index",0).put("finish_reason",preflight?"length":"stop");
                String output="{";if(!preflight){generations.incrementAndGet();String prompt=request.path("messages").get(1).path("content").asText();
                    boolean supplement=prompt.contains("Specific earlier evidence need");if(supplement){supplementPrompts.add(prompt);supplementInitialCounts.add(initials.get());}
                    output="model_failure".equals(mode)&&supplement?"{\"records\":null}":response(prompt,supplement);
                }
                choice.putObject("message").put("role","assistant").put("content",output);envelope.putObject("usage").put("prompt_tokens",200).put("completion_tokens",1);return JsonUtils.write(envelope);
            });
            LlmClient local=mock(LlmClient.class);profiles=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local);
            review=new VettingSemanticReview(new AiGateway(local,profiles),props,mock(VettingRetrievalClient.class));review.setQwenUsagePreflight(new QwenChatUsagePreflight(props,ledger));
            FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);when(fixed.projectSelection("authored")).thenReturn(JsonUtils.mapper().createObjectNode());
            Map<String,FixedReferenceEvidence> evidence=new LinkedHashMap<>();for(Chunk source:Arrays.asList(method,heading,period)){
                ObjectNode payload=JsonUtils.mapper().valueToTree(source);payload.remove(Arrays.asList("canonicalEvidence","canonicalPayload"));payload.putObject("sourceQuality").put("family",source==method||"standard_editions".equals(mode)?"SL":"GCC");payload.put("fixedBaselineManifestSha256",VettingCorpus.hash("AUTHORED manifest"));payload.put("fixedBaselineEditionId","standard_editions".equals(mode)?"SL2022":"AUTHORED edition");evidence.put(source.getId(),new FixedReferenceEvidence(source.getId(),0.9,payload,null,null));
            }
            when(fixed.evidence(any())).thenAnswer(call->evidence.get(call.<com.consense.vector.VectorStore.SearchHit>getArgument(0).getId()));
            when(fixed.toModelMaterial(anyList(),anyInt())).thenAnswer(call->{List<FixedReferenceEvidence> selected=new ArrayList<>();for(com.consense.vector.VectorStore.SearchHit hit:call.<List<com.consense.vector.VectorStore.SearchHit>>getArgument(0))selected.add(evidence.get(hit.getId()));return FixedBaselineModelMaterial.render(selected,call.getArgument(1));});
            when(fixed.searchForProject(anyString(),anyString(),anyString(),anyInt())).thenAnswer(call->{start(5);String query=call.getArgument(2);if(!mode.startsWith("standard"))return Collections.emptyList();
                if("standard_editions".equals(mode)){
                    if(query.matches("(?is).*SL\\s*2022\\s*clause\\s*6\\.3.*")){sctQueries.incrementAndGet();return Collections.singletonList(evidence.get(method.getId()).asHit());}
                    if(query.matches("(?is).*SL\\s*2022\\s*clause\\s*7\\.4\\.1.*")){childQueries.incrementAndGet();return Collections.singletonList(evidence.get(period.getId()).asHit());}
                    if(query.matches("(?is).*SL\\s*2022\\s*clause\\s*7\\.4.*")){preQueries.incrementAndGet();return Collections.singletonList(evidence.get(heading.getId()).asHit());}return Collections.emptyList();
                }
                if(query.matches("(?is).*SL\\s*5.*")){sctQueries.incrementAndGet();return Collections.singletonList(evidence.get(method.getId()).asHit());}
                if(query.matches("(?is).*GCC\\s*(?:clause\\s*)?99\\.1.*")){childQueries.incrementAndGet();return Collections.singletonList(evidence.get(period.getId()).asHit());}
                if(query.matches("(?is).*GCC\\s*(?:clause\\s*)?99.*")){preQueries.incrementAndGet();return Collections.singletonList(evidence.get(heading.getId()).asHit());}return Collections.emptyList();});review.setFixedBaselineRetrievalClient(fixed);
            OerBusinessEvidence oer=mock(OerBusinessEvidence.class);review.setOerBusinessEvidence(oer);
            Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
            session=constructor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
            Class<?> type=Class.forName(OerBusinessEvidence.class.getName()+"$Corpus");Constructor<?> create=type.getDeclaredConstructor(OerBusinessEvidence.Session.class,List.class);create.setAccessible(true);
            @SuppressWarnings("unchecked") List<Chunk> bound=(List<Chunk>)create.newInstance(session,Arrays.asList(tender,method,heading,period,other,secondPeriod));corpus=bound;
            when(oer.retrieveReviewText(eq(session),anyString(),nullable(String.class),any(),anyInt())).thenAnswer(call->{
                String query=call.getArgument(1);start("deadline".equals(mode)?600:"slow".equals(mode)?100:45);
                if("tender".equals(call.getArgument(2))){initials.incrementAndGet();return Collections.singletonList(tender);}
                if(query.matches("(?is).*SCT\\s*5.*")){sctQueries.incrementAndGet();return "no_new_evidence".equals(mode)?Collections.emptyList():Collections.singletonList(method);}
                if(query.matches("(?is).*PRE\\s*(?:clause\\s*)?2\\.1.*")){childQueries.incrementAndGet();return Collections.singletonList(period);}
                if(query.matches("(?is).*PRE\\s*(?:clause\\s*)?2\\.2.*")){childQueries.incrementAndGet();return Collections.singletonList(secondPeriod);}
                if(query.matches("(?is).*PRE\\s*(?:clause\\s*)?2.*")){preQueries.incrementAndGet();return "no_new_evidence".equals(mode)?Collections.emptyList():Collections.singletonList(heading);}
                if(query.matches("(?is).*SCT\\s*7.*"))return Collections.singletonList(other);
                return Collections.emptyList();
            });
        }
        VettingSemanticReview.Result execute()throws Exception {
            Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class,java.util.function.LongSupplier.class);run.setAccessible(true);
            LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation operation=review.captureOperation(chat);
            try(LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope bound=review.bindOperation(operation);QwenChatUsagePreflight.Operation.ActionScope action=operation.getQwen().openAction("AUTHORED grouped evidence",()->{})){
                return (VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"),(java.util.function.LongSupplier)nanos::get);
            }
        }
        private String response(String prompt,boolean supplement){
            boolean first=prompt.contains("Assigned review direction: CHECKLIST-1. "),fourth=prompt.contains("Assigned review direction: CHECKLIST-4. ");
            boolean pending=first&&(!supplement||!prompt.contains(period.getContent())&&!("split".equals(mode)&&prompt.contains(secondPeriod.getContent()))||"round_limit".equals(mode))||fourth&&!supplement;
            String need="Need "+periodTarget()+("round_limit".equals(mode)&&prompt.contains(period.getContent())?".2":".1")+" to confirm the submission period.";
            Map<String,Object> record=new LinkedHashMap<>();VettingCompetitionFixture.details(record);record.put("assessment",pending?"insufficient_context":"consistent");record.put("type","requirement");record.put("severity","low");record.put("title","Authored submission requirement");
            record.put("comment",pending?first&&!supplement?"Missing "+methodTarget()+" text is required to confirm the method.":fourth?"Need SCT 7 to confirm the tender submission copy.":"repeated_target".equals(mode)?"Need SCT 5(4) to confirm the same submission method.":need:"The supplied passages state the submission method and period.");
            record.put("impact","Review only");record.put("suggestion","Retain the source-supported result");
            if(pending){List<String> facts=first&&!supplement?Arrays.asList("Retrieve "+methodTarget()+" to confirm the submission method.","Need "+periodTarget()+" to confirm the submission period."):Collections.singletonList(fourth?"Need SCT 7 to confirm the tender submission copy.":"repeated_target".equals(mode)?"Need SCT 5(4) to confirm the same submission method.":need);record.put("pendingFacts",facts);}
            List<Map<String,Object>> quotes=new ArrayList<>();quotes.add(quote("R1","source",tender.getContent()));
            if(supplement&&prompt.contains(method.getContent()))quotes.add(quote("R2","reference",method.getContent()));
            if(supplement&&prompt.contains(period.getContent()))quotes.add(quote("R4","reference",period.getContent()));
            if(supplement&&prompt.contains(secondPeriod.getContent()))quotes.add(quote("R4","reference",secondPeriod.getContent()));
            if(supplement&&fourth)quotes.add(quote("R2","reference",other.getContent()));
            record.put("evidence",quotes);
            if("split".equals(mode)&&first&&supplement&&pending){Map<String,Object> another=new LinkedHashMap<>(record);String gap="Need PRE clause 2.2 to confirm the signed-copy submission period.";another.put("title","Authored signed-copy submission requirement");another.put("comment",gap);another.put("pendingFacts",Collections.singletonList(gap));return "{\"records\":"+JsonUtils.write(Arrays.asList(record,another))+"}";}
            return "{\"records\":"+JsonUtils.write(Collections.singletonList(record))+"}";
        }
        private String methodTarget(){return "standard_editions".equals(mode)?"SL2022 clause 6.3":"standard".equals(mode)?"SL 5(4)":"SCT 5(4)";}
        private String periodTarget(){return "standard_editions".equals(mode)?"SL2022 clause 7.4":"standard".equals(mode)?"GCC clause 99":"PRE clause 2";}
        private void start(int seconds){started.add(nanos.get());nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));}
        @Override public void close(){}
    }
    private static Map<String,Object> quote(String id,String side,String text){Map<String,Object> quote=new LinkedHashMap<>();quote.put("chunkId",id);quote.put("side",side);quote.put("quote",text);return quote;}
    private static Chunk chunk(String id,String text){
        Chunk c=VettingContextBudgetTest.chunk(id,"SCT1",text,text.length());c.setDocumentId("1");c.setFileName("Authored tender.docx");
        c.setSourceHash(VettingCorpus.hash("AUTHORED parsed source"));c.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED original source"));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();pointer.setCanonicalChunkId(id);pointer.setJavaParsedSourceHash(c.getSourceHash());c.setCanonicalEvidence(pointer);return c;
    }
}
