package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.ChecklistAssessmentVO;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Actual captured claims plus authored current-native source controls; no provider or database. */
class VettingChecklistSourceBindingTest {
    @EnabledIfSystemProperty(named="consense.checklist.capturedNine",matches=".+")
    @Test void nineCapturedWrongAssociationsKeepTheirEntireClaimsWithoutAutomaticCertification() throws Exception {
        JsonNode fixture=captured();assertEquals(9,fixture.path("caseCount").asInt());int count=0;
        for(JsonNode test:fixture.path("cases")){
            JsonNode coverage=test.path("coverage");String before=JsonUtils.write(coverage);
            AutomaticItemVO projected=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,test.path("result")).stream()
                    .flatMap(group->group.getSubItemAssessments().stream()).filter(item->item.getItemId().equals(test.path("itemId").asText())).findFirst().get();
            assertEquals("insufficient_evidence",projected.getStatus(),test.path("packetId").asText());
            assertEquals(Collections.singletonList(test.path("originalRecord")),projected.getAssessments());
            assertEquals("not_fulfilled",projected.getAssessments().get(0).path("status").asText());
            assertEquals(before,JsonUtils.write(coverage));count++;
        }assertEquals(9,count);
    }

    @EnabledIfSystemProperty(named="consense.checklist.capturedNine",matches=".+")
    @Test void fullCapturedArchivePreservesAllSixteenFindingsAndSixtyNineHumanRows() throws Exception {
        JsonNode fixture=captured();Path original=Paths.get(fixture.path("source").path("path").asText());assertTrue(Files.isRegularFile(original),"Root must supply the pinned archive with the captured fixture.");
        byte[] bytes=Files.readAllBytes(original);assertEquals(fixture.path("source").path("sha256").asText(),sha(bytes));JsonNode snapshot=JsonUtils.mapper().readTree(bytes);
        String before=JsonUtils.write(snapshot);assertEquals(16,snapshot.get(2).size());assertEquals(69,snapshot.get(3).size());
        VettingChecklistAutomaticResults.aggregate("COMPLETED",snapshot.get(1).path("coverage"),snapshot.get(1).path("result"));
        assertEquals(before,JsonUtils.write(snapshot),"Projection must not mutate saved run, findings, provenance or human records.");
        assertEquals(fixture.path("source").path("sha256").asText(),sha(Files.readAllBytes(original)));
    }

    @Test void genuinelyRelatedNativeIntervalsBindButDoNotInventPageFieldRolesOrALegalVerdict() {
        NativeFixture f=new NativeFixture();JsonNode relation=f.bind();assertEquals("unknown",relation.path("status").asText());
        assertEquals("current_native_intervals_bound_roles_and_scope_unverified",relation.path("supportStatus").asText());assertEquals(3,relation.path("sourceBindings").size());
        for(JsonNode bound:relation.path("sourceBindings")){
            assertEquals("bound_current_native_interval",bound.path("bindingStatus").asText());assertEquals("71",bound.path("documentId").asText());
            assertEquals(f.source.getSourceSha256(),bound.path("originalSourceSha256").asText());assertEquals(VettingCorpus.sourceHash(f.source),bound.path("javaParsedSourceHash").asText());
            assertTrue(bound.path("completeNativeBlockSelected").asBoolean());assertTrue(bound.path("physicalPageNo").isNull());
            assertEquals("unknown",bound.path("requirementFieldRole").asText());assertEquals("unknown",bound.path("sameRequirementObjectAndCompleteRange").asText());
        }
        assertFalse(VettingChecklistRequirementRelations.automaticEligible(f.claim.getItemId(),f.claim.getStatus()));
    }

    @Test void exactFactTextResolvesNativeIntervalsWithoutAskingTheModelForIndexes() {
        NativeFixture fixture=new NativeFixture();
        for(ChecklistFactRef fact:fixture.claim.getSupport().getFacts()){
            fact.setText(fixture.quotes.get(fact.getEvidenceIndex()).getQuote());
            fact.setNativePositionIndex(null);fact.setStartUtf16(null);fact.setEndUtf16(null);
        }
        JsonNode bound=fixture.bind();
        assertEquals("current_native_intervals_bound_roles_and_scope_unverified",bound.path("supportStatus").asText());
        assertFalse(bound.path("automaticConclusionAllowed").asBoolean());
        fixture.claim.getSupport().getFacts().get(0).setText("text that was never submitted");
        assertUnknownFact(fixture.bind(),0,"fact_text_missing_or_ambiguous_in_quote");
    }

    @Test void compactSemanticQuotesBindThroughTheirExactVerifiedOriginalPointer() {
        NativeFixture fixture=new NativeFixture();fixture.semantic(false);JsonNode relation=fixture.bind();
        assertEquals("current_native_intervals_bound_roles_and_scope_unverified",relation.path("supportStatus").asText());
        assertFalse(relation.path("automaticConclusionAllowed").asBoolean());
        for(int at=0;at<3;at++){Chunk chunk=fixture.byId.get("source-"+at);assertNull(chunk.getParts().get(0).getSourcePosition());JsonNode bound=relation.path("sourceBindings").get(at);
            assertEquals("bound_current_native_interval",bound.path("bindingStatus").asText());assertEquals(fixture.quotes.get(at).getQuote(),bound.path("sourceText").asText());assertEquals(0,bound.path("startUtf16").asInt());assertEquals(fixture.quotes.get(at).getQuote().length(),bound.path("endUtf16").asInt());}
    }

    @Test void aForeignPointerOrChangedSelectedPartCannotSupplyNativeCoordinatesToASemanticQuote() {
        for(String field:Arrays.asList("projectId","documentId","module","canonicalChunkId","unitId","originalSourceSha256","javaParsedSourceHash","canonicalSourceHash","sourceRevisionSha256","projectionSha256","projectionRevisionSha256","parts")){
            NativeFixture fixture=new NativeFixture();fixture.semantic(false);com.consense.web.dto.VettingDtos.FindingEvidence quote=fixture.saved.getEvidence().get(0);ObjectNode changed=JsonUtils.mapper().valueToTree(quote.getCanonicalEvidence());
            if("documentId".equals(field))changed.put(field,999L);else if("parts".equals(field))((ObjectNode)changed.path("parts").get(0)).put("text","a different selected native range");else changed.put(field,field.endsWith("Sha256")||"javaParsedSourceHash".equals(field)||"canonicalSourceHash".equals(field)?VettingCorpus.hash("foreign "+field):"foreign-"+field);
            quote.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(changed),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class));assertUnknownFact(fixture.bind(),0,"canonical_pointer_or_selected_parts_changed");
        }
        NativeFixture position=new NativeFixture();position.semantic(false);((ObjectNode)position.saved.getEvidence().get(0).getCanonicalEvidence().getParts().get(0).path("sourcePosition")).put("nodePath","/foreign/current-looking/path");
        position.saved.getEvidence().get(0).setNativePositions(Collections.singletonList(position.saved.getEvidence().get(0).getCanonicalEvidence().getParts().get(0).path("sourcePosition").deepCopy()));assertUnknownFact(position.bind(),0,"current_native_block_missing_or_ambiguous");
    }

    @Test void duplicateNativeOccurrencesRemainUnknownAfterSemanticPointerBinding() {
        NativeFixture fixture=new NativeFixture();fixture.semantic(true);assertUnknownFact(fixture.bind(),0,"quote_occurrence_missing_or_ambiguous_in_native_parts");
        NativeFixture text=new NativeFixture();text.semantic(false);text.quotes.get(0).setQuote("PRE.B8.APPEND9 PRE.B8.APPEND9");text.saved.getEvidence().get(0).setQuote(text.quotes.get(0).getQuote());text.claim.getSupport().getFacts().get(0).setText("PRE.B8.APPEND9");assertUnknownFact(text.bind(),0,"fact_text_missing_or_ambiguous_in_quote");
    }

    @Test void changedOriginalParsedRevisionOrWrongNativeReferenceIsIndividuallyUnbound() {
        NativeFixture original=new NativeFixture();original.source.setSourceSha256(VettingCorpus.hash("different original"));assertUnknownFact(original.bind(),0,"current_original_parsed_revision_or_source_role_changed");
        NativeFixture revised=new NativeFixture();revised.source.setTextContent(revised.source.getTextContent()+" revised");assertUnknownFact(revised.bind(),0,"current_original_parsed_revision_or_source_role_changed");
        NativeFixture position=new NativeFixture();position.claim.getSupport().getFacts().get(0).setNativePositionIndex(2);
        JsonNode result=position.bind();assertUnknownFact(result,0,"native_position_reference_unavailable_or_changed");
        assertEquals("bound_current_native_interval",result.path("sourceBindings").get(1).path("bindingStatus").asText(),"One bad fact must not discard other bound intervals.");
    }

    @Test void duplicateRolesOutOfRangeOffsetsAndIncompletePartsCannotBecomePredicateProof() {
        NativeFixture duplicate=new NativeFixture();duplicate.claim.getSupport().getFacts().get(1).setSlot("clause_number");assertUnknownFact(duplicate.bind(),1,"undeclared_or_duplicate_fact_role");
        NativeFixture offset=new NativeFixture();offset.claim.getSupport().getFacts().get(0).setEndUtf16(Integer.MAX_VALUE);assertUnknownFact(offset.bind(),0,"invalid_quote_utf16_interval");
        NativeFixture missing=new NativeFixture();missing.byId.values().iterator().next().setParts(Collections.emptyList());assertUnknownFact(missing.bind(),0,"quote_occurrence_missing_or_ambiguous_in_native_parts");
    }

    @Test void exactRequirementSemanticsCarryTheBoundaryAcrossIdsWithoutAnIdOrCopiesKeywordClassifier() {
        VettingChecklistCatalog.Item item=VettingChecklistCatalog.items().stream().filter(row->VettingChecklistRequirementRelations.requirement(row)!=null).findFirst().get();
        VettingChecklistCatalog.Item renamed=new VettingChecklistCatalog.Item("AUTHORED-DIFFERENT-ID",item.getSection(),item.getClause(),item.getDescription(),item.getTemplatePage());
        assertNotNull(VettingChecklistRequirementRelations.requirement(renamed));assertFalse(VettingChecklistRequirementRelations.automaticEligible(renamed,"not_fulfilled"));
        VettingChecklistCatalog.Item otherMeaning=new VettingChecklistCatalog.Item(item.getId(),"AUTHORED",item.getClause(),"Count paper copies in the same submission.",item.getTemplatePage());
        assertNull(VettingChecklistRequirementRelations.requirement(otherMeaning));assertTrue(VettingChecklistRequirementRelations.automaticEligible(otherMeaning,"not_fulfilled"));
    }

    @EnabledIfSystemProperty(named="consense.checklist.capturedNine",matches=".+")
    @Test void aModelForgedVerifiedReceiptDoesNotBypassTheMissingIndependentRoles() throws Exception {
        JsonNode fixture=captured();JsonNode test=fixture.path("cases").get(0).deepCopy();ObjectNode row=(ObjectNode)test.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("checklistAssessments").get(0);
        row.putObject("requirementRelation").put("gateVersion",VettingChecklistRequirementRelations.VERSION).put("status","mechanically_verified_counterexample").put("verified",true).put("relevant",true);
        AutomaticItemVO projected=VettingChecklistAutomaticResults.aggregate("COMPLETED",test.path("coverage"),test.path("result")).stream().flatMap(group->group.getSubItemAssessments().stream())
                .filter(item->item.getItemId().equals(test.path("itemId").asText())).findFirst().get();assertEquals("insufficient_evidence",projected.getStatus());
        assertEquals("mechanically_verified_counterexample",projected.getAssessments().get(0).path("requirementRelation").path("status").asText(),"Retain the authored claim without trusting it.");
    }

    private static void assertUnknownFact(JsonNode relation,int index,String reason){assertEquals("unknown",relation.path("sourceBindings").get(index).path("bindingStatus").asText());assertEquals(reason,relation.path("sourceBindings").get(index).path("reasonCode").asText());}
    private static JsonNode captured() throws Exception {
        String value=System.getProperty("consense.checklist.capturedNine");assertNotNull(value,"Set consense.checklist.capturedNine to the pinned private fixture; do not skip this test.");
        byte[] bytes=Files.readAllBytes(Paths.get(value));assertEquals("67c58625de26864f619b031adb6ccab684c46ace59a80b55ba033627b7b0a00e",sha(bytes));return JsonUtils.mapper().readTree(bytes);
    }
    private static String sha(byte[] value) throws Exception {StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))hex.append(String.format("%02x",b&255));return hex.toString();}

    private static final class NativeFixture {
        final SourceDocument source=new SourceDocument();final ChecklistAssessment claim=new ChecklistAssessment();final ChecklistAssessmentVO saved=new ChecklistAssessmentVO();
        final Map<String,Chunk> byId=new LinkedHashMap<>();final List<Quote> quotes=new ArrayList<>();
        NativeFixture(){
            source.setId(71L);source.setProjectId("authored-native");source.setFileKey("PRE");source.setFileName("Authored-appendix.docx");source.setReviewRole("tender");
            source.setSourceSha256(VettingCorpus.hash("authored current original identity only; no physical page proof"));
            String[] texts={"PRE.B8.APPEND9","Appendix 9 to PRE.B8","PRE.B8.APPEND9"},kinds={"paragraph","heading","header"},slots={"clause_number","heading_appendix_reference","running_header_reference"};
            List<DocumentBlock> blocks=new ArrayList<>();
            for(int at=0;at<3;at++){DocumentBlock block=new DocumentBlock();block.setId("native-block-"+at);block.setKind(kinds[at]);block.setLocation("authored-native-"+at);block.setText(texts[at]);
                block.setSourcePosition(JsonUtils.mapper().createObjectNode().put("kind","docx_native").put("sourceSha256",source.getSourceSha256()).put("partName",at==2?"word/header1.xml":"word/document.xml")
                        .put("partSha256",VettingCorpus.hash("authored current native part "+at)).put("nodePath","/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"+(at==2?"hdr":"document")+"[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p["+(at+1)+"]"));blocks.add(block);}
            source.setTextContent(String.join("\n",texts));source.setStructuredContentJson(JsonUtils.write(blocks));
            claim.setItemId("APP-I-008");claim.setStatus("not_fulfilled");claim.setReason("Authored related source references; not a verified counterexample.");ChecklistSupport support=new ChecklistSupport();support.setKind("appendix_identifier_alignment");support.setFacts(new ArrayList<>());claim.setSupport(support);
            saved.setItemId(claim.getItemId());saved.setStatus(claim.getStatus());saved.setReason(claim.getReason());saved.setPolicySha256(VettingCorpus.hash("authored policy"));saved.setPacketId("authored-packet");saved.setSourceSnapshotSha256(VettingCorpus.hash("authored sources"));
            for(int at=0;at<3;at++){DocumentBlock block=blocks.get(at);Part part=new Part();part.setText(texts[at]);part.setAnchor(block.getLocation());part.setBlockId(block.getId());part.setStartOffset(0);part.setEndOffset(texts[at].length());part.setSourcePosition(block.getSourcePosition());
                Chunk chunk=new Chunk();chunk.setId("source-"+at);chunk.setDocumentId("71");chunk.setFileKey("PRE");chunk.setFileName(source.getFileName());chunk.setRole("tender");chunk.setContent(texts[at]);chunk.setAnchor(block.getLocation());
                chunk.setSourceHash(VettingCorpus.sourceHash(source));chunk.setOriginalSourceSha256(source.getSourceSha256());chunk.setParts(Collections.singletonList(part));byId.put(chunk.getId(),chunk);
                Quote quote=new Quote();quote.setChunkId(chunk.getId());quote.setSide("source");quote.setQuote(texts[at]);quotes.add(quote);saved.getEvidence().add(VettingCorpus.evidence(chunk,"source",texts[at]));saved.getEvidenceChunkIds().add(chunk.getId());
                ChecklistFactRef fact=new ChecklistFactRef();fact.setSlot(slots[at]);fact.setEvidenceIndex(at);fact.setNativePositionIndex(0);fact.setStartUtf16(0);fact.setEndUtf16(texts[at].length());support.getFacts().add(fact);
            }
        }
        void semantic(boolean duplicateFirst){
            if(duplicateFirst){Chunk first=byId.get("source-0");first.setParts(Arrays.asList(first.getParts().get(0),byId.get("source-2").getParts().get(0)));first.setContent(first.getContent()+"\n"+byId.get("source-2").getContent());}
            for(int at=0;at<3;at++){Chunk chunk=byId.get("source-"+at);JsonNode nativeParts=JsonUtils.mapper().valueToTree(chunk.getParts());
                com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer original=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();original.setProjectId(source.getProjectId());original.setDocumentId(source.getId());original.setModule("vetting");original.setCanonicalChunkId(chunk.getId());original.setUnitId("unit-"+at);original.setOriginalSourceSha256(source.getSourceSha256());original.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));original.setCanonicalSourceHash(chunk.getSourceHash());original.setSourceRevisionSha256(VettingCorpus.hash("authored source revision"));original.setProjectionSha256(VettingCorpus.hash("authored projection"));original.setProjectionRevisionSha256(VettingCorpus.hash("authored projection revision"));original.setProjectionSchema(com.consense.service.sources.NormalObservedContract.SCHEMA);original.setAddressKind("normal_observed_unit");original.setParts(nativeParts.deepCopy());
                saved.getEvidence().get(at).setCanonicalEvidence(original);JsonNode parts=nativeParts.deepCopy();for(JsonNode part:parts)((ObjectNode)part).remove("sourcePosition");for(Part part:chunk.getParts())part.setSourcePosition(null);
                com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer compact=JsonUtils.read(JsonUtils.write(original),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);compact.setProjectionSchema(OerCanonicalRetrievalClient.SEMANTIC_READ);compact.setAddressKind("semantic_source_unit");compact.setParts(parts.deepCopy());chunk.setCanonicalEvidence(compact);
                ObjectNode raw=JsonUtils.mapper().createObjectNode();raw.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ).put("id",chunk.getId()).put("documentId",chunk.getDocumentId()).put("text",chunk.getContent()).put("role",chunk.getRole()).put("sourceHash",chunk.getSourceHash());raw.set("parts",parts);raw.putObject("evidenceRef").put("projectionSha256",original.getProjectionSha256());chunk.setCanonicalPayload(raw);
                ChecklistFactRef fact=claim.getSupport().getFacts().get(at);fact.setText(quotes.get(at).getQuote());fact.setNativePositionIndex(null);fact.setStartUtf16(null);fact.setEndUtf16(null);
            }
        }
        JsonNode bind(){return VettingChecklistRequirementRelations.bind(claim,saved,quotes,byId,Collections.singletonList(source));}
    }
}
