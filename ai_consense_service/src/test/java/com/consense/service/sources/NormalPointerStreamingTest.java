package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.config.ConsenseProperties;
import com.consense.repository.SourceDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public single-unit read against an authored actual producer and streamed H2 CLOB. */
class NormalPointerStreamingTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings={"valid","text","structure","coverage","candidate","original","role","during_wait","native_changed","raw_snapshot","wrong_projection","missing_unit","missing_source_stamp","missing_binding_stamp","wrong_module"})
    void publicNormalUnitReadDoesNotLoadTheCompleteSnapshotOrSourceObjects(String scenario)throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            CanonicalSourceBinding binding=wire.rows.get(1L);
            String raw=produce(wire,binding,temporary);JsonNode projection=JsonUtils.parse(raw),identity=projection.path("projectionIdentity");
            binding.setProjectionSha256(projection.path("projectionSha256").asText());binding.setCanonicalSourceHash(projection.path("sourceHash").asText());
            binding.setProjectionRevisionSha256(projection.path("projectionRevisionSha256").asText());binding.setSourceRevisionSha256(identity.path("sourceRevisionSha256").asText());
            binding.setSnapshotBytesSha256(Utf8.sha256(raw));binding.setSnapshotContentSha256(binding.getSnapshotBytesSha256());
            try(PreparedStatement statement=wire.connection.prepareStatement("update canonical_source_binding set snapshot_json=?,snapshot_bytes_sha256=?,snapshot_content_sha256=? where id=1")){
                statement.setCharacterStream(1,new java.io.StringReader(raw));statement.setString(2,binding.getSnapshotBytesSha256());statement.setString(3,binding.getSnapshotContentSha256());statement.executeUpdate();
            }
            SourceDocument source=wire.documents.get(1L);Path original=temporary.resolve("original.docx");Files.writeString(original,"original-1",StandardCharsets.UTF_8);source.setStoragePath(original.toString());
            try(java.sql.Statement statement=wire.connection.createStatement()){statement.execute("create table source_document(id bigint,project_id varchar(64),text_content clob,structured_content_json clob,parse_coverage_json clob,normal_candidate_json clob)");}
            try(PreparedStatement statement=wire.connection.prepareStatement("insert into source_document values(?,?,?,?,?,?)")){
                statement.setLong(1,1);statement.setString(2,source.getProjectId());
                String[] bodies={source.getTextContent(),source.getStructuredContentJson(),source.getParseCoverageJson(),source.getNormalCandidateJson()};
                for(int index=0;index<bodies.length;index++)statement.setCharacterStream(index+3,new java.io.StringReader(bodies[index]));statement.executeUpdate();
            }
            SourceDocumentRepository documents=mock(SourceDocumentRepository.class);
            SourceDocumentRepository.ScopeMetadata metadata=mock(SourceDocumentRepository.ScopeMetadata.class,call->{
                try{return source.getClass().getMethod(call.getMethod().getName()).invoke(source);}catch(ReflectiveOperationException ignored){return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);}
            });
            when(documents.findScopeSourcesByProjectId(source.getProjectId())).thenAnswer(call->Collections.singletonList(CanonicalCurrentSourceReader.compactVerifiedSource(source)));
            when(documents.findScopeMetadataById(1L)).thenReturn(Optional.of(metadata));when(documents.findCatalogMetadataByProjectId(source.getProjectId())).thenReturn(Collections.singletonList(metadata));
            ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(temporary.toString());
            CanonicalCurrentSourceReader current=spy(new CanonicalCurrentSourceReader(documents,wire.bindings,properties));
            ReflectionTestUtils.setField(current,"entityManager",ReflectionTestUtils.getField(wire.service,"entityManager"));ReflectionTestUtils.setField(wire.service,"sources",current);
            when(wire.backend.verifyNormalSnapshot(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
                call.<CanonicalEvidenceBackend.NormalSnapshotVerifier>getArgument(4).verify(new java.io.StringReader("native_changed".equals(scenario)?raw.replace("AUTHORED clause","ALTERED clause"):raw));
                if("during_wait".equals(scenario))source.setReviewRole("standard");return true;
            });
            JsonNode selected=projection.path("chunks").get(0);
            if("text".equals(scenario))source.setTextContent(source.getTextContent()+" ALTERED");
            if("structure".equals(scenario))source.setStructuredContentJson(source.getStructuredContentJson().replace("AUTHORED clause","ALTERED clause"));
            if("coverage".equals(scenario))source.setParseCoverageJson("{\"complete\":false}");
            if("candidate".equals(scenario)){ObjectNode graph=(ObjectNode)JsonUtils.parse(source.getNormalCandidateJson());graph.put("alteredGeneration","untrusted");source.setNormalCandidateJson(JsonUtils.write(graph));}
            if(Arrays.asList("text","structure","coverage","candidate").contains(scenario)){
                CompactNormalPreparationTest.Wire.stamp(source);
                try(PreparedStatement statement=wire.connection.prepareStatement("update source_document set text_content=?,structured_content_json=?,parse_coverage_json=?,normal_candidate_json=? where id=1")){
                    String[] bodies={source.getTextContent(),source.getStructuredContentJson(),source.getParseCoverageJson(),source.getNormalCandidateJson()};
                    for(int index=0;index<bodies.length;index++)statement.setCharacterStream(index+1,new java.io.StringReader(bodies[index]));statement.executeUpdate();
                }
            }
            if("original".equals(scenario))Files.writeString(original,"ALTERED original",StandardCharsets.UTF_8);
            if("role".equals(scenario))source.setReviewRole("standard");
            if("missing_source_stamp".equals(scenario))source.setBusinessContentSha256(null);
            if("missing_binding_stamp".equals(scenario))binding.setSnapshotContentSha256(null);
            if("wrong_module".equals(scenario))binding.setDeclaredModule("advisory");
            if("raw_snapshot".equals(scenario))wire.replaceRaw(1L,raw.replace("AUTHORED clause","ALTERED clause"));
            String address="wrong_projection".equals(scenario)?"a".repeat(64):binding.getProjectionSha256();
            String unit="missing_unit".equals(scenario)?"candidate-unit:"+"0".repeat(64):selected.path("unitId").asText();
            if(!"valid".equals(scenario)){
                assertThrows(CanonicalEvidenceException.class,()->wire.service.pointer("authored-compact",1L,address,unit,"vetting"),scenario);
                verify(wire.bindings,never()).findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString());
                verify(documents,never()).findNormalObservationMetadataById(anyLong());return;
            }
            com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=wire.service.pointer("authored-compact",1L,binding.getProjectionSha256(),selected.path("unitId").asText(),"vetting");
            assertEquals(selected.path("id").asText(),pointer.getCanonicalChunkId());
            assertEquals(selected.path("parts"),pointer.getParts());assertNull(pointer.getNativeWordSourceScopesContext());
            verify(wire.bindings,never()).findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString());
            verify(wire.reader,never()).normalDocument(anyString(),anyLong(),anyString());
            verify(wire.reader,never()).captureNormalSourceStamp(any(),anyString(),any());
            verify(documents,never()).findNormalObservationMetadataById(anyLong());verify(current,never()).normalDocument(anyString(),anyLong(),anyString());
        }
    }

    static String produce(CompactNormalPreparationTest.Wire wire,CanonicalSourceBinding binding,Path temporary)throws Exception {
        SourceDocument source=wire.documents.get(binding.getDocumentId());ObjectNode request=JsonUtils.mapper().createObjectNode();
        request.set("candidateGraph",JsonUtils.parse(source.getNormalCandidateJson()));request.set("sourceBinding",wire.proof(binding).path("sourceBinding"));
        Path input=temporary.resolve("request.json"),output=temporary.resolve("projection.json"),script=temporary.resolve("produce.py");
        Files.writeString(input,JsonUtils.write(request),StandardCharsets.UTF_8);Files.copy(NormalPointerStreamingTest.class.getResourceAsStream("/canonical/normal-observed/produce.py"),script);
        Process process=new ProcessBuilder(System.getProperty("consense.test.python"),script.toString(),System.getProperty("consense.test.oerTools"),input.toString(),temporary.resolve("cas").toString(),output.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("producer.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();fail("Authored producer exceeded its finite fixture allowance");}
        assertEquals(0,process.exitValue(),"Authored producer failed");return Files.readString(output,StandardCharsets.UTF_8);
    }
}
