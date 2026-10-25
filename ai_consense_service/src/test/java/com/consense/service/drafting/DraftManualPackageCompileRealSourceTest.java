package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Explicit local originals and already-reviewed manual inputs; no application writes, model, DB or renderer. */
class DraftManualPackageCompileRealSourceTest {
    @Test void threeActualOriginalsCompileWithTheReviewed63ValuesAndDerivedThreshold() throws Exception {
        String manifestPath=System.getProperty("consense.acceptance.manifest"),oraclePath=System.getProperty("consense.acceptance.oracle");
        assumeTrue(manifestPath!=null&&oraclePath!=null,"Explicit local input manifest and reviewed oracle are required");
        JsonNode manifest=JsonUtils.mapper().readTree(Files.readAllBytes(Paths.get(manifestPath))),oracle=JsonUtils.mapper().readTree(Files.readAllBytes(Paths.get(oraclePath)));
        Map<String,Object> values=new LinkedHashMap<>();int provided=0;
        for(JsonNode check:oracle.path("checks"))if("provided".equals(check.path("manualDisposition").asText())){values.put(check.path("key").asText(),JsonUtils.mapper().convertValue(check.path("suggestedManualValue"),Object.class));provided++;}
        assertEquals(63,provided);values.put("periodAtLeast39Months",false);assertEquals("L10Pro",values.get("electronicTendering"));
        List<Executable> packages=new ArrayList<>();
        for(String key:List.of("NTT","SCT","SCC"))packages.add(()->{
            JsonNode file=null;for(JsonNode entry:manifest.path("files"))if(entry.path("path").asText().endsWith("("+key+").docx")){assertNull(file);file=entry;}
            assertNotNull(file,key);Path path=Paths.get(file.path("path").asText());byte[] original=Files.readAllBytes(path);
            DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex source=editor.inspect(original);assertEquals(file.path("sha256").asText(),source.getSourceSha256());
            Map<String,Object> plan=DraftBusinessRules.plan(values);NativeLayoutPlan layout=DraftNativeIdentity.attach(source,observe(original,key,path.getFileName().toString()),key,plan,"libreoffice",DraftNativeLayout.plan(source,key,"libreoffice"));
            List<Map<String,Object>> bindings=DraftDocumentBindings.source(key,source);DraftDocumentBindings.associateTargets(key,source,plan,bindings);
            DocxEditResult output=DraftFormattedRules.compile(original,key,plan,DraftDocumentBindings.bookmarkNames(bindings),layout);
            assertArrayEquals(original,Files.readAllBytes(path));assertFalse(output.getIndex().getMainParagraphs().isEmpty());
            if("SCT".equals(key)){
                Map<String,Object> alternative=DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(row->"sct-issue-alternative".equals(row.get("id"))).findFirst().orElseThrow();
                assertEquals("applied",alternative.get("applicationState"));assertFalse(Boolean.TRUE.equals(alternative.get("manual")),"The selected L10Pro business decision must remain unchanged");
                Set<Integer> migrated=new HashSet<>();for(NativeStoryChange row:output.getNativeStoryLedger())if("CLEAR_EXACT_STATIC_FOOTER".equals(row.getAction())&&"APPLIED".equals(row.getStatus()))migrated.add(row.getSourceParagraph().getOrdinal());
                assertTrue(migrated.containsAll(List.of(253,254,302,303,368,369)),"All six former overlapping footer paragraphs must be migrated with receipts");
                for(int ordinal:List.of(230,255,304,370,386)){String text=source.mainParagraph(ordinal).getCatalogText();if(!text.isBlank())assertFalse(output.getIndex().getMainParagraphs().stream().anyMatch(p->text.equals(p.getCatalogText())),"Unselected branch paragraph P"+ordinal+" must be removed");}
            }
            String outputDirectory=System.getProperty("consense.acceptance.outputDir");if(outputDirectory!=null){Path directory=Paths.get(outputDirectory);Files.createDirectories(directory);Files.write(directory.resolve(key+"-compiled.docx"),output.getDocxBytes());Files.writeString(directory.resolve(key+"-plan.json"),JsonUtils.write(plan));Files.writeString(directory.resolve(key+"-story-ledger.json"),JsonUtils.write(output.getNativeStoryLedger()));}
        });
        assertAll("NTT/SCT/SCC mechanical compilation",packages);
    }
}
