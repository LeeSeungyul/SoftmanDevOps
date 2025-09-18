package com.softman.devops.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.softman.devops.dto.SonarMetricValue;
import com.softman.devops.dto.SonarMetricsRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SonarMetricsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SonarMetricsService.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    private static final long BACKOFF_BASE_MILLIS = 500L;

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration jobTimeout;
    private final Clock clock;

    public SonarMetricsService(Duration requestTimeout, Duration jobTimeout) {
        this(requestTimeout, jobTimeout, Clock.systemUTC());
    }

    public SonarMetricsService(Duration requestTimeout, Duration jobTimeout, Clock clock) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (jobTimeout == null || jobTimeout.isZero() || jobTimeout.isNegative()) {
            throw new IllegalArgumentException("jobTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.jobTimeout = jobTimeout;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(requestTimeout)
                .build();
    }

    public List<SonarMetricValue> fetchMetrics(SonarMetricsRequest request, Instant startTime)
            throws CallTimeoutException, JobDeadlineExceededException, UpstreamErrorException {
        Instant deadline = startTime.plus(jobTimeout);
        int remainingRetries = Math.max(0, request.getRetries());
        int attempt = 0;
        while (true) {
            attempt++;
            Duration remainingJobTime = remainingTime(deadline);
            if (remainingJobTime.isZero()) {
                throw new JobDeadlineExceededException("Job timeout exceeded before attempting call");
            }
            Duration attemptTimeout = minDuration(requestTimeout, remainingJobTime);
            HttpRequest httpRequest = buildHttpRequest(request, attemptTimeout);
            try {
                LOGGER.debug("Attempt {} calling SonarQube {}", attempt, httpRequest.uri());
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                LOGGER.debug("Attempt {} received status {}", attempt, status);
                if (status >= 200 && status < 300) {
                    return parseMetricsResponse(response.body());
                }
                if (shouldRetry(status) && remainingRetries > 0) {
                    remainingRetries--;
                    waitBeforeRetry(attempt, deadline);
                    continue;
                }
                if (status >= 400 && status < 500) {
                    throw new UpstreamErrorException("Upstream returned client error: " + status, status);
                }
                throw new UpstreamErrorException("Upstream returned server error: " + status, status);
            } catch (HttpTimeoutException timeoutException) {
                LOGGER.warn("Attempt {} timed out after {} seconds", attempt, attemptTimeout.toSeconds());
                if (remainingRetries > 0) {
                    remainingRetries--;
                    waitBeforeRetry(attempt, deadline);
                    continue;
                }
                throw new CallTimeoutException("Call timed out after attempts: " + attempt, timeoutException);
            } catch (IOException ioException) {
                LOGGER.warn("Attempt {} failed due to I/O error: {}", attempt, ioException.getMessage());
                if (remainingRetries > 0) {
                    remainingRetries--;
                    waitBeforeRetry(attempt, deadline);
                    continue;
                }
                throw new UpstreamErrorException("I/O error communicating with SonarQube", 503, ioException);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new JobDeadlineExceededException("Interrupted while waiting for SonarQube response");
            }
        }
    }

    private HttpRequest buildHttpRequest(SonarMetricsRequest request, Duration timeout) {
        String url = buildUrl(request);
        String tokenHeader = buildAuthorizationHeader(request.getToken());
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .header("Authorization", tokenHeader)
                .timeout(timeout)
                .build();
    }

    private String buildUrl(SonarMetricsRequest request) {
        String normalizedBase = normalizeBaseUrl(request.getBaseUrl());
        StringJoiner query = new StringJoiner("&");
        query.add("component=" + urlEncode(request.getComponent()));
        query.add("metricKeys=" + urlEncode(String.join(",", request.getMetrics())));
        if (request.getPullRequest().isPresent()) {
            query.add("pullRequest=" + urlEncode(request.getPullRequest().get()));
        } else if (request.getBranch().isPresent()) {
            query.add("branch=" + urlEncode(request.getBranch().get()));
        }
        return normalizedBase + "api/measures/component?" + query;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String buildAuthorizationHeader(String token) {
        String raw = token + ":";
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private Duration remainingTime(Instant deadline) {
        Instant now = clock.instant();
        if (now.isAfter(deadline)) {
            return Duration.ZERO;
        }
        return Duration.between(now, deadline);
    }

    private Duration minDuration(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private void waitBeforeRetry(int attempt, Instant deadline) throws JobDeadlineExceededException {
        long exponentialMillis = (long) (BACKOFF_BASE_MILLIS * Math.pow(2, Math.max(0, attempt - 1)));
        Duration backoff = Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), exponentialMillis));
        Instant now = clock.instant();
        if (now.plus(backoff).isAfter(deadline)) {
            throw new JobDeadlineExceededException("Job timeout would be exceeded during backoff");
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new JobDeadlineExceededException("Interrupted during retry backoff");
        }
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private List<SonarMetricValue> parseMetricsResponse(String body) throws UpstreamErrorException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject component = root.getAsJsonObject("component");
            if (component == null) {
                throw new IllegalStateException("component is missing");
            }
            JsonArray measures = component.getAsJsonArray("measures");
            if (measures == null) {
                throw new IllegalStateException("measures array is missing");
            }
            List<SonarMetricValue> results = new ArrayList<>();
            for (JsonElement element : measures) {
                JsonObject measureObject = element.getAsJsonObject();
                String metric = requireString(measureObject, "metric");
                String value = requireString(measureObject, "value");
                boolean bestValue = readBoolean(measureObject, "bestValue");
                results.add(new SonarMetricValue(metric, value, bestValue));
            }
            return results;
        } catch (RuntimeException runtimeException) {
            LOGGER.error("Failed to parse SonarQube response", runtimeException);
            throw new UpstreamErrorException("Invalid response from SonarQube", 502);
        }
    }

    private String requireString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException(key + " missing");
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        throw new IllegalStateException(key + " is not a primitive");
    }

    private boolean readBoolean(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isString()) {
                return Boolean.parseBoolean(element.getAsString().toLowerCase(Locale.ROOT));
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt() != 0;
            }
        }
        throw new IllegalStateException(key + " must be a boolean-compatible primitive");
    }
}
