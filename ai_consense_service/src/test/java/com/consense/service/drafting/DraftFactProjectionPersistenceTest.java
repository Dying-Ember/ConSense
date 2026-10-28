package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftFactProjectionPersistenceTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:projection_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");registry.add("consense.storage-root",()->Paths.get("target","projection-test",DB).toAbsolutePath().toString());}
    @Autowired DraftTemplateGraphService service;@Autowired ProjectRepository projects;@Autowired SourceDocumentRepository sources;@Autowired StorageService storage;@MockBean AiGateway ai;
    @Test void savedPreviousRuleAndTargetCanBeRevertedAfterReload() throws Exception {
        Project project=new Project();project.setId("projection-"+UUID.randomUUID());project.setNameZhHans("Synthetic");projects.save(project);
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Alternative A");word.write(output);bytes=output.toByteArray();}
        SourceDocument source=new SourceDocument();source.setProjectId(project.getId());source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey("NTT");source.setFileName("test.docx");source.setParseStatus("PARSED");source.setTextContent("Alternative A");source.setSourceSha256(DraftTemplateRouting.sha(bytes));source.setStoragePath(storage.store(project.getId(),source.getCategory(),new MockMultipartFile("file","test.docx","application/octet-stream",bytes)).getPath());source=sources.save(source);
        String sourceId=source.getId().toString();Map<String,Object> graph=new DraftFactProjectionHistoryTest().fixture(true),local=nodes(graph).get(2);local.put("docIds",Collections.singletonList(sourceId));map(list(local.get("editTargets")).get(0)).put("docId",sourceId);graph.put("selectedDocuments",obj("NTT",sourceId));graph.remove("sources");
        Map<String,Object> projection=JsonUtils.readMap(JsonUtils.write(local.get("factProjectionReview"))),old=map(projection.get("original"));local.put("role",old.get("role"));local.put("correction",old.get("correction"));map(list(local.get("editTargets")).get(0)).put("needsRebind",false);local.remove("factProjectionReview");
        String initial=String.valueOf(service.save(project.getId(),graph,null).get("id"));Map<String,Object> converting=(Map<String,Object>)service.revision(project.getId(),initial).get("graph"),convertedNode=nodes(converting).get(2);convertedNode.put("role",obj("kind","derived"));convertedNode.put("correction",projection.get("convertedCorrection"));convertedNode.put("factProjectionReview",projection);map(list(convertedNode.get("editTargets")).get(0)).put("needsRebind",true);
        Map<String,Object> saved=service.save(project.getId(),converting,null,initial,true);String draft=String.valueOf(saved.get("id"));Map<String,Object> loaded=(Map<String,Object>)service.revision(project.getId(),draft).get("graph");local=nodes(loaded).get(2);Map<String,Object> review=map(local.get("factProjectionReview")),original=map(review.get("original"));
        assertTrue(ruleNeedsReview(local));assertEquals("old-case",map(list(original.get("targetBindings")).get(0)).get("branchId"));assertEquals("extra",map(list(map(list(map(original.get("correction")).get("branches")).get(0)).get("clauses")).get(1)).get("inputId"));
        local.put("role",original.get("role"));local.put("correction",original.get("correction"));Map<String,Object> target=map(list(local.get("editTargets")).get(0));target.put("branchId",map(list(original.get("targetBindings")).get(0)).get("branchId"));target.put("needsRebind",true);local.put("supersededFactProjectionReview",JsonUtils.readMap(JsonUtils.write(review)));local.remove("factProjectionReview");
        String reverted=String.valueOf(service.save(project.getId(),loaded,null,draft,true).get("id"));Map<String,Object> after=(Map<String,Object>)service.revision(project.getId(),reverted).get("graph"),restored=nodes(after).get(2);
        assertEquals(JsonUtils.write(original.get("correction")),JsonUtils.write(restored.get("correction")));assertEquals("old-case",map(list(restored.get("editTargets")).get(0)).get("branchId"));assertEquals(true,map(list(restored.get("editTargets")).get(0)).get("needsRebind"));assertTrue(restored.containsKey("supersededFactProjectionReview"));
    }
}
