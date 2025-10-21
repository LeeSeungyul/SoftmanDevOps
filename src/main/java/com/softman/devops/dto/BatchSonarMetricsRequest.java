package com.softman.devops.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.softman.devops.handler.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BatchSonarMetricsRequest {
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

    private final List<BatchItem> items;

    private BatchSonarMetricsRequest(List<BatchItem> items) {
        this.items = List.copyOf(items);
    }

    public List<BatchItem> getItems() {
        return items;
    }

    public static BatchSonarMetricsRequest fromJson(JsonObject body) throws ValidationException {
        if (body == null) {
            throw new ValidationException("Request body must be a JSON object");
        }

        Optional<String> baseUrl = readOptionalString(body, "baseurl");
        Optional<String> token = readOptionalString(body, "token");
        Optional<Integer> retries = readOptionalNonNegativeInt(body, "retries");
        JsonArray dataArray = findDataArray(body);
        if (dataArray == null) {
            throw new ValidationException("DATA array must be provided");
        }
        if (dataArray.isEmpty()) {
            throw new ValidationException("DATA array must not be empty");
        }

        List<BatchItem> items = new ArrayList<>();
        for (int index = 0; index < dataArray.size(); index++) {
            JsonElement element = dataArray.get(index);
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                throw new ValidationException("DATA[" + index + "] must be a JSON object");
            }
            JsonObject itemObject = element.getAsJsonObject();
            ensureFlatObject("DATA[" + index + "]", itemObject);

            JsonObject merged = new JsonObject();
            copyOverrideOrFallback(itemObject, merged, "baseurl", baseUrl, index);
            copyOverrideOrFallback(itemObject, merged, "token", token, index);
            copyIfPresent(itemObject, merged, "component");
            copyIfPresent(itemObject, merged, "metrics");
            copyIfPresent(itemObject, merged, "branch");
            copyIfPresent(itemObject, merged, "pull_request");
            copyIfPresent(itemObject, merged, "custid");
            copyMetadata(itemObject, merged);

            Optional<Integer> itemRetries = readOptionalNonNegativeInt(itemObject, "retries");
            if (itemRetries.isPresent()) {
                merged.addProperty("retries", itemRetries.get());
            } else if (retries.isPresent()) {
                merged.addProperty("retries", retries.get());
            }

            try {
                SonarMetricsRequest request = SonarMetricsRequest.fromJson(merged);
                items.add(new BatchItem(index, request));
            } catch (ValidationException validationException) {
                throw new ValidationException("DATA[" + index + "]: " + validationException.getMessage());
            }
        }

        return new BatchSonarMetricsRequest(items);
    }

    private static void copyOverrideOrFallback(JsonObject source,
                                               JsonObject target,
                                               String key,
                                               Optional<String> fallback,
                                               int index) throws ValidationException {
        Optional<String> override = readOptionalString(source, key);
        if (override.isPresent()) {
            target.addProperty(key, override.get());
        } else if (fallback.isPresent()) {
            target.addProperty(key, fallback.get());
        } else {
            throw new ValidationException("DATA[" + index + "] missing required field: " + key);
        }
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        JsonElement element = source.get(key);
        if (element != null && !element.isJsonNull()) {
            target.add(key, element);
        }
    }

    private static void copyMetadata(JsonObject source, JsonObject target) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_KEYS.contains(key) || target.has(key)) {
                continue;
            }
            JsonElement element = entry.getValue();
            if (element != null && !element.isJsonNull()) {
                target.add(key, element.deepCopy());
            }
        }
    }

    private static JsonArray findDataArray(JsonObject body) throws ValidationException {
        JsonArray data = readOptionalArray(body, "data");
        if (data != null) {
            return data;
        }
        return readOptionalArray(body, "DATA");
    }

    private static JsonArray readOptionalArray(JsonObject body, String key) throws ValidationException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw new ValidationException(key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static Optional<String> readOptionalString(JsonObject object, String key) throws ValidationException {
        if (!object.has(key)) {
            return Optional.empty();
        }
        JsonElement element = object.get(key);
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

    private static Optional<Integer> readOptionalNonNegativeInt(JsonObject object, String key) throws ValidationException {
        if (!object.has(key)) {
            return Optional.empty();
        }
        JsonElement element = object.get(key);
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

    private static void ensureFlatObject(String context, JsonObject object) throws ValidationException {
        for (String key : object.keySet()) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull() && !isPrimitive(element)) {
                throw new ValidationException(context + " only allows primitive values for " + key);
            }
        }
    }

    private static boolean isPrimitive(JsonElement element) {
        return element.isJsonPrimitive();
    }

    public record BatchItem(int index, SonarMetricsRequest request) {
    }
}
