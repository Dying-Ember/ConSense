package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Exact source containers preserve independent candidate claims; no legal equivalence assertion. */
class VettingSourceCandidateContainerTest {
    private static final String SOURCE=VettingCorpus.hash("source"),ORIGINAL=VettingCorpus.hash("original"),REVISION=VettingCorpus.hash("revision");

    @Test void sameSourceContainerDoesNotMergeQuantityAndFixedTimeClaimsOrHumanDecisions() {
        FindingVO quantity=finding("VT-001","Exactly two versus exactly three copies.");FindingVO time=copy(quantity);time.setCode("VT-002");time.setBody(LocalizedText.same("12:00 versus 14:00 on the same date."));time.setStatus("Assigned");time.setReviewRemarks("Time question only");time.setAddendumRequired(false);
        List<FindingVO> rows=Arrays.asList(quantity,time);VettingService.annotateSourceCandidateContainers(rows);
        assertEquals(2,rows.size());assertNotEquals(quantity.getBody().getEn(),time.getBody().getEn());assertEquals(quantity.getSourceCandidateContainer().getId(),time.getSourceCandidateContainer().getId());
        quantity.setStatus("Handled");quantity.setReviewRemarks("Quantity question only");quantity.setAddendumRequired(true);
        assertEquals("Assigned",time.getStatus());assertEquals("Time question only",time.getReviewRemarks());assertEquals(Boolean.FALSE,time.getAddendumRequired());assertNull(time.getSourceCandidateContainer().getLegalProblemCount());
    }

    @Test void eachCompleteSourceOrRunIdentityChangePreventsContainerReuse() {
        FindingVO original=finding("VT-001","Quantity");VettingService.annotateSourceCandidateContainers(Collections.singletonList(original));String old=original.getSourceCandidateContainer().getId();
        List<Consumer<FindingVO>> changes=Arrays.asList(r->r.setRunId("another-run"),r->r.setSourceSnapshotSha256(VettingCorpus.hash("another-material")),
                r->r.getEvidence().get(0).setAnchor("body/other"),r->r.getEvidence().get(0).setQuote("Different exact quote"),
                r->r.getEvidence().get(0).setSourceHash(VettingCorpus.hash("other-source")),r->r.getEvidence().get(0).setOriginalSourceSha256(VettingCorpus.hash("other-original")),
                r->r.getEvidence().get(0).getCanonicalEvidence().setSourceRevisionSha256(VettingCorpus.hash("other-revision")));
        for(Consumer<FindingVO> change:changes){FindingVO changed=copy(original);change.accept(changed);VettingService.annotateSourceCandidateContainers(Collections.singletonList(changed));assertTrue(changed.getSourceCandidateContainer()==null||!old.equals(changed.getSourceCandidateContainer().getId()));}
    }

    @Test void missingUnlocatedOrMismatchedSourceEvidenceNeverSharesAnEmptyContainer() {
        FindingVO missing=finding("VT-001","Missing original binding");missing.getEvidence().get(0).setOriginalSourceSha256(null);
        FindingVO unlocated=finding("VT-002","Unlocated");unlocated.getEvidence().get(0).setLocated(false);
        FindingVO empty=finding("VT-003","Empty");empty.setEvidence(Collections.emptyList());
        FindingVO mismatch=finding("VT-004","Canonical mismatch");mismatch.getEvidence().get(0).getCanonicalEvidence().setDocumentId(99L);
        for(FindingVO row:VettingService.annotateSourceCandidateContainers(Arrays.asList(missing,unlocated,empty,mismatch)))assertNull(row.getSourceCandidateContainer());
    }

    @Test void packetAndClaimDifferencesStayInMembersWithoutBecomingSourceOrIssueIdentity() {
        FindingVO first=finding("VT-001","Quantity");FindingVO second=copy(first);second.setCode("VT-002");second.setBody(LocalizedText.same("Separate fixed time concern"));second.getEvidence().get(0).setPacketId("different-packet");second.getEvidence().get(0).setPacketSourceSnapshotSha256(VettingCorpus.hash("different-packet-scope"));
        second.setBusinessDetails(new BusinessDetailsVO("Different free prose","Different stated fact basis",Collections.singletonList("Human confirmation needed"),"review",Collections.singletonList("APP-I-008")));
        VettingService.annotateSourceCandidateContainers(Arrays.asList(first,second));assertEquals(first.getSourceCandidateContainer().getId(),second.getSourceCandidateContainer().getId());
        assertEquals("different-packet",second.getEvidence().get(0).getPacketId());assertEquals(Collections.singletonList("APP-I-008"),second.getBusinessDetails().getChecklistItemIds());assertNull(first.getBusinessDetails());assertNull(first.getSourceCandidateContainer().getLegalProblemCount());
    }

