package com.softman.devops.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
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
    private static final Gson GSON = new Gson();

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
