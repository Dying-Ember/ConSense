package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingSemanticMaterialTest {
    @Test void boundedFirstPassSpreadsAcrossTenderDocumentsAndKeepsObservedUnitMembers() {
        List<Chunk> chunks=fixtureOrigins(20,20,20);List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        String companion=chunks.get(17).getId();
        for(Chunk chunk:chunks){
            List<String> ids=chunk==chunks.get(0)?Arrays.asList(chunk.getId(),companion):Collections.singletonList(chunk.getId());
            units.add(new VettingRetrievalSourceUnits.Unit(chunk.getId(),"complete_observed_unit",null,ids,Collections.singletonMap("unitId","unit-"+chunk.getId())));
        }
        VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(new VettingRetrievalSourceUnits.RankedChunks(chunks,units));
        assertEquals(48,pass.selectedOrigins);assertEquals(12,pass.omittedOrigins);
        for(String document:Arrays.asList("PRE","SCC","SCT"))
            assertEquals(16,pass.subjects.stream().filter(c->document.equals(c.getDocumentId())).count());
        assertTrue(pass.subjects.stream().anyMatch(c->"PRE".equals(c.getDocumentId())&&c==chunks.get(19)));
        assertTrue(pass.corpus.stream().anyMatch(c->companion.equals(c.getId())));
        assertFalse(pass.subjects.stream().anyMatch(c->companion.equals(c.getId())));
        assertEquals(0,pass.unrepresentedTenderDocuments);
    }

    @Test void oversizedObservedUnitIsSkippedWholeWhileOtherDocumentsRemainRepresented() {
        List<Chunk> chunks=fixtureOrigins(140,20,20);List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        List<String> oversized=chunks.subList(0,129).stream().map(Chunk::getId).collect(java.util.stream.Collectors.toList());
        for(Chunk chunk:chunks)units.add(new VettingRetrievalSourceUnits.Unit(chunk.getId(),"complete_observed_unit",null,
                chunk==chunks.get(0)?oversized:Collections.singletonList(chunk.getId()),Collections.singletonMap("unitId","unit-"+chunk.getId())));
        VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(new VettingRetrievalSourceUnits.RankedChunks(chunks,units));
        assertFalse(pass.subjects.contains(chunks.get(0)));assertFalse(pass.corpus.contains(chunks.get(0)));
        assertTrue(pass.skippedUnitCandidates>0);
        for(String document:Arrays.asList("PRE","SCC","SCT"))assertTrue(pass.subjects.stream().anyMatch(c->document.equals(c.getDocumentId())));
        assertEquals(chunks.size()-pass.selectedOrigins,pass.omittedOrigins);
    }

    @Test void fullScopeReviewRetainsFullScanDenominatorAndPendingFirstPassState() throws Exception {
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        properties.getVetting().setSemanticContextChars(100);properties.getVetting().setSemanticContextExpansionChars(100);
        com.consense.ai.AiGateway gateway=mock(com.consense.ai.AiGateway.class);when(gateway.chatModel()).thenReturn("fixture");
        when(gateway.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.emptyList());
        VettingSemanticReview review=VettingTokenFixture.review(gateway,properties,mock(VettingRetrievalClient.class));
        java.util.concurrent.atomic.AtomicInteger guardCalls=new java.util.concurrent.atomic.AtomicInteger(),previewCalls=new java.util.concurrent.atomic.AtomicInteger();
        review.setInputBudgetObserver(input->{if(guardCalls.get()==2)previewCalls.incrementAndGet();return VettingTokenFixture.observe(input);});
        List<com.consense.domain.SourceDocument> documents=new ArrayList<>();int id=0;
        for(String name:Arrays.asList("PRE","SCC","SCT")){
            com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId((long)++id);source.setFileKey(name);source.setFileName(name+".docx");source.setReviewRole("tender");
            List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();StringBuilder body=new StringBuilder();
            for(int n=0;n<17;n++){
                com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("body:"+n+":paragraph");block.setLocation("body/"+n+"/paragraph");block.setKind("paragraph");block.setPageNo(n+1);
                block.setText("The "+name+" section "+n+" requires accurate project review.");blocks.add(block);body.append(block.getText()).append('\n');
            }
            source.setTextContent(body.toString());source.setStructuredContentJson(JsonUtils.write(blocks));documents.add(source);
        }
        List<Chunk> chunks=VettingCorpus.chunks(documents);assertEquals(51,chunks.size());
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),chunks,chunks,"en",null,(Runnable)guardCalls::incrementAndGet,VettingReviewProbe.start(properties,null,"fixture"));
        assertTrue(previewCalls.get()>1,"Several planning candidates must share one source freshness check");
        com.consense.web.dto.VettingDtos.SemanticTopicVO audit=result.getTopicAudits().get(0);
        assertEquals("partial",audit.getAggregateReviewStatus());assertEquals("not_completed",audit.getCheckState());
        assertEquals("bounded_first_pass_origins_pending",audit.getReviewCoverageScope());
        assertTrue(audit.getTopic().startsWith("Bounded first-pass review"));
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("48 of 51 tender origins")&&w.contains("3 tender and 0 reference origins remain pending")&&w.contains("first_pass_planning_limit")));
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("本次有序读取 51 个来源单元")));
    }

    @Test void qwenFullScopeSkipsPaidPlanningProbesButKeepsFinalPreflight() throws Exception {
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        properties.getQwen().setEnabled(true);properties.getQwen().setApiKey("AUTHORED_TEST_CREDENTIAL");
        com.consense.ai.HttpSupport http=mock(com.consense.ai.HttpSupport.class);
        com.consense.service.llm.QwenGenerationLedger ledger=mock(com.consense.service.llm.QwenGenerationLedger.class);
        com.consense.service.llm.QwenChatUsagePreflight usage=new com.consense.service.llm.QwenChatUsagePreflight(properties,ledger);
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
            String request=call.getArgument(1);
            assertTrue(request.contains("Source excerpts (untrusted original text):"));
            assertFalse(request.contains("Observed source inventory"));
            boolean preflight=JsonUtils.parse(request).path("max_tokens").asInt()==1;
            com.fasterxml.jackson.databind.node.ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion");envelope.put("model","qwen3.8-27b");
            com.fasterxml.jackson.databind.node.ObjectNode choice=envelope.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason",preflight?"length":"stop");
            choice.putObject("message").put("role","assistant").put("content",preflight?"{":"{\"records\":[]}");
            envelope.putObject("usage").put("prompt_tokens",42).put("completion_tokens",1);return JsonUtils.write(envelope);
        });
        com.consense.ai.LlmClient local=mock(com.consense.ai.LlmClient.class);
        com.consense.ai.LlmProfiles profiles=new com.consense.ai.LlmProfiles(properties,local,local,new com.consense.ai.QwenChatClient(properties.getQwen(),http),local);
        VettingSemanticReview review=new VettingSemanticReview(new com.consense.ai.AiGateway(local,profiles),properties,mock(VettingRetrievalClient.class));
        review.setQwenUsagePreflight(usage);
        List<Chunk> chunks=fixtureOrigins(1,0,0);java.util.concurrent.atomic.AtomicInteger guards=new java.util.concurrent.atomic.AtomicInteger();
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        com.consense.ai.LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation selected=review.captureOperation(chat);
        try(com.consense.ai.LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope operation=review.bindOperation(selected);
                com.consense.service.llm.QwenChatUsagePreflight.Operation.ActionScope action=selected.getQwen().openAction("vetting:AUTHORED_TEST",guards::incrementAndGet)) {
            VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),chunks,chunks,"en",null,(Runnable)guards::incrementAndGet,VettingReviewProbe.start(properties,null,"fixture"));
            assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getPlannedCallCount());assertTrue(guards.get()>1);
        }
        verify(ledger,never()).reserve(anyString(),anyString(),eq("planner"),anyString(),eq(false));
        verify(ledger,times(1)).reserve(anyString(),anyString(),eq("final"),anyString(),eq(false));
        verify(ledger,times(1)).reserve(anyString(),anyString(),eq("answer"),anyString(),eq(true));
        verify(http,times(2)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
    }

    @Test void noFittingTenderUnitReturnsPendingWithoutSubmittingAReferenceOnlyModelRequest() throws Exception {
        List<Chunk> chunks=fixtureOrigins(130,130,130);List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        Chunk reference=VettingContextBudgetTest.chunk("reference-origin","SL1","A fixed reference clause remains available.",50);
        reference.setDocumentId("STANDARD");reference.setRole("standard");reference.setFileKey("SL");chunks.add(reference);
        for(Chunk chunk:chunks){
            List<String> ids=chunks.stream().filter(c->chunk.getDocumentId().equals(c.getDocumentId())).map(Chunk::getId).collect(java.util.stream.Collectors.toList());
            units.add(new VettingRetrievalSourceUnits.Unit(chunk.getId(),"complete_observed_unit",null,ids,Collections.singletonMap("unitId","unit-"+chunk.getId())));
        }
        List<Chunk> scanned=new VettingRetrievalSourceUnits.RankedChunks(chunks,units);
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        com.consense.ai.AiGateway gateway=mock(com.consense.ai.AiGateway.class);
        VettingSemanticReview review=VettingTokenFixture.review(gateway,properties,mock(VettingRetrievalClient.class));
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),scanned,scanned,"en",null,(Runnable)()->{},VettingReviewProbe.start(properties,null,"fixture"));
        assertEquals(0,result.getActualGatewayCallCount());assertEquals(0,result.getPlannedCallCount());
        com.consense.web.dto.VettingDtos.SemanticTopicVO audit=result.getTopicAudits().get(0);
        assertEquals("partial",audit.getAggregateReviewStatus());assertEquals("not_completed",audit.getCheckState());
        assertEquals("not_submitted",audit.getGlobalCallStatus());assertTrue(audit.getPacketAudits().isEmpty());
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("0 of 390 tender origins")&&w.contains("selected 1 of 1 reference origins")&&w.contains("390 tender and 0 reference origins remain pending")));
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("no model request was submitted")));
    }

    @Test void sourceChangeAfterPreviewStillBlocksFinalModelDispatch() throws Exception {
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        properties.getVetting().setSemanticContextChars(4000);
        com.consense.ai.AiGateway gateway=mock(com.consense.ai.AiGateway.class);when(gateway.chatModel()).thenReturn("fixture");
        VettingSemanticReview review=VettingTokenFixture.review(gateway,properties,mock(VettingRetrievalClient.class));
        com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId(1L);source.setFileKey("PRE");source.setFileName("PRE.docx");source.setReviewRole("tender");
        com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("body:0:paragraph");block.setLocation("body/0/paragraph");block.setKind("paragraph");block.setText("The Tenderer shall retain the signed records.");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        java.util.concurrent.atomic.AtomicInteger guardCalls=new java.util.concurrent.atomic.AtomicInteger(),previewCalls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        review.setInputBudgetObserver(input->{assertEquals(2,guardCalls.get(),"Preview must not become dispatch authority");previewCalls.incrementAndGet();changed.set(true);return VettingTokenFixture.observe(input);});
        Runnable guard=()->{guardCalls.incrementAndGet();if(changed.get())throw new IllegalStateException("Fixture source revision changed");};
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),chunks,chunks,"en",null,guard,VettingReviewProbe.start(properties,null,"fixture"));
        assertTrue(previewCalls.get()>0);assertTrue(guardCalls.get()>2);assertEquals(0,result.getActualGatewayCallCount());
        verify(gateway,never()).completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList());
    }

    private static List<Chunk> fixtureOrigins(int pre,int scc,int sct){
        List<Chunk> chunks=new ArrayList<>();int ordinal=0;
        String[] names={"PRE","SCC","SCT"};int[] counts={pre,scc,sct};
        for(int document=0;document<names.length;document++)for(int n=0;n<counts[document];n++){
                Chunk chunk=VettingContextBudgetTest.chunk("origin-"+(++ordinal),names[document]+n,"Source paragraph "+n+".",40);
                chunk.setDocumentId(names[document]);chunk.setFileKey(names[document]);chunks.add(chunk);
            }
        return chunks;
    }

    @Test void fullScopeReviewSubmitsUnrankedTextAndBothSidesOfAnExplicitCrossFileReference() throws Exception {
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        properties.getVetting().setSemanticContextChars(4000);properties.getVetting().setSemanticContextExpansionChars(4000);
        com.consense.ai.AiGateway gateway=mock(com.consense.ai.AiGateway.class);when(gateway.chatModel()).thenReturn("fixture");
        when(gateway.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.emptyList());
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);VettingSemanticReview review=VettingTokenFixture.review(gateway,properties,retrieval);
        List<Chunk> chunks=new ArrayList<>();
        String[] text={"The Tenderer shall use electronic submission in accordance with SCT1.","The Tenderer shall deliver signed paper originals for the same submission.","The maintenance period is twelve months after completion."};
        for(int n=0;n<text.length;n++){
            com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId((long)n+1);source.setFileKey(n==1?"SCT":"NTT");source.setFileName("source-"+n+".docx");source.setReviewRole("tender");
            com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("body:0:paragraph");block.setLocation("body/0/paragraph");block.setKind("paragraph");block.setText(text[n]);
            source.setTextContent(text[n]);source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));Chunk chunk=VettingCorpus.chunks(Collections.singletonList(source)).get(0);
            chunk.setClauseId(n==1?"SCT1":"NTT"+(n+1));chunk.setClauseHeadingLocation(block.getLocation());chunks.add(chunk);
        }
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),chunks,chunks,"en",null,(Runnable)()->{},VettingReviewProbe.start(properties,null,"fixture"));
        assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getReviewedChunkIds().containsAll(Arrays.asList(chunks.get(0).getId(),chunks.get(1).getId(),chunks.get(2).getId())));
        com.consense.web.dto.VettingDtos.SemanticTopicVO audit=result.getTopicAudits().get(0);assertEquals("full_scope",audit.getReviewKind());assertEquals("not_completed",audit.getCheckState());
        assertTrue(audit.getPacketAudits().stream().anyMatch(p->p.getSubmittedChunkIds().contains(chunks.get(0).getId())&&p.getSubmittedChunkIds().contains(chunks.get(1).getId())));
        verifyNoInteractions(retrieval);
    }

    @Test void oneConsistentAssessmentCannotCertifyAnEntireScannedPacket() {
        com.consense.web.dto.VettingDtos.SemanticTopicVO call=new com.consense.web.dto.VettingDtos.SemanticTopicVO();
        call.setStatus("completed");call.setReturnedAssessments(1);call.setVerifiedConsistentAssessments(1);
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();packet.setKind("full_scope");
        packet.setSubmittedChunkIds(Arrays.asList("assessed-source","unassessed-source"));
        VettingPacketCoverage.callResult(call,packet);assertEquals("not_completed",packet.getCheckState());
        call.setAcceptedFindings(1);VettingPacketCoverage.callResult(call,packet);assertEquals("issue",packet.getCheckState());
    }
    @Test void actualUserMessageUsesTheSameShortReferenceAsTheOutputContract() throws Exception {
        Chunk source=VettingContextBudgetTest.chunk("source-"+String.join("",Collections.nCopies(64,"f")),"SCC1","The paper route applies only where expressly selected; otherwise use the electronic route.",120);
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(Collections.singletonList(source));
        java.lang.reflect.Method user=VettingSemanticReview.class.getDeclaredMethod("user",String.class,VettingContextBuilder.Selection.class,List.class);
        user.setAccessible(true);String message=(String)user.invoke(null,"Submission method",selection,null);
        assertTrue(message.contains("\"id\":\"R1\""));assertFalse(message.contains(source.getId()));
        assertTrue(message.contains(source.getContent()));assertTrue(message.contains("otherwise use the electronic route"));
    }
    @Test void textFirstPromptKeepsReadableSourceAndShortReferencesWithoutParserMetadata() {
        Chunk source=VettingContextBudgetTest.chunk("source-identity-123","SCT1","The Contractor shall keep signed records.",44);
        source.setSourceHash("source-revision-abc");
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(Collections.singletonList(source));
        String prompt=VettingSemanticReview.textFirstUser("Records",selection);
        assertTrue(prompt.contains("R1 | tender | fixture.docx | clause SCT1\nThe Contractor shall keep signed records."));
        assertTrue(prompt.contains("insufficient_context"));
        assertFalse(prompt.contains(source.getId()));assertFalse(prompt.contains(source.getSourceHash()));
        assertFalse(prompt.contains("sourcePosition"));assertFalse(prompt.contains("sourceQuality"));
        Chunk standard=VettingContextBudgetTest.chunk("sl-fixed-id","SL1","The standard clause requires a signed form.",44);
        standard.setRole("standard");selection.setChunks(Arrays.asList(source,standard));
        String withReference=VettingSemanticReview.textFirstUser("Records",selection);
        assertTrue(withReference.indexOf("R1 | tender")<withReference.indexOf("R2 | standard reference (project adoption unconfirmed)"));
        assertTrue(withReference.contains("adoption and applicability to this project remain unconfirmed"));
    }

    @Test void supplementsRequireGroundedInsufficiencyAndPreferItsSource() {
        Chunk cited=VettingContextBudgetTest.chunk("cited","SCT1","The Contractor shall keep signed records.",44);cited.setDocumentId("SCT");
        VettingContextBuilder.Selection global=new VettingContextBuilder.Selection();global.setChunks(Collections.singletonList(cited));
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks.Result();
        for(int index=1;index<=3;index++){
            VettingSourceRequestPacks.Pack pack=new VettingSourceRequestPacks.Pack();pack.setIndex(index);
            Chunk member=VettingContextBudgetTest.chunk("extra-"+index,"A","Other readable source text.",40);
            member.setDocumentId(index==2?"SCT":"PRE");pack.getChunks().add(member);
            if(index==2)pack.getEligibleOriginIds().add(cited.getId());
            plan.getExtraPacks().add(pack);
        }
        com.consense.web.dto.VettingDtos.SemanticPacketVO first=new com.consense.web.dto.VettingDtos.SemanticPacketVO();
        assertTrue(VettingSemanticReview.supplementalPacks(plan,first,global).isEmpty());
        com.consense.web.dto.VettingDtos.FindingEvidence evidence=new com.consense.web.dto.VettingDtos.FindingEvidence();
        evidence.setDocumentId("SCT");evidence.setQuote("The Contractor shall keep signed records.");
        first.setVerifiedInsufficientContextAssessments(1);
        first.getVerifiedAssessments().add(new com.consense.web.dto.VettingDtos.VerifiedAssessmentVO(
                "insufficient_context","Missing comparison","Need the matching project clause",Collections.singletonList(evidence)));
        List<VettingSourceRequestPacks.Pack> selected=VettingSemanticReview.supplementalPacks(plan,first,global);
        assertEquals(Collections.singletonList(2),selected.stream().map(VettingSourceRequestPacks.Pack::getIndex).collect(java.util.stream.Collectors.toList()));
    }

    @Test void semanticProjectionKeepsConditionsAndTableMeaningWithoutMachineLayout() {
        Map<String,Object> raw=new LinkedHashMap<>();
        raw.put("id","source-a");raw.put("content","Electronic submission is required, except where the paper procedure is expressly adopted.");
        raw.put("sourcePosition",Collections.singletonMap("nodePath","/w:document/w:body/w:p[90]"));
        raw.put("paragraphProperties",Collections.singletonMap("layoutEvidence",String.join("",Collections.nCopies(2000,"unused layout "))));
        raw.put("tableGroup",JsonUtils.parse("{\"headers\":[\"Submission method\",\"Copies\"],\"rows\":[[\"Paper\",\"3\"]],\"units\":\"copies\",\"mergedCells\":[[0,0,1,0]],\"bbox\":[0,0,1,1]}"));
        raw.put("conditions",Collections.singletonList("Only when expressly adopted"));raw.put("unknowns",Collections.singletonList("adopted method not confirmed"));
        raw.put("sourceDeclaration",JsonUtils.parse("{\"limits\":{\"wholeSourceSemantics\":\"unknown\",\"legalClosure\":\"unknown\"},\"softwareIdentity\":\"audit-only\"}"));
        JsonNode result=VettingSourceMaterial.semantic(raw,Collections.singletonMap("source-a","R1"));
        assertEquals("R1",result.path("id").asText());assertEquals(raw.get("content"),result.path("content").asText());
        assertEquals("Copies",result.path("tableGroup").path("headers").get(1).asText());
        assertEquals("3",result.path("tableGroup").path("rows").get(0).get(1).asText());
        assertFalse(result.path("tableGroup").path("mergedCells").isEmpty());
        assertEquals("adopted method not confirmed",result.path("unknowns").get(0).asText());
        assertEquals("unknown",result.path("sourceDeclaration").path("limits").path("legalClosure").asText());assertFalse(result.path("sourceDeclaration").has("softwareIdentity"));
        assertFalse(result.toString().contains("nodePath"));assertFalse(result.toString().contains("layoutEvidence"));assertFalse(result.toString().contains("bbox"));
        assertTrue(JsonUtils.write(raw).contains("layoutEvidence"));
    }

    @Test void packetReferencesAreBoundToTheSubmittedSourcesAndDecodeBeforeEvidenceValidation() {
        Chunk a=new Chunk();a.setId("a-"+String.join("",Collections.nCopies(64,"1")));Chunk b=new Chunk();b.setId("b-"+String.join("",Collections.nCopies(64,"2")));
        VettingPacketReferences refs=new VettingPacketReferences(Arrays.asList(a,b));
        assertEquals(Arrays.asList("R1","R2"),new ArrayList<>(refs.wireIds()));
        Quote quote=new Quote();quote.setChunkId("R2");quote.setQuote("A precise source quotation.");quote.setSide("reference");
        Record record=new Record();record.setEvidence(Collections.singletonList(quote));refs.resolve(Collections.singletonList(record));assertEquals(b.getId(),quote.getChunkId());
        quote.setChunkId("R3");assertThrows(IllegalArgumentException.class,()->refs.resolve(Collections.singletonList(record)));
        quote.setChunkId(a.getId());assertThrows(IllegalArgumentException.class,()->refs.resolve(Collections.singletonList(record)));
        assertEquals(a.getId(),refs.bindings().get("R1"));
    }
    @Test void sourceReferenceAliasesNeverRewriteRoleOrLiteralOriginalText(){
        JsonNode raw=JsonUtils.parse("{\"id\":\"tender\",\"role\":\"tender\",\"content\":\"tender\",\"coreIds\":[\"tender\"],\"conditions\":[\"tender\"]}");
        JsonNode selected=VettingSourceMaterial.semantic(raw,Collections.singletonMap("tender","R1"));
        assertEquals("R1",selected.path("id").asText());assertEquals("R1",selected.path("coreIds").get(0).asText());
        assertEquals("tender",selected.path("role").asText());assertEquals("tender",selected.path("content").asText());assertEquals("tender",selected.path("conditions").get(0).asText());
    }
}
