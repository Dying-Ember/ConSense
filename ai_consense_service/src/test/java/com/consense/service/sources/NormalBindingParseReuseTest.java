package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.io.InputDecorator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalBindingParseReuseTest {
    @Test void completeContractUsesOneStrictSourceParse() throws Exception {
        String raw=NormalObservedContractTest.resource("authored-projection.json");
        JsonNode expected=JsonUtils.parse(raw),before=expected.deepCopy();
        try(JsonParseCounter reads=JsonParseCounter.canonical(raw)){
            assertEquals(expected,NormalObservedContract.validate(raw,expected.path("projectionSha256").asText(),NormalObservedContract.MAX_BYTES,expected));
            assertEquals(1,reads.sourceReads,"The checked source is traversed by the strict canonical parser only once");
        }
        assertEquals(before,expected);
    }
    @Test void oneTokenTreePreservesExistingHashesNumbersStringsAndCanonicalOrdering() {
        for(String number:new String[]{"1e-05","1E-5","-0.0","1.0","123456789012345678901234567890"}){
            String raw="{\"projectionSha256\":\"authored seal\",\"manifest\":{\"sourceBinding\":{},\"candidateGraph\":{\"float\":"+number+",\"text\":\"quote\\\" slash\\\\ \\ud83d\\ude00\"}},\"projectionIdentity\":{\"z\":true,\"a\":null},\"canonical\":{\"units\":[1,2]}}";
            CanonicalJson.NormalHashes actual=CanonicalJson.normalHashes(raw);
            String manifest=CanonicalJson.field(raw,"manifest"),identity=CanonicalJson.field(raw,"projectionIdentity"),canonical=CanonicalJson.field(raw,"canonical");
            String source=CanonicalJson.hash(CanonicalJson.rawObject(java.util.Map.of("projectionIdentity",identity,"canonical",canonical)),null);
            assertEquals(CanonicalJson.hash(raw,"projectionSha256"),actual.projectionSha256);
            assertEquals(CanonicalJson.fieldHash(manifest,"candidateGraph"),actual.candidateGraphSha256());
            assertEquals(CanonicalJson.hash(manifest,null),actual.manifestSha256());
            assertEquals(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,actual.manifestByteCount());
            assertEquals(source,actual.sourceHash());
            assertEquals(CanonicalJson.hash(CanonicalJson.rawObject(java.util.Map.of("projectionIdentity",identity,"sourceHash",CanonicalJson.scalar(source))),null),actual.projectionRevisionSha256(source));
        }
    }
    @Test void strictSingleParseStillRejectsDuplicateKeysMalformedAndTrailingValues() {
        for(String raw:new String[]{"{\"a\":1,\"a\":2}","{\"manifest\":{\"candidateGraph\":{},\"candidateGraph\":{}}}","{} {}","{\"a\":}","[]"})
            assertEquals("CANONICAL_JSON_INVALID",assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.normalHashes(raw)).getMessage());
    }
}

final class JsonParseCounter implements AutoCloseable {
    private final JsonFactory factory;
    private final InputDecorator before;
    int sourceReads;
    static JsonParseCounter canonical(String raw) throws Exception {
        java.lang.reflect.Field field=CanonicalJson.class.getDeclaredField("FACTORY");field.setAccessible(true);
        return new JsonParseCounter((JsonFactory)field.get(null),raw);
    }
    JsonParseCounter(JsonFactory factory,String raw){
        this.factory=factory;before=factory.getInputDecorator();
        if(before!=null)throw new AssertionError("Unexpected parser decorator");
        factory.setInputDecorator(new InputDecorator(){
            @Override public InputStream decorate(IOContext context,InputStream input){return null;}
            @Override public InputStream decorate(IOContext context,byte[] input,int offset,int length){return null;}
            @Override public Reader decorate(IOContext context,Reader input) throws IOException {
                StringWriter captured=new StringWriter();input.transferTo(captured);String value=captured.toString();
                if(raw.equals(value))sourceReads++;
                return new StringReader(value);
            }
        });
    }
    @Override public void close(){factory.setInputDecorator(before);}
}
