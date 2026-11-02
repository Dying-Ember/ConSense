package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored wire and mocks exercise initialization decisions, not a live index. */
class OerInitializationDecisionTest {
    private static final String KEY="test-canonical";
    private static final String GENERATION="a".repeat(64);

    private static final class Wire {
        final Object original;
        final HttpSupport http;
        final OerCanonicalRetrievalClient client;
        final CanonicalSourceBinding binding;
        final ObjectNode snapshot,index,health;
        Wire() throws Exception {
            Class<?> type=Class.forName("com.consense.service.vetting.OerCanonicalRetrievalClientTest$Wire");
            Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);original=constructor.newInstance();
            http=(HttpSupport)field("http");client=(OerCanonicalRetrievalClient)field("client");
            binding=(CanonicalSourceBinding)field("binding");snapshot=(ObjectNode)field("snapshot");
            index=(ObjectNode)field("index");health=(ObjectNode)field("health");
            binding.setScopePolicySha256(snapshot.path("manifest").path("scope").path("scopePolicySha256").asText());
            ((ObjectNode)health.path("oer")).put("indexAvailabilityContract","live-canonical-index-availability-v1")
                    .put("indexAvailabilityBootstrapContract","source-verified-cold-canonical-availability-v1");
        }
        Object field(String name) throws Exception {Field f=original.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(original);}
        void advisory() throws Exception {java.lang.reflect.Method method=original.getClass().getDeclaredMethod("advisory");method.setAccessible(true);method.invoke(original);}
        ObjectNode decision(){
            ObjectNode result=JsonUtils.mapper().createObjectNode();
            result.put("initializationContract","consense.canonical-index-initialization.v1");
            result.put("projectId",KEY);result.put("targetModule","vetting");result.put("ready",false);
            result.put("readOnly",true);result.put("initializationAllowed",true);
            result.set("canonicalProjections",index.path("canonicalProjections").deepCopy());
            result.putObject("sourcePageEvidence");result.set("competitionScope",index.path("competitionScope").deepCopy());
            return result;
        }
        CanonicalSourceBinding compact(){CanonicalSourceBinding result=new CanonicalSourceBinding();BeanUtils.copyProperties(binding,result);result.setSnapshotJson(null);return result;}
        int chunks(){return snapshot.path("chunks").size();}
        void status(ObjectNode value){when(http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenReturn(JsonUtils.write(value));}
        void availability(ObjectNode value){when(http.postJson(endsWith("/index-canonical/availability"),anyString(),anyLong())).thenReturn(JsonUtils.write(value));}
    }

    @Test void explicitBoundDecisionAllowsAllThreeStatusPaths() throws Exception {
        Wire ordinary=new Wire();ordinary.status(ordinary.decision());
        OerCanonicalRetrievalClient.IndexState a=ordinary.client.indexStatus(KEY,"vetting",Collections.singletonList(ordinary.binding));
        assertFalse(a.ready);assertTrue(a.initializationAllowed);
        assertEquals(OerCanonicalRetrievalClient.InitializationState.INITIALIZATION_ALLOWED,a.initializationState);
        verify(ordinary.http).postJson(endsWith("/index-canonical/status"),contains("\"initializationDecision\":true"),anyLong());

        Wire compact=new Wire();compact.status(compact.decision());
        OerCanonicalRetrievalClient.IndexState b=compact.client.compactIndex("vetting",KEY,Collections.singletonList(compact.compact()),compact.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false);
        assertFalse(b.ready);assertTrue(b.initializationAllowed);
        verify(compact.http).postJson(endsWith("/index-canonical/status"),contains("\"initializationDecision\":true"),anyLong());

        Wire cold=new Wire();cold.availability(cold.decision());
        OerCanonicalRetrievalClient.IndexState c=cold.client.compactRecoveryAvailability("vetting",KEY,Collections.singletonList(cold.compact()),cold.chunks(),GENERATION,()->{});
        assertFalse(c.ready);assertTrue(c.initializationAllowed);
        verify(cold.http).postJson(endsWith("/index-canonical/availability"),contains("\"initializationDecision\":true"),anyLong());
    }

