package com.consense.service.export;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EnglishFileOutputTest {
    @TempDir Path root;
    final LlmClient client=mock(LlmClient.class);
    final Map<String,String> translated=new LinkedHashMap<>();
    final List<String> submittedTexts=new ArrayList<>();
    final AtomicInteger calls=new AtomicInteger();
    EnglishFileOutput output(){
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(root.toString());when(client.available()).thenReturn(true);
        doAnswer(call->{
            calls.incrementAndGet();List<LlmClient.ChatTurn> turns=call.getArgument(0);JsonNode input=JsonUtils.parse(turns.get(1).getContent());ArrayNode answer=JsonUtils.mapper().createArrayNode();
            for(JsonNode item:input){String text=item.path("text").asText();submittedTexts.add(text);boolean changed=translated.containsKey(text);answer.addObject().put("id",item.path("id").asText()).put("translated",changed?1:0).put("text",changed?translated.get(text):"");}
            return JsonUtils.write(answer);
        }).when(client).chatStructured(anyList(),any());return new EnglishFileOutput(new AiGateway(client),new QwenChatUsagePreflight(props,null),props);
    }
    @Test void translatesDeliveryParagraphsAndMetadataButPreservesOriginalPackageAndMachineBindings() throws Exception {
        translated.put("认证后 14 天付款。","Payment is due 14 days after certification.");translated.put("验收报告","Acceptance report");translated.put("作者","Author");
        byte[] source=docx("<w:p><w:bookmarkStart w:id=\"7\" w:name=\"source_abc\"/><w:r><w:t>认证后 </w:t></w:r><w:r><w:t>14 天付款。</w:t></w:r></w:p>");byte[] before=source.clone();
        EnglishFileOutput exporter=output();byte[] result=exporter.docx(source,"snapshot:one",()->{});
        Map<String,byte[]> zip=unzip(result);String body=new String(zip.get("word/document.xml"),StandardCharsets.UTF_8);
        assertTrue(body.contains("English translation: Payment is due 14 days after certification."));assertTrue(body.contains("source_abc"));assertTrue(body.contains("w:id=\"7\""));
        assertFalse(EnglishFileOutput.docxNeedsTranslation(result));assertArrayEquals(before,source);assertArrayEquals(new byte[]{1,2,3},zip.get("word/media/opaque.bin"));
        assertArrayEquals(unzip(source).get("word/_rels/document.xml.rels"),zip.get("word/_rels/document.xml.rels"));
        assertTrue(new String(zip.get("docProps/core.xml"),StandardCharsets.UTF_8).contains("Acceptance report"));
        assertArrayEquals(result,exporter.docx(source,"snapshot:one",()->{}));assertEquals(1,calls.get(),"Word and PDF share a validated cached delivery copy");
    }
    @Test void englishValidationAndCacheKeepExactOriginalBytesIncludingUnicodeEnglishSymbols() throws Exception {
        byte[] source=docx("<w:p><w:r><w:t>English café quotation: £25 — ✓.</w:t></w:r></w:p>",false);EnglishFileOutput exporter=output();
        assertArrayEquals(source,exporter.docx(source,"snapshot:english",()->{}));assertArrayEquals(source,exporter.docx(source,"snapshot:english",()->{}));assertEquals(1,calls.get());
    }
    @Test void semanticLanguageCheckAlsoTranslatesLatinScriptForeignText() {
        translated.put("Bonjour, paiement dans 14 jours.","Hello, payment is due within 14 days.");
        assertEquals("English translation: Hello, payment is due within 14 days.",output().text("Bonjour, paiement dans 14 jours.","snapshot:french",()->{}));
    }
    @Test void jsonIsAnIndependentPresentationWithEnglishTranslationLabelsAndUnchangedTypes() {
        translated.put("待核对","Requires review");translated.put("认证后 14 天付款。","Payment is due 14 days after certification.");
        JsonNode source=JsonUtils.parse("{\"status\":\"OPEN\",\"note\":\"待核对\",\"count\":14,\"confirmed\":false,\"unknown\":null,\"evidence\":[{\"displayQuote\":\"认证后 14 天付款。\",\"originalLocated\":true}]}");String before=JsonUtils.write(source);
        JsonNode result=output().json(source,"snapshot:json",()->{});
        assertTrue(result.path("note").asText().startsWith("English translation:"));assertTrue(result.path("evidence").get(0).path("displayQuote").asText().startsWith("English translation:"));
        assertTrue(result.path("count").isIntegralNumber());assertFalse(result.path("confirmed").asBoolean());assertTrue(result.path("unknown").isNull());assertEquals(before,JsonUtils.write(source));
    }
    @Test void jsonNeverSubmitsLanguageCodesIdentifiersReferencesOrCategoricalValuesEvenWhenTheModelWouldRewriteThem(){
        translated.put("en","English");translated.put("run-42","Changed run 42");translated.put("OPEN","Open state");translated.put("1(R5)","Clause 1 reference 5");
        translated.put("待核对","Requires review");translated.put("Bonjour, paiement dans 14 jours.","Hello, payment is due within 14 days.");
        JsonNode source=JsonUtils.parse("{\"schemaVersion\":\"2.0\",\"language\":\"en\",\"reportLanguage\":\"en\",\"presentationVersion\":\"vetting-english-presentation-v2\",\"sourceRunId\":\"run-42\",\"id\":\"run-42\",\"status\":\"OPEN\",\"phase\":\"DONE\",\"code\":\"F01\",\"types\":[\"f2-iii\"],\"refs\":\"1(R5)\",\"anchor\":\"body/1/paragraph\",\"clauseId\":\"1(r5)\",\"referenceIds\":[\"R5\"],\"modelIdentity\":{\"profileId\":\"profile-42\",\"model\":\"model-42\"},\"canonicalEvidenceReference\":{\"canonicalChunkId\":\"chunk-42\"},\"inputBudget\":{\"status\":\"observed_tokens\",\"reason\":\"token_observation_missing\"},\"note\":\"待核对\",\"notes\":[\"Bonjour, paiement dans 14 jours.\"]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("sourceSnapshotSha256","abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        String before=JsonUtils.write(source);JsonNode result=output().json(source,"snapshot:mechanical",()->{});
        assertEquals(List.of("待核对","Bonjour, paiement dans 14 jours."),submittedTexts);
        for(String field:List.of("schemaVersion","language","reportLanguage","presentationVersion","sourceRunId","sourceSnapshotSha256","id","status","phase","code","types","refs","anchor","clauseId","referenceIds","modelIdentity","canonicalEvidenceReference","inputBudget"))assertEquals(source.get(field),result.get(field),field);
        assertEquals("English translation: Requires review",result.path("note").asText());assertEquals("English translation: Hello, payment is due within 14 days.",result.path("notes").get(0).asText());assertEquals(before,JsonUtils.write(source));assertEquals("english-delivery-copy-v5",EnglishFileOutput.POLICY);
    }
    @Test void jsonTranslationChangesOnlyTheReaderSlotWhenAnIdenticalStringIsAlsoAReference(){
        translated.put("OPEN","Open for review");JsonNode source=JsonUtils.parse("{\"status\":\"OPEN\",\"id\":\"OPEN\",\"note\":\"OPEN\",\"canonicalEvidenceReference\":{\"title\":\"OPEN\"}}");
        JsonNode result=output().json(source,"snapshot:shared-text",()->{});
        assertEquals(List.of("OPEN"),submittedTexts);assertEquals("English translation: Open for review",result.path("note").asText());
        assertEquals("OPEN",result.path("status").asText());assertEquals("OPEN",result.path("id").asText());assertEquals(source.get("canonicalEvidenceReference"),result.get("canonicalEvidenceReference"));
    }
    @Test void explicitNonEnglishMechanicalFieldsFailBeforeAnyModelSubmissionAndRetainTheOriginal(){
        translated.put("待核对","Requires review");EnglishFileOutput exporter=output();
        for(String mechanical:List.of("\"id\":\"原始标识\"","\"status\":\"待审核\"","\"sourceRunId\":\"原运行\"","\"refs\":[\"原条款\"]","\"canonicalEvidenceReference\":{\"unitId\":\"原单元\"}","\"modelIdentity\":{\"profileId\":\"原模型\"}","\"inputBudget\":{\"reason\":\"原原因\"}","\"unsupportedField\":\"原未知内容\"")){
            JsonNode source=JsonUtils.parse("{\"note\":\"待核对\","+mechanical+"}");String before=JsonUtils.write(source);BizException failure=assertThrows(BizException.class,()->exporter.json(source,"snapshot:unsupported-machine",()->{}));
            assertEquals(4300,failure.getCode());assertTrue(failure.getMessage().contains("mechanical JSON field at /"));assertEquals(before,JsonUtils.write(source));
        }
        assertThrows(BizException.class,()->exporter.json(JsonUtils.parse("{\"未知字段\":\"English value\"}"),"snapshot:unsupported-key",()->{}));assertEquals(0,calls.get());assertTrue(submittedTexts.isEmpty());
    }
    @Test void mechanicalOnlyJsonNeedsNoLanguageCallAndIsReturnedAsAnUnchangedIndependentCopy(){
        JsonNode source=JsonUtils.parse("{\"language\":\"en\",\"status\":\"OPEN\",\"id\":\"run-42\",\"refs\":[\"1(R5)\"],\"sourceSnapshotSha256\":\"abc42\",\"literalSourceEvidence\":false}");
        JsonNode result=output().json(source,"snapshot:codes-only",()->{});assertEquals(source,result);assertNotSame(source,result);assertEquals(0,calls.get());
    }
    @Test void derivedContractAndPackageNumbersCanBeRomanisedWithoutChangingInternalReferencesOrTheirDigits(){
        translated.put("合约 42","Contract 42");translated.put("标段 42","Package 42");JsonNode source=JsonUtils.parse("{\"project\":{\"id\":\"project-42\",\"contractNo\":\"合约 42\",\"packageRef\":\"标段 42\"},\"packageIdentityPresentation\":{\"contractNumber\":\"合约 42\"}}");
        JsonNode result=output().json(source,"snapshot:display-number",()->{});assertEquals(List.of("合约 42","标段 42"),submittedTexts);
        assertEquals("English translation: Contract 42",result.path("project").path("contractNo").asText());assertEquals("English translation: Contract 42",result.path("packageIdentityPresentation").path("contractNumber").asText());assertEquals("English translation: Package 42",result.path("project").path("packageRef").asText());assertEquals("project-42",result.path("project").path("id").asText());assertEquals("合约 42",source.path("project").path("contractNo").asText());
    }
    @Test void missingDuplicateUnchangedForeignAndChangedNumbersNeverReturnPartialFiles() {
        EnglishFileOutput.Translation row=new EnglishFileOutput.Translation();row.setId("T0");row.setTranslated(1);row.setText("Payment is due after 21 days.");
        assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("14 天付款"),List.of(row)));
        row.setTranslated(0);row.setText("");assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("14 天付款"),List.of(row)));
        assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("English"),List.of()));
        assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("English","Other"),List.of(row,row)));
        translated.put("中文","仍然中文");assertThrows(BizException.class,()->output().text("中文","snapshot:bad",()->{}));
    }
    @Test void failureAndCurrentRevisionGuardDoNotModifyTheSourceOrCreateAnAcceptedCache() throws Exception {
        byte[] source=docx("<w:p><w:r><w:t>中文</w:t></w:r></w:p>",false);byte[] before=source.clone();EnglishFileOutput exporter=output();
        assertThrows(BizException.class,()->exporter.docx(source,"snapshot:failed",()->{}));assertArrayEquals(before,source);
        assertEquals(0,Files.walk(root).filter(p->p.getFileName().toString().endsWith(".json")).count());
        translated.put("中文","Chinese text");AtomicInteger guards=new AtomicInteger();assertThrows(IllegalStateException.class,()->exporter.docx(source,"snapshot:stale",()->{if(guards.incrementAndGet()>1)throw new IllegalStateException("revision changed");}));assertArrayEquals(before,source);
    }
    @Test void filenamesUseReadableEnglishFallbackAndOpaqueNumbersAreNotStripped() {
        assertEquals("ConSense_Report_42.docx",EnglishFileOutput.fileName("测试.docx","ConSense_Report_42.docx"));
        assertEquals("Report_42.docx",EnglishFileOutput.fileName("Report:42.docx","fallback.docx"));
        assertFalse(EnglishFileOutput.hasNonEnglishText("English £42 — ✓"));assertTrue(EnglishFileOutput.hasNonEnglishText("中文"));
    }
    @Test void invalidPackagesCannotReachTheLanguageModel() {
        EnglishFileOutput exporter=output();assertThrows(BizException.class,()->exporter.docx("not a docx".getBytes(StandardCharsets.UTF_8),"snapshot:invalid",()->{}));assertEquals(0,calls.get());
    }
    @Test void numericRolesCannotBeExchangedEvenWhenTheSameDigitValuesRemain(){
        EnglishFileOutput.Translation row=new EnglishFileOutput.Translation();row.setId("T0");row.setTranslated(1);row.setText("Party A pays after 21 days; Party B pays after 14 days.");
        assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("甲方14天付款；乙方21天付款。"),List.of(row)));
        row.setText("Party A pays after 14 days; Party B pays after 21 days.");assertEquals(1,EnglishFileOutput.validate(List.of("甲方14天付款；乙方21天付款。"),List.of(row)).size());
    }
    @Test void aDeclaredTranslationCannotReturnTheUnchangedLatinForeignSentence(){
        EnglishFileOutput.Translation row=new EnglishFileOutput.Translation();row.setId("T0");row.setTranslated(1);row.setText("Bonjour, paiement dans 14 jours.");
        assertThrows(BizException.class,()->EnglishFileOutput.validate(List.of("Bonjour, paiement dans 14 jours."),List.of(row)));
    }
    @Test void nativeNumberingChartStringsCustomPropertiesAndNamespacedAuthorAreTranslated()throws Exception{
        translated.put("第%1章","Chapter %1");translated.put("工程甲","Works A");translated.put("待核对","Requires review");translated.put("作者甲","Author A");
        Map<String,byte[]> entries=unzip(docx("<w:p><w:r><w:t>Original English paragraph.</w:t></w:r></w:p>",false));
        entries.put("word/numbering.xml",("<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:lvlText w:val=\"第%1章\"/></w:lvl></w:abstractNum></w:numbering>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/charts/chart1.xml",("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart><c:tx><c:strLit><c:pt idx=\"0\"><c:v>工程甲</c:v></c:pt></c:strLit></c:tx></c:chart></c:chartSpace>").getBytes(StandardCharsets.UTF_8));
        entries.put("docProps/custom.xml",("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><property name=\"ReviewNotes\" pid=\"2\"><vt:lpwstr>待核对</vt:lpwstr></property></Properties>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/comments.xml",("<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:comment w:id=\"0\" w:author=\"作者甲\"><w:p><w:r><w:t>Original comment.</w:t></w:r></w:p></w:comment></w:comments>").getBytes(StandardCharsets.UTF_8));
        byte[] source=pack(entries),before=source.clone();Map<String,byte[]> result=unzip(output().docx(source,"snapshot:carriers",()->{}));
        assertTrue(new String(result.get("word/numbering.xml"),StandardCharsets.UTF_8).contains("Chapter %1"));
        assertTrue(new String(result.get("word/charts/chart1.xml"),StandardCharsets.UTF_8).contains("English translation: Works A"));
        assertTrue(new String(result.get("docProps/custom.xml"),StandardCharsets.UTF_8).contains("Requires review"));
        assertTrue(new String(result.get("word/comments.xml"),StandardCharsets.UTF_8).contains("Author A"));
        assertFalse(EnglishFileOutput.docxNeedsTranslation(pack(result)));assertArrayEquals(before,source);
    }
    @Test void nestedTextboxAndDrawingParagraphsAreSeparateFromTheirParentParagraph()throws Exception{
        translated.put("文本框","Textbox");translated.put("图形标签","Drawing label");
        byte[] source=docx("<w:p><w:r><w:t>Original outer paragraph.</w:t></w:r><w:r><w:drawing><w:txbxContent><w:p><w:r><w:t>文本框</w:t></w:r></w:p></w:txbxContent><a:p xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:r><a:t>图形标签</a:t></a:r></a:p></w:drawing></w:r></w:p>",false);
        String xml=new String(unzip(output().docx(source,"snapshot:nested",()->{})).get("word/document.xml"),StandardCharsets.UTF_8);
        assertTrue(xml.contains("Original outer paragraph."));assertTrue(xml.contains("English translation: Textbox"));assertTrue(xml.contains("English translation: Drawing label"));
    }
    @Test void tabsLineBreaksAndPageBreaksKeepTheirPositionWhilePlainRunsRemainOneSentence()throws Exception{
        translated.put("签发后 14 天付款。","Payment is due 14 days after certification.");translated.put("付款后21天复核。","Review 21 days after payment.");
        translated.put("下一行。","Next line.");translated.put("下一页。","Next page.");translated.put("末行。","Last line.");
        translated.put("签发后 14 天付款。付款后21天复核。下一行。下一页。末行。","Payment is due 14 days after certification. Review 21 days after payment. Next line. Next page. Last line.");
        byte[] source=docx("<w:p><w:r><w:t>签发后 </w:t></w:r><w:r><w:t>14 天付款。</w:t></w:r><w:r><w:tab/><w:t>付款后21天复核。</w:t><w:br/><w:t>下一行。</w:t><w:br w:type=\"page\"/><w:t>下一页。</w:t><w:cr/><w:t>末行。</w:t></w:r></w:p>",false),before=source.clone();
        byte[] result=output().docx(source,"snapshot:controls",()->{});org.w3c.dom.Document document=wordDocument(result);
        assertEquals(List.of("签发后 14 天付款。","付款后21天复核。","下一行。","下一页。","末行。"),submittedTexts);
        org.w3c.dom.NodeList texts=document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","t");
        assertEquals("English translation: Payment is due 14 days after certification.",texts.item(0).getTextContent());assertEquals("",texts.item(1).getTextContent());
        assertEquals("English translation: Review 21 days after payment.",document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","tab").item(0).getNextSibling().getTextContent());
        org.w3c.dom.NodeList breaks=document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","br");
        assertEquals(2,breaks.getLength());assertEquals("English translation: Next line.",breaks.item(0).getNextSibling().getTextContent());
        assertEquals("page",((org.w3c.dom.Element)breaks.item(1)).getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","type"));assertEquals("English translation: Next page.",breaks.item(1).getNextSibling().getTextContent());
        assertEquals("English translation: Last line.",document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","cr").item(0).getNextSibling().getTextContent());assertArrayEquals(before,source);
    }
    @Test void hyperlinksAndFieldResultsKeepTheirOwnTextInsteadOfAbsorbingTheParagraph()throws Exception{
        translated.put("前言。","Introduction.");translated.put("付款链接","Payment link");translated.put("后文。","Following text.");translated.put("字段结果","Field result");translated.put("简单字段","Simple field");translated.put("末句。","Final sentence.");
        byte[] source=docx("<w:p xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:r><w:t>前言。</w:t></w:r><w:hyperlink r:id=\"rId42\"><w:r><w:t>付款</w:t></w:r><w:r><w:t>链接</w:t></w:r></w:hyperlink><w:r><w:t>后文。</w:t><w:fldChar w:fldCharType=\"begin\"/><w:instrText> REF source_abc \\h </w:instrText><w:fldChar w:fldCharType=\"separate\"/><w:t>字段结果</w:t><w:fldChar w:fldCharType=\"end\"/></w:r><w:fldSimple w:instr=\"REF source_def\"><w:r><w:t>简单字段</w:t></w:r></w:fldSimple><w:r><w:t>末句。</w:t></w:r></w:p>",false);
        org.w3c.dom.Document document=wordDocument(output().docx(source,"snapshot:links-fields",()->{}));String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Element link=(org.w3c.dom.Element)document.getElementsByTagNameNS(ns,"hyperlink").item(0);
        assertEquals("English translation: Payment link",link.getTextContent());assertEquals("rId42",link.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"));
        assertEquals(" REF source_abc \\h ",document.getElementsByTagNameNS(ns,"instrText").item(0).getTextContent());
        org.w3c.dom.NodeList controls=document.getElementsByTagNameNS(ns,"fldChar");assertEquals(3,controls.getLength());assertEquals("English translation: Field result",controls.item(1).getNextSibling().getTextContent());
        org.w3c.dom.Element simple=(org.w3c.dom.Element)document.getElementsByTagNameNS(ns,"fldSimple").item(0);assertEquals("REF source_def",simple.getAttributeNS(ns,"instr"));assertEquals("English translation: Simple field",simple.getTextContent());
        assertEquals(List.of("前言。","付款链接","后文。","字段结果","简单字段","末句。"),submittedTexts);
    }
    @Test void objectsTextboxesAndDrawingLinksSeparateTheTextBeforeInsideAndAfterThem()throws Exception{
        translated.put("对象之前。","Before the object.");translated.put("对象之后。","After the object.");translated.put("文本框","Textbox");translated.put("图前","Before link");translated.put("图链接","Drawing link");translated.put("图后","After link");
        byte[] source=docx("<w:p><w:r><w:t>对象之前。</w:t><w:object><w:txbxContent><w:p><w:r><w:t>文本框</w:t></w:r></w:p></w:txbxContent><a:p xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><a:r><a:t>图前</a:t></a:r><a:r><a:rPr><a:hlinkClick r:id=\"rId7\"/></a:rPr><a:t>图链接</a:t></a:r><a:r><a:t>图后</a:t></a:r></a:p></w:object><w:t>对象之后。</w:t></w:r></w:p>",false);
        org.w3c.dom.Document document=wordDocument(output().docx(source,"snapshot:objects",()->{}));String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Element object=(org.w3c.dom.Element)document.getElementsByTagNameNS(ns,"object").item(0);
        assertEquals("English translation: Before the object.",object.getPreviousSibling().getTextContent());assertEquals("English translation: After the object.",object.getNextSibling().getTextContent());
        assertEquals("English translation: Textbox",object.getElementsByTagNameNS(ns,"p").item(0).getTextContent());
        org.w3c.dom.Element link=(org.w3c.dom.Element)document.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","hlinkClick").item(0);
        assertEquals("rId7",link.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"));assertEquals("English translation: Drawing link",link.getParentNode().getParentNode().getTextContent());
        assertEquals(List.of("对象之前。","对象之后。","文本框","图前","图链接","图后"),submittedTexts);
    }
    @Test void unsupportedExplicitNonEnglishXmlTextFailsBeforeAnyLanguageCall()throws Exception{
        for(String body:List.of("<w:p><w:r><w:instrText>未支持的字段指令</w:instrText></w:r></w:p>","<custom:caption xmlns:custom=\"urn:authored-unsupported\">未支持标题</custom:caption>")){
            byte[] source=docx(body,false),before=source.clone();assertThrows(BizException.class,()->output().docx(source,"snapshot:unsupported",()->{}));assertArrayEquals(before,source);
        }
        assertEquals(0,calls.get());
    }
    @Test void visibleWordAndDrawingAttributesAreTranslatedWithoutChangingListValuesOrLinkReferences()throws Exception{
        translated.put("付款说明","Payment guidance");translated.put("项目名称","Project name");translated.put("选项甲","Option A");translated.put("字段帮助","Field help");translated.put("状态说明","Status guidance");translated.put("默认文字","Default wording");translated.put("绘图链接提示","Drawing link tooltip");
        byte[] source=docx("<w:p xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:hyperlink r:id=\"rId42\" w:tooltip=\"付款说明\"><w:r><w:t>Original English link.</w:t></w:r></w:hyperlink><w:sdt><w:sdtPr><w:alias w:val=\"项目名称\"/><w:dropDownList><w:listItem w:displayText=\"选项甲\" w:value=\"opaque-42\"/></w:dropDownList></w:sdtPr><w:sdtContent><w:r><w:t>Original English choice.</w:t></w:r></w:sdtContent></w:sdt><w:r><w:ffData><w:helpText w:type=\"text\" w:val=\"字段帮助\"/><w:statusText w:type=\"text\" w:val=\"状态说明\"/><w:ddList><w:listEntry w:val=\"选项甲\"/></w:ddList><w:textInput><w:default w:val=\"默认文字\"/></w:textInput></w:ffData><w:drawing><a:p><a:r><a:rPr><a:hlinkClick r:id=\"rId7\" tooltip=\"绘图链接提示\"/></a:rPr><a:t>Original drawing link.</a:t></a:r></a:p></w:drawing></w:r></w:p>",false),before=source.clone();
        org.w3c.dom.Document document=wordDocument(output().docx(source,"snapshot:visible-attributes",()->{}));String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main",r="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        org.w3c.dom.Element link=(org.w3c.dom.Element)document.getElementsByTagNameNS(w,"hyperlink").item(0);assertEquals("Payment guidance",link.getAttributeNS(w,"tooltip"));assertEquals("rId42",link.getAttributeNS(r,"id"));
        assertEquals("Project name",((org.w3c.dom.Element)document.getElementsByTagNameNS(w,"alias").item(0)).getAttributeNS(w,"val"));
        org.w3c.dom.Element choice=(org.w3c.dom.Element)document.getElementsByTagNameNS(w,"listItem").item(0);assertEquals("Option A",choice.getAttributeNS(w,"displayText"));assertEquals("opaque-42",choice.getAttributeNS(w,"value"));
        for(String[] item:List.of(new String[]{"helpText","Field help"},new String[]{"statusText","Status guidance"},new String[]{"listEntry","Option A"},new String[]{"default","Default wording"}))assertEquals(item[1],((org.w3c.dom.Element)document.getElementsByTagNameNS(w,item[0]).item(0)).getAttributeNS(w,"val"));
        org.w3c.dom.Element drawing=(org.w3c.dom.Element)document.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","hlinkClick").item(0);assertEquals("Drawing link tooltip",drawing.getAttribute("tooltip"));assertEquals("rId7",drawing.getAttributeNS(r,"id"));assertFalse(submittedTexts.contains("opaque-42"));assertFalse(submittedTexts.contains("rId42"));assertArrayEquals(before,source);
    }
    @Test void unsupportedLatinTextIsCheckedButCannotBeTranslatedAsAnUnknownCarrier()throws Exception{
        translated.put("Bonjour, paiement dans 14 jours.","Hello, payment is due within 14 days.");
        byte[] source=docx("<custom:caption xmlns:custom=\"urn:authored\">Bonjour, paiement dans 14 jours.</custom:caption><custom:id xmlns:custom=\"urn:authored\">run-42</custom:id>",false),before=source.clone();
        BizException failure=assertThrows(BizException.class,()->output().docx(source,"snapshot:unknown-latin",()->{}));assertTrue(failure.getMessage().contains("cannot be translated safely"));assertEquals(List.of("Bonjour, paiement dans 14 jours."),submittedTexts);assertArrayEquals(before,source);
    }
    @Test void unknownEnglishTextAndMechanicalXmlIdentifiersRemainExactAfterLanguageChecking()throws Exception{
        byte[] source=docx("<custom:caption xmlns:custom=\"urn:authored\">English caption.</custom:caption><custom:id xmlns:custom=\"urn:authored\">run-42</custom:id><w:p><w:r><w:instrText> REF source_abc \\h </w:instrText></w:r></w:p>",false);
        assertArrayEquals(source,output().docx(source,"snapshot:unknown-english",()->{}));assertEquals(List.of("English caption."),submittedTexts);
    }
    @Test void unlabelledDropDownValuesAreLanguageCheckedWithoutRewritingTheirMechanicalIdentity()throws Exception{
        translated.put("Bonjour","Hello");byte[] source=docx("<w:p><w:sdt><w:sdtPr><w:dropDownList><w:listItem w:value=\"Bonjour\"/></w:dropDownList></w:sdtPr></w:sdt></w:p>",false),before=source.clone();
        assertThrows(BizException.class,()->output().docx(source,"snapshot:unlabelled-list",()->{}));assertEquals(List.of("Bonjour"),submittedTexts);assertArrayEquals(before,source);
    }
    @Test void drawingsReferencedImagesAndEmbeddedCarriersFailBeforeAnyLanguageCall()throws Exception{
        for(String kind:List.of("vml","svg","image","external-image","oleObject","package","aFChunk")){
            Map<String,byte[]> values=unzip(docx("<w:p><w:r><w:t>Original English text.</w:t></w:r></w:p>",false));
            if(List.of("vml","svg").contains(kind))values.put("word/media/visible."+kind,"Unsupported source content".getBytes(StandardCharsets.UTF_8));
            else {String type=kind.equals("external-image")?"image":kind;values.put("word/_rels/document.xml.rels",("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId7\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+type+"\" Target=\""+(kind.equals("external-image")?"https://example.invalid/source.png":"media/opaque.bin")+"\""+(kind.equals("external-image")?" TargetMode=\"External\"":"")+"/></Relationships>").getBytes(StandardCharsets.UTF_8));}
            byte[] source=pack(values),before=source.clone();BizException failure=assertThrows(BizException.class,()->output().docx(source,"snapshot:media:"+kind,()->{}));assertTrue(failure.getMessage().contains("language cannot be certified"));assertArrayEquals(before,source);
        }
        assertEquals(0,calls.get());
    }
    @Test void jsonSourceRequestReasonsAndHumanLocationsTranslateWithoutChangingRequestReferences(){
        translated.put("证据不足","Insufficient evidence");translated.put("中文材料.docx 条款 14","Chinese material.docx clause 14");
        JsonNode source=JsonUtils.parse("{\"location\":\"中文材料.docx 条款 14\",\"sourceRequests\":[{\"requestId\":\"request-42\",\"unknownReason\":\"证据不足\",\"transportStatus\":\"unknown\"}]}");String before=JsonUtils.write(source);
        JsonNode result=output().json(source,"snapshot:reason-location",()->{});assertEquals("English translation: Chinese material.docx clause 14",result.path("location").asText());assertEquals("English translation: Insufficient evidence",result.path("sourceRequests").get(0).path("unknownReason").asText());assertEquals("request-42",result.path("sourceRequests").get(0).path("requestId").asText());assertEquals("unknown",result.path("sourceRequests").get(0).path("transportStatus").asText());assertEquals(before,JsonUtils.write(source));
    }
    @Test void longMarkdownUsesExistingLineBoundariesAndPreservesExactLineEndingsAndNumericRoles(){
        String first="English line 14 "+"word ".repeat(4000),second="付款21天。",original=first+"\r\n\r\n"+first+"\n"+second+"\r";
        translated.put(second,"Payment after 21 days.");String result=output().text(original,"snapshot:long-markdown",()->{});
        assertEquals(first+"\r\n\r\n"+first+"\nEnglish translation: Payment after 21 days.\r",result);assertEquals(List.of(first,second),submittedTexts);
    }
    @Test void aLongUnbrokenTextSegmentIsRejectedWithoutCuttingItsMeaningOrCallingTheModel(){
        String source="word ".repeat(6500);assertThrows(BizException.class,()->output().text(source,"snapshot:unbroken-markdown",()->{}));assertEquals(0,calls.get());
    }
    private byte[] docx(String body)throws Exception{return docx(body,true);}
    private byte[] docx(String body,boolean metadata)throws Exception{
        Map<String,byte[]> values=new LinkedHashMap<>();values.put("word/document.xml",("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        values.put("word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>".getBytes(StandardCharsets.UTF_8));values.put("word/media/opaque.bin",new byte[]{1,2,3});
        if(metadata)values.put("docProps/core.xml","<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>验收报告</dc:title><dc:creator>作者</dc:creator></cp:coreProperties>".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> item:values.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception{Map<String,byte[]> out=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)out.put(entry.getName(),zip.readAllBytes());}return out;}
    private static byte[] pack(Map<String,byte[]> values)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> item:values.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return bytes.toByteArray();}
    private static org.w3c.dom.Document wordDocument(byte[] bytes)throws Exception{javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);return factory.newDocumentBuilder().parse(new ByteArrayInputStream(unzip(bytes).get("word/document.xml")));}
}
