package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.transaction.PlatformTransactionManager;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise service gates with temporary synthetic sources, without a database or Spring startup. */
class DraftTemplateGraphDependencyTest {
    @TempDir Path temp;
    @Test void saveRejectsAReferencedSourceVerificationBeforePublishingARevision() throws Exception {
        Fixture f=new Fixture(temp);assertSourceCheck(()->f.service.save(f.project,f.graph,null,null,true));assertTrue(f.revisionValues.isEmpty());
    }
    @Test void confirmRejectsAnInvalidHistoricalGraphWithoutChangingItsHead() throws Exception {
        Fixture f=new Fixture(temp);f.historical();assertSourceCheck(()->f.service.confirm(f.project,"historical",null));assertNull(f.head.getCurrentRevisionId());
    }
    @Test void planCannotTurnUnknownVerificationIntoAnExecutableDelete() throws Exception {
        Fixture f=new Fixture(temp);f.historical();assertSourceCheck(()->f.service.plan(f.project,Collections.singletonMap("owner","true")));
    }
    @Test void anInvalidHistoricalGraphRemainsReadableAndCanBeRepaired() throws Exception {
        Fixture f=new Fixture(temp);f.historical();Map<String,Object> current=f.service.current(f.project);assertEquals(2,nodes(map(map(current.get("draft")).get("graph"))).size());
        nodes(f.graph).get(1).put("correction",obj("branches",Collections.singletonList(obj("id","delete","mode","all","clauses",Collections.singletonList(obj("inputId","owner","operator","no","value",""))))));
        Map<String,Object> saved=f.service.save(f.project,f.graph,null,"historical",true);Map<String,Object> confirmed=f.service.confirm(f.project,text(saved.get("id")),null);assertEquals("CONFIRMED",confirmed.get("status"));
        assertEquals("delete",map(list(f.service.plan(f.project,Collections.singletonMap("owner","false")).get("actions")).get(0)).get("action"));
        assertEquals("not_adopted",map(list(f.service.plan(f.project,Collections.singletonMap("owner","true")).get("actions")).get(0)).get("action"));
    }
    @Test void aSplitRelationshipMustBeReviewedBeforeConfirmingOrExecutingItsEdits() throws Exception {
        Fixture f=new Fixture(temp);Map<String,Object> owner=nodes(f.graph).get(1);owner.put("correction",obj("branches",Collections.singletonList(obj("id","delete","mode","all","clauses",Collections.singletonList(obj("inputId","owner","operator","no","value",""))))));owner.put("splitReview",obj("mode","unknown","inputIds",Collections.singletonList("owner")));
        Map<String,Object> saved=f.service.save(f.project,f.graph,null,null,true);assertTrue(assertThrows(BizException.class,()->f.service.confirm(f.project,text(saved.get("id")),null)).getMessage().contains("GRAPH_RULE_REBIND_REQUIRED"));
        assertEquals("pending",map(list(f.service.plan(f.project,Collections.singletonMap("owner","false")).get("actions")).get(0)).get("action"));
        map(owner.get("splitReview")).put("mode","all");Map<String,Object> repaired=f.service.save(f.project,f.graph,null,text(saved.get("id")),true);assertEquals("CONFIRMED",f.service.confirm(f.project,text(repaired.get("id")),null).get("status"));
    }
    private void assertSourceCheck(org.junit.jupiter.api.function.Executable action){assertTrue(assertThrows(BizException.class,action).getMessage().contains("GRAPH_SOURCE_CHECK_NOT_A_VALUE"));}

