package com.softman.devops.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.SoftmanDevOpsServer;
import com.softman.devops.config.LogLevel;
import com.softman.devops.config.ServiceConfiguration;
import com.softman.devops.service.SonarMetricsService;
import com.softman.devops.support.SonarStubServer;
import com.softman.devops.support.SonarStubServer.CapturedRequest;
import com.softman.devops.support.SonarStubServer.ResponsePlan;
import com.softman.devops.support.TestPorts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoftmanDevOpsServerIntegrationTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private SonarStubServer sonarStubServer;
    private SoftmanDevOpsServer softmanServer;
    private HttpClient httpClient;
    private int serverPort;

    @BeforeEach
    void setUp() {
        sonarStubServer = new SonarStubServer();
        httpClient = HttpClient.newHttpClient();
        serverPort = TestPorts.findAvailablePort();
    }

    @AfterEach
    void tearDown() {
        if (softmanServer != null) {
            softmanServer.stop();
        }
        sonarStubServer.close();
    }

    @Test
    void successfulRequestReturnsMetrics() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "coverage");
        measure.addProperty("value", "80.0");
        measure.addProperty("bestValue", true);
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPayload()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println("Raw body: " + httpResponse.body());
        assertEquals(200, httpResponse.statusCode());

        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("SUCCESS", body.get("status").getAsString());
        JsonArray result = body.getAsJsonArray("result");
        assertEquals(1, result.size());
        assertEquals("coverage", result.get(0).getAsJsonObject().get("metric").getAsString());
    }

    @Test
    void validationFailureReturnsBadRequest() throws Exception {
        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));
        JsonObject payloadObj = new JsonObject();
        payloadObj.addProperty("token", "abc");
        payloadObj.addProperty("component", "demo");
        payloadObj.addProperty("metrics", "coverage");
        String payload = payloadObj.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, httpResponse.statusCode());
        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
    }

    @Test
    void invalidJsonReturnsBadRequest() throws Exception {
        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{invalid"))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, httpResponse.statusCode());
        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
        assertTrue(body.get("message").getAsString().contains("Invalid JSON"));
    }

    @Test
    void getRequestReturnsMethodNotAllowed() throws Exception {
        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(405, response.statusCode());
    }

    @Test
    void custIdEchoedOnSuccess() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "ncloc");
        measure.addProperty("value", "1200");
        measure.addProperty("bestValue", false);
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "token");
        payload.addProperty("component", "cust");
        payload.addProperty("metrics", "ncloc");
        payload.addProperty("custid", "ci-123");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, httpResponse.statusCode());
        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("ci-123", body.get("custid").getAsString());
    }

    @Test
    void pullRequestOverridesBranch() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "bugs");
        measure.addProperty("value", "0");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "my-token");
        payload.addProperty("component", "demo-project");
        payload.addProperty("metrics", "bugs");
        payload.addProperty("branch", "main");
        payload.addProperty("pull_request", "42");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> responseHttp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, responseHttp.statusCode());
        CapturedRequest captured = sonarStubServer.takeRequest(Duration.ofSeconds(1));
        String query = captured.uri().getQuery();
        assertTrue(query.contains("pullRequest=42"));
        assertTrue(!query.contains("branch="));
    }

    @Test
    void rejectsWhenConcurrencyLimitExceeded() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "code_smells");
        measure.addProperty("value", "10");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.successWithDelay(response, 1200));

        startServer(1, Duration.ofSeconds(5), Duration.ofSeconds(10));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPayload()))
                .build();

        CompletableFuture<HttpResponse<String>> ongoing = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        sonarStubServer.takeRequest(Duration.ofSeconds(1));

        HttpResponse<String> rejected = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(429, rejected.statusCode(), "status=" + rejected.statusCode() + " body=" + rejected.body());
        JsonObject body = GSON.fromJson(rejected.body(), JsonObject.class);
        assertEquals("TOO_MANY_REQUESTS", body.get("status").getAsString());

        ongoing.get(3, TimeUnit.SECONDS);
    }

    @Test
    void batchRequestReturnsPartialSuccess() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "coverage");
        measure.addProperty("value", "81.0");
        measure.addProperty("bestValue", true);
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));
        sonarStubServer.enqueue(ResponsePlan.status(503));

        startServer(3, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "token-value");
        JsonArray data = new JsonArray();

        JsonObject firstItem = new JsonObject();
        firstItem.addProperty("component", "project-a");
        firstItem.addProperty("metrics", "coverage");
        data.add(firstItem);

        JsonObject secondItem = new JsonObject();
        secondItem.addProperty("component", "project-b");
        secondItem.addProperty("metrics", "bugs");
        secondItem.addProperty("custid", "ci-002");
        secondItem.addProperty("retries", 0);
        data.add(secondItem);

        payload.add("data", data);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics_batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, httpResponse.statusCode());

        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("PARTIAL_SUCCESS", body.get("status").getAsString());

        JsonArray results = body.getAsJsonArray("results");
        assertEquals(2, results.size());
        JsonObject firstResult = results.get(0).getAsJsonObject();
        assertEquals("SUCCESS", firstResult.get("status").getAsString());
        assertEquals("project-a", firstResult.get("component").getAsString());
        assertEquals("coverage", firstResult.get("metric01").getAsString());
        assertEquals("81.0", firstResult.get("value01").getAsString());
        assertTrue(firstResult.get("bestValue01").getAsBoolean());
        JsonObject secondResult = results.get(1).getAsJsonObject();
        assertEquals("UPSTREAM_5XX", secondResult.get("status").getAsString());
        assertEquals("project-b", secondResult.get("component").getAsString());
        assertTrue(secondResult.get("metric01").isJsonNull());
        assertTrue(secondResult.get("value01").isJsonNull());
        assertTrue(secondResult.get("bestValue01").isJsonNull());
    }

    @Test
    void batchRequestMissingBaseUrlFailsValidation() throws Exception {
        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("token", "token-value");
        JsonArray data = new JsonArray();
        JsonObject firstItem = new JsonObject();
        firstItem.addProperty("component", "project-a");
        firstItem.addProperty("metrics", "coverage");
        data.add(firstItem);
        payload.add("data", data);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics_batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, httpResponse.statusCode());
        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
        assertTrue(body.get("message").getAsString().contains("DATA[0]"));
    }

    @Test
    void batchRequestAcceptsUppercaseDataKey() throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("metric", "coverage");
        measure.addProperty("value", "90.0");
        JsonArray measures = new JsonArray();
        measures.add(measure);
        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        startServer(2, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "token-value");
        JsonArray data = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("component", "project-a");
        item.addProperty("metrics", "coverage");
        data.add(item);
        payload.add("DATA", data);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics_batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, httpResponse.statusCode());
        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        assertEquals("SUCCESS", body.get("status").getAsString());
        JsonArray results = body.getAsJsonArray("results");
        assertEquals(1, results.size());
        assertEquals("project-a", results.get(0).getAsJsonObject().get("component").getAsString());
    }

    @Test
    void batchRequestReturnsMetricsInRequestedOrder() throws Exception {
        JsonArray measures = new JsonArray();

        JsonObject coverage = new JsonObject();
        coverage.addProperty("metric", "coverage");
        coverage.addProperty("value", "83.7");
        coverage.addProperty("bestValue", true);
        measures.add(coverage);

        JsonObject securityHotspots = new JsonObject();
        securityHotspots.addProperty("metric", "security_hotspots");
        securityHotspots.addProperty("value", "4");
        securityHotspots.addProperty("bestValue", false);
        measures.add(securityHotspots);

        JsonObject bugs = new JsonObject();
        bugs.addProperty("metric", "bugs");
        bugs.addProperty("value", "2");
        bugs.addProperty("bestValue", false);
        measures.add(bugs);

        JsonObject component = new JsonObject();
        component.add("measures", measures);
        JsonObject response = new JsonObject();
        response.add("component", component);
        sonarStubServer.enqueue(ResponsePlan.success(response));

        startServer(3, Duration.ofSeconds(2), Duration.ofSeconds(10));

        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "token-value");
        JsonArray data = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("component", "project-c");
        item.addProperty("metrics", "bugs,coverage,security_hotspots");
        data.add(item);
        payload.add("data", data);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/sonar/metrics_batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, httpResponse.statusCode());

        JsonObject body = GSON.fromJson(httpResponse.body(), JsonObject.class);
        JsonArray results = body.getAsJsonArray("results");
        assertEquals(1, results.size());
        JsonObject first = results.get(0).getAsJsonObject();
        assertEquals("SUCCESS", first.get("status").getAsString());
        assertEquals("project-c", first.get("component").getAsString());

        assertEquals("bugs", first.get("metric01").getAsString());
        assertEquals("2", first.get("value01").getAsString());
        assertFalse(first.get("bestValue01").getAsBoolean());

        assertEquals("coverage", first.get("metric02").getAsString());
        assertEquals("83.7", first.get("value02").getAsString());
        assertTrue(first.get("bestValue02").getAsBoolean());

        assertEquals("security_hotspots", first.get("metric03").getAsString());
        assertEquals("4", first.get("value03").getAsString());
        assertFalse(first.get("bestValue03").getAsBoolean());
    }

    private void startServer(int maxConnections, Duration timeout, Duration jobTimeout) {
        Path logDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        ServiceConfiguration configuration = new ServiceConfiguration(serverPort, maxConnections, timeout, jobTimeout, LogLevel.INFO, logDirectory);
        SonarMetricsService service = new SonarMetricsService(timeout, jobTimeout);
        softmanServer = new SoftmanDevOpsServer(configuration, service, GSON);
        softmanServer.start();
    }

    private String buildPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
        payload.addProperty("token", "sonar-token");
        payload.addProperty("component", "sample-component");
        payload.addProperty("metrics", "coverage");
        return payload.toString();
    }
}