    @Test void typedHttpFailuresClassifyStatusWithoutExposingResponseBodiesInIndexStateReason() throws Exception {
        assertEquals(OerCanonicalRetrievalClient.InitializationState.UNKNOWN,new OerCanonicalRetrievalClient.IndexState(false,null,0,"legacy").initializationState);
        for(String kind:java.util.Arrays.asList("QDRANT_BACKEND_UNAVAILABLE","QDRANT_BACKEND_RELEASE_UNKNOWN","UNRECOGNIZED","old404","old422")){
            int code="old404".equals(kind)?404:"old422".equals(kind)?422:503;
            String body="{\"detail\":{\"kind\":\""+kind+"\",\"message\":\"AUTHORED_BODY_MUST_NOT_BE_REASON\"}}";
            com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);try(java.io.OutputStream output=exchange.getResponseBody()){output.write(bytes);}});
            server.start();
            try{
                String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/status";HttpSupport actual=new HttpSupport();
                Wire ordinary=new Wire();when(ordinary.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->actual.postJson(endpoint,call.getArgument(1),call.getArgument(2)));
                OerCanonicalRetrievalClient.IndexState first=ordinary.client.indexStatus(KEY,"vetting",Collections.singletonList(ordinary.binding));
                Wire compact=new Wire();when(compact.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->actual.postJson(endpoint,call.getArgument(1),call.getArgument(2)));
                OerCanonicalRetrievalClient.IndexState second=compact.client.compactIndex("vetting",KEY,Collections.singletonList(compact.compact()),compact.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false);
                OerCanonicalRetrievalClient.InitializationState expected="QDRANT_BACKEND_UNAVAILABLE".equals(kind)?OerCanonicalRetrievalClient.InitializationState.DATABASE_UNAVAILABLE:OerCanonicalRetrievalClient.InitializationState.UNKNOWN;
                for(OerCanonicalRetrievalClient.IndexState state:java.util.Arrays.asList(first,second)){
                    assertFalse(state.ready);assertFalse(state.initializationAllowed);assertEquals(expected,state.initializationState);
                    assertEquals(expected==OerCanonicalRetrievalClient.InitializationState.DATABASE_UNAVAILABLE?"canonical_index_database_unavailable":"canonical_index_status_unknown",state.reason);
                }
                Wire cold=new Wire();when(cold.http.postJson(endsWith("/index-canonical/availability"),anyString(),anyLong())).thenAnswer(call->actual.postJson(endpoint,call.getArgument(1),call.getArgument(2)));
                if(kind.startsWith("QDRANT_BACKEND_")){
                    OerCanonicalRetrievalClient.IndexState state=cold.client.compactRecoveryAvailability("vetting",KEY,Collections.singletonList(cold.compact()),cold.chunks(),GENERATION,()->{});
                    assertFalse(state.ready);assertFalse(state.initializationAllowed);assertEquals(expected,state.initializationState);
                    assertEquals(expected==OerCanonicalRetrievalClient.InitializationState.DATABASE_UNAVAILABLE?"canonical_index_database_unavailable":"canonical_index_status_unknown",state.reason);
                }else assertThrows(HttpSupport.HttpResponseFailure.class,()->cold.client.compactRecoveryAvailability("vetting",KEY,Collections.singletonList(cold.compact()),cold.chunks(),GENERATION,()->{}));
            }finally{server.stop(0);}
        }
    }

    @Test void malformedOrUnboundDecisionsNeverAuthorizeInitialization() throws Exception {
        for(Consumer<ObjectNode> mutate: java.util.Arrays.<Consumer<ObjectNode>>asList(
                r->r.put("initializationAllowed",false),r->r.put("initializationAllowed","true"),
                r->r.put("ready",true),r->r.put("readOnly",false),r->r.put("projectId","other"),
                r->r.put("targetModule","advisory"),r->r.putArray("canonicalProjections").add("other"),
                r->r.remove("sourcePageEvidence"),r->r.putObject("sourcePageEvidence").put("other","a".repeat(64)),
                r->((ObjectNode)r.path("competitionScope")).put("targetModule","advisory"),
                r->r.put("initializationContract","unknown"))){
            Wire wire=new Wire();ObjectNode value=wire.decision();mutate.accept(value);wire.status(value);
            OerCanonicalRetrievalClient.IndexState state=wire.client.indexStatus(KEY,"vetting",Collections.singletonList(wire.binding));
            assertFalse(state.ready);assertFalse(state.initializationAllowed);
        }
    }

    @Test void old404AndUnavailabilityDoNotBecomeInitializationPermission() throws Exception {
        for(String message:java.util.Arrays.asList("HTTP 404 Project is not indexed","HTTP 503 database unavailable","HTTP 422 MIGRATION_ROUTE_UNAVAILABLE")){
            Wire wire=new Wire();when(wire.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenThrow(new BizException(message));
            OerCanonicalRetrievalClient.IndexState state=wire.client.indexStatus(KEY,"vetting",Collections.singletonList(wire.binding));
            assertFalse(state.ready);assertFalse(state.initializationAllowed);
            Wire compact=new Wire();when(compact.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenThrow(new BizException(message));
            assertFalse(compact.client.compactIndex("vetting",KEY,Collections.singletonList(compact.compact()),compact.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false).initializationAllowed);
            Wire cold=new Wire();when(cold.http.postJson(endsWith("/index-canonical/availability"),anyString(),anyLong())).thenThrow(new BizException(message));
            assertThrows(BizException.class,()->cold.client.compactRecoveryAvailability("vetting",KEY,Collections.singletonList(cold.compact()),cold.chunks(),GENERATION,()->{}));
        }
    }

    @Test void compactAndColdRejectAnInitializationDecisionForAnotherProject() throws Exception {
        Wire compact=new Wire();ObjectNode invalid=compact.decision();invalid.put("projectId","other");compact.status(invalid);
        assertFalse(compact.client.compactIndex("vetting",KEY,Collections.singletonList(compact.compact()),compact.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false).initializationAllowed);
        Wire cold=new Wire();ObjectNode bad=cold.decision();bad.put("projectId","other");cold.availability(bad);
        assertThrows(IllegalStateException.class,()->cold.client.compactRecoveryAvailability("vetting",KEY,Collections.singletonList(cold.compact()),cold.chunks(),GENERATION,()->{}));
    }

    @Test void initializationDecisionCannotReplaceAnExistingLocalReceipt() throws Exception {
        Wire wire=new Wire();wire.client.index(KEY,"vetting",Collections.singletonList(wire.binding));wire.status(wire.decision());
        OerCanonicalRetrievalClient.IndexState state=wire.client.indexStatus(KEY,"vetting",Collections.singletonList(wire.binding));
        assertFalse(state.ready);assertFalse(state.initializationAllowed);
    }

    @Test void initializationDecisionCannotSurviveSourceIdentityChangingDuringStatus() throws Exception {
        Wire wire=new Wire();ObjectNode decision=wire.decision();
        when(wire.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{
            wire.binding.setRoutingSha256("b".repeat(64));return JsonUtils.write(decision);
        });
        OerCanonicalRetrievalClient.IndexState state=wire.client.indexStatus(KEY,"vetting",Collections.singletonList(wire.binding));
        assertFalse(state.ready);assertFalse(state.initializationAllowed);
    }

    @Test void automaticBuildsCarryTheFlagAndManualBuildsDoNot() throws Exception {
        Wire ordinary=new Wire();ordinary.client.initialize(KEY,"vetting",Collections.singletonList(ordinary.binding));
        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(ordinary.http).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertTrue(JsonUtils.parse(body.getValue()).path("initializationDecision").asBoolean());

        Wire compact=new Wire();compact.client.compactInitialize("vetting",KEY,Collections.singletonList(compact.compact()),compact.chunks(),Collections.emptySet(),GENERATION,()->{},()->{});
        verify(compact.http).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertTrue(JsonUtils.parse(body.getValue()).path("initializationDecision").asBoolean());

        Wire advisory=new Wire();advisory.advisory();
        advisory.client.advisoryCompactInitialize(KEY,Collections.singletonList(advisory.compact()),advisory.chunks(),Collections.emptySet(),GENERATION,()->{},()->{});
        verify(advisory.http).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertTrue(JsonUtils.parse(body.getValue()).path("initializationDecision").asBoolean());

        Wire manual=new Wire();manual.client.index(KEY,"vetting",Collections.singletonList(manual.binding));
        verify(manual.http).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertFalse(JsonUtils.parse(body.getValue()).has("initializationDecision"));
        Wire rebuild=new Wire();rebuild.client.rebuild(KEY,"vetting",Collections.singletonList(rebuild.binding));
        verify(rebuild.http).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertFalse(JsonUtils.parse(body.getValue()).has("initializationDecision"));assertTrue(JsonUtils.parse(body.getValue()).path("rebuild").asBoolean());
    }

    @Test void failedAdvisoryRebuildCanInitializeOnlyAfterFreshBoundMissingDecision() throws Exception {
        Wire wire=new Wire();wire.advisory();CanonicalSourceBinding compact=wire.compact();
        when(wire.http.postJson(endsWith("/index-canonical"),anyString(),anyLong()))
                .thenThrow(new BizException("HTTP 422 uninitialized"))
                .thenAnswer(call->JsonUtils.write(wire.index));
        assertThrows(BizException.class,()->wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},true,true));
        assertFalse(wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false).ready,
                "An invalidated receipt must not become ready merely because the remote status says ready");
        ObjectNode decision=wire.decision();decision.put("targetModule","advisory");
        ((ObjectNode)decision.path("competitionScope")).put("targetModule","advisory");
        ObjectNode wrong=decision.deepCopy();wrong.put("projectId","other");wire.status(wrong);
        assertFalse(wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false).initializationAllowed);
        wire.status(decision);
        assertTrue(wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},false,false).initializationAllowed);
        assertTrue(wire.client.advisoryCompactInitialize(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{}).ready);
        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(wire.http,times(2)).postJson(endsWith("/index-canonical"),body.capture(),anyLong());
        assertTrue(JsonUtils.parse(body.getAllValues().get(0)).path("rebuild").asBoolean());
        assertTrue(JsonUtils.parse(body.getAllValues().get(1)).path("initializationDecision").asBoolean());
    }

    @Test void invalidatedAdvisoryReceiptRecoversVerifiedExistingIndexWithoutRebuild() throws Exception {
        Wire wire=new Wire();wire.advisory();CanonicalSourceBinding compact=wire.compact();
        when(wire.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenThrow(new BizException("HTTP 422 interrupted build"));
        assertThrows(BizException.class,()->wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},true,true));
        ObjectNode available=wire.index.deepCopy();available.put("ready",true);available.put("readOnly",true);
        available.put("fullVectorIntegrityChecked",false);available.put("coldBootstrap",true);
        available.put("indexAvailabilityContract","live-canonical-index-availability-v1");
        available.put("vectorPoints",wire.chunks());available.put("vectorDimension",2);available.put("collection","authored-existing-index");
        available.put("indexGeneration","a".repeat(32));
        ObjectNode wrong=available.deepCopy();wrong.put("projectId","other");wire.availability(wrong);
        assertThrows(IllegalStateException.class,()->wire.client.advisoryRecoveryAvailability(KEY,Collections.singletonList(compact),wire.chunks(),GENERATION,()->{}));
        wire.availability(available);
        OerCanonicalRetrievalClient.IndexState recovered=wire.client.advisoryRecoveryAvailability(KEY,Collections.singletonList(compact),wire.chunks(),GENERATION,()->{});
        assertTrue(recovered.ready);assertEquals(wire.index.path("signature").asText(),recovered.signature);
        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(wire.http,times(2)).postJson(endsWith("/index-canonical/availability"),body.capture(),anyLong());
        assertTrue(JsonUtils.parse(body.getAllValues().get(1)).path("coldBootstrap").asBoolean());
        available.put("indexGeneration","b".repeat(32));wire.availability(available);
        assertFalse(wire.client.indexAvailable(KEY,"advisory",Collections.singletonList(compact),wire.chunks()).ready,
                "A different live index generation must not reuse the recovered receipt");
        verify(wire.http,times(1)).postJson(endsWith("/index-canonical"),anyString(),anyLong());
    }

    @Test void invalidatedPreviouslyAcceptedIndexMustMatchItsSignatureAndGeneration() throws Exception {
        Wire wire=new Wire();wire.advisory();CanonicalSourceBinding compact=wire.compact();
        wire.client.advisoryCompactInitialize(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{});
        ObjectNode available=wire.index.deepCopy();available.put("ready",true);available.put("readOnly",true);
        available.put("fullVectorIntegrityChecked",false);available.put("coldBootstrap",false);
        available.put("indexAvailabilityContract","live-canonical-index-availability-v1");
        available.put("vectorPoints",wire.chunks());available.put("vectorDimension",2);available.put("collection","authored-existing-index");
        available.put("indexGeneration","a".repeat(32));wire.availability(available);
        assertTrue(wire.client.indexAvailable(KEY,"advisory",Collections.singletonList(compact),wire.chunks()).ready);
        when(wire.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenThrow(new BizException("HTTP 422 interrupted rebuild"));
        assertThrows(BizException.class,()->wire.client.advisoryCompactIndex(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{},true,true));
        available.put("indexGeneration","b".repeat(32));wire.availability(available);
        assertThrows(IllegalStateException.class,()->wire.client.advisoryRecoveryAvailability(KEY,Collections.singletonList(compact),wire.chunks(),GENERATION,()->{}));
        available.put("indexGeneration","a".repeat(32));available.put("signature","b".repeat(64));wire.availability(available);
        assertThrows(IllegalStateException.class,()->wire.client.advisoryRecoveryAvailability(KEY,Collections.singletonList(compact),wire.chunks(),GENERATION,()->{}));
        available.put("signature",wire.index.path("signature").asText());wire.availability(available);
        assertTrue(wire.client.advisoryRecoveryAvailability(KEY,Collections.singletonList(compact),wire.chunks(),GENERATION,()->{}).ready);
        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(wire.http,times(4)).postJson(endsWith("/index-canonical/availability"),body.capture(),anyLong());
        JsonNode request=JsonUtils.parse(body.getAllValues().get(3));
        assertFalse(request.path("coldBootstrap").asBoolean());assertEquals(wire.index.path("signature").asText(),request.path("expectedSignature").asText());
        assertEquals("a".repeat(32),request.path("expectedGeneration").asText());
    }

    @Test void advisoryRetrievalRegistersRequestCancellationWithoutInvalidatingIndex() throws Exception {
        Wire wire=new Wire();wire.advisory();CanonicalSourceBinding compact=wire.compact();
        wire.client.advisoryCompactInitialize(KEY,Collections.singletonList(compact),wire.chunks(),Collections.emptySet(),GENERATION,()->{},()->{});
        java.util.concurrent.atomic.AtomicReference<Runnable> registered=new java.util.concurrent.atomic.AtomicReference<>();
        when(wire.http.postJsonCancelable(endsWith("/retrieve"),anyString(),anyLong(),any())).thenAnswer(call->{
            java.util.function.Consumer<Runnable> onCancel=call.getArgument(3);onCancel.accept(()->{});
            throw new java.util.concurrent.CancellationException("AUTHORED cancellation");
        });
        assertThrows(java.util.concurrent.CancellationException.class,()->wire.client.retrieveAdvisory(KEY,"question",Collections.singletonList(compact),GENERATION,()->{},ids->null,null,registered::set));
        assertNotNull(registered.get());assertTrue(wire.client.sourceGenerationLinked("advisory",KEY,GENERATION));
        verify(wire.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }

    @Test void ensureIndexOnlyBuildsAfterExplicitPermission() throws Exception {
        OerBusinessEvidenceTest.Wire business=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=business.load();
        Wire wire=new Wire();ObjectNode decision=wire.decision();decision.put("projectId",session.indexKey);wire.status(decision);
        OerCanonicalRetrievalClient.IndexState permitted=wire.client.indexStatus(session.indexKey,"vetting",Collections.singletonList(wire.binding));
        assertTrue(permitted.initializationAllowed);
        OerBusinessEvidence subject=spy(business.service);
        doReturn(new OerCanonicalRetrievalClient.IndexState(false,null,0,"unknown")).when(subject).indexStatus(session);
        assertThrows(IllegalStateException.class,()->subject.ensureIndex(session));
        verify(business.client,never()).initialize(anyString(),eq("vetting"),anyList());
        doReturn(permitted).when(subject).indexStatus(session);subject.ensureIndex(session);
        verify(business.client).initialize(anyString(),eq("vetting"),anyList());
        verify(business.client,never()).index(anyString(),eq("vetting"),anyList());
    }
}
