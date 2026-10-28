package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import java.time.Instant;
import java.util.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exact snapshot identity and bounded repository reads; no application, original file or model is used. */
class DraftSnapshotBulkReadTest {
    static final String PROJECT="snapshot-bulk-test", REVISION="frozen-graph";
    static Map<String,Object> map(Object... entries){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put(String.valueOf(entries[i]),entries[i+1]);return result;}
    static String hash(Object value){return DraftAdoption.hash(JsonUtils.write(value));}

    static final class Fixture {
        final DraftVariableRepository variables=mock(DraftVariableRepository.class);
        final DraftGraphInputBindingRepository bindings=mock(DraftGraphInputBindingRepository.class);
        final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        final DraftTemplateGraphRevisionRepository revisions=mock(DraftTemplateGraphRevisionRepository.class);
        final DraftTemplateGraphHeadRepository heads=mock(DraftTemplateGraphHeadRepository.class);
        final DraftReviewSources reader=mock(DraftReviewSources.class);
        final Map<String,DraftVariable> values=new LinkedHashMap<>();
        final Map<String,DraftGraphInputBinding> definitions=new LinkedHashMap<>();
        final SourceDocument original=new SourceDocument(), correspondence=new SourceDocument();
        final List<String> order=new ArrayList<>(Arrays.asList("z","a","missing-variable","missing-binding"));
        String originalSha="original-v1", correspondenceSha="correspondence-v1", scope="initial-scope";
        boolean reverseRepositoryOrder;
        final DraftTemplateGraphService service;

