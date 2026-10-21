package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exact 2a source and real approved compiler/editor operations; no LO, DB, model or full frontend acceptance. */
class DraftNativeHeadingRemovalRealSourceTest {
    @Test void actualFireBranchRemovalExcludesTwelveRelationshipsAndKeepsTheNotUsedHeadingAndAllTocTargets()throws Exception {
        String directory=System.getProperty("consense.acceptance.sourceDir");assumeTrue(directory!=null&&!directory.isBlank(),"Explicit actual sourceDir is required");
        byte[] original=Files.readAllBytes(Paths.get(directory,"06_Special Conditions of Contract (SCC).docx"));DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex source=editor.inspect(original);assertEquals(DraftNativeHeadingBoundariesTest.ORIGINAL_SOURCE_SHA,source.getSourceSha256());
        Map<String,Object> action=DraftBusinessRules.map("id","scc-specialist-SCC20.303","document","SCC","clause","SCC20.303","action","not_adopted","decisionAction","not_adopted","inputKeys",Arrays.asList("subcontractors","subcontractArrangement"));
        Map<String,Object> plan=DraftBusinessRules.map("actions",new ArrayList<>(Arrays.asList(action)),"effectiveValues",Collections.emptyMap());List<Map<String,Object>> bindings=DraftDocumentBindings.source("SCC",source);DraftDocumentBindings.associateTargets("SCC",source,plan,bindings);
        // The production formatter clears the source-verified preparer notes before checking its registered empty gaps.
        DocxEditResult result=DraftFormattedRules.compile(original,"SCC",plan,DraftDocumentBindings.bookmarkNames(bindings),DraftNativeLayout.plan(source,"SCC","libreoffice"));
        assertEquals("applied",action.get("applicationState"),JsonUtils.write(action));
        assertTrue(result.getLedger().stream().anyMatch(edit->"SCC-editorial-note-01-P100".equals(edit.getOperationId())),"The unchanged production formatter must clear the first registered guidance gap");
        List<Map<String,Object>> generated=DraftDocumentBindings.generated(bindings,result);List<Object> ledger=DraftNativeLayout.ledger(result,DraftNativeLayout.info("SCC","libreoffice",result));Map<String,Object> proof=new LinkedHashMap<>();
        List<NativeHeadingFlow> active=DraftNativeHeadingRemovalAuthorizationTest.selectFlows(result.getDocxBytes(),generated,ledger,proof);
        assertDoesNotThrow(()->editor.updateNativeHeadingBoundaries(result.getDocxBytes(),result.getDocxSha256(),active,Collections.emptySet()),"An approved actual deletion must not leave the original full flow set demanding the deleted endpoint");
        assertEquals(239,proof.get("headingFlowDenominator"));assertEquals(227,active.size());assertEquals(12,proof.get("headingFlowExcludedAuthorized"));assertEquals(Arrays.asList(26,81,93,94,101,102,140,141,142,143,144,233),DraftBusinessRules.list(proof.get("excludedAuthorizedHeadingFlows")).stream().map(DraftBusinessRules::asMap).map(r->r.get("registeredOrdinal")).collect(java.util.stream.Collectors.toList()));
        Map<String,Object> heading=generated.stream().filter(r->Integer.valueOf(1550).equals(r.get("sourceParagraphOrdinal"))).findFirst().orElseThrow();assertEquals("exact",heading.get("locationStatus"));assertEquals("SCC20.303Notused",String.valueOf(heading.get("text")).replaceAll("(?U)\\s+",""));
        assertEquals(92,editor.tocPageReferences(result.getDocxBytes()).size());Set<String> targets=new LinkedHashSet<>();for(TocPageReference reference:editor.tocPageReferences(result.getDocxBytes()))assertTrue(targets.add(reference.getTargetBookmark()));assertEquals(92,targets.size());
        String originalCompiled=result.getDocxSha256();DocxEditResult previous=result;
        for(int bodyEdit=1;bodyEdit<=2;bodyEdit++){
            Map<String,Object> retained=generated.stream().filter(r->Integer.valueOf(1665).equals(r.get("sourceParagraphOrdinal"))).findFirst().orElseThrow();assertEquals("exact",retained.get("locationStatus"));Paragraph paragraph=previous.getIndex().paragraph(String.valueOf(retained.get("resultParagraphId")));
            DocxEditResult next=editor.applyWithParagraphBookmarks(previous.getDocxBytes(),new SourceEditBatch(previous.getDocxSha256(),DraftSourceEditCompiler.editableSpans("body-test-"+bodyEdit,paragraph,paragraph.getText()+" AUTHORED TEST ONLY body edit "+bodyEdit)),Collections.emptyMap(),true);
            generated=DraftDocumentBindings.bodyEdited(generated,next);ledger=DraftNativeLayout.bodyLedger(JsonUtils.write(ledger),next);Map<String,Object> after=new LinkedHashMap<>();List<NativeHeadingFlow> afterFlows=DraftNativeHeadingRemovalAuthorizationTest.selectFlows(next.getDocxBytes(),generated,ledger,after);
            assertEquals(239,after.get("headingFlowDenominator"));assertEquals(227,afterFlows.size());assertEquals(12,after.get("headingFlowExcludedAuthorized"));assertEquals(92,editor.tocPageReferences(next.getDocxBytes()).size());
            assertDoesNotThrow(()->editor.updateNativeHeadingBoundaries(next.getDocxBytes(),next.getDocxSha256(),afterFlows,Collections.emptySet()));
            Map<String,Object> layout=ledger.stream().filter(r->"native_layout".equals(DraftBusinessRules.asMap(r).get("kind"))).map(DraftBusinessRules::asMap).findFirst().orElseThrow();assertEquals(originalCompiled,layout.get("compiledDocxSha256"));assertEquals(next.getDocxSha256(),layout.get("docxSha256"));previous=next;
        }
    }
}
