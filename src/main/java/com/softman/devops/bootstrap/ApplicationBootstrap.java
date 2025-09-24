package com.softman.devops.bootstrap;

import com.softman.devops.SoftmanDevOpsServer;
import com.softman.devops.cli.CommandLineOptions;
import com.softman.devops.cli.CommandLineParser;
import com.softman.devops.config.ServiceConfiguration;
import com.softman.devops.factory.ConfigurationFactory;
import com.softman.devops.factory.ServerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ApplicationBootstrap {

    private ApplicationBootstrap() {
    }

    public static void main(String[] args) {
        CommandLineParser parser = new CommandLineParser();
        SoftmanDevOpsServer server;

        try {
            server = launch(parser, args);
        } catch (IllegalArgumentException parseException) {
            System.err.println("Failed to parse options: " + parseException.getMessage());
            System.err.println();
            System.err.println(parser.buildHelpMessage());
            return;
        }

        if (server == null) {
            return;
        }

        registerShutdownHook(server);
        LoggerFactory.getLogger(ApplicationBootstrap.class).info("SoftmanDevOps service is running");
        server.awaitShutdown();
    }

    public static SoftmanDevOpsServer launch(CommandLineParser parser, String[] args) {
        CommandLineOptions options = parser.parse(args);

        if (options.helpRequested()) {
            System.out.println(parser.buildHelpMessage());
            return null;
        }

        if (options.port().isEmpty()) {
            System.err.println("Missing required option --port");
            System.err.println(parser.buildHelpMessage());
            return null;
        }

        // Create configuration from command line options
        ServiceConfiguration configuration = ConfigurationFactory.createConfiguration(options);

        // Initialize logging with the configuration (must be done before any logger usage)
        LoggingInitializer.initializeLogging(
            configuration.getLogDirectory(),
            configuration.getLogLevel()
        );

        // Get logger after initialization
        Logger logger = LoggerFactory.getLogger(ApplicationBootstrap.class);
        logger.info(
            "SoftmanDevOps starting on port {} (maxCon={}, timeout={}s, jobTimeout={}s, logDir={})",
            configuration.getPort(),
            configuration.getMaxConnections(),
            configuration.getRequestTimeout().toSeconds(),
            configuration.getJobTimeout().toSeconds(),
            configuration.getLogDirectory().toAbsolutePath()
        );

        // Create and start the server
        SoftmanDevOpsServer server = ServerFactory.createServer(configuration);
        server.start();

        return server;
    }

    private static void registerShutdownHook(SoftmanDevOpsServer server) {
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}