package com.consense.web;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Scope regressions exercise HTTP, persisted documents, index and model prompt, not classifier mocks. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.vector.stuffing-limit=1",
        "consense.vector.top-k=1","consense.vector.retrieve-top-k-floor=1",
        "consense.llm.num-ctx=262144","consense.llm.structured-thinking=false",
        "consense.llm.chat-model=scope-test-chat","consense.llm.embed-model=scope-test-embedding"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceDocumentScopeIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:advice_scope_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","advice-scope-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired SourceDocumentRepository sources;
    @Autowired StorageService storage;
    @Autowired com.consense.repository.ChatMessageRepository messages;
    @Autowired com.consense.service.advice.AdviceService advice;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean com.consense.service.vetting.VettingTokenBudgetCliObserver counter;
    private final List<String> prompts=new ArrayList<>();

    @BeforeEach void adapters() {
        com.consense.service.advice.AdviceBudgetFixture.bind(counter,local);
        prompts.clear();
        when(local.available()).thenReturn(true);
        when(local.chatModel()).thenReturn("scope-test-chat");
        when(local.embedModel()).thenReturn("scope-test-embedding");
        when(local.embed(anyList())).thenAnswer(call->{List<float[]> v=new ArrayList<>();for(Object ignored:call.<List<?>>getArgument(0))v.add(new float[]{1,0});return v;});
        when(local.chat(anyList())).thenAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST ONLY supported answer\",\"citations\":[\"[1]\"]}";});
    }
    @Test void adviceUsesCurrentReviewPackageNotAllProjectMaterialsOrExampleAnswers() throws Exception {
        String id=project();
        source(id,"NTT","03 - NTT.txt","VETTING_PACKAGE",null,"CURRENT TENDER SCOPE TEST ONLY");
        source(id,"NTT","03 - NTT.txt","VETTING_PACKAGE",null,"REPLACED OLD TEXT TEST ONLY");
        source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"CURRENT CONTRACT SCOPE TEST ONLY");
        source(id,"SL","26_Specification Library SL2022.txt","VETTING_PACKAGE","standard","FULL TECHNICAL LIBRARY TEST ONLY CON EAR WAT DRA");
        source(id,"PRE","25 - Specification - PRE.txt","VETTING_PACKAGE",null,"PROJECT PRE REPLACES LIBRARY PRE TEST ONLY");
        source(id,"SP","24 - The Specification.txt","VETTING_PACKAGE",null,"SPECIFICATION COMPOSITION TEST ONLY");
        source(id,"NTT","Draft template.txt","STANDARD_TEMPLATE",null,"DRAFT TEMPLATE LEAK TEST ONLY");
        source(id,"OTHER","drafting-only-email.txt","PROJECT_INPUT",null,"DRAFTING ONLY LEAK TEST ONLY");
        source(id,"OTHER","01_Vetting Comments.txt","VETTING_PACKAGE",null,"SAMPLE ANSWER LEAK TEST ONLY");
        source(id,"OTHER","02_App I - Checklist.txt","VETTING_PACKAGE",null,"CHECKLIST ANSWER LEAK TEST ONLY");
        source(id,"OTHER","01_Contractual advice Q&A.txt","VETTING_PACKAGE",null,"ADVICE ANSWER LEAK TEST ONLY");
        source(id,"OTHER","02_Appendix-Flowchart.txt","VETTING_PACKAGE",null,"FLOWCHART ANSWER LEAK TEST ONLY");
        source(id,"SP","Project PSS - Concrete works.txt","VETTING_PACKAGE",null,"WORKS PSS LEAK TEST ONLY");
        source(id,"BQ","Bills of Quantities.txt","VETTING_PACKAGE",null,"PRICING LEAK TEST ONLY");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.chunks").value(5));
        JsonNode listed=data(get("/api/advice/{id}/sources",id));
        assertEquals(5,listed.findValues("included").stream().filter(JsonNode::asBoolean).count());
    }
    @Test void contractScopeFiltersBeforeTopKAndStaleOrOtherProjectHitsCannotEnterThePrompt() throws Exception {
        String id=project(),other=project();
        SourceDocument old=source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"OLD CONTRACT SHOULD NEVER ENTER TEST ONLY");
        source(id,"NTT","03 - NTT.txt","VETTING_PACKAGE",null,"TENDER ONLY SHOULD NEVER ENTER CONTRACT TEST ONLY");
        source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"NEW CONTRACT CURRENT TEST ONLY");
        source(other,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"OTHER PROJECT SHOULD NEVER ENTER TEST ONLY");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        JsonNode answer=ask(id,"contract");
        assertTrue(answer.path("grounded").asBoolean());assertEquals(1,answer.path("sources").size());
        assertTrue(answer.path("sources").get(0).path("content").asText().contains("NEW CONTRACT CURRENT"));
        assertFalse(answer.path("sources").get(0).path("documentId").asText().equals(String.valueOf(old.getId())));
        String prompt=String.join("\n",prompts);assertFalse(prompt.contains("TENDER ONLY"));assertFalse(prompt.contains("OTHER PROJECT"));assertFalse(prompt.contains("OLD CONTRACT"));
        assertEquals("contract",answer.path("sourceScope").asText());
    }
    @Test void aDocumentReplacementRequiresRefreshAndCannotSilentlyAnswerWithOldEvidence() throws Exception {
        String id=project();source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"OLD REVISION TEST ONLY");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"NEW REVISION TEST ONLY");
        mvc.perform(get("/api/advice/{id}/index/status",id)).andExpect(jsonPath("$.data.stale").value(true))
                .andExpect(jsonPath("$.data.message.en").value("The review package or source revision changed. Refresh the advice evidence index before asking."));
        mvc.perform(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"TEST ONLY?\",\"scope\":\"fullset\"}")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chat(anyList());
    }
    @Test void fakeCitationCannotMakeAnAnswerGroundedAndActualHistoryEvidenceIsFrozen() throws Exception {
        String id=project();SourceDocument original=source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE",null,"FROZEN EVIDENCE TEST ONLY");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY fabricated\",\"citations\":[\"outside-file SCC999\"],\"clarifications\":[\"What stage is this contract at?\"]}");
        JsonNode rejected=ask(id,"fullset");assertFalse(rejected.path("grounded").asBoolean());
        assertEquals("What stage is this contract at?",rejected.path("clarifications").get(0).asText());
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY accepted\",\"citations\":[\"[1]\"]}");
        JsonNode accepted=ask(id,"fullset");assertTrue(accepted.path("grounded").asBoolean());
        original.setTextContent("MUTATED CONTENT TEST ONLY");sources.saveAndFlush(original);
        JsonNode history=data(get("/api/advice/{id}/messages",id));
        assertEquals(accepted.path("sources"),history.get(history.size()-1).path("sources"));
        assertFalse(history.get(history.size()-1).path("sources").toString().contains("MUTATED CONTENT"));
        assertTrue(accepted.path("sources").get(0).path("pageNo").isNull()||accepted.path("sources").get(0).path("pageNo").isMissingNode());
    }
    @Test void arbitraryScopeIsRejectedRatherThanPretendingToRestrictEvidence() throws Exception {
        String id=project();
        mvc.perform(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"TEST ONLY?\",\"scope\":\"untrusted-all\"}")).andExpect(jsonPath("$.code").value(4202));
    }
    @Test void projectFactsOrAPackageManifestAloneCannotEstablishContractualGrounding() throws Exception {
        for(String role:new String[]{"project_fact","package_manifest"}){
            String id=project();source(id,"OTHER","Meeting or composition list.txt","VETTING_PACKAGE",role,"TEST ONLY background: participants expect quantities to be firm, but no adopted contractual provision is supplied.");
            mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
            when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY asserted contractual conclusion\",\"citations\":[\"[1]\"],\"basisScope\":\"project_contract\"}");
            JsonNode answer=ask(id,"fullset");assertFalse(answer.path("grounded").asBoolean());assertEquals("insufficient",answer.path("basisScope").asText());assertFalse(answer.path("clarifications").isEmpty());
        }
    }
    @Test void technicalLibraryHitIncludesCurrentSpecificationAdoptionContextBeforeTheModelAnswers() throws Exception {
        String id=project();source(id,"SL","Specification Library SL2022.txt","VETTING_PACKAGE","standard","SL CON2.1 TECHNICAL LIBRARY TEST ONLY");
        source(id,"SP","24 - The Specification.txt","VETTING_PACKAGE","tender","The Specification Library SL2022 edition August 2024 is adopted except PRE. Project PRE replaces standard PRE. TEST ONLY");
        when(local.embed(anyList())).thenAnswer(call->{List<float[]> v=new ArrayList<>();for(Object text:call.<List<?>>getArgument(0))v.add(String.valueOf(text).contains("TECHNICAL LIBRARY")||String.valueOf(text).contains("question")?new float[]{1,0}:new float[]{.8f,.2f});return v;});
        when(local.chat(anyList())).thenAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST ONLY adopted technical condition\",\"citations\":[\"[1]\",\"[2]\"],\"basisScope\":\"project_contract\"}";});
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        JsonNode answer=ask(id,"fullset");assertTrue(answer.path("grounded").asBoolean());
        assertTrue(String.join("\n",prompts).contains("Project PRE replaces standard PRE"));
        assertEquals(2,answer.path("sources").size());assertEquals("project_contract",answer.path("basisScope").asText());
    }
    @Test void incorporationContextMetadataCannotTellTheModelTheEntireLibraryIsComplete() throws Exception {
        String id=project();source(id,"SL","Specification Library SL2022.txt","VETTING_PACKAGE","standard","SL CON2.1 TECHNICAL LIBRARY TEST ONLY SELECTED CLAUSE");
        source(id,"SP","24 - The Specification.txt","VETTING_PACKAGE","tender","The Specification Library SL2022 edition August 2024 is adopted except PRE. Project PRE replaces standard PRE. TEST ONLY");
        when(local.embed(anyList())).thenAnswer(call->{List<float[]> v=new ArrayList<>();for(Object text:call.<List<?>>getArgument(0))v.add(String.valueOf(text).contains("TECHNICAL LIBRARY")||String.valueOf(text).contains("question")?new float[]{1,0}:new float[]{.8f,.2f});return v;});
        when(local.chat(anyList())).thenAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST ONLY adopted technical condition\",\"citations\":[\"[1]\",\"[2]\"],\"basisScope\":\"project_contract\"}";});
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        JsonNode answer=ask(id,"fullset");assertTrue(answer.path("grounded").asBoolean());
        assertTrue(answer.path("sources").findValues("applicabilityContextComplete").stream().anyMatch(JsonNode::asBoolean),"Keep bounded incorporation metadata for the gate and citations");
        String prompt=String.join("\n",prompts);
        assertFalse(prompt.contains("incorporationContextComplete"),"An internal bounded-context flag must not imply a complete standard corpus");
        assertFalse(prompt.contains("applicabilityContextComplete"));
        assertTrue(prompt.contains("Selected source excerpts"));
        assertTrue(prompt.contains("do not establish that the entire standard/library or project package is complete"));
        assertTrue(prompt.contains("Project PRE replaces standard PRE"));
    }
    @Test void standardLibraryAloneCannotEstablishAdoptionOrSilentlySwitchTheRequestedBasis() throws Exception {
        String id=project();source(id,"SL","Specification Library SL2022.txt","VETTING_PACKAGE","standard","SL WAT1 WATERPROOFING LIBRARY REFERENCE TEST ONLY");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        JsonNode unknown=ask(id,"fullset");assertFalse(unknown.path("grounded").asBoolean());assertFalse(unknown.path("clarifications").isEmpty());
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY standard reference explanation; project applicability remains unconfirmed\",\"citations\":[\"[1]\"],\"basisScope\":\"reference_only\"}");
        JsonNode reference=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Explain the SL WAT1 standard library reference clause.\",\"scope\":\"fullset\"}"));
        assertFalse(reference.path("grounded").asBoolean());assertEquals("insufficient",reference.path("basisScope").asText());
        JsonNode history=data(get("/api/advice/{id}/messages",id));assertEquals("insufficient",history.get(history.size()-1).path("basisScope").asText());
    }
    @Test void incompleteBoundedSpecificationCompositionCannotProveProjectApplicability() throws Exception {
        String id=project();source(id,"SL","Specification Library SL2022.txt","VETTING_PACKAGE","standard","SL CON2.1 TECHNICAL LIBRARY TEST ONLY");
        StringBuilder large=new StringBuilder();for(int i=0;i<650;i++)large.append("Specification Library SL2022 adoption detail TEST ONLY.\n");
        source(id,"SP","24 - The Specification.txt","VETTING_PACKAGE","tender",large.toString());
        when(local.embed(anyList())).thenAnswer(call->{List<float[]> v=new ArrayList<>();for(Object text:call.<List<?>>getArgument(0))v.add(String.valueOf(text).contains("TECHNICAL LIBRARY")||String.valueOf(text).contains("question")?new float[]{1,0}:new float[]{.8f,.2f});return v;});
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY adopted requirement\",\"citations\":[\"[1]\",\"[2]\"],\"basisScope\":\"project_contract\"}");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        JsonNode answer=ask(id,"fullset");assertFalse(answer.path("grounded").asBoolean());assertFalse(answer.path("clarifications").isEmpty());
    }
    @Test void explicitUploadScopeIsIndependentOfRoleAndVisibleRatherThanSilentlyUsedAsContractEvidence() throws Exception {
        String id=project();
        mvc.perform(multipart("/api/vetting/{id}/package/upload",id).file(new MockMultipartFile("files","ambiguous.txt","text/plain","TEST ONLY works-related PSS".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("sourceRole","tender").param("documentScope","works_pss")).andExpect(jsonPath("$.code").value(0));
        JsonNode catalog=data(get("/api/advice/{id}/sources",id));
        JsonNode uploaded=null;for(JsonNode source:catalog)if("ambiguous.txt".equals(source.path("fileName").asText()))uploaded=source;
        assertNotNull(uploaded);assertEquals("tender",uploaded.path("sourceRole").asText());assertEquals("works_pss",uploaded.path("documentScope").asText());
        assertFalse(uploaded.path("included").asBoolean());assertEquals("project_works_specification",uploaded.path("exclusionReason").asText());
        JsonNode reviewed=data(get("/api/vetting/{id}/files",id));assertFalse(reviewed.toString().contains("ambiguous.txt"));
    }
    @Test void explicitPackageLabelCannotOverrideTheCompetitionPricingOrWorksPssExclusions() throws Exception {
        String id=project();
        String[][] cases={{"BQ","18 - Bills of Quantities.txt","tender","pricing_document"},{"GS","22 - General Summary.txt","tender","pricing_document"},
                {"SOR","Rates.txt","tender","pricing_document"},{"SP","PSS Concrete Works.txt","tender","project_works_specification"},
                {"PRE","PSS Preliminaries.txt","tender",""},{"SL","Specification Library SL2022.txt","standard",""},{"GS","General Specification for Civil Engineering Works.txt","standard",""},
                {"GS","GS.pdf","standard",""},{"GS","General Summary - incorrect standard label.txt","standard","pricing_document"},{"GS","GS tender summary.txt","tender","pricing_document"}};
        for(String[] c:cases){SourceDocument d=source(id,c[0],c[1],"VETTING_PACKAGE",c[2],"TEST ONLY scope fixture");d.setDocumentScope("package");sources.saveAndFlush(d);}
        JsonNode catalog=data(get("/api/advice/{id}/sources",id));
        for(String[] c:cases){JsonNode found=null;for(JsonNode entry:catalog)if(c[1].equals(entry.path("fileName").asText()))found=entry;assertNotNull(found);assertEquals(c[3].isEmpty(),found.path("included").asBoolean(),c[1]);if(!c[3].isEmpty())assertEquals(c[3],found.path("exclusionReason").asText());}
    }
    @Test void anExplicitClarificationReplyKeepsTheOriginalQuestionAndLabelsUserFactsOutsideContractCitations() throws Exception {
        String id=project();source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE","tender","SCC4.1 TEST ONLY contractual provision; quantities are set by the adopted contract.");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        when(local.chat(anyList())).thenReturn("{\"grounded\":false,\"missingReason\":\"TEST ONLY contract stage unknown\",\"clarifications\":[\"Is this pre-contract or post-contract?\"]}");
        JsonNode clarification=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"ORIGINAL QUESTION TEST ONLY: how should the quantity correction be handled?\",\"scope\":\"contract\"}"));
        assertTrue(clarification.path("messageId").isIntegralNumber());long reply=clarification.path("messageId").asLong();
        when(local.chat(anyList())).thenAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST ONLY conditional answer\",\"citations\":[\"[1]\"]}";});
        JsonNode answer=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"USER FACT TEST ONLY: post-contract, firm quantities\",\"scope\":\"contract\",\"replyToMessageId\":"+reply+"}"));
        assertTrue(answer.path("grounded").asBoolean());String prompt=String.join("\n",prompts);
        assertTrue(prompt.contains("ORIGINAL QUESTION TEST ONLY"));assertTrue(prompt.contains("USER FACT TEST ONLY"));assertTrue(prompt.contains("user-supplied-background"));
        assertFalse(answer.path("sources").toString().contains("USER FACT TEST ONLY"));
    }
    @Test void aModelAnswerRequiringNecessaryClarificationRemainsInsufficientAndCanBeRepliedTo() throws Exception {
        String id=project();source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE","tender","SCC4.1 TEST ONLY adopted contractual condition");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY tentative conclusion\",\"citations\":[\"[1]\"],\"clarifications\":[\"Is this pre-contract or post-contract?\"]}");
        JsonNode pending=ask(id,"contract");assertFalse(pending.path("grounded").asBoolean());assertEquals("insufficient",pending.path("basisScope").asText());assertEquals("Is this pre-contract or post-contract?",pending.path("clarifications").get(0).asText());
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST ONLY conditional conclusion following user background\",\"citations\":[\"[1]\"]}");
        JsonNode completed=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"TEST ONLY post-contract\",\"scope\":\"contract\",\"replyToMessageId\":"+pending.path("messageId").asLong()+"}"));assertTrue(completed.path("grounded").asBoolean());
    }
    @Test void clarificationReplyCannotCrossProjectOrScopeAndANewQuestionDoesNotInheritIt() throws Exception {
        String id=project(),other=project();source(id,"SCC","10 - SCC.txt","VETTING_PACKAGE","tender","SCC4.1 TEST ONLY basis");source(other,"SCC","10 - SCC.txt","VETTING_PACKAGE","tender","SCC4.1 OTHER TEST ONLY basis");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));mvc.perform(post("/api/advice/{id}/index/rebuild",other)).andExpect(jsonPath("$.code").value(0));
        when(local.chat(anyList())).thenReturn("{\"grounded\":false,\"clarifications\":[\"What contract stage?\"]}");
        long reply=ask(id,"contract").path("messageId").asLong();
        for(String project:new String[]{id,other})mvc.perform(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"TEST ONLY reply\",\"scope\":\"fullset\",\"replyToMessageId\":"+reply+"}")).andExpect(jsonPath("$.code").value(4202));
        when(local.chat(anyList())).thenAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST ONLY separate answer\",\"citations\":[\"[1]\"]}";});
        JsonNode separate=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"SEPARATE NEW QUESTION TEST ONLY\",\"scope\":\"contract\"}"));
        assertTrue(separate.path("grounded").asBoolean());assertFalse(String.join("\n",prompts).contains("user-supplied-background"));
    }
    @Test void verifiedPartialAnalysisAndChecksSurviveHistoryWithoutBecomingGrounded() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED contractual procedural provision");
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        when(local.chat(anyList())).thenReturn("{\"grounded\":false,\"answer\":\"AUTHORED supported procedure [1]; the disputed entitlement is unresolved\",\"citations\":[\"[1]\"],\"missingReason\":\"AUTHORED dates are unknown\",\"clarifications\":[\"When was the notice received?\"]}");
        JsonNode result=ask(id,"contract");assertFalse(result.path("grounded").asBoolean());assertEquals("insufficient",result.path("basisScope").asText());
        assertTrue(result.path("content").path("en").asText().contains("supported procedure [1]"));assertEquals("[1]",result.path("citations").get(0).asText());
        JsonNode history=data(get("/api/advice/{id}/messages",id));JsonNode assistant=history.get(history.size()-1);
        assertEquals(result.path("content"),assistant.path("content"));assertEquals(result.path("citations"),assistant.path("citations"));assertEquals("insufficient",assistant.path("basisScope").asText());
        com.consense.domain.ChatMessage row=messages.findById(result.path("messageId").asLong()).get();JsonNode checks=JsonUtils.parse(row.getAdviceChecksJson());
        assertEquals("ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470",checks.path("policySha256").asText());assertEquals(3,checks.path("publishedSectionIds").size());
        assertTrue(checks.path("applicationRejections").isEmpty());assertTrue(checks.path("sections").get(1).path("checks").isArray());assertEquals(result.path("sources").get(0).path("sourceHash"),JsonUtils.parse(row.getSourcesJson()).get(0).path("sourceHash"));
    }
    @Test void unrecordedChecksAreRejectedRatherThanInventedForNewAnswers() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED provision");mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        doReturn("{\"grounded\":true,\"answer\":\"UNVERIFIED LEGACY OUTPUT\",\"citations\":[\"[1]\"]}").when(local).chatStructured(anyList(),any());
        mvc.perform(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Explain this\",\"scope\":\"contract\"}")).andExpect(jsonPath("$.code").value(4205));
        assertTrue(data(get("/api/advice/{id}/messages",id)).isEmpty());verify(local,times(2)).chatStructured(anyList(),any());
    }
    @Test void closedVerifiedUnitsArePublishedBeforeProviderCompletesAndCancellationSavesNoExchange() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED provision");mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        final List<String> published=new ArrayList<>(),phases=new ArrayList<>();final boolean[] cancelled={false};
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            public boolean streaming(){return true;}public void phase(String p){phases.add(p);}public void section(String text,List<String> refs,List<com.consense.web.dto.AdviceDtos.CitationVO> sources){published.add(text);}
            public void checkCancelled(){if(cancelled[0])throw new java.util.concurrent.CancellationException("AUTHORED request stop");}
        };
        String output=com.consense.service.advice.AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED streamed analysis [1]\",\"citations\":[\"[1]\"]}");
        when(local.supportsStreaming()).thenReturn(true);
        doAnswer(call->{java.util.function.Consumer<String> delta=call.getArgument(2);for(int i=0;i<output.length();i++)delta.accept(output.substring(i,i+1));assertEquals(2,published.size());assertTrue(published.get(1).contains("analysis [1]"));assertTrue(messages.findByProjectIdOrderByIdAsc(id).isEmpty());return output;}).when(local).chatStructuredStream(anyList(),any(),any(),any());
        assertTrue(advice.ask(id,new com.consense.web.dto.AdviceDtos.AskRequest("Explain the clause","contract"),progress).isGrounded());assertTrue(phases.contains("retrieving"));assertTrue(phases.contains("generating"));assertTrue(phases.contains("saving"));
        String stopped=project();source(stopped,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED provision");mvc.perform(post("/api/advice/{id}/index/rebuild",stopped)).andExpect(jsonPath("$.code").value(0));
        doAnswer(call->{java.util.function.Consumer<String> delta=call.getArgument(2);cancelled[0]=true;delta.accept(output);return output;}).when(local).chatStructuredStream(anyList(),any(),any(),any());
        assertThrows(java.util.concurrent.CancellationException.class,()->advice.ask(stopped,new com.consense.web.dto.AdviceDtos.AskRequest("Explain","contract"),progress));assertTrue(messages.findByProjectIdOrderByIdAsc(stopped).isEmpty());
    }
    @Test void invalidStreamUsesOnlyOneFreshlyCountedRepair() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED provision");mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        String valid=com.consense.service.advice.AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED repaired stream [1]\",\"citations\":[\"[1]\"]}");java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        when(local.supportsStreaming()).thenReturn(true);doAnswer(call->{java.util.function.Consumer<String> delta=call.getArgument(2);String raw=calls.getAndIncrement()==0?"@invalid":valid;delta.accept(raw);return raw;}).when(local).chatStructuredStream(anyList(),any(),any(),any());
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){public boolean streaming(){return true;}};
        assertTrue(advice.ask(id,new com.consense.web.dto.AdviceDtos.AskRequest("Explain","contract"),progress).isGrounded());assertEquals(2,calls.get());verify(counter,times(2)).observe(any());
        JsonNode checks=JsonUtils.parse(messages.findByProjectIdOrderByIdAsc(id).get(1).getAdviceChecksJson());assertEquals(2,checks.path("attempts").size());assertFalse(checks.path("attempts").get(0).path("contractParsed").asBoolean());
    }
    @Test void naturalFlowchartQuestionUsesApplicationHarnessAndGraphRepairSharesTheTotalBudget() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED Contractor submits notice; evidence review follows.");mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        com.fasterxml.jackson.databind.node.ObjectNode answer=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(com.consense.service.advice.AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED notice procedure [1]\",\"citations\":[\"[1]\"]}"));answer.put("questionType","T6");
        com.fasterxml.jackson.databind.node.ObjectNode graph=(com.fasterxml.jackson.databind.node.ObjectNode)answer.path("sections").get(1);graph.put("kind","flowchart");
        graph.set("flowchartPlan",JsonUtils.parse("{\"nodes\":[{\"id\":\"s\",\"label\":\"Start\",\"kind\":\"start\",\"citations\":[]},{\"id\":\"f\",\"label\":\"Review evidence\",\"kind\":\"end\",\"citations\":[\"[1]\"]}],\"edges\":[{\"from\":\"s\",\"to\":\"f\",\"condition\":\"\",\"actor\":\"Contractor\",\"action\":\"Submit notice\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]}]}"));
        com.fasterxml.jackson.databind.node.ObjectNode invalid=answer.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("sections").get(1).path("flowchartPlan").path("edges").get(0)).put("period","28 days");
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();doAnswer(call->{String prompt=JsonUtils.write(call.getArgument(0));assertTrue(prompt.contains("Application task selection: T6"));assertTrue(prompt.contains("请画个处理流程图"));return JsonUtils.write(calls.getAndIncrement()==0?invalid:answer);}).when(local).chatStructured(anyList(),any());
        JsonNode result=data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"请画个处理流程图\",\"scope\":\"contract\"}"));assertTrue(result.path("grounded").asBoolean());assertEquals(2,calls.get());verify(counter,times(2)).observe(any());
        String content=result.path("content").path("en").asText();assertTrue(content.contains("```mermaid\nflowchart TD"));assertTrue(content.contains("Contractor; Submit notice; [1]"));assertFalse(content.contains("28 days"));
        JsonNode checks=JsonUtils.parse(messages.findByProjectIdOrderByIdAsc(id).get(1).getAdviceChecksJson());assertEquals("T6",checks.path("questionType").asText());assertEquals(2,checks.path("attempts").size());assertFalse(checks.path("attempts").get(0).path("rejections").isEmpty());
        java.nio.file.Files.write(java.nio.file.Paths.get("C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/test-output/harness-authored-flowchart.md"),content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void cancellationDuringAtomicSaveRollsBackMessagesAndChecksTogether() throws Exception {
        String id=project();source(id,"SCC","SCC.txt","VETTING_PACKAGE","tender","AUTHORED provision");mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        final boolean[] saving={false};com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){public void phase(String phase){if("saving".equals(phase))saving[0]=true;}public void checkCancelled(){if(saving[0]&&!messages.findByProjectIdOrderByIdAsc(id).isEmpty())throw new java.util.concurrent.CancellationException("AUTHORED stop before atomic commit");}};
        assertThrows(java.util.concurrent.CancellationException.class,()->advice.ask(id,new com.consense.web.dto.AdviceDtos.AskRequest("Explain","contract"),progress));assertTrue(messages.findByProjectIdOrderByIdAsc(id).isEmpty());
    }
    private JsonNode ask(String id,String scope)throws Exception{return data(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"TEST ONLY question?\",\"scope\":\""+scope+"\"}"));}
    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)throws Exception{return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private String project()throws Exception{String id="scope-test-"+UUID.randomUUID();data(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"SCOPE TEST ONLY\"}"));return id;}
    private SourceDocument source(String id,String key,String name,String category,String role,String text){SourceDocument s=new SourceDocument();s.setProjectId(id);s.setFileKey(key);s.setFileName(name);s.setCategory(category);s.setReviewRole(role);s.setTextContent(text);s.setParseStatus("PARSED");s.setPageCount(25);s.setCreatedAt(Instant.now());
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);StorageService.StoredFile original=storage.store(id,category,new MockMultipartFile("files",name,"text/plain",bytes));
        s.setStoragePath(original.getPath());s.setSourceSha256(OerSourceMetadata.originalSha256(bytes));return sources.saveAndFlush(s);}
}
