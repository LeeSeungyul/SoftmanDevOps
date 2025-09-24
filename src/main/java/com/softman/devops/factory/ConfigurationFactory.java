package com.softman.devops.factory;

import com.softman.devops.cli.CommandLineOptions;
import com.softman.devops.config.LogLevel;
import com.softman.devops.config.ServiceConfiguration;
import java.nio.file.Path;
import java.time.Duration;

public final class ConfigurationFactory {

    private ConfigurationFactory() {
    }

    public static ServiceConfiguration createConfiguration(CommandLineOptions options) {
        validateOptions(options);

        int port = options.port().orElseThrow(() ->
            new IllegalArgumentException("Port is required"));
        int maxConnections = options.maxConnections();
        Duration timeout = Duration.ofSeconds(options.timeoutSeconds());
        Duration jobTimeout = Duration.ofSeconds(options.jobTimeoutSeconds());
        LogLevel logLevel = LogLevel.fromCode(options.logLevelCode());
        Path logDirectory = options.logDirectory()
            .map(Path::of)
            .orElse(Path.of("."));

        return new ServiceConfiguration(
            port,
            maxConnections,
            timeout,
            jobTimeout,
            logLevel,
            logDirectory
        );
    }

    private static void validateOptions(CommandLineOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Command line options cannot be null");
        }

        if (options.port().isEmpty()) {
            throw new IllegalArgumentException("Port is required");
        }
    }
}