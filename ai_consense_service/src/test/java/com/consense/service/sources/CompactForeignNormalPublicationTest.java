package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline authored producer compatibility, separate from business and source-quality acceptance. */
class CompactForeignNormalPublicationTest {
    @TempDir Path temporary;
    @Test void completeRegisteredPythonPublicationsShareOneCurrentGraphAcrossTheForeignHistory()throws Exception {
        try(CompactForeignNormalPreparationTest.History h=new CompactForeignNormalPreparationTest.History()){
            for(CanonicalSourceBinding row:Arrays.asList(h.own,h.foreign)){
                String raw=produce(h.wire,row);JsonNode projection=NormalObservedContract.validate(raw,JsonUtils.parse(raw).path("projectionSha256").asText());JsonNode identity=projection.path("projectionIdentity");
                row.setProjectionSha256(projection.path("projectionSha256").asText());row.setCanonicalSourceHash(projection.path("sourceHash").asText());row.setProjectionRevisionSha256(projection.path("projectionRevisionSha256").asText());row.setSourceRevisionSha256(identity.path("sourceRevisionSha256").asText());row.setSnapshotBytesSha256(Utf8.sha256(raw));row.setSnapshotContentSha256(row.getSnapshotBytesSha256());CanonicalEvidenceService.validateBindingPins(row,projection);
                try(PreparedStatement statement=h.wire.connection.prepareStatement("merge into canonical_source_binding key(id) values(?,?,?,?)")){statement.setLong(1,row.getId());statement.setCharacterStream(2,new java.io.StringReader(raw));statement.setString(3,row.getSnapshotBytesSha256());statement.setString(4,row.getSnapshotContentSha256());statement.executeUpdate();}
            }
            List<CanonicalSourceBinding> selected=h.wire.candidates();assertNotNull(selected);assertEquals(h.own.getId(),selected.get(0).getId());
            CanonicalEvidenceService.CompactNormalPreparation prepared=h.wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(h.wire.stamps.values()),selected,(source,graph)->{});assertNotNull(prepared);h.wire.attest(prepared,h.wire.proofs());assertDoesNotThrow(()->h.wire.service.assertCompactCurrent(prepared.scope()));
            verify(h.wire.reader,times(1)).normalDocument("authored-compact",1L,"vetting");verifyNoInteractions(h.wire.backend);
        }
    }
    private String produce(CompactNormalPreparationTest.Wire wire,CanonicalSourceBinding binding)throws Exception {
        String python=System.getProperty("consense.test.python"),tools=System.getProperty("consense.test.oerTools");assertNotNull(python);assertNotNull(tools);
        Path directory=Files.createDirectory(temporary.resolve(binding.getDeclaredModule())),input=directory.resolve("request.json"),output=directory.resolve("projection.json"),script=directory.resolve("produce.py");
        SourceDocument source=wire.documents.get(binding.getDocumentId());ObjectNode request=JsonUtils.mapper().createObjectNode();request.set("candidateGraph",JsonUtils.parse(source.getNormalCandidateJson()));request.set("sourceBinding",wire.proof(binding).path("sourceBinding"));Files.writeString(input,JsonUtils.write(request),StandardCharsets.UTF_8);
        Files.copy(getClass().getResourceAsStream("/canonical/normal-observed/produce.py"),script);Process process=new ProcessBuilder(python,script.toString(),tools,input.toString(),directory.resolve("cas").toString(),output.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("producer.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();fail("Authored offline producer exceeded its finite fixture allowance");}
        assertEquals(0,process.exitValue(),()->"Offline authored producer failed; inspect "+directory.resolve("producer.log"));return Files.readString(output,StandardCharsets.UTF_8);
    }
}
