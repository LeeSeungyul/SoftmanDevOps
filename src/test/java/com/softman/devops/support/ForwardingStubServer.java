package com.softman.devops.support;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ForwardingStubServer implements AutoCloseable {
    private final HttpServer httpServer;
    private final ExecutorService executorService;
    private final BlockingQueue<ResponsePlan> responsePlans = new LinkedBlockingQueue<>();
    private final BlockingQueue<CapturedRequest> capturedRequests = new LinkedBlockingQueue<>();

    public ForwardingStubServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create forwarding stub server", exception);
        }
        this.executorService = Executors.newCachedThreadPool(new StubThreadFactory());
        this.httpServer.setExecutor(executorService);
        this.httpServer.createContext("/", new StubHandler());
        this.httpServer.start();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public void enqueue(ResponsePlan plan) {
        responsePlans.add(plan);
    }

    public CapturedRequest takeRequest(Duration timeout) throws InterruptedException {
        CapturedRequest captured = capturedRequests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (captured == null) {
            throw new IllegalStateException("No forwarding request captured within timeout");
        }
        return captured;
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executorService.shutdownNow();
    }

    private final class StubHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Headers requestHeaders = new Headers();
            requestHeaders.putAll(exchange.getRequestHeaders());
            capturedRequests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), requestHeaders, body));

            ResponsePlan plan = Optional.ofNullable(responsePlans.poll()).orElse(ResponsePlan.json(500, defaultError()));
            if (plan.delayMillis() > 0) {
                try {
                    Thread.sleep(plan.delayMillis());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] responseBody = plan.body().getBytes(StandardCharsets.UTF_8);
            Headers responseHeaders = exchange.getResponseHeaders();
            for (Map.Entry<String, List<String>> entry : plan.headers().entrySet()) {
                responseHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            exchange.sendResponseHeaders(plan.statusCode(), responseBody.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        }

        private JsonObject defaultError() {
            JsonObject error = new JsonObject();
            error.addProperty("message", "forwarding stub default error");
            return error;
        }
    }

    private static final class StubThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("forwarding-stub-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    public record ResponsePlan(int statusCode, String body, Map<String, List<String>> headers, long delayMillis) {
        public ResponsePlan {
            headers = Map.copyOf(headers);
        }

        public static ResponsePlan json(int statusCode, JsonObject body) {
            return json(statusCode, body.toString());
        }

        public static ResponsePlan json(int statusCode, String body) {
            return new ResponsePlan(statusCode, body, Map.of("Content-Type", List.of("application/json; charset=utf-8")), 0);
        }

        public static ResponsePlan withHeaders(int statusCode, String body, Map<String, List<String>> headers) {
            return new ResponsePlan(statusCode, body, headers, 0);
        }

        public static ResponsePlan status(int statusCode) {
            return json(statusCode, "{}");
        }
    }

    public record CapturedRequest(String method, URI uri, Headers headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name);
            return (values == null || values.isEmpty()) ? null : values.get(0);
        }
    }
}
