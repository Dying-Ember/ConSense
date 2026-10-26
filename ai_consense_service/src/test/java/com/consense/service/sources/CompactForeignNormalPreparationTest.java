package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Registered foreign normal revisions retain independent module authority. */
class CompactForeignNormalPreparationTest {
    @Test void newerAdvisoryNormalRevisionKeepsTheOlderCurrentVettingCompactPreparation()throws Exception {
        try(History h=new History()){
            List<CanonicalSourceBinding> selected=h.wire.candidates();
            assertNotNull(selected,"A validated foreign normal revision must not disable the owned compact source");
            assertEquals(1L,selected.get(0).getId());AtomicInteger graphs=new AtomicInteger();
            CanonicalEvidenceService.CompactNormalPreparation prepared=h.wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(h.wire.stamps.values()),selected,(source,graph)->graphs.incrementAndGet());
            assertNotNull(prepared);assertEquals(1,graphs.get());h.wire.attest(prepared,h.wire.proofs());assertDoesNotThrow(()->h.wire.service.assertCompactCurrent(prepared.scope()));
            verify(h.wire.reader,times(1)).normalDocument("authored-compact",1L,"vetting");verifyNoInteractions(h.wire.backend);
        }
    }
    @Test void foreignReviewedFamilyAndMissingOwnedAuthorityNeverEnterCompactPreparation()throws Exception {
        for(String defect:Arrays.asList("reviewed","family","missingOwn"))try(History h=new History()){
            if("reviewed".equals(defect))h.foreign.setAdoptionManifestSha256(CompactNormalPreparationTest.Wire.hash("reviewed foreign"));
            else if("family".equals(defect))h.foreign.setFamilyCompositionSha256(CompactNormalPreparationTest.Wire.hash("foreign family"));
            else when(h.wire.bindings.findCompactByDocumentBefore(eq("authored-compact"),eq(1L),eq(2L),any())).thenReturn(Collections.emptyList());
            assertNull(h.wire.candidates(),defect);verifyNoInteractions(h.wire.reader);verifyNoInteractions(h.wire.backend);
        }
    }
    @Test void foreignNormalProofRejectsGraphGenerationSourceRoutingUnknownScopeAndModuleDrift()throws Exception {
        for(String defect:Arrays.asList("graph","generation","source","routing","unknownScope","module","extraIdentity","authority","rawBytes"))try(History h=new History()){
            ObjectNode raw=(ObjectNode)JsonUtils.parse(h.raw),identity=(ObjectNode)raw.path("projectionIdentity");
            switch(defect){
                case "graph":identity.put("candidateGraphSha256",CompactNormalPreparationTest.Wire.hash("changed graph"));break;
                case "generation":identity.put("generationSha256",CompactNormalPreparationTest.Wire.hash("changed generation"));break;
                case "source":((ObjectNode)raw.path("source")).put("filename","another-source.docx");break;
                case "routing":identity.put("routingSha256",CompactNormalPreparationTest.Wire.hash("changed routing"));break;
                case "unknownScope":h.foreign.setScopePolicySha256("unknown");identity.put("scopePolicySha256","unknown");((ObjectNode)raw.path("manifest").path("sourceBinding")).put("scopePolicySha256","unknown");break;
                case "module":identity.put("module","drafting");break;
                case "extraIdentity":identity.put("unrelatedBody","must not be retained as authority");break;
                case "authority":raw.put("humanConfirmed",true);break;
                default:raw.put("authoredBody","changed raw bytes without the committed stamp");
            }
            h.raw=JsonUtils.write(raw);if("rawBytes".equals(defect)){try(PreparedStatement s=h.wire.connection.prepareStatement("update canonical_source_binding set snapshot_json=? where id=2")){s.setString(1,h.raw);s.executeUpdate();}}else h.stampRaw();
            List<CanonicalSourceBinding> selected=h.wire.candidates();assertNotNull(selected,defect);
            assertThrows(CanonicalEvidenceException.class,()->h.wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(h.wire.stamps.values()),selected,(source,graph)->fail("Invalid foreign authority must not reach the consumer")),defect);verifyNoInteractions(h.wire.backend);
        }
    }
    @Test void foreignNonNormalFallsBackAndChangedHistoryAcrossNativeWaitCannotAttest()throws Exception {
        try(History h=new History()){
            ObjectNode changed=(ObjectNode)JsonUtils.parse(h.raw);changed.put("schema","other");changed.put("producerKind","other");h.raw=JsonUtils.write(changed);h.stampRaw();
            assertNull(h.wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(h.wire.stamps.values()),h.wire.candidates(),(source,graph)->fail("Non-normal foreign authority must fall back")));
        }
        try(History h=new History()){
            CanonicalEvidenceService.CompactNormalPreparation prepared=h.wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(h.wire.stamps.values()),h.wire.candidates(),(source,graph)->{});
            h.foreign.setProjectionRevisionSha256(CompactNormalPreparationTest.Wire.hash("changed during native wait"));assertThrows(CanonicalEvidenceException.class,()->h.wire.attest(prepared,h.wire.proofs()));
        }
    }
    @Test void foreignIdentityComparisonChecksBothModuleDirectionsAndGraphDrift()throws Exception {
        java.lang.reflect.Method method=CanonicalEvidenceService.class.getDeclaredMethod("validateCompactForeignNormal",CanonicalSourceBinding.class,com.fasterxml.jackson.databind.JsonNode.class,SourceDocument.class,com.fasterxml.jackson.databind.JsonNode.class,String.class,String.class);method.setAccessible(true);
        for(String requested:Arrays.asList("vetting","advisory"))try(History h=new History()){
            String other="vetting".equals(requested)?"advisory":"vetting";h.foreign.setDeclaredModule(other);ObjectNode header=(ObjectNode)JsonUtils.parse(h.raw);((ObjectNode)header.path("projectionIdentity")).put("module",other);ObjectNode binding=(ObjectNode)header.path("manifest").path("sourceBinding");binding.put("module",other);header.set("sourceBinding",binding);
            SourceDocument source=h.wire.documents.get(1L);com.fasterxml.jackson.databind.JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());String graphSha=CanonicalJson.nodeHash(graph);assertDoesNotThrow(()->method.invoke(null,h.foreign,header,source,graph,graphSha,requested));
            ((ObjectNode)header.path("projectionIdentity")).put("candidateGraphSha256",CompactNormalPreparationTest.Wire.hash("other graph"));assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,h.foreign,header,source,graph,graphSha,requested));
        }
    }
    static final class History implements AutoCloseable {
        final CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1);
        final CanonicalSourceBinding own=wire.rows.get(1L),foreign=new CanonicalSourceBinding();
        String raw;
        History()throws Exception {
            org.springframework.beans.BeanUtils.copyProperties(own,foreign);foreign.setId(2L);foreign.setDeclaredModule("advisory");foreign.setProjectionSha256(CompactNormalPreparationTest.Wire.hash("foreign projection"));foreign.setProjectionRevisionSha256(CompactNormalPreparationTest.Wire.hash("foreign projection revision"));foreign.setCanonicalSourceHash(CompactNormalPreparationTest.Wire.hash("foreign canonical"));foreign.setScopePolicySha256(CompactNormalPreparationTest.Wire.hash("foreign policy"));
            ObjectNode snapshot=wire.proof(foreign);snapshot.remove(Arrays.asList("snapshotSha256","parentSha256"));snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.put("adoptionStatus","candidate_unverified");snapshot.put("humanConfirmed",false);snapshot.put("sourceQualityAccepted",false);snapshot.put("semanticScopeVerified",false);snapshot.put("legalClosure","unknown");
            ((ObjectNode)snapshot.path("projectionIdentity")).put("projectionCodeSha256",CompactNormalPreparationTest.Wire.hash("foreign code"));((ObjectNode)snapshot.path("projectionIdentity")).put("observationManifestSha256",CompactNormalPreparationTest.Wire.hash("foreign manifest"));
            snapshot.putObject("manifest").set("sourceBinding",snapshot.remove("sourceBinding"));snapshot.put("authoredBody","AUTHORED body is streamed, never retained by admission. ".repeat(1024));raw=JsonUtils.write(snapshot);stampRaw();
            doAnswer(call->Collections.singletonList(CompactNormalPreparationTest.Wire.metadata(foreign))).when(wire.bindings).findCompactByDocument(eq("authored-compact"),eq(1L),any());
            when(wire.bindings.findCompactByDocumentBefore(eq("authored-compact"),eq(1L),eq(2L),any())).thenReturn(Collections.singletonList(CompactNormalPreparationTest.Wire.metadata(own)));
            when(wire.bindings.findCompactById(anyLong())).thenAnswer(call->{long id=call.getArgument(0);return Optional.of(CompactNormalPreparationTest.Wire.metadata(id==1?own:foreign));});
        }
        void stampRaw()throws Exception {
            foreign.setSnapshotBytesSha256(Utf8.sha256(raw));foreign.setSnapshotContentSha256(foreign.getSnapshotBytesSha256());
            try(PreparedStatement s=wire.connection.prepareStatement("merge into canonical_source_binding key(id) values(?,?,?,?)")){s.setLong(1,foreign.getId());s.setCharacterStream(2,new java.io.StringReader(raw));s.setString(3,foreign.getSnapshotBytesSha256());s.setString(4,foreign.getSnapshotContentSha256());s.executeUpdate();}
        }
        @Override public void close()throws Exception{wire.close();}
    }
}
