package com.softman.devops.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.SoftmanDevOpsServer;
import com.softman.devops.config.LogLevel;
import com.softman.devops.config.ServiceConfiguration;
import com.softman.devops.service.EaiDataForwardingClient;
import com.softman.devops.service.EaiDataPayloadBuilder;
import com.softman.devops.service.EaiDataService;
import com.softman.devops.service.JenkinsSonarForwardingService;
import com.softman.devops.service.SonarMetricsService;
import com.softman.devops.support.ForwardingStubServer;
import com.softman.devops.support.ForwardingStubServer.CapturedRequest;
import com.softman.devops.support.ForwardingStubServer.ResponsePlan;
import com.softman.devops.support.TestPorts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JenkinsSonarEndpointIntegrationTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private ForwardingStubServer forwardingStub;
    private ForwardingStubServer callbackStub;
    private SoftmanDevOpsServer softmanServer;
    private HttpClient httpClient;
    private int serverPort;

    @BeforeEach
    void setUp() {
        forwardingStub = new ForwardingStubServer();
        callbackStub = new ForwardingStubServer();
        httpClient = HttpClient.newHttpClient();
        serverPort = TestPorts.findAvailablePort();
    }

    @AfterEach
    void tearDown() {
        if (softmanServer != null) {
            softmanServer.stop();
        }
        forwardingStub.close();
        callbackStub.close();
    }

    @Test
    void happyPathPassthroughsResponse() throws Exception {
        JsonObject upstreamBody = new JsonObject();
        upstreamBody.addProperty("status", "OK");
        upstreamBody.addProperty("count", 2);
        forwardingStub.enqueue(ResponsePlan.json(200, upstreamBody));

        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        JsonObject skippedJob = new JsonObject();
        skippedJob.addProperty("NAME", "skipped");
        payload.getAsJsonArray("DEP_JOBS").add(skippedJob);
        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(200, response.statusCode());
        assertEquals(upstreamBody.toString(), response.body());

        CapturedRequest captured = forwardingStub.takeRequest(Duration.ofSeconds(1));
        assertEquals("/jenkins-result-sonar", captured.uri().getPath());
        assertEquals("application/json; charset=utf-8", captured.header("Content-Type"));

        JsonObject forwarded = GSON.fromJson(captured.body(), JsonObject.class);
        assertFalse(forwarded.has("baseurl"));
        assertEquals("175", forwarded.get("SM_ISID").getAsString());
        assertEquals("3", forwarded.get("TABLE_TYPE").getAsString());
        JsonArray jobs = forwarded.getAsJsonArray("DEP_JOBS");
        assertEquals(2, jobs.size());
        assertEquals("sm-test2", jobs.get(0).getAsJsonObject().get("NAME").getAsString());
        assertThrows(IllegalStateException.class, () -> callbackStub.takeRequest(Duration.ofMillis(200)));
    }

    @Test
    void callUrlIsForwardedButNotUsedForTarget() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 4);
        forwardingStub.enqueue(ResponsePlan.status(204));

        String baseUrl = "http://localhost:" + forwardingStub.port() + "/jenkins-result-sonar";
        String callUrl = "/ignored-path";

        JsonObject payload = buildPayload(baseUrl, callUrl, callbackUrl(), 0);
        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(204, response.statusCode());

        CapturedRequest captured = forwardingStub.takeRequest(Duration.ofSeconds(1));
        assertEquals("/jenkins-result-sonar", captured.uri().getPath());

        JsonObject forwarded = GSON.fromJson(captured.body(), JsonObject.class);
        assertEquals(callUrl, forwarded.get("CALL_URL").getAsString());
        assertFalse(forwarded.has("callurl"));
        assertFalse(forwarded.has("calltime"));
        assertThrows(IllegalStateException.class, () -> callbackStub.takeRequest(Duration.ofMillis(200)));
    }

    @Test
    void allJobsSkippedReturnsLocalSuccess() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        callbackStub.enqueue(ResponsePlan.status(200));

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        JsonArray jobs = payload.getAsJsonArray("DEP_JOBS");
        jobs.get(0).getAsJsonObject().addProperty("NAME", "skipped");
        jobs.get(1).getAsJsonObject().addProperty("NAME", "skipped");

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(200, response.statusCode());

        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("175", body.get("SM_ISID").getAsString());
        assertEquals("lee.hyun-ju", body.get("SM_DEPUSER").getAsString());
        assertEquals("DEP_JEN", body.get("WFDCODE").getAsString());
        assertEquals("SUCCESS", body.get("RESULT").getAsString());
        assertThrows(IllegalStateException.class, () -> forwardingStub.takeRequest(Duration.ofMillis(200)));

        CapturedRequest callbackRequest = callbackStub.takeRequest(Duration.ofSeconds(1));
        assertEquals("/callback", callbackRequest.uri().getPath());
        JsonObject callbackBody = GSON.fromJson(callbackRequest.body(), JsonObject.class);
        assertEquals("SUCCESS", callbackBody.get("RESULT").getAsString());
    }

    @Test
    void callTimeAcceptsNumericString() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        callbackStub.enqueue(ResponsePlan.status(200));

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        payload.addProperty("calltime", "0");
        JsonArray jobs = payload.getAsJsonArray("DEP_JOBS");
        jobs.get(0).getAsJsonObject().addProperty("NAME", "skipped");
        jobs.get(1).getAsJsonObject().addProperty("NAME", "skipped");

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(200, response.statusCode());

        CapturedRequest callbackRequest = callbackStub.takeRequest(Duration.ofSeconds(1));
        assertEquals("/callback", callbackRequest.uri().getPath());
        JsonObject callbackBody = GSON.fromJson(callbackRequest.body(), JsonObject.class);
        assertEquals("SUCCESS", callbackBody.get("RESULT").getAsString());
    }

    @Test
    void emptyDepJobsFailsValidation() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        payload.add("DEP_JOBS", new JsonArray());

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(400, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
        assertThrows(IllegalStateException.class, () -> forwardingStub.takeRequest(Duration.ofMillis(200)));
    }

    @Test
    void missingDepJobsFailsValidation() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        payload.remove("DEP_JOBS");

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(400, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
        assertThrows(IllegalStateException.class, () -> forwardingStub.takeRequest(Duration.ofMillis(200)));
    }

    @Test
    void tableTypeMustBeString() throws Exception {
        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        payload.addProperty("TABLE_TYPE", 3);

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(400, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("BAD_REQUEST", body.get("status").getAsString());
        assertTrue(body.get("message").getAsString().contains("TABLE_TYPE"));
    }

    @Test
    void upstreamServerErrorsPassThrough() throws Exception {
        JsonObject upstreamBody = new JsonObject();
        upstreamBody.addProperty("status", "DOWN");
        forwardingStub.enqueue(ResponsePlan.withHeaders(503, upstreamBody.toString(), Map.of(
                "Content-Type", List.of("application/json"),
                "X-Trace-Id", List.of("trace-001")
        )));

        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(503, response.statusCode());
        assertEquals(upstreamBody.toString(), response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        assertEquals("trace-001", response.headers().firstValue("X-Trace-Id").orElse(null));
    }

    @Test
    void upstreamClientErrorsPassThrough() throws Exception {
        JsonObject upstreamBody = new JsonObject();
        upstreamBody.addProperty("message", "not found");
        forwardingStub.enqueue(ResponsePlan.json(404, upstreamBody));

        startServer(Duration.ofSeconds(2), Duration.ofSeconds(10), 5);

        JsonObject payload = buildPayload(baseUrl(), "/jenkins-result-sonar", callbackUrl(), 0);
        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(404, response.statusCode());
        assertEquals(upstreamBody.toString(), response.body());
    }

    private void startServer(Duration timeout, Duration jobTimeout, int maxConnections) {
        Path logDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        ServiceConfiguration configuration = new ServiceConfiguration(
                serverPort,
                maxConnections,
                timeout,
                jobTimeout,
                LogLevel.INFO,
                logDirectory
        );
        SonarMetricsService sonarMetricsService = new SonarMetricsService(timeout, jobTimeout);
        JenkinsSonarForwardingService forwardingService = new JenkinsSonarForwardingService(timeout);
        EaiDataService eaiDataService = new EaiDataService(new EaiDataPayloadBuilder(),
                new EaiDataForwardingClient(timeout, GSON));
        softmanServer = new SoftmanDevOpsServer(configuration, sonarMetricsService, forwardingService, eaiDataService, GSON);
        softmanServer.start();
    }

    private HttpResponse<String> sendRequest(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/jenkins/sonar"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonObject buildPayload(String baseUrl, String callUrl, String callbackUrl, int callTimeSeconds) {
        JsonObject payload = new JsonObject();
        payload.addProperty("baseurl", baseUrl);
        payload.addProperty("SM_ISID", "175");
        payload.addProperty("SM_DEPUSER", "lee.hyun-ju");
        payload.addProperty("SM_WFDCODE", "DEP_JEN");
        payload.addProperty("DEP_DATE", "2025-10-21 오전 10:46:44");
        payload.addProperty("CALL_URL", callUrl);
        payload.addProperty("callurl", callbackUrl);
        payload.addProperty("calltime", callTimeSeconds);
        payload.addProperty("TABLE_TYPE", "3");
        JsonArray jobs = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("NAME", "sm-test2");
        jobs.add(first);
        JsonObject second = new JsonObject();
        second.addProperty("NAME", "sm-test3");
        jobs.add(second);
        payload.add("DEP_JOBS", jobs);
        return payload;
    }

    private String baseUrl() {
        return "http://localhost:" + forwardingStub.port() + "/jenkins-result-sonar";
    }

    private String callbackUrl() {
        return "http://localhost:" + callbackStub.port() + "/callback";
    }
}
