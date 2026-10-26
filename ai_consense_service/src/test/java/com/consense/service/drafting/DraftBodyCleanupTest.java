package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.BodyScopePatch;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;

/** Server-selected current native scopes, using real CS registration and body-clear receipts. */
class DraftBodyCleanupTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    private static String p(String text){return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
    private static String row(String paragraphs){return "<w:tr><w:tc>"+paragraphs+"</w:tc></w:tr>";}
    private static String table(String rows){return "<w:tbl>"+rows+"</w:tbl>";}
    private static Map<String,byte[]> entries(byte[] bytes)throws Exception {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;while((n=zip.read(buffer))!=-1)out.write(buffer,0,n);result.put(entry.getName(),out.toByteArray());}}return result;
    }
    private static byte[] pack(Map<String,byte[]> entries)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return out.toByteArray();
    }
    private static byte[] fixture(String middle)throws Exception {
        Map<String,byte[]> parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml",("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/document.xml",("<w:document xmlns:w=\""+W+"\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"+p("Retained before")+middle+p("Retained after")+"<w:sectPr/></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/media/opaque.bin",new byte[]{0,11,32,-1,5});return pack(parts);
    }
    private static final class Current {
        final byte[] bytes;final TemplateIndex index;final List<Map<String,Object>> bindings;
        Current(byte[] bytes,List<Map<String,Object>> bindings){this.bytes=bytes;this.index=EDITOR.inspect(bytes);this.bindings=bindings;}
        List<Map<String,Object>> candidates(){return DraftBodyCleanup.candidates(bytes,index,bindings);}
        List<Edit> compile(BodyScopePatch...patches){return DraftBodyCleanup.compile(bytes,index,bindings,Arrays.asList(patches));}
        DocxEditResult apply(BodyScopePatch...patches){return EDITOR.apply(bytes,new SourceEditBatch(index.getSourceSha256(),compile(patches)));}
    }
    private static Current cleared(String middle)throws Exception {
        byte[] source=fixture(middle);List<Map<String,Object>> bindings=DraftDocumentBindings.source("SCT",EDITOR.inspect(source));
        byte[] working=DraftDocumentBindings.sourceWorkingCopy(source,bindings);TemplateIndex index=EDITOR.inspect(working);bindings=DraftDocumentBindings.reconcile(bindings,index,false);
        List<Edit> edits=new ArrayList<>();for(Paragraph paragraph:index.getMainParagraphs())if(paragraph.getText().startsWith("Clear"))edits.add(Edit.clearParagraph("body-clear-"+paragraph.getOrdinal(),paragraph));
        DocxEditResult cleared=EDITOR.apply(working,new SourceEditBatch(index.getSourceSha256(),edits));return new Current(cleared.getDocxBytes(),DraftDocumentBindings.bodyEdited(bindings,cleared));
    }
    private static Element firstScopeParagraph(Document document){return (Element)((Element)document.getElementsByTagNameNS(W,"tbl").item(0)).getElementsByTagNameNS(W,"p").item(0);}
    private static void bookmark(Document document,Element paragraph,String id,String name,boolean end){
        Element start=document.createElementNS(W,"w:bookmarkStart");start.setAttributeNS(W,"w:id",id);start.setAttributeNS(W,"w:name",name);paragraph.appendChild(start);
        if(end){Element close=document.createElementNS(W,"w:bookmarkEnd");close.setAttributeNS(W,"w:id",id);paragraph.appendChild(close);}
    }
    private static Current changedXml(Current current,Consumer<Document> change)throws Exception {
        Map<String,byte[]> parts=entries(current.bytes);DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        Document document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts.get("word/document.xml")));change.accept(document);ByteArrayOutputStream out=new ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document),new StreamResult(out));parts.put("word/document.xml",out.toByteArray());
        return new Current(pack(parts),current.bindings);
    }
    private static Map<String,Object> only(Current current){List<Map<String,Object>> found=current.candidates();assertEquals(1,found.size(),found.toString());return found.get(0);}
    @SuppressWarnings("unchecked") private static List<String> members(Map<String,Object> scope){return new ArrayList<>((List<String>)scope.get("memberBindingIds"));}
    private static BodyScopePatch patch(Map<String,Object> scope){BodyScopePatch patch=new BodyScopePatch();patch.setScopeId((String)scope.get("scopeId"));patch.setExpectedScopeHash((String)scope.get("nodeSha256"));patch.setOperation((String)scope.get("operation"));patch.setMemberBindingIds(members(scope));return patch;}
    private static void rejected(String code,Executable action){assertTrue(assertThrows(BizException.class,action).getMessage().contains(code));}
    private static List<String> text(TemplateIndex index){List<String> result=new ArrayList<>();for(Paragraph paragraph:index.getMainParagraphs())result.add(paragraph.getText());return result;}
    private static Map<String,Object> proof(Current current){return current.bindings.stream().filter(b->String.valueOf(b.get("sourceText")).startsWith("Clear")).findFirst().orElseThrow(AssertionError::new);}

    @Test void serverOffersWholeEmptyRowIncludingUnboundTabBreakParagraphAndAppliesRemoveNodes()throws Exception {
        Current current=cleared(table(row(p("Clear guidance")+"<w:p><w:r><w:tab/><w:br/></w:r></w:p>")+row(p("Retained SCT obligation"))));
        Map<String,Object> scope=only(current);assertEquals("row",scope.get("kind"));assertEquals("delete_empty_row",scope.get("operation"));assertEquals(2,scope.get("paragraphCount"));assertEquals(1,members(scope).size());assertEquals(members(scope),scope.get("clearProofBindings"));
        assertEquals("removed",proof(current).get("locationStatus"));assertEquals("body_edited",proof(current).get("applicationStatus"));assertFalse(DraftBusinessRules.list(proof(current).get("bodyOperationIds")).isEmpty());
        assertEquals(current.index.anchor((String)scope.get("scopeId")).getSha256(),scope.get("nodeSha256"));assertEquals("Retained before",scope.get("previousText"));assertEquals("Retained SCT obligation",scope.get("nextText"));
        DocxEditResult result=current.apply(patch(scope));assertEquals(Collections.singletonList(EditType.REMOVE_NODES),Collections.singletonList(result.getLedger().get(0).getType()));assertEquals("REMOVED",result.getLedger().get(0).getTargets().get(0).getStatus());
        assertEquals(Arrays.asList("Retained before","Retained SCT obligation","Retained after"),text(result.getIndex()));assertArrayEquals(entries(current.bytes).get("word/media/opaque.bin"),entries(result.getDocxBytes()).get("word/media/opaque.bin"));
    }

    @Test void largestEmptyTableSuppressesRowsAndNeedsOnlyOneActualBodyClearProof()throws Exception {
        Current current=cleared(table(row(p("Clear body"))+row(p("Clear generated title"))+row(p("Clear earlier title"))));
        for(Map<String,Object> binding:current.bindings)if(String.valueOf(binding.get("sourceText")).contains("title")){binding.put("applicationStatus","applied");binding.put("bodyOperationIds",Collections.emptyList());}
        Map<String,Object> scope=only(current);assertEquals("table",scope.get("kind"));assertEquals("delete_empty_table",scope.get("operation"));assertEquals(3,members(scope).size());assertEquals(1,DraftBusinessRules.list(scope.get("clearProofBindings")).size());
        BodyScopePatch patch=patch(scope);Collections.reverse(patch.getMemberBindingIds());assertEquals(Arrays.asList("Retained before","Retained after"),text(current.apply(patch).getIndex()));
        Current sole=cleared(table(row(p("Clear only row"))));assertEquals("table",only(sole).get("kind"),"The last remaining row must be represented by whole-table removal, not a row candidate");
    }

    @Test void originalEmptyScopesAndPartlyVisibleRowsAreNotDeletionCandidates()throws Exception {
        assertTrue(cleared(table(row("<w:p/>")+row("<w:p/>"))).candidates().isEmpty());
        assertTrue(cleared(table(row(p("Clear one paragraph")+p("Still required"))+row(p("Retained row")))).candidates().isEmpty());
    }

    @Test void bodyClearProofCannotBeReplacedByGeneratedDeletedOrUnrecordedEmptyText()throws Exception {
        for(String missing:Arrays.asList("status","operations","source")){
            Current current=cleared(table(row(p("Clear proof"))+row(p("Retained"))));Map<String,Object> binding=proof(current);
            if("status".equals(missing))binding.put("applicationStatus","applied");else if("operations".equals(missing))binding.put("bodyOperationIds",Collections.emptyList());else binding.put("sourceText","");
            assertTrue(current.candidates().isEmpty(),missing);
        }
        Current deleted=cleared(table(row(p("Clear proof"))+row(p("Retained"))));proof(deleted).put("applicationStatus","body_deleted");assertTrue(deleted.candidates().isEmpty());
    }

    @Test void emptyVisibleTextCannotHideNativeFieldsPayloadsSectionsOrUnknownCarriers()throws Exception {
        List<String> controls=Arrays.asList("<w:p><w:fldSimple w:instr=\"PAGE\"/></w:p>","<w:p><m:oMath><m:r><m:t/></m:r></m:oMath></w:p>","<w:p><w:r><w:drawing/></w:r></w:p>","<w:p><w:r><w:pict/></w:r></w:p>","<w:p><w:r><w:object/></w:r></w:p>","<w:p><w:pPr><w:sectPr/></w:pPr></w:p>","<w:altChunk/>","<w:sdt><w:sdtContent><w:p/></w:sdtContent></w:sdt>","<x:opaque xmlns:x=\"urn:opaque\"/>","<w:p><w:r><w:sym w:font=\"Symbol\" w:char=\"F0A1\"/></w:r></w:p>","<w:p><w:hyperlink/></w:p>","<w:p><w:ins w:id=\"81\"><w:r><w:t/></w:r></w:ins></w:p>");
        for(String control:controls){Current current=cleared(table(row(p("Clear proof")+control)+row(p("Retained"))));assertTrue(current.candidates().isEmpty(),control);}
    }

    @Test void mergesNestedTablesAndRowGridSpanExcludeUnsafeNativeScopes()throws Exception {
        for(String properties:Arrays.asList("<w:vMerge w:val=\"restart\"/>","<w:vMerge/>","<w:gridSpan w:val=\"2\"/>")){
            Current current=cleared(table(row("<w:tcPr>"+properties+"</w:tcPr>"+p("Clear proof"))+row(p("Retained"))));assertTrue(current.candidates().isEmpty(),properties);
        }
        Current nextMerge=cleared(table(row(p("Clear proof"))+row("<w:tcPr><w:vMerge/></w:tcPr>"+p("Retained continuation"))));assertTrue(nextMerge.candidates().isEmpty());
        Current nested=cleared(table(row(p("Clear proof")+table(row("<w:p/>"))+"<w:p/>")+row(p("Retained"))));assertTrue(nested.candidates().isEmpty());
    }

    @Test void unknownPartialCrossScopeAndDuplicateBookmarksCannotBecomeCandidates()throws Exception {
        Current original=cleared(table(row(p("Clear proof"))+row(p("Retained"))));
        Current unknown=changedXml(original,d->bookmark(d,firstScopeParagraph(d),"9001","CSunregistered",true));assertTrue(unknown.candidates().isEmpty());
        Current partial=changedXml(original,d->bookmark(d,firstScopeParagraph(d),"9002","CLpartial",false));assertTrue(partial.candidates().isEmpty());
        Current cross=changedXml(original,d->{bookmark(d,firstScopeParagraph(d),"9003","CLcross",false);Element end=d.createElementNS(W,"w:bookmarkEnd");end.setAttributeNS(W,"w:id","9003");NodeList paragraphs=d.getElementsByTagNameNS(W,"p");paragraphs.item(paragraphs.getLength()-1).appendChild(end);});assertTrue(cross.candidates().isEmpty());
        Current duplicate=changedXml(original,d->{Element first=firstScopeParagraph(d);String existing=((Element)first.getElementsByTagNameNS(W,"bookmarkStart").item(0)).getAttributeNS(W,"id");bookmark(d,first,existing,"CLduplicateId",true);});assertTrue(duplicate.candidates().isEmpty());
    }

    @Test void registeredBindingIdentityMustMatchCurrentUniqueMarkerPathAndTextHash()throws Exception {
        for(String fault:Arrays.asList("missing","duplicate","path","hash")){
            Current current=cleared(table(row(p("Clear proof"))+row(p("Retained"))));Map<String,Object> binding=proof(current);
            if("missing".equals(fault))current.bindings.remove(binding);else if("duplicate".equals(fault))current.bindings.add(new LinkedHashMap<>(binding));else if("path".equals(fault))binding.put("resultParagraphId",current.index.mainParagraph(1).getId());else binding.put("textHash","stale");
            assertTrue(current.candidates().isEmpty(),fault);
        }
    }

    @Test void currentByteHashAndScopeXmlHashAreIndependentCompileGuards()throws Exception {
        Current current=cleared(table(row(p("Clear proof"))+row(p("Retained"))));BodyScopePatch patch=patch(only(current));Map<String,byte[]> parts=entries(current.bytes);parts.put("word/media/opaque.bin",new byte[]{99});byte[] otherVersion=pack(parts);
        rejected("BODY_SCOPE_SOURCE_CONFLICT",()->DraftBodyCleanup.candidates(otherVersion,current.index,current.bindings));rejected("BODY_SCOPE_SOURCE_CONFLICT",()->DraftBodyCleanup.compile(otherVersion,current.index,current.bindings,Collections.singletonList(patch)));
        Current changed=changedXml(current,d->((Element)d.getElementsByTagNameNS(W,"tr").item(0)).setAttributeNS(W,"w:rsidR","00000099"));assertNotEquals(patch.getExpectedScopeHash(),only(changed).get("nodeSha256"));rejected("BODY_SCOPE_HASH_CONFLICT",()->changed.compile(patch));
    }

    @Test void compileRejectsMissingExtraDuplicateMembersWrongOperationAndUnknownScope()throws Exception {
        Current current=cleared(table(row(p("Clear proof"))+row(p("Retained"))));Map<String,Object> scope=only(current);
        for(String fault:Arrays.asList("missing","extra","duplicate","operation","scope","hash")){
            BodyScopePatch patch=patch(scope);String code="BODY_SCOPE_BINDINGS_CONFLICT";
            if("missing".equals(fault))patch.setMemberBindingIds(Collections.emptyList());else if("extra".equals(fault))patch.getMemberBindingIds().add("CSunregistered");else if("duplicate".equals(fault))patch.getMemberBindingIds().add(patch.getMemberBindingIds().get(0));else if("operation".equals(fault)){patch.setOperation("delete_empty_table");code="BODY_SCOPE_OPERATION_UNSUPPORTED";}else if("scope".equals(fault)){patch.setScopeId("word/document.xml#/w:document[1]/w:body[1]/w:tbl[99]");code="BODY_SCOPE_NOT_AVAILABLE";}else{patch.setExpectedScopeHash("stale");code="BODY_SCOPE_HASH_CONFLICT";}
            rejected(code,()->current.compile(patch));
        }
        BodyScopePatch valid=patch(scope);rejected("BODY_SCOPE_DUPLICATE_OR_INVALID",()->current.compile(valid,valid));rejected("BODY_SCOPE_DUPLICATE_OR_INVALID",()->current.compile((BodyScopePatch)null));
        proof(current).put("bodyOperationIds",Collections.emptyList());rejected("BODY_SCOPE_NOT_AVAILABLE",()->current.compile(valid));
    }

    @Test void parentTableCandidateCannotBeCombinedWithItsSuppressedChildRow()throws Exception {
        Current current=cleared(table(row(p("Clear first"))+row(p("Clear second"))));Map<String,Object> table=only(current);BodyScopePatch child=patch(table);String rowId=current.index.getMainParagraphs().stream().filter(p->p.getRowId()!=null).findFirst().orElseThrow(AssertionError::new).getRowId();child.setScopeId(rowId);child.setExpectedScopeHash(current.index.anchor(rowId).getSha256());child.setOperation("delete_empty_row");
        rejected("BODY_SCOPE_NOT_AVAILABLE",()->current.compile(patch(table),child));
    }

    @Test void disjointGuardCoversUnboundEmptyParagraphsAsWellAsRegisteredMembers()throws Exception {
        Current current=cleared(table(row(p("Clear proof")+"<w:p/>")+row(p("Retained"))));Map<String,Object> scope=only(current);BodyScopePatch patch=patch(scope);NodeAnchor node=current.index.anchor(patch.getScopeId());
        for(String paragraph:node.getParagraphIds())rejected("BODY_SCOPE_BLOCK_CONFLICT",()->DraftBodyCleanup.requireDisjoint(current.index,Collections.singletonList(patch),Collections.singleton(paragraph)));
        assertDoesNotThrow(()->DraftBodyCleanup.requireDisjoint(current.index,Collections.singletonList(patch),Collections.singleton(current.index.mainParagraph(1).getId())));
    }

    @Test void intentionalCompleteClRemovalKeepsReceiptAndLayoutHistoryAcrossOrdinarySave()throws Exception {
        Current current=changedXml(cleared(table(row(p("Clear proof"))+row(p("Retained")))),d->bookmark(d,firstScopeParagraph(d),"9100","CLCleanupLayout",true));Map<String,Object> scope=only(current);BodyScopePatch patch=patch(scope);DocxEditResult result=current.apply(patch);
        Map<String,Object> receipt=DraftBodyCleanup.receipt("parent-revision",current.bytes,current.index,current.bindings,Collections.singletonList(patch),result);assertEquals("body_scope_cleanup",receipt.get("kind"));assertEquals(current.index.getSourceSha256(),receipt.get("parentDocxSha256"));assertEquals(result.getDocxSha256(),receipt.get("generatedDocxSha256"));assertEquals(false,receipt.get("graphOrValuesChanged"));
        Map<String,Object> removed=DraftBusinessRules.asMap(DraftBusinessRules.list(receipt.get("scopes")).get(0));assertEquals("EXPLICIT_STRUCTURAL_SCOPE_REMOVED",removed.get("status"));assertEquals(scope.get("nodeSha256"),removed.get("nodeSha256"));assertEquals(scope.get("clearProofBindings"),removed.get("clearProofBindings"));
        String paragraph=current.index.anchor(patch.getScopeId()).getParagraphIds().get(0);Map<String,Object> layout=DraftBusinessRules.map("kind","native_layout","status","applied","sourceSha256",current.index.getSourceSha256(),"docxSha256",current.index.getSourceSha256(),"compiledDocxSha256",current.index.getSourceSha256(),"actions",Collections.singletonList(DraftBusinessRules.map("layoutId","CLCleanupLayout","generatedId",paragraph,"action","keep_verified_heading_flow","status","APPLIED")));
        List<Object> history=DraftNativeLayout.bodyLedger(JsonUtils.write(Collections.singletonList(layout)),result);history.add(receipt);Map<String,Object> savedLayout=history.stream().map(DraftBusinessRules::asMap).filter(x->"native_layout".equals(x.get("kind"))).findFirst().orElseThrow(AssertionError::new);Map<String,Object> savedAction=DraftBusinessRules.asMap(DraftBusinessRules.list(savedLayout.get("actions")).get(0));assertEquals("REMOVED_WITH_BODY_SCOPE",savedAction.get("status"));assertFalse(DraftBusinessRules.list(savedAction.get("bodyOperationIds")).isEmpty());assertNotEquals("unavailable",savedLayout.get("status"));
        Paragraph neighbor=result.getIndex().mainParagraph(1);DocxEditResult ordinary=EDITOR.apply(result.getDocxBytes(),new SourceEditBatch(result.getDocxSha256(),Collections.singletonList(Edit.replaceSpan("ordinary-body-edit",neighbor,0,neighbor.getText().length(),neighbor.getText(),"Retained before, reviewed"))));
        List<Object> later=DraftNativeLayout.bodyLedger(JsonUtils.write(history),ordinary);assertEquals(1,DraftBodyCleanup.receipts(JsonUtils.write(later)).size());assertEquals(receipt.get("generatedDocxSha256"),DraftBodyCleanup.receipts(JsonUtils.write(later)).get(0).get("generatedDocxSha256"));Map<String,Object> laterLayout=later.stream().map(DraftBusinessRules::asMap).filter(x->"native_layout".equals(x.get("kind"))).findFirst().orElseThrow(AssertionError::new);assertEquals("REMOVED_WITH_BODY_SCOPE",DraftBusinessRules.asMap(DraftBusinessRules.list(laterLayout.get("actions")).get(0)).get("status"));
        assertEquals(1,DraftBodyCleanup.receipts(JsonUtils.write(DraftBodyCleanup.preserveHistory(JsonUtils.write(history),later))).size());
        DocxEditResult unchanged=EDITOR.apply(current.bytes,new SourceEditBatch(current.index.getSourceSha256(),Collections.emptyList()));rejected("BODY_SCOPE_REMOVAL_UNVERIFIED",()->DraftBodyCleanup.receipt("parent",current.bytes,current.index,current.bindings,Collections.singletonList(patch),unchanged));
    }

    @Test void outsideReferenceToClBookmarkKeepsEvenAnOtherwiseEmptyRow()throws Exception {
        Current current=changedXml(cleared(table(row(p("Clear proof"))+row(p("Retained")))),d->{bookmark(d,firstScopeParagraph(d),"9200","CLReferenced",true);Element reference=d.createElementNS(W,"w:fldSimple");reference.setAttributeNS(W,"w:instr"," REF CLReferenced ");d.getElementsByTagNameNS(W,"p").item(0).appendChild(reference);});assertTrue(current.candidates().isEmpty());
    }
}
