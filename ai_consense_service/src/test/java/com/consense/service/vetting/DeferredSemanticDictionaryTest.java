package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored source-contract seam only; no model, live source or business acceptance. */
class DeferredSemanticDictionaryTest {
    @Test void knownProfileAndSourceBoundTemplateGrantOnlyThePrivateNormalMode()throws Exception{
        Fixture f=new Fixture(true,true);assertTrue(f.selected.deferredNormalOrigin(f.origin,f.deferred));
        ObjectNode promoted=f.deferred.deepCopy();promoted.put("semanticScopeVerified",true);assertThrows(RuntimeException.class,()->f.selected.deferredNormalOrigin(f.origin,promoted));
        ObjectNode forged=f.deferred.deepCopy();forged.put("unitId",sha("another source"));assertThrows(RuntimeException.class,()->f.selected.deferredNormalOrigin(f.origin,forged));
        assertFalse(new Fixture(false,true).selected.deferredNormalOrigin(f.origin,f.deferred),"A public profile value without the verified capability grants no admission");
        assertFalse(new Fixture(true,false).selected.deferredNormalOrigin(f.origin,f.deferred),"The old profile does not reinterpret unknown declarations");
    }
    @Test void requestedIdsAcceptFreshCompleteGroupsWithTheirOwnIdentity()throws Exception{
        Fixture f=new Fixture(true,true);ObjectNode complete=f.complete();assertNotEquals(complete.path("unitId"),f.deferred.path("unitId"));
        assertDoesNotThrow(()->f.validate(f.response(complete)));
    }
    @Test void requestedIdsRejectADeferredPlaceholderOrMissingOrigin()throws Exception{
        Fixture f=new Fixture(true,true);assertThrows(RuntimeException.class,()->f.validate(f.response(f.deferred)));
        ObjectNode missing=f.complete();missing.putArray("requiredMemberIds");assertThrows(RuntimeException.class,()->f.validate(f.response(missing)));
    }
    @Test void requestedIdsRejectForeignGroupsAndChangedSourceRevision()throws Exception{
        Fixture f=new Fixture(true,true);ObjectNode foreign=f.complete();foreign.put("documentId","43");assertThrows(RuntimeException.class,()->f.validate(f.response(foreign)));
        ObjectNode response=f.response(f.complete());((ObjectNode)response.path("origins").get(0).path("evidenceRef")).put("sourceRevisionSha256",sha("changed revision"));
        assertThrows(RuntimeException.class,()->f.validate(response));
    }
    private static final class Fixture {
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final ObjectNode source=JsonUtils.mapper().createObjectNode(),origin=JsonUtils.mapper().createObjectNode(),deferred;
        final OerCanonicalRetrievalClient.SemanticSources selected;
        Fixture(boolean advertised,boolean requested)throws Exception{
            binding.setDocumentId(42L);binding.setProjectId("authored");binding.setProjectionSha256(sha("projection"));binding.setOriginalSourceSha256(sha("original"));binding.setCanonicalSourceHash(sha("source"));binding.setProjectionRevisionSha256(sha("projection revision"));binding.setSourceRevisionSha256(sha("source revision"));binding.setScopePolicySha256(sha("policy"));
            source.put("documentId","42");source.put("producerKind","normal_observed");source.put("sourceHash",binding.getCanonicalSourceHash());source.put("chunkCount",1);
            origin.put("id","origin-0");origin.put("unitId","source-unit-0");origin.put("documentId","42");origin.put("producerKind","normal_observed");origin.put("role","tender");origin.put("sourceHash",binding.getCanonicalSourceHash());origin.put("text","The Contractor shall retain the complete reading text.");origin.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);origin.putArray("eligibleModules").add("vetting");origin.putArray("parts");
            origin.putObject("evidenceRef").put("projectionSha256",binding.getProjectionSha256()).put("canonicalChunkId","origin-0").put("originalSourceSha256",binding.getOriginalSourceSha256()).put("module","vetting").put("canonicalSourceHash",binding.getCanonicalSourceHash()).put("projectionRevisionSha256",binding.getProjectionRevisionSha256()).put("sourceRevisionSha256",binding.getSourceRevisionSha256());
            Class<?> capability=Class.forName(OerCanonicalRetrievalClient.class.getName()+"$Capability");Constructor<?> health=capability.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);health.setAccessible(true);Object identity=health.newInstance(sha("health"),"authored source units","unused",true,true,sha("software"));ReflectionTestUtils.setField(identity,"semanticDeferredTextFirst",advertised);
            ObjectNode prepared=JsonUtils.mapper().createObjectNode();prepared.putArray("projectionSha256").add(binding.getProjectionSha256());prepared.putArray("readManifestSha256").add(sha("manifest"));prepared.put("readManifestToken",sha("token"));prepared.putArray("sources").add(source);prepared.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);prepared.put("semanticReadProfile",requested?OerCanonicalRetrievalClient.DEFERRED_TEXT_FIRST_PROFILE:OerCanonicalRetrievalClient.TEXT_FIRST_PROFILE);
            Constructor<?> constructor=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(String.class,String.class,capability,String.class,JsonNode.class,List.class,int.class);constructor.setAccessible(true);selected=(OerCanonicalRetrievalClient.SemanticSources)constructor.newInstance("vetting",binding.getScopePolicySha256(),identity,sha("runtime"),prepared,Collections.singletonList(binding),1);
            ObjectNode address=JsonUtils.mapper().createObjectNode();address.set("source",source);address.put("reason","additional_source_context_deferred");deferred=base();deferred.put("unitId",sha(JsonUtils.write(OerCanonicalSourceUnits.sorted(address))));deferred.put("status","unknown");deferred.put("reason","additional_source_context_deferred");
        }
        ObjectNode base(){ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId",sha("complete group"));unit.put("documentId","42");unit.put("kind","unknown");unit.put("status","complete_observed_unit");unit.putNull("reason");unit.putArray("requiredMemberIds");unit.putArray("blockIds");unit.putNull("clauseId");unit.put("derivedMembersCanBecomeOrigins",false);unit.putNull("memberScores");unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);unit.put("legalClosureVerified",false);return unit;}
        ObjectNode complete(){ObjectNode unit=base();unit.withArray("requiredMemberIds").add("origin-0");return unit;}
        ObjectNode response(JsonNode unit){ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);result.putObject("scopeIdentity").put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);result.putArray("origins").add(origin.deepCopy());String group=sha(JsonUtils.write(OerCanonicalSourceUnits.sorted(unit)));result.putArray("units").addObject().put("groupId",group).set("unit",unit);result.putArray("blockedOrigins");result.putObject("originUnits").put("origin-0",group);ObjectNode coverage=result.putObject("coverage");coverage.putArray("returnedOriginIds").add("origin-0");coverage.putArray("blockedOriginIds");coverage.put("totalOrigins",1);coverage.put("returnedOrigins",1);coverage.putArray("requestedOriginIds").add("origin-0");coverage.put("status","complete");result.putNull("nextCursor");return result;}
        void validate(JsonNode response){ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.putArray("originIds").add("origin-0");ReflectionTestUtils.invokeMethod(OerCanonicalRetrievalClient.class,"validateSemanticDictionary",response,demand,selected,"ids",1,sha("scope"));}
    }
    private static String sha(String value){return VettingCorpus.hash(value);}
}
