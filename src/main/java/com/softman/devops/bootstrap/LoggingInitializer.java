package com.softman.devops.bootstrap;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.softman.devops.config.LogLevel;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.LoggerFactory;

public final class LoggingInitializer {
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private LoggingInitializer() {
    }

    public static void initializeLogging(Path logDirectory, LogLevel logLevel) {
        Path absolutePath = logDirectory.toAbsolutePath();
        createLogDirectory(absolutePath);

        String logDate = LocalDate.now().format(LOG_DATE_FORMAT);
        System.setProperty("LOG_DIR", absolutePath.toString());
        System.setProperty("LOG_LEVEL", logLevel.name());
        System.setProperty("LOG_DATE", logDate);

        reconfigureLogback(absolutePath.toString(), logLevel.name(), logDate);
    }

    private static void createLogDirectory(Path logDirectory) {
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create log directory " + logDirectory, exception);
        }
    }

    private static void reconfigureLogback(String logDir, String logLevel, String logDate) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        synchronized (LoggingInitializer.class) {
            context.stop();
            context.reset();
            context.putProperty("LOG_DIR", logDir);
            context.putProperty("LOG_LEVEL", logLevel);
            context.putProperty("LOG_DATE", logDate);

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);

            URL config = LoggingInitializer.class.getClassLoader().getResource("logback.xml");
            if (config == null) {
                StatusPrinter.printInCaseOfErrorsOrWarnings(context);
                throw new IllegalStateException("logback.xml configuration file not found on classpath");
            }

            try {
                configurator.doConfigure(config);
            } catch (JoranException exception) {
                StatusPrinter.printInCaseOfErrorsOrWarnings(context);
                throw new IllegalStateException("Failed to configure logging", exception);
            }
        }
    }
}
