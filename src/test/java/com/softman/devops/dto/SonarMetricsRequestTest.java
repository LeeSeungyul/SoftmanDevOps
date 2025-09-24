package com.softman.devops.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.softman.devops.handler.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SonarMetricsRequestTest {

    @Test
    void missingRequiredFieldThrows() {
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", "http://localhost");
        json.addProperty("token", "token");
        json.addProperty("metrics", "coverage");

        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void metricsMustBeLowercaseAndNonEmpty() {
        JsonObject json = baseRequest();
        json.addProperty("metrics", "Coverage");

        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void metricsMustNotContainEmptyEntry() {
        JsonObject json = baseRequest();
        json.addProperty("metrics", "coverage,,bugs");
        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void metricsMustNotContainDuplicates() {
        JsonObject json = baseRequest();
        json.addProperty("metrics", "coverage,coverage");
        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void metricsRejectInvalidCharacters() {
        JsonObject json = baseRequest();
        json.addProperty("metrics", "cover@ge");
        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void splitsMetricsIntoList() throws Exception {
        JsonObject json = baseRequest();
        json.addProperty("metrics", "coverage,bugs");

        SonarMetricsRequest request = SonarMetricsRequest.fromJson(json);
        assertEquals(List.of("coverage", "bugs"), request.getMetrics());
    }

    @Test
    void optionalStringRejectsBlank() {
        JsonObject json = baseRequest();
        json.addProperty("branch", " ");
        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    @Test
    void rejectsNestedJsonValues() {
        JsonObject json = baseRequest();
        JsonObject nested = new JsonObject();
        nested.addProperty("id", "value");
        json.add("custid", nested);
        assertThrows(ValidationException.class, () -> SonarMetricsRequest.fromJson(json));
    }

    private JsonObject baseRequest() {
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", "http://localhost");
        json.addProperty("token", "token");
        json.addProperty("component", "component");
        json.addProperty("metrics", "coverage");
        return json;
    }
}
