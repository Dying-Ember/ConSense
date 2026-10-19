package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.OerSourceRouting;
import com.consense.document.ParseCoverage;
import com.consense.ocr.OcrClient;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.domain.Project;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Routing/persistence fixtures use a bound parser result, never a model or original source. */
@ExtendWith(MockitoExtension.class)
class DraftTemplateRoutingTest {
    private static final String PROJECT="template-routing-fixture";
    @Mock ProjectService projects;
    @Mock StorageService storage;
    @Mock DocumentParser parser;
    @Mock SourceDocumentRepository sources;
    @Mock DraftVariableRepository variables;
    @Mock PlatformTransactionManager transactions;
    @Mock EntityManager entities;
    @InjectMocks DraftingService service;
    private final List<SourceDocument> documents=new ArrayList<>();
    private final Map<String,byte[]> assets=new LinkedHashMap<>();

    @BeforeEach void persistence() {
        Project project=new Project();project.setId(PROJECT);lenient().when(entities.find(Project.class,PROJECT,LockModeType.PESSIMISTIC_WRITE)).thenReturn(project);
        lenient().when(sources.findFirstByProjectIdAndFileKeyAndCategory(eq(PROJECT),anyString(),eq(SourceDocument.CATEGORY_STANDARD_TEMPLATE)))
                .thenAnswer(call->documents.stream().filter(d->Objects.equals(call.getArgument(1),d.getFileKey())).findFirst());
        lenient().when(sources.findByProjectIdAndCategoryOrderByIdAsc(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE)).thenReturn(documents);
        lenient().when(sources.save(any(SourceDocument.class))).thenAnswer(call->{SourceDocument d=call.getArgument(0);if(d.getId()==null){d.setId((long)documents.size()+1);documents.add(d);}else for(int i=0;i<documents.size();i++)if(d.getId().equals(documents.get(i).getId())){documents.set(i,d);break;}return d;});
        lenient().when(storage.store(eq(PROJECT),eq(SourceDocument.CATEGORY_STANDARD_TEMPLATE),any(MultipartFile.class))).thenAnswer(call->{MultipartFile file=call.getArgument(2);String path="fixture-asset-"+(assets.size()+1);assets.put(path,file.getBytes());return new StorageService.StoredFile(file.getOriginalFilename(),path,file.getSize(),file.getContentType());});
        lenient().when(storage.read(anyString())).thenAnswer(call->assets.get(call.getArgument(0)));
        lenient().when(parser.parseTemplate(nullable(String.class),anyString(),any(byte[].class))).thenAnswer(call->parsed(call.getArgument(0),call.getArgument(2)));
        lenient().when(parser.parseTemplate(nullable(String.class),anyString(),any(byte[].class),nullable(DocumentParser.ParsedDocument.class),nullable(OerDocumentIdentity.class))).thenAnswer(call->parsed(call.getArgument(0),call.getArgument(2)));
        lenient().when(parser.observe(eq("template.pdf"),any(byte[].class))).thenAnswer(call->{byte[] bytes=call.getArgument(1);return observation(bytes,new String(bytes,5,bytes.length-5,StandardCharsets.UTF_8));});
    }
    private static MockMultipartFile word(String name,String text) {
        byte[] body=text.getBytes(StandardCharsets.UTF_8),bytes=new byte[body.length+4];bytes[0]='P';bytes[1]='K';bytes[2]=3;bytes[3]=4;System.arraycopy(body,0,bytes,4,body.length);
        return new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes);
    }
    private static MockMultipartFile pdf(String name,String text) {return new MockMultipartFile("files",name,"application/pdf",("%PDF-"+text).getBytes(StandardCharsets.UTF_8));}
    private static DocumentParser.ParsedDocument parsed(String expected,byte[] bytes) {
        String text=new String(bytes,4,bytes.length-4,StandardCharsets.UTF_8);if("pdf".equals(DraftTemplateRouting.format(bytes)))text=new String(bytes,5,bytes.length-5,StandardCharsets.UTF_8);
        DocumentBlock block=new DocumentBlock();block.setId("fixture-p1");block.setKind("paragraph");block.setSource("docx");block.setText(text);block.setOriginalText(text);
        ParseCoverage coverage=new ParseCoverage();coverage.setComplete(true);
        DocumentParser.ParsedDocument result=new DocumentParser.ParsedDocument(text,Collections.emptyList(),0,false,"synthetic parser result",Collections.singletonList(block),coverage,DraftTemplateRouting.sha(bytes),Collections.emptyList());
        coverage.setTemplateEvidence(OerTemplateEvidence.analyse(expected,result));return result;
    }
    private static DocumentParser.ParsedDocument observation(byte[] bytes,String text) {
        DocumentBlock block=new DocumentBlock();block.setId("identity-p1");block.setKind("identity_paragraph");block.setSource("native");block.setText(text);block.setOriginalText(text);
        ParseCoverage coverage=new ParseCoverage();coverage.setComplete(false);
        DocumentParser.ParsedDocument observed=new DocumentParser.ParsedDocument(text,Collections.emptyList(),1,false,"synthetic identity observation only",Collections.singletonList(block),coverage,DraftTemplateRouting.sha(bytes),Collections.emptyList());
        ReflectionTestUtils.setField(observed,"observationOnly",true);ReflectionTestUtils.setField(observed,"observationFormat","pdf");
        ObjectNode routing=JsonUtils.mapper().createObjectNode();routing.put("format","pdf");ReflectionTestUtils.setField(observed,"routingObservation",routing);return observed;
    }
    private static SourceDocument fixedIdentity(MultipartFile file,String kind,String purpose)throws Exception {
        String sha=DraftTemplateRouting.sha(file.getBytes());OerSourceRouting router=new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(sha,kind,purpose,"standard","synthetic-fixed-v1")));
        SourceDocument source=new SourceDocument();source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileName(file.getOriginalFilename());source.setSourceSha256(sha);source.setOerIdentityJson(JsonUtils.write(router.route(file.getOriginalFilename(),sha,null)));return source;
    }
    private SourceDocument primary(String key) {return documents.stream().filter(d->key.equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);}
    private SourceDocument existing(String key,String title)throws Exception {
        assertEquals(1,service.replaceTemplate(PROJECT,key,word("original.docx",title)).getParsed());return primary(key);
    }
    private SourceDocument existingNativeWord(String key,String title)throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText(title);word.write(out);bytes=out.toByteArray();}
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),ocr).parseTemplate(key,"native.docx",bytes);verifyNoInteractions(ocr);
        when(parser.parseTemplate(eq(key),eq("native.docx"),any())).thenReturn(result);
        assertEquals(1,service.replaceTemplate(PROJECT,key,new MockMultipartFile("file","native.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)).getParsed());return primary(key);
    }

    @Test void wordWinsRegardlessOfPdfOrderAndAuxiliaryBytesRemainUnparsedAndUnadopted()throws Exception {
        MockMultipartFile pdf=pdf("NTT.pdf","scanned alternative edition"),word=word("NTT.docx","Notes to Tenderers");
        UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(pdf,word));
        assertEquals(1,result.getParsed());assertEquals(0,result.getFailed());assertEquals(DraftTemplateRouting.sha(word.getBytes()),primary("NTT").getSourceSha256());
        verify(parser,never()).parseTemplate(any(),eq("NTT.pdf"),any(),any(),any());verify(parser,never()).observe(anyString(),any());
        SourceDocument auxiliary=documents.stream().filter(d->d.getFileKey()==null).findFirst().orElseThrow(AssertionError::new);
        assertEquals(DraftTemplateRouting.AUXILIARY_STATUS,auxiliary.getParseStatus());assertArrayEquals(pdf.getBytes(),assets.get(auxiliary.getStoragePath()));
        JsonNode selection=JsonUtils.parse(auxiliary.getParseCoverageJson()).path("templateSelection");
        assertFalse(selection.path("familyConfirmed").asBoolean());assertFalse(selection.path("sameEditionVerified").asBoolean());assertFalse(selection.path("contentParsed").asBoolean());
        assertTrue(result.getMessages().stream().anyMatch(message->message.contains("unselected_auxiliary_candidate")));
    }
    @Test void conflictingWordEditionsDoNotChooseTheFirstOrLast() {
        UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(word("a.docx","Notes to Tenderers\nFirst edition"),word("b.docx","Notes to Tenderers\nDifferent edition")));
        assertEquals(0,result.getParsed());assertEquals(2,result.getFailed());assertTrue(documents.isEmpty());verify(storage,never()).store(any(),any(),any());
    }
    @Test void parsedPdfCandidatesRemainCompleteWhenTheirContentMatchesThePreferredWordFamily()throws Exception {
        String[] keys={"NTT","SCT","SCC"},titles={"Notes to Tenderers","Special Conditions of Tender","Special Conditions of Contract"};
        for(int i=0;i<keys.length;i++) {
            MockMultipartFile pdf=pdf("source-"+i+".pdf",titles[i]+"\nIndependent PDF edition"),word=word("editable-"+i+".docx",titles[i]);
            UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(pdf,word));
            assertEquals(1,result.getParsed());assertEquals(0,result.getFailed());
            assertEquals(DraftTemplateRouting.sha(word.getBytes()),primary(keys[i]).getSourceSha256());
            assertRetainedParsedAuxiliary(pdf,keys[i]);
            assertFalse(result.getMessages().stream().anyMatch(message->message.contains(pdf.getOriginalFilename()+": duplicate source bytes")));
            verify(parser).parseTemplate(isNull(),eq(pdf.getOriginalFilename()),any(),any(),any());
        }
        assertEquals(6,documents.size());
    }
    @Test void anUnhintedParsedPdfCannotDisplaceAnExistingBoundWordOrChangeItsSnapshot()throws Exception {
        SourceDocument word=existingNativeWord("SCT","Special Conditions of Tender");String before=JsonUtils.write(word);
        String snapshot=ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null);
        MockMultipartFile pdf=pdf("reference.pdf","Special Conditions of Tender\nIndependent PDF edition");
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(pdf));
        assertEquals(0,result.getParsed());assertEquals(0,result.getFailed());assertEquals(before,JsonUtils.write(primary("SCT")));
        assertRetainedParsedAuxiliary(pdf,"SCT");
        assertEquals(snapshot,ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null));
    }
    @Test void parsingPreviouslyUnanalysedAuxiliaryBytesKeepsTheirRecordAndAllNewEvidence()throws Exception {
        existingNativeWord("SCC","Special Conditions of Contract");
        MockMultipartFile hinted=pdf("SCC.pdf","Special Conditions of Contract\nIndependent PDF edition");
        assertEquals(0,service.uploadTemplates(PROJECT,Collections.singletonList(hinted)).getFailed());
        SourceDocument retained=documents.stream().filter(DraftTemplateRouting::isAuxiliary).findFirst().orElseThrow(AssertionError::new);
        assertNull(retained.getStructuredContentJson());Long id=retained.getId();String path=retained.getStoragePath();int assetCount=assets.size();
        MockMultipartFile renamed=new MockMultipartFile("files","reference.pdf","application/pdf",hinted.getBytes());
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(renamed));
        assertEquals(0,result.getFailed());SourceDocument parsed=assertRetainedParsedAuxiliary(renamed,"SCC");
        assertEquals(id,parsed.getId());assertEquals(path,parsed.getStoragePath());assertEquals(assetCount,assets.size());assertEquals(2,documents.size());
    }
    private SourceDocument assertRetainedParsedAuxiliary(MockMultipartFile file,String key)throws Exception {
        String sha=DraftTemplateRouting.sha(file.getBytes());
        SourceDocument source=documents.stream().filter(d->DraftTemplateRouting.isAuxiliary(d)&&sha.equals(d.getSourceSha256())).findFirst().orElseThrow(AssertionError::new);
        DocumentParser.ParsedDocument expected=parsed(key,file.getBytes());
        assertArrayEquals(file.getBytes(),assets.get(source.getStoragePath()));assertEquals(expected.getText(),source.getTextContent());
        assertEquals(JsonUtils.write(expected.getBlocks()),source.getStructuredContentJson());assertEquals(JsonUtils.write(expected.getCoverage()),source.getParseCoverageJson());
        JsonNode coverage=JsonUtils.parse(source.getParseCoverageJson());
        assertNotNull(OerTemplateEvidence.requireBound(key,sha,JsonUtils.parse(source.getStructuredContentJson()),coverage,coverage.path("templateEvidence")));
        return source;
    }
    @Test void anExistingBoundNativeWordKeepsAPdfOnlyUploadUnparsedWithoutClaimingStrictIdentity()throws Exception {
        SourceDocument source=existingNativeWord("NTT","Notes to Tenderers");String before=JsonUtils.write(source);
        JsonNode evidence=service.templateEvidence(PROJECT,"NTT");assertFalse(evidence.path("route").path("strictIdentityVerified").asBoolean());
        assertEquals("NTT",evidence.path("route").path("titleFamilyCandidate").asText());clearInvocations(parser);
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(pdf("NTT.pdf","different unanalysed edition")));
        assertEquals(0,result.getParsed());assertEquals(0,result.getFailed());assertEquals(before,JsonUtils.write(primary("NTT")));assertEquals(2,documents.size());verifyNoInteractions(parser);
        SourceDocument auxiliary=documents.stream().filter(DraftTemplateRouting::isAuxiliary).findFirst().orElseThrow(AssertionError::new);
        JsonNode selection=JsonUtils.parse(auxiliary.getParseCoverageJson()).path("templateSelection");assertEquals(source.getSourceSha256(),selection.path("preferredWordCandidateSha256").asText());
        assertFalse(selection.path("familyConfirmed").asBoolean());assertFalse(selection.path("sameEditionVerified").asBoolean());
    }
    @Test void anExistingWordWithoutBoundAnalysisCannotSkipThePdfParser()throws Exception {
        SourceDocument source=existingNativeWord("NTT","Notes to Tenderers");source.setParseCoverageJson("{}");clearInvocations(parser);
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(pdf("NTT.pdf","Notes to Tenderers")));
        assertEquals(0,result.getParsed());assertEquals(1,result.getFailed());assertEquals(1,documents.size());verify(parser).parseTemplate(isNull(),eq("NTT.pdf"),any(),any(),any());
    }
    @Test void anExplicitUnverifiedNativeWordCannotLendItsFamilyToAPdfHint()throws Exception {
        existingNativeWord("NTT","Unclassified fixture paragraph");clearInvocations(parser);
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(pdf("NTT.pdf","Notes to Tenderers")));
        assertEquals(0,result.getParsed());assertEquals(1,result.getFailed());assertEquals(1,documents.size());verify(parser).parseTemplate(isNull(),eq("NTT.pdf"),any(),any(),any());
    }
    @Test void identicalWordDuplicatesHaveOnePrimary() {
        UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(word("a.docx","Notes to Tenderers"),word("b.docx","Notes to Tenderers")));
        assertEquals(1,result.getParsed());assertEquals(0,result.getFailed());assertEquals(1,documents.size());
    }
    @Test void bulkUploadPreservesAnExistingDifferentSourceAndRequiresExplicitReplacement()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers\nOriginal edition");String before=JsonUtils.write(old);clearInvocations(storage);
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(word("new.docx","Notes to Tenderers\nNew edition")));
        assertEquals(0,result.getParsed());assertEquals(1,result.getFailed());assertEquals(before,JsonUtils.write(old));verify(storage,never()).store(any(),any(),any());
    }
    @Test void bulkCommitRechecksTheSourceAfterAcquiringTheProjectLock()throws Exception {
        byte[] competing=word("competing.docx","Notes to Tenderers\nConcurrent edition").getBytes();
        when(entities.find(Project.class,PROJECT,LockModeType.PESSIMISTIC_WRITE)).thenAnswer(call->{
            SourceDocument current=new SourceDocument();current.setId(99L);current.setProjectId(PROJECT);current.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);current.setFileKey("NTT");current.setSourceSha256(DraftTemplateRouting.sha(competing));current.setStoragePath("concurrent-source");documents.add(current);assets.put("concurrent-source",competing);Project project=new Project();project.setId(PROJECT);return project;
        });
        UploadResultVO result=service.uploadTemplates(PROJECT,Collections.singletonList(word("pending.docx","Notes to Tenderers\nPending edition")));
        assertEquals(0,result.getParsed());assertEquals(1,result.getFailed());assertEquals(1,documents.size());assertEquals(DraftTemplateRouting.sha(competing),primary("NTT").getSourceSha256());
    }
    @Test void explicitReplacementChangesEditionAfterSuccessfulBinding()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers\nOriginal edition");String previous=old.getSourceSha256();
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",word("new.docx","Notes to Tenderers\nNew edition")).getParsed());
        assertEquals(old.getId(),primary("NTT").getId());assertNotEquals(previous,primary("NTT").getSourceSha256());assertEquals(1,documents.size());
    }
    @Test void sameOriginalBytesCanUpgradeALegacyMissingHashWithoutChangingEdition()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers");String hash=old.getSourceSha256();old.setSourceSha256(null);
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(word("again.docx","Notes to Tenderers"))).getParsed());
        assertEquals(hash,primary("NTT").getSourceSha256());assertEquals(1,documents.size());
    }
    @Test void explicitWrongFamilyReplacementRetainsEveryOldField()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers");String before=JsonUtils.write(old);clearInvocations(storage);
        UploadResultVO result=service.replaceTemplate(PROJECT,"NTT",word("NTT.docx","Special Conditions of Tender"));
        assertEquals(1,result.getFailed());assertEquals(before,JsonUtils.write(old));verify(storage,never()).store(any(),any(),any());
    }
    @Test void parserFailureDoesNotClearTheOldTemplate()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers");String before=JsonUtils.write(old);
        when(parser.parseTemplate(eq("NTT"),eq("bad.docx"),any())).thenThrow(new IllegalArgumentException("damaged source"));
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",word("bad.docx","malformed")).getFailed());assertEquals(before,JsonUtils.write(old));
    }
    @Test void anInitialUnsupportedSourceRemainsReadableWithoutFabricatedAnalysis()throws Exception {
        byte[] raw="Unsupported raw source".getBytes(StandardCharsets.UTF_8);when(parser.parseTemplate(eq("NTT"),eq("broken.docx"),any())).thenThrow(new IllegalArgumentException("Unsupported template format"));
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",new MockMultipartFile("file","broken.docx","text/plain",raw)).getFailed());
        SourceDocument saved=primary("NTT");assertEquals("FAILED",saved.getParseStatus());assertArrayEquals(raw,service.templateSource(PROJECT,"NTT").getBytes());
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("TEMPLATE_EVIDENCE_MISSING"));
    }
    @Test void unsupportedReplacementDoesNotDisplaceAnExistingValidOriginal()throws Exception {
        SourceDocument saved=existing("NTT","Notes to Tenderers");String before=JsonUtils.write(saved);clearInvocations(storage);
        when(parser.parseTemplate(eq("NTT"),eq("unsupported.txt"),any())).thenThrow(new IllegalArgumentException("Unsupported template format"));
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",new MockMultipartFile("file","unsupported.txt","text/plain",new byte[]{1,2,3})).getFailed());
        assertEquals(before,JsonUtils.write(saved));assertEquals(1,documents.size());verify(storage,never()).store(any(),any(),any());
    }
    @Test void anInitialMalformedPdfWrappedAsParser4003RetainsItsRawEvidence()throws Exception {
        MockMultipartFile file=pdf("broken.pdf","damaged source");when(parser.observe(eq("template.pdf"),any())).thenReturn(observation(file.getBytes(),"Special Conditions of Tender"));when(parser.parseTemplate(eq("SCT"),eq("broken.pdf"),any(),any(),any())).thenThrow(new BizException(4003,"Unable to parse damaged PDF"));
        assertEquals(1,service.replaceTemplate(PROJECT,"SCT",file).getFailed());assertEquals("FAILED",primary("SCT").getParseStatus());
        assertArrayEquals(file.getBytes(),service.templateSource(PROJECT,"SCT").getBytes());
        assertTrue(assertThrows(BizException.class,()->service.templateReading(PROJECT,"SCT")).getMessage().contains("SOURCE_PDF_INVALID"));
    }
    @Test void malformedPdf4003CannotDisplaceAnExistingParsedOriginal()throws Exception {
        SourceDocument source=existing("NTT","Notes to Tenderers");String before=JsonUtils.write(source);clearInvocations(storage);
        MockMultipartFile bad=pdf("broken.pdf","damaged source");when(parser.observe(eq("template.pdf"),any())).thenReturn(observation(bad.getBytes(),"Notes to Tenderers"));when(parser.parseTemplate(eq("NTT"),eq("broken.pdf"),any(),any(),any())).thenThrow(new BizException(4003,"Unable to parse damaged PDF"));
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",pdf("broken.pdf","damaged source")).getFailed());
        assertEquals(before,JsonUtils.write(primary("NTT")));assertEquals(1,documents.size());verify(storage,never()).store(any(),any(),any());
    }
    @Test void partialTemplateIsReadableAndShownForReviewWhileGenerationRemainsBlocked()throws Exception {
        SourceDocument source=existingNativeWord("NTT","Notes to Tenderers");source.setParseStatus("PARTIAL");clearInvocations(parser);
        com.consense.web.dto.DraftingDtos.TemplateVO shown=service.listTemplates(PROJECT).stream().filter(item->"NTT".equals(item.getKey())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("partial",shown.getTag());assertEquals("Partially parsed; review required",shown.getStatus().getEn());
        assertEquals("docx",service.templateReading(PROJECT,"NTT").getFormat());verifyNoInteractions(parser);
        BizException blocked=assertThrows(BizException.class,()->ReflectionTestUtils.invokeMethod(service,"requireTemplate",PROJECT,"NTT"));assertEquals(4011,blocked.getCode());
    }
    @Test void unknownFilenameAndUnknownContentNeverDefaultToNtt() {
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(word("unknown.docx","Unclassified document"))).getFailed());assertTrue(documents.isEmpty());
    }
    @Test void exactEvaluationIdentityIsRejectedBeforeAnyParserOrAuxiliaryShortcut()throws Exception {
        MockMultipartFile file=pdf("NTT.pdf","Notes to Tenderers");DraftingService guarded=spy(service);doReturn(fixedIdentity(file,"EVALUATION_ANSWER","evaluation_answer")).when(guarded).knownTemplateSource(any());
        assertEquals(1,guarded.replaceTemplate(PROJECT,"NTT",file).getFailed());assertEquals(1,guarded.uploadTemplates(PROJECT,Collections.singletonList(file)).getFailed());
        verifyNoInteractions(parser);assertTrue(documents.isEmpty());verify(storage,never()).store(any(),any(),any());
    }
    @Test void anExactCompletedProjectSccCannotBeUsedAsAStandardTemplate()throws Exception {
        MockMultipartFile file=word("SCC.docx","Special Conditions of Contract");DraftingService guarded=spy(service);doReturn(fixedIdentity(file,"SCC","project_document")).when(guarded).knownTemplateSource(any());
        UploadResultVO result=guarded.replaceTemplate(PROJECT,"SCC",file);assertEquals(1,result.getFailed());assertTrue(result.getMessages().get(0).contains("PURPOSE_MISMATCH"));verifyNoInteractions(parser);assertTrue(documents.isEmpty());
    }
    @Test void anExactStandardFamilyMismatchIsRejectedBeforeParsingAndKeepsTheOldSource()throws Exception {
        SourceDocument old=existing("NTT","Notes to Tenderers");String before=JsonUtils.write(old);clearInvocations(parser,storage);
        MockMultipartFile file=word("NTT.docx","Special Conditions of Tender");DraftingService guarded=spy(service);doReturn(fixedIdentity(file,"SCT","standard_template")).when(guarded).knownTemplateSource(any());
        assertEquals(1,guarded.replaceTemplate(PROJECT,"NTT",file).getFailed());assertEquals(before,JsonUtils.write(primary("NTT")));verifyNoInteractions(parser);verify(storage,never()).store(any(),any(),any());
    }
    @Test void aNativeDrawingContainerIsRejectedBeforeAnyIdentityOrDetailParser() {
        MockMultipartFile file=new MockMultipartFile("file","NTT.docx","application/octet-stream","AC1027 drawing container".getBytes(StandardCharsets.US_ASCII));
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",file).getFailed());verifyNoInteractions(parser);assertTrue(documents.isEmpty());verify(storage,never()).store(any(),any(),any());
    }
    @Test void anExactStandardPdfUsesItsVerifiedIdentityWithoutAnIdentityObservation()throws Exception {
        MockMultipartFile file=pdf("renamed.bin","Notes to Tenderers");DraftingService guarded=spy(service);doReturn(fixedIdentity(file,"NTT","standard_template")).when(guarded).knownTemplateSource(any());
        assertEquals(1,guarded.replaceTemplate(PROJECT,"NTT",file).getParsed());verify(parser,never()).observe(anyString(),any());verify(parser).parseTemplate(eq("NTT"),eq("renamed.bin"),any(),isNull(),any());
    }
    @Test void anUnknownPdfCannotUseTheExplicitKeyOrFilenameAsNonDrawingEvidence()throws Exception {
        MockMultipartFile file=pdf("NTT.pdf","This source refers to Notes to Tenderers for background.");
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",file).getFailed());verify(parser).observe(eq("template.pdf"),any());
        verify(parser,never()).parseTemplate(any(),anyString(),any());verify(parser,never()).parseTemplate(any(),anyString(),any(),any(),any());
        SourceDocument pending=primary("NTT");assertEquals("IDENTITY_PENDING",pending.getParseStatus());assertArrayEquals(file.getBytes(),service.templateSource(PROJECT,"NTT").getBytes());assertNull(pending.getStructuredContentJson());
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("TEMPLATE_EVIDENCE_MISSING"));
        assertEquals("identity_pending",service.listTemplates(PROJECT).get(0).getTag());
    }
    @Test void anUnassignedIdentityPendingPdfDoesNotChangeTheAdoptedSourceSnapshot()throws Exception {
        existing("NTT","Notes to Tenderers");String before=ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null);
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(pdf("unknown.pdf","Unclassified source"))).getFailed());
        SourceDocument pending=documents.stream().filter(d->d.getFileKey()==null).findFirst().orElseThrow(AssertionError::new);assertEquals("IDENTITY_PENDING",pending.getParseStatus());
        assertEquals(before,ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null));
    }
    @Test void aContentSupportedFamilyDoesNotDependOnFilename() {
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(word("mystery.bin","Special Conditions of Tender"))).getParsed());assertNotNull(primary("SCT"));
    }
    @Test void explicitUnknownContentKeepsItsIdentityUnverified() {
        assertEquals(1,service.replaceTemplate(PROJECT,"NTT",word("chosen.docx","Unclassified fixture paragraph")).getParsed());
        JsonNode route=service.templateEvidence(PROJECT,"NTT").path("route");assertEquals("explicit_unverified",route.path("familyIdentityStatus").asText());assertFalse(route.path("strictIdentityVerified").asBoolean());
    }
    @Test void foreignParserBytesCannotReplaceTheUploadedSource()throws Exception {
        when(parser.parseTemplate(isNull(),eq("moved.docx"),any())).thenReturn(parsed("NTT",word("different.docx","Notes to Tenderers\nOther bytes").getBytes()));
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(word("moved.docx","Notes to Tenderers"))).getFailed());assertTrue(documents.isEmpty());verify(storage,never()).store(any(),any(),any());
    }
    @Test void aStandalonePdfUsesTheFullTemplateEntry() {
        assertEquals(1,service.uploadTemplates(PROJECT,Collections.singletonList(pdf("SCT.pdf","Special Conditions of Tender"))).getParsed());
        verify(parser).observe(eq("template.pdf"),any());verify(parser).parseTemplate(isNull(),eq("SCT.pdf"),any(),any(),any());assertEquals(1,documents.size());
    }
    @Test void unknownAndConflictingPdfHintsCannotBorrowTheWordFamily() {
        assertNull(DraftTemplateRouting.filenameHint("unknown.pdf"));assertNull(DraftTemplateRouting.filenameHint("NTT-SCT.pdf"));
        UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(word("NTT.docx","Notes to Tenderers"),pdf("unknown.pdf","Unclassified source")));
        assertEquals(1,result.getParsed());assertEquals(1,result.getFailed());verify(parser).observe(eq("template.pdf"),any());verify(parser,never()).parseTemplate(any(),eq("unknown.pdf"),any(),any(),any());assertEquals(2,documents.size());
        SourceDocument pending=documents.stream().filter(d->d.getFileKey()==null).findFirst().orElseThrow(AssertionError::new);assertEquals("IDENTITY_PENDING",pending.getParseStatus());assertNull(pending.getStructuredContentJson());
    }
    @Test void storedEvidenceReadsWithoutParserAndRejectsChangedInputAndPayload()throws Exception {
        SourceDocument source=existing("NTT","Notes to Tenderers");clearInvocations(parser);
        JsonNode evidence=service.templateEvidence(PROJECT,"NTT");assertEquals(source.getSourceSha256(),evidence.path("sourceBinding").path("sourceSha256").asText());
        ((ObjectNode)evidence).put("externalMutation",true);assertFalse(service.templateEvidence(PROJECT,"NTT").has("externalMutation"));verifyNoInteractions(parser);
        String originalBlocks=source.getStructuredContentJson();JsonNode blocks=JsonUtils.parse(originalBlocks);((ObjectNode)blocks.get(0)).put("text","changed stored input");source.setStructuredContentJson(JsonUtils.write(blocks));
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("INPUT_CHANGED"));
        source.setStructuredContentJson(originalBlocks);JsonNode coverage=JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)coverage.path("templateEvidence")).put("externalMutation",true);source.setParseCoverageJson(JsonUtils.write(coverage));
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("PAYLOAD_CHANGED"));
    }
    @Test void legacyMissingEvidenceDoesNotTriggerAHiddenReparse()throws Exception {
        SourceDocument source=existing("NTT","Notes to Tenderers");source.setParseCoverageJson("{}");clearInvocations(parser);
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("TEMPLATE_EVIDENCE_MISSING"));verifyNoInteractions(parser);
    }
    @Test void storedEvidenceRejectsChangedOriginalBytes()throws Exception {
        SourceDocument source=existing("NTT","Notes to Tenderers");assets.put(source.getStoragePath(),new byte[]{1,2,3});clearInvocations(parser);
        assertTrue(assertThrows(BizException.class,()->service.templateEvidence(PROJECT,"NTT")).getMessage().contains("SOURCE_HASH_MISMATCH"));verifyNoInteractions(parser);
    }
    @Test void unselectedAuxiliaryBytesDoNotChangeTheAdoptedDraftSourceSnapshot()throws Exception {
        existing("NTT","Notes to Tenderers");String before=ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null);
        UploadResultVO result=service.uploadTemplates(PROJECT,Arrays.asList(word("original.docx","Notes to Tenderers"),pdf("NTT.pdf","different source edition")));
        assertEquals(1,result.getParsed());assertEquals(0,result.getFailed());assertEquals(2,documents.size());
        String after=ReflectionTestUtils.invokeMethod(service,"snapshotId",PROJECT,(Map<String,String>)null);assertEquals(before,after);
    }
}
