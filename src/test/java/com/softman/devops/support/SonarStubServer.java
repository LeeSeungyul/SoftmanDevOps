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
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SonarStubServer implements AutoCloseable {
    private final HttpServer httpServer;
    private final ExecutorService executorService;
    private final BlockingQueue<ResponsePlan> responsePlans = new LinkedBlockingQueue<>();
    private final BlockingQueue<CapturedRequest> capturedRequests = new LinkedBlockingQueue<>();

    public SonarStubServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create stub server", exception);
        }
        this.executorService = Executors.newCachedThreadPool(new StubThreadFactory());
        this.httpServer.setExecutor(executorService);
        this.httpServer.createContext("/api/measures/component", new StubHandler());
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
            throw new IllegalStateException("No request captured within timeout");
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
            capturedRequests.add(CapturedRequest.from(exchange));
            ResponsePlan plan = Optional.ofNullable(responsePlans.poll()).orElse(ResponsePlan.internalError());
            if (plan.delayMillis() > 0) {
                try {
                    Thread.sleep(plan.delayMillis());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = plan.body().getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.put("Content-Type", List.of("application/json"));
            headers.put("Cache-Control", List.of("no-store"));
            exchange.sendResponseHeaders(plan.statusCode(), body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }

    private static final class StubThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("sonar-stub-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    public record ResponsePlan(int statusCode, String body, long delayMillis) {
        public static ResponsePlan success(JsonObject body) {
            return new ResponsePlan(200, body.toString(), 0);
        }

        public static ResponsePlan successWithDelay(JsonObject body, long delayMillis) {
            return new ResponsePlan(200, body.toString(), delayMillis);
        }

        public static ResponsePlan of(int statusCode, String body) {
            return new ResponsePlan(statusCode, body, 0);
        }

        public static ResponsePlan status(int statusCode) {
            return new ResponsePlan(statusCode, "{}", 0);
        }

        public static ResponsePlan internalError() {
            JsonObject error = new JsonObject();
            error.addProperty("message", "stub default error");
            return new ResponsePlan(500, error.toString(), 0);
        }
    }

    public record CapturedRequest(String method, URI uri, Headers headers) {
        private static CapturedRequest from(HttpExchange exchange) {
            Headers copy = new Headers();
            copy.putAll(exchange.getRequestHeaders());
            return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), copy);
        }

        public String header(String name) {
            List<String> values = headers.get(name);
            return (values == null || values.isEmpty()) ? null : values.get(0);
        }
    }
}
