package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WordNoteReferenceTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String TYPES="http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static String reference(String kind,String id,String extra){return "<w:r><w:"+kind+"Reference w:id='"+id+"'"+extra+"/></w:r>";}
    private static byte[] fixture(String content,boolean end)throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createParagraph().getCTP().set(CTP.Factory.parse("<w:p xmlns:w='"+W+"'>"+content+"</w:p>"));
            org.apache.poi.xwpf.usermodel.XWPFFootnote note=word.createFootnote();note.getCTFtnEdn().setId(BigInteger.valueOf(9));note.createParagraph();
            if(end){org.apache.poi.xwpf.usermodel.XWPFEndnote n=word.createEndnote();n.getCTFtnEdn().setId(BigInteger.valueOf(4));n.createParagraph().createRun().setText("End source text.");}
            word.write(out);return out.toByteArray();
        }
    }
    private static DocumentParser parser(OcrClient ocr){ConsenseProperties p=new ConsenseProperties();p.getOcr().setEnabled(false);return new DocumentParser(p,ocr);}
    private static List<JsonNode> references(DocumentParser.ParsedDocument parsed) {
        List<JsonNode> out=new ArrayList<>();for(DocumentBlock block:parsed.getBlocks())for(JsonNode item:block.getWordNativeObjects())
            if(item.path("kind").asText().endsWith("noteReference"))out.add(item);return out;
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception {
        Map<String,byte[]> parts=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;byte[] buffer=new byte[8192];while((entry=in.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);parts.put(entry.getName(),out.toByteArray());}
        }return parts;
    }
    private static byte[] zip(Map<String,byte[]> parts)throws Exception {
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(Map.Entry<String,byte[]> part:parts.entrySet()){out.putNextEntry(new ZipEntry(part.getKey()));out.write(part.getValue());out.closeEntry();}out.finish();return bytes.toByteArray();
        }
    }
    private static byte[] xml(Node node)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(new DOMSource(node),new StreamResult(out));return out.toByteArray();
    }
    private static String path(Node node) {
        String name="{"+node.getNamespaceURI()+"}"+node.getLocalName();int index=1;
        for(Node prior=node.getPreviousSibling();prior!=null;prior=prior.getPreviousSibling())if(prior.getNodeType()==Node.ELEMENT_NODE&&java.util.Objects.equals(node.getNamespaceURI(),prior.getNamespaceURI())&&node.getLocalName().equals(prior.getLocalName()))index++;
        Node parent=node.getParentNode();return (parent!=null&&parent.getNodeType()==Node.ELEMENT_NODE?path(parent):"")+"/"+name+"["+index+"]";
    }
    private static Element target(Document xml,String local,String id) {
        org.w3c.dom.NodeList nodes=xml.getElementsByTagNameNS(W,local);for(int i=0;i<nodes.getLength();i++){Element element=(Element)nodes.item(i);if(id.equals(element.getAttributeNS(W,"id")))return element;}throw new AssertionError("fixture target missing");
    }
    private static void position(JsonNode value,byte[] original,String part,Node node,Map<String,byte[]> parts) {
        assertEquals("docx_native",value.path("kind").asText());assertEquals(DocumentParseProbe.sha256(original),value.path("sourceSha256").asText());
        assertEquals(part,value.path("partName").asText());assertEquals(DocumentParseProbe.sha256(parts.get(part)),value.path("partSha256").asText());assertEquals(path(node),value.path("nodePath").asText());
    }

    @Test void emptyFootnoteAndNonemptyEndnoteBindThroughTheirActualUniqueRelations()throws Exception {
        byte[] bytes=fixture("<w:r><w:t>Before </w:t></w:r>"+reference("footnote","9"," w:customMarkFollows='1'")+"<w:r><w:t>After.</w:t></w:r>"+reference("endnote","4",""),true);
        byte[] before=bytes.clone();Map<String,byte[]> parts=unzip(bytes);OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("notes.docx",bytes);
        assertEquals(2,references(parsed).size());Document main=OerNativeDocxObservation.parseXml(parts.get("word/document.xml"));
        for(JsonNode object:references(parsed)) {
            String kind=object.path("kind").asText();boolean foot=kind.equals("footnoteReference");String id=foot?"9":"4",part=foot?"word/footnotes.xml":"word/endnotes.xml";
            JsonNode ref=object.path("noteReference");assertEquals(id,ref.path("rawId").asText());assertEquals("resolved",ref.path("bindingStatus").asText());assertEquals("unknown",object.path("semanticStatus").asText());
            assertTrue(object.path("nativeXml").asText().contains(kind));position(ref.path("sourcePosition"),bytes,"word/document.xml",target(main,kind,id),parts);
            position(ref.path("targetSourcePosition"),bytes,part,target(OerNativeDocxObservation.parseXml(parts.get(part)),foot?"footnote":"endnote",id),parts);
            assertEquals(TYPES+(foot?"footnotes":"endnotes"),ref.path("relationship").path("type").asText());assertFalse(ref.path("relationship").path("id").asText().isEmpty());
            assertEquals("word/_rels/document.xml.rels",ref.path("relationship").path("sourcePosition").path("partName").asText());
            assertEquals(id,ref.path("rawAttributes").path("{"+W+"}id").asText());
            if(foot)assertEquals("1",ref.path("rawAttributes").path("{"+W+"}customMarkFollows").asText());
        }
        assertEquals("Before After.",parsed.getBlocks().get(0).getText());assertTrue(parsed.getText().contains("End source text."));
        assertTrue(parsed.getCoverage().isComplete());assertArrayEquals(before,bytes);verifyNoInteractions(ocr);
    }

    @Test void missingMultipleExternalRelationshipsAndMissingOrDuplicateRootsStayUnknown()throws Exception {
        byte[] base=fixture("<w:r><w:t>Body.</w:t></w:r>"+reference("footnote","9",""),false);
        for(String mode:new String[]{"missing_relation","multiple_relations","external_relation","missing_root","duplicate_root"}) {
            Map<String,byte[]> parts=unzip(base);Document rels=OerNativeDocxObservation.parseXml(parts.get("word/_rels/document.xml.rels"));Element relation=null;
            org.w3c.dom.NodeList all=rels.getElementsByTagNameNS(REL,"Relationship");for(int i=0;i<all.getLength();i++){Element r=(Element)all.item(i);if((TYPES+"footnotes").equals(r.getAttribute("Type")))relation=r;}
            assertNotNull(relation);
            if(mode.equals("missing_relation"))relation.getParentNode().removeChild(relation);
            if(mode.equals("multiple_relations")){Element duplicate=(Element)relation.cloneNode(true);duplicate.setAttribute("Id","rIdExtraFootnotes");relation.getParentNode().appendChild(duplicate);}
            if(mode.equals("external_relation")){relation.setAttribute("TargetMode","External");relation.setAttribute("Target","https://invalid.example/footnotes.xml");}
            parts.put("word/_rels/document.xml.rels",xml(rels));
            if(mode.endsWith("root")) {
                Document notes=OerNativeDocxObservation.parseXml(parts.get("word/footnotes.xml"));Element note=target(notes,"footnote","9");
                if(mode.equals("missing_root"))note.setAttributeNS(W,"w:id","99");else note.getParentNode().appendChild(note.cloneNode(true));parts.put("word/footnotes.xml",xml(notes));
            }
            OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("unknown-notes.docx",zip(parts));assertEquals(1,references(parsed).size(),mode);
            JsonNode object=references(parsed).get(0),ref=object.path("noteReference");assertEquals("unknown",ref.path("bindingStatus").asText(),mode);assertFalse(ref.path("reason").asText().isEmpty(),mode);
            assertEquals("9",ref.path("rawId").asText());assertTrue(object.path("nativeXml").asText().contains("footnoteReference"));assertFalse(ref.hasNonNull("targetSourcePosition"));
            assertEquals("Body.",parsed.getText());assertFalse(parsed.getCoverage().isComplete());assertFalse(parsed.getBlocks().get(0).getWordWarnings().isEmpty());verifyNoInteractions(ocr);
        }
    }

    @Test void referencesRetainDeletedAndStruckFlagsWithoutInventingVisibleNumbers()throws Exception {
        byte[] bytes=fixture("<w:r><w:t>Kept.</w:t></w:r><w:del w:id='1'>"+reference("footnote","9","")+"</w:del><w:r><w:rPr><w:strike/></w:rPr><w:footnoteReference w:id='9'/></w:r>",false);
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("removed-reference.docx",bytes);
        assertEquals("Kept.",parsed.getText());assertEquals(2,references(parsed).size());assertTrue(references(parsed).get(0).path("deleted").asBoolean());assertTrue(references(parsed).get(1).path("struck").asBoolean());
        for(JsonNode object:references(parsed))assertEquals("resolved",object.path("noteReference").path("bindingStatus").asText());
        assertEquals("",parsed.getBlocks().get(0).getDeletedText());assertEquals("",parsed.getBlocks().get(0).getStrikeText());verifyNoInteractions(ocr);
    }

    @Test void referenceOnlyParagraphIsRetainedAndLegacyAddsNoReferenceMetadata()throws Exception {
        byte[] bytes=fixture(reference("footnote","9",""),false);OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);
        DocumentParser.ParsedDocument fresh=p.parse("empty-note.docx",bytes),legacy=p.projectLegacyDocxMath(bytes);
        assertEquals("",fresh.getText());assertEquals(1,fresh.getBlocks().size());assertEquals(1,references(fresh).size());assertEquals("resolved",references(fresh).get(0).path("noteReference").path("bindingStatus").asText());
        assertEquals("",legacy.getText());assertTrue(legacy.getBlocks().isEmpty());assertTrue(references(legacy).isEmpty());verifyNoInteractions(ocr);
    }

    @Test void integerIdLexicalAliasesBindButInvalidAndNumericallyDuplicateIdsRemainUnknown()throws Exception {
        for(String rawId:new String[]{"+09","  +09  ","not-an-integer"}) {
            byte[] bytes=fixture("<w:r><w:t>Body.</w:t></w:r>"+reference("footnote",rawId,""),false);OcrClient ocr=mock(OcrClient.class);
            DocumentParser.ParsedDocument parsed=parser(ocr).parse("integer-id.docx",bytes);assertEquals(1,references(parsed).size());JsonNode ref=references(parsed).get(0).path("noteReference");
            assertEquals(rawId,ref.path("rawId").asText());assertEquals(rawId,ref.path("rawAttributes").path("{"+W+"}id").asText());
            assertEquals(rawId.equals("not-an-integer")?"unknown":"resolved",ref.path("bindingStatus").asText());
            if(rawId.equals("not-an-integer"))assertEquals("invalid_note_reference_id",ref.path("reason").asText());
            else assertTrue(ref.hasNonNull("targetSourcePosition"));assertEquals("Body.",parsed.getText());verifyNoInteractions(ocr);
        }
        Map<String,byte[]> parts=unzip(fixture("<w:r><w:t>Body.</w:t></w:r>"+reference("footnote","9",""),false));
        Document notes=OerNativeDocxObservation.parseXml(parts.get("word/footnotes.xml"));Element note=target(notes,"footnote","9"),duplicate=(Element)note.cloneNode(true);
        duplicate.setAttributeNS(W,"w:id","+09");note.getParentNode().appendChild(duplicate);parts.put("word/footnotes.xml",xml(notes));
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("duplicate-integer-id.docx",zip(parts));
        assertEquals("unknown",references(parsed).get(0).path("noteReference").path("bindingStatus").asText());
        assertEquals("duplicate_note_id",references(parsed).get(0).path("noteReference").path("reason").asText());verifyNoInteractions(ocr);
    }

    @Test void noteReferencesSurviveTheRoutedNormalParseWithoutOcr()throws Exception {
        byte[] bytes=fixture("<w:r><w:t>SPECIAL CONDITIONS OF CONTRACT</w:t><w:br/><w:t>Contract No: TEST/2046/03</w:t></w:r>"+reference("footnote","9",""),false);
        OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);DocumentParser.ParsedDocument observed=p.observe("arbitrary.docx",bytes);
        OerDocumentIdentity identity=new OerSourceRouting().route("arbitrary.docx",DocumentParseProbe.sha256(bytes),observed);assertEquals("SCC",identity.getKind());
        DocumentParser.ParsedDocument parsed=p.parse("arbitrary.docx",bytes,observed,identity);assertEquals(1,references(parsed).size());
        assertEquals("resolved",references(parsed).get(0).path("noteReference").path("bindingStatus").asText());assertEquals(observed.getText(),parsed.getText());verifyNoInteractions(ocr);
    }
}
