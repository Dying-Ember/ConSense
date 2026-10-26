package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real editor operations on authored content; no source-quality or renderer acceptance. */
class DraftSourceRemovalLedgerStateTest {
    @Test void laterEditorSavePreservesThePriorTocProofAndRecordsItsExactNativeParent()throws Exception {
        byte[] source;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){document.createParagraph().createRun().setText("AUTHORED retained body");document.write(output);source=output.toByteArray();}
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(source);DocxEditResult initial=editor.apply(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()));Map<String,Object> layout=DraftNativeLayout.info("SCC","libreoffice",initial);
        Map<String,Object> toc=DraftBusinessRules.map("kind","toc_finalization","status","PASS","compiledDocxSha256","0".repeat(64),"finalDocxSha256",index.getSourceSha256(),"headingFlowRepairCount",0,"headingMutationCount",0);List<Object> previous=new ArrayList<>(Arrays.asList(layout,toc));Paragraph p=index.mainParagraph(1);DocxEditResult changed=editor.apply(source,new SourceEditBatch(index.getSourceSha256(),Collections.singletonList(Edit.replaceSpan("body-1",p,0,p.getText().length(),p.getText(),"AUTHORED manual body successor"))));
        List<Object> saved=DraftNativeLayout.bodyLedger(JsonUtils.write(previous),changed);assertTrue(saved.stream().anyMatch(raw->JsonUtils.mapper().valueToTree(raw).equals(JsonUtils.mapper().valueToTree(toc))),"Dropping the old TOC proof breaks the cleanup-to-current path");assertTrue(DraftBodyCleanup.verifiedSavedTransition(index.getSourceSha256(),changed.getDocxSha256(),saved));assertFalse(DraftBodyCleanup.verifiedSavedTransition(index.getSourceSha256(),"f".repeat(64),saved));
    }
    @Test void twoBodyEditsRetainTheOriginalSourceRemovalAndItsCompiledIdentityExactlyOnce()throws Exception {
        byte[] source;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){document.createParagraph().createRun().setText("AUTHORED excluded branch");document.createParagraph().createRun().setText("AUTHORED retained body");document.write(output);source=output.toByteArray();}
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex original=editor.inspect(source);DocxEditResult generated=editor.apply(source,new SourceEditBatch(original.getSourceSha256(),Arrays.asList(Edit.removeNodes("adopted-remove-scope",Arrays.asList(original.anchor(original.mainParagraph(1).getId()))))));
        String generatedHash=generated.getDocxSha256();List<Object> ledger=DraftNativeLayout.ledger(generated,DraftNativeLayout.info("SCC","libreoffice",generated));
        for(int edit=1;edit<=2;edit++){Paragraph body=generated.getIndex().mainParagraph(1);DocxEditResult next=editor.apply(generated.getDocxBytes(),new SourceEditBatch(generated.getDocxSha256(),Arrays.asList(Edit.replaceSpan("body-edit-"+edit,body,0,body.getText().length(),body.getText(),"AUTHORED retained body edit "+edit))));
            ledger=DraftNativeLayout.bodyLedger(JsonUtils.write(ledger),next);List<Map<String,Object>> removals=new ArrayList<>();Map<String,Object> layout=null;for(Object raw:JsonUtils.readList(JsonUtils.write(ledger),Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("adopted-remove-scope".equals(row.get("operationId")))removals.add(row);if("native_layout".equals(row.get("kind")))layout=row;}
            assertEquals(1,removals.size(),"Each subsequent body edit must preserve exactly one original deletion record");assertEquals(original.getSourceSha256(),removals.get(0).get("sourceSha256"));assertEquals(generatedHash,removals.get(0).get("generatedSha256"));assertNotNull(layout);assertEquals(generatedHash,layout.get("compiledDocxSha256"));assertEquals(next.getDocxSha256(),layout.get("docxSha256"));generated=next;
        }
    }
}
