package com.consense.service.sources;

import com.consense.common.Utf8;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Only fixed registry paths against an operator-configured base; never a caller URL. */
@Component
public class CanonicalEvidenceBackend {
    private final CanonicalEvidenceProperties properties;
    public CanonicalEvidenceBackend(CanonicalEvidenceProperties properties){this.properties=properties;}
    public static class Reply {
        public final byte[] bytes;
        public final boolean notModified;
        public final String contentType,etag,sourceSha256,projectionSha256,sourceRevisionSha256,evidenceIdentitySha256;
        Reply(byte[] bytes,String type,String etag,String source,String projection){this(bytes,type,etag,source,projection,null,null);}
        Reply(byte[] bytes,String type,String etag,String source,String projection,String revision,String evidence){
            this(bytes,type,etag,source,projection,revision,evidence,false);}
        Reply(byte[] bytes,String type,String etag,String source,String projection,String revision,String evidence,boolean notModified){
            this.bytes=bytes;this.contentType=type;this.etag=etag;this.sourceSha256=source;this.projectionSha256=projection;
            this.sourceRevisionSha256=revision;this.evidenceIdentitySha256=evidence;this.notModified=notModified;}
        public String json(){requireUtf8(bytes);return new String(bytes,StandardCharsets.UTF_8);}
        /** Validate strictly with bounded scratch space before the compact String constructor. */
        static void requireUtf8(byte[] bytes){
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer input=ByteBuffer.wrap(bytes);CharBuffer output=CharBuffer.allocate(8192);CoderResult result;
            try{
                do{result=decoder.decode(input,output,true);if(result.isError())result.throwException();output.clear();}while(result.isOverflow());
                do{result=decoder.flush(output);if(result.isError())result.throwException();output.clear();}while(result.isOverflow());
            }catch(java.nio.charset.CharacterCodingException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
        }
    }
    public int normalMaximumBytes(){return NormalObservedContract.maximumBytes(properties.getMaxSnapshotBytes());}
    public String health(){return get(null,null,null,null,false,null,properties.getMaxSnapshotBytes()).json();}
    public String normalHealth(String module){return request(normalBase(module).newBuilder().addPathSegment("health").build(),normalMaximumBytes()).json();}
    public String normalSnapshot(String projection,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}"))throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");
        return execute(new Request.Builder().url(normalBase(module).newBuilder().addPathSegment("canonical").addPathSegment(projection).addQueryParameter("module",module).build())
                .tag(Integer.class,normalTimeout()).get().build(),normalMaximumBytes()).json();
    }
    /** A conditional read still requires the registry's complete source/CAS checks before 304. */
    public Reply snapshotIfChanged(String projection,String module,String wireSha256,boolean normal){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||wireSha256==null||!wireSha256.matches("[a-f0-9]{64}"))
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");
        Request.Builder request=new Request.Builder().url(normalBase(module).newBuilder().addPathSegment("canonical")
                .addPathSegment(projection).addQueryParameter("module",module).build()).header("If-None-Match","\""+wireSha256+"\"");
        if(normal)request.tag(Integer.class,normalTimeout());
        Reply reply=execute(request.get().build(),normal?normalMaximumBytes():Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES));
        if(reply.notModified&&!projection.equals(reply.projectionSha256))
            throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
        return reply;
    }
    @FunctionalInterface public interface NormalSnapshotVerifier {void verify(Reader reader)throws IOException;}
    /** A 200 reply is checked through a bounded stream, never a complete byte[] or String. */
    public boolean verifyNormalSnapshot(String projection,String module,String original,String wireSha256,NormalSnapshotVerifier verifier){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||original==null||!original.matches("[a-f0-9]{64}")
                ||wireSha256==null||!wireSha256.matches("[a-f0-9]{64}")||verifier==null)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");
        String expected="\""+wireSha256+"\"";
        Request request=new Request.Builder().url(normalBase(module).newBuilder().addPathSegment("canonical").addPathSegment(projection).addQueryParameter("module",module).build())
                .header("If-None-Match",expected).get().build();
        int timeout=normalTimeout(),maximum=normalMaximumBytes();
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .readTimeout(timeout,TimeUnit.MILLISECONDS).callTimeout(timeout,TimeUnit.MILLISECONDS).build();
        try(Response response=client.newCall(request).execute()){
            // Normal canonical replies bind the source in the verified body/CAS, without a source header.
            String source=response.header("X-Source-Sha256");
            if(!projection.equals(response.header("X-Projection-Sha256"))||source!=null&&!original.equals(source))
                throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
            if(response.code()==304){
                if(!expected.equals(response.header("ETag")))throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
                return true;
            }
            if(response.code()!=200||response.body()==null)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");
            if(response.body().contentLength()>maximum)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");
            java.security.MessageDigest digest;
            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            InputStream limited=new FilterInputStream(response.body().byteStream()){
                long bytes;
                private void count(int size){if(size>0&&(bytes+=size)>maximum)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");}
                @Override public int read(byte[] value,int offset,int length)throws IOException {int size=in.read(value,offset,length);count(size);return size;}
                @Override public int read()throws IOException {int value=in.read();if(value>=0)count(1);return value;}
            };
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try(Reader reader=new InputStreamReader(new java.security.DigestInputStream(limited,digest),decoder)){
                Reader owned=new FilterReader(reader){@Override public void close(){/* The transport owns and closes the stream. */}};
                verifier.verify(owned);
                if(owned.read()!=-1)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
            }
            String actual="\""+java.util.HexFormat.of().formatHex(digest.digest())+"\"";
            if(!actual.equals(response.header("ETag")))throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
            return true;
        }catch(IOException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");}
    }
    public String normalObserved(String request,String module){
        return normalObservedReply(request,module).json();
    }
    /** Keep the complete normal reply in bytes until its small receipt and projection are separated. */
    public Reply normalObservedReply(String request,String module){
        int maximum=normalMaximumBytes();
        int timeout=normalTimeout();
        long length=Utf8.byteCount(request);
        if(length>maximum)throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"NORMAL_OBSERVED_TOO_LARGE");
        HttpUrl base=normalBase(module);
        if(maximum<=0)
            throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        RequestBody body=new RequestBody(){
            @Override public MediaType contentType(){return MediaType.parse("application/json; charset=utf-8");}
            @Override public long contentLength(){return length;}
            @Override public void writeTo(okio.BufferedSink sink)throws IOException{
                // Closing finishes a trailing unmatched surrogate, while the wrapper leaves OkHttp's sink open.
                OutputStream output=new FilterOutputStream(sink.outputStream()){@Override public void close()throws IOException{flush();}};
                try(Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8))){writer.write(request);}
            }
        };
        Request wire=new Request.Builder().url(base.newBuilder().addPathSegment("normal-observed").build()).tag(Integer.class,timeout)
                .post(body).build();
        return execute(wire,maximum);
    }
    /** Bounded source reads use the module process; callers send addresses, never a complete graph. */
    public String sourceRead(String request,String module,int maximumBytes){
        if(request==null||Utf8.byteCount(request)>65536||maximumBytes<1024||maximumBytes>16*1024*1024)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"OER_SOURCE_READ_BUDGET_INVALID");
        int maximum=Math.min(maximumBytes,properties.getMaxSnapshotBytes());
        if(maximum<1024)throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        Request.Builder wire=new Request.Builder().url(normalBase(module).newBuilder().addPathSegment("sources").addPathSegment("read").build());
        try{
            com.fasterxml.jackson.databind.JsonNode body=JsonUtils.mapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(request);
            if(body!=null&&module.equals(body.path("module").asText())&&java.util.Arrays.asList("vetting","advisory").contains(module)
                    &&"prepare".equals(body.path("operation").asText())&&"semantic_units".equals(body.path("view").asText()))
                wire.tag(Integer.class,normalTimeout());
        }catch(IOException malformed){/* Invalid requests retain the ordinary transport budget. */}
        return execute(wire.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),Utf8.bytes(request))).build(),maximum).json();
    }
    /** One owned semantic scan; only a single bounded UTF-8 frame is retained by the transport. */
    public void sourceReadStream(String request,String module,int maximumBytes,Consumer<String> frame){
        if(request==null||Utf8.byteCount(request)>65536||maximumBytes<1024||maximumBytes>16*1024*1024||frame==null)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"OER_SOURCE_READ_BUDGET_INVALID");
        int maximum=Math.min(maximumBytes,properties.getMaxSnapshotBytes());
        if(maximum<1024)throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        Request wire=new Request.Builder().url(normalBase(module).newBuilder().addPathSegment("sources").addPathSegment("read").build())
                .header("Accept","application/x-ndjson").tag(Integer.class,normalTimeout())
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),Utf8.bytes(request))).build();
        executeStream(wire,maximum,frame);
    }
    protected void executeStream(Request request,int maximum,Consumer<String> frame){
        int timeout=request.tag(Integer.class);
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .readTimeout(timeout,TimeUnit.MILLISECONDS).callTimeout(timeout,TimeUnit.MILLISECONDS).build();
        Call call=client.newCall(request);if(Thread.currentThread().isInterrupted())call.cancel();
        try(Response response=call.execute()){
            String type=response.header("Content-Type","").split(";",2)[0].trim();
            if(response.code()!=200||response.body()==null||!"application/x-ndjson".equalsIgnoreCase(type))
                throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_SOURCE_STREAM_PROTOCOL_INVALID");
            okio.BufferedSource input=response.body().source();
            while(!input.exhausted()){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Source stream cancelled");
                long newline=input.indexOf((byte)'\n',0,(long)maximum+1);
                if(newline<0)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_SOURCE_STREAM_FRAME_INVALID");
                byte[] line=input.readByteArray(newline);input.skip(1);Reply.requireUtf8(line);
                if(line.length==0)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_SOURCE_STREAM_FRAME_INVALID");
                frame.accept(new String(line,StandardCharsets.UTF_8));
            }
        }catch(IOException error){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");}
        finally{call.cancel();}
    }
    private int normalTimeout(){int timeout=properties.getNormalPublicationTimeoutMs();if(timeout<=0)throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");return timeout;}
    private HttpUrl normalBase(String module){
        if(!java.util.Arrays.asList("drafting","vetting","advisory").contains(module))throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        String configured=properties.getRetrievalBaseUrls().getOrDefault(module,properties.getBaseUrl());HttpUrl base=HttpUrl.parse(configured==null?"":configured);
        if(base==null||!base.username().isEmpty()||!base.password().isEmpty()||base.query()!=null||base.fragment()!=null||!base.encodedPath().equals("/")||properties.getTimeoutMs()<=0)
            throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");return base;
    }
    public String snapshot(String projection,String module){return get(projection,null,null,null,false,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES)).json();}
    /** A batch is addressed by its ordinal under one sealed document root, never by a caller CAS path. */
    public Reply batch(String projection,int ordinal,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||ordinal<0)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_COLLECTION_BATCH_ADDRESS_INVALID");
        return get(projection,null,null,ordinal,false,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES));
    }
    public Reply envelope(String projection,int ordinal,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||ordinal<0)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_COLLECTION_ENVELOPE_ADDRESS_INVALID");
        if(!java.util.Arrays.asList("drafting","vetting","advisory").contains(module))
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        return get(projection,null,null,ordinal,true,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES));
    }
    public Reply asset(String projection,String source,String asset,String module){return get(projection,source,asset,null,false,module,properties.getMaxAssetBytes());}
    public Reply sourcePages(String projection,String source,String evidence,String asset,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||source==null||!source.matches("[a-f0-9]{64}")
                ||evidence==null||!evidence.matches("[a-f0-9]{64}")||!java.util.Arrays.asList("vetting","advisory").contains(module)
                ||asset!=null&&!asset.matches("source-render:[a-f0-9]{64}"))
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_SOURCE_PAGE_ADDRESS_INVALID");
        HttpUrl base=normalBase(module);
        HttpUrl.Builder url=base.newBuilder().addPathSegment("canonical").addPathSegment(projection).addPathSegment("sources")
                .addPathSegment(source).addPathSegment("source-pages");
        if(asset!=null)url.addPathSegment(asset);
        url.addQueryParameter("module",module).addQueryParameter("evidence",evidence);
        return request(url.build(),asset==null?properties.getMaxSnapshotBytes():properties.getMaxAssetBytes());
    }
    private Reply get(String projection,String source,String asset,Integer ordinal,boolean envelope,String module,int maximum){
        HttpUrl base=module==null?HttpUrl.parse(properties.getBaseUrl()==null?"":properties.getBaseUrl()):normalBase(module);
        if(base==null||!base.username().isEmpty()||!base.password().isEmpty()||base.query()!=null||base.fragment()!=null
                ||!base.encodedPath().equals("/")||maximum<=0||properties.getTimeoutMs()<=0)
            throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        HttpUrl.Builder path=base.newBuilder();
        if(projection==null)path.addPathSegment("health");else{
            path.addPathSegment("canonical").addPathSegment(projection);
            if(ordinal!=null)path.addPathSegment(envelope?"envelopes":"batches").addPathSegment(String.valueOf(ordinal));
            if(asset!=null)path.addPathSegment("sources").addPathSegment(source).addPathSegment("assets").addPathSegment(asset);
            path.addQueryParameter("module",module);
        }
        return request(path.build(),maximum);
    }
    /** Transport seam for in-process contract probes; production keeps the same bounded fixed request. */
    protected Reply request(HttpUrl url,int maximum){
        return execute(new Request.Builder().url(url).get().build(),maximum);
    }
    protected Reply execute(Request request,int maximum){
        // Complete normal graphs, their sealed read-back and cold semantic preparation share a budget.
        Integer normalTimeout=request.tag(Integer.class);int timeout=normalTimeout==null?properties.getTimeoutMs():normalTimeout;
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .readTimeout(timeout,TimeUnit.MILLISECONDS)
                .callTimeout(timeout,TimeUnit.MILLISECONDS).build();
        try(Response response=client.newCall(request).execute()){
            if(response.code()==304){
                String expected=request.header("If-None-Match");
                if(expected==null||!expected.equals(response.header("ETag")))
                    throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_CONDITIONAL_IDENTITY_MISMATCH");
                return new Reply(new byte[0],response.header("Content-Type"),response.header("ETag"),response.header("X-Source-Sha256"),
                        response.header("X-Projection-Sha256"),response.header("X-Source-Revision-Sha256"),response.header("X-Evidence-Identity-Sha256"),true);
            }
            if(!response.isSuccessful()||response.body()==null)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");
            return new Reply(readBody(response.body(),maximum),response.header("Content-Type"),response.header("ETag"),response.header("X-Source-Sha256"),response.header("X-Projection-Sha256"),
                    response.header("X-Source-Revision-Sha256"),response.header("X-Evidence-Identity-Sha256"));
        }catch(IOException error){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");}
    }
    static byte[] readBody(ResponseBody body,int maximum)throws IOException{
        if(maximum<=0)throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        if(body.contentLength()>maximum)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        try(InputStream input=body.byteStream()){while((n=input.read(block))!=-1){if(out.size()>maximum-n)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");out.write(block,0,n);}}
        return out.toByteArray();
    }

}
