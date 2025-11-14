package com.softman.devops.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EaiDataEndpointIntegrationTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private ForwardingStubServer upstream;
    private SoftmanDevOpsServer server;
    private HttpClient httpClient;
    private int serverPort;

    @BeforeEach
    void setUp() {
        upstream = new ForwardingStubServer();
        httpClient = HttpClient.newHttpClient();
        serverPort = TestPorts.findAvailablePort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        upstream.close();
    }

    @Test
    void happyPathBuildsDataPayloadAndReturnsResultSet() throws Exception {
        JsonObject upstreamBody = new JsonObject();
        JsonObject dataObject = new JsonObject();
        JsonArray resultSet = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("if_id", "IF-99");
        row.addProperty("tx_id", "tx_IF-99");
        resultSet.add(row);
        dataObject.add("resultSet", resultSet);
        upstreamBody.add("DATA", dataObject);
        upstream.enqueue(ResponsePlan.json(200, upstreamBody));

        startServer();
        JsonObject payload = buildPayload(eaiUrl("/if-api"), "search-key");
        payload.getAsJsonObject("HEADER").addProperty("glob_id", "CICDEAI");
        payload.getAsJsonObject("HEADER").addProperty("rstn_yn", 0);

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(200, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        JsonArray responseSet = body.getAsJsonArray("resultSet");
        assertEquals(1, responseSet.size());
        assertEquals("IF-99", responseSet.get(0).getAsJsonObject().get("if_id").getAsString());

        CapturedRequest captured = upstream.takeRequest(Duration.ofSeconds(1));
        assertEquals("/if-api", captured.uri().getPath());
        JsonObject forwarded = GSON.fromJson(captured.body(), JsonObject.class);
        assertEquals("CICDEAI", forwarded.getAsJsonObject("HEADER").get("glob_id").getAsString());
        JsonObject data = forwarded.getAsJsonObject("DATA");
        JsonArray conditions = data.getAsJsonObject("filters").getAsJsonArray("conditions");
        assertEquals("%search-key%", conditions.get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void blankValueSkipsUpstreamCall() throws Exception {
        startServer();
        JsonObject payload = buildPayload(eaiUrl("/skip"), "");

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(200, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals(0, body.getAsJsonArray("resultSet").size());
        assertThrows(IllegalStateException.class, () -> upstream.takeRequest(Duration.ofMillis(200)));
    }

    @Test
    void oversizedHeaderValueReturns413() throws Exception {
        startServer();
        JsonObject payload = buildPayload(eaiUrl("/limit"), "keyword");
        payload.getAsJsonObject("HEADER").addProperty("glob_id", "a".repeat(1025));

        HttpResponse<String> response = sendRequest(payload.toString());
        assertEquals(413, response.statusCode());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("PAYLOAD_TOO_LARGE", body.get("status").getAsString());
        assertThrows(IllegalStateException.class, () -> upstream.takeRequest(Duration.ofMillis(200)));
    }

    private HttpResponse<String> sendRequest(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/eai/data"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private String eaiUrl(String path) {
        return "http://localhost:" + upstream.port() + path;
    }

    private void startServer() {
        Duration timeout = Duration.ofSeconds(2);
        Duration jobTimeout = Duration.ofSeconds(10);
        int maxConnections = 5;
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
        JenkinsSonarForwardingService jenkinsService = new JenkinsSonarForwardingService(timeout);
        EaiDataService eaiDataService = new EaiDataService(new EaiDataPayloadBuilder(),
                new EaiDataForwardingClient(timeout, GSON));
        server = new SoftmanDevOpsServer(configuration, sonarMetricsService, jenkinsService, eaiDataService, GSON);
        server.start();
    }
}
