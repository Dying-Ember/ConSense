package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real source, semantic whole-clause compiler and default native layout; no renderer/model. */
class DraftNttWholeClauseFurnitureTest {
    @ParameterizedTest @ValueSource(strings={"ordinary-false","not_used","delete"})
    void wtoNegativePreservesTheFollowingGeneralContinuationTitleAndBondClause(String decision)throws Exception {
        String configured=System.getProperty("consense.acceptance.sourceDir");assumeTrue(configured!=null,"Actual standard sourceDir required");
        byte[] source=Files.readAllBytes(Paths.get(configured,"01_Notes to Tenderers (NTT).docx"));assertEquals("60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",DraftPdfConverter.sha256(source));
        Map<String,Object> values="ordinary-false".equals(decision)?DraftBusinessRules.map("wtoGpaApplies",false):DraftBusinessRules.map("targetOverrides",Collections.singletonList(DraftBusinessRules.map("actionId","NTT-9-WTO","action",decision)));
        DocxTemplateEditor.DocxEditResult result=DraftFormattedRules.compile(source,"NTT",DraftBusinessRules.plan(values),Collections.emptyMap(),DraftNativeLayout.plan(source,"NTT","libreoffice"));
        for(int ordinal:new int[]{473,474,477,481,482,484})assertTrue(NativeDocxComparison.retainsParagraph(source,ordinal,result.getDocxBytes()),"Outside NTT9 source P"+ordinal+" remains exact, including the following page furniture and retained NTT10");
        assertFalse(result.getIndex().getMainParagraphs().stream().anyMatch(p->p.getCatalogText().contains("This tender is covered by the Agreement on Government Procurement")));
        DraftingArtifactIntegrationTest.opaquePartsIdentical(source,result.getDocxBytes());
    }
}
