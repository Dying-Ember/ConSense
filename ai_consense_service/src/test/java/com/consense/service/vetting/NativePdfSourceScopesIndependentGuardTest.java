package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.PdfPageEvidence.PdfFormField;
import com.consense.document.PdfPageEvidence.PdfGlyph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent public-build guards authored from reviewed native PDF observations. */
class NativePdfSourceScopesIndependentGuardTest {
    private static final String SHA="c".repeat(64), FRAME="normalized_rendered_crop_top_left";

    @Test void exactPrefixedRootsReferencesAndFieldNamesUseOneIdentityWithoutBareNumberAliases() {
        for(String prefix:Arrays.asList("SCC","GCT","GCC")) {
            String root=prefix+"7.6", child=root+"(1)";
            List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(
                heading("root",1,.10,.10,root+" Native contractual title"),
                line("one",1,.16,.15,"(1) Supply the stated items."),
                line("reference",1,.18,.18,"See condition "+child+" for the duty."),
                line("bare-reference",1,.18,.20,"See condition 7.6(1) for the other source."),
                line("two",1,.16,.24,"(2) Another stated item."),
                heading("next",1,.10,.30,prefix+"7.7 Next actual title")));
            blocks.add(form("note",1,"Clause"+child,"See native requirement"));
            blocks.add(form("bare-note",1,"Clause7.6(1)","See native requirement"));
            JsonNode result=build(blocks),parent=group(result,"source_clause",root),first=group(result,"source_clause",child);
            assertEquals(parent.path("id"),first.path("parentId"),prefix);
            assertEquals(Arrays.asList("root","one","reference","bare-reference","two"),strings(parent.path("bodyBlockIds")),prefix);
            assertEquals("next",parent.path("endBoundary").path("blockId").asText(),prefix);
            assertEquals(4,countKind(result,"source_clause"),prefix+" body references cannot create headings");
            JsonNode exact=reference(result,child),bare=reference(result,"7.6(1)");
            assertEquals(first.path("id"),exact.path("targetGroupId"),prefix);
            assertEquals("unknown",bare.path("status").asText(),prefix);
            assertTrue(bare.path("targetGroupId").isNull(),prefix);
            assertEquals(first.path("id"),group(result,"source_margin_note",child).path("parentId"),prefix);
            assertTrue(group(result,"source_margin_note",child).path("scopeComplete").asBoolean(),prefix);
            assertFalse(group(result,"source_margin_note","7.6(1)").path("scopeComplete").asBoolean(),prefix);
            assertFalse(first.path("automaticEditEligible").asBoolean());
            assertFalse(first.path("semanticScopeVerified").asBoolean());
        }
    }

    @Test void mixedSmallRevisionAndBodyRunsKeepMainNumberAndWholeImmutableNativeBlock() {
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(heading("root",1,.10,.10,"SCC9.12 Warranty requirements"));
        blocks.add(mixed("mixed-one",1,.15,.20,"Rev. 3 ","(1) Supply equipment meeting the definition."));
        blocks.add(sizedLine("revision-description",1,.10,.17,"approved on",6));
        blocks.add(mixed("mixed-date",1,.19,.22,"17.04.2031 ","The stated definition continues here."));
        blocks.add(line("next-child",1,.20,.24,"(2) Retain the specified record."));
        blocks.add(heading("next-root",1,.10,.30,"SCC9.13 Next source requirement"));
        String before=JsonUtils.write(blocks);
        JsonNode result=build(blocks),root=group(result,"source_clause","SCC9.12"),one=group(result,"source_clause","SCC9.12(1)");
        assertTrue(strings(one.path("bodyBlockIds")).containsAll(Arrays.asList("mixed-one","mixed-date")));
        assertEquals(root.path("id"),one.path("parentId"));
        assertEquals("next-child",one.path("endBoundary").path("blockId").asText());
        assertFalse(hasReference(result,"17.04.2031"),"Revision ink cannot become a root clause");
        assertEquals("mixed-one",one.path("sourcePosition").path("blockId").asText(),"Identity remains the original mixed native block");
        assertEquals(before,JsonUtils.write(blocks),"Native text, glyphs, font evidence and IDs must remain unchanged");
    }

    @Test void dateShapedMainBodyNumberRemainsSupportedInsteadOfGlobalDateBlacklisting() {
        List<DocumentBlock> blocks=Arrays.asList(
            line("date-clause",1,.10,.10,"17.04.2031 A genuine observed numbered provision"),
            line("date-body",1,.165,.14,"Its body remains source content."),
            line("peer",1,.10,.20,"18. Next observed provision"));
        JsonNode date=group(build(blocks),"source_clause","17.04.2031");
        assertEquals("date-clause",date.path("startBoundary").path("blockId").asText());
        assertEquals("peer",date.path("endBoundary").path("blockId").asText());
    }

    @Test void boldPrefixInsideNormalBodyIsNotAWholeBoldNativeTitle() {
        for(String prefix:Arrays.asList("SCC","GCT","GCC")) {
            DocumentBlock reference=line("emphasized-reference",1,.18,.19,prefix+"7.8 governs the item in this existing paragraph.");
            int emphasized=(prefix+"7.8").length();for(int i=0;i<emphasized;i++)reference.getPdfGlyphs().get(i).setFontName("Times New Roman,Bold");
            List<DocumentBlock> blocks=Arrays.asList(
                heading("root",1,.10,.10,prefix+"7.6 Genuine complete bold title"),
                line("one",1,.16,.15,"(1) The existing source provision starts here."),
                reference,
                line("two",1,.16,.25,"(2) The actual next source provision."),
                heading("next",1,.10,.35,prefix+"7.7 Genuine next bold title"));
            JsonNode result=build(blocks),one=group(result,"source_clause",prefix+"7.6(1)");
            assertFalse(hasReference(result,prefix+"7.8"),"A short bold citation cannot become a title: "+prefix);
            assertEquals(Arrays.asList("one","emphasized-reference"),strings(one.path("bodyBlockIds")),prefix);
            assertEquals("two",one.path("endBoundary").path("blockId").asText(),prefix);
            assertEquals(4,countKind(result,"source_clause"),prefix);
        }
    }

    @Test void observedRevisionLaneCannotEraseSmallMainNumberFarOutsideItsVerticalBand() {
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(heading("root",1,.10,.10,"SCC9.12 Actual bold source title"));
        blocks.add(mixed("mixed-one",1,.15,.20,"Rev. 3 ","(1) Observed native definition."));
        blocks.add(mixed("mixed-date",1,.19,.22,"17.04.2031 ","The definition continues here."));
        blocks.add(line("next-child",1,.20,.24,"(2) Another native duty."));
        blocks.add(sizedLine("small-main-number",1,.10,.60,"9.8 A genuine smaller native numbered provision.",6));
        blocks.add(line("next-number",1,.10,.70,"10.1 A genuine later native numbered provision."));
        String before=JsonUtils.write(blocks);JsonNode result=build(blocks),small=group(result,"source_clause","9.8");
        assertEquals("small-main-number",small.path("startBoundary").path("blockId").asText());
        assertEquals("next-number",small.path("endBoundary").path("blockId").asText());
        assertEquals(before,JsonUtils.write(blocks));
    }

    @Test void disconnectedRevisionBandsCannotEraseSmallMainNumberInTheGapBetweenThem() {
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(heading("upper-root",1,.10,.10,"SCC9.12 Actual upper source title"));
        blocks.add(mixed("upper-mixed",1,.15,.20,"Rev. 3 ","(1) The upper native source definition."));
        blocks.add(sizedLine("middle-main-number",1,.10,.45,"9.8 A genuine smaller numbered provision between the two revisions.",6));
        blocks.add(heading("lower-root",1,.10,.70,"SCC9.13 Actual lower source title"));
        blocks.add(mixed("lower-mixed",1,.75,.20,"Rev. 4 ","(1) The lower native source definition."));
        blocks.add(line("next-number",1,.10,.90,"10.1 The next actual native numbered provision."));
        String before=JsonUtils.write(blocks);JsonNode result=build(blocks),middle=group(result,"source_clause","9.8");
        assertEquals("middle-main-number",middle.path("startBoundary").path("blockId").asText());
        assertEquals(Collections.singletonList("middle-main-number"),strings(middle.path("bodyBlockIds")));
        assertEquals("lower-root",middle.path("endBoundary").path("blockId").asText());
        assertEquals(before,JsonUtils.write(blocks),"Independent sidebands cannot remove intervening native source observations");
    }

    @Test void explicitAlignedNextPageSiblingAndAncestorKeepQualifierWithDThenCloseAtE() {
        List<DocumentBlock> blocks=continuationFixture(2);
        String before=JsonUtils.write(blocks);
        JsonNode result=build(blocks),parent=group(result,"source_clause","GCT8.9(4)"),d=group(result,"source_clause","GCT8.9(4)(d)"),e=group(result,"source_clause","GCT8.9(4)(e)");
        assertEquals(Arrays.asList("d","q1","q2","q3","q4","q5","q6"),strings(d.path("bodyBlockIds")));
        assertEquals(Arrays.asList(1,2),integers(d.path("physicalPageNos")));
        assertTrue(d.path("scopeComplete").asBoolean(),d.toString());
        assertEquals("e",d.path("endBoundary").path("blockId").asText());
        assertEquals(parent.path("id"),e.path("parentId"));
        assertEquals(e.path("id"),group(result,"source_clause","GCT8.9(4)(e)(i)").path("parentId"));
        assertEquals(e.path("id"),group(result,"source_clause","GCT8.9(4)(e)(ii)").path("parentId"));
        assertEquals("five",e.path("endBoundary").path("blockId").asText());
        assertEquals(before,JsonUtils.write(blocks));
    }

    @Test void missingPhysicalPageCannotBorrowSameIndentAndLiteralSiblingAsContinuity() {
        JsonNode result=build(continuationFixture(3)),d=group(result,"source_clause","GCT8.9(4)(d)");
        assertFalse(d.path("scopeComplete").asBoolean());
        assertFalse(strings(d.path("bodyBlockIds")).contains("q3"));
        assertTrue(d.path("unknownReasons").size()>0);
    }

    @Test void unprovenNextPageBodyWithoutObservedAnchorsRemainsUnknown() {
        List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(
            heading("root",1,.10,.10,"SCC6.4 Native title"),
            line("one",1,.16,.20,"(1) Observed body starts here."),
            line("unknown-next",2,.18,.10,"Possible continuation with no numbered anchor.")));
        JsonNode one=group(build(blocks),"source_clause","SCC6.4(1)");
        assertFalse(one.path("scopeComplete").asBoolean());
        assertEquals(Collections.singletonList("one"),strings(one.path("bodyBlockIds")));
    }

    @Test void foreignSourceOrWrongGlyphFrameCannotRepairAnOtherwiseUnprovenContinuation() {
        for(boolean foreign:new boolean[]{true,false}) {
            List<DocumentBlock> blocks=continuationFixture(2);
            DocumentBlock q3=blocks.stream().filter(block->"q3".equals(block.getId())).findFirst().orElseThrow();
            if(foreign)((ObjectNode)q3.getReadingContext()).put("sourceSha256","d".repeat(64));
            else q3.getPdfGlyphs().get(0).setCoordinateSpace("appearance_stream_local_matrix_no_page_box");
            JsonNode result=build(blocks),d=group(result,"source_clause","GCT8.9(4)(d)");
            assertFalse(d.path("scopeComplete").asBoolean(),"foreign="+foreign);
            assertFalse(strings(d.path("bodyBlockIds")).contains("q3"));
            assertTrue(result.path("issues").toString().contains("q3"));
        }
    }

    @Test void unprovenSameBaselineTableColumnsStayVisibleAsContextAndCannotBecomeClauses() {
        List<DocumentBlock> blocks=Arrays.asList(
            heading("root",1,.10,.10,"GCC4.2 Native source table"),
            line("one",1,.16,.15,"(1) The following items are stated:"),
            line("table-left",1,.18,.20,"a. Structural item"),
            line("table-right",1,.70,.20,"Ten years"),
            line("two",1,.16,.30,"(2) Next observed duty."),
            heading("next",1,.10,.40,"GCC4.3 Next observed title"));
        String before=JsonUtils.write(blocks);JsonNode result=build(blocks),one=group(result,"source_clause","GCC4.2(1)");
        assertFalse(one.path("scopeComplete").asBoolean(),"No complete physical grid carrier was supplied");
        assertTrue(result.path("contextBlocks").toString().contains("table-left"));
        assertTrue(result.path("contextBlocks").toString().contains("table-right"));
        assertEquals(4,countKind(result,"source_clause"));
        assertEquals(before,JsonUtils.write(blocks));
    }

    private List<DocumentBlock> continuationFixture(int secondPage) {
        return new ArrayList<>(Arrays.asList(
            line("header-one",1,.10,.02,"Repeated native contract header"),
            heading("root",1,.10,.10,"GCT8.9 Native warranty title"),
            line("four",1,.16,.30,"(4) The warranty shall provide:"),
            line("d",1,.22,.70,"(d) All relevant periods are defined here;"),
            line("q1",1,.24,.82,"and each period begins on the date of"),
            line("q2",1,.24,.84,"completion certified by the named manager,"),
            line("footer-one",1,.80,.96,"8"),
            line("header-two",secondPage,.10,.02,"Repeated native contract header"),
            line("q3",secondPage,.24,.10,"but where more than one certificate is issued,"),
            line("q4",secondPage,.24,.12,"the applicable date is the completion of"),
            line("q5",secondPage,.24,.14,"the relevant section in which the item is situated"),
            line("q6",secondPage,.24,.16,"under the stated general conditions; and"),
            line("e",secondPage,.22,.20,"(e) The stated equipment shall be:"),
            line("i",secondPage,.28,.24,"(i) Free from every stated defect;"),
            line("ii",secondPage,.28,.28,"(ii) Compliant with the specified requirement."),
            line("five",secondPage,.16,.36,"(5) Keep all records available.")));
    }

    private JsonNode build(List<DocumentBlock> blocks) {
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",false);
        return NormalPdfSourceGroups.build(blocks,SHA,coverage);
    }
    private DocumentBlock line(String id,int page,double x,double y,String text) {return sizedLine(id,page,x,y,text,10);}
    private DocumentBlock heading(String id,int page,double x,double y,String text) {
        DocumentBlock block=line(id,page,x,y,text);for(PdfGlyph glyph:block.getPdfGlyphs())glyph.setFontName("Times New Roman,Bold");return block;
    }
    private DocumentBlock sizedLine(String id,int page,double x,double y,String text,double font) {
        DocumentBlock block=base(id,page,text);List<PdfGlyph> glyphs=new ArrayList<>();append(glyphs,id,page,x,y,text,font);
        block.setPdfGlyphs(glyphs);block.setBbox(new double[]{x,y,text.length()*font*.0005,font*.001});return block;
    }
    private DocumentBlock mixed(String id,int page,double y,double bodyX,String margin,String body) {
        DocumentBlock block=base(id,page,margin+body);List<PdfGlyph> glyphs=new ArrayList<>();
        append(glyphs,id,page,.10,y,margin,6);append(glyphs,id,page,bodyX,y+.002,body,10);
        block.setPdfGlyphs(glyphs);block.setBbox(new double[]{.10,y,bodyX-.10+body.length()*.005,.012});return block;
    }
    private DocumentBlock base(String id,int page,String text) {
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind("pdf_line");block.setSource("native");block.setPageNo(page);block.setText(text);block.setOriginalText(text);
        ObjectNode context=JsonUtils.mapper().createObjectNode();context.put("sourceSha256",SHA);block.setReadingContext(context);return block;
    }
    private void append(List<PdfGlyph> glyphs,String id,int page,double x,double y,String text,double font) {
        for(int offset=0;offset<text.length();) {
            int point=text.codePointAt(offset);String value=new String(Character.toChars(point));double w=font*.0005;
            PdfGlyph glyph=new PdfGlyph();glyph.setId("pdf-page/"+page+"/glyph/"+id+"/"+glyphs.size());glyph.setUnicode(value);glyph.setCoordinateSpace(FRAME);glyph.setEligibleForNativeText(true);
            glyph.setBbox(new double[]{x,y,w,font*.001});glyph.setTextMatrix(new double[]{font,0,0,font,x*1000,(1-y)*1000});glyph.setFontName("Times New Roman");glyph.setFontSize(font);glyph.setFontSizeInPt(font);glyph.setPdfboxPosition(new double[]{0,0,0,0,0,0,0,0,1000,1000});glyphs.add(glyph);x+=w;offset+=value.length();
        }
    }
    private DocumentBlock form(String id,int page,String name,String display) {
        DocumentBlock block=base(id,page,display);block.setKind("pdf_form_widget");block.setSource("native_form");block.setBbox(new double[]{.02,.15,.05,.02});
        PdfFormField field=new PdfFormField();field.setId("pdf-form/"+id+"/widget/0");field.setFieldName(name);field.setFieldValue(display);field.setAppearanceValue(display);field.setPageNo(page);field.setAppearancePresent(true);field.setAppearanceGlyphObservationStatus("observed_existing_normal_AP_operators_unreviewed");
        PdfGlyph glyph=new PdfGlyph();glyph.setId(field.getId()+"/glyph/0");glyph.setUnicode(display);glyph.setCoordinateSpace("appearance_stream_local_matrix_no_page_box");glyph.setTextMatrix(new double[]{8,0,0,8,2,20});glyph.setEligibleForNativeText(true);field.getAppearanceGlyphs().add(glyph);block.setPdfFormField(field);return block;
    }
    private JsonNode group(JsonNode value,String kind,String reference) {
        List<JsonNode> found=new ArrayList<>();for(JsonNode node:value.path("groups"))if(kind.equals(node.path("kind").asText())&&reference.equals(node.path("reference").asText()))found.add(node);
        assertEquals(1,found.size(),kind+":"+reference);return found.get(0);
    }
    private JsonNode reference(JsonNode value,String target) {for(JsonNode edge:value.path("relations"))if("source_literal_reference".equals(edge.path("type").asText())&&target.equals(edge.path("targetReference").asText()))return edge;fail("Missing exact literal reference "+target);return null;}
    private boolean hasReference(JsonNode value,String target) {for(JsonNode node:value.path("groups"))if(target.equals(node.path("reference").asText()))return true;return false;}
    private int countKind(JsonNode value,String kind) {int count=0;for(JsonNode node:value.path("groups"))if(kind.equals(node.path("kind").asText()))count++;return count;}
    private List<String> strings(JsonNode values) {List<String> out=new ArrayList<>();values.forEach(value->out.add(value.asText()));return out;}
    private List<Integer> integers(JsonNode values) {List<Integer> out=new ArrayList<>();values.forEach(value->out.add(value.asInt()));return out;}
}
