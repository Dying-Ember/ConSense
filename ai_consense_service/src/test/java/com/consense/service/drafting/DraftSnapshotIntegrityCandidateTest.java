package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.domain.DraftArtifact;
import com.consense.web.dto.DraftingDtos.DocumentPatch;
import java.util.Arrays;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated synthetic repository doubles only; not production database evidence. */
class DraftSnapshotIntegrityCandidateTest {
    private static final String P=DraftingPlanGenerationStateTest.PROJECT;

    @ParameterizedTest
    @ValueSource(strings={"source","bindings","preview","word","pdf","text","edit","toc"})
    void everyCurrentArtifactConsumerRejectsARevisionFromAnotherSnapshot(String op) throws Exception {
            DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();
            f.seedFrozen();String key="toc".equals(op)?"SCC":"NTT";
            DraftArtifact a=f.artifacts.get("fixture-"+key);
            a.setSnapshotId("previous-snapshot");
            ReflectionTestUtils.setField(f.service,"entityManager",mock(EntityManager.class));
            BizException failure=assertThrows(BizException.class,()->{
                switch(op) {
                    case "source": f.service.documentSource(P,key,a.getId());break;
                    case "bindings": f.service.documentBindings(P,key,a.getId(),null);break;
                    case "preview": f.service.previewPdf(P,key,a.getId());break;
                    case "word": f.service.exportWord(P,key,a.getId());break;
                    case "pdf": f.service.exportPdf(P,key,a.getId());break;
                    case "text": f.service.download(P,key);break;
                    case "edit": f.service.updateDocument(P,key,new DocumentPatch());break;
                    case "toc": f.service.refreshToc(P,key,a.getId(),a.getDocxSha256());break;
                    default: fail("unknown operation");
                }
            },op);
            assertEquals(4012,failure.getCode(),op);
            assertEquals("GENERATED_ARTIFACT_SNAPSHOT_MISMATCH",failure.getMessage(),op);
            verify(f.docs,never()).save(any());verify(f.revisions,never()).save(any());
    }

    @Test void sameSnapshotAndEditedChildRevisionRemainReadable() throws Exception {
        for(boolean edited:Arrays.asList(false,true)) {
            DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
            DraftArtifact a=f.artifacts.get("fixture-NTT");
            if(edited)a.setParentRevisionId("previous-revision");
            assertArrayEquals(a.getDocxBytes(),f.service.documentSource(P,"NTT",a.getId()));
        }
    }

