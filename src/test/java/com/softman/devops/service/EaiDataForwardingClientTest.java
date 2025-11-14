package com.softman.devops.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.support.ForwardingStubServer;
import com.softman.devops.support.ForwardingStubServer.CapturedRequest;
import com.softman.devops.support.ForwardingStubServer.ResponsePlan;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EaiDataForwardingClientTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private ForwardingStubServer stubServer;
    private EaiDataForwardingClient client;

    @BeforeEach
    void setUp() {
        stubServer = new ForwardingStubServer();
        client = new EaiDataForwardingClient(Duration.ofSeconds(3), GSON);
    }

    @AfterEach
    void tearDown() {
        stubServer.close();
    }

    @Test
    void forwardSendsHeaderAndData() throws Exception {
        JsonObject response = new JsonObject();
        JsonObject data = new JsonObject();
        JsonArray resultSet = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("if_id", "IF-001");
        resultSet.add(row);
        data.add("resultSet", resultSet);
        response.add("DATA", data);
        stubServer.enqueue(ResponsePlan.json(200, response));

        JsonObject header = new JsonObject();
        header.addProperty("glob_id", "abc");
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");
        JsonArray orderBy = new JsonArray();
        dataSection.add("orderBy", orderBy);

        JsonArray result = client.forward(baseUrl("/api"), header, dataSection);
        assertEquals(1, result.size());
        assertEquals("IF-001", result.get(0).getAsJsonObject().get("if_id").getAsString());

        CapturedRequest captured = stubServer.takeRequest(Duration.ofSeconds(1));
        JsonObject forwarded = GSON.fromJson(captured.body(), JsonObject.class);
        assertEquals("abc", forwarded.getAsJsonObject("HEADER").get("glob_id").getAsString());
        assertEquals("v_if_master", forwarded.getAsJsonObject("DATA").get("table").getAsString());
    }

    @Test
    void upstreamErrorsPropagateStatusCode() {
        stubServer.enqueue(ResponsePlan.status(500));
        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward(baseUrl("/fail"), header, dataSection));
        assertEquals(500, exception.getStatusCode());
    }

    @Test
    void invalidBaseUrlThrowsValidation() {
        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");
        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward("not-a-url", header, dataSection));
        assertEquals(400, exception.getStatusCode());
    }

    @Test
    void missingHostInBaseUrlFailsValidation() {
        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");
        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward("http:///missing" , header, dataSection));
        assertEquals(400, exception.getStatusCode());
    }

    @Test
    void missingDataSectionCausesUpstreamError() {
        JsonObject root = new JsonObject();
        stubServer.enqueue(ResponsePlan.json(200, root));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward(baseUrl("/missing"), header, dataSection));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void nullResultSetReturnsEmptyArray() throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject dataObject = new JsonObject();
        dataObject.add("resultSet", com.google.gson.JsonNull.INSTANCE);
        payload.add("DATA", dataObject);
        stubServer.enqueue(ResponsePlan.json(200, payload));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        JsonArray result = client.forward(baseUrl("/null"), header, dataSection);
        assertEquals(0, result.size());
    }

    @Test
    void missingResultSetReturnsEmptyArray() throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject dataObject = new JsonObject();
        payload.add("DATA", dataObject);
        stubServer.enqueue(ResponsePlan.json(200, payload));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        JsonArray result = client.forward(baseUrl("/missing-result"), header, dataSection);
        assertEquals(0, result.size());
    }

    @Test
    void nonArrayResultSetThrowsException() {
        JsonObject payload = new JsonObject();
        JsonObject dataObject = new JsonObject();
        JsonObject single = new JsonObject();
        single.addProperty("if_id", "bad");
        dataObject.add("resultSet", single);
        payload.add("DATA", dataObject);
        stubServer.enqueue(ResponsePlan.json(200, payload));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward(baseUrl("/non-array"), header, dataSection));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void nonObjectResponseBodyThrowsError() {
        stubServer.enqueue(ResponsePlan.json(200, "[]"));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward(baseUrl("/array"), header, dataSection));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void nonObjectDataSectionThrowsError() {
        JsonObject payload = new JsonObject();
        payload.addProperty("DATA", "oops");
        stubServer.enqueue(ResponsePlan.json(200, payload));

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward(baseUrl("/wrong-data"), header, dataSection));
        assertEquals(502, exception.getStatusCode());
    }

    @Test
    void slowResponseTriggersTimeout() {
        JsonObject payload = new JsonObject();
        JsonObject dataObject = new JsonObject();
        dataObject.add("resultSet", new JsonArray());
        payload.add("DATA", dataObject);
        ResponsePlan delayed = new ResponsePlan(200, payload.toString(),
                Map.of("Content-Type", List.of("application/json; charset=utf-8")), 2_000);
        stubServer.enqueue(delayed);

        EaiDataForwardingClient shortClient = new EaiDataForwardingClient(Duration.ofMillis(500), GSON);
        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> shortClient.forward(baseUrl("/slow"), header, dataSection));
        assertEquals(504, exception.getStatusCode());
    }

    @Test
    void connectionFailureRaisesIoError() {
        ForwardingStubServer temporary = new ForwardingStubServer();
        int port = temporary.port();
        temporary.close();

        JsonObject header = new JsonObject();
        JsonObject dataSection = new JsonObject();
        dataSection.addProperty("table", "v_if_master");

        EaiForwardingException exception = assertThrows(EaiForwardingException.class,
                () -> client.forward("http://localhost:" + port + "/down", header, dataSection));
        assertEquals(502, exception.getStatusCode());
    }

    private String baseUrl(String path) {
        return "http://localhost:" + stubServer.port() + path;
    }
}
