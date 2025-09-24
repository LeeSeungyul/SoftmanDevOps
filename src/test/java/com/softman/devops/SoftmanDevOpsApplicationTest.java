package com.softman.devops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.bootstrap.ApplicationBootstrap;
import com.softman.devops.cli.CommandLineParser;
import com.softman.devops.support.SonarStubServer;
import com.softman.devops.support.SonarStubServer.ResponsePlan;
import com.softman.devops.support.TestPorts;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoftmanDevOpsApplicationTest {
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void launchWithHelpPrintsUsageAndReturnsNull() {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer));

        SoftmanDevOpsServer server = ApplicationBootstrap.launch(new CommandLineParser(), new String[]{"--help"});

        assertNull(server);
        assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("SoftmanDevOps service options"));
    }

    @Test
    void launchWithoutPortPrintsErrorAndReturnsNull() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer));

        SoftmanDevOpsServer server = ApplicationBootstrap.launch(new CommandLineParser(), new String[]{"--timeout", "5"});

        assertNull(server);
        String output = errBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Missing required option --port"));
    }

    @Test
    void launchStartsServerAndServesRequests() throws Exception {
        Path logDir = Files.createTempDirectory("softman-logs");
        try (SonarStubServer sonarStubServer = new SonarStubServer()) {
            JsonObject measure = new JsonObject();
            measure.addProperty("metric", "coverage");
            measure.addProperty("value", "88.1");
            measure.addProperty("bestValue", true);
            JsonArray measures = new JsonArray();
            measures.add(measure);
            JsonObject component = new JsonObject();
            component.add("measures", measures);
            JsonObject response = new JsonObject();
            response.add("component", component);
            sonarStubServer.enqueue(ResponsePlan.success(response));

            int port = TestPorts.findAvailablePort();
            String[] args = {
                    "--port", String.valueOf(port),
                    "--logdir", logDir.toString(),
                    "--timeout", "2",
                    "--jobtimeout", "5"
            };

            SoftmanDevOpsServer server = ApplicationBootstrap.launch(new CommandLineParser(), args);
            assertNotNull(server);
            try {
                HttpClient client = HttpClient.newHttpClient();
                JsonObject payload = new JsonObject();
                payload.addProperty("baseurl", "http://localhost:" + sonarStubServer.port());
                payload.addProperty("token", "token-value");
                payload.addProperty("component", "component-key");
                payload.addProperty("metrics", "coverage");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/sonar/metrics"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .timeout(Duration.ofSeconds(2))
                        .build();

                HttpResponse<String> responseHttp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertEquals(200, responseHttp.statusCode());
                assertTrue(responseHttp.body().contains("SUCCESS"));
            } finally {
                server.stop();
            }
        }
    }

}
