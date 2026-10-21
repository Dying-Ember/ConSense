package com.consense.web;

import com.consense.domain.Project;
import com.consense.domain.VettingRun;
import com.consense.common.JsonUtils;
import com.consense.repository.ProjectRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.seed.DemoDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual REST plus isolated H2 persistence, without seed data or model/parser calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VettingChecklistWorkflowTest {
    private static final String ISOLATION=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:checklist_"+ISOLATION+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target/checklist-uploads",ISOLATION).toAbsolutePath().toString());
    }
    @MockBean DemoDataInitializer seed;
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired VettingRunRepository runs;
    @Autowired com.consense.repository.VettingFindingRepository findings;

    @Test void completeReviewWithNoFindingsLeavesEveryChecklistRequirementUnchecked() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        mvc.perform(get(url(run)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.writable").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("APP-I-001"))
                .andExpect(jsonPath("$.data.items[0].status").value("unchecked"))
                .andExpect(jsonPath("$.data.items[0].remarks").value(""))
                .andExpect(jsonPath("$.data.logicalGroups.length()").value(44))
                .andExpect(jsonPath("$.data.logicalGroups[18].id").value("CK-019"))
                .andExpect(jsonPath("$.data.logicalGroups[18].sourceItemIds[0]").value("APP-I-026"))
                .andExpect(jsonPath("$.data.annexItemIds.length()").value(2))
                .andExpect(jsonPath("$.data.annexItemIds[0]").value("APP-I-068"));
    }

    @Test void explicitHumanCountercheckPersistsOnlyForItsExactCompletedRun() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        JsonNode initial=read(run);
        String remarks="  Reviewed by the Contract Team.\r\nNot an issued addendum.  ";
        Map<String,Object> update=new LinkedHashMap<>();update.put("runSnapshotSha256",initial.path("runSnapshotSha256").asText());update.put("expectedRunId",run.getId());update.put("expectedSourceSnapshotSha256",initial.path("sourceSnapshotSha256").asText());
        Map<String,Object> item=new LinkedHashMap<>();item.put("id","APP-I-001");item.put("status","fulfilled");item.put("remarks",remarks);
        update.put("items",Arrays.asList(item));
        mvc.perform(put(url(run)).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].status").value("fulfilled"))
                .andExpect(jsonPath("$.data.items[0].remarks").value(remarks));
        JsonNode saved=read(run);
        assertEquals("fulfilled",saved.path("items").get(0).path("status").asText());
        assertEquals(remarks,saved.path("items").get(0).path("remarks").asText());
        assertFalse(saved.path("items").get(0).path("updatedAt").isNull());
        assertEquals("unchecked",saved.path("items").get(1).path("status").asText());
        VettingRun next=newRun(run.getProjectId(),"{\"total\":0}");
        assertEquals("unchecked",read(next).path("items").get(0).path("status").asText());
        assertNotEquals(saved.path("runSnapshotSha256").asText(),read(next).path("runSnapshotSha256").asText());
    }

    @Test void unchangedSourceAndGeneratedChecksCanReuseHumanCountercheckAcrossRuns() throws Exception {
        VettingRun first=completedRun("{\"total\":0}");
        first.setModelIdentityJson("{\"profileId\":\"local\",\"provider\":\"offline-contract-fixture\",\"model\":\"fixed-model\",\"configurationSha256\":\""
                +com.consense.service.vetting.VettingCorpus.hash("fixed model configuration")+"\",\"identityScope\":\"configured-contract-fixture\"}");
        first.setRecoveryContractSha256(com.consense.service.vetting.VettingCorpus.hash("fixed review recipe"));runs.saveAndFlush(first);
        JsonNode initial=read(first);
        save(first,initial.path("runSnapshotSha256").asText(),Collections.singletonList(row("APP-I-001","fulfilled","Exact version manually checked")))
                .andExpect(jsonPath("$.code").value(0));
        VettingRun next=newRun(first.getProjectId(),first.getResultJson());
        next.setModelIdentityJson(first.getModelIdentityJson());next.setRecoveryContractSha256(first.getRecoveryContractSha256());runs.saveAndFlush(next);
        JsonNode reused=read(next).path("items").get(0);
        assertEquals("fulfilled",reused.path("status").asText());assertEquals("Exact version manually checked",reused.path("remarks").asText());
        assertEquals(first.getId(),reused.path("reviewedRunId").asText());
        assertEquals("required_member_dependencies_unknown",reused.path("dependencyStatus").asText());
        assertEquals("exact_whole_generation",reused.path("reuseScope").asText());assertEquals("matched_exact_whole_generation",reused.path("reuseStatus").asText());
        next.setCoverageJson("{\"documents\":[],\"warnings\":[\"new generated limitation\"]}");runs.saveAndFlush(next);
        assertEquals("unchecked",read(next).path("items").get(0).path("status").asText());
        JsonNode pending=read(next).path("items").get(0);assertEquals("history_only_dependency_or_generation_unknown",pending.path("reuseStatus").asText());
        assertEquals("fulfilled",pending.path("previousDecisionStatus").asText());assertEquals("Exact version manually checked",pending.path("previousRemarks").asText());assertEquals(first.getId(),pending.path("previousReviewedRunId").asText());
        assertEquals("fulfilled",read(first).path("items").get(0).path("status").asText(),"Historical human record retains its original generation");
    }

    @Test void staleSnapshotAndUnknownRowsCannotPartiallyReplaceHumanCounterchecks() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","Original human decision")))
                .andExpect(jsonPath("$.code").value(0));
        save(run,String.join("",Collections.nCopies(64,"0")),Collections.singletonList(row("APP-I-001","unchecked","Must not overwrite")))
                .andExpect(jsonPath("$.code").value(4123));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        save(run,binding,Arrays.asList(row("APP-I-001","unchecked","Must not partially save"),row("APP-I-999","fulfilled","Unknown requirement")))
                .andExpect(jsonPath("$.code").value(4121));
        assertEquals("Original human decision",read(run).path("items").get(0).path("remarks").asText());
        save(run,binding,Arrays.asList(row("APP-I-001","unchecked","First"),row("APP-I-001","not_applicable","Contradictory duplicate")))
                .andExpect(jsonPath("$.code").value(4121));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
    }

    @Test void unfinishedRunsAndOtherProjectsCannotAcceptCounterchecks() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        VettingRun other=completedRun("{\"total\":0}");
        mvc.perform(get("/api/vetting/"+other.getProjectId()+"/runs/"+run.getId()+"/checklist"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(4102));
        run.setStatus("RUNNING");runs.saveAndFlush(run);
        assertFalse(read(run).path("writable").asBoolean());
        save(run,read(run).path("runSnapshotSha256").asText(),Collections.singletonList(row("APP-I-001","fulfilled","Premature")))
                .andExpect(jsonPath("$.code").value(4104));
        assertEquals("unchecked",read(run).path("items").get(0).path("status").asText());
    }

    @Test void rowUpdatesKeepOtherDecisionsAndSupportManualNotApplicableAndReset() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","A"))).andExpect(jsonPath("$.code").value(0));
        save(run,binding,Collections.singletonList(row("APP-I-002","not_applicable","Human reviewed scope"))).andExpect(jsonPath("$.code").value(0));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        assertEquals("not_applicable",read(run).path("items").get(1).path("status").asText());
        save(run,binding,Collections.singletonList(row("APP-I-001","unchecked","Recheck requested"))).andExpect(jsonPath("$.code").value(0));
        assertEquals("unchecked",read(run).path("items").get(0).path("status").asText());
        assertEquals("not_applicable",read(run).path("items").get(1).path("status").asText());
    }

    @Test void invalidStatesAndOversizedRemarksLeavePreviouslySavedRowsIntact() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","Existing decision"))).andExpect(jsonPath("$.code").value(0));
        save(run,binding,Collections.singletonList(row("APP-I-001","Handled","Not a checklist state"))).andExpect(jsonPath("$.code").value(4001));
        save(run,binding,Collections.singletonList(row("APP-I-001","unchecked",String.join("",Collections.nCopies(4001,"x")))))
                .andExpect(jsonPath("$.code").value(4001));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        assertEquals("Existing decision",read(run).path("items").get(0).path("remarks").asText());
    }

    @Test void explicitHumanNonFulfilmentRequiresReasonAndNeverChangesOtherRows() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","Existing human decision")))
                .andExpect(jsonPath("$.code").value(0));
        for(String reason:Arrays.asList(null,""," \r\n\t ")) {
            save(run,binding,Arrays.asList(row("APP-I-001","unchecked","Must not partially save"),
                    row("APP-I-002","not_fulfilled",reason))).andExpect(jsonPath("$.code").value(4121));
            assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
            assertEquals("unchecked",read(run).path("items").get(1).path("status").asText());
        }
        String reason="REQ: The reviewed clause does not identify the obligation holder.";
        save(run,binding,Collections.singletonList(row("APP-I-002","not_fulfilled",reason)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode saved=read(run).path("items");
        assertEquals("fulfilled",saved.get(0).path("status").asText());
        assertEquals("not_fulfilled",saved.get(1).path("status").asText());
        assertEquals(reason,saved.get(1).path("remarks").asText());
        assertEquals("unchecked",saved.get(2).path("status").asText());
    }

    @Test void aBoundAutomaticSuggestionNeverWritesOrReusesAHumanDecision() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String policy=com.consense.service.vetting.VettingCorpus.hash("authored checklist policy");
        String packetSource=com.consense.service.vetting.VettingCorpus.hash("authored packet snapshot");
        com.fasterxml.jackson.databind.node.ObjectNode coverage=JsonUtils.mapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode packet=coverage.putArray("semanticTopics").addObject().putArray("packetAudits").addObject();
        packet.put("packetId","authored-packet").put("sourceSnapshotSha256",packetSource).put("actualGatewayCallStarted",true).put("status","completed");
        packet.putArray("submittedChunkIds").add("authored-chunk");
        com.fasterxml.jackson.databind.node.ObjectNode assessment=packet.putArray("checklistAssessments").addObject();
        assessment.put("itemId","APP-I-011").put("status","fulfilled").put("reason","The supplied clause expressly fulfils this requirement.")
                .put("producerVersion","pm-checklist-packet-v1").put("policySha256",policy).put("packetId","authored-packet").put("sourceSnapshotSha256",packetSource);
        assessment.putArray("evidenceChunkIds").add("authored-chunk");
        com.fasterxml.jackson.databind.node.ObjectNode quote=assessment.putArray("evidence").addObject().put("side","source").put("documentId","71").put("quote","Authored complete requirement wording.")
                .put("located",true).put("sourceRole","tender")
                .put("sourceHash",com.consense.service.vetting.VettingCorpus.hash("revision"))
                .put("originalSourceSha256",com.consense.service.vetting.VettingCorpus.hash("original"))
                .put("packetId","authored-packet").put("packetSourceSnapshotSha256",packetSource);
        com.fasterxml.jackson.databind.node.ObjectNode pointer=quote.putObject("canonicalEvidence").put("addressKind","canonical_adoption_unit")
                .put("projectId",run.getProjectId()).put("documentId",71).put("canonicalChunkId","authored-chunk")
                .put("originalSourceSha256",com.consense.service.vetting.VettingCorpus.hash("original"))
                .put("canonicalSourceHash",com.consense.service.vetting.VettingCorpus.hash("canonical revision"))
                .put("javaParsedSourceHash",com.consense.service.vetting.VettingCorpus.hash("revision"));
        com.fasterxml.jackson.databind.node.ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("total",0).putObject("promptPolicy").put("sha256",policy);
        com.consense.service.vetting.VettingChecklistCatalog.Item requirement=com.consense.service.vetting.VettingChecklistCatalog.items().get(10);
        com.fasterxml.jackson.databind.node.ObjectNode dependencies=coverage.putObject("checklistDependencyLedger");
        dependencies.put("producerVersion","checklist-current-required-material-members-v1").put("projectId",run.getProjectId());
        com.fasterxml.jackson.databind.node.ObjectNode dependency=dependencies.putObject("items").putObject("APP-I-011").put("status","required_member_scope_closed")
                .put("requirementSha256",com.consense.service.vetting.VettingCorpus.hash(JsonUtils.write(Arrays.asList(requirement.getId(),requirement.getSection(),requirement.getClause(),requirement.getDescription(),requirement.getReviewScope(),requirement.getScopeNote()))));
        dependency.put("basisSha256",com.consense.service.vetting.VettingCorpus.hash("authored closed required members"));dependency.putArray("requiredMemberRefs").addArray().add(pointer.deepCopy());
        run.setCoverageJson(coverage.toString());run.setResultJson(result.toString());runs.saveAndFlush(run);
        JsonNode current=read(run);
        assertEquals("fulfilled",current.path("automaticResults").get(6).path("status").asText());
        assertEquals("\u2713",current.path("automaticResults").get(6).path("symbol").asText());
        assertEquals("unchecked",current.path("items").get(10).path("status").asText());
        assertFalse(current.path("items").get(10).hasNonNull("updatedAt"));
        assessment.put("policySha256",com.consense.service.vetting.VettingCorpus.hash("another policy"));run.setCoverageJson(coverage.toString());runs.saveAndFlush(run);
        current=read(run);
        assertEquals("insufficient_evidence",current.path("automaticResults").get(6).path("status").asText());
        assertEquals("",current.path("automaticResults").get(6).path("symbol").asText());
        assertEquals("unchecked",current.path("items").get(10).path("status").asText());
        assertEquals("required_member_scope_closed",current.path("items").get(10).path("dependencyStatus").asText());
        assertEquals("exact_whole_generation",current.path("items").get(10).path("reuseScope").asText());
    }

    @Test void downloadedReportsContainTheSameExplicitHumanChecklistDecisions() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        String checkedRemarks="Reviewed applicable scope with the Contract Team.";
        String excludedRemarks="Not applicable to this selected contract after manual countercheck.";
        String unfulfilledRemarks="REQ: Reviewed wording leaves the obligation holder unspecified.";
        save(run,binding,Arrays.asList(row("APP-I-001","fulfilled",checkedRemarks),row("APP-I-002","not_applicable",excludedRemarks),
                row("APP-I-004","not_fulfilled",unfulfilledRemarks)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        assertEquals(run.getId(),report.path("job").path("id").asText());
        assertEquals("fulfilled",report.path("checklistResults").get(0).path("status").asText());
        assertEquals(checkedRemarks,report.path("checklistResults").get(0).path("remarks").asText());
        assertEquals("not_applicable",report.path("checklistResults").get(1).path("status").asText());
        assertEquals(excludedRemarks,report.path("checklistResults").get(1).path("remarks").asText());
        assertEquals("unchecked",report.path("checklistResults").get(2).path("status").asText());
        String tableJson=report.path("checklistTables").toString();
        assertTrue(tableJson.contains("Checked and fulfilled"));
        assertTrue(tableJson.contains("Not applicable"));
        assertTrue(tableJson.contains("Not checked (unknown)"));
        assertTrue(tableJson.contains("Checked and fulfilled \u2713"));
        assertTrue(tableJson.contains("Checked and not fulfilled \u2717"));
        assertTrue(tableJson.contains(unfulfilledRemarks));
        assertTrue(tableJson.contains(checkedRemarks));assertTrue(tableJson.contains(excludedRemarks));
        try(XWPFDocument docx=new XWPFDocument(new ByteArrayInputStream(download(run,"docx")))) {
            String tableText=docx.getTables().stream().flatMap(t->t.getRows().stream()).flatMap(r->r.getTableCells().stream())
                    .map(c->c.getText()).reduce("",(a,b)->a+"\n"+b);
            assertTrue(tableText.contains(checkedRemarks));assertTrue(tableText.contains(excludedRemarks));
            assertTrue(tableText.contains("Checked and fulfilled"));
            assertTrue(tableText.contains("Not checked (unknown)"));
            assertTrue(tableText.contains("Checked and not fulfilled \u2717"));
            assertTrue(tableText.contains(unfulfilledRemarks));
        }
        try(PDDocument pdf=PDDocument.load(download(run,"pdf"))) {
            String text=new PDFTextStripper().getText(pdf).replaceAll("\\s+"," ");
            assertTrue(text.contains(checkedRemarks));assertTrue(text.contains(excludedRemarks));
            assertTrue(text.contains("Checked and fulfilled"));
            assertTrue(text.contains("Not checked (unknown)"));
            assertTrue(text.contains("Checked and not fulfilled"));
            assertTrue(text.contains("\u2713"));assertTrue(text.contains("\u2717"));
            assertTrue(text.contains(unfulfilledRemarks));
        }
    }

    @Test void samplePricingCounterchecksRemainVisibleWithoutExpandingCompetitionScope() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        JsonNode checklist=read(run).path("items");
        assertEquals(69,checklist.size());
        assertEquals("pricing_document_excluded",checklist.get(57).path("reviewScope").asText());
        assertTrue(checklist.get(57).path("scopeNote").asText().contains("outside the competition scope"));
        assertEquals(checklist.get(57).path("scopeNote").asText(),checklist.get(57).path("scopeNoteText").path("en").asText());
        assertFalse(checklist.get(57).path("scopeNoteText").path("zhHans").asText().isEmpty());
        assertFalse(checklist.get(57).path("scopeNoteText").path("zhHant").asText().isEmpty());
        assertEquals("unchecked",checklist.get(57).path("status").asText());
        assertEquals("mixed_contract_pricing",checklist.get(54).path("reviewScope").asText());
        assertEquals("in_scope",checklist.get(0).path("reviewScope").asText());
        JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        assertTrue(report.path("checklistTables").toString().contains("Outside scope: pricing-document countercheck."));
        assertTrue(report.path("checklistTables").toString().contains("PRE / contract wording only. Pricing excluded."));
    }


    @Test void jsonReportQualifiesUnknownClauseOwnersWithoutChangingStoredEvidenceOrHumanState() throws Exception {
        VettingRun run=completedRun("{\"total\":1}");
        com.consense.domain.VettingFinding finding=new com.consense.domain.VettingFinding();
        finding.setProjectId(run.getProjectId());finding.setRunId(run.getId());finding.setCode("VT-JSON-READER");
        finding.setGroupKey("conflict");finding.setScope("intra");finding.setFileKey("SCT");finding.setSeverity("high");
        finding.setStatus("Open");finding.setSource("model");finding.setTitleEn("Authored candidate naming Clause 1 and Clause 2");
        finding.setBodyEn("Authored candidate: Clause 1 and Clause 2 require source review.");
        com.consense.web.dto.VettingDtos.FindingEvidence evidence=new com.consense.web.dto.VettingDtos.FindingEvidence(
                "source",null,"SCT","AUTHORED-REPORT-FIXTURE.docx",null,"body/4/paragraph","AUTHORED full source condition QUOTE-END",true,"authored-source-hash",null);
        evidence.setPacketId("AUTHORED-REPORT-PACKET-NO-PROVIDER");evidence.setPacketSourceSnapshotSha256("AUTHORED-PACKET-SNAPSHOT");
        finding.setEvidenceJson(JsonUtils.write(Collections.singletonList(evidence)));findings.saveAndFlush(finding);
        String originalEvidence=finding.getEvidenceJson(),coverage=run.getCoverageJson(),result=run.getResultJson();
        JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        JsonNode row=report.path("commentsTables").get(0).path("rows").get(0);
        assertTrue(row.get(3).asText().contains("formal clause numbers stated below are unconfirmed"),"JSON_UNKNOWN_OWNER_QUALIFICATION");
        assertTrue(row.get(2).asText().contains("Body paragraph 4 (formal clause ownership unconfirmed)"));
        assertFalse(row.get(2).asText().contains("Clause 4"));assertEquals("Not located",row.get(1).asText());
        assertTrue(row.get(3).asText().contains(finding.getBodyEn()));
        assertEquals("Review status: Awaiting project-team review",row.get(4).asText());
        assertTrue(report.path("evidenceIndex").path("rows").get(0).get(2).asText().contains("formal clause ownership unconfirmed"),"JSON_INDEX_OWNER_QUALIFICATION");
        JsonNode raw=report.path("findings").get(0);
        assertEquals(JsonUtils.parse(originalEvidence),raw.path("evidence"));assertEquals("Open",raw.path("status").asText());
        assertTrue(raw.path("reviewRemarks").isNull());assertTrue(raw.path("actionTaken").isNull());assertTrue(raw.path("addendumRequired").isNull());
        assertEquals("body/4/paragraph",raw.path("evidence").get(0).path("anchor").asText());
        assertEquals("AUTHORED-REPORT-PACKET-NO-PROVIDER",raw.path("evidence").get(0).path("packetId").asText());
        assertEquals(originalEvidence,findings.findById(finding.getId()).orElseThrow(AssertionError::new).getEvidenceJson());
        VettingRun stored=runs.findById(run.getId()).orElseThrow(AssertionError::new);assertEquals(coverage,stored.getCoverageJson());assertEquals(result,stored.getResultJson());
        assertEquals(69,report.path("checklistResults").size());for(JsonNode human:report.path("checklistResults")){assertEquals("unchecked",human.path("status").asText());assertEquals("",human.path("remarks").asText());}
    }

    @Test void jsonReportKeepsExcludedPricingRowsExplicitlyOutsideAutomatedScopeWithoutHumanConfirmation() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        String tables=report.path("checklistTables").toString();
        assertFalse(tables.contains("Unknown status: out_of_scope"),"JSON_KNOWN_EXCLUDED_SCOPE_IS_NOT_UNKNOWN_STATUS");
        int outside=0,rows=0;
        for(JsonNode table:report.path("checklistTables"))for(JsonNode row:table.path("rows")) {
            String remarks=row.get(2).asText();assertTrue(remarks.contains("Not checked (unknown)"));assertFalse(remarks.contains("Checked and fulfilled"));
            if(remarks.contains("Outside automated review scope; no automatic countercheck performed")){outside++;assertTrue(remarks.contains("Outside scope: pricing-document countercheck."));}rows++;
        }
        assertEquals(69,rows);assertEquals(12,outside,"All excluded source rows remain visible with their actual scope");
        for(JsonNode human:report.path("checklistResults")){assertEquals("unchecked",human.path("status").asText());assertEquals("",human.path("remarks").asText());}
        int rawOutside=0;for(JsonNode group:report.path("automaticChecklistResults"))for(JsonNode item:group.path("subItemAssessments"))if("out_of_scope".equals(item.path("status").asText()))rawOutside++;
        assertEquals(12,rawOutside,"The raw automatic audit retains its original out_of_scope status code");
    }

    private byte[] download(VettingRun run,String format) throws Exception {
        return mvc.perform(get("/api/vetting/"+run.getProjectId()+"/report."+format).param("lang","zh-CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private org.springframework.test.web.servlet.ResultActions save(VettingRun run,String binding,java.util.List<Map<String,Object>> items) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("runSnapshotSha256",binding);body.put("items",items);body.put("expectedRunId",run.getId());body.put("expectedSourceSnapshotSha256",run.getSourceSnapshotSha256());
        return mvc.perform(put(url(run)).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andExpect(status().isOk());
    }
    private Map<String,Object> row(String id,String state,String remarks) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("status",state);row.put("remarks",remarks);return row;
    }

    private JsonNode read(VettingRun run) throws Exception {
        String response=mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonUtils.mapper().readTree(response).path("data");
    }

    private VettingRun completedRun(String result) {
        Project p=new Project();p.setId("checklist-"+UUID.randomUUID());p.setNameEn("Checklist workflow fixture");p.setContractNo("TEST");
        projects.saveAndFlush(p);
        return newRun(p.getId(),result);
    }
    private VettingRun newRun(String projectId,String result) {
        VettingRun run=new VettingRun();run.setId(UUID.randomUUID().toString());run.setProjectId(projectId);
        run.setStatus("COMPLETED");run.setStartedAt(Instant.now());run.setFinishedAt(Instant.now());
        // Synthetic completed runs bind v2 coverage and candidate-structure source dimensions.
        run.setCoverageJson("{\"documents\":[]}");run.setSourceSnapshotSha256(com.consense.service.vetting.VettingCorpus.hash("competition-review-package-v2\n[]"));run.setResultJson(result);runs.saveAndFlush(run);return run;
    }
    private String url(VettingRun run) {return "/api/vetting/"+run.getProjectId()+"/runs/"+run.getId()+"/checklist";}
}