        Fixture(){
            original.setId(1L);original.setProjectId(PROJECT);original.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
            correspondence.setId(2L);correspondence.setProjectId(PROJECT);correspondence.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);correspondence.setTextContent("Frozen correspondence");
            value("z","11",true,false,"[]");value("a","",true,null,null);value("missing-binding","retained",false,true,"[]");
            value("hidden-history","not a current field",true,false,"[]");value("targetOverrides","[]",true,false,"[]");
            binding("z","adopted-z");binding("a","adopted-a");binding("missing-variable","adopted-missing-variable");binding("hidden-history","irrelevant");
            DraftGraphInputBinding decoy=new DraftGraphInputBinding();decoy.setId("wrong-computed-id");decoy.setProjectId(PROJECT);decoy.setVarKey("missing-binding");decoy.setAdoptedDefinitionSha256("must-not-be-used");definitions.put(decoy.getId(),decoy);
            DraftTemplateGraphHead head=new DraftTemplateGraphHead();head.setProjectId(PROJECT);head.setCurrentRevisionId(REVISION);
            when(heads.findById(PROJECT)).thenReturn(Optional.of(head));
            when(revisions.findByIdAndProjectId(REVISION,PROJECT)).thenAnswer(inv->Optional.of(revision()));
            when(variables.findByProjectIdAndVarKey(eq(PROJECT),anyString())).thenAnswer(inv->Optional.ofNullable(values.get(inv.getArgument(1))));
            when(bindings.findById(anyString())).thenAnswer(inv->Optional.ofNullable(definitions.get(inv.getArgument(0))));
            when(variables.findByProjectIdOrderBySortOrderAsc(PROJECT)).thenAnswer(inv->{List<DraftVariable> rows=new ArrayList<>(values.values());if(reverseRepositoryOrder)Collections.reverse(rows);return rows;});
            when(bindings.findByProjectId(PROJECT)).thenAnswer(inv->{List<DraftGraphInputBinding> rows=new ArrayList<>(definitions.values());if(reverseRepositoryOrder)Collections.reverse(rows);return rows;});
            when(sources.findDraftReviewSourceByProjectIdAndId(PROJECT,1L)).thenReturn(Optional.of(original));
            when(sources.findByProjectIdAndCategoryOrderByIdAsc(PROJECT,SourceDocument.CATEGORY_PROJECT_INPUT)).thenReturn(Collections.singletonList(correspondence));
            when(reader.archive(original)).thenAnswer(inv->JsonUtils.mapper().valueToTree(map("sourceDocumentId",1L,"sourceSha256",originalSha)));
            when(reader.archive(correspondence)).thenAnswer(inv->JsonUtils.mapper().valueToTree(map("sourceDocumentId",2L,"sourceSha256",correspondenceSha)));
            service=new DraftTemplateGraphService(mock(ProjectService.class),sources,revisions,heads,bindings,variables,mock(DraftExtractionRunRepository.class),reader,mock(AiGateway.class),mock(DraftExtractionHarness.class),mock(EntityManager.class),mock(PlatformTransactionManager.class));
        }
        void value(String key,String text,Boolean confirmed,Boolean review,String adoptedSources){DraftVariable value=new DraftVariable();value.setProjectId(PROJECT);value.setVarKey(key);value.setValueText(text);value.setConfirmed(confirmed);value.setReviewRequired(review);value.setAdoptedSourcesJson(adoptedSources);values.put(key,value);}
        void binding(String key,String adopted){DraftGraphInputBinding binding=new DraftGraphInputBinding();binding.setId(DraftAdoption.hash(PROJECT+"\n"+key));binding.setProjectId(PROJECT);binding.setVarKey(key);binding.setDefinitionSha256("current-"+key);binding.setAdoptedDefinitionSha256(adopted);definitions.put(binding.getId(),binding);}
        Map<String,Object> graph(){List<Object> nodes=new ArrayList<>();for(String key:order)nodes.add(map("id",key,"nameZh",key,"nameEn",key,"definitionScope",scope,"status","confirmed","role",map("kind","input"),"type",map("kind","text"),"optional",true,"catalogAlignment",map("status","new","confirmed",true,"reason","Human-reviewed synthetic manual input"),"docIds",Collections.emptyList(),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList()));return map("kind","consense-template-graph-v1","graph",map("variables",nodes),"sources",Collections.emptyList(),"selectedDocuments",Collections.emptyMap());}
        DraftTemplateGraphRevision revision(){String json=JsonUtils.write(graph());return new DraftTemplateGraphRevision(REVISION,PROJECT,null,"CONFIRMED",DraftAdoption.hash(json),json,JsonUtils.write(Collections.singletonList(map("sourceDocumentId",1L,"sourceSha256","original-v1"))),Instant.EPOCH);}
        String expected(Map<String,String> patches){
            Map<String,List<Object>> tuples=new HashMap<>();
            tuples.put("z",Arrays.asList("z",values.get("z").getValueText(),values.get("z").getConfirmed(),values.get("z").getReviewRequired(),definitions.get(DraftAdoption.hash(PROJECT+"\nz")).getAdoptedDefinitionSha256(),values.get("z").getAdoptedSourcesJson()));
            tuples.put("a",Arrays.asList("a",values.get("a").getValueText(),values.get("a").getConfirmed(),values.get("a").getReviewRequired(),definitions.get(DraftAdoption.hash(PROJECT+"\na")).getAdoptedDefinitionSha256(),values.get("a").getAdoptedSourcesJson()));
            tuples.put("missing-variable",Arrays.asList("missing-variable",null,null,null,"adopted-missing-variable",null));
            tuples.put("missing-binding",Arrays.asList("missing-binding","retained",false,true,null,"[]"));
            List<Object> inputs=new ArrayList<>();for(String key:order)inputs.add(tuples.get(key));
            String evidence=hash(Collections.singletonList(Arrays.asList(2L,correspondenceSha,DraftAdoption.sourceHash(correspondence))));
            return hash(map("graphRevisionId",REVISION,"graphSha256",revision().getGraphSha256(),"sourceCurrent","original-v1".equals(originalSha),"evidenceRevision",evidence,"inputs",inputs,"preview",patches));
        }
    }

    @Test void exactLegacyHashUsesTwoProjectBulkReadsInsteadOfPerFieldQueries(){
        Fixture f=new Fixture();assertEquals(f.expected(null),f.service.snapshotId(PROJECT,null));
        verify(f.variables,times(1)).findByProjectIdOrderBySortOrderAsc(PROJECT);verify(f.bindings,times(1)).findByProjectId(PROJECT);
        verify(f.variables,never()).findByProjectIdAndVarKey(anyString(),anyString());verify(f.bindings,never()).findById(anyString());
    }
    @Test void missingRowsAndWrongBindingIdsRetainNullInsteadOfBorrowingHistory(){Fixture f=new Fixture();assertEquals(f.expected(null),f.service.snapshotId(PROJECT,null));}
    @Test void repositoryOrderAndUnselectedHistoryNeverReorderOrExpandSnapshot(){
        Fixture f=new Fixture();String before=f.service.snapshotId(PROJECT,null);f.reverseRepositoryOrder=true;f.values.get("hidden-history").setValueText("Changed hidden history");f.values.get("targetOverrides").setValueText("Not selected by this graph");assertEquals(before,f.service.snapshotId(PROJECT,null));assertEquals(f.expected(null),before);
    }
    @Test void exactGraphFieldOrderAndPreviewPatchesStillBelongToTheHash(){
        Fixture f=new Fixture();String before=f.service.snapshotId(PROJECT,null);Collections.reverse(f.order);Map<String,String> patch=new LinkedHashMap<>();patch.put("z","12");assertEquals(f.expected(patch),f.service.snapshotId(PROJECT,patch));assertNotEquals(before,f.service.snapshotId(PROJECT,null));assertNotEquals(f.service.snapshotId(PROJECT,null),f.service.snapshotId(PROJECT,patch));
    }
    @Test void valueAdoptionFlagsSourcesAndDefinitionChangesInvalidateWithoutAnyCache(){
        Fixture f=new Fixture();String previous=f.service.snapshotId(PROJECT,null);f.values.get("z").setValueText("12");String next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);assertEquals(f.expected(null),next);previous=next;
        f.values.get("z").setConfirmed(false);next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);previous=next;
        f.values.get("z").setReviewRequired(true);next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);previous=next;
        f.definitions.get(DraftAdoption.hash(PROJECT+"\nz")).setAdoptedDefinitionSha256("new-adopted-definition");next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);previous=next;
        f.values.get("z").setAdoptedSourcesJson("[{\"source\":\"new explicit provenance\"}]");next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);previous=next;
        f.scope="changed-field-definition";next=f.service.snapshotId(PROJECT,null);assertNotEquals(previous,next);assertEquals(f.expected(null),next);
    }
    @Test void originalAndCorrespondenceChangesAreRecheckedOnEverySnapshot(){
        Fixture f=new Fixture();String before=f.service.snapshotId(PROJECT,null);f.originalSha="original-v2";String stale=f.service.snapshotId(PROJECT,null);assertNotEquals(before,stale);assertEquals(f.expected(null),stale);f.correspondenceSha="correspondence-v2";String changedEvidence=f.service.snapshotId(PROJECT,null);assertNotEquals(stale,changedEvidence);assertEquals(f.expected(null),changedEvidence);verify(f.reader,times(3)).archive(f.original);verify(f.reader,times(3)).archive(f.correspondence);
    }
    @Test void savedPlanReusesOneCurrentSourceAndEvidenceReadForBothSnapshotIds(){
        Fixture f=new Fixture();Map<String,Object> plan=f.service.plan(PROJECT,null);
        assertEquals(f.expected(null),plan.get("snapshotId"));assertEquals(plan.get("snapshotId"),plan.get("savedSnapshotId"));
        verify(f.reader,times(1)).archive(f.original);verify(f.reader,times(1)).archivedBytes(any());verify(f.reader,times(1)).archive(f.correspondence);
    }
    @Test void previewPlanRetainsDistinctSavedHashWithoutRescanningOriginals(){
        Fixture f=new Fixture();Map<String,String> patch=Collections.singletonMap("z","12");Map<String,Object> plan=f.service.plan(PROJECT,patch);
        assertEquals(f.expected(patch),plan.get("snapshotId"));assertEquals(f.expected(null),plan.get("savedSnapshotId"));assertNotEquals(plan.get("snapshotId"),plan.get("savedSnapshotId"));
        verify(f.reader,times(1)).archive(f.original);verify(f.reader,times(1)).archive(f.correspondence);
    }
    @Test void planRequestReuseNeverCarriesOriginalOrCorrespondenceIdentityIntoTheNextRequest(){
        Fixture f=new Fixture();Map<String,Object> first=f.service.plan(PROJECT,null);f.originalSha="original-v2";Map<String,Object> stale=f.service.plan(PROJECT,null);
        assertNotEquals(first.get("snapshotId"),stale.get("snapshotId"));assertTrue(((List<?>)stale.get("unresolved")).stream().anyMatch(raw->"graph_source_changed".equals(((Map<?,?>)raw).get("id"))));
        f.correspondenceSha="correspondence-v2";Map<String,Object> changed=f.service.plan(PROJECT,null);assertNotEquals(stale.get("evidenceRevision"),changed.get("evidenceRevision"));assertNotEquals(stale.get("snapshotId"),changed.get("snapshotId"));
        verify(f.reader,times(3)).archive(f.original);verify(f.reader,times(3)).archive(f.correspondence);
    }
}
