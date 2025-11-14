package com.softman.devops.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EaiDataForwardingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(EaiDataForwardingClient.class);

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Gson gson;

    public EaiDataForwardingClient(Duration requestTimeout, Gson gson) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.gson = Objects.requireNonNull(gson, "gson");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(requestTimeout)
                .build();
    }

    public JsonArray forward(String baseUrl, JsonObject header, JsonObject data)
            throws EaiForwardingException {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(data, "data");
        URI uri = buildUri(baseUrl);
        JsonObject payload = new JsonObject();
        payload.add("HEADER", header.deepCopy());
        payload.add("DATA", data.deepCopy());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();
        try {
            LOGGER.debug("Calling EAI upstream {}", uri);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return extractResultSet(response.body());
            }
            throw new EaiForwardingException("Upstream returned status " + response.statusCode(), response.statusCode());
        } catch (HttpTimeoutException timeoutException) {
            throw new EaiForwardingException("Upstream request timed out", 504, timeoutException);
        } catch (IOException ioException) {
            throw new EaiForwardingException("I/O error calling upstream service", 502, ioException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new EaiForwardingException("Forwarding interrupted", 503, interruptedException);
        }
    }

    private URI buildUri(String baseUrl) throws EaiForwardingException {
        try {
            URI uri = new URI(baseUrl);
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                throw new URISyntaxException(baseUrl, "scheme is missing");
            }
            if (uri.getHost() == null && uri.getAuthority() == null) {
                throw new URISyntaxException(baseUrl, "host is missing");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new EaiForwardingException("baseurl must be a valid absolute URL", 400, exception);
        }
    }

    private JsonArray extractResultSet(String body) throws EaiForwardingException {
        try {
            JsonElement rootElement = JsonParser.parseString(body);
            if (!rootElement.isJsonObject()) {
                throw new IllegalStateException("Response body must be a JSON object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonElement dataElement = root.get("DATA");
            if (dataElement == null || dataElement.isJsonNull()) {
                throw new IllegalStateException("DATA section is missing");
            }
            if (!dataElement.isJsonObject()) {
                throw new IllegalStateException("DATA section must be an object");
            }
            JsonObject dataObject = dataElement.getAsJsonObject();
            JsonElement resultElement = dataObject.get("resultSet");
            if (resultElement == null) {
                return new JsonArray();
            }
            if (resultElement.isJsonNull()) {
                return new JsonArray();
            }
            if (!resultElement.isJsonArray()) {
                throw new IllegalStateException("resultSet must be an array");
            }
            return resultElement.getAsJsonArray().deepCopy();
        } catch (RuntimeException runtimeException) {
            throw new EaiForwardingException("Invalid response from upstream service", 502, runtimeException);
        }
    }
}
