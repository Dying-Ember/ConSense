package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.DraftArtifact;
import com.consense.domain.DraftDocument;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.web.dto.DraftingDtos.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public body-save seam with real native packages and mocked persistence; no database, API or renderer. */
class DraftBodyParagraphRemovalTest {
    static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    static final String MATH="<m:oMath><m:r><m:t>x=1</m:t></m:r></m:oMath>";
    static byte[] fixture(String middle)throws Exception {
        Map<String,String> parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        parts.put("word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body><w:p><w:r><w:t>Retained before</w:t></w:r>"+MATH.replace("x=1","z=2")+"</w:p>"+middle+"<w:p><w:r><w:t>Retained after</w:t></w:r></w:p><w:sectPr/></w:body></w:document>");
        parts.put("word/media/opaque.bin","UNCHANGED_OPAQUE_BYTES");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,String> entry:parts.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();
    }
    static String mathParagraph(){return "<w:p><w:r><w:t>(i.e. </w:t></w:r>"+MATH+"<w:r><w:br/></w:r></w:p>";}
    static byte[] entry(byte[] bytes,String name)throws Exception {try(ZipInputStream zip=new ZipInputStream(new java.io.ByteArrayInputStream(bytes))){ZipEntry next;while((next=zip.getNextEntry())!=null)if(name.equals(next.getName())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=zip.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}throw new AssertionError(name);}
    static class Saved {
        final DraftArtifactRepository artifacts=mock(DraftArtifactRepository.class);
        final DraftDocumentRepository documents=mock(DraftDocumentRepository.class);
        final DraftVariableRepository variables=mock(DraftVariableRepository.class);
        final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        final DraftDocument document=new DraftDocument();final Map<String,DraftArtifact> revisions=new LinkedHashMap<>();
        final DraftingService service;final DraftArtifact original;
        Saved(String middle)throws Exception {
            service=new DraftingService(mock(ProjectService.class),null,null,null,sources,variables,documents,artifacts,mock(DraftPdfArtifactRepository.class),new ConsenseProperties(),null,null,mock(EntityManager.class),null,null,null);
            byte[] source=fixture(middle);List<Map<String,Object>> rows=DraftDocumentBindings.source("NTT",EDITOR.inspect(source));
            byte[] bytes=DraftDocumentBindings.sourceWorkingCopy(source,rows);TemplateIndex index=EDITOR.inspect(bytes);rows=DraftDocumentBindings.reconcile(rows,index,false);
            Method snapshot=DraftingService.class.getDeclaredMethod("snapshotId",String.class,Map.class);snapshot.setAccessible(true);String id=(String)snapshot.invoke(service,"synthetic-body",null);
            original=new DraftArtifact();original.setId("initial-body");original.setProjectId("synthetic-body");original.setFileKey("NTT");original.setSnapshotId(id);original.setSourceSha256(EDITOR.inspect(source).getSourceSha256());original.setDocxSha256(index.getSourceSha256());original.setDocxBytes(bytes);original.setBindingsJson(JsonUtils.write(rows));original.setBlocksJson(JsonUtils.write(DraftDocumentBindings.blocks(index,rows)));original.setLedgerJson("[]");original.setFieldImpactsJson("[]");revisions.put(original.getId(),original);
            document.setProjectId("synthetic-body");document.setFileKey("NTT");document.setTitle("Synthetic body");document.setGenerated(true);document.setSnapshotId(id);document.setRevisionId(original.getId());document.setUnresolvedJson("[]");
            when(documents.findByProjectIdAndFileKey("synthetic-body","NTT")).thenReturn(Optional.of(document));
            when(artifacts.findById(anyString())).thenAnswer(call->Optional.ofNullable(revisions.get(call.getArgument(0))));
            when(artifacts.save(any(DraftArtifact.class))).thenAnswer(call->{DraftArtifact artifact=call.getArgument(0);revisions.put(artifact.getId(),artifact);return artifact;});
        }
        DraftArtifact current(){return revisions.get(document.getRevisionId());}
        Map<String,Object> target(String text){return DraftDocumentBindings.read(current().getBlocksJson()).stream().filter(b->String.valueOf(b.get("text")).contains(text)).findFirst().orElseThrow(AssertionError::new);}
        BlockPatch block(String text,String operation){Map<String,Object> b=target(text);BlockPatch patch=new BlockPatch();patch.setId(String.valueOf(b.get("id")));patch.setBindingId((String)b.get("bindingId"));patch.setExpectedTextHash((String)b.get("textHash"));patch.setOperation(operation);return patch;}
        DocumentPatch patch(BlockPatch...blocks){DocumentPatch patch=new DocumentPatch();patch.setRevisionId(current().getId());patch.setDocxSha256(current().getDocxSha256());patch.setBlocks(Arrays.asList(blocks));return patch;}
        DraftDocumentVO save(DocumentPatch patch){return service.updateDocument("synthetic-body","NTT",patch);}
        void unchanged(){assertEquals(original.getId(),document.getRevisionId());assertEquals(1,revisions.size());assertArrayEquals(original.getDocxBytes(),current().getDocxBytes());verify(artifacts,never()).save(any());verify(documents,never()).save(any());}
    }
    @Test void explicitRemovalDeletesNativeMathAndKeepsImmutableRevisionNeighborsAndBindingLedger()throws Exception {
        Saved s=new Saved(mathParagraph());BlockPatch removal=s.block("(i.e.","delete_paragraph");String binding=removal.getBindingId();byte[] frozen=s.original.getDocxBytes();
        DocumentPatch request=JsonUtils.mapper().readValue(JsonUtils.write(s.patch(removal)),DocumentPatch.class);DraftDocumentVO saved=s.save(request);assertNotEquals(s.original.getId(),saved.getRevisionId());assertEquals(s.original.getId(),s.current().getParentRevisionId());assertArrayEquals(frozen,s.original.getDocxBytes());assertArrayEquals(entry(frozen,"word/media/opaque.bin"),entry(s.current().getDocxBytes(),"word/media/opaque.bin"));
        assertTrue(assertThrows(BizException.class,()->s.save(request)).getMessage().contains("REVISION_CONFLICT"));
        TemplateIndex index=EDITOR.inspect(s.current().getDocxBytes()),before=EDITOR.inspect(frozen);assertEquals(1,index.getFeatures().stream().filter(f->"OMML".equals(f.getKind())).count());assertEquals(Arrays.asList("Retained before","Retained after"),Arrays.asList(index.mainParagraph(1).getText(),index.mainParagraph(2).getText()));assertEquals(before.mainParagraph(1).getSha256(),index.mainParagraph(1).getSha256());assertEquals(before.mainParagraph(3).getSha256(),index.mainParagraph(2).getSha256());
        Map<String,Object> removed=DraftDocumentBindings.read(s.current().getBindingsJson()).stream().filter(b->binding.equals(b.get("bindingId"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("removed",removed.get("locationStatus"));assertEquals("body_deleted",removed.get("applicationStatus"));assertFalse(DraftBusinessRules.list(removed.get("bodyOperationIds")).isEmpty());assertNull(removed.get("resultParagraphId"));assertNull(removed.get("geometry"));
        BlockPatch edit=s.block("Retained after",null);edit.setText("Retained after, manually reviewed.");s.save(s.patch(edit));Map<String,Object> again=DraftDocumentBindings.read(s.current().getBindingsJson()).stream().filter(b->binding.equals(b.get("bindingId"))).findFirst().orElseThrow(AssertionError::new);assertEquals("removed",again.get("locationStatus"));assertEquals("body_deleted",again.get("applicationStatus"));assertEquals(removed.get("bodyOperationIds"),again.get("bodyOperationIds"));
    }
    @Test void ordinaryEmptyAndWhitespaceTextStillCannotClearNativeMath()throws Exception {
        for(String text:Arrays.asList(""," \t\n")){Saved s=new Saved(mathParagraph());BlockPatch patch=s.block("(i.e.",null);patch.setText(text);assertTrue(assertThrows(BizException.class,()->s.save(s.patch(patch))).getMessage().contains("NATIVE_MATH_CLEAR_UNSUPPORTED"));s.unchanged();}
    }
    @Test void rejectsMissingBindingWrongTextHashUnknownOperationAndMixedDeletionWithoutPartialSave()throws Exception {
        String[] codes={"BINDING_REQUIRED_FOR_PARAGRAPH_DELETE","SOURCE_BLOCK_TEXT_CONFLICT","BODY_OPERATION_UNSUPPORTED","PARAGRAPH_DELETE_CONFLICT","PARAGRAPH_DELETE_CONFLICT"};
        for(int fault=0;fault<5;fault++){Saved s=new Saved(mathParagraph());BlockPatch remove=s.block("(i.e.","delete_paragraph"),valid=s.block("Retained before",null);valid.setText("Uncommitted change");switch(fault){case 0:remove.setBindingId(null);break;case 1:remove.setExpectedTextHash("wrong");break;case 2:remove.setOperation("remove_everything");break;case 3:remove.setText("");break;default:remove.setInsertAfter(Collections.singletonList("Conflicting insertion"));}assertTrue(assertThrows(BizException.class,()->s.save(s.patch(valid,remove))).getMessage().contains(codes[fault]));s.unchanged();}
    }
    @Test void rejectsStaleRevisionAndHashAndTableParagraphRemoval()throws Exception {
        for(int fault=0;fault<2;fault++){Saved s=new Saved(mathParagraph());DocumentPatch patch=s.patch(s.block("(i.e.","delete_paragraph"));if(fault==0)patch.setRevisionId("old");else patch.setDocxSha256("old");assertTrue(assertThrows(BizException.class,()->s.save(patch)).getMessage().contains("REVISION_CONFLICT"));s.unchanged();}
        Saved table=new Saved("<w:tbl><w:tr><w:tc>"+mathParagraph()+"</w:tc></w:tr></w:tbl>");assertTrue(assertThrows(BizException.class,()->table.save(table.patch(table.block("(i.e.","delete_paragraph")))).getMessage().contains("PARAGRAPH_DELETE_SCOPE_UNSUPPORTED"));table.unchanged();
    }
    @Test void preservesFieldSectionAndCrossParagraphBookmarkGuards()throws Exception {
        String[] codes={"FIELD_TEXT_EDIT_UNSUPPORTED","SECTION_DELETION_UNSUPPORTED","PARTIAL_BOOKMARK_SCOPE","TOC_TARGET_DELETE_UNSUPPORTED"};int n=0;
        for(String middle:Arrays.asList("<w:p><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>Protected field</w:t></w:r></w:fldSimple></w:p>","<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>Protected section</w:t></w:r></w:p>","<w:p><w:bookmarkStart w:id=\"500\" w:name=\"spanning\"/><w:r><w:t>Protected bookmark</w:t></w:r></w:p><w:p><w:r><w:t>Outside endpoint</w:t></w:r><w:bookmarkEnd w:id=\"500\"/></w:p>","<w:p><w:bookmarkStart w:id=\"501\" w:name=\"_Toc123\"/><w:r><w:t>Protected TOC target</w:t></w:r><w:bookmarkEnd w:id=\"501\"/></w:p>")){
            Saved s=new Saved(middle);assertTrue(assertThrows(BizException.class,()->s.save(s.patch(s.block("Protected ","delete_paragraph")))).getMessage().contains(codes[n++]));s.unchanged();
        }
    }
    @Test void rejectsUnregisteredAndDuplicateSelectedBindings()throws Exception {
        Saved unknown=new Saved(mathParagraph());BlockPatch bad=unknown.block("(i.e.","delete_paragraph");bad.setBindingId("CSunregistered");assertTrue(assertThrows(BizException.class,()->unknown.save(unknown.patch(bad))).getMessage().contains("BINDING_LOCATION_CONFLICT"));unknown.unchanged();
        Saved duplicate=new Saved(mathParagraph());BlockPatch block=duplicate.block("(i.e.","delete_paragraph");assertTrue(assertThrows(BizException.class,()->duplicate.save(duplicate.patch(block,block))).getMessage().contains("SOURCE_BLOCK_INVALID"));duplicate.unchanged();
    }
    @Test void missingBookmarkWithoutAnAppliedRemovalDoesNotBecomeDeleted()throws Exception {
        Saved s=new Saved(mathParagraph());Map<String,Object> old=new LinkedHashMap<>(DraftDocumentBindings.read(s.original.getBindingsJson()).get(0));old.put("bookmarkName","CSmissing");old.put("locationStatus","exact");
        DocxEditResult unchanged=EDITOR.apply(s.original.getDocxBytes(),new SourceEditBatch(s.original.getDocxSha256(),Collections.emptyList()));Map<String,Object> row=DraftDocumentBindings.bodyEdited(Collections.singletonList(old),unchanged).get(0);
        assertEquals("missing",row.get("locationStatus"));assertNotEquals("body_deleted",row.get("applicationStatus"));assertTrue(DraftBusinessRules.list(row.get("bodyOperationIds")).isEmpty());
    }
}
