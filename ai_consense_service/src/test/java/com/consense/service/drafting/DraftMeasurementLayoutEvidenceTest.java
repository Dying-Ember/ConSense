package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Mechanical ledger fixtures over the exact source; no Spring, renderer or generation acceptance. */
class DraftMeasurementLayoutEvidenceTest {
    private static final String BODY="word/document.xml#/w:document[1]/w:body[1]/w:p[",PROFILE="consense-lo-scc-cleared-20261022.v2";
    private static byte[] original()throws IOException {String directory=System.getProperty("consense.acceptance.sourceDir");assumeTrue(directory!=null&&!directory.isBlank(),"Explicit actual sourceDir required");return Files.readAllBytes(Paths.get(directory,"06_Special Conditions of Contract (SCC).docx"));}
    private static org.w3c.dom.Document xml(byte[] source)throws Exception {javax.xml.parsers.DocumentBuilderFactory f=javax.xml.parsers.DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(DraftingArtifactIntegrationTest.parts(source).get("word/document.xml")));}
    private static Map<String,Object> action(DocxTemplateEditor.TemplateIndex source,int p,String kind,String suffix){String id=BODY+p+"]";return DraftBusinessRules.map("action",kind,"status","APPLIED","sourceId",id,"sourceNodeSha256",source.anchor(id).getSha256(),"layoutId","CL"+DraftAdoption.hash(source.getSourceSha256()+"|"+PROFILE+"|"+suffix+"|"+id).substring(0,28));}
    private static Map<String,Object> document(DocxTemplateEditor.TemplateIndex source,boolean provisional){List<Object> actions=new ArrayList<>();for(int p:new int[]{467,468,755,756,757,758})actions.add(action(source,p,"keep_verified_paragraph_with_next","keep-next"));actions.add(action(source,469,"keep_verified_cleared_guidance_with_next","keep-next"));actions.add(action(source,755,"page_break_before_verified_heading","heading-break"));for(int p:new int[]{1046,1342,1829})if(p!=1046||provisional)actions.add(action(source,p,"release_verified_trailing_page_break","heading-flow-trailing-break"));String docx="0".repeat(64);return DraftBusinessRules.map("docxSha256",docx,"nativeLayout",DraftBusinessRules.map("status","applied","profile",PROFILE,"sourceSha256",source.getSourceSha256(),"docxSha256",docx,"actions",actions));}
    private static List<Object> actions(Map<String,Object> document){return DraftBusinessRules.list(DraftBusinessRules.asMap(document.get("nativeLayout")).get("actions"));}
    private static Map<String,Object> copy(Map<String,Object> document){return JsonUtils.readMap(JsonUtils.write(document));}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void countsOnlyAppliedRetainedSourceReleasesWithoutChargingTheRemovedOptionTwice(boolean provisional)throws Exception {
        byte[] original=original();DocxTemplateEditor.TemplateIndex source=new DocxTemplateEditor().inspect(original);Map<String,Object> document=document(source,provisional);
        assertEquals(provisional?11:10,DraftingArtifactIntegrationTest.verifiedMeasurementLayoutMarkers(document,source).size());
        assertEquals(provisional?4:3,DraftingArtifactIntegrationTest.verifiedMeasurementReleasedPageBreaks(document,source,xml(original),provisional?1017:1029,provisional?1027:1061));
    }
    @Test void unknownAppliedActionForgedSourceHashMarkerAndDuplicateRemainRejected()throws Exception {
        DocxTemplateEditor.TemplateIndex source=new DocxTemplateEditor().inspect(original());Map<String,Object> valid=document(source,false);
        for(String key:Arrays.asList("action","sourceNodeSha256","layoutId")){Map<String,Object> bad=copy(valid);DraftBusinessRules.asMap(actions(bad).get(0)).put(key,"UNKNOWN_OR_FORGED");assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementLayoutMarkers(bad,source),key);}
        Map<String,Object> wrongSource=copy(valid);DraftBusinessRules.asMap(wrongSource.get("nativeLayout")).put("sourceSha256","wrong-source");assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementLayoutMarkers(wrongSource,source));
        Map<String,Object> duplicate=copy(valid);actions(duplicate).add(new LinkedHashMap<>(DraftBusinessRules.asMap(actions(duplicate).get(0))));assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementLayoutMarkers(duplicate,source));
    }
    @Test void missingMandatoryControlOrReleaseAndAnAppliedReleaseInsideTheRemovedBranchAreRejected()throws Exception {
        byte[] original=original();DocxTemplateEditor.TemplateIndex source=new DocxTemplateEditor().inspect(original);Map<String,Object> valid=document(source,false);
        Map<String,Object> missingOld=copy(valid);actions(missingOld).remove(0);assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementLayoutMarkers(missingOld,source));
        Map<String,Object> missingNew=copy(valid);actions(missingNew).removeIf(a->(BODY+"1829]").equals(DraftBusinessRules.asMap(a).get("sourceId")));assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementReleasedPageBreaks(missingNew,source,xml(original),1029,1061));
        Map<String,Object> doubleCharge=copy(valid);actions(doubleCharge).add(action(source,1046,"release_verified_trailing_page_break","heading-flow-trailing-break"));assertThrows(AssertionError.class,()->DraftingArtifactIntegrationTest.verifiedMeasurementReleasedPageBreaks(doubleCharge,source,xml(original),1029,1061));
    }
}
