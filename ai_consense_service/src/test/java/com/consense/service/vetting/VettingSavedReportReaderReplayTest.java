package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.*;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only saved-artifact replay, separate from the authored HTTP export tests and source/legal acceptance. */
@EnabledIfSystemProperty(named="consense.vetting.savedReportFixtureDirectory",matches=".+")
class VettingSavedReportReaderReplayTest {
    private Path fixture(String name,String expectedSha) throws Exception {
        Path path=Paths.get(System.getProperty("consense.vetting.savedReportFixtureDirectory")).resolve(name);
        byte[] bytes=Files.readAllBytes(path);StringBuilder hash=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",value&255));
        assertEquals(expectedSha,hash.toString(),"Explicit saved fixture must be the frozen runtime19 artifact");return path;
    }
    private JsonNode saved() throws Exception {
        JsonNode value=JsonUtils.mapper().readTree(Files.readAllBytes(fixture("vetting-saved-report-json.bin","32c7374be807fba2f4ba4a8c57e9c6b01d64f9115f326d36f3cc5419b92e21e7")));
        assertEquals("61e6ddf7556a77e9ec267b82668fca1559c4a4448c1a6f0f1ea8d3df4238448f",value.path("reportSnapshotId").asText());
        assertEquals(16,value.path("findings").size());assertEquals(69,value.path("checklistResults").size());return value;
    }
    private XWPFDocument word() throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(fixture("vetting-saved-report-docx.bin","daf2f2a52f6dd5182f57bad15079653538f7e275ec8f54149811968e655483f9"))));
    }
    private String compact(String value){return value.replaceAll("\\s+","");}

    @Test void savedSixteenCandidatesKeepUnknownOwnersAllRawPointersAndTheActualWordReaderMeaning() throws Exception {
        JsonNode saved=saved();List<FindingVO> findings=JsonUtils.readList(saved.path("findings").toString(),FindingVO.class);
        VettingJobVO job=JsonUtils.read(saved.path("job").toString(),VettingJobVO.class);
        String before=JsonUtils.write(findings),jobBefore=JsonUtils.write(job);
        List<VettingReportPresentation.Table> comments=VettingReportPresentation.readerComments(findings,job.getCoverage());
        assertEquals(1,comments.size());assertEquals(17,comments.get(0).getRows().size(),"Sixteen candidates plus the separately labelled scope summary");
        VettingReportPresentation.Table index=VettingReportPresentation.readerEvidenceIndex(findings,job.getCoverage());
        assertEquals(16,index.getRows().size());int evidenceCount=0;
        try(XWPFDocument word=word()) {
            XWPFTable table=word.getTables().get(1);
            for(int i=0;i<findings.size();i++) {
                FindingVO finding=findings.get(i);List<String> row=comments.get(0).getRows().get(i);
                assertEquals("3."+(i+1),row.get(0));assertEquals("Not located",row.get(1));
                assertTrue(row.get(2).contains("formal clause ownership unconfirmed"));
                assertTrue(row.get(3).contains("formal clause numbers stated below are unconfirmed"));
                assertTrue(index.getRows().get(i).get(2).contains("formal clause ownership unconfirmed"));
                assertFalse(row.get(2).contains("Clause 1"));assertFalse(row.get(2).contains("Clause 2"));
                assertEquals("Review status: Awaiting project-team review",row.get(4));
                for(int cell=0;cell<5;cell++)assertEquals(compact(table.getRow(i+2).getCell(cell).getText()),compact(row.get(cell)),finding.getCode()+" cell "+cell);
                for(String literal:Arrays.asList(finding.getTitle().pick("en"),finding.getBody().pick("en"),finding.getImpact().pick("en"),finding.getSuggestion().pick("en")))assertTrue(row.get(3).contains(literal),finding.getCode());
                assertFalse(row.get(3).contains("canonical:"));assertFalse(row.get(3).contains("body/"));
                String originalEvidence=saved.path("findings").get(i).path("evidence").toString(),replayedEvidence=JsonUtils.write(finding.getEvidence());
                assertEquals(originalEvidence,replayedEvidence,"Complete raw evidence serialization, object fields and array order, including all canonical native pointers");
                assertEquals(JsonUtils.parse(originalEvidence),JsonUtils.parse(replayedEvidence),"Complete raw evidence values after the same JSON parser");
                for(int j=0;j<finding.getEvidence().size();j++) {
                    FindingEvidence evidence=finding.getEvidence().get(j);JsonNode raw=saved.path("findings").get(i).path("evidence").get(j);evidenceCount++;
                    assertNull(evidence.getClauseId());assertNull(evidence.getPageNo());assertTrue(evidence.isLocated());
                    assertFalse(raw.path("canonicalEvidence").path("parts").isEmpty());
                    for(JsonNode part:raw.path("canonicalEvidence").path("parts")) {
                        assertEquals("unknown",part.path("readingContext").path("ownerStatus").asText());
                        assertEquals("word/document.xml",part.path("sourcePosition").path("partName").asText());
                        assertFalse(part.path("sourcePosition").path("nodePath").asText().isEmpty());
                        assertEquals("121fe9d141e684decffbbff870a62203146047f2b1be223d317483ae96821b9f",part.path("sourcePosition").path("sourceSha256").asText());
                    }
                }
            }
        }
        assertEquals(32,evidenceCount);assertEquals(before,JsonUtils.write(findings));assertEquals(jobBefore,JsonUtils.write(job));
    }

    @Test void savedSixtyNineHumanRecordsAndTwelveExcludedScopeRowsMatchActualWordWithoutPromotingAnyDecision() throws Exception {
        JsonNode saved=saved();List<VettingChecklistCatalog.Result> human=JsonUtils.readList(saved.path("checklistResults").toString(),VettingChecklistCatalog.Result.class);
        List<AutomaticGroupVO> automatic=JsonUtils.readList(saved.path("automaticChecklistResults").toString(),AutomaticGroupVO.class);
        String humanBefore=JsonUtils.write(human),automaticBefore=JsonUtils.write(automatic);
        List<VettingReportPresentation.Table> reader=VettingReportPresentation.readerChecklist(human,automatic);
        assertEquals(17,reader.size());int rows=0,outside=0;
        try(XWPFDocument word=word()) {
            for(int t=0;t<reader.size();t++) {
                XWPFTable table=word.getTables().get(t+2);
                for(int r=0;r<reader.get(t).getRows().size();r++) {
                    List<String> row=reader.get(t).getRows().get(r);
                    for(int c=0;c<3;c++)assertEquals(compact(table.getRow(r+1).getCell(c).getText()),compact(row.get(c)),"Checklist table "+t+" row "+r+" cell "+c);
                    assertTrue(row.get(2).contains("Not checked (unknown)"));
                    assertFalse(row.get(2).contains("Unknown status: out_of_scope"));
                    assertFalse(row.get(2).contains("Checked and fulfilled"));
                    if(row.get(2).contains("Outside automated review scope; no automatic countercheck performed"))outside++;
                    assertEquals("unchecked",human.get(rows).getStatus());assertEquals("",human.get(rows).getRemarks());rows++;
                }
            }
        }
        assertEquals(69,rows);assertEquals(12,outside);
        assertEquals(humanBefore,JsonUtils.write(human));assertEquals(automaticBefore,JsonUtils.write(automatic));
        assertEquals(saved.path("checklistResults"),JsonUtils.mapper().valueToTree(human));
        assertEquals(saved.path("automaticChecklistResults"),JsonUtils.mapper().valueToTree(automatic));
    }
}
