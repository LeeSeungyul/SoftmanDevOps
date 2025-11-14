package com.softman.devops.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.handler.ValidationException;
import org.junit.jupiter.api.Test;

class JenkinsSonarRequestTest {

    @Test
    void fromJsonNormalizesBaseUrlAndFiltersSkippedJobs() throws Exception {
        JsonObject payload = buildPayload();
        payload.addProperty("baseurl", "  http://example.com/api  ");
        payload.addProperty("customField", "value");
        JsonArray jobs = payload.getAsJsonArray("DEP_JOBS");
        jobs.get(0).getAsJsonObject().addProperty("NAME", " job-A ");
        JsonObject skipped = new JsonObject();
        skipped.addProperty("NAME", "skipped");
        jobs.add(skipped);

        JenkinsSonarRequest request = JenkinsSonarRequest.fromJson(payload);
        assertEquals("http://example.com/api", request.baseUrl());
        assertEquals(1, request.jobs().size());
        assertEquals("job-A", request.jobs().get(0).name());

        JsonObject forward = request.forwardPayload();
        assertEquals("value", forward.get("customField").getAsString());
        assertFalse(forward.has("baseurl"));
    }

    @Test
    void baseUrlMustBeAbsolute() {
        JsonObject payload = buildPayload();
        payload.addProperty("baseurl", "http:///missing");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void callUrlMustBeAbsolute() {
        JsonObject payload = buildPayload();
        payload.addProperty("callurl", "relative/path");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void callUrlMustUseHttpOrHttps() {
        JsonObject payload = buildPayload();
        payload.addProperty("callurl", "ftp://example.com/callback");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void callTimeMustBeNumericString() {
        JsonObject payload = buildPayload();
        payload.addProperty("calltime", "abc");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void callTimeMustNotBeNegative() {
        JsonObject payload = buildPayload();
        payload.addProperty("calltime", "-1");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void depJobsMustNotBeEmpty() {
        JsonObject payload = buildPayload();
        payload.add("DEP_JOBS", new JsonArray());
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void depJobsMustContainObjects() {
        JsonObject payload = buildPayload();
        JsonArray jobs = payload.getAsJsonArray("DEP_JOBS");
        jobs.set(0, payload.get("SM_ISID"));
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    @Test
    void jobNameMustNotBeBlank() {
        JsonObject payload = buildPayload();
        JsonArray jobs = payload.getAsJsonArray("DEP_JOBS");
        jobs.get(0).getAsJsonObject().addProperty("NAME", "   ");
        assertThrows(ValidationException.class, () -> JenkinsSonarRequest.fromJson(payload));
    }

    private JsonObject buildPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://example.com/api");
        payload.addProperty("SM_ISID", "175");
        payload.addProperty("SM_DEPUSER", "lee.hyun-ju");
        payload.addProperty("SM_WFDCODE", "DEP_JEN");
        payload.addProperty("DEP_DATE", "2025-10-21 10:46:44");
        payload.addProperty("CALL_URL", "/jenkins-result-sonar");
        payload.addProperty("callurl", "http://example.com/callback");
        payload.addProperty("calltime", "5");
        payload.addProperty("TABLE_TYPE", "3");
        JsonArray jobs = new JsonArray();
        JsonObject job = new JsonObject();
        job.addProperty("NAME", "job-1");
        jobs.add(job);
        payload.add("DEP_JOBS", jobs);
        return payload;
    }
}
