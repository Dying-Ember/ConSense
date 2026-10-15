package com.consense.ai;

import com.consense.common.BizException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpSupportOneShotMultipartTest {
    @Test void existingMultipartCallsRemainAbleToReadResponsesAboveTheNativeOnlyCap() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int size=9*1024*1024;
        server.createContext("/ordinary",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,size);
            byte[] block=new byte[8192];java.util.Arrays.fill(block,(byte)'x');
            try(java.io.OutputStream output=exchange.getResponseBody()){for(int written=0;written<size;written+=block.length)output.write(block);}});server.start();
        try {
            MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("ordinary","value").build();
            assertEquals(size,new HttpSupport().postMultipart("http://127.0.0.1:"+server.getAddress().getPort()+"/ordinary",body,10000,java.util.Collections.emptyMap()).length());
        } finally {server.stop(0);}
    }

    @Test void disconnectedOneShotMultipartIsDispatchedExactlyOnce() throws Exception {
        check(false);
    }

    @Test void oneShotMultipartDoesNotFollow303AndSendASecondRequest() throws Exception {
        check(true);
    }

    private static void check(boolean redirect) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger received=new AtomicInteger(),forwarded=new AtomicInteger();
        server.createContext("/native-pdf/table-candidates",exchange->{
            received.incrementAndGet();assertTrue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).contains("complete-native-context"));
            if(redirect){exchange.getResponseHeaders().add("Location","/replayed");exchange.sendResponseHeaders(303,-1);}
            exchange.close();
        });
        server.createContext("/replayed",exchange->{forwarded.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});
        server.start();
        try {
            RequestBody context=new RequestBody() {
                @Override public MediaType contentType(){return MediaType.get("application/json");}
                @Override public void writeTo(BufferedSink sink) throws IOException{sink.writeUtf8("complete-native-context");}
                @Override public boolean isOneShot(){return true;}
            };
            MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("nativeContext","native-context.json",context).build();
            assertTrue(body.part(0).body().isOneShot());
            assertThrows(BizException.class,()->new HttpSupport().postMultipart("http://127.0.0.1:"+server.getAddress().getPort()+"/native-pdf/table-candidates",body,3000));
            assertEquals(1,received.get());assertEquals(0,forwarded.get());
        } finally {server.stop(0);}
    }
}
