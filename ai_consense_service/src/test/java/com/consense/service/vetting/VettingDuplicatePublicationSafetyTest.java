package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prepared only: the real Service.run publication seam, no new product abstraction. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingDuplicatePublicationSafetyTest {
    private static final String ID=UUID.randomUUID().toString();
    private static final String TEXT="Clause 1: The Tenderer shall submit exactly two signed paper copies of the tender at 12:00 noon on 30 November 2026.\n"
            +"Clause 2: The Tenderer shall submit exactly three signed paper copies of the same tender at 14:00 on 30 November 2026.";
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:duplicate_publication_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target/duplicate-publication-uploads",ID).toAbsolutePath().toString());}
    @Autowired VettingService service;@Autowired ConsenseProperties props;
    @Autowired ProjectRepository projects;@Autowired SourceDocumentRepository documents;
    @Autowired VettingFindingRepository persistedFindings;
    @MockBean VettingSemanticReview semantic;
    @BeforeEach void settings(){props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);}

    @Test void samePacketAndSameQuotesDoNotEraseDifferentQuantityAndFixedTimeClaims() {
        SourceDocument source=source();Candidate quantity=candidate(source,"same","Quantity mismatch","The same tender event requires exactly two versus exactly three signed paper copies.");
        Candidate time=candidate(source,"same","Appointment time mismatch","The same tender event is fixed at 12:00 noon versus 14:00 on the same date.");
        assertEquals(VettingService.fingerprint(quantity),VettingService.fingerprint(time),"Precondition: identical raw fingerprint metadata, distinct actual reported claims");
        when(semantic.reviewWithProgress(isNull(),eq(source.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(quantity,time));
        assertEquals(2,service.run(source.getProjectId(),"en").getTotal(),"PUBLICATION_DISTINCT_REPORTED_CLAIMS_MUST_SURVIVE_RAW_FINGERPRINT_COLLISION");
        List<FindingVO> rows=service.listFindings(source.getProjectId(),null,null,null,null,null);
        assertEquals(2,rows.size());assertEquals(new HashSet<>(Arrays.asList(quantity.getComment(),time.getComment())),new HashSet<>(Arrays.asList(rows.get(0).getBody().getEn(),rows.get(1).getBody().getEn())));
        assertEquals(rows.get(0).getFingerprint(),rows.get(1).getFingerprint());assertEquals(64,rows.get(0).getFingerprint().length());assertNotEquals(rows.get(0).getGeneratedRevisionSha256(),rows.get(1).getGeneratedRevisionSha256());
    }

    @Test void separatePacketsRemainSeparateRawAuditMembersEvenForTheSameReportedProblem() {
        SourceDocument source=source();Candidate a=candidate(source,"A","Quantity mismatch","The same tender event requires exactly two versus exactly three copies.");
        Candidate b=candidate(source,"B",a.getTitle(),a.getComment());assertNotEquals(VettingService.fingerprint(a),VettingService.fingerprint(b));
        when(semantic.reviewWithProgress(isNull(),eq(source.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(a,b));
        assertEquals(2,service.run(source.getProjectId(),"en").getTotal());List<FindingVO> rows=service.listFindings(source.getProjectId(),null,null,null,null,null);assertEquals(2,rows.size());
        Set<String> packets=new HashSet<>();for(FindingVO row:rows)packets.add(row.getEvidence().get(0).getPacketId());assertEquals(new HashSet<>(Arrays.asList(a.getPacketId(),b.getPacketId())),packets);
    }

    @Test void anExactRepeatedCandidateInTheSamePacketRemainsIdempotent() {
        SourceDocument source=source();Candidate a=candidate(source,"same","Quantity mismatch","The same tender event requires exactly two versus exactly three copies.");
        Candidate same=JsonUtils.read(JsonUtils.write(a),Candidate.class);
        when(semantic.reviewWithProgress(isNull(),eq(source.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(a,same));
        assertEquals(1,service.run(source.getProjectId(),"en").getTotal());
    }

    @Test void sameFingerprintKeepsHumanDecisionsSeparateByExactGeneratedRevision() {
        SourceDocument source=source();Candidate quantity=candidate(source,"same","Quantity mismatch","Two versus three copies require review.");
        Candidate time=candidate(source,"same","Fixed time mismatch","12:00 versus 14:00 requires separate review.");
        when(semantic.reviewWithProgress(isNull(),eq(source.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(quantity,time));
        assertEquals(2,service.run(source.getProjectId(),"en").getTotal(),"PUBLICATION_DISTINCT_REPORTED_CLAIMS_MUST_SURVIVE_RAW_FINGERPRINT_COLLISION");
        List<FindingVO> first=service.listFindings(source.getProjectId(),null,null,null,null,null);
        FindingVO q=first.stream().filter(row->quantity.getTitle().equals(row.getTitle().getEn())).findFirst().orElseThrow(AssertionError::new);
        FindingVO t=first.stream().filter(row->time.getTitle().equals(row.getTitle().getEn())).findFirst().orElseThrow(AssertionError::new);
        VettingHumanReviewFixture.updateStatus(service,source.getProjectId(),q.getCode(),"Handled");
        VettingHumanReviewFixture.updateReview(service,source.getProjectId(),q.getCode(),new ReviewUpdateRequest("Quantity decision only","Clarify copy count",true));
        VettingHumanReviewFixture.updateStatus(service,source.getProjectId(),t.getCode(),"Assigned");
        VettingHumanReviewFixture.updateReview(service,source.getProjectId(),t.getCode(),new ReviewUpdateRequest("Time decision only","Ask team about fixed time",null));
        assertEquals(2,service.run(source.getProjectId(),"en").getTotal());
        List<FindingVO> same=service.listFindings(source.getProjectId(),null,null,null,null,null);
        FindingVO sameQ=same.stream().filter(row->q.getCode().equals(row.getCode())).findFirst().orElseThrow(AssertionError::new);
        FindingVO sameT=same.stream().filter(row->t.getCode().equals(row.getCode())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Handled",sameQ.getStatus());assertEquals("Quantity decision only",sameQ.getReviewRemarks());assertEquals(Boolean.TRUE,sameQ.getAddendumRequired());
        assertEquals("Assigned",sameT.getStatus());assertEquals("Time decision only",sameT.getReviewRemarks());assertNull(sameT.getAddendumRequired());
        quantity.setComment(quantity.getComment()+" Clarify the quantity wording.");assertEquals(VettingService.fingerprint(quantity),VettingService.fingerprint(time));
        assertEquals(2,service.run(source.getProjectId(),"en").getTotal());
        List<FindingVO> changed=service.listFindings(source.getProjectId(),null,null,null,null,null);
        FindingVO changedQ=changed.stream().filter(row->quantity.getTitle().equals(row.getTitle().getEn())).findFirst().orElseThrow(AssertionError::new);
        FindingVO unchangedT=changed.stream().filter(row->t.getCode().equals(row.getCode())).findFirst().orElseThrow(AssertionError::new);
        assertNotEquals(q.getCode(),changedQ.getCode());assertEquals("Open",changedQ.getStatus());assertNull(changedQ.getReviewRemarks());assertNull(changedQ.getActionTaken());assertNull(changedQ.getAddendumRequired());
        assertEquals("Assigned",unchangedT.getStatus());assertEquals("Time decision only",unchangedT.getReviewRemarks());
        VettingFinding history=persistedFindings.findByProjectIdAndCode(source.getProjectId(),q.getCode()).orElseThrow(AssertionError::new);
        assertFalse(history.getActive());assertEquals("Handled",history.getStatus());assertEquals("Quantity decision only",history.getReviewRemarks());
    }

    private SourceDocument source(){
        Project project=new Project();project.setId("duplicate-safety-"+UUID.randomUUID());project.setNameEn("Synthetic publication safety");project.setContractNo("TEST");projects.saveAndFlush(project);
        DocumentBlock block=new DocumentBlock();block.setId("fixture-block");block.setKind("paragraph");block.setLocation("body/fixture");block.setSource("fixture_saved_text");block.setText(TEXT);
        SourceDocument source=new SourceDocument();source.setProjectId(project.getId());source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setFileKey("SCT");source.setFileName("publication-fixture.txt");source.setParseStatus("PARSED");source.setTextContent(TEXT);source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));return documents.saveAndFlush(source);
    }
    private Candidate candidate(SourceDocument source,String packetVariant,String title,String comment){
        String snapshot=VettingCorpus.hash("synthetic-publication-scope:"+packetVariant),packet="topic-1-packet-0-source-"+snapshot;
        Candidate candidate=new Candidate();candidate.setRuleId("semantic-conflict");candidate.setType("conflict");candidate.setSource("model");candidate.setSeverity("high");candidate.setTitle(title);candidate.setComment(comment);candidate.setImpact("Manual review required.");candidate.setSuggestion("Review the two source instructions independently.");
        candidate.setBusinessDetails(new BusinessDetailsVO("Submission requirements","The quoted clauses set mandatory submission quantities and fixed times.",Collections.emptyList(),"review"));candidate.setPromptSupplementSha256(VettingPromptPolicy.supplementSha256(""));candidate.setPacketId(packet);candidate.setSourceSnapshotSha256(snapshot);
        FindingEvidence evidence=new FindingEvidence("source",String.valueOf(source.getId()),source.getFileKey(),source.getFileName(),null,"body/fixture",TEXT,true,VettingCorpus.sourceHash(source),null);evidence.setPacketId(packet);evidence.setPacketSourceSnapshotSha256(snapshot);candidate.setEvidence(Collections.singletonList(evidence));return candidate;
    }
    private Result result(Candidate... candidates){Result result=new Result();result.setModel("synthetic-publication-safety");result.getCandidates().addAll(Arrays.asList(candidates));return result;}
}
