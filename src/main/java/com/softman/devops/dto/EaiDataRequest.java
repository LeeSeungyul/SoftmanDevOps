package com.softman.devops.dto;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.softman.devops.handler.PayloadTooLargeException;
import com.softman.devops.handler.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class EaiDataRequest {
    private static final int MAX_VALUE_BYTES = 1024;

    private final JsonObject header;
    private final String baseUrl;
    private final String value;

    private EaiDataRequest(JsonObject header, String baseUrl, String value) {
        this.header = header;
        this.baseUrl = baseUrl;
        this.value = value;
    }

    public JsonObject getHeader() {
        return header.deepCopy();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getValue() {
        return value;
    }

    public boolean isValueBlank() {
        return value == null || value.isBlank();
    }

    public static EaiDataRequest fromJson(JsonObject body)
            throws ValidationException, PayloadTooLargeException {
        if (body == null) {
            throw new ValidationException("Request body must be a JSON object");
        }
        JsonObject headerObject = readObject(body, "HEADER");
        JsonObject sanitizedHeader = sanitizeHeader(headerObject);
        JsonObject bodyObject = readObject(body, "BODY");
        String baseUrl = readNonBlankString(bodyObject, "baseurl");
        ensureValueWithinLimit(baseUrl, "BODY.baseurl");
        String value = readString(bodyObject, "value");
        ensureValueWithinLimit(value, "BODY.value");
        return new EaiDataRequest(sanitizedHeader, baseUrl.trim(), value);
    }

    private static JsonObject readObject(JsonObject source, String key) throws ValidationException {
        JsonElement element = source.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException(key + " must be provided");
        }
        if (!element.isJsonObject()) {
            throw new ValidationException(key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String readString(JsonObject source, String key) throws ValidationException {
        JsonElement element = source.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException(key + " must be provided");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ValidationException(key + " must be a string");
        }
        return element.getAsString();
    }

    private static String readNonBlankString(JsonObject source, String key) throws ValidationException {
        String value = readString(source, key).trim();
        if (value.isEmpty()) {
            throw new ValidationException(key + " must not be blank");
        }
        return value;
    }

    private static JsonObject sanitizeHeader(JsonObject header)
            throws ValidationException, PayloadTooLargeException {
        JsonObject sanitized = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : header.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                sanitized.add(key, JsonNull.INSTANCE);
                continue;
            }
            if (!value.isJsonPrimitive()) {
                throw new ValidationException("HEADER." + key + " must be a primitive or null");
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            ensureValueWithinLimit(primitive, "HEADER." + key);
            sanitized.add(key, primitive.deepCopy());
        }
        return sanitized;
    }

    private static void ensureValueWithinLimit(JsonPrimitive primitive, String field)
            throws PayloadTooLargeException {
        String textual;
        if (primitive.isString()) {
            textual = primitive.getAsString();
        } else {
            textual = primitive.toString();
        }
        ensureValueWithinLimit(textual, field);
    }

    private static void ensureValueWithinLimit(String value, String field) throws PayloadTooLargeException {
        if (value == null) {
            return;
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_VALUE_BYTES) {
            throw new PayloadTooLargeException(field + " exceeds " + MAX_VALUE_BYTES + " bytes");
        }
    }
}
