package com.ticketrush.assistant.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for event-service and booking-service. The JDK HttpServer, not MockWebServer: Spring AI's Anthropic SDK
 * brings OkHttp 4, and mockwebserver3 needs OkHttp 5 (the two clash on one classpath).
 */
public final class FakeServices {

    public record Request(String method, String path, String authorization) {}

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 404;
    private volatile String body = "{}";

    public FakeServices() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void answer(int status, String body) {
        this.status = status;
        this.body = body;
        requests.clear();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }
}
