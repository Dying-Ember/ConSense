package com.consense.service.drafting;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
class DraftMeasurementRetainedBoundaryTest {
    private static final String W=NativeDocxComparison.W,CS="CS310d3b5988c4dea5c07e4f3b1fcb",CL="CL322f2697d2a5aaa1a4ad602c840f";
    private static byte[][] fixture()throws Exception {
        String source=System.getProperty("consense.acceptance.sourceDir"),output=System.getProperty("consense.audit.retainedBoundaryDocx");
        assumeTrue(source!=null&&output!=null,"Explicit actual source and preserved normal46 DOCX required");
        byte[] original=Files.readAllBytes(Paths.get(source,"06_Special Conditions of Contract (SCC).docx"));assertEquals(DraftNativeHeadingBoundariesTest.ORIGINAL_SOURCE_SHA,DraftPdfConverter.sha256(original));
        return new byte[][]{original,Files.readAllBytes(Paths.get(output))};
    }
    private static byte[] altered(byte[] original,boolean text)throws Exception {
        Map<String,byte[]> parts=DraftingArtifactIntegrationTest.parts(original);org.w3c.dom.Document document=NativeDocxComparison.parse(parts.get("word/document.xml"));org.w3c.dom.Element paragraph=null;
        for(org.w3c.dom.Element marker:NativeDocxComparison.elements(document,"bookmarkStart"))if(CS.equals(marker.getAttributeNS(W,"name")))paragraph=(org.w3c.dom.Element)marker.getParentNode();assertNotNull(paragraph);
        if(text)paragraph.getElementsByTagNameNS(W,"t").item(0).setTextContent("UNAUTHORIZED CONTENT");else ((org.w3c.dom.Element)paragraph.getElementsByTagNameNS(W,"jc").item(0)).setAttributeNS(W,"w:val","left");
        ByteArrayOutputStream xml=new ByteArrayOutputStream();javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(document),new javax.xml.transform.stream.StreamResult(xml));parts.put("word/document.xml",xml.toByteArray());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    @Test void actualNormalTitleRetainsExactXmlApartFromItsVerifiedMarkerAndTwoHeadingControls()throws Exception {byte[][] f=fixture();assertTrue(DraftingArtifactIntegrationTest.verifiedMeasurementRetainsParagraph(f[0],1063,f[1],Set.of(CL)));assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementRetainsParagraph(f[0],1063,f[1],Set.of()));}
    @Test void aChangedNativeRunStillFailsTheCompleteRetainedBoundaryComparison()throws Exception {byte[][] f=fixture();assertFalse(DraftingArtifactIntegrationTest.verifiedMeasurementRetainsParagraph(f[0],1063,altered(f[1],true),Set.of(CL)));}
    @Test void anUnrelatedParagraphFormatChangeStillFailsTheCompleteRetainedBoundaryComparison()throws Exception {byte[][] f=fixture();assertFalse(DraftingArtifactIntegrationTest.verifiedMeasurementRetainsParagraph(f[0],1063,altered(f[1],false),Set.of(CL)));}
}
