package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.domain.VettingFinding;
import com.consense.repository.VettingFindingRepository;
import com.consense.service.ProjectService;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingSemanticReview.Candidate;
import com.consense.web.dto.VettingDtos.EvidenceItemVO;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ordinary authored conversion and detail seams; no original-file or business-acceptance claim. */
class VettingRuleEvidenceLocationTest {
    @Test void aLogicalClauseDoesNotReplaceTheNativeAnchorNeededForExactCanonicalBinding() throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();
        OerBusinessEvidence.Session session=wire.load();Chunk chunk=session.chunks().get(0);Part part=chunk.getParts().get(0);
        VettingRuleEngine.Evidence raw=new VettingRuleEngine.Evidence(chunk.getDocumentId(),chunk.getFileKey(),chunk.getFileName(),
                wire.binding.getJavaParsedSourceHash(),part.getBlockId(),part.getAnchor(),null,"SCC 99.999",part.getText(),null);
        Candidate candidate=convert(output(raw),Collections.emptyMap());FindingEvidence quote=candidate.getEvidence().get(0);
        assertEquals(part.getAnchor(),quote.getAnchor());assertEquals("SCC 99.999",quote.getClauseId());
        wire.service.bindRuleEvidence(session,candidate.getEvidence());
        assertTrue(quote.isLocated());assertNotNull(quote.getCanonicalEvidence());
        assertEquals(chunk.getId(),quote.getCanonicalEvidence().getCanonicalChunkId());
        assertNull(quote.getPacketId());assertNull(quote.getPacketSourceSnapshotSha256());
    }

    @Test void packageIdentityOutputAlreadyUsesItsNativeLocationAndDoesNotInventAClauseLabel() {
        DocumentBlock block=new DocumentBlock();block.setId("identity-block");block.setLocation("native/identity-block");block.setText("Contract No: PKG-002");
        ObjectNode reading=JsonUtils.mapper().createObjectNode();ObjectNode context=reading.putObject(VettingPackageIdentity.CONTEXT);
        context.put("status","source_bound");context.put("role","tender");context.put("selectedStartsAtSourceLineBoundary",true);context.put("selectedEndsAtSourceLineBoundary",true);block.setReadingContext(reading);
        VettingRuleEngine.Source source=new VettingRuleEngine.Source("42","NTT","identity.docx","parsed",Collections.singletonList(block),block.getText());
        ObjectNode authority=JsonUtils.mapper().createObjectNode();authority.putObject("core").putObject("fields").putObject("number").put("eligible",true).put("value","PKG-001");
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source),authority);
        assertEquals(1,review.outputs().size());VettingRuleEngine.Evidence raw=review.outputs().get(0).getEvidence().get(0);
        assertEquals(raw.getLocation(),raw.getAnchor(),"The identity path has no logical/native-anchor mismatch");
        FindingEvidence quote=convert(review.outputs().get(0),Collections.emptyMap()).getEvidence().get(0);
        assertEquals(block.getLocation(),quote.getAnchor());assertNull(quote.getClauseId());assertEquals(block.getText(),quote.getQuote());
        assertNull(quote.getPacketId());assertNull(quote.getCanonicalEvidence(),"Conversion alone never establishes canonical binding");
    }

    @Test void savedEvidenceDetailRetainsReadableClauseAndOriginalIdentityWithoutPromotingLocation() {
        FindingEvidence quote=new FindingEvidence("source","42","SCC","source.docx",null,"native/paragraph","Exact saved source text.",false,"parsed",null);
        quote.setClauseId("SCC 4.301");quote.setOriginalSourceSha256(VettingCorpus.hash("original"));quote.setSourceRole("tender");
        VettingFinding finding=new VettingFinding();finding.setCode("F1");finding.setEvidenceJson(JsonUtils.write(Collections.singletonList(quote)));
        VettingFindingRepository findings=mock(VettingFindingRepository.class);when(findings.findByProjectIdAndCode("project","F1")).thenReturn(Optional.of(finding));
        VettingService service=new VettingService(mock(ProjectService.class),null,null,null,null,findings,null,null,null,null,null);
        JsonNode detail=JsonUtils.mapper().valueToTree(service.evidence("project","F1"));JsonNode item=detail.path("items").get(0);
        assertEquals("SCC 4.301",item.path("clauseId").asText());assertEquals(quote.getAnchor(),item.path("anchor").asText());
        assertEquals(quote.getOriginalSourceSha256(),item.path("originalSourceSha256").asText());assertEquals("tender",item.path("sourceRole").asText());
        assertFalse(item.path("located").asBoolean());assertFalse(detail.path("located").asBoolean());assertFalse(item.has("packetId"));
        EvidenceItemVO formerFull=new EvidenceItemVO("SCC",null,"text","source","42","SCC","source.docx","native/location","text",false,"parsed",null,null,null,null,null,quote.getOriginalSourceSha256(),"tender",null,"content-stamp");
        assertEquals("content-stamp",formerFull.getBusinessContentSha256());assertEquals("tender",formerFull.getSourceRole());
    }

    @Test void canonicalRulesRetainOnlyEmittedBoundUniqueNativePositionsAndOrdinaryRulesKeepCompatibility() {
        DocumentBlock kept=nativeBlock("kept",true),unbound=nativeBlock("unbound",false),missing=nativeBlock("missing",true),duplicate=nativeBlock("duplicate",true),unused=nativeBlock("unused",true);
        missing.setReadingContext(null);
        VettingRuleEngine.Source source=source("42",kept,unbound,missing,duplicate,JsonUtils.read(JsonUtils.write(duplicate),DocumentBlock.class),unused);
        VettingRuleEngine.Source unrelated=source("43",nativeBlock("other-document",true));
        VettingRuleEngine.Output output=output(raw(source,kept),raw(source,unbound),raw(source,missing),raw(source,duplicate));
        Map<String,Map<String,JsonNode>> guarded=positions(Arrays.asList(source,unrelated),Collections.singletonList(output),true);
        assertEquals(Collections.singleton("42"),guarded.keySet());assertEquals(Collections.singleton("kept"),guarded.get("42").keySet());
        FindingEvidence quote=convert(output(raw(source,kept)),guarded).getEvidence().get(0);
        assertEquals(kept.getSourcePosition(),quote.getNativePositions().get(0));assertNotSame(kept.getSourcePosition(),quote.getNativePositions().get(0));
        Map<String,Map<String,JsonNode>> ordinary=positions(Collections.singletonList(source),Collections.singletonList(output),false);
        assertEquals(new HashSet<>(Arrays.asList("kept","unbound","missing")),ordinary.get("42").keySet());
        assertFalse(ordinary.get("42").containsKey("unused"));assertFalse(ordinary.get("42").containsKey("duplicate"));
    }

    @Test void originalIdentityChecksStillFilterWrongNativeShaWithoutPromotingAnUnresolvedCanonicalLink() {
        DocumentBlock block=nativeBlock("kept",true);VettingRuleEngine.Source source=source("42",block);VettingRuleEngine.Output output=output(raw(source,block));
        Candidate candidate=convert(output,positions(Collections.singletonList(source),Collections.singletonList(output),true));FindingEvidence quote=candidate.getEvidence().get(0);quote.setLocated(false);
        SourceDocument captured=new SourceDocument();captured.setId(42L);captured.setTextContent("Captured source");captured.setFileName("source.docx");captured.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);captured.setSourceSha256(VettingCorpus.hash("original"));quote.setSourceHash(VettingCorpus.sourceHash(captured));
        VettingService.bindRuleOriginalSources(Collections.singletonList(candidate),Collections.singletonList(captured));
        assertEquals(captured.getSourceSha256(),quote.getOriginalSourceSha256());assertEquals(1,quote.getNativePositions().size());assertFalse(quote.isLocated());assertNull(quote.getPacketId());
        ((ObjectNode)quote.getNativePositions().get(0)).put("sourceSha256",VettingCorpus.hash("different original"));
        VettingService.bindRuleOriginalSources(Collections.singletonList(candidate),Collections.singletonList(captured));
        assertTrue(quote.getNativePositions().isEmpty());assertFalse(quote.isLocated());
    }

    @Test void repeatedCanonicalOccurrencesRemainAmbiguousEvenAfterTheNativeAnchorRepair() throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session loaded=wire.load();Chunk original=loaded.chunks().get(0);
        Chunk repeated=JsonUtils.read(JsonUtils.write(original),Chunk.class);repeated.setId("authored-repeated-occurrence");repeated.getCanonicalEvidence().setCanonicalChunkId(repeated.getId());((ObjectNode)repeated.getCanonicalPayload()).put("id",repeated.getId());
        Map<String,Chunk> views=new LinkedHashMap<>();views.put(original.getId(),original);views.put(repeated.getId(),repeated);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
        OerBusinessEvidence.Session ambiguous=constructor.newInstance("project","vetting",Collections.singletonList(42L),Collections.singletonList(wire.binding),views,Collections.emptyMap());Part part=original.getParts().get(0);
        FindingEvidence quote=convert(output(new VettingRuleEngine.Evidence(original.getDocumentId(),original.getFileKey(),original.getFileName(),wire.binding.getJavaParsedSourceHash(),part.getBlockId(),part.getAnchor(),null,"SCC 99.999",part.getText(),null)),Collections.emptyMap()).getEvidence().get(0);
        wire.service.bindRuleEvidence(ambiguous,Collections.singletonList(quote));
        assertFalse(quote.isLocated());assertNull(quote.getCanonicalEvidence());assertEquals("SCC 99.999",quote.getClauseId());assertNull(quote.getPacketId());
    }

    @Test void sourceRevisionWrongDocumentWrongAnchorAndInexactQuoteStillReject() throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=wire.load();Chunk chunk=session.chunks().get(0);Part part=chunk.getParts().get(0);
        for(String mismatch:Arrays.asList("parsed","document","anchor","quote")) {
            VettingRuleEngine.Evidence raw=new VettingRuleEngine.Evidence("document".equals(mismatch)?"another-document":chunk.getDocumentId(),chunk.getFileKey(),chunk.getFileName(),
                    "parsed".equals(mismatch)?VettingCorpus.hash("replacement parsed revision"):wire.binding.getJavaParsedSourceHash(),part.getBlockId(),"anchor".equals(mismatch)?"another native location":part.getAnchor(),null,"SCC 99.999","quote".equals(mismatch)?"Text absent from this source.":part.getText(),null);
            FindingEvidence quote=convert(output(raw),Collections.emptyMap()).getEvidence().get(0);wire.service.bindRuleEvidence(session,Collections.singletonList(quote));
            assertFalse(quote.isLocated(),mismatch);assertNull(quote.getCanonicalEvidence(),mismatch);
        }
    }

    @Test void absentNativeLocationKeepsTheLegacyLogicalAnchorWithoutDuplicatingItsLabel() {
        FindingEvidence quote=convert(output(new VettingRuleEngine.Evidence("42","SCC","source.docx","parsed","block",null,null,"SCC 4.301","Exact legacy text.",null)),Collections.emptyMap()).getEvidence().get(0);
        assertEquals("SCC 4.301",quote.getAnchor());assertNull(quote.getClauseId());
    }

    @Test void fixedReferenceLocationNamesTheEditionForNewAndPreviouslySavedFindings() {
        FindingEvidence project=new FindingEvidence("source","42","SP","source.docx",null,"body/8/paragraph","Project text.",true,"parsed",null);
        FindingEvidence reference=new FindingEvidence("reference","fixed:1","SL",null,"1932","DRA2.W1920.A","Reference text.",true,"fixed",null);
        reference.setFixedBaselineEvidence(JsonUtils.parse("{\"sourceFamily\":\"SL\",\"editionId\":\"sl2022-batch02-rev1\",\"pageNo\":1932}"));
        Candidate candidate=new Candidate();candidate.setType("gap");candidate.setEvidence(Arrays.asList(project,reference));
        VettingService service=new VettingService(null,null,null,null,null,null,null,null,null,null,null);VettingFinding saved=new VettingFinding();
        ReflectionTestUtils.invokeMethod(service,"apply",saved,candidate);
        assertEquals("source.docx · body/8/paragraph; SL2022 Batch 2 revision 1 · page 1932",saved.getLocation());
        saved.setLocation("source.docx · body/8/paragraph; null · 1932");String evidenceBefore=saved.getEvidenceJson();
        com.consense.web.dto.VettingDtos.FindingVO view=ReflectionTestUtils.invokeMethod(service,"toVO",saved,"snapshot");
        assertEquals("source.docx · body/8/paragraph; SL2022 Batch 2 revision 1 · page 1932",view.getLocation());
        assertEquals("source.docx · body/8/paragraph; null · 1932",saved.getLocation());assertEquals(evidenceBefore,saved.getEvidenceJson());
    }

    private static VettingRuleEngine.Output output(VettingRuleEngine.Evidence... evidence) {
        return new VettingRuleEngine.Output("authored_rule","Authored rule","Review exact text","Review source","low","reference",Arrays.asList(evidence));
    }
    private static Candidate convert(VettingRuleEngine.Output output,Map<String,Map<String,JsonNode>> positions) {
        VettingService service=new VettingService(null,null,null,null,null,null,null,null,null,null,null);
        return ReflectionTestUtils.invokeMethod(service,"ruleCandidate",output,positions);
    }
    private static Map<String,Map<String,JsonNode>> positions(List<VettingRuleEngine.Source> sources,List<VettingRuleEngine.Output> outputs,boolean sourceBoundOnly) {
        VettingService service=new VettingService(null,null,null,null,null,null,null,null,null,null,null);
        return ReflectionTestUtils.invokeMethod(service,"ruleNativePositions",sources,outputs,sourceBoundOnly);
    }
    private static DocumentBlock nativeBlock(String id,boolean sourceBound) {
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setLocation("native/"+id);block.setText("Exact source text for "+id);
        ObjectNode position=JsonUtils.mapper().createObjectNode();position.put("kind","docx_native");position.put("sourceSha256",VettingCorpus.hash("original"));position.put("partName","word/document.xml");position.put("partSha256",VettingCorpus.hash("native part"));position.put("nodePath","/{urn:word}document[1]/{urn:word}body[1]/{urn:word}p[1]");block.setSourcePosition(position);
        ObjectNode context=JsonUtils.mapper().createObjectNode();context.putObject(VettingPackageIdentity.CONTEXT).put("status",sourceBound?"source_bound":"unknown");block.setReadingContext(context);return block;
    }
    private static VettingRuleEngine.Source source(String id,DocumentBlock... blocks) {
        return new VettingRuleEngine.Source(id,"SCC","source.docx","parsed",Arrays.asList(blocks),"");
    }
    private static VettingRuleEngine.Evidence raw(VettingRuleEngine.Source source,DocumentBlock block) {
        return new VettingRuleEngine.Evidence(source.getId(),source.getFileKey(),source.getFileName(),source.getHash(),block.getId(),block.getLocation(),null,block.getLocation(),block.getText(),null);
    }
}