    @Test void metadataSurvivesReportSnapshotRoundTripAndLegalNullSurvivesHttpNonNull() throws Exception {
        FindingVO legacy=finding("VT-OLD","Legacy snapshot without grouping metadata");String oldJson=JsonUtils.write(legacy);
        assertFalse(oldJson.contains("sourceCandidateContainer"));assertNull(JsonUtils.read(oldJson,FindingVO.class).getSourceCandidateContainer(),"Reading an old snapshot must not infer or backfill grouping");
        List<FindingVO> rows=Arrays.asList(finding("VT-001","Quantity"),finding("VT-002","Time"));VettingService.annotateSourceCandidateContainers(rows);
        List<FindingVO> saved=JsonUtils.readList(JsonUtils.write(rows),FindingVO.class);assertNotNull(saved.get(0).getSourceCandidateContainer());assertEquals(rows.get(0).getSourceCandidateContainer(),saved.get(0).getSourceCandidateContainer());
        JsonNode http=JsonUtils.mapper().copy().setSerializationInclusion(JsonInclude.Include.NON_NULL).valueToTree(saved);
        assertTrue(http.get(0).path("sourceCandidateContainer").has("legalProblemCount"));assertTrue(http.get(0).path("sourceCandidateContainer").path("legalProblemCount").isNull());
    }

    @Test void reportRetainsEveryCommentHumanRowAndEvidenceIndexWithoutRepresentativeRows() {
        FindingVO first=finding("VT-001","Quantity claim");first.setReviewRemarks("Quantity team note");first.setActionTaken("Retain separate decision");first.setAddendumRequired(true);
        FindingVO second=finding("VT-002","Fixed time claim");second.setReviewRemarks("Time team note");second.setAddendumRequired(false);
        List<FindingVO> rows=VettingService.annotateSourceCandidateContainers(Arrays.asList(first,second));List<VettingReportPresentation.Table> tables=VettingReportPresentation.comments(rows,null);
        assertEquals(1,tables.size());assertEquals(5,tables.get(0).getHeadings().size());assertEquals(2,tables.get(0).getRows().size());assertEquals(Arrays.asList("VT-001","VT-002"),tables.get(0).getFindingCodes());
        String rendered=tables.get(0).getRows().toString();for(String value:Arrays.asList("Quantity claim","Fixed time claim","Quantity team note","Time team note","Required","Not required","issue equivalence is unconfirmed","Legal problem count: undetermined"))assertTrue(rendered.contains(value),value);
        assertEquals(2,VettingReportPresentation.evidenceIndex(rows,null).getRows().size());
        DocumentCoverageVO document=new DocumentCoverageVO();document.setDocumentId("6");document.setFileKey("SCT");document.setFileName("source.docx");document.setParseStatus("PARSED");document.setTotalSegments(1);document.setReviewedSegments(1);document.setTextChars(100);document.setReviewedChars(100);
        CoverageVO coverage=new CoverageVO();coverage.setDocuments(Collections.singletonList(document));VettingReportPresentation.SectionSummary summary=VettingReportPresentation.sectionSummaries(rows,coverage).get(0);
        assertEquals(2,summary.getFindingCount());assertTrue(summary.getSummary().contains("Raw candidate records: 2"));assertTrue(summary.getSummary().contains("Recorded exact-source candidate containers: 1"));assertTrue(summary.getSummary().contains("Legal problem count: undetermined"));
    }

    private FindingVO finding(String code,String body) {
        FindingVO row=new FindingVO();row.setCode(code);row.setRunId("same-run");row.setSourceSnapshotSha256(VettingCorpus.hash("material"));row.setGroup("conflict");row.setTypes(Collections.singletonList("f2-viii"));row.setStatus("Open");row.setTitle(LocalizedText.same(body));row.setBody(LocalizedText.same(body));row.setFingerprint(VettingCorpus.hash(code));row.setGeneratedRevisionSha256(VettingCorpus.hash("revision:"+code));
        FindingEvidence evidence=new FindingEvidence("source","6","SCT","source.docx",null,"body/4/paragraph","Exactly two versus exactly three copies, at 12:00 versus 14:00.",true,SOURCE,null);evidence.setOriginalSourceSha256(ORIGINAL);evidence.setPacketId("packet-"+code);evidence.setPacketSourceSnapshotSha256(VettingCorpus.hash("packet:"+code));
        EvidencePointer canonical=new EvidencePointer();canonical.setDocumentId(6L);canonical.setOriginalSourceSha256(ORIGINAL);canonical.setJavaParsedSourceHash(SOURCE);canonical.setSourceRevisionSha256(REVISION);evidence.setCanonicalEvidence(canonical);row.setEvidence(Collections.singletonList(evidence));return row;
    }
    private FindingVO copy(FindingVO row){return JsonUtils.read(JsonUtils.write(row),FindingVO.class);}
}
