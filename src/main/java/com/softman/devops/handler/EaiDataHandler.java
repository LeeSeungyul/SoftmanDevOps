package com.softman.devops.handler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.softman.devops.dto.EaiDataRequest;
import com.softman.devops.service.EaiDataService;
import com.softman.devops.service.EaiForwardingException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EaiDataHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EaiDataHandler.class);

    private final EaiDataService eaiDataService;
    private final Gson gson;
    private final AtomicInteger activeRequests;
    private final int maxConcurrentRequests;

    public EaiDataHandler(EaiDataService eaiDataService,
                          Gson gson,
                          AtomicInteger activeRequests,
                          int maxConcurrentRequests) {
        this.eaiDataService = Objects.requireNonNull(eaiDataService, "eaiDataService");
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
            LOGGER.warn("Rejecting EAI request due to concurrency limit");
            sendError(exchange, 429, "TOO_MANY_REQUESTS", "Maximum concurrent requests exceeded");
            return;
        }

        try {
            String body = readBody(exchange.getRequestBody());
            if (body.isBlank()) {
                throw new ValidationException("Request body must not be empty");
            }
            JsonObject jsonObject = parseJson(body);
            EaiDataRequest request = EaiDataRequest.fromJson(jsonObject);
            if (request.isValueBlank()) {
                LOGGER.info("Received blank value for /eai/data; skipping upstream call");
                sendResultSet(exchange, new JsonArray());
                return;
            }
            JsonArray resultSet = eaiDataService.fetchResultSet(request);
            sendResultSet(exchange, resultSet);
        } catch (PayloadTooLargeException payloadTooLargeException) {
            LOGGER.info("Payload exceeds limit: {}", payloadTooLargeException.getMessage());
            sendError(exchange, 413, "PAYLOAD_TOO_LARGE", payloadTooLargeException.getMessage());
        } catch (ValidationException validationException) {
            LOGGER.info("Validation failure for /eai/data: {}", validationException.getMessage());
            sendError(exchange, 400, "BAD_REQUEST", validationException.getMessage());
        } catch (JsonParseException parseException) {
            LOGGER.info("Malformed JSON payload for /eai/data", parseException);
            sendError(exchange, 400, "BAD_REQUEST", "Invalid JSON payload");
        } catch (EaiForwardingException forwardingException) {
            LOGGER.warn("EAI forwarding failure: {}", forwardingException.getMessage());
            sendError(exchange, forwardingException.getStatusCode(), "UPSTREAM_ERROR", forwardingException.getMessage());
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private JsonObject parseJson(String body) {
        return gson.fromJson(body, JsonObject.class);
    }

    private String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendResultSet(HttpExchange exchange, JsonArray resultSet) throws IOException {
        JsonObject payload = new JsonObject();
        payload.add("resultSet", resultSet.deepCopy());
        sendJson(exchange, 200, payload);
    }

    private void sendError(HttpExchange exchange, int statusCode, String status, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);
        payload.addProperty("message", message);
        sendJson(exchange, statusCode, payload);
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] data = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.put("Content-Type", List.of("application/json; charset=UTF-8"));
        responseHeaders.put("Cache-Control", List.of("no-store"));
        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(data);
        }
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
