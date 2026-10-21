package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generated identity formatting only; source body/fields/fonts remain independently guarded. */
class DraftNativeIdentityFontTest {
    private static final Identity HAN=new Identity("合約-42", "城市工程示例");
    private static NativeStoryPlan identity(NativeStoryPlan plan,Identity value){
        NativeStoryPlan result=new NativeStoryPlan(plan.sourceSha256,plan.profile,value,plan.evenAndOddHeaders,plan.sections,plan.staticFooterGroups,plan.staticFooterSourceFormat);
        return plan.sctTitleMigration==null?result:result.withSctTitleMigration(plan.sctTitleMigration);
    }
    private static List<Element> identityRuns(Document document,String value){
        List<Element> result=new ArrayList<>();for(Element run:elements(document,W,"r"))if(value.equals(visible(run)))result.add(run);return result;
    }
    private static void assertHanRun(Element run){
        List<Element> properties=new ArrayList<>();for(Node n=run.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&"rPr".equals(n.getLocalName()))properties.add((Element)n);
        assertEquals(1,properties.size(),"Font and optional explicit size must share one rPr");
        NodeList fonts=properties.get(0).getElementsByTagNameNS(W,"rFonts");assertEquals(1,fonts.getLength());
        assertEquals("Microsoft YaHei",((Element)fonts.item(0)).getAttributeNS(W,"eastAsia"));
        assertFalse(((Element)fonts.item(0)).hasAttributeNS(W,"eastAsiaTheme"));
    }
    @Test void newHanHeaderAndContractNumberRunsUseTheProductEastAsiaFaceWithoutTouchingSourceEntries()throws Exception {
        Fixture f=new Fixture(p("Original body")+"<w:sectPr/>","",Collections.emptyMap(),"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));
        Map<String,byte[]> after=unzip(f.apply(identity(f.plan(),HAN),Collections.emptyList()).getDocxBytes());
        List<Element> title=identityRuns(parse(after.get("word/header1.xml")),HAN.title),number=identityRuns(parse(after.get("word/footer1.xml")),HAN.number);
        assertEquals(1,title.size());assertEquals(1,number.size());assertHanRun(title.get(0));assertHanRun(number.get(0));
        unchanged(f.parts,after,new HashSet<>(Arrays.asList(MAIN,RELS,TYPES)));
    }
    @Test void aHanNumberReplacesOnlyItsLabelAndKeepsNeighbouringRunsAndPageFields()throws Exception {
        Fixture f=loaded();f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,0,8,"20250101");
        Map<String,byte[]> after=unzip(f.apply(identity(f.plan(),HAN),Collections.emptyList()).getDocxBytes());Document footer=parse(after.get("word/footer1.xml"));
        List<Element> generated=identityRuns(footer,HAN.number);assertEquals(1,generated.size());assertHanRun(generated.get(0));
        assertEquals(HAN.number+"SCT.C/1230",text(footer));assertEquals(" PAGE ",elements(footer,W,"instrText").get(0).getTextContent());
        assertEquals(" NUMPAGES ",elements(footer,W,"fldSimple").get(0).getAttributeNS(W,"instr"));assertEquals("30",elements(footer,W,"fldSimple").get(0).getTextContent());
        for(Element run:elements(footer,W,"r"))if(!visible(run).equals(HAN.number))assertEquals(0,run.getElementsByTagNameNS(W,"rFonts").getLength(),"Original surrounding source runs must retain their fonts");
        assertEquals(1,generated.get(0).getElementsByTagNameNS(W,"b").getLength(),"Identity inherits the source label weight");
        unchanged(f.parts,after,new HashSet<>(Arrays.asList("word/header1.xml","word/footer1.xml")));
    }
    @Test void englishIdentityFollowsTheExistingRunShapeWithoutAddingFontOverrides()throws Exception {
        Fixture f=loaded();f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,0,8,"20250101");
        Map<String,byte[]> after=unzip(f.apply().getDocxBytes());Document header=parse(after.get("word/header1.xml")),footer=parse(after.get("word/footer1.xml"));
        assertEquals(0,elements(header,W,"rFonts").size());assertEquals(0,elements(footer,W,"rFonts").size());assertEquals(2,elements(footer,W,"b").size());
        assertEquals("CONTRACT-42SCT.C/1230",text(footer));
    }
    @Test void aHanLabelInsideOneHyperlinkedRunKeepsSourcePrefixSuffixAndTheirFont()throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr","<w:p><w:hyperlink><w:r><w:rPr><w:i/><w:rFonts w:eastAsia=\"Source Face\"/></w:rPr><w:t>left20250101right</w:t></w:r></w:hyperlink>"+PAGE+"</w:p>"));
        Fixture f=new Fixture(p("Body")+"<w:sectPr>"+reference("footer","rId8")+"</w:sectPr>",relation("rId8","footer","footer1.xml"),stories,"");f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,4,12,"20250101");
        Document footer=parse(unzip(f.apply(identity(f.plan(),HAN),Collections.emptyList()).getDocxBytes()).get("word/footer1.xml"));
        List<Element> generated=identityRuns(footer,HAN.number);assertEquals(1,generated.size());assertHanRun(generated.get(0));assertEquals("hyperlink",generated.get(0).getParentNode().getLocalName());
        for(Element run:elements(footer,W,"r"))if(Arrays.asList("left","right").contains(visible(run))){assertEquals("Source Face",((Element)run.getElementsByTagNameNS(W,"rFonts").item(0)).getAttributeNS(W,"eastAsia"));assertEquals(1,run.getElementsByTagNameNS(W,"i").getLength());}
        assertEquals("left"+HAN.number+"right12",text(footer));assertEquals(" PAGE ",elements(footer,W,"instrText").get(0).getTextContent());
    }
    @Test void aStyledLabelKeepsItsStyleBeforeTheNewFontAndPreservesItsSize()throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr","<w:p><w:r><w:rPr><w:rStyle w:val=\"Label\"/><w:b/><w:sz w:val=\"20\"/></w:rPr><w:t>left20250101right</w:t></w:r>"+PAGE+"</w:p>"));
        Fixture f=new Fixture(p("Body")+"<w:sectPr>"+reference("footer","rId8")+"</w:sectPr>",relation("rId8","footer","footer1.xml"),stories,"");f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,4,12,"20250101");
        Document footer=parse(unzip(f.apply(identity(f.plan(),HAN),Collections.emptyList()).getDocxBytes()).get("word/footer1.xml"));Element generated=identityRuns(footer,HAN.number).get(0);assertHanRun(generated);
        Element properties=(Element)generated.getElementsByTagNameNS(W,"rPr").item(0);List<String> order=new ArrayList<>();for(Node n=properties.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)order.add(n.getLocalName());
        assertEquals(Arrays.asList("rStyle","rFonts","b","sz"),order);assertEquals("Label",((Element)properties.getElementsByTagNameNS(W,"rStyle").item(0)).getAttributeNS(W,"val"));assertEquals("20",((Element)properties.getElementsByTagNameNS(W,"sz").item(0)).getAttributeNS(W,"val"));
        assertEquals("left"+HAN.number+"right12",text(footer));assertEquals(" PAGE ",elements(footer,W,"instrText").get(0).getTextContent());
    }
    @Test void fullWidthAndKanaIdentityValuesUseTheSameEastAsiaSlotAsTheCapabilityCheck()throws Exception {
        for(String value:Arrays.asList("ＡＢＣ－１２３","かなカナ","\uD840\uDC00")){
            Fixture f=new Fixture(p("Original body")+"<w:sectPr/>","",Collections.emptyMap(),"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));Identity adopted=new Identity(value,value);
            Map<String,byte[]> after=unzip(f.apply(identity(f.plan(),adopted),Collections.emptyList()).getDocxBytes());
            assertHanRun(identityRuns(parse(after.get("word/header1.xml")),value).get(0));assertHanRun(identityRuns(parse(after.get("word/footer1.xml")),value).get(0));
        }
    }
    @Test void theThreeActualSourcePoliciesCoverHanIdentityAndKeepTheirStylesAndFontTables()throws Exception {
        String directory=System.getProperty("consense.acceptance.sourceDir");assertNotNull(directory,"Actual 2a source directory is required for this specific regression");
        String[] keys={"NTT","SCT","SCC"},names={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
        for(int i=0;i<keys.length;i++){
            byte[] source=Files.readAllBytes(Paths.get(directory,names[i]));TemplateIndex index=EDITOR.inspect(source);Map<String,Object> prepared=new LinkedHashMap<>(),values=new LinkedHashMap<>(),adopted=new LinkedHashMap<>();
            adopted.put("number",HAN.number);adopted.put("title",HAN.title);values.put("contractTitle",adopted);prepared.put("effectiveValues",values);prepared.put("unresolved",new ArrayList<>());
            NativeLayoutPlan plan=DraftNativeIdentity.attach(index,observe(source,keys[i],names[i]),keys[i],prepared,"libreoffice",null);assertNotNull(plan);assertNotNull(plan.getStories());
            DocxEditResult output=EDITOR.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,plan);
            Map<String,byte[]> before=unzip(source),after=unzip(output.getDocxBytes());assertArrayEquals(before.get("word/styles.xml"),after.get("word/styles.xml"));assertArrayEquals(before.get("word/fontTable.xml"),after.get("word/fontTable.xml"));
            int titles=0,numbers=0;for(Map.Entry<String,byte[]> part:after.entrySet())if(part.getKey().matches("word/(header|footer)[0-9]+\\.xml")){
                Document xml=parse(part.getValue());for(Element run:identityRuns(xml,HAN.title)){assertHanRun(run);titles++;}for(Element run:identityRuns(xml,HAN.number)){assertHanRun(run);numbers++;}
                for(Element run:elements(xml,W,"r"))if(visible(run).equals(HAN.number)&&plan.getStories().staticFooterSourceFormat!=null&&plan.getStories().staticFooterSourceFormat.contractNumberHalfPoints!=null){assertEquals(1,run.getElementsByTagNameNS(W,"sz").getLength());assertEquals(plan.getStories().staticFooterSourceFormat.contractNumberHalfPoints.toString(),((Element)run.getElementsByTagNameNS(W,"sz").item(0)).getAttributeNS(W,"val"));}
            }
            assertTrue(titles>0,keys[i]+" adopted title");assertTrue(numbers>0,keys[i]+" adopted number");if("SCT".equals(keys[i]))assertTrue(titles>=2,"SCT rebuilt default and first-page headers both retain the explicit identity face");
        }
    }
    @Test void aMissingFontOnOriginalCjkBodyTextStillFailsCapabilityValidation()throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();run.setText("原始正文");run.setFontFamily("Unavailable Original CJK Typeface",org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.eastAsia);word.write(out);bytes=out.toByteArray();
        }
        DraftPdfConversionException failure=assertThrows(DraftPdfConversionException.class,()->DraftPdfSourceCapabilities.inspect(bytes,new HashSet<>(Arrays.asList("Times New Roman","Microsoft YaHei"))));
        assertEquals(DraftPdfConversionException.Code.FONT_CAPABILITY_MISSING,failure.getCode());assertTrue(failure.getMessage().contains("Unavailable Original CJK Typeface"));
    }
}
