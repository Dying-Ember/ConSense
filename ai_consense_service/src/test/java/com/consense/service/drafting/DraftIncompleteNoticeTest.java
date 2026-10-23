package com.consense.service.drafting;

import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftIncompleteNoticeTest {
    @Test void deliveryReceiptBindsOnlyNewNoticeParagraphsAndSurvivesReconciliation() throws Exception {
        byte[] original;try(XWPFDocument source=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            source.createParagraph().createRun().setText("Original contract wording");source.write(out);original=out.toByteArray();
        }
        DraftIncompleteNotice.Delivery delivery=DraftIncompleteNotice.applyWithReceipt(original,Arrays.asList("First pending question","Second pending question"));
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();
        com.consense.document.DocxTemplateEditor.TemplateIndex index=editor.inspect(delivery.bytes);
        List<Map<String,Object>> bindings=new ArrayList<>();DraftIncompleteNotice.bindDelivery(bindings,index,delivery);
        assertEquals(4,bindings.size());assertEquals("before",bindings.get(0).get("deliveryPosition"));assertEquals("after",bindings.get(3).get("deliveryPosition"));
        for(Map<String,Object> row:bindings){assertNull(row.get("sourceParagraphId"));assertEquals("delivery_notice",row.get("applicationStatus"));assertFalse(row.get("text").toString().contains("Original contract wording"));}
        assertEquals(bindings,DraftDocumentBindings.reconcile(bindings,index,false));
        assertThrows(IllegalStateException.class,()->DraftIncompleteNotice.bindDelivery(new ArrayList<>(),editor.inspect(original),delivery));
    }
    @Test void sourceSeedLabelUsesOriginalExcerptInsteadOfInternalHash(){
        Map<String,Object> graph=com.consense.common.JsonUtils.readMap("{\"sources\":[{\"docId\":\"64\",\"fileName\":\"NTT.docx\"}],\"graph\":{\"variables\":[{\"id\":\"seed\",\"definitionKind\":\"source_seed\",\"nameZh\":\"guidance:secret-hash\",\"sourceAnchors\":[{\"docId\":\"64\",\"text\":\"Use Appendix G1a\"}]}]}}");
        Map<String,Object> plan=com.consense.common.JsonUtils.readMap("{\"unresolved\":[{\"kind\":\"NodeReviewRequired\",\"inputKeys\":[\"seed\"]}]}");
        String item=DraftIncompleteNotice.outstanding(graph,plan).get(0);
        assertTrue(item.contains("NTT.docx"));assertTrue(item.contains("Use Appendix G1a"));assertFalse(item.contains("secret-hash"));assertFalse(item.contains("guidance:"));
    }
    @Test void fileListKeepsGlobalAndUnknownIssuesAndSummarizesExplicitOtherDocuments(){
        Map<String,Object> plan=com.consense.common.JsonUtils.readMap("{\"unresolved\":[{\"kind\":\"SourceFormat\",\"document\":\"SCC\"},{\"kind\":\"SourceFormat\",\"document\":\"NTT\"},{\"kind\":\"InputMissing\",\"inputKeys\":[\"contractTitle\"]},{\"kind\":\"unknown\",\"document\":\"unrecognized\"}]}");
        List<String> items=DraftIncompleteNotice.outstanding(Collections.emptyMap(),plan,"NTT");
        assertEquals(4,items.size());assertTrue(items.get(0).contains("NTT"));assertTrue(items.get(0).contains("页眉页脚"));assertTrue(items.get(1).contains("合约编号"));assertTrue(items.get(2).contains("项目文稿"));assertTrue(items.get(3).contains("SCC 文稿另有 1"));
        Map<String,Object> onlyOther=com.consense.common.JsonUtils.readMap("{\"unresolved\":[{\"kind\":\"SourceFormat\",\"document\":\"SCC\"}]}");
        assertFalse(DraftIncompleteNotice.outstanding(Collections.emptyMap(),onlyOther,"NTT").isEmpty());
        assertTrue(DraftIncompleteNotice.outstanding(Collections.emptyMap(),Collections.emptyMap(),"NTT").isEmpty());
    }
    @Test void groupsReasonsForExactSubjectWithoutMergingSameNamedQuestionsOrUnknowns(){
        Map<String,Object> graph=com.consense.common.JsonUtils.readMap("{\"graph\":{\"variables\":[{\"id\":\"a\",\"nameZh\":\"同名问题\"},{\"id\":\"b\",\"nameZh\":\"同名问题\"}]}}");
        Map<String,Object> plan=com.consense.common.JsonUtils.readMap("{\"unresolved\":[{\"kind\":\"NodeReviewRequired\",\"inputKeys\":[\"a\"]},{\"kind\":\"InputMissing\",\"inputKeys\":[\"a\"]},{\"kind\":\"InputMissing\",\"inputKeys\":[\"a\"]},{\"kind\":\"InputMissing\",\"inputKeys\":[\"b\"]},{\"kind\":\"unknown1\"},{\"kind\":\"unknown2\"}]}");
        String before=com.consense.common.JsonUtils.write(plan);
        List<String> items=DraftIncompleteNotice.outstanding(graph,plan);
        assertEquals(4,items.size());assertTrue(items.get(0).contains("问题或规则尚未确认"));assertTrue(items.get(0).contains("答案尚未明确采用"));
        assertEquals(1,items.get(0).split("答案尚未明确采用",-1).length-1);
        assertTrue(items.get(1).startsWith("同名问题"));assertEquals(before,com.consense.common.JsonUtils.write(plan));
    }
    @Test void outstandingListUsesBusinessLabelsAndNeverRawDiagnosticIdentifiers(){
        Map<String,Object> graph=com.consense.common.JsonUtils.readMap("{\"sources\":[{\"docId\":\"17\",\"fileName\":\"SCT.docx\"}],\"graph\":{\"variables\":[{\"id\":\"internal-node-123\",\"nameZh\":\"预制立面\",\"editTargets\":[{\"id\":\"internal-target-456\",\"docId\":\"17\",\"text\":\"Tender option\"}]}]}}");
        Map<String,Object> plan=com.consense.common.JsonUtils.readMap("{\"unresolved\":[{\"kind\":\"NodeReviewRequired\",\"inputKeys\":[\"internal-node-123\"]},{\"kind\":\"OverlappingEdits\",\"inputKeys\":[\"internal-target-456\"]},{\"kind\":\"InputMissing\",\"inputKeys\":[\"contractTitle\"]},{\"kind\":\"unexpected_internal_error\",\"message\":\"secret-hash-and-node-id\"}]}");
        List<String> items=DraftIncompleteNotice.outstanding(graph,plan);assertEquals(4,items.size());assertTrue(items.get(0).contains("预制立面"));assertTrue(items.get(1).contains("SCT.docx"));assertTrue(items.get(1).contains("Tender option"));assertTrue(items.get(2).contains("合约编号"));
        assertFalse(items.toString().contains("internal-"));assertFalse(items.toString().contains("secret-hash"));assertFalse(items.toString().contains("unexpected_internal_error"));
        assertTrue(DraftIncompleteNotice.outstanding(graph,Collections.emptyMap()).isEmpty());
    }
    @Test void partialCopyContainsNoticeAndListWhileCompleteCopyKeepsExactBytes() throws Exception {
        byte[] original;
        try(XWPFDocument source=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            source.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Project identity");
            source.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Contract number");
            source.createParagraph().createRun().setText("Unresolved source clause remains unchanged.");
            source.createTable(1,1).getRow(0).getCell(0).setText("Existing table");source.write(out);original=out.toByteArray();
        }
        byte[] before=original.clone();
        byte[] marked=DraftIncompleteNotice.apply(original,Arrays.asList("SCC: Price fluctuation reference needs review.","SCT: Confirm the applicable facade option."));
        assertArrayEquals(before,original);
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(marked))) {
            assertTrue(doc.getParagraphs().get(0).getText().startsWith(DraftIncompleteNotice.TITLE));
            assertEquals("Unresolved source clause remains unchanged.",doc.getParagraphs().get(1).getText());
            assertEquals("Existing table",doc.getTables().get(0).getRow(0).getCell(0).getText());
            assertTrue(doc.getHeaderList().get(0).getText().contains("Project identity"));assertTrue(doc.getFooterList().get(0).getText().contains("Contract number"));
            assertTrue(doc.getParagraphs().get(2).isPageBreak());
            assertEquals("1. SCC: Price fluctuation reference needs review.",doc.getParagraphs().get(3).getText());
            assertEquals("2. SCT: Confirm the applicable facade option.",doc.getParagraphs().get(4).getText());
        }
        assertSame(original,DraftIncompleteNotice.apply(original,Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->DraftIncompleteNotice.apply(original,Collections.singletonList(" ")));
    }
}
