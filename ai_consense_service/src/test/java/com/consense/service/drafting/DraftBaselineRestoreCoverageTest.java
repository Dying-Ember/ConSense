package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.ParseCoverage;
import com.consense.domain.SourceDocument;
import com.consense.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftBaselineRestoreCoverageTest {
    private static final String SHA="a".repeat(64),ANALYSIS="b".repeat(64);
    @TempDir Path directory;

    @Test void partialRequirementsRebindAcrossProjectIdsWithoutClaimingCompleteCoverage(){
        Map<String,Object> origin=graph("old",true),shared=DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),false,origin);
        assertNotNull(shared);assertEquals(1,list(state(shared).get("requirements")).size());assertFalse(state(shared).containsKey("baselineRequirementsValidated"));
        Map<String,Object> restored=DraftBaselineRestore.restore(shared,graph("new",false),Collections.singletonList("duration"),false);
        assertNotNull(restored);assertEquals("new",map(list(state(restored).get("requirements")).get(0)).get("sourceId"));assertEquals("pending",nodes(restored).get(0).get("status"));assertEquals("partial_requires_review",map(state(restored).get("baselineRestoration")).get("status"));
        assertFalse(state(restored).containsKey("sourceTaskCoverage"));
    }
    @Test void confirmedDefinitionWithOmittedBqBranchesNeverBecomesAnEntireCoveredTask(){
        Map<String,Object> origin=graph("old",true);nodes(origin).get(0).put("nameEn","BQ issue format");nodes(origin).get(0).put("status","pending");
        state(origin).put("automaticTaskProofs",Collections.singletonList(obj("taskId","clean-model-task","nodeIds",Collections.singletonList("duration"),"requirementIds",Collections.singletonList("req-duration"))));
        state(origin).put("sourceTaskCoverage",Collections.singletonList(obj("nodeIds",Collections.singletonList("duration"),"requirementIds",Collections.singletonList("req-duration"))));
        Map<String,Object> shared=DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),false,origin);
        assertNotNull(shared);assertTrue(list(nodes(shared).get(0).get("editTargets")).isEmpty());assertFalse(state(shared).containsKey("automaticTaskProofs"));assertFalse(state(shared).containsKey("sourceTaskCoverage"));
        Map<String,Object> restored=DraftBaselineRestore.restore(shared,graph("new",false),Collections.singletonList("duration"),false);assertNotNull(restored);assertFalse(state(restored).containsKey("sourceTaskCoverage"));assertEquals("partial_requires_review",map(state(restored).get("baselineRestoration")).get("status"));
    }
    @Test void aRequirementThatAlsoReferencesAnUnsharedFactIsNotCopied(){
        Map<String,Object> origin=graph("old",true),second=JsonUtils.readMap(JsonUtils.write(nodes(origin).get(0)));second.put("id","other");List<Object> variables=new ArrayList<>(list(state(origin).get("variables")));variables.add(second);state(origin).put("variables",variables);
        map(list(state(origin).get("requirements")).get(0)).put("nodeIds",Arrays.asList("duration","other"));
        Map<String,Object> shared=DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),false,origin);assertNotNull(shared);assertTrue(list(state(shared).get("requirements")).isEmpty());
    }
    @Test void changedAnalysisKeepsDefinitionsButDropsOldRequirementEvidence(){
        Map<String,Object> origin=graph("old",true),shared=DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),false,origin),current=graph("new",false);
        map(list(current.get("sources")).get(0)).put("analysisSha256","c".repeat(64));Map<String,Object> restored=DraftBaselineRestore.restore(shared,current,Collections.singletonList("duration"),false);
        assertNotNull(restored);assertEquals(1,nodes(restored).size());assertTrue(list(state(restored).get("requirements")).isEmpty());
    }
    @Test void changedRequirementEvidenceAndUntrustedOriginAreNotShared(){
        Map<String,Object> origin=graph("old",true),changed=JsonUtils.readMap(JsonUtils.write(origin));map(list(state(changed).get("requirements")).get(0)).put("sourceQuote","Another instruction");
        Map<String,Object> shared=DraftBaselineRestore.snapshot(changed,Collections.singletonList("duration"),false,origin);assertNotNull(shared);assertTrue(list(state(shared).get("requirements")).isEmpty());
        assertTrue(list(state(DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),false)).get("requirements")).isEmpty());assertNull(DraftBaselineRestore.snapshot(origin,Collections.singletonList("duration"),true));
    }
    @Test void exactBoundTemplateWordingSurvivesFixedAndSystemDependenciesWithoutProjectValues(){
        for(String role:Arrays.asList("fixed","system")){
            Map<String,Object> original=graph("old",true),literal=JsonUtils.readMap(JsonUtils.write(nodes(original).get(0)));literal.put("id","template-wording");literal.put("role",obj("kind",role));literal.put("type",obj("kind","text"));literal.put("fixedValue","Duration: ____ months.");
            Map<String,Object> input=nodes(original).get(0);input.put("relatedInputIds",Collections.singletonList("template-wording"));input.put("value","PROJECT_ANSWER");input.put("fixedValue","PROJECT_ANSWER");state(original).put("variables",Arrays.asList(input,literal));
            Map<String,Object> shared=DraftBaselineRestore.snapshot(original,Collections.singletonList("duration"),true,original);assertNotNull(shared,role);assertFalse(JsonUtils.write(shared).contains("PROJECT_ANSWER"));
            Map<String,Object> restored=DraftBaselineRestore.restore(shared,graph("new",false),Collections.singletonList("duration"),true);assertNotNull(restored,role);Map<String,Object> restoredLiteral=nodes(restored).stream().filter(node->"template-wording".equals(node.get("id"))).findFirst().orElseThrow();assertEquals("Duration: ____ months.",restoredLiteral.get("fixedValue"));assertEquals("pending",restoredLiteral.get("status"));assertEquals("new",map(list(restoredLiteral.get("sourceAnchors")).get(0)).get("docId"));assertFalse(map(list(restoredLiteral.get("sourceAnchors")).get(0)).containsKey("nativeBinding"));
        }
    }
    @Test void substringsUnboundWordingAndBooleanFactsDoNotBecomeTemplateConstants(){
        for(String variant:Arrays.asList("substring","unbound","boolean","system-number")){
            Map<String,Object> original=graph("old",true),literal=JsonUtils.readMap(JsonUtils.write(nodes(original).get(0)));literal.put("id","template-wording");literal.put("role",obj("kind",variant.equals("system-number")?"system":"fixed"));literal.put("type",obj("kind","text"));literal.put("fixedValue","Duration: ____ months.");Map<String,Object> anchor=map(list(literal.get("sourceAnchors")).get(0));
            if("substring".equals(variant))literal.put("fixedValue","months.");if("unbound".equals(variant))anchor.put("nativeBinding",obj("status","missing"));if("boolean".equals(variant)){literal.put("type",obj("kind","boolean"));literal.put("fixedValue",true);anchor.put("text","true");}if("system-number".equals(variant)){literal.put("type",obj("kind","number"));literal.put("fixedValue","39");anchor.put("text","39");}
            Map<String,Object> input=nodes(original).get(0);input.put("relatedInputIds",Collections.singletonList("template-wording"));state(original).put("variables",Arrays.asList(input,literal));assertNull(DraftBaselineRestore.snapshot(original,Collections.singletonList("duration"),true,original),variant);
        }
    }
    @Test void namedPdfObservationSurvivesSharingAndRebindsOnlyToTheSameCurrentEvidence()throws Exception{
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(directory.toString());DraftReviewSources reader=new DraftReviewSources(new StorageService(properties),properties);
        SourceDocument first=pdfSource(1),second=pdfSource(2);JsonNode reading=reader.reading(first),note=reading.path("guidanceNotes").path(0);
        ObjectNode passage=JsonUtils.mapper().createObjectNode().put("id","pdf-anchor").put("docId","1").put("sourceDocumentId",1).put("sourceSha256",first.getSourceSha256()).put("text",note.path("text").asText()).put("sourceObservationId",note.path("id").asText());
        passage.set("sourceObservation",note.deepCopy());passage.set("observedRanges",note.path("memberRanges").deepCopy());for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))passage.set(key,reading.path(key).deepCopy());
        ObjectNode binding=reader.bindObservation(first,passage);assertEquals("bound",binding.path("status").asText());passage.set("sourceObservationBinding",binding);
        Map<String,Object> original=graph("1",true);original.put("sources",Collections.singletonList(identity(reading)));nodes(original).get(0).put("sourceAnchors",Collections.singletonList(JsonUtils.mapper().convertValue(passage,Map.class)));
        Map<String,Object> shared=DraftBaselineRestore.snapshot(original,Collections.singletonList("duration"),false);assertNotNull(shared);
        Map<String,Object> current=graph("2",false);current.put("sources",Collections.singletonList(identity(reader.reading(second))));Map<String,Object> restored=DraftBaselineRestore.restore(shared,current,Collections.singletonList("duration"),false);assertNotNull(restored);
        ObjectNode restoredAnchor=JsonUtils.mapper().valueToTree(list(nodes(restored).get(0).get("sourceAnchors")).get(0));assertEquals(2,restoredAnchor.path("sourceDocumentId").asInt());assertEquals(note,restoredAnchor.path("sourceObservation"));assertEquals(passage.path("observedRanges"),restoredAnchor.path("observedRanges"));assertFalse(restoredAnchor.has("sourceObservationBinding"));assertFalse(restoredAnchor.has("native"));
        ObjectNode rebound=reader.bindObservation(second,restoredAnchor);assertEquals("bound",rebound.path("status").asText());assertFalse(rebound.path("editableAuthority").asBoolean());assertNotEquals("bound",reader.bind(second,restoredAnchor).path("status").asText());
        ObjectNode wrong=restoredAnchor.deepCopy();wrong.put("sourceDocumentId",1);assertEquals("SOURCE_DOCUMENT_MISMATCH",reader.bindObservation(second,wrong).path("reason").asText());
        wrong=restoredAnchor.deepCopy();wrong.put("analysisSha256","old-or-forged");assertEquals("SOURCE_OBSERVATION_ANALYSIS_CHANGED",reader.bindObservation(second,wrong).path("reason").asText());
        wrong=restoredAnchor.deepCopy();((ObjectNode)wrong.path("observedRanges").path(0)).put("end",1);assertEquals("SOURCE_OBSERVATION_SCOPE_CHANGED",reader.bindObservation(second,wrong).path("reason").asText());
        wrong=restoredAnchor.deepCopy();((ObjectNode)wrong.path("sourceObservation")).put("captureComplete",false);assertEquals("SOURCE_OBSERVATION_OBJECT_CHANGED",reader.bindObservation(second,wrong).path("reason").asText());
        map(list(nodes(original).get(0).get("sourceAnchors")).get(0)).put("sourceObservationBinding",obj("status","unsafe"));assertTrue(list(nodes(DraftBaselineRestore.snapshot(original,Collections.singletonList("duration"),false)).get(0).get("sourceAnchors")).isEmpty());
    }
    private Map<String,Object> identity(JsonNode reading){Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("id","sourceDocumentId","fileKey","format","sourceSha256","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(reading.has(key))result.put(key,JsonUtils.mapper().convertValue(reading.get(key),Object.class));return result;}
    private SourceDocument pdfSource(long id)throws Exception{
        byte[] bytes="%PDF-1.4\nbaseline observation roundtrip".getBytes(StandardCharsets.UTF_8);String sha=DraftTemplateRouting.sha(bytes);Path path=directory.resolve(id+".pdf");Files.write(path,bytes);
        SourceDocument source=new SourceDocument();source.setId(id);source.setProjectId("project-"+id);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey("NTT");source.setFileName("source.pdf");source.setStoragePath(path.toString());source.setSourceSha256(sha);source.setParseStatus("PARSED");
        DocumentBlock block=new DocumentBlock();block.setId("page-1");block.setPageNo(1);block.setSource("native_pdf");block.setText("(Guidance Notes: Select the applicable option.)");block.setReadingContext(JsonUtils.mapper().createObjectNode().put("sourceSha256",sha).put("occurrenceId","page-1"));
        ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);coverage.setParsedPages(1);coverage.setComplete(true);DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(block.getText(),Collections.emptyList(),1,false,null,Collections.singletonList(block),coverage,sha,Collections.emptyList());coverage.setTemplateEvidence(OerTemplateEvidence.analyse("NTT",parsed));source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));return source;
    }
    private Map<String,Object> graph(String sourceId,boolean populated){
        Map<String,Object> source=obj("id",sourceId,"fileKey","NTT","format","docx","sourceSha256",SHA,"analysisRevision","v1","analysisInputSha256",SHA,"analysisSha256",ANALYSIS,"evidenceStatus","bound");
        List<Object> variables=new ArrayList<>();Map<String,Object> graph=obj("kind","consense-template-graph-v1","sources",Collections.singletonList(source),"selectedDocuments",obj("NTT",sourceId),"graph",obj("variables",variables,"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>()));if(!populated)return graph;
        Map<String,Object> passage=obj("id","anchor","docId",sourceId,"text","Duration: ____ months.","native",obj("sourceSha256",SHA,"paragraphId","p1","paragraphSha256",SHA,"start",0,"end",8,"expectedText","Duration","channel","editableText"),"nativeBinding",obj("status","bound"));
        variables.add(obj("id","duration","nameEn","Duration","type",obj("kind","number"),"role",obj("kind","input"),"status","confirmed","docIds",Collections.singletonList(sourceId),"sourceAnchors",Collections.singletonList(passage),"editTargets",new ArrayList<>(),"definitionReview",obj("confirmed",true)));
        state(graph).put("requirements",Collections.singletonList(obj("id","req-duration","sourceId",sourceId,"sourceSha256",SHA,"sourceQuote","Duration","kind","input","nodeIds",Collections.singletonList("duration"),"status","pending","sourceQuoteEvidence",obj("anchor",passage),"analysisRevision","v1","analysisInputSha256",SHA,"analysisSha256",ANALYSIS)));return graph;
    }
}
