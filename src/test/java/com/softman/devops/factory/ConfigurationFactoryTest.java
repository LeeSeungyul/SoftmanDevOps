package com.softman.devops.factory;

import static org.junit.jupiter.api.Assertions.*;

import com.softman.devops.cli.CommandLineOptions;
import com.softman.devops.config.LogLevel;
import com.softman.devops.config.ServiceConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigurationFactoryTest {

    @Test
    void createConfigurationWithValidOptions() {
        CommandLineOptions options = new CommandLineOptions(
            false,
            Optional.of(8080),
            10,
            30,
            120,
            2,
            Optional.of("/tmp/logs")
        );

        ServiceConfiguration config = ConfigurationFactory.createConfiguration(options);

        assertNotNull(config);
        assertEquals(8080, config.getPort());
        assertEquals(10, config.getMaxConnections());
        assertEquals(Duration.ofSeconds(30), config.getRequestTimeout());
        assertEquals(Duration.ofSeconds(120), config.getJobTimeout());
        assertEquals(LogLevel.INFO, config.getLogLevel());
        assertEquals(Path.of("/tmp/logs"), config.getLogDirectory());
    }

    @Test
    void createConfigurationWithDefaultValues() {
        CommandLineOptions options = new CommandLineOptions(
            false,
            Optional.of(5050),
            5,
            60,
            180,
            2,
            Optional.empty()
        );

        ServiceConfiguration config = ConfigurationFactory.createConfiguration(options);

        assertNotNull(config);
        assertEquals(5050, config.getPort());
        assertEquals(5, config.getMaxConnections());
        assertEquals(Duration.ofSeconds(60), config.getRequestTimeout());
        assertEquals(Duration.ofSeconds(180), config.getJobTimeout());
        assertEquals(LogLevel.INFO, config.getLogLevel());
        assertEquals(Path.of("."), config.getLogDirectory());
    }

    @Test
    void createConfigurationThrowsWhenOptionsIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
            ConfigurationFactory.createConfiguration(null)
        );
    }

    @Test
    void createConfigurationThrowsWhenPortIsMissing() {
        CommandLineOptions options = new CommandLineOptions(
            false,
            Optional.empty(),
            5,
            60,
            180,
            2,
            Optional.empty()
        );

        assertThrows(IllegalArgumentException.class, () ->
            ConfigurationFactory.createConfiguration(options)
        );
    }
}