    private static class Fixture {
        final String project="isolated-dependency-test";
        final Map<String,DraftTemplateGraphRevision> revisionValues=new HashMap<>();
        final DraftTemplateGraphHead head=new DraftTemplateGraphHead();
        final DraftTemplateGraphService service;
        final Map<String,Object> graph;
        Fixture(Path temp) throws Exception {
            ProjectService projects=mock(ProjectService.class);SourceDocumentRepository sources=mock(SourceDocumentRepository.class);DraftTemplateGraphRevisionRepository revisions=mock(DraftTemplateGraphRevisionRepository.class);DraftTemplateGraphHeadRepository heads=mock(DraftTemplateGraphHeadRepository.class);
            DraftGraphInputBindingRepository bindings=mock(DraftGraphInputBindingRepository.class);DraftVariableRepository variables=mock(DraftVariableRepository.class);DraftExtractionRunRepository runs=mock(DraftExtractionRunRepository.class);DraftReviewSources reader=mock(DraftReviewSources.class);EntityManager em=mock(EntityManager.class);
            service=new DraftTemplateGraphService(projects,sources,revisions,heads,bindings,variables,runs,reader,mock(AiGateway.class),mock(DraftExtractionHarness.class),em,mock(PlatformTransactionManager.class));
            Project entity=new Project();entity.setId(project);when(projects.require(project)).thenReturn(entity);when(em.find(eq(Project.class),eq(project),eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(entity);
            head.setProjectId(project);when(heads.findById(project)).thenReturn(Optional.of(head));when(heads.save(any())).thenAnswer(i->i.getArgument(0));when(revisions.save(any())).thenAnswer(i->{DraftTemplateGraphRevision r=i.getArgument(0);revisionValues.put(r.getId(),r);return r;});when(revisions.findByIdAndProjectId(anyString(),eq(project))).thenAnswer(i->Optional.ofNullable(revisionValues.get(i.getArgument(0))));
            Map<String,DraftVariable> values=new HashMap<>();when(variables.findByProjectIdAndVarKey(eq(project),anyString())).thenAnswer(i->Optional.ofNullable(values.get(i.getArgument(1))));when(variables.save(any())).thenAnswer(i->{DraftVariable v=i.getArgument(0);values.put(v.getVarKey(),v);return v;});
            Map<String,DraftGraphInputBinding> inputBindings=new HashMap<>();when(bindings.findById(anyString())).thenAnswer(i->Optional.ofNullable(inputBindings.get(i.getArgument(0))));when(bindings.save(any())).thenAnswer(i->{DraftGraphInputBinding b=i.getArgument(0);inputBindings.put(b.getId(),b);return b;});
            SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId(project);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey("NTT");source.setTextContent("Source passage.");when(sources.findById(1L)).thenReturn(Optional.of(source));when(sources.findByProjectIdAndCategoryOrderByIdAsc(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE)).thenReturn(Collections.singletonList(source));
            when(sources.findDraftReviewSourceByProjectIdAndId(project,1L)).thenReturn(Optional.of(source));when(sources.findDraftReviewSourcesByProjectIdAndCategory(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE)).thenReturn(Collections.singletonList(source));
            ObjectNode identity=JsonUtils.mapper().createObjectNode().put("id","1").put("docId","1").put("sourceDocumentId",1L).put("fileKey","NTT").put("format","docx").put("sourceSha256","synthetic-archive");ArrayNode identities=JsonUtils.mapper().createArrayNode().add(identity);when(reader.sources(anyList())).thenReturn(identities);when(reader.archive(source)).thenReturn(identity);when(reader.archivedBytes(any())).thenReturn(new byte[0]);
            byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Source passage.");word.write(out);bytes=out.toByteArray();}Path original=temp.resolve("synthetic.docx");Files.write(original,bytes);source.setStoragePath(original.toString());StorageService storage=mock(StorageService.class);when(storage.read(original.toRealPath().toString())).thenReturn(bytes);ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(temp.toString());DraftReviewSources.BoundSource bound=new DraftReviewSources(storage,properties).bindSource(source);when(reader.bindSource(source)).thenReturn(bound);
            Map<String,Object> check=node("check","verification"),owner=node("owner","input");owner.put("correction",obj("branches",Collections.singletonList(obj("id","delete","mode","all","clauses",Collections.singletonList(obj("inputId","check","operator","unknown","value",""))))));owner.put("editTargets",Collections.singletonList(obj("id","delete-target","docId","1","text","Source passage.","action","delete","branchId","delete")));
            graph=obj("graph",obj("variables",Arrays.asList(check,owner)),"selectedDocuments",obj("NTT","1"));
        }
        Map<String,Object> node(String id,String role){return obj("id",id,"nameEn",id,"type",obj("kind","boolean"),"role",obj("kind",role),"status","confirmed","docIds",Collections.singletonList("1"),"sourceAnchors",Collections.singletonList(obj("docId","1","text","Source passage.")));}
        void historical(){Map<String,Object> saved=JsonUtils.readMap(JsonUtils.write(graph));saved.put("sources",Collections.singletonList(obj("id","1","docId","1","sourceDocumentId",1L,"fileKey","NTT")));revisionValues.put("historical",new DraftTemplateGraphRevision("historical",project,null,"DRAFT","old-sha",JsonUtils.write(saved),"[]",Instant.now()));head.setDraftRevisionId("historical");}
    }
}
