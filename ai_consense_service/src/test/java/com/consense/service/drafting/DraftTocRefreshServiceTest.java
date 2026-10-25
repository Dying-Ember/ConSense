package com.consense.service.drafting;

import com.consense.common.*;
import com.consense.domain.*;
import com.consense.document.DocxTemplateEditor;
import com.consense.web.DraftingController;
import com.consense.web.dto.DraftingDtos.DraftDocumentVO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import javax.persistence.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual grafted Service with repository doubles and authored finalizer pairs; no DB, renderer or model. */
class DraftTocRefreshServiceTest {
    static final String PROJECT=DraftingPlanGenerationStateTest.PROJECT;
    static byte[] word(String page)throws Exception {
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        String xml="<w:document xmlns:w=\""+w+"\"><w:body><w:p><w:r><w:t>Human body edit retained exactly.</w:t></w:r></w:p>"
                +"<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> TOC \\o \"1-2\" \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r></w:p>"
                +"<w:p><w:hyperlink w:anchor=\"_TocRefresh\"><w:r><w:t>SCC1</w:t><w:tab/><w:t>Manual heading</w:t><w:tab/></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGEREF _TocRefresh \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>"+page+"</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:hyperlink></w:p>"
                +"<w:p><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p><w:p><w:bookmarkStart w:id=\"1\" w:name=\"_TocRefresh\"/><w:r><w:t>SCC1 Manual heading</w:t></w:r><w:bookmarkEnd w:id=\"1\"/></w:p><w:sectPr/></w:body></w:document>";
        Map<String,String> parts=new LinkedHashMap<>();parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");parts.put("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");parts.put("word/document.xml",xml);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,String> entry:parts.entrySet()){ZipEntry item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();
    }
    static DraftPdfConversionResult pdf(byte[] docx)throws Exception {
        byte[] bytes;try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.save(out);bytes=out.toByteArray();}
        Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("docxSha256",DraftPdfConverter.sha256(docx));manifest.put("pdfSha256",DraftPdfConverter.sha256(bytes));manifest.put("renderProfileHash","authored-service-contract-only");return new DraftPdfConversionResult(bytes,manifest);
    }
    static class Fixture extends DraftingPlanGenerationStateTest.Fixture {
        final EntityManager em=mock(EntityManager.class);final byte[] before,after;final DraftArtifact parent;final DraftDocument document;final DraftingService tested;
        DraftTocFinalizer.Result finalized;Runnable during;int renderCalls;
        Fixture()throws Exception {
            seedFrozen();before=word("1");after=word("2");source("SCC",before);templates.get("SCC").setSourceSha256(DraftPdfConverter.sha256(before));
            parent=artifacts.get("fixture-SCC");parent.setDocxBytes(before);parent.setDocxSha256(DraftPdfConverter.sha256(before));parent.setSourceSha256(DraftPdfConverter.sha256(before));parent.setBindingsJson("[]");parent.setLedgerJson("[{\"kind\":\"human_body_edit\",\"operationId\":\"manual-1\",\"note\":\"Keep the original edit evidence\"}]");parent.setFieldImpactsJson("[{\"kind\":\"existing_manual_impact\"}]");
            document=documents.get("SCC");document.setSnapshotId(ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null));parent.setSnapshotId(document.getSnapshotId());document.setContent(DraftFormattedRules.content(new DocxTemplateEditor().inspect(before)));document.setContentEdited(true);document.setRuleVersion("frozen-rule");document.setInputSnapshotJson("{\"human\":\"adopted values remain frozen\"}");document.setUnresolvedJson("[{\"id\":\"retain-unknown\"}]");
            props.getDrafting().setRendererKind("libreoffice");props.getDrafting().setRendererExecutable("authored-not-launched");props.getDrafting().setRendererWorkRoot("authored-not-created");
            ReflectionTestUtils.setField(service,"entityManager",em);tested=spy(service);when(pdfs.findById(anyString())).thenReturn(Optional.empty());finalized=result("PASS",after);
            doAnswer(call->{renderCalls++;assertArrayEquals(before,call.getArgument(0));assertEquals(parent.getSourceSha256(),call.getArgument(1));if(during!=null)during.run();return finalized;}).when(tested).finalizeCompiledToc(any(byte[].class),anyString(),anyList(),anyList());
            clearInvocations(docs,revisions,pdfs,inputs,sources,storage,model,em);
        }
        DraftTocFinalizer.Result result(String status,byte[] output)throws Exception {Map<String,Object> proof=new LinkedHashMap<>();proof.put("kind","toc_finalization");proof.put("status",status);proof.put("compiledDocxSha256",DraftPdfConverter.sha256(before));proof.put("finalDocxSha256",DraftPdfConverter.sha256(output));proof.put("headingFlowStatus","UNKNOWN");proof.put("headingFlowReason","Authored retained empty endpoint");proof.put("headingFlowRepairCount",0);proof.put("headingMutationCount",0);proof.put("targetDenominator",1);if(!"PASS".equals(status))proof.put("reason","AUTHORED_TOC_NOT_VERIFIED");return new DraftTocFinalizer.Result(before,output,pdf(before),pdf(output),proof);}
        Map<String,Object> refresh(){return tested.refreshToc(PROJECT,"SCC",parent.getId(),parent.getDocxSha256());}
        void noWrites(){verify(revisions,never()).save(any());verify(docs,never()).save(any());verify(pdfs,never()).save(any());verify(inputs,never()).save(any());verifyNoInteractions(model);}
    }
    @Test void pureTocChildKeepsParentBodyEvidenceAndAllBusinessMetadata()throws Exception {
        Fixture f=new Fixture();String values=JsonUtils.write(f.variables.values()),parentLedger=f.parent.getLedgerJson(),snapshot=f.document.getSnapshotId(),inputs=f.document.getInputSnapshotJson(),unresolved=f.document.getUnresolvedJson();byte[] parentBytes=f.parent.getDocxBytes();
        Map<String,Object> response=f.refresh();assertEquals(true,response.get("updated"));DraftDocumentVO shown=(DraftDocumentVO)response.get("document");assertNotEquals(f.parent.getId(),shown.getRevisionId());DraftArtifact child=f.artifacts.get(shown.getRevisionId());
        assertEquals(f.parent.getId(),child.getParentRevisionId());assertEquals(f.parent.getSourceSha256(),child.getSourceSha256());assertEquals(snapshot,child.getSnapshotId());assertArrayEquals(f.after,child.getDocxBytes());assertArrayEquals(parentBytes,f.parent.getDocxBytes());assertEquals(parentLedger,f.parent.getLedgerJson());assertEquals(JsonUtils.readList(parentLedger,Object.class).get(0),JsonUtils.readList(child.getLedgerJson(),Object.class).get(0));assertEquals(f.parent.getFieldImpactsJson(),child.getFieldImpactsJson());
        assertTrue(f.document.getContentEdited());assertEquals("frozen-rule",f.document.getRuleVersion());assertEquals(inputs,f.document.getInputSnapshotJson());assertEquals(unresolved,f.document.getUnresolvedJson());assertEquals(values,JsonUtils.write(f.variables.values()));assertTrue(f.document.getContent().contains("Human body edit retained exactly."));assertFalse(shown.isStale());assertEquals("UNKNOWN",((Map<?,?>)response.get("tocFinalization")).get("headingFlowStatus"));
        verify(f.em).clear();org.mockito.InOrder order=inOrder(f.em,f.docs,f.revisions);order.verify(f.em).clear();order.verify(f.docs).findByProjectIdAndFileKey(PROJECT,"SCC");order.verify(f.em).refresh(f.document,LockModeType.PESSIMISTIC_WRITE);order.verify(f.revisions).save(any(DraftArtifact.class));verify(f.inputs,never()).save(any());verifyNoInteractions(f.model);
    }
    @Test void alreadyCorrectAndUnknownResultsKeepTheRevisionAndWriteNothing()throws Exception {
        for(String status:Arrays.asList("PASS","UNKNOWN")){Fixture f=new Fixture();f.finalized=f.result(status,"PASS".equals(status)?f.before:f.after);Map<String,Object> result=f.refresh();assertEquals(false,result.get("updated"));assertEquals(f.parent.getId(),((DraftDocumentVO)result.get("document")).getRevisionId());assertEquals(status,((Map<?,?>)result.get("tocFinalization")).get("status"));f.noWrites();}
    }
    @Test void missingStaleRevisionHashOrChangedOriginalCannotStartFinalization()throws Exception {
        for(int fault=0;fault<5;fault++){Fixture f=new Fixture();String revision=f.parent.getId(),hash=f.parent.getDocxSha256();if(fault==0)revision=null;if(fault==1)f.document.setSnapshotId("stale");if(fault==2)revision="foreign-revision";if(fault==3)hash=String.join("",Collections.nCopies(64,"0"));if(fault==4)f.files.put("SCC",word("3"));final String r=revision,h=hash;assertThrows(BizException.class,()->f.tested.refreshToc(PROJECT,"SCC",r,h));assertEquals(0,f.renderCalls);f.noWrites();}
    }
    @Test void clearingPersistenceContextExposesValuesChangedWhileRendering()throws Exception {
        Fixture f=new Fixture();List<DraftVariable> cached=new ArrayList<>(f.variables.values());AtomicBoolean cleared=new AtomicBoolean();when(f.inputs.findByProjectIdOrderBySortOrderAsc(PROJECT)).thenAnswer(call->cleared.get()?new ArrayList<>(f.variables.values()):cached);doAnswer(call->{cleared.set(true);return null;}).when(f.em).clear();f.during=()->f.value("foundationIncluded",true);
        BizException failure=assertThrows(BizException.class,f::refresh);assertEquals(4009,failure.getCode());assertTrue(cleared.get());assertEquals(1,f.renderCalls);f.noWrites();
    }
    @Test void sourceRevisionAndSavedHashRacesAfterRenderingCannotPublishAChild()throws Exception {
        for(int fault=0;fault<3;fault++){Fixture f=new Fixture();if(fault==0)f.during=()->{try{f.files.put("SCC",word("3"));}catch(Exception failure){throw new RuntimeException(failure);}};if(fault==1)f.during=()->f.document.setRevisionId("concurrent-revision");if(fault==2)f.during=()->{f.parent.setDocxBytes(f.after);f.parent.setDocxSha256(DraftPdfConverter.sha256(f.after));};assertThrows(BizException.class,f::refresh);assertEquals(1,f.renderCalls);verify(f.em).clear();f.noWrites();}
    }
    @Test void headingRepairCandidatesNeverBecomeHiddenTocOnlyEdits()throws Exception {
        for(int fault=0;fault<3;fault++){Fixture f=new Fixture();Map<String,Object> proof=new LinkedHashMap<>(f.finalized.proof);proof.put(fault==0?"repairDocxSha256":fault==1?"headingFlowRepairCount":"headingMutationCount",fault==0?"authored-heading-repair":1);f.finalized=new DraftTocFinalizer.Result(f.before,f.after,f.finalized.first,f.finalized.pdf,proof);Map<String,Object> response=f.refresh();assertEquals(false,response.get("updated"));assertEquals("TOC_REFRESH_REQUIRES_HEADING_REPAIR",((Map<?,?>)response.get("tocFinalization")).get("reason"));f.noWrites();}
    }
    @Test void routeAcceptsOnlyTheTwoIdentityFieldsAndKeepsItsTransactionContract()throws Exception {
        DraftingService service=mock(DraftingService.class);DraftingController controller=new DraftingController(service,null);Map<String,String> body=new LinkedHashMap<>();body.put("revisionId","r");body.put("docxSha256","h");Map<String,Object> response=new LinkedHashMap<>();when(service.refreshToc("p","SCC","r","h")).thenReturn(response);assertSame(response,controller.refreshToc("p","SCC",body).getData());verify(service).refreshToc("p","SCC","r","h");clearInvocations(service);body.put("businessValue","forbidden");assertThrows(BizException.class,()->controller.refreshToc("p","SCC",body));verifyNoInteractions(service);
        Transactional tx=DraftingService.class.getDeclaredMethod("refreshToc",String.class,String.class,String.class,String.class).getAnnotation(Transactional.class);assertNotNull(tx);assertEquals(Isolation.READ_COMMITTED,tx.isolation());assertFalse(tx.readOnly());
    }
}
