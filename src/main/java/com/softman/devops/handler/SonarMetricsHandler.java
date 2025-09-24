package com.softman.devops.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.softman.devops.dto.SonarMetricValue;
import com.softman.devops.dto.SonarMetricsRequest;
import com.softman.devops.service.CallTimeoutException;
import com.softman.devops.service.JobDeadlineExceededException;
import com.softman.devops.service.SonarMetricsService;
import com.softman.devops.service.UpstreamErrorException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SonarMetricsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SonarMetricsHandler.class);

    private final SonarMetricsService sonarMetricsService;
    private final Gson gson;
    private final AtomicInteger activeRequests;
    private final int maxConcurrentRequests;

    public SonarMetricsHandler(SonarMetricsService sonarMetricsService,
                               Gson gson,
                               AtomicInteger activeRequests,
                               int maxConcurrentRequests) {
        this.sonarMetricsService = Objects.requireNonNull(sonarMetricsService, "sonarMetricsService");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.activeRequests = Objects.requireNonNull(activeRequests, "activeRequests");
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlainText(exchange, 405, "Method Not Allowed");
            return;
        }
        int inFlight = activeRequests.incrementAndGet();
        if (inFlight > maxConcurrentRequests) {
            activeRequests.decrementAndGet();
            LOGGER.warn("Rejecting request due to concurrency limit");
            sendError(exchange, 429, "TOO_MANY_REQUESTS", "Maximum concurrent requests exceeded");
            return;
        }
        Instant startTime = Instant.now();
        try {
            String requestBody = readBody(exchange.getRequestBody());
            if (requestBody.isBlank()) {
                throw new ValidationException("Request body must not be empty");
            }
            JsonObject jsonObject = parseJson(requestBody);
            SonarMetricsRequest sonarRequest = SonarMetricsRequest.fromJson(jsonObject);
            List<SonarMetricValue> metrics = sonarMetricsService.fetchMetrics(sonarRequest, startTime);
            sendSuccess(exchange, metrics, sonarRequest.getCustomerId());
        } catch (ValidationException validationException) {
            LOGGER.info("Validation failure: {}", validationException.getMessage());
            sendError(exchange, 400, "BAD_REQUEST", validationException.getMessage());
        } catch (CallTimeoutException callTimeoutException) {
            LOGGER.warn("Call timeout: {}", callTimeoutException.getMessage());
            sendError(exchange, 408, "CALL_TIMEOUT", callTimeoutException.getMessage());
        } catch (JobDeadlineExceededException jobTimeoutException) {
            LOGGER.warn("Job timeout: {}", jobTimeoutException.getMessage());
            sendError(exchange, 504, "JOB_DEADLINE_EXCEEDED", jobTimeoutException.getMessage());
        } catch (UpstreamErrorException upstreamErrorException) {
            int statusCode = upstreamErrorException.getStatusCode();
            boolean serverError = upstreamErrorException.isServerError();
            String status = serverError ? "UPSTREAM_5XX" : "UPSTREAM_4XX";
            String message = status + " (" + statusCode + ")";
            LOGGER.warn("Upstream error: {}", message);
            sendError(exchange, statusCode, status, message);
        } catch (JsonParseException jsonParseException) {
            LOGGER.info("Malformed JSON payload", jsonParseException);
            sendError(exchange, 400, "BAD_REQUEST", "Invalid JSON payload");
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private JsonObject parseJson(String requestBody) throws ValidationException {
        JsonObject jsonObject = gson.fromJson(requestBody, JsonObject.class);
        if (jsonObject == null) {
            throw new ValidationException("Request body must be a JSON object");
        }
        return jsonObject;
    }

    private void sendSuccess(HttpExchange exchange, List<SonarMetricValue> metrics, Optional<String> customerId) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("status", "SUCCESS");
        customerId.ifPresent(id -> response.addProperty("custid", id));
        response.add("result", gson.toJsonTree(metrics));
        sendJson(exchange, 200, response);
    }

    private void sendError(HttpExchange exchange, int statusCode, String status, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("status", status);
        error.addProperty("message", message);
        sendJson(exchange, statusCode, error);
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonObject body) throws IOException {
        byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.put("Content-Type", List.of("application/json; charset=UTF-8"));
        headers.put("Cache-Control", List.of("no-store"));
        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(data);
        }
    }

    private String readBody(InputStream bodyStream) throws IOException {
        return new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
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
}
