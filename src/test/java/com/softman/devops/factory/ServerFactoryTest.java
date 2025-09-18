package com.softman.devops.factory;

import static org.junit.jupiter.api.Assertions.*;

import com.softman.devops.SoftmanDevOpsServer;
import com.softman.devops.config.LogLevel;
import com.softman.devops.config.ServiceConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerFactoryTest {

    @Test
    void createServerWithValidConfiguration() {
        ServiceConfiguration config = new ServiceConfiguration(
            8080,
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(120),
            LogLevel.INFO,
            Path.of("/tmp")
        );

        SoftmanDevOpsServer server = ServerFactory.createServer(config);

        assertNotNull(server);
    }

    @Test
    void createServerWithMinimalConfiguration() {
        ServiceConfiguration config = new ServiceConfiguration(
            5051,
            5,
            Duration.ofSeconds(60),
            Duration.ofSeconds(180),
            LogLevel.ERROR,
            Path.of(".")
        );

        SoftmanDevOpsServer server = ServerFactory.createServer(config);

        assertNotNull(server);
    }

    @Test
    void createServerThrowsWhenConfigurationIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
            ServerFactory.createServer(null)
        );
    }

    @Test
    void createServerWithDifferentTimeouts() {
        ServiceConfiguration config = new ServiceConfiguration(
            9090,
            20,
            Duration.ofSeconds(10),
            Duration.ofSeconds(300),
            LogLevel.DEBUG,
            Path.of("./logs")
        );

        SoftmanDevOpsServer server = ServerFactory.createServer(config);

        assertNotNull(server);
    }
}