    @Test void missingLegacySnapshotIsNotPresentedAsCurrent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        f.documents.get("NTT").setSnapshotId(null);
        f.documents.get("NTT").setContent("retained legacy text");
        BizException failure=assertThrows(BizException.class,()->f.service.documentSource(P,"NTT","fixture-NTT"));
        assertEquals(4009,failure.getCode());
        com.consense.web.dto.DraftingDtos.DraftDocumentVO legacy=f.service.listDocuments(P).stream().filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(legacy.isStale());assertEquals("retained legacy text",legacy.getContent());
        assertNull(legacy.getRevisionId());assertNull(legacy.getDocxSha256());
    }

    @ParameterizedTest
    @ValueSource(strings={"snapshot","project","file","bytes"})
    void listingHidesUntrustedArtifactMetadataAndKeepsOwnText(String defect) throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        DraftArtifact a=f.artifacts.get("fixture-NTT");f.documents.get("NTT").setContent("own retained text");
        switch(defect) {
            case "snapshot": a.setSnapshotId("previous-snapshot");break;
            case "project": a.setProjectId("other-project");break;
            case "file": a.setFileKey("SCT");break;
            case "bytes": a.setDocxBytes(new byte[]{1,2,3});break;
            default: fail("unexpected defect");
        }
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("own retained text",result.getContent());
        assertNull(result.getRevisionId());assertNull(result.getDocxSha256());assertNull(result.getSourceSha256());
        assertNull(result.getBlocks());assertNull(result.getNativeLayout());assertNull(result.getPdfSha256());
    }

    @Test void ownedHistoricalArtifactRemainsVisibleAsStale() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        f.documents.get("NTT").setSnapshotId("historical-snapshot");
        f.artifacts.get("fixture-NTT").setSnapshotId("historical-snapshot");
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("fixture-NTT",result.getRevisionId());
        assertEquals(f.artifacts.get("fixture-NTT").getDocxSha256(),result.getDocxSha256());
    }

    @Test void missingArtifactKeepsOwnTextAndReportsMissingWithoutChangingData() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        f.documents.get("NTT").setContent("retained own text");f.artifacts.remove("fixture-NTT");
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("retained own text",result.getContent());
        assertEquals("MISSING",result.getArtifactStatus());assertNull(result.getRevisionId());
        assertNull(result.getDocxSha256());assertNull(result.getBlocks());
        BizException failure=assertThrows(BizException.class,()->f.service.documentSource(P,"NTT","fixture-NTT"));
        assertEquals(4012,failure.getCode());assertEquals("GENERATED_ARTIFACT_MISSING",failure.getMessage());
        verify(f.docs,never()).save(any());verify(f.revisions,never()).save(any());
    }

    @Test void missingRevisionPointerKeepsOwnTextAndReportsItsDistinctState() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        f.documents.get("NTT").setContent("retained pointerless text");f.documents.get("NTT").setRevisionId(null);
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("retained pointerless text",result.getContent());
        assertEquals("REVISION_MISSING",result.getArtifactStatus());assertNull(result.getRevisionId());
        assertNull(result.getDocxSha256());assertNull(result.getBlocks());
        verify(f.revisions,never()).findById(isNull());
    }

    @ParameterizedTest
    @ValueSource(strings={"docx","blocks","impacts","bindings","ledger"})
    void corruptArtifactContentKeepsOwnTextAndReportsInvalid(String defect) throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        f.documents.get("NTT").setContent("retained own text");DraftArtifact a=f.artifacts.get("fixture-NTT");
        switch(defect) {
            case "docx": byte[] invalid={1,2,3};a.setDocxBytes(invalid);a.setDocxSha256(DraftPdfConverter.sha256(invalid));break;
            case "blocks": a.setBlocksJson("[broken");break;
            case "impacts": a.setFieldImpactsJson("[broken");break;
            case "bindings": a.setBindingsJson("[broken");break;
            case "ledger": a.setLedgerJson("[broken");break;
            default: fail("unknown defect");
        }
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("CONTENT_INVALID",result.getArtifactStatus());
        assertEquals("retained own text",result.getContent());assertNull(result.getRevisionId());
        assertNull(result.getDocxSha256());assertNull(result.getBlocks());assertNull(result.getNativeLayout());
        verify(f.docs,never()).save(any());verify(f.revisions,never()).save(any());
    }

    @Test void repositoryFaultIsNotConvertedToMissingOrCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        when(f.revisions.findById("fixture-NTT")).thenThrow(new IllegalStateException("synthetic repository failure"));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
        assertEquals("synthetic repository failure",error.getMessage());
    }

    @Test void pdfRepositoryFaultIsNotConvertedToCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        when(f.pdfs.existsByDocxSha256(anyString())).thenThrow(new IllegalStateException("synthetic PDF repository failure"));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
        assertEquals("synthetic PDF repository failure",error.getMessage());
    }

    public static final class BrokenXmlFactory extends javax.xml.parsers.DocumentBuilderFactory {
        public javax.xml.parsers.DocumentBuilder newDocumentBuilder() throws javax.xml.parsers.ParserConfigurationException {
            throw new javax.xml.parsers.ParserConfigurationException("synthetic parser configuration failure");
        }
        public void setAttribute(String name,Object value) { }
        public Object getAttribute(String name) { return null; }
        public void setFeature(String name,boolean value) { }
        public boolean getFeature(String name) { return false; }
    }

    @Test void parserConfigurationFaultIsNotConvertedToCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        String property="javax.xml.parsers.DocumentBuilderFactory",previous=System.getProperty(property);
        try {
            System.setProperty(property,BrokenXmlFactory.class.getName());
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
            assertEquals("DOCX_INSPECTION_UNAVAILABLE",failure.getMessage());
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
        }
    }

    public static final class IoFaultXmlFactory extends javax.xml.parsers.DocumentBuilderFactory {
        public javax.xml.parsers.DocumentBuilder newDocumentBuilder() {
            return new javax.xml.parsers.DocumentBuilder() {
                public org.w3c.dom.Document parse(org.xml.sax.InputSource source) throws java.io.IOException {
                    String kind=System.getProperty("consense.test.parser.io", "general");
                    if("eof".equals(kind))throw new java.io.EOFException("synthetic parser EOF failure");
                    if("zip".equals(kind))throw new java.util.zip.ZipException("synthetic parser ZIP failure");
                    throw new java.io.IOException("synthetic parser I/O failure");
                }
                public boolean isNamespaceAware(){return true;}
                public boolean isValidating(){return false;}
                public void setEntityResolver(org.xml.sax.EntityResolver resolver){ }
                public void setErrorHandler(org.xml.sax.ErrorHandler handler){ }
                public org.w3c.dom.Document newDocument(){throw new UnsupportedOperationException();}
                public org.w3c.dom.DOMImplementation getDOMImplementation(){throw new UnsupportedOperationException();}
            };
        }
        public void setAttribute(String name,Object value) { }
        public Object getAttribute(String name) { return null; }
        public void setFeature(String name,boolean value) { }
        public boolean getFeature(String name) { return false; }
    }

    @Test void parserIoFaultIsNotConvertedToCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        String property="javax.xml.parsers.DocumentBuilderFactory",previous=System.getProperty(property);
        try {
            System.setProperty(property,IoFaultXmlFactory.class.getName());
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
            assertEquals("DOCX_INSPECTION_UNAVAILABLE",failure.getMessage());
            assertEquals("synthetic parser I/O failure",failure.getCause().getMessage());
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"eof","zip"})
    void parserSpecialIoFaultIsNotConvertedToCorruptContent(String kind) throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        String property="javax.xml.parsers.DocumentBuilderFactory", previous=System.getProperty(property);
        String ioProperty="consense.test.parser.io",previousIo=System.getProperty(ioProperty);
        try {
            System.setProperty(property,IoFaultXmlFactory.class.getName());System.setProperty(ioProperty,kind);
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
            assertEquals("DOCX_INSPECTION_UNAVAILABLE",failure.getMessage());
            assertEquals("eof".equals(kind)?java.io.EOFException.class:java.util.zip.ZipException.class,failure.getCause().getClass());
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
            if(previousIo==null)System.clearProperty(ioProperty);else System.setProperty(ioProperty,previousIo);
        }
    }

    public static final class SaxFaultXmlFactory extends javax.xml.parsers.DocumentBuilderFactory {
        public javax.xml.parsers.DocumentBuilder newDocumentBuilder() {
            return new javax.xml.parsers.DocumentBuilder() {
                public org.w3c.dom.Document parse(org.xml.sax.InputSource source) throws org.xml.sax.SAXException {
                    if("wrapped-io".equals(System.getProperty("consense.test.parser.sax")))
                        throw new org.xml.sax.SAXParseException("synthetic wrapped I/O",null,null,1,1,new java.io.IOException("synthetic underlying I/O"));
                    throw new org.xml.sax.SAXException("synthetic parser system failure");
                }
                public boolean isNamespaceAware(){return true;}
                public boolean isValidating(){return false;}
                public void setEntityResolver(org.xml.sax.EntityResolver resolver){ }
                public void setErrorHandler(org.xml.sax.ErrorHandler handler){ }
                public org.w3c.dom.Document newDocument(){throw new UnsupportedOperationException();}
                public org.w3c.dom.DOMImplementation getDOMImplementation(){throw new UnsupportedOperationException();}
            };
        }
        public void setAttribute(String name,Object value) { }
        public Object getAttribute(String name) { return null; }
        public void setFeature(String name,boolean value) { }
        public boolean getFeature(String name) { return false; }
    }

    @Test void parserSaxSystemFaultIsNotConvertedToCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        String property="javax.xml.parsers.DocumentBuilderFactory",previous=System.getProperty(property);
        try {
            System.setProperty(property,SaxFaultXmlFactory.class.getName());
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
            assertEquals("DOCX_INSPECTION_UNAVAILABLE",failure.getMessage());
            assertEquals("synthetic parser system failure",failure.getCause().getMessage());
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
        }
    }

    @Test void malformedDocumentXmlIsStillReportedAsInvalidContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        DraftArtifact a=f.artifacts.get("fixture-NTT");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(a.getDocxBytes()));
            java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;byte[] buffer=new byte[4096];
            while((entry=in.getNextEntry())!=null){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if("word/document.xml".equals(entry.getName()))zip.write("<broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else {int count;while((count=in.read(buffer))!=-1)zip.write(buffer,0,count);}zip.closeEntry();}
        }
        byte[] corrupt=out.toByteArray();a.setDocxBytes(corrupt);a.setDocxSha256(DraftPdfConverter.sha256(corrupt));
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("CONTENT_INVALID",result.getArtifactStatus());
    }

    @Test void truncatedZipIsStillReportedAsInvalidContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        DraftArtifact a=f.artifacts.get("fixture-NTT");byte[] original=a.getDocxBytes();
        byte[] corrupt=Arrays.copyOf(original,original.length/2);a.setDocxBytes(corrupt);a.setDocxSha256(DraftPdfConverter.sha256(corrupt));
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("CONTENT_INVALID",result.getArtifactStatus());
    }

    @Test void zipEntryNameWithInvalidUtf8IsReportedAsInvalidContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        DraftArtifact a=f.artifacts.get("fixture-NTT");byte[] corrupt=a.getDocxBytes();
        assertTrue(corrupt.length>30&&corrupt[0]=='P'&&corrupt[1]=='K'&&corrupt[2]==3&&corrupt[3]==4);
        corrupt[30]=(byte)0xff;a.setDocxBytes(corrupt);a.setDocxSha256(DraftPdfConverter.sha256(corrupt));
        com.consense.web.dto.DraftingDtos.DraftDocumentVO result=f.service.listDocuments(P).stream()
            .filter(d->"NTT".equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(result.isStale());assertEquals("CONTENT_INVALID",result.getArtifactStatus());
        assertNull(result.getRevisionId());assertNull(result.getDocxSha256());
    }

    @Test void saxParseExceptionWrappingIoIsNotConvertedToCorruptContent() throws Exception {
        DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();f.seedFrozen();
        String property="javax.xml.parsers.DocumentBuilderFactory",previous=System.getProperty(property);
        String mode="consense.test.parser.sax",previousMode=System.getProperty(mode);
        try {
            System.setProperty(property,SaxFaultXmlFactory.class.getName());System.setProperty(mode,"wrapped-io");
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.service.listDocuments(P));
            assertEquals("DOCX_INSPECTION_UNAVAILABLE",failure.getMessage());
            assertTrue(failure.getCause() instanceof org.xml.sax.SAXParseException);
            assertTrue(failure.getCause().getCause() instanceof java.io.IOException);
            assertEquals("synthetic underlying I/O",failure.getCause().getCause().getMessage());
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
            if(previousMode==null)System.clearProperty(mode);else System.setProperty(mode,previousMode);
        }
    }
}
