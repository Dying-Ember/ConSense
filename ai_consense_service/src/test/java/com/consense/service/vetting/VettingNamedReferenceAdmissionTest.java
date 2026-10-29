package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.llm.*;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual direction caller and sealed Qwen wire; authored lengths, no service, provider or original calls. */
class VettingNamedReferenceAdmissionTest {
    @Test void requestedStandardsKeepTheExistingProjectAllowanceAfterAThreeCharacterTenderLabel()throws Exception {
        Exercise run=new Exercise("project_allowance");VettingSemanticReview.Result result=run.execute();
        assertEquals(2,run.wires.size(),"The empty checklist reply retains its single bounded coverage attempt");
        assertEquals(3,run.tender.getContent().length());
        for(String user:run.wires){
            assertTrue(user.contains(run.operativeProject.getContent()),"Complete requested standards must not consume the project allowance and leave only a three-character tender label");
            assertTrue(user.contains(run.definitions.getContent()));assertTrue(user.contains(run.commencement.getContent()));
            assertFalse(user.contains(run.priorNoise.getContent()));
            assertFalse(user.contains("General Conditions of Contract for Building Works 2013 Edition (version 1.1)"));
            assertTrue(run.excerptChars(user)<=30000);
        }
        List<com.consense.web.dto.VettingDtos.SemanticPacketVO> packets=result.getTopicAudits().stream().filter(a->!a.getPacketAudits().isEmpty()).findFirst().orElseThrow().getPacketAudits();
        for(com.consense.web.dto.VettingDtos.SemanticPacketVO packet:packets){
            assertTrue(packet.getSubmittedChunkIds().contains(run.operativeProject.getId()));
            assertTrue(packet.getSubmittedChunkIds().containsAll(Arrays.asList(run.definitions.getId(),run.commencement.getId())));
            assertEquals(run.tender.getContent().length()+run.operativeProject.getContent().length()+run.definitions.getContent().length()+run.commencement.getContent().length(),packet.getSubmittedChars());
        }
    }
    @Test void explicitlyRequestedDefinitionsAndCommencementReachWireInsteadOfTenHeaders()throws Exception {
        Exercise run=new Exercise("initial");run.execute();
        assertEquals(2,run.wires.size());String user=run.wires.get(0);
        assertTrue(user.contains(run.definitions.getContent()),"The full 13,387-character requested GCC1.1 body must reach the actual prepared wire");
        assertTrue(user.contains(run.commencement.getContent()));
        assertFalse(user.contains("General Conditions of Contract for Building Works 2013 Edition (version 1.1)"));
        assertFalse(user.contains(run.insurance.getContent()));assertFalse(user.contains(run.possession.getContent()));
        assertTrue(run.excerptChars(user)>10000);assertTrue(run.excerptChars(user)<=30000);
    }
    @Test void secondRoundKeepsAffectedTenderAndRequestedDisputesButDoesNotRepeatUncitedPriorBulk()throws Exception {
        Exercise run=new Exercise("supplement");VettingSemanticReview.Result result=run.execute();
        assertEquals(4,run.wires.size(),"One initial, two finite requested rounds and one omitted-row attempt reach the existing wire");
        assertTrue(run.wires.get(1).contains(run.priorNoise.getContent()));
        String user=run.wires.get(2);assertTrue(user.contains(run.disputes.getContent()),"The complete 21,615-character requested parent must fit a conditional bounded supplement");
        assertTrue(user.contains(run.tender.getContent()));assertFalse(user.contains(run.priorNoise.getContent()));
        assertTrue(run.excerptChars(user)>20000);assertTrue(run.excerptChars(user)<=30000);
        assertEquals(4,result.getTopicAudits().get(0).getPacketAudits().size());
        assertEquals(result.getTopicAudits().get(0).getPacketAudits().get(1).getPacketId(),result.getTopicAudits().get(0).getPacketAudits().get(2).getParentPacketId());
    }
    @Test void explicitStorageWindowOverInitialAllowanceIsPreservedWholeWithinExistingCombinedAllowance()throws Exception {
        Exercise run=new Exercise("storage");run.execute();assertEquals(2,run.wires.size());
        assertTrue(run.wires.get(0).contains(run.storage.getContent()),"The required APPEND12 source window including Storage(a) must not disappear at 10,000 chars");
        assertEquals(10068,run.storage.getContent().length());assertTrue(run.excerptChars(run.wires.get(0))<=30000);
    }
    @Test void aGenuinelyOversizedNamedParentRemainsPendingWithoutTruncationOrUnboundedExpansion()throws Exception {
        Exercise run=new Exercise("oversized");run.execute();assertEquals(2,run.wires.size());
        assertFalse(run.wires.get(0).contains(run.definitions.getContent()));
        assertTrue(run.wires.get(0).contains("pending"));assertTrue(run.excerptChars(run.wires.get(0))<=10000);
        assertEquals(40000,run.definitions.getContent().length());
    }
    @Test void initialFixedCitationCatalogSurvivesARequestedSupplementWithoutRepeatingUncitedDefinitions()throws Exception {
        Exercise run=new Exercise("initial_supplement");run.execute();assertEquals(3,run.wires.size());
        String user=run.wires.get(1);assertTrue(user.contains(run.commencement.getContent()));assertTrue(user.contains(run.disputes.getContent()));
        assertFalse(user.contains(run.definitions.getContent()));assertTrue(run.excerptChars(user)<=30000);
    }
    @Test void evenSmallUncitedHistoricalFixedTextDoesNotReturnWhenTheNextAnswerHasRoom()throws Exception {
        Exercise run=new Exercise("initial_small_supplement");run.execute();assertEquals(3,run.wires.size());
        assertTrue(run.wires.get(1).contains(run.commencement.getContent()));assertTrue(run.wires.get(1).contains(run.disputes.getContent()));
        assertFalse(run.wires.get(1).contains(run.definitions.getContent()),"An old uncited body must stay out even when it would fit");
    }
    @Test void aNamedMultiParentGroupReachesWireWholeWithinTheBoundedAllowance()throws Exception {
        Exercise run=new Exercise("group");run.execute();assertEquals(2,run.wires.size());
        assertTrue(run.wires.get(0).contains(run.definitions.getContent()));assertTrue(run.wires.get(0).contains(run.continuation.getContent()));
    }
    @Test void aNamedGroupLargerThanTheCeilingCannotLeakOnlyItsSmallMember()throws Exception {
        Exercise run=new Exercise("group_oversized");run.execute();assertEquals(2,run.wires.size());
        assertFalse(run.wires.get(0).contains(run.definitions.getContent()));assertFalse(run.wires.get(0).contains(run.continuation.getContent()));
        assertTrue(run.wires.get(0).contains("remain pending"));
    }
    private static final class Exercise {
        final String mode;final ConsenseProperties props=new ConsenseProperties();final List<String> wires=new ArrayList<>();
        final Chunk tender=chunk("tender","tender","SCT1","Affected tender text requiring a source-bound comparison.");
        final Chunk operativeProject=chunk("operative-project","tender","PRE.B6.085",sized("The Contractor shall design and execute the identified project works and submit the design programme to the Contract Manager for approval.\n",8000));
        final Chunk definitions=chunk("definitions","standard","1.1",sized("1.1 Definitions and interpretation\n",13387));
        final Chunk commencement=chunk("commencement","standard","8.1",sized("8.1 Commencement of the Works\n",927));
        final Chunk insurance=chunk("insurance","standard","5.14",sized("5.14 Third party insurance\n",3859));
        final Chunk possession=chunk("possession","standard","8.2",sized("8.2 Possession of the Site\n",1192));
        final Chunk disputes=chunk("disputes","standard","17.1",sized("17.1 Avoidance and resolution of disputes\n",21615));
        final Chunk priorNoise=chunk("uncited-prior","tender","PRE2",sized("Uncited earlier appendix background\n",17000));
        final Chunk storage=chunk("storage-window","tender","PRE.B8.APPEND12.P",sized("PRE.B8.APPEND12.P\n3.3 Storage\na. Material storage location plan: three months.\n",10068));
        final Chunk continuation=chunk("continuation","standard",null,sized("Required observed continuation\n",8000));
        final Map<String,FixedReferenceEvidence> evidence=new LinkedHashMap<>();final List<SearchHit> initialHits=new ArrayList<>();
        final LlmProfiles profiles;final VettingSemanticReview review;final OerBusinessEvidence.Session session;final List<Chunk> corpus;
        Exercise(String mode)throws Exception {
            this.mode=mode;if("oversized".equals(mode))definitions.setContent(sized("1.1 Definitions and interpretation\n",40000));
            if("project_allowance".equals(mode))tender.setContent("8.1");
            if("initial_small_supplement".equals(mode))disputes.setContent(sized("17.1 Avoidance and resolution of disputes\n",120));
            props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_NO_NETWORK_CREDENTIAL");
            HttpSupport http=mock(HttpSupport.class);
            when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
                JsonNode request=JsonUtils.parse(call.getArgument(1));boolean preflight=request.path("max_tokens").asInt()==1;
                String output="{";if(!preflight){String user=request.path("messages").get(1).path("content").asText();wires.add(user);output=response(user);}
                ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion").put("model","qwen3.8-27b");
                envelope.putArray("choices").addObject().put("index",0).put("finish_reason",preflight?"length":"stop").putObject("message").put("role","assistant").put("content",output);
                envelope.putObject("usage").put("prompt_tokens",1000).put("completion_tokens",1);return JsonUtils.write(envelope);
            });
            LlmClient local=mock(LlmClient.class);profiles=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local);
            review=new VettingSemanticReview(new AiGateway(local,profiles),props,mock(VettingRetrievalClient.class));
            review.setQwenUsagePreflight(new QwenChatUsagePreflight(props,mock(QwenGenerationLedger.class)));
            FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);
            when(fixed.projectSelection(anyString())).thenReturn(JsonUtils.mapper().createObjectNode());
            for(int i=0;i<10;i++)add(chunk("header-"+i,"standard",null,"General Conditions of Contract for Building Works 2013 Edition (version 1.1)"),"document","content",initialHits);
            for(Chunk source:Arrays.asList(insurance,possession,commencement,definitions))add(source,"clause","body",initialHits);
            if(mode.startsWith("group")){
                if("group_oversized".equals(mode))continuation.setContent(sized("Required observed continuation\n",20000));
                add(continuation,"note","context",initialHits);
                ObjectNode group=JsonUtils.mapper().createObjectNode();group.put("unitId","AUTHORED complete definitions");group.putArray("requiredMemberIds").add(definitions.getId()).add(continuation.getId());
                for(Chunk source:Arrays.asList(definitions,continuation)){ObjectNode payload=(ObjectNode)evidence.get(source.getId()).getPayload();payload.set("fixedReferenceSourceUnit",group);evidence.put(source.getId(),new FixedReferenceEvidence(source.getId(),0.9,payload,null,group));}
                for(int i=0;i<initialHits.size();i++)initialHits.set(i,evidence.get(initialHits.get(i).getId()).asHit());
            }
            add(disputes,"clause","body",new ArrayList<>());
            when(fixed.evidence(any())).thenAnswer(call->evidence.get(call.<SearchHit>getArgument(0).getId()));
            when(fixed.toModelMaterial(anyList(),anyInt())).thenAnswer(call->{List<FixedReferenceEvidence> selected=new ArrayList<>();for(SearchHit hit:call.<List<SearchHit>>getArgument(0))selected.add(evidence.get(hit.getId()));return FixedBaselineModelMaterial.render(selected,call.getArgument(1));});
            when(fixed.searchForProject(anyString(),anyString(),anyString(),anyInt())).thenAnswer(call->{String query=call.getArgument(2);
                if("storage".equals(mode))return Collections.emptyList();
                if(mode.startsWith("initial_")&&query.contains("17.1"))return Collections.singletonList(evidence.get(disputes.getId()).asHit());
                if("supplement".equals(mode)){if(query.contains("17.1"))return Collections.singletonList(evidence.get(disputes.getId()).asHit());if(query.contains("8.1"))return Collections.singletonList(evidence.get(commencement.getId()).asHit());return Collections.emptyList();}
                return initialHits;
            });review.setFixedBaselineRetrievalClient(fixed);
            OerBusinessEvidence oer=mock(OerBusinessEvidence.class);review.setOerBusinessEvidence(oer);
            Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
            session=constructor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
            Class<?> type=Class.forName(OerBusinessEvidence.class.getName()+"$Corpus");Constructor<?> create=type.getDeclaredConstructor(OerBusinessEvidence.Session.class,List.class);create.setAccessible(true);
            @SuppressWarnings("unchecked") List<Chunk> bound=(List<Chunk>)create.newInstance(session,"project_allowance".equals(mode)?Arrays.asList(tender,operativeProject,priorNoise,storage):Arrays.asList(tender,priorNoise,storage));corpus=bound;
            when(oer.retrieveReviewText(eq(session),anyString(),nullable(String.class),any(),anyInt())).thenAnswer(call->{String query=call.getArgument(1);String role=call.getArgument(2);
                if("tender".equals(role)){boolean target="supplement".equals(mode)?query.contains("GS CEW"):"storage".equals(mode)?query.contains("material storage location plan submission time"):query.contains("SL guidance contract type");return !target?Collections.emptyList():"project_allowance".equals(mode)?Arrays.asList(tender,operativeProject,priorNoise):Collections.singletonList(tender);}
                return "supplement".equals(mode)&&query.contains("PRE clause 2")?Collections.singletonList(priorNoise):Collections.emptyList();
            });
            when(oer.retrieveRequiredProjectText(eq(session),anyString(),anyList(),any(),anyInt())).thenAnswer(call->"storage".equals(mode)&&"PRE.B8.APPEND12".equals(call.getArgument(1))?Collections.singletonList(storage):Collections.emptyList());
        }
        void add(Chunk source,String kind,String role,List<SearchHit> target){ObjectNode payload=JsonUtils.mapper().valueToTree(source);payload.remove(Arrays.asList("canonicalEvidence","canonicalPayload"));
            payload.put("observedNodeKind",kind).put("observedSourceRole",role);payload.putObject("sourceQuality").put("family","GCC");
            payload.put("fixedBaselineManifestSha256",VettingCorpus.hash("AUTHORED manifest")).put("fixedBaselineEditionId","AUTHORED GCC edition");
            FixedReferenceEvidence item=new FixedReferenceEvidence(source.getId(),0.9,payload,null,null);evidence.put(source.getId(),item);target.add(item.asHit());}
        VettingSemanticReview.Result execute()throws Exception {Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);run.setAccessible(true);
            LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation operation=review.captureOperation(chat);
            try(LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope bound=review.bindOperation(operation);QwenChatUsagePreflight.Operation.ActionScope action=operation.getQwen().openAction("AUTHORED fixed admission",()->{})){
                return (VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"));}}
        String response(String prompt){if(mode.startsWith("initial_")&&wires.size()>1||!mode.startsWith("initial_")&&!"supplement".equals(mode)||wires.size()>=3)return "{\"records\":[]}";
            Map<String,Object> record=new LinkedHashMap<>();VettingCompetitionFixture.details(record);record.put("assessment","insufficient_context");record.put("type","requirement");record.put("severity","low");record.put("title","Authored source comparison");
            String need=mode.startsWith("initial_")?"Retrieve GCC 17.1 to confirm the dispute procedure.":wires.size()==1?"Retrieve PRE clause 2 and GCC 8.1 to confirm the programme.":"Retrieve GCC 17.1 to confirm the dispute procedure.";
            record.put("comment",need);record.put("pendingFacts",Collections.singletonList(need));record.put("impact","Review only");record.put("suggestion","Obtain the named source");
            Map<String,Object> quote=new LinkedHashMap<>();quote.put("chunkId","R1");quote.put("side","source");quote.put("quote",tender.getContent());List<Map<String,Object>> quotes=new ArrayList<>();quotes.add(quote);
            if(mode.startsWith("initial_")){Map<String,Object> reference=new LinkedHashMap<>();reference.put("chunkId","R2");reference.put("side","reference");reference.put("quote",commencement.getContent());quotes.add(reference);}record.put("evidence",quotes);
            return "{\"records\":"+JsonUtils.write(Collections.singletonList(record))+"}";}
        int excerptChars(String user){int chars=0;for(Chunk source:Arrays.asList(tender,operativeProject,definitions,commencement,insurance,possession,disputes,priorNoise,storage,continuation))if(user.contains(source.getContent()))chars+=source.getContent().length();return chars;}
    }
    private static String sized(String prefix,int count){return prefix+"x".repeat(count-prefix.length());}
    private static Chunk chunk(String id,String role,String clause,String text){Chunk c=VettingContextBudgetTest.chunk(id,clause,text,text.length());c.setRole(role);c.setDocumentId("1");c.setFileKey("standard".equals(role)?"GCC":"PRE");c.setFileName("AUTHORED source.docx");c.setSourceHash(VettingCorpus.hash("AUTHORED parsed"));c.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED original"));return c;}
}
