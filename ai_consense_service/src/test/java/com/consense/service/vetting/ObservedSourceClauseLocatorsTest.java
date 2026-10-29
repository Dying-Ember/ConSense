package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservedSourceClauseLocatorsTest {
    private static final String QUOTE="For the same monthly interim payment, the Employer shall pay each certified amount within 14 days after certification.";

    @Test void actualNativeParserRangeSupportsTheExactSelectedQuoteWithoutInventingFlatClauseOrAdoption()throws Exception{
        Fixture f=fixture();String before=JsonUtils.write(f.chunk),graphBefore=JsonUtils.write(f.graph);
        JsonNode range=f.word.path("ranges").get(0);
        assertEquals("NTT1",range.path("reference").asText());assertFalse(range.hasNonNull("parentId"));
        assertEquals("source_syntax_complete",range.path("status").asText());
        assertTrue(f.pointer.getParts().get(0).path("sourcePosition").path("nodePath").asText().contains("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p["));
        ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",f.word);
        assertNotNull(bound,f.word.toString());assertNull(f.chunk.getClauseId());assertNull(f.chunk.getClauseHeadingLocation());
        assertEquals(Collections.singleton("NTT1"),bound.references(f.chunk,QUOTE));
        JsonNode descriptor=JsonUtils.mapper().valueToTree(bound.model(f.chunk)).get(0);
        assertEquals(ObservedSourceClauseLocators.VERSION,descriptor.path("version").asText());assertEquals("NTT1",descriptor.path("reference").asText());
        assertFalse(descriptor.path("automaticEditEligible").asBoolean());assertFalse(descriptor.path("semanticApplicabilityVerified").asBoolean());
        assertTrue(descriptor.path("meaning").asText().contains("remain unknown"));assertFalse(f.pointer.isHumanConfirmed());assertFalse(f.canonical.hasNonNull("adoption"));
        VettingSemanticReview.Record record=record(f.chunk,QUOTE,"NTT Clause 1 requires payment within 14 days after certification.");
        assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(f.chunk.getId(),f.chunk)).size());
        assertTrue(reasons(f.chunk,record,bound).isEmpty());
        JsonNode model=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(f.chunk),FixedVettingReferences.empty(),null,
                Collections.singletonMap(f.chunk.getId(),f.word),Collections.singletonMap(f.chunk.getId(),bound))).get(0);
        assertEquals(descriptor,model.path("observedSourceClauseLocators").get(0));
        assertEquals(before,JsonUtils.write(f.chunk));assertEquals(graphBefore,JsonUtils.write(f.graph));
    }

    @Test void sourceAndSelectedPartsWrapperMutationsCannotBorrowTheCapturedContext()throws Exception{
        Fixture f=fixture();
        for(String field:Arrays.asList("sourceSha256","originalSourceSha256","sourceRevisionSha256","projectionSha256","canonicalChunkId","selectedPartsSha256","generationSha256","projectId","documentId","parsedSourceHash","version")){
            ObjectNode word=f.word.deepCopy();
            if(Arrays.asList("projectId","documentId","parsedSourceHash").contains(field))((ObjectNode)word.path("source")).put(field,"foreign");
            else word.put(field,"foreign");
            IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",word),field);
            assertEquals("normal_word_source_scope_context_binding_differs",rejected.getMessage());
        }
        for(String field:Arrays.asList("documentId","role","id","content")){
            ObjectNode canonical=f.canonical.deepCopy();canonical.put(field,"foreign");
            assertNull(ObservedSourceClauseLocators.capture(f.pointer,canonical,"tender",f.word),field);
        }
        EvidencePointer foreign=copy(f.pointer);foreign.setProducerKind("foreign");assertNull(ObservedSourceClauseLocators.capture(foreign,f.canonical,"tender",f.word));
        assertNull(ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"project_fact",f.word));
        assertNull(ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",null));
    }

    @Test void actualStandardSccRangeIsSupportedButLiteralReferencesAndAdjacentLabelsRemainUnowned()throws Exception{
        Fixture standard=fixture("SCC","standard","SCC1 "+QUOTE,null);
        ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(standard.pointer,standard.canonical,"standard",standard.word);assertNotNull(bound,standard.word.toString());
        assertEquals(Collections.singleton("SCC1"),bound.references(standard.chunk,QUOTE));
        assertTrue(reasons(standard.chunk,record(standard.chunk,QUOTE,"SCC Clause 1 requires payment within 14 days."),bound).isEmpty());
        assertEquals(1,reasons(standard.chunk,record(standard.chunk,QUOTE,"NTT Clause 1 requires payment within 14 days."),bound).size());
        for(Fixture unowned:Arrays.asList(fixture("The payment paragraph only refers to NTT1. "+QUOTE),fixture("NTT","tender",QUOTE,"NTT1"))){
            assertNull(unowned.chunk.getClauseId());
            assertNull(ObservedSourceClauseLocators.capture(unowned.pointer,unowned.canonical,"tender",unowned.word));
            assertTrue(VettingCorpus.evidence(unowned.chunk,"tender",QUOTE).isLocated());
            assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(record(unowned.chunk,QUOTE,"NTT Clause 1 requires payment within 14 days.")),Collections.singletonMap(unowned.chunk.getId(),unowned.chunk)).size());
        }
    }

    @Test void incompleteAmbiguousContextOnlyAndAlteredNativeRangesStayUnknown()throws Exception{
        Fixture f=fixture();Map<String,Consumer<ObjectNode>> variations=new LinkedHashMap<>();
        variations.put("context issue",word->word.withArray("issues").add("AUTHORED_UNRESOLVED_SOURCE"));
        variations.put("different member position",word->((ObjectNode)selectedMember(word).path("sourcePosition")).put("nodePath","foreign"));
        variations.put("different member block",word->selectedMember(word).putArray("retainedBlockIds").add("foreign"));
        variations.put("different member text",word->((ObjectNode)selectedMember(word).path("textObservation")).put("text","foreign"));
        variations.put("unobserved member",word->((ObjectNode)selectedMember(word).path("textObservation")).put("status","unknown"));
        variations.put("unbound member",word->selectedMember(word).put("sourceClauseClassification","context_only"));
        variations.put("duplicate matching member",word->word.withArray("members").add(selectedMember(word).deepCopy()));
        variations.put("no body member",word->range(word).putArray("orderedMemberIds"));
        variations.put("context only member",word->{String id=selectedMember(word).path("id").asText();range(word).putArray("orderedMemberIds");range(word).putArray("contextMemberIds").add(id);});
        variations.put("body context overlap",word->range(word).withArray("contextMemberIds").add(selectedMember(word).path("id").asText()));
        variations.put("duplicate body id",word->range(word).withArray("orderedMemberIds").add(selectedMember(word).path("id").asText()));
        variations.put("multiple ranges",word->word.withArray("ranges").add(range(word).deepCopy()));
        variations.put("partial scope",word->range(word).put("scopeComplete",false));
        variations.put("wrong status",word->range(word).put("status","source_syntax_partial"));
        variations.put("edit authority claimed",word->range(word).put("automaticEditEligible",true));
        variations.put("applicability claimed",word->range(word).put("semanticApplicabilityVerified",true));
        variations.put("invalid range id",word->range(word).put("id","synthetic-clause:1"));
        variations.put("literal reference only",word->range(word).put("reference","see Clause 1"));
        variations.put("adjacent heading start",word->((ObjectNode)range(word).path("startBoundary")).put("kind","adjacent_heading"));
        variations.put("unknown end",word->((ObjectNode)range(word).path("endBoundary")).put("kind","unverified_end"));
        variations.put("nested owner",word->range(word).put("parentId","source-clause:"+VettingCorpus.hash("parent")));
        for(Map.Entry<String,Consumer<ObjectNode>> variation:variations.entrySet()){
            ObjectNode word=f.word.deepCopy();variation.getValue().accept(word);
            assertNull(ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",word),variation.getKey());
        }
    }

    @Test void selectedPartialMultiplePartsAndTableParagraphsCannotAcquireTheWholeParagraphLocator()throws Exception{
        Fixture f=fixture();
        for(String variation:Arrays.asList("partial start","partial end","multiple parts","table path","cell path","not native","wrong original","wrong part hash","body differs")){
            ObjectNode canonical=f.canonical.deepCopy(),part=(ObjectNode)canonical.path("parts").get(0),position=(ObjectNode)part.path("sourcePosition");
            if("partial start".equals(variation))part.put("startOffset",1);
            if("partial end".equals(variation))part.put("endOffset",part.path("endOffset").asInt()-1);
            if("multiple parts".equals(variation))canonical.withArray("parts").add(part.deepCopy());
            if("table path".equals(variation))position.put("nodePath","/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}tbl[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p[1]");
            if("cell path".equals(variation))position.put("nodePath","/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}tc[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p[1]");
            if("not native".equals(variation))position.put("kind","text_inferred");
            if("wrong original".equals(variation))position.put("sourceSha256",VettingCorpus.hash("foreign"));
            if("wrong part hash".equals(variation))position.put("partSha256","invalid");
            if("body differs".equals(variation))canonical.put("content","different body");
            EvidencePointer pointer=copy(f.pointer);pointer.setParts(canonical.path("parts").deepCopy());
            ObjectNode word=f.word.deepCopy();word.put("selectedPartsSha256",VettingCorpus.hash(JsonUtils.write(pointer.getParts())));
            assertNull(ObservedSourceClauseLocators.capture(pointer,canonical,"tender",word),variation);
        }
    }

    @Test void capturedLocatorRejectsChangedChunkPointerPartsTextAndRole()throws Exception{
        Fixture f=fixture();ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",f.word);assertNotNull(bound);
        for(String field:Arrays.asList("projectId","documentId","originalSourceSha256","javaParsedSourceHash","canonicalSourceHash","sourceRevisionSha256","projectionSha256","projectionRevisionSha256","canonicalChunkId")){
            Chunk changed=copy(f.chunk);ObjectNode pointer=JsonUtils.mapper().valueToTree(changed.getCanonicalEvidence());
            if("documentId".equals(field))pointer.put(field,999L);else pointer.put(field,"foreign");
            changed.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(pointer),EvidencePointer.class));
            assertTrue(bound.references(changed,QUOTE).isEmpty(),field);assertTrue(bound.model(changed).isEmpty(),field);
        }
        Map<String,Consumer<Chunk>> variations=new LinkedHashMap<>();
        variations.put("chunk id",c->c.setId("foreign"));variations.put("document",c->c.setDocumentId("999"));
        variations.put("source hash",c->c.setSourceHash("foreign"));variations.put("source bytes",c->c.setOriginalSourceSha256("foreign"));
        variations.put("role",c->c.setRole("standard"));variations.put("content",c->c.setContent("foreign"));
        variations.put("part block",c->c.getParts().get(0).setBlockId("foreign"));variations.put("part text",c->c.getParts().get(0).setText("foreign"));
        variations.put("part start",c->c.getParts().get(0).setStartOffset(1));variations.put("part end",c->c.getParts().get(0).setEndOffset(1));
        variations.put("part native position",c->c.getParts().get(0).setSourcePosition(JsonUtils.mapper().createObjectNode().put("kind","foreign")));
        variations.put("extra part",c->c.getParts().add(JsonUtils.read(JsonUtils.write(c.getParts().get(0)),VettingCorpus.Part.class)));
        variations.put("pointer part text",c->((ObjectNode)c.getCanonicalEvidence().getParts().get(0)).put("text","foreign"));
        variations.put("pointer part start",c->((ObjectNode)c.getCanonicalEvidence().getParts().get(0)).put("startOffset",1));
        variations.put("pointer part position",c->((ObjectNode)c.getCanonicalEvidence().getParts().get(0).path("sourcePosition")).put("nodePath","foreign"));
        for(Map.Entry<String,Consumer<Chunk>> variation:variations.entrySet()){
            Chunk changed=copy(f.chunk);variation.getValue().accept(changed);
            assertTrue(bound.references(changed,QUOTE).isEmpty(),variation.getKey());assertTrue(bound.model(changed).isEmpty(),variation.getKey());
        }
    }

    @Test void aShortExactUniqueNativeQuoteRetainsItsBoundSourceClause()throws Exception{
        Fixture f=fixture("NTT1 Not used.");String quote="Not used.";assertEquals(9,quote.length());
        ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",f.word);assertNotNull(bound);
        assertTrue(VettingCorpus.evidence(f.chunk,"source",quote).isLocated());
        assertEquals(Collections.singleton("NTT1"),bound.references(f.chunk,quote));
        assertTrue(reasons(f.chunk,record(f.chunk,quote,"NTT Clause 1 states that the provision is not used."),bound).isEmpty());
        assertTrue(bound.references(f.chunk,"Not Used.").isEmpty());
        assertEquals(1,reasons(f.chunk,record(f.chunk,quote,"SCC Clause 1 states that the provision is not used."),bound).size());
    }

    @Test void aLocatedNormalizedBlankOrRepeatedQuoteDoesNotGrantSourceClauseOwnership()throws Exception{
        Fixture f=fixture();ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",f.word);assertNotNull(bound);
        for(String quote:Arrays.asList(""," ","\u00a0\u2003",QUOTE.toUpperCase(Locale.ROOT),QUOTE.replace(" ","  "))){
            assertTrue(bound.references(f.chunk,quote).isEmpty(),quote);
            assertEquals(1,reasons(f.chunk,record(f.chunk,quote,"Clause 1 requires payment within 14 days."),bound).size(),quote);
        }
        assertTrue(VettingCorpus.evidence(f.chunk,"tender",QUOTE.replace(" ","  ")).isLocated(),"Existing normalized evidence location alone is weaker than this new ownership proof");
        Fixture repeated=fixture("NTT1 "+QUOTE+" "+QUOTE);ObservedSourceClauseLocators.Bound repeatedBound=ObservedSourceClauseLocators.capture(repeated.pointer,repeated.canonical,"tender",repeated.word);assertNotNull(repeatedBound);
        assertTrue(VettingCorpus.evidence(repeated.chunk,"tender",QUOTE).isLocated());assertTrue(repeatedBound.references(repeated.chunk,QUOTE).isEmpty());
        assertEquals(1,reasons(repeated.chunk,record(repeated.chunk,QUOTE,"Clause 1 requires payment within 14 days."),repeatedBound).size());
        assertEquals(1,reasons(f.chunk,record(f.chunk,QUOTE,"NTT Clause 2 requires payment within 14 days."),bound).size());
        assertEquals(1,reasons(f.chunk,record(f.chunk,QUOTE,"SCC Clause 1 requires payment within 14 days."),bound).size());
    }

    @Test void bothCompactModelBranchesRetainTheSamePreprojectionProofAndUnknownLegalAuthority()throws Exception{
        Fixture f=fixture();ObservedSourceClauseLocators.Bound bound=ObservedSourceClauseLocators.capture(f.pointer,f.canonical,"tender",f.word);assertNotNull(bound);
        JsonNode descriptor=JsonUtils.mapper().valueToTree(bound.model(f.chunk));
        for(boolean grouped:new boolean[]{false,true}){
            Chunk compact=compact(f.chunk,grouped);String before=JsonUtils.write(compact);
            assertNull(OerBusinessEvidence.canonicalViewStatus(compact));assertNull(compact.getParts().get(0).getSourcePosition());
            assertFalse(compact.getCanonicalEvidence().getParts().get(0).has("sourcePosition"));
            assertEquals(Collections.singleton("NTT1"),bound.references(compact,QUOTE));
            JsonNode model=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(compact),FixedVettingReferences.empty(),null,
                    Collections.singletonMap(compact.getId(),VettingSourceMaterial.semantic(f.word,Collections.emptyMap())),Collections.singletonMap(compact.getId(),bound))).get(0);
            assertEquals(descriptor,model.path("observedSourceClauseLocators"));assertFalse(model.has("canonicalStructure"));
            assertEquals(grouped,model.has("semanticUnitRef"));assertNull(compact.getClauseId());assertFalse(compact.getCanonicalEvidence().isHumanConfirmed());
            VettingSemanticReview.Record record=record(compact,QUOTE,"NTT Clause 1 requires payment within 14 days after certification.");
            assertTrue(reasons(compact,record,bound).isEmpty());assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(compact.getId(),compact)).size());
            assertEquals(before,JsonUtils.write(compact));
        }
    }

    private static Chunk compact(Chunk original,boolean grouped){
        Chunk compact=copy(original);ObjectNode raw=JsonUtils.mapper().createObjectNode();
        for(String field:Arrays.asList("id","unitId","documentId","role","clauseId","sourceHash","eligibleModules","sourceOrder","sourceQuality"))raw.set(field,original.getCanonicalPayload().path(field).deepCopy());
        raw.put("semanticProjectionVersion","source-semantic-units-v1");raw.put("text",original.getContent());raw.putArray("unknowns");
        raw.putObject("evidenceRef").put("projectionSha256",original.getCanonicalEvidence().getProjectionSha256()).put("canonicalChunkId",original.getId()).put("originalSourceSha256",original.getOriginalSourceSha256());
        for(JsonNode part:original.getCanonicalPayload().path("parts")){
            ObjectNode retained=raw.withArray("parts").addObject();for(String field:Arrays.asList("blockId","text","startOffset","endOffset"))retained.set(field,part.path(field).deepCopy());
        }
        ObjectNode unit=raw.putObject("semanticUnit");unit.put("status","complete_observed_unit");unit.put("unitId",VettingCorpus.hash(original.getId()));unit.putArray("requiredMemberIds").add(original.getId());unit.putArray("originIds").add(original.getId());
        if(grouped)raw.put("semanticUnitRef",unit.path("unitId").asText());
        compact.setCanonicalPayload(raw);compact.getCanonicalEvidence().setProjectionSchema("source-semantic-units-v1");compact.getCanonicalEvidence().setAddressKind("semantic_source_unit");compact.getCanonicalEvidence().setParts(raw.path("parts").deepCopy());
        compact.getParts().forEach(part->part.setSourcePosition(null));return compact;
    }

    private static ObjectNode selectedMember(ObjectNode word){for(JsonNode member:word.path("members"))if(member.path("textObservation").path("text").asText().startsWith("NTT1 "))return (ObjectNode)member;throw new AssertionError("Selected native NTT1 member missing");}
    private static ObjectNode range(ObjectNode word){return (ObjectNode)word.path("ranges").get(0);}
    private static EvidencePointer copy(EvidencePointer pointer){return JsonUtils.read(JsonUtils.write(pointer),EvidencePointer.class);}
    private static Chunk copy(Chunk chunk){return JsonUtils.read(JsonUtils.write(chunk),Chunk.class);}

    private static List<Map<String,Object>> reasons(Chunk c,VettingSemanticReview.Record record,ObservedSourceClauseLocators.Bound bound){
        return VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(c.getId(),c),Collections.singletonMap(c.getId(),bound));
    }
    private static VettingSemanticReview.Record record(Chunk chunk,String quote,String comment){
        VettingSemanticReview.Quote evidence=new VettingSemanticReview.Quote();evidence.setChunkId(chunk.getId());evidence.setSide(chunk.getRole());evidence.setQuote(quote);
        VettingSemanticReview.Record record=new VettingSemanticReview.Record();record.setComment(comment);record.setEvidence(Collections.singletonList(evidence));return record;
    }
    private static Fixture fixture()throws Exception{return fixture("NTT1 "+QUOTE);}
    static SourceDocument authoredSource()throws Exception{return fixture().source;}
    private static Fixture fixture(String selectedText)throws Exception{return fixture("NTT","tender",selectedText,null);}
    private static Fixture fixture(String family,String role,String selectedText,String preceding)throws Exception{
        OcrClient ocr=mock(OcrClient.class);byte[] bytes;
        try(XWPFDocument document=new XWPFDocument()){
            document.createParagraph().createRun().setText("SCC".equals(family)?"Special Conditions of Contract":"Notes to Tenderers");
            if(preceding!=null)document.createParagraph().createRun().setText(preceding);
            document.createParagraph().createRun().setText(selectedText);
            document.createParagraph().createRun().setText(family+"2 The next independent condition applies only to its own source text.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();document.write(out);bytes=out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("authored-locator.docx",bytes);verifyNoInteractions(ocr);
        SourceDocument source=new SourceDocument();source.setId(98L);source.setProjectId("authored");source.setFileName("authored-locator.docx");source.setFileKey(family);source.setReviewRole(role);source.setDocumentScope("fullset");source.setParseStatus(parsed.getParseStatus());source.setSourceSha256(DocumentParseProbe.sha256(bytes));source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source),selected=null;
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(selectedText.equals(part.path("text").asText()))selected=unit;
        assertNotNull(selected,"Actual normal producer must retain the authored whole source paragraph");
        JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,selected);
        Method selectedPointer=CanonicalWordSourceContextTest.class.getDeclaredMethod("selectedPointer",com.consense.domain.CanonicalSourceBinding.class,JsonNode.class,JsonNode.class);selectedPointer.setAccessible(true);
        EvidencePointer pointer=(EvidencePointer)selectedPointer.invoke(null,CanonicalWordSourceContextTest.binding(source),graph,canonical);
        Method view=OerBusinessEvidence.class.getDeclaredMethod("view",JsonNode.class,EvidencePointer.class,SourceDocument.class);view.setAccessible(true);
        Chunk chunk=(Chunk)view.invoke(null,canonical,pointer,source);assertNull(OerBusinessEvidence.canonicalViewStatus(chunk));
        return new Fixture(source,graph,canonical,pointer,chunk,pointer.getNativeWordSourceScopesContext());
    }
    private static final class Fixture {
        final SourceDocument source;final JsonNode graph,canonical,word;final EvidencePointer pointer;final Chunk chunk;
        Fixture(SourceDocument source,JsonNode graph,JsonNode canonical,EvidencePointer pointer,Chunk chunk,JsonNode word){this.source=source;this.graph=graph;this.canonical=canonical;this.pointer=pointer;this.chunk=chunk;this.word=word;}
    }
}
