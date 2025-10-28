package com.softman.devops.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.softman.devops.dto.JenkinsSonarRequest;
import com.softman.devops.service.ForwardingException;
import com.softman.devops.service.JenkinsSonarForwardingService;
import com.softman.devops.service.JenkinsSonarForwardingService.ForwardResponse;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JenkinsSonarHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(JenkinsSonarHandler.class);

    private final JenkinsSonarForwardingService forwardingService;
    private final Gson gson;
    private final AtomicInteger activeRequests;
    private final int maxConcurrentRequests;
    private final ScheduledExecutorService scheduler;

    public JenkinsSonarHandler(JenkinsSonarForwardingService forwardingService,
                               Gson gson,
                               AtomicInteger activeRequests,
                               int maxConcurrentRequests,
                               ScheduledExecutorService scheduler) {
        this.forwardingService = Objects.requireNonNull(forwardingService, "forwardingService");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.activeRequests = Objects.requireNonNull(activeRequests, "activeRequests");
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlainText(exchange, 405, "Method Not Allowed");
            return;
        }

        int current = activeRequests.incrementAndGet();
        if (current > maxConcurrentRequests) {
            activeRequests.decrementAndGet();
            LOGGER.warn("Rejecting Jenkins sonar request due to concurrency limit");
            sendError(exchange, 429, "TOO_MANY_REQUESTS", "Maximum concurrent requests exceeded");
            return;
        }

        try {
            String requestBody = readBody(exchange.getRequestBody());
            if (requestBody.isBlank()) {
                throw new ValidationException("Request body must not be empty");
            }
            JsonObject jsonObject = parseJson(requestBody);
            JenkinsSonarRequest request = JenkinsSonarRequest.fromJson(jsonObject);
            if (!request.hasForwardJobs()) {
                LOGGER.info("All Jenkins sonar jobs marked as skipped; returning local success response");
                sendLocalSuccess(exchange, request);
                scheduleCallback(request);
                return;
            }
            String forwardPayload = gson.toJson(request.forwardPayload());
            ForwardResponse response = forwardingService.forward(request, forwardPayload);
            passthrough(exchange, response);
        } catch (ValidationException validationException) {
            LOGGER.info("Jenkins sonar validation failure: {}", validationException.getMessage());
            sendError(exchange, 400, "BAD_REQUEST", validationException.getMessage());
        } catch (JsonParseException parseException) {
            LOGGER.info("Malformed Jenkins sonar JSON payload", parseException);
            sendError(exchange, 400, "BAD_REQUEST", "Invalid JSON payload");
        } catch (ForwardingException forwardingException) {
            LOGGER.warn("Forwarding failure: {}", forwardingException.getMessage());
            sendError(exchange, forwardingException.getStatusCode(), "FORWARDING_ERROR", forwardingException.getMessage());
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private JsonObject parseJson(String body) {
        return gson.fromJson(body, JsonObject.class);
    }

    private void passthrough(HttpExchange exchange, ForwardResponse response) throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.clear();
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if ("content-length".equalsIgnoreCase(key) || "transfer-encoding".equalsIgnoreCase(key)) {
                continue;
            }
            responseHeaders.put(key, List.copyOf(entry.getValue()));
        }

        byte[] body = response.body();
        exchange.sendResponseHeaders(response.statusCode(), body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendError(HttpExchange exchange, int statusCode, String status, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);
        payload.addProperty("message", message);
        sendJson(exchange, statusCode, payload);
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] data = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.put("Content-Type", List.of("application/json; charset=UTF-8"));
        headers.put("Cache-Control", List.of("no-store"));
        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(data);
        }
    }

    private void sendLocalSuccess(HttpExchange exchange, JenkinsSonarRequest request) throws IOException {
        sendJson(exchange, 200, request.callbackPayload());
    }

    private void sendPlainText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.put("Content-Type", List.of("text/plain; charset=UTF-8"));
        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(data);
        }
    }

    private void scheduleCallback(JenkinsSonarRequest request) {
        long delay = Math.max(0, request.callTimeSeconds());
        JsonObject payload = request.callbackPayload();
        String payloadJson = gson.toJson(payload);
        String callbackUrl = request.callbackUrl();
        try {
            scheduler.schedule(() -> executeCallback(callbackUrl, payloadJson), delay, TimeUnit.SECONDS);
            LOGGER.info("Scheduled Jenkins sonar callback in {} second(s) to {}", delay, callbackUrl);
        } catch (RuntimeException runtimeException) {
            LOGGER.warn("Failed to schedule callback to {}: {}", callbackUrl, runtimeException.getMessage());
        }
    }

    private void executeCallback(String callbackUrl, String payloadJson) {
        try {
            forwardingService.postJson(callbackUrl, payloadJson);
            LOGGER.info("Callback delivered successfully to {}", callbackUrl);
        } catch (ForwardingException forwardingException) {
            LOGGER.warn("Callback delivery failed for {}: {}", callbackUrl, forwardingException.getMessage());
        }
    }
}
