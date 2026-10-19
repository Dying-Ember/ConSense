package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.apache.poi.xwpf.usermodel.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OerTemplatePdfSourceScopeTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),OTHER=String.join("",Collections.nCopies(64,"b"));

    @Test void leadingNativePdfInstructionBindsItsWholeClauseAndExactLocalReferencesOnly(){
        JsonNode evidence=analyse(fixture()),note=evidence.path("guidanceNotes").get(0),scope=scope(evidence,"SCC8.9");
        assertTrue(scope.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("owner","one","two"),ids(scope.path("orderedMemberIds")));
        assertEquals("next",scope.path("endBoundary").path("memberId").asText());assertEquals("source_bound_observed_glyph_frames",note.path("pdfLaneProjection").path("frameStatus").asText());
        assertEquals(595.0,note.path("pdfLaneProjection").path("observedGlyphFrames").get(0).path("pageWidthInPt").asDouble());assertFalse(note.path("scopeComplete").asBoolean());
        for(JsonNode reference:note.path("referenceCandidates"))assertEquals(reference.path("text").asText().startsWith("SCC8.9(")?"resolved_explicit_source_target":"literal_reference_candidate_not_resolved",reference.path("status").asText());
        assertEquals(note.path("referenceCandidates"),evidence.path("conditions").get(0).path("referenceCandidates"));assertFalse(scope.path("automaticEditEligible").asBoolean());
    }
    @Test void aLaterInstructionCannotBorrowAnotherNotesCompleteTargets(){
        List<DocumentBlock> blocks=fixture();blocks.add(4,line("late",7,"[Guidance Note: For use where another fact is confirmed; insert SCC8.9(1).]",.6));
        JsonNode evidence=analyse(blocks),late=evidence.path("guidanceNotes").get(1);assertTrue(scope(evidence,"SCC8.9").path("scopeComplete").asBoolean());assertEquals("literal_reference_candidate_not_resolved",late.path("referenceCandidates").get(0).path("status").asText());
    }
    @Test void completePdfScopeAndReferenceProofRejectMissingOrCorruptObservations(){
        for(int mode=0;mode<7;mode++){
            List<DocumentBlock> blocks=fixture();DocumentBlock target=blocks.get(2);
            if(mode==0)blocks.remove(4);
            if(mode==1)((ObjectNode)target.getReadingContext()).put("sourceSha256",OTHER);
            if(mode==2)target.getPdfGlyphs().get(0).setUnicode("?");
            if(mode==3)target.getPdfGlyphs().get(0).setCoordinateSpace("unknown");
            if(mode==4){double[] matrix=target.getPdfGlyphs().get(0).getTextMatrix().clone();matrix[4]+=10;target.getPdfGlyphs().get(0).setTextMatrix(matrix);}
            if(mode==5){double[] position=target.getPdfGlyphs().get(0).getPdfboxPosition().clone();position[8]=700;target.getPdfGlyphs().get(0).setPdfboxPosition(position);}
            if(mode==6)target.getPdfGlyphs().get(1).setId(target.getPdfGlyphs().get(0).getId());
            JsonNode evidence=analyse(blocks);assertFalse(completeScope(evidence,"SCC8.9"),"Unsafe observed source case "+mode);
            for(JsonNode note:evidence.path("guidanceNotes"))for(JsonNode reference:note.path("referenceCandidates"))assertEquals("literal_reference_candidate_not_resolved",reference.path("status").asText(),"Reference case "+mode);
        }
    }
    @Test void repeatedChildLabelsDoNotResolveAnExplicitReference(){
        List<DocumentBlock> blocks=fixture();blocks.add(3,line("duplicate",7,"(1) Another observed source item.",.4));JsonNode evidence=analyse(blocks);
        assertTrue(completeScope(evidence,"SCC8.9"));for(JsonNode reference:evidence.path("guidanceNotes").get(0).path("referenceCandidates"))assertEquals("literal_reference_candidate_not_resolved",reference.path("status").asText());
    }
    @Test void aRevisionPrefixWithoutItsCompleteGeometryCannotHideAnEarlierBoundary(){
        List<DocumentBlock> blocks=fixture();blocks.set(4,line("unproven-next",7,"Rev. 1 SCC8.10 A separately observed next clause",.7));blocks.add(line("later",7,"SCC8.11 A later clause",.8));
        assertFalse(completeScope(analyse(blocks),"SCC8.9"));
    }
    @Test void missingWholePhysicalPagesCannotBecomeACompleteClause(){
        List<DocumentBlock> blocks=fixture();blocks.set(3,line("two",9,"(2) Second complete requirement.",.5));blocks.set(4,line("next",9,"SCC8.10 Next clause",.7));assertFalse(completeScope(analyse(blocks),"SCC8.9"));
    }
    @Test void explicitChildTargetsCannotSkipAnUnobservedPhysicalPageOrResolveTheirReferences(){
        List<DocumentBlock> blocks=fixture();blocks.set(1,line("note",7,"[Guidance Note: Objects in the Clauses SCC8.9(1) and SCC8.9(2) are to be deleted as appropriate.]",.2));blocks.set(3,line("two",9,"(2) Second complete requirement.",.5));blocks.set(4,line("next",9,"SCC8.10 Next clause",.7));JsonNode evidence=analyse(blocks);assertFalse(referenceGroup(evidence).path("scopeComplete").asBoolean());for(JsonNode reference:evidence.path("guidanceNotes").get(0).path("referenceCandidates"))assertEquals("literal_reference_candidate_not_resolved",reference.path("status").asText());
    }
    @Test void aNonleadingConditionalNoteDoesNotClaimTheOwningClause(){
        List<DocumentBlock> blocks=fixture();Collections.swap(blocks,1,2);assertFalse(completeScope(analyse(blocks),"SCC8.9"));
    }
    @Test void exactReferenceBackfillAlsoUsesWordSourceIdentityAndKeepsCaptureSeparate(){
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(false)),note=evidence.path("guidanceNotes").get(0);assertFalse(note.path("scopeComplete").asBoolean());
        for(JsonNode reference:note.path("referenceCandidates"))assertEquals("resolved_explicit_source_target",reference.path("status").asText());
        JsonNode foreign=OerTemplateEvidence.analyse("SCC",word(true));for(JsonNode reference:foreign.path("guidanceNotes").get(0).path("referenceCandidates"))assertEquals("literal_reference_candidate_not_resolved",reference.path("status").asText());
    }
    @Test void syntheticNativeDocxRetainsLeadingSccInstructionWithSeparateOrTableRevisionContext() throws Exception {
        for(boolean tableSidebar:Arrays.asList(false,true)){
            DocumentParser.ParsedDocument parsed=syntheticWordFromPdfLayout(tableSidebar);JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed),group=scope(evidence,"SCC22.1"),note=evidence.path("guidanceNotes").get(0);
            assertTrue(group.path("scopeComplete").asBoolean());assertFalse(note.path("scopeComplete").asBoolean());assertFalse(group.has("observedGlyphFrames"));
            List<String> selected=new ArrayList<>();for(JsonNode range:group.path("memberRanges"))for(JsonNode member:evidence.path("sourceMembers"))if(member.path("id").equals(range.path("memberId")))selected.add(member.path("textObservation").path("text").asText());
            assertEquals(4,selected.size());assertTrue(selected.get(0).startsWith("SCC22.1 "));assertTrue(selected.get(3).startsWith("Provided that"));for(String text:selected)assertFalse(text.contains("Rev. 1")||text.contains("approved on")||text.startsWith("SCC22.2"));
            JsonNode boundary=null;for(JsonNode member:evidence.path("sourceMembers"))if(member.path("id").equals(group.path("endBoundary").path("memberId")))boundary=member;assertNotNull(boundary);assertTrue(boundary.path("textObservation").path("text").asText().startsWith("SCC22.2 "));
            int local=0,outside=0;for(JsonNode reference:note.path("referenceCandidates")){boolean own=reference.path("text").asText().startsWith("SCC22.1(");assertEquals(own?"resolved_explicit_source_target":"literal_reference_candidate_not_resolved",reference.path("status").asText());if(own)local++;else outside++;}assertEquals(2,local);assertEquals(2,outside);
        }
    }
    @Test void syntheticNativeDocxLeadingScopeRejectsForeignSourceOrMissingNextClause() throws Exception {
        for(int mode=0;mode<3;mode++){DocumentParser.ParsedDocument parsed=syntheticWordFromPdfLayout(false);ObjectNode inventory=(ObjectNode)parsed.getCoverage().getNativeDocxParagraphs();
            for(JsonNode item:inventory.path("paragraphs")){ObjectNode member=(ObjectNode)item;String text=member.path("textObservation").path("text").asText();if(text.startsWith("(2)")){if(mode==0)((ObjectNode)member.path("sourcePosition")).put("sourceSha256",OTHER);if(mode==1)((ObjectNode)member.path("sourcePosition")).put("partSha256",OTHER);}if(mode==2&&text.startsWith("SCC22.2"))((ObjectNode)member.path("textObservation")).put("text","An unlabelled observed paragraph");}
            JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed);assertFalse(completeScope(evidence,"SCC22.1"));
        }
    }

    @Test void nativeDocxEmptyContentRequiresObservedEmptyDomAndKeepsNormalAndTemplateSourceIdentity() throws Exception {
        String word="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        String[] contents={"","<w:pPr><w:tabs><w:tab w:val='left' w:pos='720'/></w:tabs></w:pPr>",
                "<w:bookmarkStart w:id='0' w:name='empty'/><w:bookmarkEnd w:id='0'/>",
                "<w:r/>","<w:r><w:t/></w:r>","<w:fldSimple w:instr='PAGE'/>",
                "<w:r><w:pict/></w:r>","<w:r><w:drawing/></w:r>","<m:oMath/>","<x:pPr/>",
                "<w:bookmarkStart w:id='1' w:name='payload'><w:r/></w:bookmarkStart>",
                "<w:bookmarkEnd w:id='1'><w:r/></w:bookmarkEnd>"};
        StringBuilder xml=new StringBuilder("<w:document xmlns:w='"+word+"' xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math' xmlns:x='urn:foreign-content'><w:body>");
        for(String content:contents)xml.append("<w:p>").append(content).append("</w:p>");
        byte[] part=xml.append("</w:body></w:document>").toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(syntheticWordBytes(false)));ZipOutputStream out=new ZipOutputStream(output)){
            ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] data=in.readAllBytes();out.putNextEntry(new ZipEntry(entry.getName()));out.write("word/document.xml".equals(entry.getName())?part:data);out.closeEntry();}
        }
        byte[] bytes=output.toByteArray();String source=DocumentParseProbe.sha256(bytes),partSha=DocumentParseProbe.sha256(part);
        ConsenseProperties props=new ConsenseProperties();props.getOcr().setEnabled(false);
        OcrClient noCalls=new OcrClient(){public OcrResult recognize(byte[] image){throw new AssertionError("Native DOCX content observation must not call OCR");}public boolean available(){throw new AssertionError("Native DOCX content observation must not probe OCR");}};
        DocumentParser parser=new DocumentParser(props,noCalls);
        DocumentParser.ParsedDocument normal=parser.parse("native-content.docx",bytes),template=parser.parseTemplate("SCC","native-content.docx",bytes);
        JsonNode normalMembers=normal.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("members"),templateMembers=template.getCoverage().getNativeDocxParagraphs().path("paragraphs");
        assertEquals(source,normal.getSourceSha256());assertEquals(source,template.getSourceSha256());assertEquals(contents.length,normalMembers.size());assertEquals(contents.length,templateMembers.size());
        for(int n=0;n<contents.length;n++){
            String path="/{"+word+"}document[1]/{"+word+"}body[1]/{"+word+"}p["+(n+1)+"]";
            for(JsonNode member:Arrays.asList(normalMembers.get(n),templateMembers.get(n))){
                assertEquals(n<3,member.path("nativeContentEmpty").asBoolean(),"DOM content case "+n);assertEquals(n<3,member.has("nativeContentEmpty"),"Positive fact only, case "+n);
                assertEquals(n,member.path("ordinalZeroBased").asInt());assertEquals("body",member.path("storyKind").asText());
                assertEquals(source,member.path("sourcePosition").path("sourceSha256").asText());assertEquals("word/document.xml",member.path("sourcePosition").path("partName").asText());assertEquals(partSha,member.path("sourcePosition").path("partSha256").asText());assertEquals(path,member.path("sourcePosition").path("nodePath").asText());
                assertEquals("docx-p:"+DocumentParseProbe.sha256((source+"|word/document.xml|"+path).getBytes(StandardCharsets.UTF_8)),member.path("id").asText());
                assertEquals(n==8?"[unresolved Word math]":"",member.path("textObservation").path("text").asText());
            }
            assertEquals(normalMembers.get(n).path("sourcePosition"),templateMembers.get(n).path("sourcePosition"));assertTrue(templateMembers.get(n).has("nativeXml"));
        }
        assertEquals(source,DocumentParseProbe.sha256(bytes));
    }

    @Test void lastPdfChildKeepsNativeBodyColumnContinuationsAcrossTheNextPageAndFurniture(){
        JsonNode evidence=analyse(childContinuationFixture()),group=referenceGroup(evidence);assertTrue(group.path("scopeComplete").asBoolean());JsonNode last=null;for(JsonNode target:group.path("targets"))if("SCC8.9(6)(c)".equals(target.path("reference").asText()))last=target;assertNotNull(last);assertTrue(last.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("c","c-line-one","c-line-two"),ids(last.path("orderedMemberIds")));assertEquals("seven",last.path("endBoundary").path("memberId").asText());assertFalse(ids(last.path("orderedMemberIds")).contains("footer-three"));assertFalse(ids(last.path("orderedMemberIds")).contains("header-four"));
    }

    @Test void lastPdfChildContinuationRequiresGlyphSourceColumnAndAnExplicitExclusiveBoundary(){
        for(int variant=0;variant<4;variant++){
            List<DocumentBlock> blocks=childContinuationFixture();DocumentBlock continuation=blocks.stream().filter(block->"c-line-two".equals(block.getId())).findFirst().orElseThrow();if(variant==0)continuation.getPdfGlyphs().clear();if(variant==1)((ObjectNode)continuation.getReadingContext()).put("sourceSha256",OTHER);if(variant==2)shiftPdfLine(continuation,.05);if(variant==3)blocks.removeIf(block->Arrays.asList("seven","next").contains(block.getId()));assertFalse(referenceGroup(analyse(blocks)).path("scopeComplete").asBoolean(),"Unsafe PDF continuation "+variant);
        }
    }

    @Test void aBodyColumnClauseReferenceCanContinueAChildButANewSourceHeadingStillEndsIt(){
        for(boolean newHeading:Arrays.asList(false,true)){
            List<DocumentBlock> blocks=childContinuationFixture();int at=0;while(!"c-line-two".equals(blocks.get(at).getId()))at++;DocumentBlock continuation=line("c-line-two",4,newHeading?"SCC8.10 Independent source obligations":"SCC8.9 of the Special Conditions of Contract or otherwise.",.20);shiftPdfLine(continuation,.02);blocks.set(at,continuation);JsonNode group=referenceGroup(analyse(blocks)),last=null;for(JsonNode target:group.path("targets"))if("SCC8.9(6)(c)".equals(target.path("reference").asText()))last=target;assertNotNull(last);assertTrue(last.path("scopeComplete").asBoolean());assertEquals(!newHeading,ids(last.path("orderedMemberIds")).contains("c-line-two"));assertEquals(newHeading?"c-line-two":"seven",last.path("endBoundary").path("memberId").asText());
        }
    }

    private static List<DocumentBlock> childContinuationFixture(){
        String header="HKHA Standard Library of SCC for Building Works 2026 Edition";DocumentBlock first=line("c-line-one",3,"continues in the same observed body column",.80),second=line("c-line-two",4,"and ends on the following physical page.",.20);shiftPdfLine(first,.02);shiftPdfLine(second,.02);
        return new ArrayList<>(Arrays.asList(line("header-three",3,header,.03),line("owner",3,"SCC8.9 Source obligations",.15),line("note",3,"[Guidance Note: Objects in the Clauses SCC8.9(6)(a), SCC8.9(6)(b) and SCC8.9(6)(c) are to be deleted as appropriate.]",.25),line("six",3,"(6) The source obligations include:-",.35),line("a",3,"(a) First requirement.",.45),line("b",3,"(b) Second requirement.",.55),line("c",3,"(c) The last requirement",.70),first,line("footer-three",3,"3",.96),line("header-four",4,header,.03),second,line("seven",4,"(7) A separate source obligation.",.40),line("next",4,"SCC8.10 The next source clause",.60),line("footer-four",4,"4",.96)));
    }
    private static JsonNode referenceGroup(JsonNode evidence){for(JsonNode group:evidence.path("groups"))if("explicit_reference_scope".equals(group.path("kind").asText()))return group;fail("Missing explicit PDF reference group");return null;}
    private static void shiftPdfLine(DocumentBlock block,double delta){double[] box=block.getBbox().clone();box[0]+=delta;block.setBbox(box);for(PdfPageEvidence.PdfGlyph glyph:block.getPdfGlyphs()){double[] glyphBox=glyph.getBbox().clone();glyphBox[0]+=delta;glyph.setBbox(glyphBox);double[] matrix=glyph.getTextMatrix().clone();matrix[4]+=delta*595;glyph.setTextMatrix(matrix);double[] position=glyph.getPdfboxPosition().clone();position[0]+=delta*595;position[4]+=delta*595;glyph.setPdfboxPosition(position);}}

    private static List<DocumentBlock> fixture(){return new ArrayList<>(Arrays.asList(line("owner",7,"SCC8.9 Complete source clause",.1),line("note",7,"[Guidance Note: For use where the stated fact applies; insert both SCC8.9(1) and SCC8.9(2), and check SCC1.4.]",.2),line("one",7,"(1) First complete requirement.",.3),line("two",7,"(2) Second complete requirement.",.5),line("next",7,"SCC8.10 Next clause",.7)));}
    private static JsonNode analyse(List<DocumentBlock> blocks){return OerTemplateEvidence.analyse("SCC",new DocumentParser.ParsedDocument("",Collections.emptyList(),9,false,null,blocks,new ParseCoverage(),SHA,Collections.emptyList()));}
    private static JsonNode scope(JsonNode evidence,String reference){for(JsonNode group:evidence.path("groups"))if("source_unit_instruction_scope".equals(group.path("kind").asText())&&reference.equals(group.path("sourceReference").asText()))return group;fail("Missing source scope "+reference);return null;}
    private static boolean completeScope(JsonNode evidence,String reference){for(JsonNode group:evidence.path("groups"))if("source_unit_instruction_scope".equals(group.path("kind").asText())&&reference.equals(group.path("sourceReference").asText())&&group.path("scopeComplete").asBoolean())return true;return false;}
    private static List<String> ids(JsonNode values){List<String> out=new ArrayList<>();values.forEach(value->out.add(value.asText()));return out;}
    private static DocumentBlock line(String id,int page,String text,double top){
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind("pdf_line");block.setSource("native");block.setPageNo(page);block.setLocation("pdf-page/"+page+"/"+id);block.setText(text);block.setOriginalText(text);block.setBbox(new double[]{.1,top,.8,.01});ObjectNode context=JsonUtils.mapper().createObjectNode();context.put("sourceSha256",SHA);block.setReadingContext(context);
        for(int n=0;n<text.length();n++){if(Character.isWhitespace(text.charAt(n)))continue;double x=.1+n*.005,w=595,h=842,baseline=(top+.01)*h;PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId("pdf-page/"+page+"/glyph/"+id+"/"+n);glyph.setUnicode(text.substring(n,n+1));glyph.setCoordinateSpace("normalized_rendered_crop_top_left");glyph.setEligibleForNativeText(true);glyph.setFontSize(9);glyph.setFontSizeInPt(9);glyph.setDirection(0);glyph.setBbox(new double[]{x,top,.004,.01});glyph.setTextMatrix(new double[]{9,0,0,9,x*w,h-baseline});glyph.setPdfboxPosition(new double[]{x*w,baseline,.004*w,.01*h,x*w,baseline,.004*w,.01*h,w,h});block.getPdfGlyphs().add(glyph);}return block;
    }
    private static DocumentParser.ParsedDocument word(boolean foreign){
        ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.put("sourceSha256",SHA);String[] texts={"Special Conditions of Contract","SCC8.9 Complete source clause","[Guidance Note: In the Clauses SCC8.9(1) and SCC8.9(2) are to be deleted.]","(1) First complete requirement.","(2) Second complete requirement.","SCC8.10 Next clause"};
        for(int n=0;n<texts.length;n++){ObjectNode member=inventory.withArray("paragraphs").addObject();member.put("id","word-"+n);member.put("storyKind","body");member.put("storyRootNodePath","/{w}document[1]/{w}body[1]");ObjectNode position=member.putObject("sourcePosition");position.put("sourceSha256",foreign&&n==3?OTHER:SHA);position.put("partName","word/document.xml");position.put("partSha256",SHA);position.put("nodePath","/{w}document[1]/{w}body[1]/{w}p["+(n+1)+"]");ObjectNode text=member.putObject("textObservation");text.put("status","observed_native_ooxml");text.put("text",texts[n]);text.put("originalText",texts[n]);text.put("deletedText","");text.put("strikeText","");}
        ParseCoverage coverage=new ParseCoverage();coverage.setNativeDocxParagraphs(inventory);return new DocumentParser.ParsedDocument("",Collections.emptyList(),0,false,null,Collections.emptyList(),coverage,SHA,Collections.emptyList());
    }
    // Synthetic DOCX source shape only; this does not substitute for an unavailable original Word edition.
    static DocumentParser.ParsedDocument syntheticWordFromPdfLayout(boolean tableSidebar) throws Exception {
        byte[] bytes=syntheticWordBytes(tableSidebar);ConsenseProperties props=new ConsenseProperties();props.getOcr().setEnabled(false);
        OcrClient noCalls=new OcrClient(){public OcrResult recognize(byte[] image){throw new AssertionError("Synthetic native DOCX must not call OCR");}public boolean available(){throw new AssertionError("Synthetic native DOCX must not probe OCR");}};
        return new DocumentParser(props,noCalls).parseTemplate("SCC","synthetic-pdf-equivalent.docx",bytes);
    }
    static byte[] syntheticWordBytes(boolean tableSidebar) throws Exception {
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            paragraph(document,"Special Conditions of Contract");paragraph(document,"HKHA Standard Library of SCC for Building Works (synthetic 2026 edition)");
            IBody body=document;if(tableSidebar){XWPFTable table=document.createTable(1,2);XWPFTableCell margin=table.getRow(0).getCell(0);margin.getParagraphs().get(0).createRun().setText("Rev. 1");paragraph(margin,"approved on");paragraph(margin,"01.09.2026");body=table.getRow(0).getCell(1);}else{paragraph(document,"Rev. 1");paragraph(document,"approved on");paragraph(document,"01.09.2026");}
            paragraph(body,"SCC22.1 Protection of Old and Valuable Trees");paragraph(body,"[Guidance Note: For use where an Old and Valuable Tree is present on the Site.");paragraph(body,"(i) Confirm the tree information from the original source.");paragraph(body,"(ii) Insert the tree serial in SCC22.1(1) and SCC22.1(2).");paragraph(body,"(iii) Confirm the protective requirements from the original source.");paragraph(body,"(iv) Confirm the project facts before adoption.");paragraph(body,"(v) Ensure SCC1.4 and SCC22.2 are incorporated.]");
            paragraph(body,"(1) The Contractor shall protect the identified tree _____* throughout the Works.");paragraph(body,"(2) The stated lump sum for the identified tree _____* requires a project value.");paragraph(body,"Provided that the original protective obligation continues under the stated circumstances.");paragraph(document,"SCC22.2 Commencement and completion of the Works");paragraph(document,"(1) A separately observed next clause requirement.");document.write(output);return output.toByteArray();
        }
    }
    private static void paragraph(IBody body,String text){XWPFParagraph paragraph=body instanceof XWPFDocument?((XWPFDocument)body).createParagraph():((XWPFTableCell)body).addParagraph();paragraph.createRun().setText(text);}
}
