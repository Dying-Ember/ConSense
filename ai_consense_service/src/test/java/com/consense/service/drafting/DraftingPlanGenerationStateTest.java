package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.ocr.OcrClient;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Actual service plan/generate seam with repository doubles; no business DB or model call. */
class DraftingPlanGenerationStateTest {
    static final String PROJECT="isolated-plan-generation-state";
    static class Fixture {
        final Map<String,DraftVariable> variables=new LinkedHashMap<>();
        final Map<String,SourceDocument> templates=new LinkedHashMap<>();
        final List<SourceDocument> correspondence=new ArrayList<>();
        final Map<String,byte[]> files=new LinkedHashMap<>();
        final Map<String,DraftDocument> documents=new LinkedHashMap<>();
        final Map<String,DraftArtifact> artifacts=new LinkedHashMap<>();
        final DraftDocumentRepository docs=mock(DraftDocumentRepository.class);
        final DraftArtifactRepository revisions=mock(DraftArtifactRepository.class);
        final DraftPdfArtifactRepository pdfs=mock(DraftPdfArtifactRepository.class);
        final DraftVariableRepository inputs=mock(DraftVariableRepository.class);
        final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        final AiGateway model=mock(AiGateway.class);
        final ConsenseProperties props=new ConsenseProperties();
        final DocumentParser parser=new DocumentParser(props,mock(OcrClient.class));
        final StorageService storage=mock(StorageService.class);
        final DraftingService service;
        Fixture() {
            ProjectService projects=mock(ProjectService.class);Project p=new Project();p.setId(PROJECT);when(projects.require(PROJECT)).thenReturn(p);
            when(inputs.findByProjectIdAndVarKey(eq(PROJECT),anyString())).thenAnswer(i->Optional.ofNullable(variables.get(i.getArgument(1))));
            when(inputs.findByProjectIdOrderBySortOrderAsc(PROJECT)).thenAnswer(i->new ArrayList<>(variables.values()));
            when(inputs.save(any(DraftVariable.class))).thenAnswer(i->{DraftVariable v=i.getArgument(0);variables.put(v.getVarKey(),v);return v;});
            when(sources.findFirstByProjectIdAndFileKeyAndCategory(eq(PROJECT),anyString(),eq(SourceDocument.CATEGORY_STANDARD_TEMPLATE))).thenAnswer(i->Optional.ofNullable(templates.get(i.getArgument(1))));
            when(sources.findByProjectIdAndCategoryOrderByIdAsc(eq(PROJECT),anyString())).thenAnswer(i->SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(i.getArgument(1))?new ArrayList<>(templates.values()):new ArrayList<>(correspondence));
            when(storage.read(anyString())).thenAnswer(i->files.get(i.getArgument(0)).clone());
            when(docs.findByProjectIdAndFileKey(eq(PROJECT),anyString())).thenAnswer(i->Optional.ofNullable(documents.get(i.getArgument(1))));
            when(docs.findByProjectIdOrderByIdAsc(PROJECT)).thenAnswer(i->new ArrayList<>(documents.values()));
            when(docs.save(any(DraftDocument.class))).thenAnswer(i->{DraftDocument d=i.getArgument(0);documents.put(d.getFileKey(),d);return d;});
            when(revisions.findById(anyString())).thenAnswer(i->Optional.ofNullable(artifacts.get(i.getArgument(0))));
            when(revisions.save(any(DraftArtifact.class))).thenAnswer(i->{DraftArtifact a=i.getArgument(0);artifacts.put(a.getId(),a);return a;});
            service=new DraftingService(projects,storage,parser,model,sources,inputs,docs,revisions,pdfs,props,null,null,null,null,null,null);
        }
        void value(String key,Object value) {DraftVariable v=new DraftVariable();v.setProjectId(PROJECT);v.setScope("INPUT");v.setVarKey(key);v.setConfirmed(true);v.setValueText(value instanceof String?(String)value:JsonUtils.write(value));variables.put(key,v);}
        void source(String key,byte[] bytes) {
            DocumentParser.ParsedDocument parsed=parser.parse(key+".docx",bytes);SourceDocument s=new SourceDocument();s.setId((long)templates.size()+1);s.setProjectId(PROJECT);s.setFileKey(key);s.setFileName(key+".docx");s.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);s.setStoragePath(key);s.setParseStatus("PARSED");s.setTextContent(parsed.getText());s.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));templates.put(key,s);files.put(key,bytes.clone());
        }
        Map<String,Object> seedFrozen()throws Exception {
            byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Untouched source condition.");word.write(out);bytes=out.toByteArray();}
            for(String key:DraftBlueprint.draftFileKeys())source(key,bytes);
            value("foundationIncluded",false);service.listVariables(PROJECT);
            Map<String,Object> frozen=service.plan(PROJECT,null);
            Map<String,Object> target=action(frozen,"scc-excision");target.put("action","not_adopted");target.put("decisionAction","not_adopted");target.put("application","applied");target.put("applicationState","applied");
            String plan=JsonUtils.write(frozen);
            for(String key:DraftBlueprint.draftFileKeys()) {
                DraftDocument d=new DraftDocument();d.setProjectId(PROJECT);d.setFileKey(key);d.setGenerated(true);d.setContentEdited(false);d.setSnapshotId((String)frozen.get("snapshotId"));d.setRuleVersion((String)frozen.get("ruleVersion"));d.setRevisionId("fixture-"+key);d.setInputSnapshotJson(plan);documents.put(key,d);
                DraftArtifact a=new DraftArtifact();a.setId(d.getRevisionId());a.setProjectId(PROJECT);a.setFileKey(key);a.setSnapshotId(d.getSnapshotId());a.setSourceSha256(DraftPdfConverter.sha256(bytes));a.setDocxBytes(bytes);a.setDocxSha256(DraftPdfConverter.sha256(bytes));artifacts.put(a.getId(),a);
            }
            return frozen;
        }
    }
    static Map<String,Object> action(Map<String,Object> p,String id) {return DraftBusinessRules.list(p.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow(AssertionError::new);}
    static Map<String,Long> states(Map<String,Object> p,String key) {Map<String,Long> r=new LinkedHashMap<>();for(Object raw:DraftBusinessRules.list(p.get("actions"))){Map<String,Object>a=DraftBusinessRules.asMap(raw);if(key.equals(a.get("document")))r.merge(String.valueOf(a.get("applicationState")),1L,Long::sum);}return r;}
    static void fresh(Fixture f) {assertNotEquals("applied",action(f.service.plan(PROJECT,null),"scc-excision").get("applicationState"));verifyNoInteractions(f.model);}

    @Test void actualThreeOriginalGenerationAndImmediatePlanHaveTheSameSourceApplicationStates()throws Exception {
        String dir=System.getProperty("consense.audit.planCaptureDir");assumeTrue(dir!=null,"Explicit preserved real-original capture required.");Path capture=Paths.get(dir);
        Fixture f=new Fixture();Map<String,Object> values=JsonUtils.readMap(new String(Files.readAllBytes(capture.resolve("confirmed-values.json")),"UTF-8"));values.forEach(f::value);
        for(String key:DraftBlueprint.draftFileKeys())f.source(key,Files.readAllBytes(capture.resolve(key+"-source.docx")));
        f.service.listVariables(PROJECT);Map<String,Object> before=f.service.plan(PROJECT,null);
        String inputsBefore=JsonUtils.write(f.variables.values());List<DraftDocumentVO> generated=f.service.generate(PROJECT,"en");assertEquals(3,generated.size());
        Map<String,Object> saved=JsonUtils.readMap(f.documents.get("SCC").getInputSnapshotJson());Map<String,Object> read=f.service.plan(PROJECT,null);
        Map<String,Object> report=DraftBusinessRules.map("savedStates",states(saved,"SCC"),"planStates",states(read,"SCC"),"beforeStates",states(before,"SCC"),"applicationStateSource",read.get("applicationStateSource"),"revisions",new ArrayList<>(f.artifacts.keySet()),"newModelCalls",0,"businessDatabaseWrites",0);
        String out=System.getProperty("consense.audit.planResult");if(out!=null)Files.write(Paths.get(out),JsonUtils.write(report).getBytes("UTF-8"));
        assertEquals(inputsBefore,JsonUtils.write(f.variables.values()));verifyNoInteractions(f.model);
        assertEquals(states(saved,"SCC"),states(read,"SCC"),"Plan must represent the current saved source compile, not a lossy flat-text reevaluation.");
        for(String key:DraftBlueprint.draftFileKeys())assertEquals(states(saved,key),states(read,key));
        assertEquals(DraftBusinessRules.list(saved.get("unresolved")),DraftBusinessRules.list(read.get("unresolved")),"Independent pending issues must survive.");
    }
    @Test void matchingCurrentRevisionsExposeTheirFrozenApplicationState()throws Exception {Fixture f=new Fixture();f.seedFrozen();assertEquals("applied",action(f.service.plan(PROJECT,null),"scc-excision").get("applicationState"));verifyNoInteractions(f.model);}
    @Test void listingDraftsWithoutCachedPdfsChecksExistenceWithoutLoadingPdfArtifacts()throws Exception {
        Fixture f=new Fixture();f.seedFrozen();DraftingService service=spy(f.service);
        f.props.getDrafting().setRendererExecutable("missing-cache-must-not-probe-renderer");f.props.getDrafting().setRendererWorkRoot("missing-cache-must-not-create-renderer-work");
        List<DraftDocumentVO> listed=service.listDocuments(PROJECT);assertEquals(3,listed.size());
        for(DraftDocumentVO document:listed) {
            assertTrue(document.isGenerated());assertEquals("fixture-"+document.getFileKey(),document.getRevisionId());assertNull(document.getPdfSha256());assertNull(document.getRenderProfileHash());
        }
        verify(service,never()).currentConfiguredPdfProfileHash();
        String hash=f.artifacts.get("fixture-NTT").getDocxSha256();verify(f.pdfs,times(3)).existsByDocxSha256(hash);verifyNoMoreInteractions(f.pdfs);verifyNoInteractions(f.model);
    }
    @Test void listingDraftsWithCachedPdfsReadsOnlyCurrentProfileMetadata()throws Exception {
        Fixture f=new Fixture();f.seedFrozen();DraftingService service=spy(f.service);
        String hash=f.artifacts.get("fixture-NTT").getDocxSha256(),profile="assessed-current-renderer-profile",pdfHash="saved-current-pdf-hash",cacheId=DraftAdoption.hash(hash+"|"+profile);
        when(f.pdfs.existsByDocxSha256(hash)).thenReturn(true);
        doReturn(profile).when(service).currentConfiguredPdfProfileHash();
        DraftPdfArtifactRepository.Metadata metadata=mock(DraftPdfArtifactRepository.Metadata.class);
        when(metadata.getDocxSha256()).thenReturn(hash);when(metadata.getPdfSha256()).thenReturn(pdfHash);when(metadata.getRenderProfileHash()).thenReturn(profile);
        when(f.pdfs.findMetadataById(cacheId)).thenReturn(Optional.of(metadata));
        when(f.pdfs.findById(anyString())).thenThrow(new AssertionError("Draft listing must not hydrate PDF or manifest LOBs"));
        List<DraftDocumentVO> listed=service.listDocuments(PROJECT);assertEquals(3,listed.size());
        for(DraftDocumentVO document:listed){assertTrue(document.isGenerated());assertEquals("fixture-"+document.getFileKey(),document.getRevisionId());assertEquals(hash,document.getDocxSha256());assertEquals(pdfHash,document.getPdfSha256());assertEquals(profile,document.getRenderProfileHash());}
        verify(service,times(1)).currentConfiguredPdfProfileHash();verify(f.pdfs,times(3)).existsByDocxSha256(hash);
        verify(f.pdfs,times(3)).findMetadataById(cacheId);verifyNoMoreInteractions(f.pdfs);verifyNoInteractions(f.model);
    }
    @Test void listingDraftsRejectsMismatchedPdfMetadataIdentity()throws Exception {
        for(String changed:Arrays.asList("docx","profile")) {
            Fixture f=new Fixture();f.seedFrozen();DraftingService service=spy(f.service);
            String hash=f.artifacts.get("fixture-NTT").getDocxSha256(),profile="assessed-current-renderer-profile",cacheId=DraftAdoption.hash(hash+"|"+profile);
            when(f.pdfs.existsByDocxSha256(hash)).thenReturn(true);
            doReturn(profile).when(service).currentConfiguredPdfProfileHash();
            DraftPdfArtifactRepository.Metadata metadata=mock(DraftPdfArtifactRepository.Metadata.class);
            when(metadata.getDocxSha256()).thenReturn("docx".equals(changed)?"different-source":hash);when(metadata.getRenderProfileHash()).thenReturn("profile".equals(changed)?"different-renderer-profile":profile);
            when(f.pdfs.findMetadataById(cacheId)).thenReturn(Optional.of(metadata));
            BizException failure=assertThrows(BizException.class,()->service.listDocuments(PROJECT));assertEquals(4013,failure.getCode());assertEquals("BINDING_PDF_IDENTITY_MISMATCH",failure.getMessage());
            verify(f.pdfs).existsByDocxSha256(hash);verify(f.pdfs).findMetadataById(cacheId);verifyNoMoreInteractions(f.pdfs);verifyNoInteractions(f.model);
        }
    }
    @Test void threeDistinctDraftPdfsShareOneLiveRendererAssessmentWithoutCachingTheNextRequest()throws Exception {
        Fixture f=new Fixture();f.seedFrozen();DraftingService service=spy(f.service);
        f.props.getDrafting().setRendererExecutable("authored-renderer-must-not-run");f.props.getDrafting().setRendererWorkRoot("authored-work-must-not-create");
        String profile="first-live-profile",changedProfile="next-live-profile";doReturn(profile,changedProfile).when(service).currentConfiguredPdfProfileHash();
        for(String key:DraftBlueprint.draftFileKeys()) {
            DraftArtifact artifact=f.artifacts.get("fixture-"+key);byte[] original=artifact.getDocxBytes(),distinct=Arrays.copyOf(original,original.length+key.length());
            System.arraycopy(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),0,distinct,original.length,key.length());
            artifact.setDocxBytes(distinct);String hash=DraftPdfConverter.sha256(distinct);artifact.setDocxSha256(hash);
            when(f.pdfs.existsByDocxSha256(hash)).thenReturn(true);
            DraftPdfArtifactRepository.Metadata metadata=mock(DraftPdfArtifactRepository.Metadata.class);
            when(metadata.getDocxSha256()).thenReturn(hash);when(metadata.getRenderProfileHash()).thenReturn(profile);when(metadata.getPdfSha256()).thenReturn("saved-pdf-"+key);
            when(f.pdfs.findMetadataById(DraftAdoption.hash(hash+"|"+profile))).thenReturn(Optional.of(metadata));
        }
        List<DraftDocumentVO> first=service.listDocuments(PROJECT);
        assertEquals(3,first.size());assertEquals(3,first.stream().map(DraftDocumentVO::getDocxSha256).distinct().count());
        for(DraftDocumentVO document:first){assertEquals(profile,document.getRenderProfileHash());assertEquals("saved-pdf-"+document.getFileKey(),document.getPdfSha256());}
        verify(service,times(1)).currentConfiguredPdfProfileHash();
        for(DraftDocumentVO document:service.listDocuments(PROJECT))assertNull(document.getPdfSha256(),"A new request must check the changed live profile, not reuse the previous lookup");
        verify(service,times(2)).currentConfiguredPdfProfileHash();
        for(DraftArtifact artifact:f.artifacts.values()) {
            verify(f.pdfs).findMetadataById(DraftAdoption.hash(artifact.getDocxSha256()+"|"+profile));
            verify(f.pdfs).findMetadataById(DraftAdoption.hash(artifact.getDocxSha256()+"|"+changedProfile));
        }
        verify(f.pdfs,never()).findById(anyString());verifyNoInteractions(f.model);
    }
    @Test void changedAdoptedInputsRejectTheOldGenerationState()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.value("foundationIncluded",true);fresh(f);}
    @Test void changedCorrespondenceCannotBorrowTheOldSourceCompile()throws Exception {Fixture f=new Fixture();f.seedFrozen();SourceDocument evidence=new SourceDocument();evidence.setId(91L);evidence.setProjectId(PROJECT);evidence.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);evidence.setTextContent("Changed project correspondence.");f.correspondence.add(evidence);fresh(f);assertEquals(true,f.service.plan(PROJECT,null).get("evidenceChangedSinceExtraction"));}
    @Test void previewPatchesNeverExposeAnOldGenerationAsTheirResult()throws Exception {Fixture f=new Fixture();f.seedFrozen();Map<String,Object> p=f.service.plan(PROJECT,Collections.singletonMap("foundationIncluded","true"));assertNotEquals("applied",action(p,"scc-excision").get("applicationState"));assertEquals(true,p.get("preview"));verifyNoInteractions(f.model);}
    @Test void changedTemplateMetadataOrOriginalBytesRejectTheOldGeneration()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.templates.get("SCC").setTextContent("Changed source condition.");fresh(f);f=new Fixture();f.seedFrozen();byte[] altered=f.files.get("SCC").clone();altered[altered.length-1]^=1;f.files.put("SCC",altered);fresh(f);}
    @Test void missingOrBodyEditedDraftsCannotBorrowCompiledState()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.documents.remove("NTT");fresh(f);f=new Fixture();f.seedFrozen();f.documents.get("SCT").setContentEdited(true);fresh(f);}
    @Test void mismatchingRuleVersionOrSiblingFrozenPlanCannotBorrowState()throws Exception {
        Fixture f=new Fixture();f.seedFrozen();f.documents.get("SCC").setRuleVersion("different-rule");
        String before=JsonUtils.write(DraftBusinessRules.map("documents",f.documents,"artifacts",f.artifacts));fresh(f);
        assertEquals(before,JsonUtils.write(DraftBusinessRules.map("documents",f.documents,"artifacts",f.artifacts)),"Reading the fresh plan must not rewrite an older saved document or artifact");
        f=new Fixture();f.seedFrozen();Map<String,Object> p=JsonUtils.readMap(f.documents.get("SCT").getInputSnapshotJson());p.put("evidenceRevision","stale-evidence");f.documents.get("SCT").setInputSnapshotJson(JsonUtils.write(p));fresh(f);
    }
    @Test void artifactSourceRevisionAndByteIntegrityMustAllMatch()throws Exception {for(String change:Arrays.asList("source","snapshot","file","bytes","parent","missing")){Fixture f=new Fixture();f.seedFrozen();DraftArtifact a=f.artifacts.get("fixture-SCC");switch(change){case "source":a.setSourceSha256("wrong");break;case "snapshot":a.setSnapshotId("wrong");break;case "file":a.setFileKey("NTT");break;case "bytes":a.setDocxSha256("wrong");break;case "parent":a.setParentRevisionId("manual-parent");break;default:f.artifacts.remove(a.getId());}fresh(f);}}
    @Test void regenerationMustCompileFreshInsteadOfTrustingPreviousSuccess()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.service.generate(PROJECT,"en");Map<String,Object> saved=JsonUtils.readMap(f.documents.get("SCC").getInputSnapshotJson());assertNotEquals("applied",action(saved,"scc-excision").get("applicationState"));assertFalse(f.artifacts.containsKey(f.documents.get("SCC").getRevisionId())&&f.documents.get("SCC").getRevisionId().startsWith("fixture-"));verifyNoInteractions(f.model);}
}
