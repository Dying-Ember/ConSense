package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.ai.AiGateway;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.DocxTemplateEditor;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Public original-template HTTP seam. Fixtures are uploaded through the real parser and storage. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingTemplateReadingIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final Path STORAGE=Paths.get("target","drafting-template-reading-uploads",DB).toAbsolutePath();
    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_template_reading_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->STORAGE.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired SourceDocumentRepository sourceDocuments;
    @Autowired DraftReviewSources reviewSources;
    @Autowired DraftTemplateGraphService templateGraphs;

    @Test void uploadedDocxSourceReturnsItsOriginalBytesAndFilename() throws Exception {
        String id=project();byte[] uploaded=docx("Uploaded source paragraph.");
        replace(id,"NTT","uploaded template.docx",DOCX,uploaded);
        MockHttpServletResponse response=mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse();
        assertArrayEquals(uploaded,response.getContentAsByteArray());
        assertEquals("inline; filename*=UTF-8''uploaded%20template.docx",response.getHeader("Content-Disposition"));
    }

    @Test void readingIncludesNativeTableParagraphsAndDoesNotChangeTheSourceOrAdoptedValues() throws Exception {
        String id=project();byte[] uploaded=orderedDocx();
        replace(id,"SCT","current edition.docx",DOCX,uploaded);
        mvc.perform(put("/api/drafting/{id}/variables/siteInspectionStartDate",id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"2026-10-17\"}"))
                .andExpect(jsonPath("$.code").value(0));
        String variables=mvc.perform(get("/api/drafting/{id}/variables",id)).andReturn().getResponse().getContentAsString();
        String documents=mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.fileKey").value("SCT"))
                .andExpect(jsonPath("$.data.fileName").value("current edition.docx"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.format").value("docx"))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(4))
                .andExpect(jsonPath("$.data.paragraphs[0].id").value("word/document.xml#/w:document[1]/w:body[1]/w:p[1]"))
                .andExpect(jsonPath("$.data.paragraphs[0].ordinal").value(1))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Before table\tsecond part\nnext line"))
                .andExpect(jsonPath("$.data.paragraphs[1].id").value("word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]"))
                .andExpect(jsonPath("$.data.paragraphs[1].ordinal").value(2))
                .andExpect(jsonPath("$.data.paragraphs[1].text").value("Inside table"))
                .andExpect(jsonPath("$.data.paragraphs[2].ordinal").value(3))
                .andExpect(jsonPath("$.data.paragraphs[2].text").value(""))
                .andExpect(jsonPath("$.data.paragraphs[3].ordinal").value(4))
                .andExpect(jsonPath("$.data.paragraphs[3].text").value("After table"));
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCT/source",id))
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(variables,mvc.perform(get("/api/drafting/{id}/variables",id)).andReturn().getResponse().getContentAsString());
        assertEquals(documents,mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString());
    }

    @Test void unhintedNativePdfCandidatesSurviveRealBatchUploadAndRemainOutsideTheDraftSnapshot() throws Exception {
        String id=project();String[] keys={"NTT","SCT","SCC"},titles={"Notes to Tenderers","Special Conditions of Tender","Special Conditions of Contract"};
        byte[] firstPdf=null;Long firstAuxiliaryId=null;
        for(int i=0;i<keys.length;i++) {
            byte[] word=docx(titles[i]),nativePdf;
            try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                try(org.apache.pdfbox.pdmodel.PDPageContentStream content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                    content.beginText();content.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12);content.newLineAtOffset(72,720);
                    content.showText(titles[i]);content.newLineAtOffset(0,-20);content.showText("Independent PDF edition.");content.endText();
                }
                pdf.save(out);nativePdf=out.toByteArray();
            }
            mvc.perform(multipart("/api/drafting/{id}/templates/upload",id)
                    .file(new MockMultipartFile("files","reference-"+i+".pdf","application/pdf",nativePdf))
                    .file(new MockMultipartFile("files","editable-"+i+".docx",DOCX,word)))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1)).andExpect(jsonPath("$.data.failed").value(0));
            SourceDocument primary=sourceDocuments.findFirstByProjectIdAndFileKeyAndCategory(id,keys[i],SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(AssertionError::new);
            assertEquals(sha256(word),primary.getSourceSha256());
            String pdfSha=sha256(nativePdf);
            SourceDocument auxiliary=sourceDocuments.findByProjectIdAndCategoryOrderByIdAsc(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE).stream()
                    .filter(d->DraftTemplateRouting.isAuxiliary(d)&&pdfSha.equals(d.getSourceSha256())).findFirst().orElseThrow(AssertionError::new);
            assertArrayEquals(nativePdf,Files.readAllBytes(Paths.get(auxiliary.getStoragePath())));assertTrue(auxiliary.getTextContent().contains("Independent PDF edition."));
            assertEquals(1,auxiliary.getPageCount());assertFalse(auxiliary.getOcrUsed());assertFalse(JsonUtils.parse(auxiliary.getNativePdfEvidenceJson()).isEmpty());
            JsonNode coverage=JsonUtils.parse(auxiliary.getParseCoverageJson());
            OerTemplateEvidence.requireBound(keys[i],pdfSha,JsonUtils.parse(auxiliary.getStructuredContentJson()),coverage,coverage.path("templateEvidence"));
            if(i==0){firstPdf=nativePdf;firstAuxiliaryId=auxiliary.getId();}
        }
        String snapshot=JsonUtils.parse(mvc.perform(get("/api/drafting/{id}/plan",id)).andReturn().getResponse().getContentAsString()).path("data").path("snapshotId").asText();
        assertFalse(snapshot.isEmpty());
        mvc.perform(multipart("/api/drafting/{id}/templates/upload",id).file(new MockMultipartFile("files","another-name.pdf","application/pdf",firstPdf)))
                .andExpect(jsonPath("$.data.parsed").value(0)).andExpect(jsonPath("$.data.failed").value(0));
        assertEquals(6,sourceDocuments.findByProjectIdAndCategoryOrderByIdAsc(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE).size());
        assertTrue(DraftTemplateRouting.isAuxiliary(sourceDocuments.findById(firstAuxiliaryId).orElseThrow(AssertionError::new)));
        assertEquals(snapshot,JsonUtils.parse(mvc.perform(get("/api/drafting/{id}/plan",id)).andReturn().getResponse().getContentAsString()).path("data").path("snapshotId").asText());
    }

    @Test void verifiedCatalogueEditionDependsOnCurrentBytesAndFileKeyInsteadOfFilename() throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(sourceDir!=null,"Actual competition sourceDir required for verified edition acceptance");
        String id=project();String[] keys={"NTT","SCT","SCC"};
        String[] names={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
        String[] hashes={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
        List<JsonNode> reviewReadings=new ArrayList<>();
        for(int i=0;i<keys.length;i++) {
            replace(id,keys[i],"renamed source.docx",DOCX,Files.readAllBytes(Paths.get(sourceDir,names[i])));
            mvc.perform(get("/api/drafting/{id}/templates/{key}/reading",id,keys[i]))
                    .andExpect(jsonPath("$.data.catalogueSourceVerified").value(true))
                    .andExpect(jsonPath("$.data.sourceHash").value(hashes[i]));
            mvc.perform(get("/api/drafting/{id}/templates/{key}/evidence",id,keys[i]))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.sourceBinding.fileKey").value(keys[i]))
                    .andExpect(jsonPath("$.data.sourceBinding.sourceSha256").value(hashes[i]))
                    .andExpect(jsonPath("$.data.sourceBinding.analysisRevision").value(OerTemplateEvidence.ANALYSIS_REVISION));
            SourceDocument source=sourceDocuments.findFirstByProjectIdAndFileKeyAndCategory(id,keys[i],SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(AssertionError::new);
            reviewReadings.add(reviewSources.reading(source));
        }
        assertActualReviewConsumerEvidence(reviewReadings);
        byte[] replacement=docx("Replacement keeps the filename and must lose historical paragraph ordinals.");
        replace(id,"NTT","renamed source.docx",DOCX,replacement);
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(replacement)))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(1))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Replacement keeps the filename and must lose historical paragraph ordinals."));
        mvc.perform(multipart("/api/drafting/{id}/templates/SCT/replace",id)
                .file(new MockMultipartFile("file",names[1],DOCX,Files.readAllBytes(Paths.get(sourceDir,names[0])))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(0)).andExpect(jsonPath("$.data.failed").value(1));
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(true)).andExpect(jsonPath("$.data.sourceHash").value(hashes[1]));
    }

    @Test void actualSctBillAnchorsBindExactlyAndAReviewedLiteralReachesTheGeneratedPackage() throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");assumeTrue(sourceDir!=null,"Actual competition sourceDir required for Bill insertion acceptance");
        String project=project();byte[] original=Files.readAllBytes(Paths.get(sourceDir,"02_Special Conditions of Tender (SCT).docx"));
        assertEquals("7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4",sha256(original));
        replace(project,"SCT","actual SCT.docx",DOCX,original);
        SourceDocument source=sourceDocuments.findFirstByProjectIdAndFileKeyAndCategory(project,"SCT",SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(AssertionError::new);
        assertActualBillInsertions(project,Collections.singletonList(reviewSources.reading(source)));
    }

    private void assertActualBillInsertions(String project,List<JsonNode> readings) throws Exception {
        JsonNode reading=readings.stream().filter(item->"SCT".equals(item.path("fileKey").asText())).findFirst().orElseThrow(AssertionError::new);
        SourceDocument source=sourceDocuments.findById(reading.path("sourceDocumentId").asLong()).orElseThrow(AssertionError::new);
        byte[] original=Files.readAllBytes(Paths.get(source.getStoragePath()));ObjectNode focused=((ObjectNode)reading).deepCopy();
        ArrayNode groups=focused.putArray("groups"),notes=focused.putArray("guidanceNotes");focused.putArray("markerCandidates");Set<String> noteIds=new HashSet<>();
        for(JsonNode group:reading.path("groups"))if("bill_description".equals(group.path("fieldLabel").asText())) {
            groups.add(group);for(JsonNode note:group.path("guidanceNoteIds"))noteIds.add(note.asText());
            if(group.has("guidanceNoteId"))noteIds.add(group.path("guidanceNoteId").asText());
        }
        assertEquals(14,groups.size());for(JsonNode note:reading.path("guidanceNotes"))if(noteIds.contains(note.path("id").asText()))notes.add(note);
        AiGateway gateway=mock(AiGateway.class);when(gateway.complete(anyString(),anyString())).thenAnswer(call->{
            JsonNode payload=JsonUtils.parse(call.getArgument(1));List<Object> candidates=new ArrayList<>();int ordinal=0;
            for(JsonNode scope:payload.path("scopeCandidates"))if(scope.path("sourceInsertionAnchorComplete").asBoolean()) {
                String id="description"+(ordinal++);candidates.add(obj("id",id,"type","text","role","input","nameEn","Test-only Bill description "+id,"sourceQuote",payload.path("instruction").path("text").asText(),
                        "effects",Collections.singletonList(obj("scopeId",scope.path("id").asText(),"quote","","action","insert","valueSourceId",id))));
            }
            return JsonUtils.write(obj("nodes",candidates,"notes",Collections.singletonList("Test-only candidate descriptions; no project list or cardinality inferred.")));
        });
        Map<String,Object> discovered=DraftReviewDiscovery.discover(gateway,obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>())),Collections.singletonList(focused),10);
        Map<String,Map<String,Object>> targets=new LinkedHashMap<>();Map<String,Object> representative=null;
        for(Map<String,Object> node:nodes(discovered))if("discovered".equals(node.get("definitionKind"))) {
            assertEquals("pending",node.get("status"));assertFalse(node.containsKey("fixedValue"));
            Map<String,Object> target=map(list(node.get("editTargets")).get(0));assertEquals("insert",target.get("action"));
            JsonNode bound=reviewSources.bind(source,JsonUtils.mapper().valueToTree(target));assertEquals("bound",bound.path("status").asText(),target.get("sourceScopeId").toString());
            target.put("nativeBinding",JsonUtils.mapper().convertValue(bound,Map.class));assertNull(targets.put(text(target.get("sourceScopeId")),target));
            JsonNode observation=JsonUtils.mapper().valueToTree(target.get("sourceScopeObservation")),range=observation.path("insertionAnchors").path(0);
            assertEquals("unknown_from_existing_numbered_source_rows",observation.path("listCardinalityStatus").asText());assertFalse(observation.path("scopeComplete").asBoolean());
            assertEquals(range.path("start"),bound.path("spans").path(0).path("start"));assertEquals(range.path("end"),bound.path("spans").path(0).path("end"));
            assertEquals("",bound.path("spans").path(0).path("expectedText").asText());assertEquals("insert",bound.path("spans").path(0).path("operation").asText());
            if(representative==null)representative=node;
        }
        assertEquals(14,targets.size());assertNotNull(representative);
        representative.put("status","confirmed");Map<String,Object> graph=obj("graph",obj("variables",Collections.singletonList(representative)),"selectedDocuments",obj("SCT",reading.path("id").asText()));
        Map<String,Object> saved=templateGraphs.save(project,graph,null),savedAnchor=map(list(nodes(map(saved.get("graph"))).get(0).get("sourceAnchors")).get(0));
        assertEquals("bound",map(savedAnchor.get("sourceObservationBinding")).get("status"));assertEquals(false,map(savedAnchor.get("sourceObservationBinding")).get("editableAuthority"));
        assertNotEquals("bound",map(savedAnchor.get("nativeBinding")).get("status"));
        String draft=text(saved.get("id"));templateGraphs.confirm(project,draft,null);
        Map<String,Object> unknown=templateGraphs.plan(project,null);assertEquals("pending",map(list(unknown.get("actions")).get(0)).get("action"));
        com.consense.web.dto.DraftingDtos.VariableVO variable=templateGraphs.variables(project).stream().filter(item->representativeId(graph).equals(item.getKey())).findFirst().orElseThrow(AssertionError::new);
        String literal=" TEST-ONLY MANUALLY REVIEWED DESCRIPTION";
        templateGraphs.updateVariable(project,variable.getKey(),JsonUtils.mapper().convertValue(obj("graphRevisionId",variable.getGraphRevisionId(),"fieldDefinitionHash",variable.getFieldDefinitionHash(),"value",literal,"note","Opt-in local test literal; not a project answer"),com.consense.web.dto.DraftingDtos.VariablePatch.class));
        Map<String,Object> plan=templateGraphs.plan(project,null);assertTrue(list(plan.get("unresolved")).isEmpty());assertEquals("insert",map(list(plan.get("actions")).get(0)).get("action"));
        Map<String,Object> target=map(list(representative.get("editTargets")).get(0)),span=map(list(map(target.get("nativeBinding")).get("spans")).get(0));
        DocxTemplateEditor editor=new DocxTemplateEditor();DocxTemplateEditor.TemplateIndex before=editor.inspect(original);
        DocxTemplateEditor.DocxEditResult generated=templateGraphs.compile(original,"SCT",plan,Collections.emptyMap(),null);
        for(DocxTemplateEditor.Paragraph paragraph:before.getMainParagraphs())assertEquals(paragraph.getText()+(paragraph.getId().equals(span.get("paragraphId"))?literal:""),generated.getIndex().mainParagraph(paragraph.getOrdinal()).getText());
        assertEquals(1,generated.getLedger().size());assertEquals(DocxTemplateEditor.EditType.INSERT_TEXT,generated.getLedger().get(0).getType());
    }
    private static String representativeId(Map<String,Object> graph){return text(nodes(graph).get(0).get("id"));}

    private static void assertActualReviewConsumerEvidence(List<JsonNode> readings) {
        Map<String,JsonNode> fields=new LinkedHashMap<>();List<JsonNode> focused=new ArrayList<>();
        JsonNode multiScope=null;String multiScopeText=null;
        for(JsonNode reading:readings) {
            assertEquals("bound",reading.path("evidenceStatus").asText());Set<String> noteIds=new HashSet<>();
            int fieldCount=0;
            for(JsonNode group:reading.path("groups")) {
                if("native_source_field_candidate".equals(group.path("kind").asText())) {
                    fields.put(group.path("id").asText(),group);fieldCount++;
                    assertTrue(group.path("captureComplete").asBoolean());assertFalse(group.path("scopeComplete").asBoolean());
                    assertFalse(group.path("automaticEditEligible").asBoolean());assertFalse(group.path("semanticApplicabilityVerified").asBoolean());
                    assertEquals("unknown_requires_project_source",group.path("fieldValueStatus").asText());
                    for(JsonNode note:group.path("guidanceNoteIds"))noteIds.add(note.asText());
                }
                if(multiScope==null&&group.path("scopeComplete").asBoolean()&&group.path("memberRanges").size()>1&&group.has("guidanceNoteId")) {
                    multiScope=group;noteIds.add(group.path("guidanceNoteId").asText());List<String> pieces=new ArrayList<>();
                    for(JsonNode range:group.path("memberRanges")) {
                        JsonNode member=null;for(JsonNode candidate:reading.path("sourceMembers"))if(candidate.path("id").equals(range.path("memberId"))){member=candidate;break;}
                        assertNotNull(member);String text=member.path("textObservation").path(range.path("channel").asText()).asText();
                        pieces.add(text.substring(range.path("start").asInt(),range.path("end").asInt()));
                    }
                    multiScopeText=String.join("\n",pieces);
                }
            }
            assertEquals("NTT".equals(reading.path("fileKey").asText())?0:"SCT".equals(reading.path("fileKey").asText())?23:5,fieldCount);
            ObjectNode selected=reading.deepCopy();ArrayNode notes=selected.putArray("guidanceNotes");
            for(JsonNode note:reading.path("guidanceNotes"))if(noteIds.contains(note.path("id").asText()))notes.add(note);
            selected.putArray("markerCandidates");focused.add(selected);
        }
        assertEquals(28,fields.size());assertNotNull(multiScope);assertFalse(multiScopeText.isEmpty());
        Map<String,Object> empty=DraftReviewGraph.obj("kind","consense-template-graph-v1","graph",DraftReviewGraph.obj("variables",new ArrayList<>(),"events",new ArrayList<>()));
        JsonNode seeds=JsonUtils.mapper().valueToTree(DraftReviewDiscovery.seed(empty,readings));int orphanSeeds=0;
        for(JsonNode seed:seeds.path("graph").path("variables"))if("field".equals(seed.path("discovery").path("itemKind").asText())) {
            orphanSeeds++;assertEquals("pending",seed.path("status").asText());
            assertEquals("bill_range_endpoint",seed.path("sourceObservation").path("fieldLabel").asText());
            assertTrue(seed.path("sourceObservation").path("guidanceNoteIds").isEmpty());
        }
        assertEquals(1,orphanSeeds,"Only the field without a guidance association needs an independent seed.");
        AiGateway model=mock(AiGateway.class);when(model.complete(anyString(),anyString())).thenReturn("{\"nodes\":[],\"notes\":[]}");
        for(JsonNode reading:focused)DraftReviewDiscovery.discover(model,empty,Collections.singletonList(reading),10);
        ArgumentCaptor<String> payloads=ArgumentCaptor.forClass(String.class);verify(model,atLeastOnce()).complete(anyString(),payloads.capture());
        Map<String,JsonNode> conveyed=new LinkedHashMap<>();boolean multiScopeConveyed=false;int namedChannels=0,anchors=0;
        for(String raw:payloads.getAllValues())for(JsonNode scope:JsonUtils.parse(raw).path("scopeCandidates")) {
            String scopeId=scope.path("id").asText();JsonNode original=fields.get(scopeId);
            if(original!=null) {
                assertEquals(original,scope.path("observation"));conveyed.put(scopeId,scope);
            }
            if(scopeId.equals(multiScope.path("id").asText())) {
                assertEquals(multiScopeText,scope.path("text").asText());assertEquals(multiScope.path("memberRanges"),scope.path("memberRanges"));multiScopeConveyed=true;
            }
        }
        assertEquals(fields.keySet(),conveyed.keySet(),"All 27 guidance-associated fields and the orphan endpoint must reach discovery.");
        for(Map.Entry<String,JsonNode> entry:conveyed.entrySet()) {
            JsonNode original=fields.get(entry.getKey());
            for(JsonNode anchor:original.path("insertionAnchors")) {
                anchors++;assertEquals(anchor.path("start"),anchor.path("end"));assertEquals("",anchor.path("observedText").asText());
                assertFalse(original.path("sourceFieldRangeComplete").asBoolean());assertFalse(entry.getValue().path("scopeComplete").asBoolean());
            }
            for(JsonNode range:original.path("memberRanges"))if("preNormalizedTextObservation.text".equals(range.path("channel").asText())) {
                namedChannels++;assertFalse(entry.getValue().path("text").asText().isEmpty());
                assertEquals(range.path("observedText").asText(),entry.getValue().path("text").asText());
            }
        }
        assertEquals(14,anchors);assertEquals(3,namedChannels);assertTrue(multiScopeConveyed);
    }

    @Test void uploadedPdfHasHonestReadingMetadataAndTheExistingPreviewStillReturnsARealPdf() throws Exception {
        String id=project();byte[] uploaded;
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(out);uploaded=out.toByteArray();
        }
        replace(id,"NTT","uploaded source.pdf","application/pdf",uploaded);
        mvc.perform(get("/api/drafting/{id}/templates/NTT/evidence",id))
                .andExpect(jsonPath("$.code").value(4012))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TEMPLATE_EVIDENCE_MISSING")));
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.format").value("pdf"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(0));
        byte[] preview=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id))
                .andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(uploaded,preview);
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(preview)) {assertEquals(1,pdf.getNumberOfPages());}
    }

    @Test void unsupportedSourceHasAnExplicitFormatAndRetainsTheOriginalEvidence() throws Exception {
        String id=project();byte[] uploaded="Unsupported source still has its original evidence.".getBytes(StandardCharsets.UTF_8);
        replace(id,"SCC","uploaded source.txt","text/plain",uploaded);
        mvc.perform(get("/api/drafting/{id}/templates/SCC/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.format").value("unsupported"))
                .andExpect(jsonPath("$.data.fileName").value("uploaded source.txt"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(0));
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCC/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/octet-stream")).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void anUnavailableStoredSourceTellsTheUserToUploadTheTemplateAgain() throws Exception {
        String id=project();replace(id,"NTT","test-only unavailable.docx",DOCX,docx("Test-only missing-file simulation."));
        // Simulate only the file-system boundary in this test's isolated storage root.
        Path stored;
        try(Stream<Path> files=Files.walk(STORAGE.resolve(id))) {stored=files.filter(Files::isRegularFile).findFirst().get();}
        Files.delete(stored);
        for(String endpoint:new String[]{"source","reading"}) {
            mvc.perform(get("/api/drafting/{id}/templates/NTT/"+endpoint,id))
                    .andExpect(jsonPath("$.code").value(4011))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Upload")));
        }
    }

    @Test void missingAndCorruptSourcesHaveExplicitErrorsAndKeepTheirRawEvidence() throws Exception {
        String id=project();
        for(String endpoint:new String[]{"source","reading"}) {
            mvc.perform(get("/api/drafting/{id}/templates/NTT/"+endpoint,id))
                    .andExpect(jsonPath("$.code").value(4011))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_NOT_UPLOADED")));
        }
        byte[] brokenDocx="This is not a DOCX package.".getBytes(StandardCharsets.UTF_8);
        replace(id,"NTT","broken.docx",DOCX,brokenDocx);
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.code").value(4013))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_DOCX_INVALID")));
        assertArrayEquals(brokenDocx,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/octet-stream")).andReturn().getResponse().getContentAsByteArray());
        byte[] brokenPdf="%PDF-1.7\nThis PDF is damaged.".getBytes(StandardCharsets.UTF_8);
        replace(id,"SCT","broken.pdf","application/pdf",brokenPdf);
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id))
                .andExpect(jsonPath("$.code").value(4013))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_PDF_INVALID")));
        assertArrayEquals(brokenPdf,mvc.perform(get("/api/drafting/{id}/templates/SCT/source",id))
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test void sourceFormatComesFromTheCurrentBytesInsteadOfTheFilenameOrUploadMime() throws Exception {
        String id=project();byte[] uploaded=docx("Native source survives a misleading filename and MIME.");
        replace(id,"SCC","mislabelled.txt","text/plain",uploaded);
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCC/source",id))
                .andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/drafting/{id}/templates/SCC/reading",id))
                .andExpect(jsonPath("$.data.format").value("docx"))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Native source survives a misleading filename and MIME."));
    }

    private String project() throws Exception {
        String id="template-reading-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","测试专用模板阅读","nameEn","Test-only template reading"))))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }
    private void replace(String id,String key,String name,String mime,byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key)
                .file(new MockMultipartFile("file",name,mime,bytes))).andExpect(jsonPath("$.code").value(0));
    }
    private static byte[] docx(String text) throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(text);word.write(out);return out.toByteArray();
        }
    }
    private static byte[] orderedDocx() throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();
            run.setText("Before table");run.addTab();run.setText("second part");run.addBreak();run.setText("next line");
            word.createTable(1,1).getRow(0).getCell(0).setText("Inside table");
            word.createParagraph();word.createParagraph().createRun().setText("After table");word.write(out);return out.toByteArray();
        }
    }
    private static String sha256(byte[] source) throws Exception {
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source))hash.append(String.format("%02x",b));return hash.toString();
    }
}
