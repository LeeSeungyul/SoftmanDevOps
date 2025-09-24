package com.softman.devops.handler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.softman.devops.dto.BatchSonarMetricsRequest;
import com.softman.devops.dto.BatchSonarMetricsRequest.BatchItem;
import com.softman.devops.dto.SonarMetricValue;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BatchSonarMetricsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchSonarMetricsHandler.class);

    private final SonarMetricsService sonarMetricsService;
    private final Gson gson;
    private final AtomicInteger activeRequests;
    private final int maxConcurrentRequests;

    public BatchSonarMetricsHandler(SonarMetricsService sonarMetricsService,
                                    Gson gson,
                                    AtomicInteger activeRequests,
                                    int maxConcurrentRequests) {
        this.sonarMetricsService = sonarMetricsService;
        this.gson = gson;
        this.activeRequests = activeRequests;
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
            LOGGER.warn("Rejecting batch request due to concurrency limit");
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
            BatchSonarMetricsRequest batchRequest = BatchSonarMetricsRequest.fromJson(jsonObject);
            JsonObject response = processBatch(batchRequest, startTime);
            sendJson(exchange, 200, response);
        } catch (ValidationException validationException) {
            LOGGER.info("Batch validation failure: {}", validationException.getMessage());
            sendError(exchange, 400, "BAD_REQUEST", validationException.getMessage());
        } catch (JsonParseException parseException) {
            LOGGER.info("Malformed JSON payload for batch endpoint", parseException);
            sendError(exchange, 400, "BAD_REQUEST", "Invalid JSON payload");
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private JsonObject processBatch(BatchSonarMetricsRequest batchRequest, Instant startTime) {
        JsonObject response = new JsonObject();
        JsonArray resultsArray = new JsonArray();
        int successCount = 0;
        for (BatchItem item : batchRequest.getItems()) {
            JsonObject itemResult = new JsonObject();
            itemResult.addProperty("component", item.request().getComponent());
            item.request().getCustomerId().ifPresent(id -> itemResult.addProperty("custid", id));
            try {
                List<SonarMetricValue> metrics = sonarMetricsService.fetchMetrics(item.request(), startTime);
                itemResult.addProperty("status", "SUCCESS");
                addMetricFields(itemResult, metrics, item.request().getMetrics());
                successCount++;
            } catch (CallTimeoutException callTimeoutException) {
                LOGGER.warn("Batch item {} timed out: {}", item.index(), callTimeoutException.getMessage());
                addError(itemResult, "CALL_TIMEOUT", item.request().getMetrics());
            } catch (JobDeadlineExceededException jobTimeoutException) {
                LOGGER.warn("Batch item {} exceeded job deadline", item.index());
                addError(itemResult, "JOB_DEADLINE_EXCEEDED", item.request().getMetrics());
            } catch (UpstreamErrorException upstreamErrorException) {
                int statusCode = upstreamErrorException.getStatusCode();
                boolean serverError = upstreamErrorException.isServerError();
                String status = serverError ? "UPSTREAM_5XX" : "UPSTREAM_4XX";
                String message = status + " (" + statusCode + ")";
                LOGGER.warn("Batch item {} upstream error: {}", item.index(), message);
                addError(itemResult, status, item.request().getMetrics());
            }
            resultsArray.add(itemResult);
        }

        int total = batchRequest.getItems().size();
        response.addProperty("status", resolveBatchStatus(total, successCount));
        response.add("results", resultsArray);
        return response;
    }

    private String resolveBatchStatus(int total, int successCount) {
        if (successCount == 0) {
            return "FAILED";
        }
        if (successCount == total) {
            return "SUCCESS";
        }
        return "PARTIAL_SUCCESS";
    }

    private void addError(JsonObject target, String status, List<String> requestedMetrics) {
        target.addProperty("status", status);
        addNullMetricFields(target, requestedMetrics);
    }

    private void addMetricFields(JsonObject target,
                                 List<SonarMetricValue> metrics,
                                 List<String> requestedMetrics) {
        Map<String, SonarMetricValue> metricsByKey = new HashMap<>();
        for (SonarMetricValue metricValue : metrics) {
            metricsByKey.put(metricValue.metric(), metricValue);
        }
        for (int i = 0; i < requestedMetrics.size(); i++) {
            String metricKey = requestedMetrics.get(i);
            int displayIndex = i + 1;
            String suffix = String.format("%02d", displayIndex);
            target.addProperty("metric" + suffix, metricKey);
            SonarMetricValue metricValue = metricsByKey.get(metricKey);
            if (metricValue != null) {
                target.addProperty("value" + suffix, metricValue.value());
                target.addProperty("bestValue" + suffix, metricValue.bestValue());
            } else {
                target.add("value" + suffix, JsonNull.INSTANCE);
                target.add("bestValue" + suffix, JsonNull.INSTANCE);
            }
        }
    }

    private void addNullMetricFields(JsonObject target, List<String> requestedMetrics) {
        for (int i = 0; i < requestedMetrics.size(); i++) {
            int displayIndex = i + 1;
            String suffix = String.format("%02d", displayIndex);
            target.add("metric" + suffix, JsonNull.INSTANCE);
            target.add("value" + suffix, JsonNull.INSTANCE);
            target.add("bestValue" + suffix, JsonNull.INSTANCE);
        }
    }

    private JsonObject parseJson(String requestBody) {
        return gson.fromJson(requestBody, JsonObject.class);
    }

    private String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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

    private void sendError(HttpExchange exchange, int statusCode, String status, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);
        payload.addProperty("message", message);
        sendJson(exchange, statusCode, payload);
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
