package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.document.DocumentBlock;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;
import javax.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored complete-scope evidence seam with actual streamed H2 CLOBs; no live source or service. */
class CompactNormalPreparationTest {
    @Test void allThirtyThreeSourcesUseRawClobIntegrityWithoutMaterializingStoredSnapshots() throws Exception {
        try(Wire wire=new Wire(33)) {
            List<Long> consumed=new ArrayList<>();
            Object prepared=wire.prepare((source,graph)->{
                assertEquals(JsonUtils.parse(source.getNormalCandidateJson()),graph);
                consumed.add(source.getId());
            });
            wire.attest(prepared,wire.proofs());
            assertEquals(33,consumed.size());
            assertEquals(new ArrayList<>(wire.documents.keySet()),consumed);
            @SuppressWarnings("unchecked") Map<Long,SourceDocument> retained=(Map<Long,SourceDocument>)invoke(prepared,"sources");
            assertEquals(33,retained.size());
            for(SourceDocument source:retained.values()) {
                assertNull(source.getTextContent());assertNull(source.getStructuredContentJson());
                assertNull(source.getParseCoverageJson());assertNull(source.getNormalCandidateJson());
            }
            for(Long id:wire.documents.keySet())verify(wire.reader,times(1)).normalDocument("authored-compact",id,"vetting");
            verify(wire.bindings,never()).findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString());
            verify(wire.bindings,never()).findById(anyLong());
            verifyNoInteractions(wire.backend);
        }
    }

    @Test void changedRawClobBytesRejectBeforeConsumingTheCurrentSource()throws Exception {
        try(Wire wire=new Wire(1)){
            wire.replaceRaw(1L,"{\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\",\"changed\":\"😀\"}");
            assertReason("CANONICAL_STORED_BYTES_CHANGED",()->wire.prepare((source,graph)->fail("Changed stored bytes must not reach the consumer")));
            verify(wire.reader,never()).normalDocument(anyString(),anyLong(),anyString());
        }
    }

    @Test void capabilityClassificationDoesNotReadCurrentSourcesOrRehashOriginals()throws Exception {
        try(Wire wire=new Wire(33)){
            assertEquals(33,wire.candidates().size());verifyNoInteractions(wire.reader);verifyNoInteractions(wire.backend);
        }
    }

    @Test void preparationRetainsTheModuleEligibilityGuardBeforeReadingSourceContent()throws Exception {
        try(Wire wire=new Wire(1)){
            wire.rows.get(1L).setEligibleModulesJson("[\"advisory\"]");
            assertReason("CANONICAL_MODULE_NOT_ELIGIBLE",()->wire.prepare((source,graph)->fail("Ineligible binding must not reach the consumer")));
            verify(wire.reader,never()).normalDocument(anyString(),anyLong(),anyString());
        }
    }

    @Test void everyChangedCurrentContentFieldRejectsTheOldCandidateDespiteStableOriginalAndRouting()throws Exception {
        for(String field:Arrays.asList("text","structure","coverage","candidate"))try(Wire wire=new Wire(1)){
            SourceDocument source=wire.documents.get(1L);String original=source.getSourceSha256(),routing=CanonicalEvidenceService.routingHash(source);
            if("text".equals(field))source.setTextContent("AUTHORED changed current text");
            else if("structure".equals(field)){ArrayNode blocks=(ArrayNode)JsonUtils.parse(source.getStructuredContentJson());((ObjectNode)blocks.get(0)).put("text","AUTHORED changed structured text");source.setStructuredContentJson(JsonUtils.write(blocks));}
            else if("coverage".equals(field))source.setParseCoverageJson("{\"complete\":false,\"limitations\":[\"AUTHORED new limitation\"]}");
            else {ObjectNode graph=(ObjectNode)JsonUtils.parse(source.getNormalCandidateJson());graph.put("generationSha256",Wire.hash("changed candidate generation"));source.setNormalCandidateJson(JsonUtils.write(graph));}
            Wire.stamp(source);wire.stamps.put(1L,CanonicalCurrentSourceReader.compactVerifiedSource(source));
            assertEquals(original,source.getSourceSha256());assertEquals(routing,CanonicalEvidenceService.routingHash(source));
            assertThrows(CanonicalEvidenceException.class,()->wire.prepare((current,graph)->fail("Stale source graph must not reach the consumer")),field);
        }
    }

    @Test void orderedNativeProofPreservesEverySourceGraphAndBindingIdentity()throws Exception {
        for(String defect:Arrays.asList("graph","generation","role","module","routing","original","sourceHash","projectionRevision","sourceClass","sourcePurpose","filename","document","extraBody","missing","reordered"))try(Wire wire=new Wire(2)){
            Object prepared=wire.prepare((source,graph)->{});ArrayNode proofs=wire.proofs();ObjectNode proof=(ObjectNode)proofs.get(0),identity=(ObjectNode)proof.path("projectionIdentity");
            switch(defect){
                case "graph":identity.put("candidateGraphSha256",Wire.hash("changed graph"));break;
                case "generation":identity.put("generationSha256",Wire.hash("changed generation"));break;
                case "role":identity.put("role","standard");break;
                case "module":identity.put("module","advisory");break;
                case "routing":identity.put("routingSha256",Wire.hash("changed routing"));break;
                case "original":identity.put("originalSourceSha256",Wire.hash("changed original"));break;
                case "sourceHash":proof.put("sourceHash",Wire.hash("changed canonical"));break;
                case "projectionRevision":proof.put("projectionRevisionSha256",Wire.hash("changed projection revision"));break;
                case "sourceClass":((ObjectNode)proof.path("sourceBinding")).put("sourceClass","evaluation_answer");break;
                case "sourcePurpose":((ObjectNode)proof.path("sourceBinding")).put("sourcePurpose","evaluation_answer");break;
                case "filename":((ObjectNode)proof.path("source")).put("filename","AUTHORED changed.docx");break;
                case "document":identity.put("documentId","999");break;
                case "extraBody":proof.putArray("chunks").add("AUTHORED full payload must not be accepted");break;
                case "missing":proofs.remove(0);break;
                default:JsonNode first=proofs.get(0);proofs.set(0,proofs.get(1));proofs.set(1,first);
            }
            assertThrows(CanonicalEvidenceException.class,()->wire.attest(prepared,proofs),defect);
            assertReason("CANONICAL_COMPACT_NORMAL_PROOF_REQUIRED",()->invoke(prepared,"scope"));
        }
    }

    @Test void currentScopeAndOriginalDriftAcrossNativeWaitCannotAttest()throws Exception {
        for(boolean original:new boolean[]{false,true})try(Wire wire=new Wire(1)){
            Object prepared=wire.prepare((source,graph)->{});
            if(original)doThrow(new CanonicalEvidenceException(org.springframework.http.HttpStatus.CONFLICT,"SOURCE_ORIGINAL_METADATA_CHANGED")).when(wire.reader).requireOriginalFreshness(any(),any());
            else{SourceDocument changed=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(wire.stamps.get(1L),changed);changed.setTextContentSha256(Wire.hash("changed current text"));changed.setBusinessContentSha256(SourceContentDigests.combined(changed.getTextContentSha256(),changed.getStructuredContentSha256(),changed.getParseCoverageSha256(),changed.getNormalCandidateSha256()));wire.stamps.put(1L,changed);}
            assertThrows(RuntimeException.class,()->wire.attest(prepared,wire.proofs()));
            assertReason("CANONICAL_COMPACT_NORMAL_PROOF_REQUIRED",()->invoke(prepared,"sources"));
        }
    }

    @Test void nativeGraphIdentityUsesTheExistingPythonBinary64Spelling()throws Exception {
        try(Wire wire=new Wire(1)){
            SourceDocument source=wire.documents.get(1L);source.setParseCoverageJson("{\"complete\":true,\"readingPlan\":{\"authoredRatio\":1e-05}}");NormalSourceCandidateGraph.capture(source);Wire.stamp(source);wire.stamps.put(1L,CanonicalCurrentSourceReader.compactVerifiedSource(source));
            JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());assertNotEquals(CanonicalJson.hash(source.getNormalCandidateJson(),null),CanonicalJson.nodeHash(graph));
            Object prepared=wire.prepare((current,value)->{});assertDoesNotThrow(()->wire.attest(prepared,wire.proofs()));
        }
    }

    @Test void legacyForeignAndReviewedRowsUseTheExistingFullPath()throws Exception {
        for(String reason:Arrays.asList("legacySource","legacyBinding","foreign","reviewed"))try(Wire wire=new Wire(1)){
            CanonicalSourceBinding row=wire.rows.get(1L);
            switch(reason){
                case "legacySource":wire.stamps.get(1L).setBusinessContentSha256(null);break;
                case "legacyBinding":row.setSnapshotContentSha256(null);break;
                case "foreign":row.setDeclaredModule("advisory");break;
                default:row.setAdoptionManifestSha256(Wire.hash("authored reviewed adoption"));
            }
            assertNull(wire.candidates(),reason);
            verify(wire.bindings,never()).findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString());
        }
    }

    @Test void invalidCommittedSnapshotStampAndCurrentScopeAreRejectedRatherThanTreatedAsLegacy()throws Exception {
        try(Wire wire=new Wire(1)){wire.rows.get(1L).setSnapshotContentSha256(Wire.hash("changed content stamp"));assertReason("CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE",wire::candidates);}
        try(Wire wire=new Wire(1)){SourceDocument current=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(wire.documents.get(1L),current);current.setFileName("AUTHORED different current source.docx");when(wire.reader.normalDocument("authored-compact",1L,"vetting")).thenReturn(current);assertReason("CANONICAL_SOURCE_STALE",()->wire.prepare((source,graph)->fail("Changed source scope must not reach the consumer")));}
    }

    @Test void aForeignRevisionAppearingDuringPreparationCannotBecomeUncheckedAuthority()throws Exception {
        try(Wire wire=new Wire(1)){
            CanonicalSourceBinding original=wire.rows.get(1L),foreign=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(original,foreign);foreign.setId(2L);foreign.setDeclaredModule("advisory");
            when(wire.bindings.findCompactByDocumentBefore(eq("authored-compact"),eq(1L),eq(2L),any())).thenReturn(Collections.singletonList(Wire.metadata(original)));
            when(wire.reader.normalDocument("authored-compact",1L,"vetting")).thenAnswer(call->{wire.rows.put(1L,foreign);return wire.documents.get(1L);});
            assertReason("CANONICAL_COMPACT_BINDING_CHANGED",()->wire.prepare((source,graph)->{}));
        }
    }

    private static void assertReason(String reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(CanonicalEvidenceException.class,action).getMessage());}

    private static Object invoke(Object target,String name,Object... arguments) {
        Method selected=null;
        for(Method method:target.getClass().getMethods())if(name.equals(method.getName())&&method.getParameterCount()==arguments.length){selected=method;break;}
        assertNotNull(selected,"Missing compact preparation method: "+name);
        try{return selected.invoke(target,arguments);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof RuntimeException)throw (RuntimeException)cause;if(cause instanceof Error)throw (Error)cause;throw new AssertionError(cause);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }

    static final class Wire implements AutoCloseable {
        final CanonicalSourceBindingRepository bindings=mock(CanonicalSourceBindingRepository.class);
        final CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
        final CanonicalEvidenceService service=new CanonicalEvidenceService(bindings,backend,reader);
        final Map<Long,SourceDocument> documents=new LinkedHashMap<>(),stamps=new LinkedHashMap<>();
        final Map<Long,CanonicalSourceBinding> rows=new LinkedHashMap<>();
        final Connection connection;
        Wire(int count)throws Exception {
            connection=DriverManager.getConnection("jdbc:h2:mem:compact_normal_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
            try(Statement statement=connection.createStatement()){statement.execute("create table canonical_source_binding(id bigint primary key,snapshot_json clob,snapshot_bytes_sha256 varchar(64),snapshot_content_sha256 varchar(64))");}
            EntityManager manager=mock(EntityManager.class);Session session=mock(Session.class);
            when(manager.unwrap(Session.class)).thenReturn(session);
            when(session.doReturningWork(any())).thenAnswer(call->((ReturningWork<?>)call.getArgument(0)).execute(connection));
            ReflectionTestUtils.setField(service,"entityManager",manager);
            when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString()))
                .thenThrow(new AssertionError("Full stored snapshot repository read before native preparation"));
            when(bindings.findById(anyLong())).thenThrow(new AssertionError("Full stored snapshot entity read before native preparation"));
            when(bindings.findCompactByDocument(anyString(),anyLong(),any())).thenAnswer(call->{CanonicalSourceBinding row=rows.get(call.getArgument(1));return row==null?Collections.emptyList():Collections.singletonList(metadata(row));});
            when(bindings.findCompactHistoriesForSelected(anyString(),anyList(),any())).thenAnswer(call->rows.values().stream().map(Wire::metadata).collect(java.util.stream.Collectors.toList()));
            when(bindings.findCompactById(anyLong())).thenAnswer(call->Optional.ofNullable(rows.get(call.getArgument(0))).map(Wire::metadata));
            when(reader.currentModuleMetadata("authored-compact","vetting")).thenAnswer(call->stamps);
            when(reader.currentVettingMetadata("authored-compact")).thenAnswer(call->stamps);
            when(reader.currentStamp(eq("authored-compact"),anyLong(),eq("vetting"))).thenAnswer(call->stamps.get(call.getArgument(1)));
            when(reader.normalDocument(eq("authored-compact"),anyLong(),eq("vetting"))).thenAnswer(call->documents.get(call.getArgument(1)));
            doAnswer(call->{SourceContentDigests.requireCurrent(call.getArgument(0),call.getArgument(1));return null;}).when(reader).requireCurrentStamp(any(),any());
            for(long id=1;id<=count;id++)add(id);
        }
        void add(long id)throws Exception {
            SourceDocument source=new SourceDocument();source.setId(id);source.setProjectId("authored-compact");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
            source.setFileKey("SCT");source.setFileName("AUTHORED "+id+".docx");source.setReviewRole("tender");source.setDocumentScope("project");source.setParseStatus("PARSED");source.setSourceSha256(hash("original-"+id));
            OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(source.getSourceSha256());identity.setKind("SCT");identity.setSourcePurpose("project_document");identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));source.setOerIdentityJson(JsonUtils.write(identity));
            source.setTextContent("AUTHORED clause "+id+" keeps its exact text and Unicode 😀.");
            DocumentBlock block=new DocumentBlock();block.setId("authored-block-"+id);block.setText(source.getTextContent());block.setLocation("AUTHORED paragraph "+id);block.setSource("native");
            block.setSourcePosition(JsonUtils.parse("{\"kind\":\"docx_native\",\"sourceSha256\":\""+source.getSourceSha256()+"\",\"partName\":\"word/document.xml\",\"partSha256\":\""+hash("part-"+id)+"\",\"nodePath\":\"/{body}/p["+id+"]\"}"));
            source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setParseCoverageJson("{\"complete\":true}");NormalSourceCandidateGraph.capture(source);stamp(source);
            SourceDocument compact=CanonicalCurrentSourceReader.compactVerifiedSource(source);documents.put(id,source);stamps.put(id,compact);
            CanonicalSourceBinding row=new CanonicalSourceBinding();row.setId(id);row.setProjectId(source.getProjectId());row.setDocumentId(id);row.setProjectionSha256(hash("projection-"+id));row.setOriginalSourceSha256(source.getSourceSha256());row.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));row.setRoutingSha256(CanonicalEvidenceService.routingHash(source));row.setSourceRevisionSha256(hash("revision-"+id));row.setProjectionRevisionSha256(hash("projection-revision-"+id));row.setCanonicalSourceHash(hash("canonical-"+id));row.setScopePolicySha256(hash("policy"));row.setDeclaredModule("vetting");row.setEligibleModulesJson("[\"vetting\",\"advisory\"]");
            String raw="{\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\",\"authoredBody\":\""+("AUTHORED Unicode 😀 "+id+" ").repeat(1024)+"\"}";
            row.setSnapshotBytesSha256(Utf8.sha256(raw));row.setSnapshotContentSha256(row.getSnapshotBytesSha256());rows.put(id,row);
            try(PreparedStatement statement=connection.prepareStatement("insert into canonical_source_binding values(?,?,?,?)")){statement.setLong(1,id);statement.setCharacterStream(2,new java.io.StringReader(raw));statement.setString(3,row.getSnapshotBytesSha256());statement.setString(4,row.getSnapshotContentSha256());statement.executeUpdate();}
        }
        Object prepare(BiConsumer<SourceDocument,JsonNode> consumer) {
            boolean compact=Arrays.stream(service.getClass().getMethods()).anyMatch(method->"prepareCompactNormalSources".equals(method.getName()));
            if(!compact){service.verifiedSemanticBindings("authored-compact","vetting",new ArrayList<>(documents.values()));throw new AssertionError("Legacy full snapshot preparation unexpectedly returned");}
            List<CanonicalSourceBinding> candidates=candidates();
            assertNotNull(candidates);
            return invoke(service,"prepareCompactNormalSources","authored-compact","vetting",new ArrayList<>(stamps.values()),candidates,consumer);
        }
        @SuppressWarnings("unchecked") List<CanonicalSourceBinding> candidates(){return (List<CanonicalSourceBinding>)invoke(service,"compactNormalCandidates","authored-compact","vetting",new ArrayList<>(stamps.values()));}
        void replaceRaw(long id,String raw)throws Exception{try(PreparedStatement statement=connection.prepareStatement("update canonical_source_binding set snapshot_json=? where id=?")){statement.setCharacterStream(1,new java.io.StringReader(raw));statement.setLong(2,id);statement.executeUpdate();}}
        void attest(Object preparation,JsonNode proofs){invoke(service,"attestCompactNormalSources",preparation,proofs);}
        ArrayNode proofs(){ArrayNode result=JsonUtils.mapper().createArrayNode();for(CanonicalSourceBinding row:rows.values())result.add(proof(row));return result;}
        ObjectNode proof(CanonicalSourceBinding row){
            SourceDocument source=documents.get(row.getDocumentId());ObjectNode proof=JsonUtils.mapper().createObjectNode();proof.put("projectionSha256",row.getProjectionSha256());proof.put("producerKind","normal_observed");proof.put("projectionRevisionSha256",row.getProjectionRevisionSha256());proof.put("sourceHash",row.getCanonicalSourceHash());proof.put("snapshotSha256",hash("native-wire-"+row.getId()));proof.put("parentSha256",hash("parents-"+row.getId()));
            ObjectNode identity=proof.putObject("projectionIdentity");identity.put("version",NormalObservedContract.VERSION);identity.put("producerKind","normal_observed");identity.put("documentId",String.valueOf(row.getDocumentId()));identity.put("role",VettingCorpus.sourceRole(source));identity.put("module",row.getDeclaredModule());identity.set("eligibleModules",JsonUtils.parse(row.getEligibleModulesJson()));identity.put("originalSourceSha256",row.getOriginalSourceSha256());identity.put("javaParsedSourceHash",row.getJavaParsedSourceHash());identity.put("routingSha256",row.getRoutingSha256());identity.put("scopePolicySha256",row.getScopePolicySha256());identity.put("sourceRevisionSha256",row.getSourceRevisionSha256());identity.put("candidateGraphSha256",CanonicalJson.nodeHash(JsonUtils.parse(source.getNormalCandidateJson())));identity.set("generationSha256",JsonUtils.parse(source.getNormalCandidateJson()).path("generationSha256"));identity.put("sourcePurpose","project_document");identity.put("sourceClass","sct");
            ObjectNode original=proof.putObject("source");original.put("projectId",source.getProjectId());original.put("documentId",String.valueOf(source.getId()));original.put("sourceSha256",source.getSourceSha256());original.put("parsedSourceHash",VettingCorpus.sourceHash(source));original.put("sourceRevisionSha256",row.getSourceRevisionSha256());original.put("filename",source.getFileName());original.put("sourceFilename",source.getFileName());
            ObjectNode sourceBinding=proof.putObject("sourceBinding");for(String key:Arrays.asList("module","eligibleModules","sourcePurpose","sourceClass","routingSha256","scopePolicySha256"))sourceBinding.set(key,identity.path(key));return proof;
        }
        static CanonicalSourceBindingRepository.CompactMetadata metadata(CanonicalSourceBinding row){return mock(CanonicalSourceBindingRepository.CompactMetadata.class,call->{try{return row.getClass().getMethod(call.getMethod().getName()).invoke(row);}catch(ReflectiveOperationException ignored){return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);}});}
        static void stamp(SourceDocument source){source.setTextContentSha256(SourceContentDigests.field(source.getTextContent()));source.setStructuredContentSha256(SourceContentDigests.field(source.getStructuredContentJson()));source.setParseCoverageSha256(SourceContentDigests.field(source.getParseCoverageJson()));source.setNormalCandidateSha256(SourceContentDigests.field(source.getNormalCandidateJson()));source.setBusinessContentSha256(SourceContentDigests.combined(source.getTextContentSha256(),source.getStructuredContentSha256(),source.getParseCoverageSha256(),source.getNormalCandidateSha256()));}
        static String hash(String value){return VettingCorpus.hash(value);}
        @Override public void close()throws Exception{connection.close();}
    }
}
