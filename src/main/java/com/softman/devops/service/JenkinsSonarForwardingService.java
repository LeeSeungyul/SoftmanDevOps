package com.softman.devops.service;

import com.softman.devops.dto.JenkinsSonarRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JenkinsSonarForwardingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JenkinsSonarForwardingService.class);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JenkinsSonarForwardingService(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(requestTimeout)
                .build();
    }

    public ForwardResponse forward(JenkinsSonarRequest request, String payloadJson) throws ForwardingException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(payloadJson, "payloadJson");
        URI targetUri = buildUri(request.baseUrl(), "baseurl");
        LOGGER.info("Forwarding Jenkins sonar request to {}", maskUri(targetUri));
        return send(targetUri, payloadJson);
    }

    public ForwardResponse postJson(String targetUrl, String payloadJson) throws ForwardingException {
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(payloadJson, "payloadJson");
        URI targetUri = buildUri(targetUrl, "callurl");
        LOGGER.info("Posting Jenkins sonar payload to {}", maskUri(targetUri));
        return send(targetUri, payloadJson);
    }

    private ForwardResponse send(URI targetUri, String payloadJson) throws ForwardingException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            LOGGER.info("Received response {} from {}", response.statusCode(), maskUri(targetUri));
            return new ForwardResponse(response.statusCode(), copyHeaders(response.headers().map()), response.body());
        } catch (HttpTimeoutException timeoutException) {
            LOGGER.warn("Forwarding timeout when calling {}", maskUri(targetUri));
            throw new ForwardingException("Upstream request timed out", 504, timeoutException);
        } catch (IOException ioException) {
            LOGGER.warn("Forwarding I/O error to {}: {}", maskUri(targetUri), ioException.getMessage());
            throw new ForwardingException("Failed to reach upstream service", 502, ioException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new ForwardingException("Forwarding interrupted", 503, interruptedException);
        }
    }

    private URI buildUri(String url, String fieldName) throws ForwardingException {
        try {
            return new URI(url);
        } catch (URISyntaxException exception) {
            throw new ForwardingException(fieldName + " must be a valid URL", 400, exception);
        }
    }

    private Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private String maskUri(URI uri) {
        if (uri.getHost() == null) {
            return uri.toString();
        }
        if (uri.getPort() == -1) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + uri.getPort();
    }

    public record ForwardResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public ForwardResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        public byte[] body() {
            return body.clone();
        }
    }
}
