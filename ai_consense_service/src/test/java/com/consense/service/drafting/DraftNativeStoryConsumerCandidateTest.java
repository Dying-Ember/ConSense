package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan.Identity;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public consumer boundaries, with synthetic DOCX only and no private paths or renderer. */
class DraftNativeStoryConsumerCandidateTest {
    private static Map<String,Object> map(Object... entries){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)out.put((String)entries[i],entries[i+1]);return out;}
    private static Map<String,Object> prepared(){return map("effectiveValues",map("contractTitle",map("number","PKG-42","title","Qualified package title")),"inputValues",map("contractTitle",map("number","WRONG-SUGGESTION","title","Suggestion only")),"project",map("contractNo","TEMP-1","nameEn","Temporary metadata"),"unresolved",new ArrayList<>());}
    private static byte[] source() throws Exception {try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){document.createParagraph().createRun().setText("Body");document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("PKG-42");document.write(out);return out.toByteArray();}}

    @Test void qualifiedPackageIdentityDoesNotRequireTopbarRenameAndNeverReadsSuggestionsOrMetadata() throws Exception {
        Map<String,Object> prepared=prepared();Identity identity=DraftNativeIdentity.effectiveIdentity(prepared);assertEquals("PKG-42",identity.number);assertEquals("Qualified package title",identity.title);assertFalse(prepared.containsKey("confirmedFrom"));
        prepared.put("effectiveValues",map());assertNull(DraftNativeIdentity.effectiveIdentity(prepared));
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(source());NativeLayoutPlan body=new NativeLayoutPlan(index.getSourceSha256(),"synthetic-body",Collections.emptyList(),Collections.emptyList());assertSame(body,DraftNativeIdentity.attach(index,null,"NTT",prepared,"libreoffice",body));assertNull(body.getStories());assertEquals("NATIVE_STORY_IDENTITY_UNAVAILABLE",DraftBusinessRules.asMap(DraftBusinessRules.list(prepared.get("unresolved")).get(0)).get("code"));
    }

    @Test void unverifiedEditionPreservesSourceStoriesAndReturnsAnExplicitSourceReview() throws Exception {
        DocxTemplateEditor editor=new DocxTemplateEditor();byte[] source=source();TemplateIndex index=editor.inspect(source);NativeLayoutPlan body=new NativeLayoutPlan(index.getSourceSha256(),"synthetic-body",Collections.emptyList(),Collections.emptyList());Map<String,Object> prepared=prepared();
        assertSame(body,DraftNativeIdentity.attach(index,null,"NTT",prepared,"libreoffice",body));assertNull(body.getStories());assertEquals("NATIVE_STORY_SOURCE_EDITION_UNVERIFIED",DraftBusinessRules.asMap(DraftBusinessRules.list(prepared.get("unresolved")).get(0)).get("code"));assertEquals(index.getSourceSha256(),editor.inspect(source).getSourceSha256());
    }

    @Test void ordinaryBodyRevisionPreservesStoryProvenanceAndAdvancesItsCurrentDocxHash() throws Exception {
        DocxTemplateEditor editor=new DocxTemplateEditor();byte[] source=source();TemplateIndex index=editor.inspect(source);DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.singletonList(Edit.replaceSpan("body",index.mainParagraph(1),0,4,"Body","Changed body"))),Collections.emptyMap(),false);
        Map<String,Object> previous=map("kind","native_layout","status","applied","sourceSha256",index.getSourceSha256(),"docxSha256",index.getSourceSha256(),"actions",Collections.emptyList(),"nativeStories",map("kind","native_story","status","applied","sourceSha256",index.getSourceSha256(),"compiledDocxSha256",index.getSourceSha256(),"docxSha256",index.getSourceSha256(),"entryHashes",map("word/header1.xml",index.getEntryHashes().get("word/header1.xml")),"actions",Collections.emptyList()));
        Map<String,Object> current=layout(DraftNativeLayout.bodyLedger(JsonUtils.write(Collections.singletonList(previous)),result)),story=DraftBusinessRules.asMap(current.get("nativeStories"));assertEquals("applied",story.get("status"));assertEquals(index.getSourceSha256(),story.get("compiledDocxSha256"));assertEquals(index.getSourceSha256(),story.get("parentDocxSha256"));assertEquals(result.getDocxSha256(),story.get("docxSha256"));assertEquals(index.getEntryHashes().get("word/header1.xml"),result.getIndex().getEntryHashes().get("word/header1.xml"));assertEquals(index.getSourceSha256(),DraftBusinessRules.asMap(previous.get("nativeStories")).get("docxSha256"),"Previous immutable metadata stays untouched");
    }

    @Test void bodyRevisionDoesNotAdvertiseAppliedStoriesWhenTheirFrozenPartIdentityIsWrong() throws Exception {
        DocxTemplateEditor editor=new DocxTemplateEditor();byte[] source=source();TemplateIndex index=editor.inspect(source);DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.singletonList(Edit.replaceSpan("body",index.mainParagraph(1),0,4,"Body","Changed body"))),Collections.emptyMap(),false);
        Map<String,Object> previous=map("kind","native_layout","status","applied","sourceSha256",index.getSourceSha256(),"docxSha256",index.getSourceSha256(),"actions",Collections.emptyList(),"nativeStories",map("status","applied","docxSha256",index.getSourceSha256(),"entryHashes",map("word/header1.xml","0".repeat(64))));Map<String,Object> current=layout(DraftNativeLayout.bodyLedger(JsonUtils.write(Collections.singletonList(previous)),result));assertEquals("unavailable",current.get("status"));assertEquals("native_story_part_changed",DraftBusinessRules.asMap(current.get("nativeStories")).get("status"));
    }
    private static Map<String,Object> layout(List<Object> rows){for(Object row:rows)if(row instanceof Map&&"native_layout".equals(((Map<?,?>)row).get("kind")))return DraftBusinessRules.asMap(row);throw new AssertionError("Native layout ledger was lost");}
}
