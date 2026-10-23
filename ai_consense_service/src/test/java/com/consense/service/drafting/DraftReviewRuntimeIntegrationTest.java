package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.*;
import com.consense.document.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static com.consense.service.drafting.DraftReviewGraph.obj;

/** Real synthetic source -> saved rules -> adoption -> three artifacts, without model or renderer calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftReviewRuntimeIntegrationTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:graph_runtime_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");registry.add("consense.storage-root",()->Paths.get("target","graph-runtime-synthetic",DB).toAbsolutePath().toString());}
    @Autowired DraftingService drafting;
    @Autowired DraftTemplateGraphService graphService;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired StorageService storage;
    @Autowired DocumentParser parser;
    @MockBean AiGateway ai;

    SourceDocument template(String project,String key,String suffix)throws Exception{
        byte[] original;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Duration: ____ months.");word.createParagraph().createRun().setText("Keep "+key+" "+suffix+" unchanged.");word.write(out);original=out.toByteArray();}
        String name=key+"-"+suffix+".docx";DocumentParser.ParsedDocument parsed=parser.parseTemplate(key,name,original);
        SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey(key);source.setFileName(name);source.setSourceSha256(DraftPdfConverter.sha256(original));source.setStoragePath(storage.store(project,source.getCategory(),new MockMultipartFile("file",name,"application/octet-stream",original)).getPath());source.setTextContent(parsed.getText());source.setParseStatus(parsed.getParseStatus());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));return sources.save(source);
    }
    VariablePatch adoption(VariableVO field,String value){VariablePatch patch=new VariablePatch();patch.setGraphRevisionId(field.getGraphRevisionId());patch.setFieldDefinitionHash(field.getFieldDefinitionHash());patch.setValue(value);patch.setNote("Synthetic manual project decision");return patch;}
    @Test void selectedOriginalsRulesAdoptionsAndArtifactRevisionGuardsStayOnOneChain()throws Exception{
        Project p=new Project();p.setId("runtime-"+DB);p.setNameEn("Synthetic review runtime");projects.save(p);String project=p.getId();
        Map<String,Object> selected=new LinkedHashMap<>();List<Object> targets=new ArrayList<>(),anchors=new ArrayList<>();List<String> docs=new ArrayList<>();
        SourceDocument oldScc=template(project,"SCC","old-edition");
        for(String key:Arrays.asList("NTT","SCT","SCC")){SourceDocument source=template(project,key,"selected-edition");String id=source.getId().toString();selected.put(key,id);docs.add(id);anchors.add(obj("docId",id,"text","Duration: ____ months."));targets.add(obj("id","duration-"+key,"docId",id,"text","____","action","fill","valueSourceId","duration"));}
        Map<String,Object> node=obj("id","duration","nameZh","工期","nameEn","Duration","type",obj("kind","number"),"role",obj("kind","input"),"status","confirmed","docIds",docs,"sourceAnchors",anchors,"editTargets",targets,"options",new ArrayList<>());
        Map<String,Object> graph=obj("kind","consense-template-graph-v1","graph",obj("variables",Collections.singletonList(node),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",2,"blank",false,"guidanceMode","recognized"),"selectedDocuments",selected);
        node.put("status","pending");String draft=String.valueOf(graphService.save(project,graph,null).get("id"));
        assertEquals(draft,drafting.catalog(project).get("graphRevisionId"));assertTrue(drafting.listVariables(project).stream().anyMatch(v->"duration".equals(v.getKey())));
        List<DraftDocumentVO> partial=drafting.generate(project,"en");assertEquals(3,partial.size());assertNull(graphService.current(project).get("currentRevisionId"));
        for(DraftDocumentVO document:partial){byte[] output=drafting.exportWord(project,document.getFileKey(),document.getRevisionId());DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(output);
            assertTrue(index.getMainParagraphs().get(0).getText().contains(DraftIncompleteNotice.TITLE));assertEquals("Duration: ____ months.",index.getMainParagraphs().get(1).getText());assertTrue(document.getContent().contains("未处理清单"));assertFalse(document.isStale());}
        assertFalse(graphService.variables(project).stream().filter(v->"duration".equals(v.getKey())).findFirst().get().isConfirmed());
        node.put("status","confirmed");draft=String.valueOf(graphService.save(project,graph,null,draft,true).get("id"));graphService.confirm(project,draft,null);
        VariableVO input=graphService.variables(project).stream().filter(v->"duration".equals(v.getKey())).findFirst().get();graphService.updateVariable(project,"duration",adoption(input,"42"));
        assertTrue(((List<?>)drafting.plan(project,null).get("unresolved")).stream().anyMatch(raw->"InputMissing".equals(DraftReviewGraph.map(raw).get("kind"))));
        List<DraftDocumentVO> generated=drafting.generate(project,"en");assertEquals(3,generated.size());assertEquals(1,generated.stream().map(DraftDocumentVO::getSnapshotId).distinct().count());
        for(DraftDocumentVO document:generated){byte[] output=drafting.exportWord(project,document.getFileKey(),document.getRevisionId());DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(output);assertTrue(index.getMainParagraphs().get(0).getText().contains(DraftIncompleteNotice.TITLE));assertEquals("Duration: 42 months.",index.getMainParagraphs().get(1).getText());assertEquals("Keep "+document.getFileKey()+" selected-edition unchanged.",index.getMainParagraphs().get(2).getText());assertEquals(document.getDocxSha256(),DraftPdfConverter.sha256(output));assertFalse(document.isStale());}
        assertNotEquals(oldScc.getSourceSha256(),generated.get(2).getSourceSha256());
        DraftDocumentVO before=generated.get(0);Map<String,Object> block=before.getBlocks().get(1);BlockPatch blockPatch=new BlockPatch();blockPatch.setId(String.valueOf(block.get("id")));blockPatch.setExpectedTextHash(String.valueOf(block.get("textHash")));blockPatch.setText("Human reviewed continuation.");DocumentPatch body=new DocumentPatch();body.setRevisionId(before.getRevisionId());body.setDocxSha256(before.getDocxSha256());body.setBlocks(Collections.singletonList(blockPatch));
        DraftDocumentVO saved=drafting.updateDocument(project,"NTT",body);assertNotEquals(before.getRevisionId(),saved.getRevisionId());assertEquals(before.getSnapshotId(),saved.getSnapshotId());assertTrue(saved.getContent().contains("Human reviewed continuation."));assertThrows(BizException.class,()->drafting.updateDocument(project,"NTT",body));
        graphService.updateVariable(project,"duration",adoption(graphService.variables(project).get(0),"50"));assertTrue(drafting.listDocuments(project).stream().allMatch(DraftDocumentVO::isStale));assertThrows(BizException.class,()->drafting.exportWord(project,"NTT",saved.getRevisionId()));
        List<DraftDocumentVO> regenerated=drafting.generate(project,"en");assertTrue(regenerated.get(0).getContent().contains("Duration: 50 months."));verifyNoInteractions(ai);
    }
}
