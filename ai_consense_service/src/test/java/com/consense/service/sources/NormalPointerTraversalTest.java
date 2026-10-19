package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A sealed normal root is classified once at its boundary, never rescanned in full for each address. */
class NormalPointerTraversalTest {
    @Test void verifiedNormalUnknownsReadsOnlyTheDeclaredRootField()throws Exception{
        Fixture f=new Fixture();Counter counter=new Counter(f.snapshot);
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("unknowns",JsonNode.class);method.setAccessible(true);
        JsonNode selected=(JsonNode)method.invoke(null,counter.root);
        assertEquals(f.snapshot.path("unknowns"),selected);
        assertEquals(0,counter.rootTraversals.get(),"Reading normal unknowns must not recursively classify the complete source/manifest tree again");
    }

    @Test void verifiedNormalSinglePointerDoesNotTraverseItsCompleteSnapshotRoot()throws Exception{
        Fixture f=new Fixture();Counter counter=new Counter(f.snapshot);
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);
        JsonNode chunk=f.snapshot.path("chunks").get(0);EvidencePointer pointer=(EvidencePointer)method.invoke(null,f.binding,counter.root,chunk,"vetting");
        assertEquals(chunk.path("parts"),pointer.getParts());assertEquals(f.snapshot.path("unknowns"),pointer.getUnknowns());
        assertEquals(NormalObservedContract.PRODUCER,pointer.getProducerKind());assertNull(pointer.getNativeWordSourceScopesContext());
        assertEquals(0,counter.rootTraversals.get(),"Pointer construction may copy selected parts but must not walk every source unit to rediscover the verified normal producer");
    }

    @Test void verifiedNormalBulkPointersDoNotMultiplyCompleteSnapshotTraversalByChunkCount()throws Exception{
        Fixture f=new Fixture();CanonicalEvidenceService owner=mock(CanonicalEvidenceService.class);
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");
        Constructor<?> viewConstructor=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);viewConstructor.setAccessible(true);
        Constructor<?> constructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);constructor.setAccessible(true);
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)constructor.newInstance(owner,owner,f.binding.getProjectId(),"vetting",Collections.singletonList(viewConstructor.newInstance(f.binding,f.snapshot,false)))){
            Field views=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredField("views");views.setAccessible(true);
            Object held=((Map<?,?>)views.get(frame)).get(f.binding.getDocumentId());Field snapshot=viewType.getDeclaredField("snapshot");snapshot.setAccessible(true);
            Counter counter=new Counter((ObjectNode)snapshot.get(held));snapshot.set(held,counter.root);
            List<EvidencePointer> pointers=frame.pointers(f.binding.getDocumentId(),f.binding.getProjectionSha256(),"vetting");
            assertEquals(f.snapshot.path("chunks").size(),pointers.size());
            for(int i=0;i<pointers.size();i++)assertEquals(f.snapshot.path("chunks").get(i).path("parts"),pointers.get(i).getParts());
            assertEquals(0,counter.rootTraversals.get(),"A verified normal bulk enumeration must be linear in selected pointer payload, without full-root family/PDF searches per member");
        }
    }

    @Test void globalForeignAndPartialMarkerDetectorsStillInspectNestedUnverifiedObjects(){
        assertTrue(NativePdfSourceFamilyContract.isFamily(JsonUtils.parse("{\"nested\":[{\"sourceFamilyOriginRef\":{}}]}")));
        assertTrue(NativePdfCanonicalContract.isNativePdf(JsonUtils.parse("{\"nested\":[{\"nativeGlyphMembershipRef\":{}}]}")));
        ObjectNode mixed=JsonUtils.mapper().createObjectNode();mixed.put("schema",NormalObservedContract.SCHEMA);
        mixed.putObject("untrustedNested").putObject("sourceFamilyOriginRef");assertTrue(NativePdfSourceFamilyContract.isFamily(mixed));
        mixed.putObject("untrustedPdf").putObject("nativeTextSpansRef");assertTrue(NativePdfCanonicalContract.isNativePdf(mixed));
    }

    private static final class Counter {
        final ObjectNode root;final AtomicInteger rootTraversals=new AtomicInteger();
        Counter(ObjectNode actual){root=spy(actual);doAnswer(call->{rootTraversals.incrementAndGet();return actual.elements();}).when(root).elements();}
    }
    private static final class Fixture {
        final String raw=NormalObservedContractTest.resource("authored-projection.json");
        final ObjectNode snapshot;final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        Fixture()throws Exception{
            JsonNode preliminary=JsonUtils.parse(raw);snapshot=(ObjectNode)CanonicalEvidenceService.validateSnapshot(raw,preliminary.path("projectionSha256").asText());
            JsonNode identity=snapshot.path("projectionIdentity"),source=snapshot.path("manifest").path("candidateGraph").path("source");
            binding.setProjectId(source.path("projectId").asText());binding.setDocumentId(Long.valueOf(source.path("documentId").asText()));binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(source.path("originalSourceSha256").asText());binding.setJavaParsedSourceHash(source.path("parsedSourceHash").asText());
            binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());binding.setSourceRevisionSha256(identity.path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());binding.setSnapshotJson(raw);
        }
    }
}
