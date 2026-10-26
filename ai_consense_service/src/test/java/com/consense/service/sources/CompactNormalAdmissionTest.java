package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.StringReader;
import java.io.Reader;
import java.io.FilterReader;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy canonical field order remains admissible without a larger display-read budget. */
class CompactNormalAdmissionTest {
    @Test void sortedNormalPublicationCanBeAdmittedWithoutTheDisplayHeaderPrefix()throws Exception {
        String raw=sortedNormalPublication();
        assertTrue(raw.indexOf("\"schema\":\"consense.normal-observed.v1\"")>16384);
        assertThrows(java.io.IOException.class,()->CanonicalEvidenceService.normalDisplayHeader(new StringReader(raw)));
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            replaceStampedRaw(wire,raw);
            assertNotNull(wire.candidates(),"A valid sorted normal snapshot must not silently lose compact preparation because its authority header is late");
            verifyNoInteractions(wire.reader);verifyNoInteractions(wire.backend);
        }
    }

    @Test void preparationParsesLateAuthorityAndHashesEveryCharacterThroughOneRawStream()throws Exception {
        String raw=sortedNormalPublication();
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            replaceStampedRaw(wire,raw);AtomicInteger streams=new AtomicInteger();AtomicLong characters=new AtomicLong();countRawStreams(wire,streams,characters);
            List<String> reasons=new ArrayList<>();
            assertNotNull(wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(wire.stamps.values()),wire.candidates(),(source,graph)->{},(reason,count)->reasons.add(reason+":"+count)));
            assertEquals(1,streams.get());assertEquals(raw.length(),characters.get());assertEquals(Collections.singletonList("native_proof_pending:1"),reasons);
            verify(wire.reader,times(1)).normalDocument("authored-compact",1L,"vetting");
        }
    }

    @Test void unsupportedAuthorityFallsBackOnlyAfterCompleteRawProofAndBeforeReadingThatSource()throws Exception {
        String raw="{\"body\":\""+"AUTHORED Unicode 😀 ".repeat(2048)+"\",\"producerKind\":\"native_docx\",\"schema\":\"consense.native-docx.v1\"}  \n";
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            replaceStampedRaw(wire,raw);List<String> reasons=new ArrayList<>();AtomicInteger streams=new AtomicInteger();AtomicLong characters=new AtomicLong();countRawStreams(wire,streams,characters);
            assertNull(wire.service.prepareCompactNormalSources("authored-compact","vetting",new ArrayList<>(wire.stamps.values()),wire.candidates(),(source,graph)->fail("Unsupported authority must not consume the current source"),(reason,count)->reasons.add(reason+":"+count)));
            assertEquals(Collections.singletonList("stored_authority_not_normal:0"),reasons);assertEquals(1,streams.get());assertEquals(raw.length(),characters.get());verifyNoInteractions(wire.reader);
        }
    }

    @Test void malformedDuplicateAndTrailingJsonCannotHideBehindAnEarlyNormalHeader()throws Exception {
        String header="\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\"";
        for(String raw:Arrays.asList("{"+header+",\"body\":[}","{"+header+",\"schema\":\"consense.normal-observed.v1\"}","{"+header+",\"body\":{\"a\":1,\"a\":2}}","{"+header+"} {}","[]","{\"schema\":3,\"producerKind\":\"normal_observed\"}"))try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            replaceStampedRaw(wire,raw);
            assertEquals("CANONICAL_JSON_INVALID",assertThrows(CanonicalEvidenceException.class,()->wire.prepare((source,graph)->fail("Invalid raw JSON must not consume a current source"))).getMessage());verifyNoInteractions(wire.reader);
        }
    }

    @Test void rawByteMismatchIsRejectedEvenWhenItsAuthorityWouldOtherwiseFallBack()throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            replaceStampedRaw(wire,"{\"schema\":\"other\",\"producerKind\":\"other\",\"body\":\"AUTHORED original\"}");
            String changed="{\"schema\":\"other\",\"producerKind\":\"other\",\"body\":\"AUTHORED changed\"}";wire.replaceRaw(1L,changed);
            AtomicInteger streams=new AtomicInteger();AtomicLong characters=new AtomicLong();countRawStreams(wire,streams,characters);
            assertEquals("CANONICAL_STORED_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->wire.prepare((source,graph)->fail("Changed bytes must not consume a current source"))).getMessage());
            assertEquals(1,streams.get());assertEquals(changed.length(),characters.get());verifyNoInteractions(wire.reader);
        }
    }

    @Test void scalarAdmissionReasonReportsTheFirstUnsupportedPrefixWithoutReadingBodies()throws Exception {
        Map<String,String> expected=new LinkedHashMap<>();expected.put("legacySource","legacy_source_stamp");expected.put("missing","missing_binding");expected.put("foreign","foreign_latest_binding");expected.put("reviewed","reviewed_binding");expected.put("family","family_binding");expected.put("legacyBinding","legacy_binding_stamp");
        for(Map.Entry<String,String> test:expected.entrySet())try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(2)){
            CanonicalSourceBinding row=wire.rows.get(2L);
            switch(test.getKey()){
                case "legacySource":wire.stamps.get(2L).setBusinessContentSha256(null);break;
                case "missing":wire.rows.remove(2L);break;
                case "foreign":row.setDeclaredModule("advisory");break;
                case "reviewed":row.setReviewReferenceSha256(CompactNormalPreparationTest.Wire.hash("AUTHORED review"));break;
                case "family":row.setFamilyCompositionSha256(CompactNormalPreparationTest.Wire.hash("AUTHORED family"));break;
                default:row.setSnapshotContentSha256(null);
            }
            List<String> reasons=new ArrayList<>();assertNull(wire.service.compactNormalCandidates("authored-compact","vetting",new ArrayList<>(wire.stamps.values()),(reason,count)->reasons.add(reason+":"+count)));
            assertEquals(Collections.singletonList(test.getValue()+":1"),reasons);verifyNoInteractions(wire.reader);verifyNoInteractions(wire.backend);
        }
    }

    private static void countRawStreams(CompactNormalPreparationTest.Wire wire,AtomicInteger streams,AtomicLong characters)throws Exception {
        EntityManager manager=mock(EntityManager.class);Session session=mock(Session.class);when(manager.unwrap(Session.class)).thenReturn(session);
        Connection observed=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=call(wire.connection,method,args);
            if("prepareStatement".equals(method.getName())&&args.length>0&&"select snapshot_json from canonical_source_binding where id=?".equals(args[0])){
                PreparedStatement statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(statementProxy,statementMethod,statementArgs)->{
                    Object queryResult=call(statement,statementMethod,statementArgs);
                    if(!"executeQuery".equals(statementMethod.getName()))return queryResult;
                    ResultSet rows=(ResultSet)queryResult;
                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rowsProxy,rowsMethod,rowsArgs)->{
                        if("getString".equals(rowsMethod.getName())&&Integer.valueOf(1).equals(rowsArgs[0]))throw new AssertionError("The raw snapshot must never be materialized as a String");
                        Object value=call(rows,rowsMethod,rowsArgs);
                        if(!"getCharacterStream".equals(rowsMethod.getName()))return value;
                        streams.incrementAndGet();return new FilterReader((Reader)value){
                            @Override public int read(char[] buffer,int offset,int length)throws IOException{int count=super.read(buffer,offset,length);if(count>0)characters.addAndGet(count);return count;}
                            @Override public int read()throws IOException{int value=super.read();if(value>=0)characters.incrementAndGet();return value;}
                        };
                    });
                });
            }
            return result;
        });
        when(session.doReturningWork(any())).thenAnswer(invocation->((ReturningWork<?>)invocation.getArgument(0)).execute(observed));ReflectionTestUtils.setField(wire.service,"entityManager",manager);
    }
    private static Object call(Object target,Method method,Object[] arguments)throws Throwable{try{return method.invoke(target,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}}

    static String sortedNormalPublication()throws Exception {
        String raw=CanonicalJson.nodeWire(JsonUtils.parse(NormalObservedContractTest.resource("authored-projection.json")));
        NormalObservedContract.validate(raw,JsonUtils.parse(raw).path("projectionSha256").asText());
        return raw;
    }
    static void replaceStampedRaw(CompactNormalPreparationTest.Wire wire,String raw)throws Exception {
        CanonicalSourceBinding row=wire.rows.get(1L);row.setSnapshotBytesSha256(Utf8.sha256(raw));row.setSnapshotContentSha256(row.getSnapshotBytesSha256());
        try(PreparedStatement statement=wire.connection.prepareStatement("update canonical_source_binding set snapshot_json=?,snapshot_bytes_sha256=?,snapshot_content_sha256=? where id=1")){
            statement.setCharacterStream(1,new StringReader(raw));statement.setString(2,row.getSnapshotBytesSha256());statement.setString(3,row.getSnapshotContentSha256());statement.executeUpdate();
        }
    }
}
