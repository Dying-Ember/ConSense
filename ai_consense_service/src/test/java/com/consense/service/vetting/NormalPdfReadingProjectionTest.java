package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.*;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalPdfReadingProjectionTest {
    private static final byte[] ORIGINAL="authored immutable PDF original".getBytes(StandardCharsets.UTF_8);
    private static final String SHA=OerSourceMetadata.originalSha256(ORIGINAL);

    @Test void ordinaryCaptureKeepsExactOrderedTextAndUnknownsWithoutSerializingTheArchive(){
        List<DocumentBlock> rich=syntax();String before=JsonUtils.write(rich);ParseCoverage coverage=coverage(false);coverage.getFailedPages().add(2);coverage.getNeedsReviewPages().add(1);coverage.getLimitations().add("Unobserved next page remains unknown");
        PdfPageEvidence archive=new PdfPageEvidence(){@Override public List<JsonNode> getVectorPathOperators(){throw new AssertionError("Ordinary capture must never serialize the rich archive");}};
        DocumentParser.ParsedDocument parsed=parsed(rich,coverage,SHA,Collections.singletonList(archive));SourceDocument source=source(SHA);
        OerSourceMetadata.captureOrdinary(source,parsed,ORIGINAL);NormalSourceCandidateGraph.capture(source);
        assertNull(source.getNativePdfEvidenceJson());assertEquals(SHA,source.getSourceSha256());assertEquals(before,JsonUtils.write(rich));assertFalse(rich.get(0).getPdfGlyphs().isEmpty());
        List<DocumentBlock> compact=VettingCorpus.blocks(source);assertEquals(rich.size(),compact.size());
        for(int i=0;i<rich.size();i++){DocumentBlock actual=compact.get(i),old=rich.get(i);assertEquals(old.getId(),actual.getId());assertEquals(old.getText(),actual.getText());assertEquals(old.getOriginalText(),actual.getOriginalText());assertEquals(old.getLocation(),actual.getLocation());assertEquals(old.getPageNo(),actual.getPageNo());assertArrayEquals(old.getBbox(),actual.getBbox());assertTrue(actual.getPdfGlyphs().isEmpty());}
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source),saved=JsonUtils.parse(source.getParseCoverageJson());assertEquals(NormalSourceCandidateGraph.COMPACT_PDF_VERSION,graph.path("producerVersion").asText());
        assertFalse(saved.path("complete").asBoolean());assertEquals(JsonUtils.parse("[2]"),saved.path("failedPages"));assertEquals(JsonUtils.parse("[1]"),saved.path("needsReviewPages"));
        JsonNode groups=graph.path("nativePdfSourceGroups"),parent=group(groups,"source_clause","2"),tail=group(groups,"parent_tail","2:tail1"),last=group(groups,"source_clause","3");
        assertEquals(Arrays.asList("two","a","b","inspection","hours"),ids(parent.path("bodyBlockIds")));assertEquals(parent.path("id"),tail.path("parentId"));assertFalse(last.path("scopeComplete").asBoolean());
        assertTrue(last.path("unknownReasons").toString().contains("native_source_end_not_established"));assertFalse(saved.path(NormalPdfReadingProjection.FIELD).path("sourceQualityAccepted").asBoolean());
    }

    @Test void ordinaryTableProjectionPreservesEveryIndependentCarrierAndItsBoundNotes(){
        SourceDocument source=PdfSourceGroupConsumptionTest.source(true);List<DocumentBlock> rich=VettingCorpus.blocks(source);String old=source.getStructuredContentJson();JsonNode before=NormalSourceCandidateGraph.requireBound(source).path("nativePdfSourceGroups");
        assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),source.getSourceSha256(),Collections.singletonList(new PdfPageEvidence())),source.getSourceSha256()));NormalSourceCandidateGraph.capture(source);
        JsonNode after=NormalSourceCandidateGraph.requireBound(source).path("nativePdfSourceGroups");assertEquals(methods(before),methods(after));
        List<DocumentBlock> compact=VettingCorpus.blocks(source);for(int i=0;i<rich.size();i++)assertEquals(rich.get(i).getNativeTableCandidate(),compact.get(i).getNativeTableCandidate());assertEquals(old,JsonUtils.write(rich));
        VettingCorpus.Chunk hit=PdfSourceGroupConsumptionTest.hit(source,"row");JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit);assertTrue(model.toString().contains("EXACT_COMPLETE_NOTE_DEFINITION"));assertEquals(2,methods(after).size());
    }

    @Test void sourceAndGroupTamperingCannotReuseTheSavedGeneration(){
        for(int mode=0;mode<7;mode++){
            SourceDocument source=compact();List<DocumentBlock> blocks=VettingCorpus.blocks(source);
            if(mode==0)blocks.get(0).setText("Changed current literal");if(mode==1)blocks.get(0).setBbox(new double[]{.2,.2,.4,.01});if(mode==2)blocks.get(0).setPageNo(2);if(mode==3)blocks.get(0).setId("changed-id");if(mode==4)Collections.swap(blocks,0,1);if(mode==5)blocks.add(blocks.get(0));
            if(mode<6)source.setStructuredContentJson(JsonUtils.write(blocks));else source.setSourceSha256("b".repeat(64));
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source),"mode "+mode);
        }
    }

    @Test void changedGroupAuthorityParentsBoundaryAndLiteralRangesRejectEvenWithARecomputedGroupChecksum(){
        for(int mode=0;mode<8;mode++){
            SourceDocument source=compact();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);JsonNode inventory=carrier.path("sourceGroups");ObjectNode first=(ObjectNode)group(inventory,"source_clause","2");
            if(mode==0)first.put("semanticScopeVerified",true);if(mode==1)first.put("parentId","missing-parent");if(mode==2)first.put("parentId",first.path("id").asText());if(mode==3)((ObjectNode)first.path("startBoundary").path("sourcePosition")).put("physicalPageNo",9);
            if(mode==4)((ObjectNode)first.path("endBoundary")).put("blockId","a");if(mode==5)carrier.put("humanConfirmed",true);
            if(mode==6){for(String field:Arrays.asList("orderedBlockIds","bodyBlockIds")){ArrayNode values=(ArrayNode)first.path(field);JsonNode a=values.get(1);values.set(1,values.get(2));values.set(2,a);}}
            if(mode==7){ObjectNode end=(ObjectNode)first.path("endBoundary");end.put("blockId","a");end.set("sourcePosition",group(inventory,"source_clause","2(a)").path("sourcePosition").deepCopy());}
            carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source),"mode "+mode);
        }
    }

    @Test void independentMethodCopiesCannotBeChangedBehindTheirActualCarrier(){
        for(int mode=0;mode<6;mode++){
            SourceDocument source=PdfSourceGroupConsumptionTest.source(false);assertTrue(NormalPdfReadingProjection.capture(source,parsed(VettingCorpus.blocks(source),coverage(false),source.getSourceSha256(),Collections.singletonList(new PdfPageEvidence())),source.getSourceSha256()));
            ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);JsonNode inventory=carrier.path("sourceGroups");ObjectNode group=(ObjectNode)methods(inventory).get(0);
            if(mode==0)((ObjectNode)group.path("methodTable").path("origins").get(0)).put("text","Changed cell");if(mode==1)((ObjectNode)group.path("methodBinding")).put("provider","foreign");if(mode==2)((ObjectNode)group.path("methodBinding")).put("rawResultSha256","b".repeat(64));if(mode==3)((ObjectNode)group.path("sourcePosition")).put("physicalPageNo",2);if(mode==4)((ArrayNode)group.path("alignments").get(0).path("nativeLineBboxPoints")).set(0,JsonUtils.mapper().valueToTree(99));
            if(mode==5)((ObjectNode)group.path("alignments").get(0)).put("row",999);
            carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source),"mode "+mode);
        }
    }

    @Test void compactFormsKeepPartialAppearanceStatusAndCoordinatesWithoutStreamsOrGlyphs(){
        List<DocumentBlock> rich=syntax();DocumentBlock form=line("form",.02,.20,"Partial form value");form.setKind("pdf_form_widget");form.setSource("native_form");form.getPdfGlyphs().clear();PdfPageEvidence.PdfFormField field=new PdfPageEvidence.PdfFormField();field.setId("pdf-form/widget/0");field.setFieldName("Unresolved source note");field.setPageNo(1);field.setAppearancePresent(true);field.setAppearanceStreamObservationStatus("observed_existing_normal_AP_partial");field.setAppearanceGlyphObservationStatus("partial_unverified");field.setAppearanceMatrix(new double[]{1,0,0,1,2,3});field.setAppearanceBbox(new double[]{0,0,20,10});field.setRectanglePdf(new double[]{20,20,40,30});field.setAnnotationFlags(4);field.setAppearanceDecodedStreamBytesBase64("unneeded-raw-stream");form.setPdfFormField(field);rich.add(form);
        SourceDocument source=source(SHA);assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));PdfPageEvidence.PdfFormField saved=VettingCorpus.blocks(source).get(rich.size()-1).getPdfFormField();
        assertTrue(saved.isAppearancePresent());assertEquals(field.getAppearanceStreamObservationStatus(),saved.getAppearanceStreamObservationStatus());assertEquals(field.getAppearanceGlyphObservationStatus(),saved.getAppearanceGlyphObservationStatus());assertArrayEquals(field.getAppearanceMatrix(),saved.getAppearanceMatrix());assertArrayEquals(field.getAppearanceBbox(),saved.getAppearanceBbox());assertEquals(field.getAnnotationFlags(),saved.getAnnotationFlags());assertNull(saved.getAppearanceDecodedStreamBytesBase64());assertTrue(saved.getAppearanceGlyphs().isEmpty());
    }

    @Test void splitNativeSyntaxKeepsExactUtf16RangesAndRejectsChangedSubstringWitnesses(){
        List<DocumentBlock> rich=Arrays.asList(line("root",.10,.10,"2. Source parent"),line("nested",.15,.15,"(a) (i) Nested literal \uD83D\uDE00"),line("peer",.10,.30,"3. Next parent"));SourceDocument source=source(SHA);assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));NormalSourceCandidateGraph.capture(source);
        JsonNode groups=NormalSourceCandidateGraph.requireBound(source).path("nativePdfSourceGroups"),nested=group(groups,"source_clause","2(a)(i)"),observation=nested.path("nativeSyntaxObservations").get(0),span=observation.path("sourceSpans").get(0);
        assertEquals("exact_current_member_literal_segments",observation.path("rangeStatus").asText());assertFalse(observation.has("glyphIds"));assertEquals("(i) Nested literal \uD83D\uDE00",span.path("literal").asText());assertEquals(4,span.path("startOffset").asInt());assertEquals(rich.get(1).getText().length(),span.path("endOffset").asInt());
        ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);JsonNode inventory=carrier.path("sourceGroups");ObjectNode changed=(ObjectNode)group(inventory,"source_clause","2(a)(i)").path("nativeSyntaxObservations").get(0).path("sourceSpans").get(0);changed.put("startOffset",0);carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source));
    }

    @Test void compactReferencesUseExactOwnedTargetRowsAndCopiedOffsets(){
        for(int mode=-1;mode<7;mode++){
            List<DocumentBlock> rich=syntax();rich.add(3,line("reference",.17,.22,"See condition 2(a) above."));SourceDocument source=source(SHA);assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));NormalSourceCandidateGraph.capture(source);
            ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);JsonNode inventory=carrier.path("sourceGroups");ObjectNode edge=relation(inventory,"source_literal_reference");assertEquals(JsonUtils.parse("[\"a\"]"),edge.path("targetBlockIds"));assertEquals(edge.path("targetBlockIds"),group(inventory,"source_clause","2(a)").path("observedOwnBlockIds"));
            if(mode<0)continue;if(mode==0)edge.putArray("targetBlockIds").add("three");if(mode==1)edge.put("fromBlockId","b");if(mode==2)edge.put("to",group(inventory,"source_clause","3").path("id").asText());if(mode==3)edge.put("startOffset",0);if(mode==4)edge.put("status","unknown");if(mode==5)edge.put("targetReference","3");
            if(mode==6){JsonNode target=group(inventory,"source_clause","3");edge.put("targetReference","3").put("targetGroupId",target.path("id").asText()).put("to",target.path("id").asText());edge.set("targetSourcePosition",target.path("sourcePosition").deepCopy());edge.set("targetBlockIds",target.path("observedOwnBlockIds").deepCopy());syncCopies(inventory,edge);}
            carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source),"mode "+mode);
        }
    }

    @Test void compactLocalSiblingReferencesUseObservedAncestorOwnershipWithoutChangingText(){
        for(boolean nested:new boolean[]{false,true}){
            List<DocumentBlock> rich=syntax();int at=3;
            if(nested)rich.add(at++,line("nested",.20,.21,"(i) A nested observed provision."));
            rich.add(at,line("local-reference",nested?.22:.17,.23,"See (a) above for the available documents."));
            String before=JsonUtils.write(rich);SourceDocument source=source(SHA);
            assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));
            NormalSourceCandidateGraph.capture(source);
            ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);
            JsonNode inventory=carrier.path("sourceGroups");ObjectNode edge=relation(inventory,"source_literal_reference");
            assertEquals("(a)",edge.path("literal").asText());assertEquals("2(a)",edge.path("targetReference").asText());
            assertEquals(group(inventory,"source_clause",nested?"2(b)(i)":"2(b)").path("id"),edge.path("from"));
            assertEquals(group(inventory,"source_clause","2(a)").path("id"),edge.path("targetGroupId"));
            assertEquals(before,JsonUtils.write(rich));assertFalse(carrier.path("sourceQualityAccepted").asBoolean());
            List<DocumentBlock> compact=VettingCorpus.blocks(source);assertEquals(rich.size(),compact.size());
            for(int i=0;i<rich.size();i++)assertEquals(rich.get(i).getText(),compact.get(i).getText());
            JsonNode forged=group(inventory,"source_clause","2(b)");
            edge.put("targetReference","2(b)").put("targetGroupId",forged.path("id").asText()).put("to",forged.path("id").asText());
            edge.set("targetSourcePosition",forged.path("sourcePosition").deepCopy());edge.set("targetBlockIds",forged.path("observedOwnBlockIds").deepCopy());
            syncCopies(inventory,edge);carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));
            IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source));
            assertEquals("NORMAL_PDF_COMPACT_REFERENCE_LITERAL_TARGET",rejected.getMessage());
        }
    }

    @Test void compactNamedNoteRelationsKeepTheirWidgetAndExactNodeTarget(){
        for(int mode=-1;mode<6;mode++){
            List<DocumentBlock> rich=syntax();DocumentBlock note=line("note",.02,.20,"See source condition");note.setKind("pdf_form_widget");note.setSource("native_form");note.getPdfGlyphs().clear();PdfPageEvidence.PdfFormField field=new PdfPageEvidence.PdfFormField();field.setId("pdf-form/note/widget/0");field.setFieldName("Clause2b");field.setFieldValue(note.getText());field.setAppearanceValue(note.getText());field.setPageNo(1);field.setAppearancePresent(true);field.setAppearanceGlyphObservationStatus("observed_existing_normal_AP_operators_unreviewed");PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId(field.getId()+"/glyph/0");glyph.setUnicode(note.getText());glyph.setCoordinateSpace("appearance_stream_local_matrix_no_page_box");glyph.setTextMatrix(new double[]{8,0,0,8,2,20});glyph.setEligibleForNativeText(true);field.getAppearanceGlyphs().add(glyph);note.setPdfFormField(field);rich.add(note);
            SourceDocument source=source(SHA);assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));NormalSourceCandidateGraph.capture(source);ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),carrier=(ObjectNode)coverage.path(NormalPdfReadingProjection.FIELD);JsonNode inventory=carrier.path("sourceGroups");ObjectNode edge=relation(inventory,"source_explicit_note_target");assertEquals(JsonUtils.parse("[\"b\"]"),edge.path("targetBlockIds"));if(mode<0)continue;
            if(mode==0)edge.putArray("targetBlockIds").add("three");if(mode==1)edge.put("fieldId","foreign-widget");if(mode==2)edge.put("appearanceGlyphNamespace","foreign/glyph");if(mode==3)edge.put("targetIncludesDescendants",true);if(mode==4)((ObjectNode)group(inventory,"source_clause","2(b)").path("relations").get(0)).put("fieldName","foreign-name");
            if(mode==5){JsonNode target=group(inventory,"source_clause","3");ObjectNode noteGroup=(ObjectNode)group(inventory,"source_margin_note","2(b)");edge.put("targetGroupId",target.path("id").asText()).put("to",target.path("id").asText());edge.set("targetSourcePosition",target.path("sourcePosition").deepCopy());edge.set("targetBlockIds",target.path("observedOwnBlockIds").deepCopy());noteGroup.put("parentId",target.path("id").asText()).put("reference","3");noteGroup.set("bodyBlockIds",target.path("observedOwnBlockIds").deepCopy());noteGroup.set("orderedBlockIds",target.path("observedOwnBlockIds").deepCopy());noteGroup.putArray("requiredMemberBlockIds").add("three").add("note");syncCopies(inventory,edge);}
            carrier.put("sourceGroupsSha256",JsonUtils.serializedSha256(inventory));source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source),"mode "+mode);
        }
    }

    @Test void actualSavedAdjacentNoteContinuationAndAllMethodCopiesSurviveCompactReload() throws Throwable {
        java.lang.reflect.Method fixture=PdfSourceGroupConsumptionTest.class.getDeclaredMethod("actualCom11NoteNine",boolean.class);fixture.setAccessible(true);SourceDocument source;
        try{source=(SourceDocument)fixture.invoke(null,true);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        JsonNode before=NormalSourceCandidateGraph.requireBound(source).path("nativePdfSourceGroups");ParseCoverage coverage=JsonUtils.read(source.getParseCoverageJson(),ParseCoverage.class);List<DocumentBlock> rich=VettingCorpus.blocks(source);
        assertTrue(NormalPdfReadingProjection.capture(source,parsed(rich,coverage,source.getSourceSha256(),Collections.singletonList(new PdfPageEvidence())),source.getSourceSha256()));NormalSourceCandidateGraph.capture(source);JsonNode after=NormalSourceCandidateGraph.requireBound(source).path("nativePdfSourceGroups");assertEquals(methods(before),methods(after));boolean suffix=false;for(JsonNode group:methods(after))suffix|=group.has("methodContinuationObservations");assertTrue(suffix,"Actual saved page 19/20 notes continuation must remain observable");
    }

    @Test void sparseFontWitnessPreservesOriginalAndDecodedChannelsAndUnresolvedLiteral(){
        List<DocumentBlock> rich=syntax();DocumentBlock line=rich.get(1);line.setText("(a) \u03A6 \uF06D");line.setOriginalText("(a) \uF046 \uF06D");line.setPdfGlyphs(new ArrayList<>());addGlyphs(line,line.getOriginalText());
        PdfPageEvidence.PdfGlyph glyph=line.getPdfGlyphs().stream().filter(g->"\uF046".equals(g.getUnicode())).findFirst().orElseThrow();glyph.setCharacterCodes(new int[]{41});glyph.setFontName("Symbol");glyph.setFontSubtype("Type0");glyph.setFontEmbedded(true);
        PdfPageEvidence.TextResolution receipt=new PdfPageEvidence.TextResolution();receipt.setRawUnicode("\uF046");receipt.setSourceCharacterCodes(new int[]{41});receipt.setFontName("Symbol");receipt.setSelector("F046");receipt.setDecodedText("\u03A6");receipt.setResolutionStatus(PdfPageEvidence.TextResolution.RESOLVED);receipt.setMappingSource(PdfPageEvidence.TextResolution.MAPPING_SOURCE);receipt.setFontProgramSha256(SHA);receipt.setToUnicodeSha256(SHA);receipt.setSourceGlyphId(41);receipt.setSelectorGlyphId(41);receipt.setCmapPlatformId(3);receipt.setCmapEncodingId(0);glyph.setTextResolution(receipt);
        SourceDocument source=source(SHA);NormalPdfReadingProjection.capture(source,parsed(rich,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA);NormalSourceCandidateGraph.capture(source);
        DocumentBlock saved=VettingCorpus.blocks(source).get(1);assertEquals(line.getText(),saved.getText());assertEquals(line.getOriginalText(),saved.getOriginalText());assertTrue(saved.getText().contains("\uF06D"));
        JsonNode witnesses=JsonUtils.parse(source.getParseCoverageJson()).path(NormalPdfReadingProjection.FIELD).path("textDecodingWitnesses");assertEquals(1,witnesses.size());assertEquals("\uF046",witnesses.get(0).path("rawUnicode").asText());assertEquals("\u03A6",witnesses.get(0).path("readerText").asText());
        ObjectNode changed=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)changed.path(NormalPdfReadingProjection.FIELD).path("textDecodingWitnesses").get(0)).put("readerText","wrong");source.setParseCoverageJson(JsonUtils.write(changed));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.capture(source));
    }

    @Test void nonPdfAndExplicitRichCaptureKeepTheirExistingPersistenceAndProducer(){
        List<DocumentBlock> blocks=syntax();SourceDocument ordinary=source(SHA);DocumentParser.ParsedDocument docx=parsed(blocks,coverage(false),SHA,Collections.emptyList());OerSourceMetadata.captureOrdinary(ordinary,docx,ORIGINAL);assertFalse(JsonUtils.parse(ordinary.getParseCoverageJson()).has(NormalPdfReadingProjection.FIELD));NormalSourceCandidateGraph.capture(ordinary);assertEquals(NormalSourceCandidateGraph.VERSION,NormalSourceCandidateGraph.requireBound(ordinary).path("producerVersion").asText());
        SourceDocument rich=source(SHA);OerSourceMetadata.capture(rich,parsed(blocks,coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),ORIGINAL);assertNotNull(rich.getNativePdfEvidenceJson());assertFalse(JsonUtils.parse(rich.getNativePdfEvidenceJson()).isEmpty());
    }

    @Test void missingCarrierAndForeignWordReferencesCannotMasqueradeAsCompactPdf(){
        SourceDocument source=compact();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());coverage.remove(NormalPdfReadingProjection.FIELD);source.setParseCoverageJson(JsonUtils.write(coverage));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
        ObjectNode graph=JsonUtils.mapper().createObjectNode();graph.put("producerVersion",NormalSourceCandidateGraph.COMPACT_PDF_VERSION);graph.putObject("foreign").put("$nativeParagraph",0);assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(graph));
    }

    private static SourceDocument compact(){SourceDocument source=source(SHA);assertTrue(NormalPdfReadingProjection.capture(source,parsed(syntax(),coverage(false),SHA,Collections.singletonList(new PdfPageEvidence())),SHA));NormalSourceCandidateGraph.capture(source);return source;}
    private static SourceDocument source(String sha){SourceDocument source=new SourceDocument();source.setId(942L);source.setProjectId("authored-compact-reading");source.setFileName("authored-reading.pdf");source.setFileKey("APH");source.setSourceSha256(sha);source.setTextContent("Authored source syntax");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");return source;}
    private static ParseCoverage coverage(boolean complete){ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);coverage.setParsedPages(1);coverage.setComplete(complete);return coverage;}
    private static DocumentParser.ParsedDocument parsed(List<DocumentBlock> blocks,ParseCoverage coverage,String sha,List<PdfPageEvidence> evidence){return new DocumentParser.ParsedDocument("Authored source syntax",Collections.emptyList(),1,false,"Authored shape only",blocks,coverage,sha,evidence);}
    private static List<DocumentBlock> syntax(){return new ArrayList<>(Arrays.asList(line("two",.10,.10,"2. Documents not issued:"),line("a",.15,.15,"(a) Available documents."),line("b",.15,.20,"(b) Other source references."),line("inspection",.115,.25,"These documents may be inspected"),line("hours",.115,.27,"during normal office hours."),line("three",.10,.32,"3. Next root")));}
    private static DocumentBlock line(String id,double x,double y,String text){DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind("pdf_line");block.setSource("native");block.setLocation("pdf-page/1/line/"+id);block.setPageNo(1);block.setText(text);block.setOriginalText(text);block.setBbox(new double[]{x,y,text.codePointCount(0,text.length())*.005,.01});block.setReadingContext(JsonUtils.mapper().createObjectNode().put("sourceSha256",SHA));addGlyphs(block,text);return block;}
    private static void addGlyphs(DocumentBlock block,String text){int ordinal=0;for(int cursor=0;cursor<text.length();){int cp=text.codePointAt(cursor);String literal=new String(Character.toChars(cp));PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId("pdf-page/1/glyph/"+block.getId()+"/"+ordinal);glyph.setUnicode(literal);glyph.setEligibleForNativeText(true);glyph.setBbox(new double[]{block.getBbox()[0]+ordinal*.005,block.getBbox()[1],.005,.01});glyph.setTextMatrix(new double[]{10,0,0,10,0,0});glyph.setFontSize(10);glyph.setFontSizeInPt(10);glyph.setPdfboxPosition(new double[]{0,0,0,0,0,0,0,0,1000,1000});block.getPdfGlyphs().add(glyph);cursor+=literal.length();ordinal++;}}
    private static JsonNode group(JsonNode inventory,String kind,String reference){for(JsonNode group:inventory.path("groups"))if(kind.equals(group.path("kind").asText())&&reference.equals(group.path("reference").asText()))return group;throw new AssertionError(kind+":"+reference);}
    private static ObjectNode relation(JsonNode inventory,String type){for(JsonNode relation:inventory.path("relations"))if(type.equals(relation.path("type").asText()))return (ObjectNode)relation;throw new AssertionError(type);}
    private static void syncCopies(JsonNode inventory,JsonNode edge){for(JsonNode group:inventory.path("groups")){ArrayNode edges=(ArrayNode)group.path("relations");for(int i=edges.size()-1;i>=0;i--)if(edges.get(i).path("type").equals(edge.path("type"))&&edges.get(i).path("fromBlockId").equals(edge.path("fromBlockId")))edges.remove(i);String id=group.path("id").asText(),type=edge.path("type").asText();if("source_literal_reference".equals(type)&&id.equals(edge.path("from").asText())||"source_explicit_note_target".equals(type)&&(id.equals(edge.path("targetGroupId").asText())||"source_margin_note".equals(group.path("kind").asText())&&group.path("sourcePosition").path("blockId").equals(edge.path("fromBlockId"))))edges.add(edge.deepCopy());}}
    private static List<JsonNode> methods(JsonNode inventory){List<JsonNode> groups=new ArrayList<>();for(JsonNode group:inventory.path("groups"))if("native_table_method_group".equals(group.path("kind").asText()))groups.add(group);return groups;}
    private static List<String> ids(JsonNode values){List<String> ids=new ArrayList<>();for(JsonNode value:values)ids.add(value.asText());return ids;}
}
