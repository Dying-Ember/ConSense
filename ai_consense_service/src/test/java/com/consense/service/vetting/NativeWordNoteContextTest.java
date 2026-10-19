package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.NormalObservedContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real graph/ordinary/normal mapper/model producers, without an original parse or model call. */
class NativeWordNoteContextTest {
    private static final String SHA=VettingCorpus.hash("authored original package"),BODY_SHA=VettingCorpus.hash("authored body part"),NOTE_SHA=VettingCorpus.hash("authored note part"),REL_SHA=VettingCorpus.hash("authored relationships");
    private static final String W="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}",R="{http://schemas.openxmlformats.org/package/2006/relationships}";
    private static final String BODY="/"+W+"document[1]/"+W+"body[1]/"+W+"p[1]";
    private static final String NOTE="Required training qualification.\nThe supervisor shall endorse the report.";

    @Test void resolvedFootAndEndnoteTargetIsContextThroughActualProducersModelAndCompact()throws Exception{
        for(String kind:Arrays.asList("footnote","endnote")){
            Fixture fixture=new Fixture(kind);SourceDocument source=fixture.source();
            NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
            JsonNode body=graphPart(graph,"body:0:paragraph"),notes=body.path("readingContext").path("nativeWordNotes");
            assertEquals("native-word-note-context-v1",notes.path("version").asText());
            assertEquals("native_parent_block_explicit_reference_context",notes.path("scope").asText());
            assertFalse(notes.path("independentCitationEligible").asBoolean(true));
            assertFalse(notes.path("automaticEditEligible").asBoolean(true));
            assertFalse(notes.path("semanticScopeVerified").asBoolean(true));
            assertEquals("unknown",notes.path("legalClosure").asText());
            JsonNode entry=notes.path("entries").get(0);assertNotNull(entry);
            assertEquals("resolved",entry.path("status").asText());
            assertEquals(fixture.reference,entry.path("reference"));
            assertEquals(fixture.note.getSourcePosition(),entry.path("target").path("sourcePosition"));
            assertEquals(NOTE,entry.path("target").path("text").asText());
            assertEquals("Original note with deletion. "+NOTE,entry.path("target").path("wordObservation").path("originalText").asText());
            assertFalse(entry.path("target").has("chunkId"));assertFalse(entry.path("target").has("canonicalChunkId"));
            VettingCorpus.Chunk seed=VettingCorpus.chunks(Collections.singletonList(source)).stream()
                    .filter(c->c.getParts().stream().anyMatch(p->"body:0:paragraph".equals(p.getBlockId()))).findFirst().orElseThrow();
            String quotation=seed.getContent();JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(seed),Collections.singletonList(source)));
            assertTrue(ordinary.toString().contains("Required training qualification."));assertEquals(quotation,seed.getContent());
            assertFalse(quotation.contains(NOTE),"Context is not appended to the source quotation");
            assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));
            JsonNode model=normalModel(graph,body);assertEquals(notes,model.path("parts").get(0).path("readingContext").path("nativeWordNotes"));
            SourceJsonWireAssertions.assertRoundTrip(model);
            assertTrue(model.path("adoption").isNull());assertFalse(model.path("sourceQuality").path("sourceQualityAccepted").asBoolean(true));
        }
    }

    @Test void nativeParentReferenceContextDoesNotClaimAnExactPointInsideALongSelectedSlice(){
        Fixture fixture=new Fixture("footnote");fixture.body.setText(String.join("",Collections.nCopies(1800,"x")));fixture.body.setOriginalText(fixture.body.getText());
        SourceDocument source=fixture.source();NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        VettingCorpus.Chunk original=chunks.stream().filter(c->c.getParts().stream().anyMatch(p->"body:0:paragraph".equals(p.getBlockId()))).findFirst().orElseThrow();
        VettingCorpus.Chunk selected=JsonUtils.read(JsonUtils.write(original),VettingCorpus.Chunk.class);
        VettingCorpus.Part slice=selected.getParts().get(0);slice.setStartOffset(20);slice.setEndOffset(120);slice.setText(fixture.body.getText().substring(20,120));slice.setAnchor(slice.getAnchor()+" @20");
        selected.setContent(slice.getText());selected.setAnchor(slice.getAnchor());
        selected.setCandidateStructure(NormalSourceCandidateGraph.selected(NormalSourceCandidateGraph.requireBound(source),selected));
        // A real parent-bound source slice still cannot locate a native zero-width reference
        // inside its text offsets. Retain parent context with that limit stated explicitly.
        JsonNode raw=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(selected),Collections.singletonList(source)));
        JsonNode notes=graphPart(selected.getCandidateStructure(),"body:0:paragraph").path("readingContext").path("nativeWordNotes");
        assertEquals("native_parent_block_explicit_reference_context",notes.path("scope").asText());
        assertEquals("unknown",notes.path("referenceWithinSelectedTextRange").asText());
        assertFalse(notes.path("independentCitationEligible").asBoolean(true));
        assertTrue(raw.toString().contains("Required training qualification."));assertEquals(selected.getContent(),selected.getParts().get(0).getText());
    }

    @Test void invalidAmbiguousUnloadedAndInactiveReferencesRemainExplicitUnknown(){
        for(String mutation:Arrays.asList("missing_source_sha","reference_foreign_source","target_foreign_source","body_part_sha_mismatch","target_missing_part_sha","target_wrong_part","target_wrong_node","wrong_note_id","duplicate_target","unloaded_target","deleted_reference","struck_reference","unresolved_reference","external_relationship","wrong_relationship_target","reference_outside_parent","wrong_note_kind","duplicate_reference")){
            Fixture fixture=new Fixture("footnote");fixture.mutate(mutation);SourceDocument source=fixture.source();NormalSourceCandidateGraph.capture(source);
            JsonNode notes=graphPart(NormalSourceCandidateGraph.requireBound(source),"body:0:paragraph").path("readingContext").path("nativeWordNotes");
            assertFalse(notes.path("entries").isEmpty(),"Keep the unresolved source reference: "+mutation);
            for(JsonNode entry:notes.path("entries")){
                assertEquals("unknown",entry.path("status").asText(),mutation);
                assertTrue(entry.path("reason").isTextual()&&!entry.path("reason").asText().isEmpty(),mutation);
                assertFalse(entry.hasNonNull("target"),"Invalid reference cannot submit a borrowed note body: "+mutation);
                assertTrue(entry.path("reference").isObject(),"Original native reference remains auditable: "+mutation);
            }
            assertFalse(notes.toString().contains("Required training qualification."),mutation);
        }
    }

    @Test void sharedTargetFromTwoDistinctReferencesKeepsEachOriginalReferenceWithoutInventingCitationIds(){
        Fixture fixture=new Fixture("footnote");ObjectNode second=fixture.reference.deepCopy();
        ((ObjectNode)second.path("noteReference").path("sourcePosition")).put("nodePath",BODY+"/"+W+"r[2]/"+W+"footnoteReference[1]");
        second.put("location","body/0/paragraph/node/2");fixture.body.getWordNativeObjects().add(second);
        SourceDocument source=fixture.source();NormalSourceCandidateGraph.capture(source);
        JsonNode notes=graphPart(NormalSourceCandidateGraph.requireBound(source),"body:0:paragraph").path("readingContext").path("nativeWordNotes");
        assertEquals(2,notes.path("entries").size());
        for(JsonNode entry:notes.path("entries")){assertEquals("resolved",entry.path("status").asText());assertEquals(NOTE,entry.path("target").path("text").asText());}
        assertNotEquals(notes.path("entries").get(0).path("reference"),notes.path("entries").get(1).path("reference"));
    }

    @Test void actualNormalGraphProducerCarriesAllRevisionChannelsThroughTheCanonicalModelMapper()throws Exception{
        Fixture fixture=new Fixture("footnote");fixture.body.setWordNativeObjects(new ArrayList<>());
        fixture.body.setText("Effective body.");fixture.body.setOriginalText("Deleted body. Effective body.");fixture.body.setDeletedText("Deleted body.");fixture.body.setStrikeText("Struck body.");
        SourceDocument source=fixture.source();NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        JsonNode part=graphPart(graph,"body:0:paragraph"),observation=part.path("wordObservation");
        assertEquals(fixture.body.getText(),observation.path("effectiveText").asText());
        assertEquals(fixture.body.getOriginalText(),observation.path("originalText").asText());
        assertEquals(fixture.body.getDeletedText(),observation.path("deletedText").asText());
        assertEquals(fixture.body.getStrikeText(),observation.path("strikeText").asText());
        JsonNode model=normalModel(graph,part);assertEquals(observation,model.path("parts").get(0).path("wordObservation"));
        SourceJsonWireAssertions.assertRoundTrip(model);
        assertEquals("Effective body.",model.path("parts").get(0).path("text").asText(),"Deleted/struck channels never become effective obligations");
    }

    private static JsonNode graphPart(JsonNode graph,String id){
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(id.equals(part.path("blockId").asText()))return part;
        throw new AssertionError("Actual producer part missing: "+id);
    }
    /** Invoke the same Java normal mapper used by full-envelope validation, never patch its part. */
    private static JsonNode normalModel(JsonNode graph,JsonNode sourcePart)throws Exception{
        JsonNode unit=null;for(JsonNode value:graph.path("units"))for(JsonNode part:value.path("parts"))if(part==sourcePart)unit=value;
        assertNotNull(unit);ObjectNode identity=JsonUtils.mapper().createObjectNode();identity.put("documentId","99").put("role","tender").put("module","vetting").put("originalSourceSha256",SHA);
        Method mapper=NormalObservedContract.class.getDeclaredMethod("chunk",JsonNode.class,JsonNode.class,JsonNode.class,String.class,String.class,String.class,String.class);mapper.setAccessible(true);
        JsonNode canonical=(JsonNode)mapper.invoke(null,unit,graph,identity,VettingCorpus.hash("canonical source"),VettingCorpus.hash("revision"),VettingCorpus.hash("projection revision"),VettingCorpus.hash("manifest"));
        NormalObservedContract.validateChunk(canonical);
        EvidencePointer pointer=new EvidencePointer();pointer.setProducerKind(NormalObservedContract.PRODUCER);pointer.setProjectionSchema(NormalObservedContract.SCHEMA);pointer.setProjectId("authored");pointer.setDocumentId(99L);pointer.setModule("vetting");pointer.setOriginalSourceSha256(SHA);
        pointer.setSourceRevisionSha256(VettingCorpus.hash("revision"));pointer.setCanonicalSourceHash(VettingCorpus.hash("canonical source"));pointer.setProjectionSha256(VettingCorpus.hash("projection"));pointer.setProjectionRevisionSha256(VettingCorpus.hash("projection revision"));
        pointer.setUnitId(unit.path("unitId").asText());pointer.setCanonicalChunkId(canonical.path("id").asText());pointer.setParts(canonical.path("parts"));pointer.setSourceSpans(canonical.path("sourceSpans"));pointer.setTableGroup(canonical.get("tableGroup"));
        pointer.setPhysicalPages(Collections.emptyList());pointer.setSourceOrder(canonical.get("sourceOrder"));pointer.setRelations(canonical.path("relations"));pointer.setAssetRefs(canonical.path("assetRefs"));pointer.setUnknowns(graph.path("unknowns"));pointer.setSourceQuality(canonical.path("sourceQuality"));
        return new OerModelMaterial.Request().project(pointer,canonical,"tender");
    }

    private static final class Fixture{
        final String kind;final DocumentBlock body,note;final ObjectNode reference;final List<DocumentBlock> blocks=new ArrayList<>();String sourceSha=SHA;
        Fixture(String kind){
            this.kind=kind;body=block("body:0:paragraph","body/0/paragraph","paragraph","Referring body.",position("word/document.xml",BODY_SHA,BODY));
            String part="word/"+kind+"s.xml",root="/"+W+kind+"s[1]/"+W+kind+"[1]";
            note=block("word-"+kind+":7","word-"+kind+"/7",kind,NOTE,position(part,NOTE_SHA,root));
            note.setOriginalText("Original note with deletion. "+NOTE);note.setDeletedText("Original note with deletion.");
            reference=JsonUtils.mapper().createObjectNode();reference.put("kind",kind+"Reference").put("location","body/0/paragraph/node/1").put("status","native_note_reference_resolved").put("semanticStatus","unknown").put("deleted",false).put("struck",false).put("sourcePart","/word/document.xml");
            ObjectNode ref=reference.putObject("noteReference");ref.put("rawId","7").put("bindingStatus","resolved");ref.putObject("rawAttributes").put(W+"id","7");
            ref.set("sourcePosition",position("word/document.xml",BODY_SHA,BODY+"/"+W+"r[1]/"+W+kind+"Reference[1]"));ref.set("targetSourcePosition",note.getSourcePosition().deepCopy());
            ObjectNode relationship=ref.putObject("relationship");relationship.put("id","rId6").put("type","http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+kind+"s").put("rawTarget",kind+"s.xml").putNull("rawTargetMode");
            relationship.set("sourcePosition",position("word/_rels/document.xml.rels",REL_SHA,"/"+R+"Relationships[1]/"+R+"Relationship[1]"));
            body.setWordNativeObjects(new ArrayList<>(Collections.singletonList(reference)));blocks.add(body);blocks.add(note);
        }
        SourceDocument source(){SourceDocument source=new SourceDocument();source.setId(99L);source.setProjectId("authored");source.setFileName("authored-notes.docx");source.setFileKey("PRE");source.setReviewRole("tender");source.setDocumentScope("fullset");source.setParseStatus("PARSED");source.setOcrUsed(false);source.setSourceSha256(sourceSha);source.setTextContent(String.join("\n",blocks.stream().map(DocumentBlock::getText).toArray(String[]::new)));source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson("{\"complete\":true}");return source;}
        void mutate(String value){ObjectNode ref=(ObjectNode)reference.path("noteReference");
            if(value.equals("missing_source_sha"))sourceSha=null;
            if(value.equals("reference_foreign_source"))((ObjectNode)ref.path("sourcePosition")).put("sourceSha256",VettingCorpus.hash("foreign"));
            if(value.equals("target_foreign_source"))((ObjectNode)ref.path("targetSourcePosition")).put("sourceSha256",VettingCorpus.hash("foreign"));
            if(value.equals("body_part_sha_mismatch"))((ObjectNode)ref.path("sourcePosition")).put("partSha256",VettingCorpus.hash("foreign"));
            if(value.equals("target_missing_part_sha"))((ObjectNode)ref.path("targetSourcePosition")).remove("partSha256");
            if(value.equals("target_wrong_part"))((ObjectNode)ref.path("targetSourcePosition")).put("partName","word/endnotes.xml");
            if(value.equals("target_wrong_node"))((ObjectNode)ref.path("targetSourcePosition")).put("nodePath","/"+W+"footnotes[1]/"+W+"footnote[9]");
            if(value.equals("wrong_note_id"))ref.put("rawId","8");
            if(value.equals("duplicate_target"))blocks.add(JsonUtils.read(JsonUtils.write(note),DocumentBlock.class));
            if(value.equals("unloaded_target"))blocks.remove(note);
            if(value.equals("deleted_reference"))reference.put("deleted",true);
            if(value.equals("struck_reference"))reference.put("struck",true);
            if(value.equals("unresolved_reference"))ref.put("bindingStatus","unknown").put("reason","duplicate_note_id");
            if(value.equals("external_relationship"))((ObjectNode)ref.path("relationship")).put("rawTargetMode","External");
            if(value.equals("wrong_relationship_target"))((ObjectNode)ref.path("relationship")).put("rawTarget","endnotes.xml");
            if(value.equals("reference_outside_parent"))((ObjectNode)ref.path("sourcePosition")).put("nodePath",BODY.replace("p[1]","p[2]")+"/"+W+"r[1]/"+W+"footnoteReference[1]");
            if(value.equals("wrong_note_kind"))reference.put("kind","endnoteReference");
            if(value.equals("duplicate_reference"))body.getWordNativeObjects().add(reference.deepCopy());
        }
    }
    private static DocumentBlock block(String id,String location,String kind,String text,JsonNode position){DocumentBlock out=new DocumentBlock();out.setId(id);out.setLocation(location);out.setKind(kind);out.setSource("docx");out.setWordStructureVersion("docx-numbering-symbol-v1");out.setText(text);out.setOriginalText(text);out.setDeletedText("");out.setStrikeText("");out.setSourcePosition(position);return out;}
    private static ObjectNode position(String part,String sha,String path){return JsonUtils.mapper().createObjectNode().put("kind","docx_native").put("sourceSha256",SHA).put("partName",part).put("partSha256",sha).put("nodePath",path);}
}
