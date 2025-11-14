package com.softman.devops.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.softman.devops.handler.PayloadTooLargeException;
import com.softman.devops.handler.ValidationException;
import org.junit.jupiter.api.Test;

class EaiDataRequestTest {

    @Test
    void fromJsonParsesHeaderAndBody() throws Exception {
        JsonObject payload = buildPayload("http://localhost/api", "keyword");
        JsonObject header = payload.getAsJsonObject("HEADER");
        header.addProperty("glob_id", "CICD");
        header.addProperty("rstn_yn", 0);
        header.add("snd_svc_id", null);

        EaiDataRequest request = EaiDataRequest.fromJson(payload);
        assertEquals("http://localhost/api", request.getBaseUrl());
        assertEquals("keyword", request.getValue());
        JsonObject copied = request.getHeader();
        assertEquals("CICD", copied.get("glob_id").getAsString());
        assertEquals(0, copied.get("rstn_yn").getAsInt());
        assertEquals(true, copied.has("snd_svc_id"));
    }

    @Test
    void missingHeaderFailsValidation() {
        JsonObject payload = JsonParser.parseString("{" +
                "\"BODY\": { \"baseurl\": \"http://localhost\", \"value\": \"abc\" }" +
                "}").getAsJsonObject();

        assertThrows(ValidationException.class, () -> EaiDataRequest.fromJson(payload));
    }

    @Test
    void baseUrlMustNotBeBlank() {
        JsonObject payload = buildPayload("   ", "abc");
        assertThrows(ValidationException.class, () -> EaiDataRequest.fromJson(payload));
    }

    @Test
    void headerValuesLimitedToOneKilobyte() {
        JsonObject payload = buildPayload("http://localhost", "abc");
        String oversized = "a".repeat(1025);
        payload.getAsJsonObject("HEADER").addProperty("glob_id", oversized);
        assertThrows(PayloadTooLargeException.class, () -> EaiDataRequest.fromJson(payload));
    }

    private JsonObject buildPayload(String baseUrl, String value) {
        JsonObject header = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("baseurl", baseUrl);
        body.addProperty("value", value);
        JsonObject payload = new JsonObject();
        payload.add("HEADER", header);
        payload.add("BODY", body);
        return payload;
    }
}
