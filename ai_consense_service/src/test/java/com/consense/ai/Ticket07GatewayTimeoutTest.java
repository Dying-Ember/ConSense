package com.consense.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic HTTP gateway only; this does not validate a deployed container. */
class Ticket07GatewayTimeoutTest {
    @Test void timeoutAfterPartialBodyIsReportedAndThePartialEventIsPreserved() throws Exception {
        List<String> events = new ArrayList<>();
        HttpServer server = serve(200, true, "data: partial\n\nevent: consense-gateway-timeout\ndata: TIMEOUT\n\n");
        try {
            HttpSupport.ModelRequestFailure failure = assertThrows(HttpSupport.ModelRequestFailure.class,
                    () -> call(server, events));
            assertEquals("TIMEOUT", failure.failureKind());
            assertEquals(List.of("partial"), events);
        } finally { server.stop(0); }
    }

    @Test void timeoutBeforeHeadersIsDistinguishedFromBusy() throws Exception {
        HttpServer server = serve(504, false, "{}");
        try {
            HttpSupport.ModelRequestFailure failure = assertThrows(HttpSupport.ModelRequestFailure.class,
                    () -> call(server, new ArrayList<>()));
            assertEquals("HTTP504", failure.failureKind());
            assertEquals("timed out", failure.description());
        } finally { server.stop(0); }
    }

    @Test void ordinaryProviderEventIsNotInterpretedAsGatewaySignal() throws Exception {
        List<String> events = new ArrayList<>();
        HttpServer server = serve(200, false, "event: consense-gateway-timeout\ndata: TIMEOUT\n\n");
        try {
            call(server, events);
            assertEquals(List.of("TIMEOUT"), events);
        } finally { server.stop(0); }
    }

    private static void call(HttpServer server, List<String> events) {
        new HttpSupport().postJsonStream("http://127.0.0.1:" + server.getAddress().getPort() + "/", "{}",
                3000, null, 1024, true, events::add, ignored -> {});
    }

    private static HttpServer serve(int status, boolean gateway, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (gateway) exchange.getResponseHeaders().set("X-ConSense-Gateway-Events", "1");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
