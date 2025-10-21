package com.softman.devops.dto;

import com.softman.devops.handler.ValidationException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SonarMetricsRequest {
    public static final int DEFAULT_RETRIES = 3;
    private static final Pattern METRIC_TOKEN_PATTERN = Pattern.compile("[a-z0-9_.:-]+");

    private static final Set<String> RESERVED_KEYS = Set.of(
            "baseurl",
            "token",
            "component",
            "metrics",
            "branch",
            "pull_request",
            "custid",
            "retries"
    );

    private final String baseUrl;
    private final String token;
    private final String component;
    private final List<String> metrics;
    private final Optional<String> branch;
    private final Optional<String> pullRequest;
    private final int retries;
    private final Optional<String> customerId;
    private final Map<String, JsonElement> metadata;

    private SonarMetricsRequest(String baseUrl,
                                String token,
                                String component,
                                List<String> metrics,
                                Optional<String> branch,
                                Optional<String> pullRequest,
                                int retries,
                                Optional<String> customerId,
                                Map<String, JsonElement> metadata) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.component = component;
        this.metrics = List.copyOf(metrics);
        this.branch = branch;
        this.pullRequest = pullRequest;
        this.retries = retries;
        this.customerId = customerId;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static SonarMetricsRequest fromJson(JsonObject body) throws ValidationException {
        ensureFlatObject(body);
        String baseUrl = readRequiredString(body, "baseurl");
        String token = readRequiredString(body, "token");
        String component = readRequiredString(body, "component");
        String metricsRaw = readRequiredString(body, "metrics");
        List<String> metricList = parseMetrics(metricsRaw);

        Optional<String> branch = readOptionalString(body, "branch");
        Optional<String> pullRequest = readOptionalString(body, "pull_request");
        Optional<String> customerId = readOptionalString(body, "custid");
        int retries = readOptionalNonNegativeInt(body, "retries").orElse(DEFAULT_RETRIES);

        Map<String, JsonElement> metadata = collectMetadata(body);

        return new SonarMetricsRequest(baseUrl, token, component, metricList, branch, pullRequest, retries, customerId, metadata);
    }

    private static void ensureFlatObject(JsonObject body) throws ValidationException {
        for (String key : body.keySet()) {
            JsonElement element = body.get(key);
            if (element != null && !element.isJsonNull() && !element.isJsonPrimitive()) {
                throw new ValidationException("Only primitive values are allowed for " + key);
            }
        }
    }

    private static List<String> parseMetrics(String rawMetrics) throws ValidationException {
        String trimmed = rawMetrics.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("metrics must not be empty");
        }
        String[] tokens = trimmed.split(",");
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : tokens) {
            String candidate = token.trim();
            if (candidate.isEmpty()) {
                throw new ValidationException("metrics must not contain empty entries");
            }
            if (!candidate.equals(candidate.toLowerCase(Locale.ROOT))) {
                throw new ValidationException("metrics must be lowercase: " + candidate);
            }
            if (!METRIC_TOKEN_PATTERN.matcher(candidate).matches()) {
                throw new ValidationException("metrics contain invalid characters: " + candidate);
            }
            if (!seen.add(candidate)) {
                throw new ValidationException("metrics must not contain duplicates: " + candidate);
            }
            normalized.add(candidate);
        }
        return normalized;
    }

    private static String readRequiredString(JsonObject body, String key) throws ValidationException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException("Missing required field: " + key);
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ValidationException(key + " must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new ValidationException(key + " must not be blank");
        }
        return value;
    }

    private static Optional<String> readOptionalString(JsonObject body, String key) throws ValidationException {
        if (!body.has(key)) {
            return Optional.empty();
        }
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ValidationException(key + " must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new ValidationException(key + " must not be blank");
        }
        return Optional.of(value);
    }

    private static Optional<Integer> readOptionalNonNegativeInt(JsonObject body, String key) throws ValidationException {
        if (!body.has(key)) {
            return Optional.empty();
        }
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ValidationException(key + " must be a number");
        }
        int value = element.getAsInt();
        if (value < 0) {
            throw new ValidationException(key + " must not be negative");
        }
        return Optional.of(value);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getToken() {
        return token;
    }

    public String getComponent() {
        return component;
    }

    public List<String> getMetrics() {
        return metrics;
    }

    public Optional<String> getBranch() {
        return branch;
    }

    public Optional<String> getPullRequest() {
        return pullRequest;
    }

    public int getRetries() {
        return retries;
    }

    public Optional<String> getCustomerId() {
        return customerId;
    }

    public Map<String, JsonElement> getMetadata() {
        return metadata;
    }

    private static Map<String, JsonElement> collectMetadata(JsonObject body) {
        Map<String, JsonElement> extras = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_KEYS.contains(key)) {
                continue;
            }
            JsonElement element = entry.getValue();
            if (element == null || element.isJsonNull()) {
                continue;
            }
            extras.put(key, element.deepCopy());
        }
        return extras;
    }
}
