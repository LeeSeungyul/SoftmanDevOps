package com.softman.devops.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.dto.SonarMetricValue;
import com.softman.devops.dto.SonarMetricsRequest;
import com.softman.devops.handler.ValidationException;
import com.softman.devops.support.SonarStubServer;
import com.softman.devops.support.SonarStubServer.CapturedRequest;
import com.softman.devops.support.SonarStubServer.ResponsePlan;
import com.softman.devops.support.TestPorts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SonarMetricsServiceTest {
    private SonarStubServer sonarStubServer;

    @BeforeEach
    void setUp() {
        sonarStubServer = new SonarStubServer();
    }

    @AfterEach
    void tearDown() {
        sonarStubServer.close();
    }

    @Test
    void composesRequestAndParsesResponse() throws Exception {
        JsonObject response = successResponse("coverage", "85.3", true);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(5), Duration.ofSeconds(30));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "sample-component", "coverage");

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        CapturedRequest capturedRequest = sonarStubServer.takeRequest(Duration.ofSeconds(2));

        assertEquals(1, result.size());
        assertEquals("coverage", result.get(0).metric());
        assertTrue(capturedRequest.uri().getQuery().contains("component=sample-component"));
        assertTrue(capturedRequest.uri().getQuery().contains("metricKeys=coverage"));
        assertEquals("Basic c29uYXItdG9rZW46", capturedRequest.header("Authorization"));
    }

    @Test
    void parsesStringBestValueAsBoolean() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "coverage");
        measure.addProperty("value", "90");
        measure.addProperty("bestValue", "true");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "component", "coverage");

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertTrue(result.get(0).bestValue());
    }

    @Test
    void parsesNumericBestValueAsBoolean() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "bugs");
        measure.addProperty("value", "0");
        measure.addProperty("bestValue", 1);
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "component", "bugs");

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertTrue(result.get(0).bestValue());
    }

    @Test
    void buildsUrlWithBranchWhenPullRequestMissing() throws Exception {
        JsonObject response = successResponse("coverage", "82", true);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        json.addProperty("token", "token");
        json.addProperty("component", "component");
        json.addProperty("metrics", "coverage");
        json.addProperty("branch", "feature");
        SonarMetricsRequest request = SonarMetricsRequest.fromJson(json);

        service.fetchMetrics(request, Instant.now());
        CapturedRequest capturedRequest = sonarStubServer.takeRequest(Duration.ofSeconds(1));
        assertTrue(capturedRequest.uri().getQuery().contains("branch=feature"));
    }

    @Test
    void normalizesBaseUrlWhenTrailingSlashProvided() throws Exception {
        JsonObject response = successResponse("coverage", "80", true);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", "http://localhost:" + sonarStubServer.port() + "/");
        json.addProperty("token", "token");
        json.addProperty("component", "component");
        json.addProperty("metrics", "coverage");
        SonarMetricsRequest request = SonarMetricsRequest.fromJson(json);

        service.fetchMetrics(request, Instant.now());
        CapturedRequest capturedRequest = sonarStubServer.takeRequest(Duration.ofSeconds(1));
        assertEquals("/api/measures/component", capturedRequest.uri().getPath());
    }

    @Test
    void missingBestValueDefaultsToFalse() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "complexity");
        measure.addProperty("value", "12");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "component", "complexity");

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertTrue(!result.get(0).bestValue());
    }

    @Test
    void invalidMeasureStructureTriggersUpstreamError() throws Exception {
        JsonObject measure = new JsonObject();
        measure.add("metric", new JsonObject());
        measure.addProperty("value", "10");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "component", "bugs");

        UpstreamErrorException exception = assertThrows(UpstreamErrorException.class,
                () -> service.fetchMetrics(request, Instant.now()));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void retriesOnServerError() throws Exception {
        JsonObject response = successResponse("bugs", "3", false);
        sonarStubServer.enqueue(ResponsePlan.status(500));
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "project", "bugs", 1);

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertEquals("bugs", result.get(0).metric());
    }

    @Test
    void retriesOnTooManyRequests() throws Exception {
        JsonObject response = successResponse("coverage", "75", true);
        sonarStubServer.enqueue(ResponsePlan.status(429));
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "coverage", 1);

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertEquals("coverage", result.get(0).metric());
    }

    @Test
    void defaultRetriesAllowThreeReattempts() throws Exception {
        JsonObject response = successResponse("coverage", "70", true);
        sonarStubServer.enqueue(ResponsePlan.status(500));
        sonarStubServer.enqueue(ResponsePlan.status(502));
        sonarStubServer.enqueue(ResponsePlan.status(503));
        sonarStubServer.enqueue(ResponsePlan.success(response));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequestWithoutRetries("http://localhost:" + sonarStubServer.port(), "proj", "coverage");

        List<SonarMetricValue> result = service.fetchMetrics(request, Instant.now());
        assertEquals("coverage", result.get(0).metric());
    }

    @Test
    void doesNotRetryOnClientError() throws Exception {
        sonarStubServer.enqueue(ResponsePlan.status(404));
        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "sqale_index");

        UpstreamErrorException exception = assertThrows(UpstreamErrorException.class,
                () -> service.fetchMetrics(request, Instant.now()));
        assertEquals(404, exception.getStatusCode());
    }

    @Test
    void invalidResponseTriggersUpstreamError() throws Exception {
        sonarStubServer.enqueue(ResponsePlan.of(200, "{\"component\":{}}"));
        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(2), Duration.ofSeconds(10));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "bugs");

        UpstreamErrorException exception = assertThrows(UpstreamErrorException.class,
                () -> service.fetchMetrics(request, Instant.now()));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void networkFailureWrapsAsUpstreamError() throws Exception {
        int unusedPort = TestPorts.findAvailablePort();
        SonarMetricsService service = new SonarMetricsService(Duration.ofMillis(200), Duration.ofSeconds(1));
        SonarMetricsRequest request = buildRequest("http://localhost:" + unusedPort, "proj", "coverage", 0);

        UpstreamErrorException exception = assertThrows(UpstreamErrorException.class,
                () -> service.fetchMetrics(request, Instant.now()));
        assertEquals(503, exception.getStatusCode());
    }

    @Test
    void callTimeoutAfterRetries() throws Exception {
        JsonObject response = successResponse("sqale_index", "12", false);
        sonarStubServer.enqueue(ResponsePlan.successWithDelay(response, 1500));

        SonarMetricsService service = new SonarMetricsService(Duration.ofMillis(500), Duration.ofSeconds(3));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "sqale_index", 0);

        assertThrows(CallTimeoutException.class, () -> service.fetchMetrics(request, Instant.now()));
    }

    @Test
    void respectsJobDeadlineWhenLessThanCallTimeout() throws Exception {
        JsonObject response = successResponse("duplicated_lines_density", "1.2", false);
        sonarStubServer.enqueue(ResponsePlan.successWithDelay(response, 1200));

        SonarMetricsService service = new SonarMetricsService(Duration.ofSeconds(5), Duration.ofSeconds(1));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "duplicated_lines_density", 0);

        assertThrows(CallTimeoutException.class, () -> service.fetchMetrics(request, Instant.now()));
    }

    @Test
    void jobTimeoutExceededDuringBackoff() throws Exception {
        sonarStubServer.enqueue(ResponsePlan.status(503));
        SonarMetricsService service = new SonarMetricsService(Duration.ofMillis(200), Duration.ofMillis(600));
        SonarMetricsRequest request = buildRequest("http://localhost:" + sonarStubServer.port(), "proj", "bugs", 3);

        assertThrows(JobDeadlineExceededException.class, () -> service.fetchMetrics(request, Instant.now()));
    }

    @Test
    void constructorRejectsInvalidDurations() {
        assertThrows(IllegalArgumentException.class, () -> new SonarMetricsService(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SonarMetricsService(Duration.ofSeconds(1), Duration.ZERO));
    }

    private SonarMetricsRequest buildRequest(String baseUrl, String component, String metrics) throws ValidationException {
        return buildRequest(baseUrl, component, metrics, SonarMetricsRequest.DEFAULT_RETRIES);
    }

    private SonarMetricsRequest buildRequest(String baseUrl, String component, String metrics, int retries) throws ValidationException {
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", baseUrl);
        json.addProperty("token", "sonar-token");
        json.addProperty("component", component);
        json.addProperty("metrics", metrics);
        json.addProperty("retries", retries);
        return SonarMetricsRequest.fromJson(json);
    }

    private SonarMetricsRequest buildRequestWithoutRetries(String baseUrl, String component, String metrics) throws ValidationException {
        JsonObject json = new JsonObject();
        json.addProperty("baseurl", baseUrl);
        json.addProperty("token", "sonar-token");
        json.addProperty("component", component);
        json.addProperty("metrics", metrics);
        return SonarMetricsRequest.fromJson(json);
    }

    private JsonObject successResponse(String metric, String value, boolean bestValue) {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", metric);
        measure.addProperty("value", value);
        measure.addProperty("bestValue", bestValue);
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        return response;
    }
}
