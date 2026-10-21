package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Frozen v1 source policy remains separate from new v2; no database, renderer or visual acceptance. */
class DraftNativeHeadingProfileCompatibilityTest {
    private static final String SOURCE="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
    private static final String LEGACY="consense-lo-scc-cleared-20261010.v1",CURRENT="consense-lo-scc-cleared-20261022.v2";
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();

    @Test void authoredEmptyContextStillUsesTheCurrentFullDenominator()throws Exception {
        byte[] authored=DraftNativeHeadingBoundariesTest.fixture();Map<String,Object> proof=new LinkedHashMap<>();
        List<NativeHeadingFlow> flows=DraftNativeLayout.activeHeadingFlows(SOURCE,"libreoffice",EDITOR.inspect(authored),Collections.emptyList(),Collections.emptyList(),proof);
        assertEquals(244,flows.size());assertEquals(244,proof.get("headingFlowDenominator"));assertEquals(0,proof.get("headingFlowExcludedAuthorized"));
        assertDoesNotThrow(()->EDITOR.updateNativeHeadingBoundaries(authored,DraftPdfConverter.sha256(authored),flows,Collections.emptySet()));
    }
    @Test void currentSavedPolicyStillRequiresAll244RelationsAndItsOneAuthorizedRemoval()throws Exception {
        DraftNativeHeadingRemovalAuthorizationTest.Scenario scenario=new DraftNativeHeadingRemovalAuthorizationTest.Scenario();Map<String,Object> proof=new LinkedHashMap<>();
        assertEquals(243,scenario.select(proof).size());assertEquals(CURRENT,proof.get("headingFlowProfile"));assertEquals(245,proof.get("headingFlowBoundaryRows"));assertEquals(244,proof.get("headingFlowRegistered"));assertEquals(1,proof.get("headingFlowExcludedAuthorized"));assertEquals(1,proof.get("headingFlowProfileBindingCount"));
    }
    @Test void unknownDuplicateAndForgedSavedPoliciesCannotLowerTheDenominator()throws Exception {
        for(int fault=0;fault<15;fault++){
            DraftNativeHeadingRemovalAuthorizationTest.Scenario scenario=new DraftNativeHeadingRemovalAuthorizationTest.Scenario();
            switch(fault){
                case 0:scenario.layout.put("profile","unverified-policy");break;
                case 1:scenario.layout.remove("profile");break;
                case 2:scenario.ledger.add(new LinkedHashMap<>(scenario.layout));break;
                case 3:scenario.layout.put("sourceSha256","foreign-source");break;
                case 4:scenario.layout.put("docxSha256",DraftPdfConverter.sha256(scenario.retained));break;
                case 5:scenario.layout.put("compiledDocxSha256","unbound");break;
                case 6:scenario.layout.put("profile",LEGACY);break; // Valid old name, but the actual CL control belongs to v2.
                case 7:scenario.layout.put("actions",Collections.emptyList());break;
                case 8:DraftBusinessRules.asMap(DraftBusinessRules.list(scenario.layout.get("actions")).get(0)).put("sourceNodeSha256","foreign-node");break;
                case 9:DraftBusinessRules.asMap(DraftBusinessRules.list(scenario.layout.get("actions")).get(0)).put("layoutId","CLforgedPolicyControl");break;
                case 10:{Map<String,Object> witness=DraftBusinessRules.asMap(DraftBusinessRules.list(scenario.layout.get("actions")).get(0));String name=String.valueOf(witness.get("layoutId")),xml=DraftNativeHeadingBoundariesTest.xml(scenario.removed);scenario.removed=DraftNativeHeadingBoundariesTest.packageXml(xml.replace(DraftNativeHeadingBoundariesTest.marker(990002,name),"").replace(DraftNativeHeadingBoundariesTest.end(990002),""));scenario.layout.put("docxSha256",DraftPdfConverter.sha256(scenario.removed));break;}
                case 11:DraftBusinessRules.asMap(DraftBusinessRules.list(scenario.layout.get("actions")).get(0)).put("status","SKIPPED_REMOVED");break;
                case 12:{Object witness=DraftBusinessRules.list(scenario.layout.get("actions")).get(0);scenario.layout.put("actions",Arrays.asList(witness,witness));break;}
                case 13:scenario.bindings.clear();scenario.layout.put("profile","unverified-policy-with-empty-bindings");break;
                default:{Map<String,Object> witness=DraftBusinessRules.asMap(DraftBusinessRules.list(scenario.layout.get("actions")).get(0));String oldName=String.valueOf(witness.get("layoutId")),foreignId="word/document.xml#/w:document[1]/w:body[1]/w:p[999999]",forged="CL"+DraftAdoption.hash(SOURCE+"|"+CURRENT+"|heading-flow-keep|"+foreignId).substring(0,28);witness.put("sourceId",foreignId);witness.put("sourceNodeSha256",null);witness.put("layoutId",forged);scenario.removed=DraftNativeHeadingBoundariesTest.packageXml(DraftNativeHeadingBoundariesTest.xml(scenario.removed).replace("w:name=\""+oldName+"\"","w:name=\""+forged+"\""));scenario.layout.put("docxSha256",DraftPdfConverter.sha256(scenario.removed));}
            }
            assertThrows(IllegalStateException.class,()->scenario.select(new LinkedHashMap<>()),"fault "+fault);
        }
    }
    @Test void legacySelectionRequiresTheWholeFrozen240RowDigest()throws Exception {
        Map<String,Object> full;try(InputStream in=DraftNativeLayout.class.getResourceAsStream("/drafting/libreoffice-native-layout.json")){assertNotNull(in);full=JsonUtils.mapper().readValue(in,Map.class);}
        Map<String,Object> source=DraftBusinessRules.list(full.get("sources")).stream().map(DraftBusinessRules::asMap).filter(r->SOURCE.equals(r.get("sourceSha256"))).findFirst().orElseThrow();
        Method select=DraftNativeLayout.class.getDeclaredMethod("savedHeadingBoundaries",Map.class,String.class);select.setAccessible(true);
        assertEquals(240,((List<?>)select.invoke(null,source,LEGACY)).size());assertEquals(245,((List<?>)select.invoke(null,source,CURRENT)).size());
        DraftBusinessRules.asMap(DraftBusinessRules.list(source.get("paragraphBoundaries")).get(0)).put("headingSha256","changed-old-node");
        InvocationTargetException invalid=assertThrows(InvocationTargetException.class,()->select.invoke(null,source,LEGACY));assertInstanceOf(IllegalStateException.class,invalid.getCause());assertEquals("HEADING_FLOW_LEGACY_PROFILE_CHANGED",invalid.getCause().getMessage());
    }
    @Test void actualRestoredR2AndTwoBodyEditsKeep239Registered227ActiveAnd12AuthorizedExclusions()throws Exception {
        String configured=System.getProperty("consense.audit.profileCompatDir");assumeTrue(configured!=null&&!configured.isBlank(),"Explicit preserved actual runtime33 restore-context01 required; otherwise SKIP");Path directory=Paths.get(configured);
        byte[] bytes=Files.readAllBytes(directory.resolve("SCC-saved.docx"));assertEquals("b54655e26a8b9a06998bde87ec9ceef6cf515d69d3bf88e5914dd8a3f6e626d3",DraftPdfConverter.sha256(bytes));
        List<Map<String,Object>> bindings=DraftDocumentBindings.read(Files.readString(directory.resolve("bindings.json")));List<Object> ledger=JsonUtils.readList(Files.readString(directory.resolve("ledger.json")),Object.class);
        Map<String,Object> originalLayout=ledger.stream().map(DraftBusinessRules::asMap).filter(r->"native_layout".equals(r.get("kind"))).findFirst().orElseThrow();assertEquals(SOURCE,originalLayout.get("sourceSha256"));assertEquals(LEGACY,originalLayout.get("profile"));String compiled=String.valueOf(originalLayout.get("compiledDocxSha256")),originalText=null;
        for(int step=0;step<3;step++){
            TemplateIndex index=EDITOR.inspect(bytes);Map<String,Object> proof=new LinkedHashMap<>();List<NativeHeadingFlow> flows=DraftNativeLayout.activeHeadingFlows(SOURCE,"libreoffice",index,bindings,ledger,proof);
            assertEquals(239,proof.get("headingFlowDenominator"));assertEquals(227,flows.size());assertEquals(12,proof.get("headingFlowExcludedAuthorized"));assertEquals(LEGACY,proof.get("headingFlowProfile"));assertEquals(240,proof.get("headingFlowBoundaryRows"));assertTrue(((Number)proof.get("headingFlowProfileBindingCount")).intValue()>0);
            assertEquals(Arrays.asList(26,81,93,94,101,102,140,141,142,143,144,233),DraftBusinessRules.list(proof.get("excludedAuthorizedHeadingFlows")).stream().map(DraftBusinessRules::asMap).map(r->r.get("registeredOrdinal")).collect(java.util.stream.Collectors.toList()));
            byte[] current=bytes;assertDoesNotThrow(()->EDITOR.updateNativeHeadingBoundaries(current,index.getSourceSha256(),flows,Collections.emptySet()));assertEquals(92,EDITOR.tocPageReferences(bytes).size());
            Map<String,Object> retained=bindings.stream().filter(r->Integer.valueOf(1665).equals(r.get("sourceParagraphOrdinal"))).findFirst().orElseThrow();assertEquals("exact",retained.get("locationStatus"));Paragraph target=index.paragraph(String.valueOf(retained.get("resultParagraphId")));if(step==0)originalText=target.getText();if(step==2){assertEquals(originalText,target.getText());break;}
            String nextText=step==0?originalText+" [PROFILE COMPAT46 TEST ONLY]":originalText;DocxEditResult changed=EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("profile-compat46-"+step,target,nextText)),Collections.emptyMap(),true);
            bindings=DraftDocumentBindings.bodyEdited(bindings,changed);ledger=DraftNativeLayout.bodyLedger(JsonUtils.write(ledger),changed);bytes=changed.getDocxBytes();Map<String,Object> saved=ledger.stream().map(DraftBusinessRules::asMap).filter(r->"native_layout".equals(r.get("kind"))).findFirst().orElseThrow();assertEquals(LEGACY,saved.get("profile"));assertEquals(compiled,saved.get("compiledDocxSha256"));assertEquals(changed.getDocxSha256(),saved.get("docxSha256"));
        }
    }
}
