package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordSourceClauseConsumptionTest {
    private static final String SHA=VettingCorpus.hash("authored source"),PART=VettingCorpus.hash("authored part"),NS="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}",BODY="/"+NS+"document[1]/"+NS+"body[1]";
    @Test void actualWordOptionsRetainNativeSpacersAndPassStrictSourceContainment() throws Exception {
        byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            for(String text:Arrays.asList("Special Conditions of Contract","SCC11.302 Schedule of rates","[Guidance Note: Select either Option A or Option B as appropriate.]","Option A","(1) FIRST_BRANCH_BODY","","First branch continuation.","Option B","(1) SECOND_BRANCH_BODY","SCC11.303 Next clause")){org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph=doc.createParagraph();if(!text.isEmpty())paragraph.createRun().setText(text);}doc.write(out);bytes=out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),org.mockito.Mockito.mock(OcrClient.class)).parse("authored.docx",bytes);assertFalse(parsed.isOcrUsed());
        SourceDocument source=new SourceDocument();source.setId(78L);source.setProjectId("actual-word-options");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setFileKey("SCC");source.setFileName("authored.docx");source.setSourceSha256(parsed.getSourceSha256());source.setParseStatus("PARSED");source.setPageCount(0);source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));String coverage=source.getParseCoverageJson();NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source),part=null;for(JsonNode unit:graph.path("units"))for(JsonNode candidate:unit.path("parts"))if(candidate.path("text").asText().contains("FIRST_BRANCH_BODY"))part=candidate;assertNotNull(part);ArrayNode selectedParts=JsonUtils.mapper().createArrayNode().add(part.deepCopy());JsonNode selected=NormalSourceCandidateGraph.wordSourceScopeContext(graph,selectedParts);assertTrue(selected.path("issues").isEmpty(),selected.toString());assertFalse(selected.path("ranges").isEmpty());
        boolean spacer=false;for(JsonNode frame:selected.path("referenceFrames"))if("option:a".equals(frame.path("label").asText()))for(JsonNode id:frame.path("orderedMemberIds")){JsonNode observed=member(selected,id.asText());if(observed.path("nativeContentEmpty").isBoolean()&&observed.path("nativeContentEmpty").booleanValue())spacer=true;assertNotEquals("Option B",observed.path("textObservation").path("text").asText().trim());}assertTrue(spacer,selected.toString());
        for(JsonNode range:selected.path("ranges"))if(range.has("sourceFrameId")){assertTrue(range.path("scopeComplete").asBoolean());for(JsonNode id:range.path("orderedMemberIds"))assertFalse(member(selected,id.asText()).path("textObservation").path("text").asText().contains("SECOND_BRANCH_BODY"));}SourceJsonWireAssertions.assertRoundTrip(selected);assertEquals(coverage,source.getParseCoverageJson());
    }
    @Test void partialParagraphHitCarriesNarrowestClauseAndRealParentWithOrderedEmptyAndSeparateContext(){
        SourceDocument source=source();VettingCorpus.Chunk hit=hit(source);String before=JsonUtils.write(hit);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")));
        assertEquals(Arrays.asList("m2","m3","m4"),strings(selected.path("ranges").get(0).path("orderedMemberIds")));
        assertEquals(7,selected.path("members").size());assertEquals("",member(selected,"m3").path("textObservation").path("text").asText());
        assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));assertTrue(JsonUtils.write(selected).contains("PARENT_SIBLING"));assertTrue(JsonUtils.write(selected).contains("SEPARATE_CONTEXT"));assertFalse(JsonUtils.write(selected).contains("OUTSIDE_CHAPTER"));
        assertEquals(Collections.singletonList("m7"),strings(selected.path("ranges").get(0).path("contextMemberIds")));
        assertEquals(before,JsonUtils.write(hit));assertEquals(source.getSourceSha256(),selected.path("sourceSha256").asText());
    }
    @Test void plainWordScopeBodyAndContextReachOrdinaryModelAndLosslessCompactWireWithoutChangingQuotation(){
        SourceDocument source=source();VettingCorpus.Chunk hit=hit(source);String content=hit.getContent();OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));
        JsonNode model=request.projectOrdinary(hit);assertNotNull(model,"Plain Word clauses must not return early because no table inventory exists");
        assertEquals(2,model.path("units").size());JsonNode narrow=model.path("units").get(0);
        assertEquals("ordinary_docx_source_clause",narrow.path("kind").asText());assertEquals(3,narrow.path("members").size());assertEquals(1,narrow.path("contextMembers").size());
        assertTrue(narrow.path("text").asText().contains("CHILD_TAIL"));assertFalse(narrow.path("text").asText().contains("SEPARATE_CONTEXT"));assertEquals("SEPARATE_CONTEXT",narrow.path("contextMembers").get(0).path("textObservation").path("text").asText());
        assertTrue(narrow.path("parts").isEmpty());assertFalse(narrow.path("semanticScopeVerified").asBoolean());assertEquals("unknown",narrow.path("legalClosure").asText());
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source)));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertTrue(JsonUtils.write(ordinary).contains("PARENT_SIBLING"));assertTrue(JsonUtils.write(ordinary).contains("SEPARATE_CONTEXT"));
        assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));assertEquals(content,hit.getContent());
        assertTrue(request.projectOrdinary(hit).path("units").get(0).has("modelUnitRef"));
    }
    @Test void incompleteNarrowestScopeDoesNotExpandToItsCompleteParent(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(1)).put("scopeComplete",false).put("status","unknown_boundary");
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
        assertTrue(selected.path("ranges").isEmpty());assertFalse(selected.path("issues").isEmpty());assertFalse(JsonUtils.write(selected).contains("CHILD_TAIL"));
    }
    @Test void explicitSourceBranchesDisambiguateOnlyThePositionSelectedLocalRangeAndPreserveTheirEvidence(){
        SourceDocument source=branchSource();VettingCorpus.Chunk hit=hit(source);String quotation=hit.getContent(),coverage=source.getParseCoverageJson();JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")),selected.toString());assertTrue(selected.path("issues").isEmpty());assertEquals(Arrays.asList("frame-a","frame-b"),ids(selected.path("referenceFrames")));assertEquals(8,selected.path("members").size());
        assertEquals("frame-a",selected.path("ranges").get(0).path("sourceFrameId").asText());assertEquals("Option A",member(selected,"m1").path("textObservation").path("text").asText());assertEquals("Option B",member(selected,"m5").path("textObservation").path("text").asText());
        JsonNode canonical=NormalSourceCandidateGraph.wordSourceScopeContext(NormalSourceCandidateGraph.requireBound(source),JsonUtils.mapper().valueToTree(hit.getParts()));for(String field:Arrays.asList("ranges","members","referenceFrames","issues"))assertEquals(selected.path(field),canonical.path(field));
        JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit),unit=model.path("units").get(0);assertEquals(selected.path("referenceFrames"),unit.path("referenceFrames"));assertEquals(8,unit.path("referenceFrameMembers").size());assertEquals("frame-a",unit.path("range").path("sourceFrameId").asText());assertFalse(unit.path("semanticScopeVerified").asBoolean());assertEquals("unknown",unit.path("legalClosure").asText());
        SourceJsonWireAssertions.assertRoundTrip(model);assertEquals(quotation,hit.getContent());assertEquals(coverage,source.getParseCoverageJson());
    }
    @Test void missingInvalidDuplicateOrUnboundBranchEvidenceNeverBypassesARepeatedReference(){
        for(int defect=0;defect<21;defect++){
            SourceDocument source=branchSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),first=(ObjectNode)scopes.path("referenceFrames").get(0),second=(ObjectNode)scopes.path("referenceFrames").get(1),range=(ObjectNode)scopes.path("ranges").get(1);
            if(defect==0)range.remove("sourceFrameId");
            if(defect==1)first.put("scopeComplete",false);
            if(defect==2)first.put("status","partial_unknown_scope");
            if(defect==3)first.put("automaticEditEligible",true);
            if(defect==4)first.put("semanticApplicabilityVerified",true);
            if(defect==5)first.put("kind","guessed_branch");
            if(defect==6)first.put("label","option:b");
            if(defect==7)first.putArray("anchorMemberIds").add("m7");
            if(defect==8)first.putArray("orderedMemberIds").add("m1").add("m2");
            if(defect==9)first.putArray("orderedMemberIds").add("m2").add("m1").add("m3").add("m4");
            if(defect==10)((ArrayNode)scopes.path("referenceFrames")).add(first.deepCopy());
            if(defect==11)((ObjectNode)first.path("endBoundary")).set("sourcePosition",position(6));
            if(defect==12)((ObjectNode)first.path("startBoundary")).put("memberId","m2").set("sourcePosition",position(2));
            if(defect==13){ObjectNode other=position(7);other.put("sourceSha256",VettingCorpus.hash("foreign"));((ObjectNode)member(scopes,"m7")).set("sourcePosition",other);}
            if(defect==14)((ObjectNode)member(scopes,"m1")).put("storyKind","header");
            if(defect==15){((ObjectNode)scopes.path("ranges").get(2)).put("sourceFrameId","frame-a");}
            if(defect==16)second.put("label","option:a");
            if(defect==17)first.putArray("anchorMemberIds").add("m1").add("m1");
            if(defect==18)first.putObject("endBoundary").put("kind","observed_complete_parallel_source_subitem_paragraph_end").put("memberId","m4").put("offset",0).set("sourcePosition",position(4));
            if(defect==20)range.putNull("sourceFrameId");
            if(defect==19)range.put("sourceFrameId","missing-frame");
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");assertTrue(selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertTrue(selected.path("referenceFrames").isEmpty(),"defect "+defect);assertFalse(selected.path("issues").isEmpty(),"defect "+defect);
        }
    }
    @Test void unusedMalformedBranchDeclarationsDoNotChangeTheEstablishedUnframedPath(){
        SourceDocument source=source();JsonNode expected=hit(source).getCandidateStructure().path("sourceClauseScopes");ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes")).putArray("referenceFrames").addObject().put("id","broken").put("scopeComplete",true);source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);assertEquals(expected,hit(source).getCandidateStructure().path("sourceClauseScopes"));
    }
    @Test void nativeParallelAlternativeAnnotationsAndExactParagraphEndsRemainSourceBound(){
        for(boolean foreignRow:Arrays.asList(false,true)){
            SourceDocument source=branchSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),first=(ObjectNode)scopes.path("referenceFrames").get(0),second=(ObjectNode)scopes.path("referenceFrames").get(1),child=(ObjectNode)scopes.path("ranges").get(1);
            ObjectNode annotation=position(1),body=position(2);annotation.put("nodePath",BODY+"/"+NS+"tbl[1]/"+NS+"tr["+(foreignRow?2:1)+"]/"+NS+"tc[3]/"+NS+"p[1]");body.put("nodePath",BODY+"/"+NS+"tbl[1]/"+NS+"tr[1]/"+NS+"tc[2]/"+NS+"p[1]");
            ((ObjectNode)member(scopes,"m1")).set("sourcePosition",annotation);((ObjectNode)member(scopes,"m2")).set("sourcePosition",body);for(String channel:Arrays.asList("text","originalText")){((ObjectNode)member(scopes,"m1").path("textObservation")).put(channel,"(Alternative (a) - for tenders)");((ObjectNode)member(scopes,"m5").path("textObservation")).put(channel,"(Alternative (b) - for other tenders)");}
            first.put("label","alternative:a");second.put("label","alternative:b");first.putArray("orderedMemberIds").add("m2").add("m3").add("m4");first.putArray("contextMemberIds").add("m1").add("m7");first.putObject("startBoundary").put("kind","native_row_start_of_explicit_option_annotation").put("memberId","m2").set("sourcePosition",body);first.putObject("endBoundary").put("kind","observed_complete_parallel_source_subitem_paragraph_end").put("memberId","m4").put("offset","CHILD_TAIL".length()).set("sourcePosition",position(4));((ObjectNode)child.path("startBoundary")).set("sourcePosition",body);((ObjectNode)scopes.path("ranges").get(0).path("startBoundary")).set("sourcePosition",annotation);
            List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks){if("b1".equals(block.getId())){block.setText("(Alternative (a) - for tenders)");block.setSourcePosition(annotation);}if("b2".equals(block.getId()))block.setSourcePosition(body);if("b5".equals(block.getId()))block.setText("(Alternative (b) - for other tenders)");}source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");assertEquals(foreignRow,selected.path("ranges").isEmpty(),selected.toString());assertEquals(foreignRow,!selected.path("issues").isEmpty(),selected.toString());
        }
    }
    @Test void explicitListIntroductionsHaveLocalSourceNamespacesWithoutTurningRealDuplicatesIntoGlobalReferences(){
        for(int defect=-3;defect<8;defect++){
            SourceDocument source=branchSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),frame=(ObjectNode)scopes.path("referenceFrames").get(0);for(String channel:Arrays.asList("text","originalText"))((ObjectNode)member(scopes,"m1").path("textObservation")).put(channel,"The following defects are covered due to :"+(defect==-3?"–":defect==-2?"—":"-"));
            frame.put("kind","explicit_native_list_introduction").put("label","source-list:m1");frame.putArray("orderedMemberIds").add("m2").add("m3").add("m4");frame.putArray("contextMemberIds").add("m1").add("m7");frame.putObject("startBoundary").put("kind","first_native_list_item_after_explicit_introduction").put("memberId","m2").set("sourcePosition",position(2));
            if(defect==0)frame.put("label","source-list:m7");if(defect==1)((ObjectNode)member(scopes,"m1").path("textObservation")).put("text","The following paragraph is not an explicit list");if(defect==2)frame.putArray("contextMemberIds").add("m7");if(defect==3)frame.putArray("anchorMemberIds").add("m7");if(defect==4)frame.putArray("anchorMemberIds").add("m1").add("m7");if(defect==5)frame.putArray("orderedMemberIds").add("m1").add("m2").add("m3").add("m4");if(defect==6)((ObjectNode)member(scopes,"m1").path("sourcePosition")).put("partSha256",VettingCorpus.hash("other-part"));if(defect==7)((ObjectNode)scopes.path("referenceFrames").get(1)).put("label","source-list:m1");
            List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks)if("b1".equals(block.getId()))block.setText(member(scopes,"m1").path("textObservation").path("text").asText());source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");assertEquals(defect>=0,selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertEquals(defect>=0,!selected.path("issues").isEmpty(),"defect "+defect);
        }
    }
    @Test void nativeContainerEndNeedsTheActualSameSourceAncestorAndNeverAnInventedMember(){
        for(int defect=-1;defect<7;defect++){
            SourceDocument source=branchSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),frame=(ObjectNode)scopes.path("referenceFrames").get(1);ObjectNode root=position(8);root.put("nodePath",BODY);ObjectNode end=frame.putObject("endBoundary");end.put("kind","native_container_end").set("sourcePosition",root);
            if(defect==0)root.put("nodePath","");if(defect==1)root.put("nodePath",BODY+"/"+NS+"tbl[99]");if(defect==2)root.put("sourceSha256",VettingCorpus.hash("other-source"));if(defect==3)root.put("partSha256",VettingCorpus.hash("other-part"));if(defect==4)end.put("memberId","m8");if(defect==5)end.put("kind","source_story_end_unverified");if(defect==6)frame.remove("endBoundary");
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");assertEquals(defect>=0,selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertEquals(defect>=0,!selected.path("issues").isEmpty(),"defect "+defect);
        }
    }
    private static SourceDocument branchSource(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");for(String channel:Arrays.asList("text","originalText")){((ObjectNode)member(scopes,"m1").path("textObservation")).put(channel,"Option A");((ObjectNode)member(scopes,"m5").path("textObservation")).put(channel,"Option B");}
        List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks){if("b1".equals(block.getId()))block.setText("Option A");if("b5".equals(block.getId()))block.setText("Option B");}source.setStructuredContentJson(JsonUtils.write(blocks));
        ((ObjectNode)scopes.path("ranges").get(1)).put("sourceFrameId","frame-a");((ObjectNode)scopes.path("ranges").get(2)).put("sourceFrameId","frame-b").put("reference","PRE1(a)");ArrayNode frames=scopes.putArray("referenceFrames");
        for(int n=0;n<2;n++){ObjectNode frame=frames.addObject();frame.put("id",n==0?"frame-a":"frame-b").put("kind","explicit_native_branch").put("label",n==0?"option:a":"option:b").put("status","source_syntax_complete").put("scopeComplete",true).put("automaticEditEligible",false).put("semanticApplicabilityVerified",false);frame.set("orderedMemberIds",JsonUtils.mapper().valueToTree(n==0?Arrays.asList("m1","m2","m3","m4"):Arrays.asList("m5","m6")));frame.putArray("contextMemberIds").add("m7");frame.putArray("anchorMemberIds").add(n==0?"m1":"m5");frame.putObject("startBoundary").put("kind","standalone_native_body_option_heading").put("memberId",n==0?"m1":"m5").put("offset",0).set("sourcePosition",position(n==0?1:5));frame.putObject("endBoundary").put("kind",n==0?"next_standalone_native_body_option_heading":"next_same_or_parent_level_native_clause").put("memberId",n==0?"m5":"m8").set("sourcePosition",position(n==0?5:8));}
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    @Test void repeatedBoundNativeBulletGlyphsReachBothConsumersWithoutClaimingDuplicateOrdinals(){
        for(String glyph:Arrays.asList("•","-")){
            SourceDocument source=bulletSource(glyph);VettingCorpus.Chunk hit=hit(source);String quotation=hit.getContent();JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
            assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")),selected.toString());assertTrue(selected.path("issues").isEmpty());
            JsonNode canonical=NormalSourceCandidateGraph.wordSourceScopeContext(NormalSourceCandidateGraph.requireBound(source),JsonUtils.mapper().valueToTree(hit.getParts()));
            for(String field:Arrays.asList("ranges","members","issues"))assertEquals(selected.path(field),canonical.path(field));
            JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit);assertEquals(2,model.path("units").size());assertEquals("nonordinal_glyph",model.path("units").get(0).path("range").path("sourceReferenceKind").asText());
            SourceJsonWireAssertions.assertRoundTrip(model);assertEquals(quotation,hit.getContent());
        }
    }
    @Test void aGlyphClaimCannotBypassDuplicateReferenceChecksWithoutMatchingResolvedNativeBulletEvidence(){
        for(int defect=0;defect<5;defect++){
            SourceDocument source=bulletSource("•");ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");ObjectNode first=(ObjectNode)scopes.path("ranges").get(1),peer=(ObjectNode)scopes.path("ranges").get(2);
            if(defect==0)first.put("kind","manual-sequence");
            if(defect==1)((ObjectNode)first.path("numbering")).put("format","decimal");
            if(defect==2)((ObjectNode)member(scopes,"m2").path("paragraphProperties").path("layoutEvidence").path("numbering")).put("label","-");
            if(defect==3)((ObjectNode)peer.path("numbering")).put("resolutionStatus","unknown");
            if(defect==4)peer.remove("sourceReferenceKind");
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertFalse(selected.path("issues").isEmpty());
        }
    }
    private static SourceDocument bulletSource(String glyph){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");
        for(int i=1;i<=2;i++){
            ObjectNode range=(ObjectNode)scopes.path("ranges").get(i);range.put("kind","native-list").put("reference","1"+glyph).put("sourceReferenceKind","nonordinal_glyph");
            ObjectNode number=range.putObject("numbering");number.put("numbered",true).put("resolutionStatus","resolved").put("numId","7").put("level",0).put("format","bullet").put("label",glyph);
            ((ObjectNode)member(scopes,i==1?"m2":"m5")).putObject("paragraphProperties").putObject("layoutEvidence").set("numbering",number.deepCopy());
        }
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    @Test void aBoundLiteralDashAndNativeBulletRemainNonordinalPeersThroughBothConsumers(){
        SourceDocument source=mixedBulletSource();VettingCorpus.Chunk hit=hit(source);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")),selected.toString());assertTrue(selected.path("issues").isEmpty());
        JsonNode canonical=NormalSourceCandidateGraph.wordSourceScopeContext(NormalSourceCandidateGraph.requireBound(source),JsonUtils.mapper().valueToTree(hit.getParts()));
        for(String field:Arrays.asList("ranges","members","issues"))assertEquals(selected.path(field),canonical.path(field));
        SourceJsonWireAssertions.assertRoundTrip(new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit));
    }
    @Test void aLiteralGlyphExceptionRequiresItsOwnBoundRawTextAndNonNumberedSource(){
        for(int defect=0;defect<4;defect++){
            SourceDocument source=mixedBulletSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),literal=(ObjectNode)member(scopes,"m5"),peer=(ObjectNode)scopes.path("ranges").get(2);
            if(defect==0)((ObjectNode)literal.path("paragraphProperties").path("layoutEvidence").path("numbering")).put("numbered",true);
            if(defect==1)((ObjectNode)literal.path("preNormalizedTextObservation")).put("text","1. PARENT_SIBLING\n");
            if(defect==2)((ObjectNode)literal.path("preNormalizedTextObservation")).put("status","unknown");
            if(defect==3)peer.put("basis","unproven_glyph_guess");
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertFalse(selected.path("issues").isEmpty());
        }
    }
    private static SourceDocument mixedBulletSource(){
        SourceDocument source=bulletSource("-");ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes"),literal=(ObjectNode)member(scopes,"m5"),peer=(ObjectNode)scopes.path("ranges").get(2);
        peer.put("kind","source-region").put("basis","explicit_literal_list_label_and_bound_source_frame").remove("numbering");
        literal.putObject("paragraphProperties").putObject("layoutEvidence").putObject("numbering").put("numbered",false);
        ObjectNode raw=literal.putObject("preNormalizedTextObservation");raw.put("status","observed_parser_channels_before_whitespace_normalization").put("text","- PARENT_SIBLING\n");
        ((ObjectNode)literal.path("textObservation")).put("text","- PARENT_SIBLING").put("originalText","- PARENT_SIBLING");
        List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks)if("b5".equals(block.getId()))block.setText("- PARENT_SIBLING");
        source.setTextContent(source.getTextContent().replace("PARENT_SIBLING","- PARENT_SIBLING"));source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    @Test void completeChildSurvivesPartialParentWhoseUnknownBodyDoesNotClaimChildMembership(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode chapter=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(0);
        chapter.put("scopeComplete",false).put("status","partial_unknown_scope");chapter.set("orderedMemberIds",JsonUtils.mapper().valueToTree(Collections.singletonList("m1")));
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);VettingCorpus.Chunk hit=hit(source);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Collections.singletonList("child-a"),ids(selected.path("ranges")),"An incomplete parent's unknown body cannot invalidate the complete source-bound child");
        assertFalse(selected.path("issues").isEmpty());assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));assertFalse(JsonUtils.write(selected).contains("PARENT_SIBLING"));
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source)));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertFalse(JsonUtils.write(ordinary).contains("PARENT_SIBLING"));assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));
    }
    @Test void partialCarrierUsesOnlyTheExactBoundUniqueParagraphRange(){
        SourceDocument source=carrierSource();VettingCorpus.Chunk hit=hit(source);String before=JsonUtils.write(hit);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")));assertEquals("",member(selected,"m3").path("textObservation").path("text").asText());assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));
        JsonNode ordinary=JsonUtils.parse(JsonUtils.write(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source))));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));assertEquals(before,JsonUtils.write(hit));
    }
    @Test void nativeContainerEndUsesTheActualAncestorWithoutInventingAMember(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode boundary=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(1).path("endBoundary");boundary.put("kind","native_container_end").remove("memberId");ObjectNode body=position(1);body.put("nodePath",BODY);boundary.set("sourcePosition",body);
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);assertEquals(Arrays.asList("child-a","chapter"),ids(hit(source).getCandidateStructure().path("sourceClauseScopes").path("ranges")));
    }
    @Test void duplicateForeignOrUnboundParagraphRangesNeverExpandPartialCarrier(){
        for(int kind=0;kind<9;kind++){
            SourceDocument source=carrierSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");ObjectNode first=(ObjectNode)member(scopes,"m2");ArrayNode ranges=(ArrayNode)first.path("retainedBlockRanges");ObjectNode range=(ObjectNode)ranges.get(0);
            if(kind==0)ranges.add(range.deepCopy());if(kind==1)range.put("parentEffectiveTextSha256",VettingCorpus.hash("other carrier"));if(kind==2)range.put("startOffset",1);if(kind==3)range.put("offsetUnit","bytes");if(kind==4)range.put("textChannel","originalText");if(kind==5)first.set("sourcePosition",position(2));
            if(kind==6){List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks)if("b2".equals(block.getId())){String duplicate=block.getEffectiveText()+"\n"+first.path("textObservation").path("text").asText();block.setText(duplicate);for(JsonNode m:scopes.path("members"))for(JsonNode r:m.path("retainedBlockRanges"))((ObjectNode)r).put("parentEffectiveTextSha256",VettingCorpus.hash(duplicate));}source.setStructuredContentJson(JsonUtils.write(blocks));}
            if(kind==7){ObjectNode tail=(ObjectNode)member(scopes,"m4");((ObjectNode)tail.path("textObservation")).put("text",first.path("textObservation").path("text").asText());tail.set("retainedBlockRanges",first.path("retainedBlockRanges").deepCopy());}
            if(kind==8){ArrayNode members=(ArrayNode)scopes.path("members");JsonNode earlier=members.get(1),later=members.get(3);members.set(1,later);members.set(3,earlier);}
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"Unsafe paragraph-carrier range must not expand, case "+kind);assertFalse(selected.path("issues").isEmpty(),"Unsafe paragraph-carrier mapping must remain explicit unknown, case "+kind);
        }
    }
    @Test void wrongSourcePartStoryDuplicateReferenceAndDanglingParentsNeverExpand(){
        for(int kind=0;kind<9;kind++){
            SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");
            if(kind==0)scopes.put("sourceSha256",VettingCorpus.hash("wrong original"));
            if(kind==1)((ObjectNode)scopes.path("members").get(3).path("sourcePosition")).put("partSha256",VettingCorpus.hash("other part"));
            if(kind==2)((ObjectNode)scopes.path("members").get(3)).put("storyKind","header");
            if(kind==3){ObjectNode duplicate=scopes.path("ranges").get(1).deepCopy();duplicate.put("id","duplicate-clause");((ArrayNode)scopes.path("ranges")).add(duplicate);}
            if(kind==4)((ObjectNode)scopes.path("ranges").get(1)).put("parentId","missing-parent");
            if(kind==5)((ObjectNode)scopes.path("ranges").get(1)).remove("endBoundary");
            if(kind==6)((ObjectNode)scopes.path("ranges").get(1).path("endBoundary")).put("memberId","m7");
            if(kind==7)((ObjectNode)scopes.path("ranges").get(0)).set("orderedMemberIds",JsonUtils.mapper().valueToTree(Collections.singletonList("m1")));
            if(kind==8){ObjectNode boundary=(ObjectNode)scopes.path("ranges").get(1).path("endBoundary");boundary.put("kind","native_container_end").remove("memberId");ObjectNode foreign=position(8);foreign.put("nodePath",BODY+"/"+NS+"tbl[2]/"+NS+"tr[1]/"+NS+"tc[9]");boundary.set("sourcePosition",foreign);}
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"Unsafe source clause inventory must not expand, case "+kind);assertFalse(selected.path("issues").isEmpty(),"Unknown must be explicit, case "+kind);
        }
    }
    @Test void explicitPhysicalClauseCrossesNativeHeadingsWithoutSwallowingTheNextClause(){
        SourceDocument source=crossHeadingSource();String coverage=source.getParseCoverageJson();VettingCorpus.Chunk hit=hit(source);String quotation=hit.getContent();
        JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");assertEquals(Collections.singletonList("physical-3"),ids(selected.path("ranges")));assertTrue(selected.path("issues").isEmpty(),selected.toString());
        assertEquals(Arrays.asList("m2","m3","m4","m5"),strings(selected.path("ranges").get(0).path("orderedMemberIds")));assertEquals(4,selected.path("members").size());
        JsonNode canonical=NormalSourceCandidateGraph.wordSourceScopeContext(NormalSourceCandidateGraph.requireBound(source),JsonUtils.mapper().valueToTree(hit.getParts()));
        for(String field:Arrays.asList("ranges","members","issues"))assertEquals(selected.path(field),canonical.path(field));
        JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit);assertEquals(1,model.path("units").size());String body=model.path("units").get(0).path("text").asText();assertTrue(body.contains("CHILD_TAIL"));assertTrue(body.contains("PARENT_SIBLING"));assertFalse(body.contains("SIBLING_TAIL"));assertFalse(body.contains("OUTSIDE_CHAPTER"));
        SourceJsonWireAssertions.assertRoundTrip(model);assertEquals(coverage,source.getParseCoverageJson());assertEquals(quotation,hit.getContent());
    }
    @Test void malformedOrCompetingCrossHeadingPhysicalClausesNeverExpandOrFallBackToNativeParents(){
        for(int defect=0;defect<16;defect++){
            SourceDocument source=crossHeadingSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");ObjectNode physical=(ObjectNode)scopes.path("ranges").get(2);
            if(defect==0)physical.putArray("nativeOutlineRangeIds").add("native-a");
            if(defect==1)physical.putArray("nativeOutlineRangeIds").add("native-a").add("missing");
            if(defect==2)physical.withArray("nativeOutlineRangeIds").add("native-a");
            if(defect==3)((ObjectNode)member(scopes,"m4")).put("ordinalZeroBased",20);
            if(defect==4)physical.set("orderedMemberIds",JsonUtils.mapper().valueToTree(Arrays.asList("m2","m4","m5")));
            if(defect==5){physical.putObject("endBoundary").put("kind","next_explicit_number_in_source_sequence").put("memberId","m5").set("sourcePosition",position(5));}
            if(defect==6)physical.put("basis","unproven_cross_heading_guess");
            if(defect==7)physical.put("parentId","native-a");
            if(defect==8){ObjectNode duplicate=physical.deepCopy();duplicate.put("id","competing-physical");((ArrayNode)scopes.path("ranges")).add(duplicate);}
            if(defect==9)physical.put("scopeComplete",false).put("status","partial_unknown_scope");
            if(defect==10){ObjectNode duplicate=physical.deepCopy();duplicate.put("id","competing-untagged-clause").put("basis","other_source_sequence");duplicate.remove("nativeOutlineRangeIds");((ArrayNode)scopes.path("ranges")).add(duplicate);}
            if(defect==11)physical.put("kind","native-outline");
            if(defect==12)physical.put("automaticEditEligible",true);
            if(defect==13)physical.put("semanticApplicabilityVerified",true);
            if(defect==14)physical.put("semanticScopeVerified",true);
            if(defect==15){physical.put("kind","native-outline");((ObjectNode)scopes.path("ranges").get(0)).put("kind","manual-sequence");}
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");assertTrue(selected.path("ranges").isEmpty(),"defect "+defect+": "+selected);assertFalse(selected.path("issues").isEmpty(),"defect "+defect);
        }
    }
    private static SourceDocument crossHeadingSource(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");int ordinal=0;for(JsonNode member:scopes.path("members"))((ObjectNode)member).put("ordinalZeroBased",ordinal++);
        ArrayNode ranges=scopes.putArray("ranges");range(ranges,"native-a","Native A",null,Arrays.asList("m1","m2","m3"));range(ranges,"native-b","Native B",null,Arrays.asList("m4","m5","m6"));
        for(JsonNode range:ranges){((ObjectNode)range).put("kind","native-outline").putArray("contextMemberIds");((ObjectNode)range).putArray("contextObservations");}
        range(ranges,"physical-3","3",null,Arrays.asList("m2","m3","m4","m5"));ObjectNode physical=(ObjectNode)ranges.get(2);physical.put("kind","manual-sequence").put("basis","explicit_tab_delimited_literal_source_interval").put("hierarchyStatus","source_interval_containment_only").put("automaticEditEligible",false).put("semanticApplicabilityVerified",false);physical.putArray("nativeOutlineRangeIds").add("native-a").add("native-b");physical.putArray("contextMemberIds");physical.putArray("contextObservations");physical.putObject("endBoundary").put("kind","next_explicit_number_in_source_sequence").put("memberId","m6").set("sourcePosition",position(6));
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static List<String> ids(JsonNode ranges){List<String> out=new ArrayList<>();for(JsonNode r:ranges)out.add(r.path("id").asText());return out;}
    private static List<String> strings(JsonNode values){List<String> out=new ArrayList<>();for(JsonNode value:values)out.add(value.asText());return out;}
    private static JsonNode member(JsonNode scopes,String id){for(JsonNode member:scopes.path("members"))if(id.equals(member.path("id").asText()))return member;throw new AssertionError(id);}
    private static VettingCorpus.Chunk hit(SourceDocument source){return VettingCorpus.chunks(Collections.singletonList(source)).stream().filter(c->c.getParts().stream().anyMatch(p->"b2".equals(p.getBlockId()))).findFirst().get();}
    private static SourceDocument source(){
        SourceDocument source=new SourceDocument();source.setId(77L);source.setProjectId("authored-word-scope");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setFileKey("PRE");source.setFileName("authored.docx");source.setSourceSha256(SHA);source.setParseStatus("PARSED");source.setPageCount(0);
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",true);ObjectNode outline=coverage.putObject("nativeDocxOutline");outline.put("version","docx-native-outline-v1");outline.putArray("headings");ObjectNode scopes=outline.putObject("sourceClauseScopes");scopes.put("version","docx-source-clause-scopes-v1");scopes.put("sourceSha256",SHA);scopes.put("familyCandidate","PRE");scopes.putArray("issues");ArrayNode members=scopes.putArray("members");
        List<DocumentBlock> blocks=new ArrayList<>();String longHit="HIT "+String.join(" ",Collections.nCopies(400,"source"));String[] text={"CHAPTER",longHit,"","CHILD_TAIL","PARENT_SIBLING","SIBLING_TAIL","SEPARATE_CONTEXT","OUTSIDE_CHAPTER"};
        for(int i=0;i<text.length;i++){
            ObjectNode m=members.addObject();m.put("id","m"+(i+1));m.put("storyKind","body");m.put("storyRootNodePath",BODY);m.set("sourcePosition",position(i+1));ObjectNode observation=m.putObject("textObservation");for(String channel:Arrays.asList("text","originalText","deletedText","strikeText"))observation.put(channel,channel.equals("text")||channel.equals("originalText")?text[i]:"");ArrayNode retained=m.putArray("retainedBlockIds");
            if(!text[i].isEmpty()){retained.add("b"+(i+1));DocumentBlock block=new DocumentBlock();block.setId("b"+(i+1));block.setLocation("body/"+i);block.setKind("paragraph");block.setSource("docx");block.setText(text[i]);block.setSourcePosition(position(i+1));blocks.add(block);}
        }
        ArrayNode ranges=scopes.putArray("ranges");range(ranges,"chapter","PRE1",null,Arrays.asList("m1","m2","m3","m4","m5","m6"));range(ranges,"child-a","PRE1(a)","chapter",Arrays.asList("m2","m3","m4"));range(ranges,"child-b","PRE1(b)","chapter",Arrays.asList("m5","m6"));
        source.setTextContent(String.join("\n",text));source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static SourceDocument carrierSource(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");String row=BODY+"/"+NS+"tbl[1]/"+NS+"tr[1]",cell=row+"/"+NS+"tc[1]";JsonNode first=member(scopes,"m2");String paragraph=first.path("textObservation").path("text").asText(),carrier=paragraph+"\n\nCHILD_TAIL";
        for(int n=2;n<=4;n++){ObjectNode m=(ObjectNode)member(scopes,"m"+n);((ObjectNode)m.path("textObservation")).put("status","observed_parser_paragraph_channels");ObjectNode p=position(n);p.put("nodePath",cell+"/"+NS+"p["+(n-1)+"]");m.set("sourcePosition",p);m.putArray("retainedBlockIds").add("b2");ArrayNode mappings=m.putArray("retainedBlockRanges");if(n!=3){int start=n==2?0:paragraph.length()+2;ObjectNode mapping=mappings.addObject();mapping.put("blockId","b2");mapping.put("startOffset",start);mapping.put("endOffset",start+m.path("textObservation").path("text").asText().length());mapping.put("offsetUnit","utf16");mapping.put("textChannel","text");mapping.put("parentEffectiveTextSha256",VettingCorpus.hash(carrier));}}
        ((ObjectNode)scopes.path("ranges").get(1).path("startBoundary")).set("sourcePosition",member(scopes,"m2").path("sourcePosition"));List<DocumentBlock> blocks=VettingCorpus.blocks(source);blocks.removeIf(b->"b4".equals(b.getId()));for(DocumentBlock block:blocks)if("b2".equals(block.getId())){block.setText(carrier);block.setLocation("body/carrier/table-row/0");ObjectNode p=position(2);p.put("nodePath",row);block.setSourcePosition(p);}source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static void range(ArrayNode ranges,String id,String reference,String parent,List<String> ids){ObjectNode r=ranges.addObject();r.put("id",id);r.put("reference",reference);if(parent==null)r.putNull("parentId");else r.put("parentId",parent);r.put("status","source_syntax_complete");r.put("scopeComplete",true);r.set("orderedMemberIds",JsonUtils.mapper().valueToTree(ids));r.putArray("contextMemberIds").add("m7");r.putArray("contextObservations").addObject().put("memberId","m7").put("reason","source_note_context");r.putObject("startBoundary").put("kind","observed_source_member").put("memberId",ids.get(0)).set("sourcePosition",position(Integer.parseInt(ids.get(0).substring(1))));r.putObject("endBoundary").put("kind","next_peer").put("memberId","m8").set("sourcePosition",position(8));}
    private static ObjectNode position(int paragraph){ObjectNode p=JsonUtils.mapper().createObjectNode();p.put("kind","docx_native");p.put("sourceSha256",SHA);p.put("partName","word/document.xml");p.put("partSha256",PART);p.put("nodePath",BODY+"/"+NS+"p["+paragraph+"]");return p;}
}
