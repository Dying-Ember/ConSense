package com.consense.ocr;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativePdfResponseReadBoundTest {
    private static final int CAP=8*1024*1024,TOTAL=64*1024*1024;
    private static final byte[] PDF="%PDF-complete-original".getBytes(StandardCharsets.US_ASCII);

    @Test void declaredOversizeNativeReplyIsRejectedBeforeUnboundedBodyRead() throws Exception {rejectWhileReading(false,false);}
    @Test void chunkedNativeReplyStopsAfterTheDecompressedByteCap() throws Exception {rejectWhileReading(true,false);}
    @Test void gzipNativeReplyIsLimitedAfterDecompression() throws Exception {rejectWhileReading(true,true);}

    @Test void exactUtf8ReplyBoundaryIsAcceptedAndOneByteOverIsRejected() throws Exception {
        for(int overflow:new int[]{0,1}) {
            Map<String,Object> context=context();ObjectNode response=JsonUtils.mapper().createObjectNode();
            response.put("schema","consense.native-pdf-candidates.v1");response.put("status","failed");response.set("source",JsonUtils.mapper().valueToTree(context.get("source")));
            response.set("selectedPhysicalPages",JsonUtils.parse("[1]"));response.putArray("pages");response.putObject("bundleRef").put("sha256","actual-retained-bundle");
            response.putObject("failure").put("type","ActualProviderFailure");response.put("padding","");
            int base=JsonUtils.mapper().writeValueAsBytes(response).length;response.put("padding","x".repeat(CAP-base+overflow));
            byte[] body=JsonUtils.mapper().writeValueAsBytes(response);assertEquals(CAP+overflow,body.length);
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/native-pdf/table-candidates",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
                try(OutputStream output=exchange.getResponseBody()){output.write(body);}catch(IOException ignored){exchange.close();}});server.start();
            try {
                if(overflow==0){JsonNode result=client(server).observeNativePdf(PDF,context);assertEquals("actual-retained-bundle",result.path("bundleRef").path("sha256").asText());}
                else assertTrue(assertThrows(BizException.class,()->client(server).observeNativePdf(PDF,context)).getMessage().contains("HTTP response exceeds 8388608 bytes"));
            } finally {server.stop(0);}
        }
    }

    private static void rejectWhileReading(boolean chunked,boolean gzip) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicBoolean completed=new AtomicBoolean();AtomicInteger written=new AtomicInteger();CountDownLatch stopped=new CountDownLatch(1);
        server.createContext("/native-pdf/table-candidates",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();if(gzip)exchange.getResponseHeaders().add("Content-Encoding","gzip");exchange.sendResponseHeaders(200,chunked?0:TOTAL);
                try(OutputStream output=gzip?new GZIPOutputStream(exchange.getResponseBody()):exchange.getResponseBody()) {
                    byte[] block=new byte[8192];Random random=new Random(73);
                    while(written.get()<TOTAL){random.nextBytes(block);output.write(block);written.addAndGet(block.length);}completed.set(true);
                }
            } catch(IOException expectedDisconnect) {exchange.close();} finally {stopped.countDown();}
        });server.start();
        try {
            BizException failure=assertThrows(BizException.class,()->client(server).observeNativePdf(PDF,context()));
            assertTrue(stopped.await(10,TimeUnit.SECONDS));
            assertAll(()->assertFalse(completed.get(),"The HTTP boundary must close before reading all 64 MiB"),
                    ()->assertTrue(written.get()<TOTAL,"The old boundary consumed every decompressed byte"),
                    ()->assertTrue(failure.getMessage().contains("HTTP response exceeds 8388608 bytes"),failure.getMessage()));
        } finally {server.stop(0);}
    }
    private static PaddleOcrClient client(HttpServer server) {
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setTimeoutMs(20000);
        return new PaddleOcrClient(cfg,new HttpSupport());
    }
    private static Map<String,Object> context() {
        return Map.of("source",Map.of("sourceFilename","source.pdf","sourceSha256",DocumentParseProbe.sha256(PDF),"mediaType","application/pdf","pageCount",1),
                "selectedPhysicalPages",List.of(1),"javaNativeEvidence",List.of());
    }
}
