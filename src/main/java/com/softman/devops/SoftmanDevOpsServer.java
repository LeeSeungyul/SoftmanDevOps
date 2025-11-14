package com.softman.devops;

import com.google.gson.Gson;
import com.softman.devops.config.ServiceConfiguration;
import com.softman.devops.handler.BatchSonarMetricsHandler;
import com.softman.devops.handler.EaiDataHandler;
import com.softman.devops.handler.JenkinsSonarHandler;
import com.softman.devops.handler.SonarMetricsHandler;
import com.softman.devops.service.EaiDataService;
import com.softman.devops.service.SonarMetricsService;
import com.softman.devops.service.JenkinsSonarForwardingService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SoftmanDevOpsServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoftmanDevOpsServer.class);

    private final HttpServer httpServer;
    private final ExecutorService executorService;
    private final ScheduledExecutorService callbackExecutor;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final int maxConnections;
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    public SoftmanDevOpsServer(ServiceConfiguration configuration,
                               SonarMetricsService sonarMetricsService,
                               JenkinsSonarForwardingService jenkinsSonarForwardingService,
                               EaiDataService eaiDataService,
                               Gson gson) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(sonarMetricsService, "sonarMetricsService");
        Objects.requireNonNull(jenkinsSonarForwardingService, "jenkinsSonarForwardingService");
        Objects.requireNonNull(eaiDataService, "eaiDataService");
        Objects.requireNonNull(gson, "gson");
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(configuration.getPort()), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start HTTP server on port " + configuration.getPort(), exception);
        }
        this.maxConnections = configuration.getMaxConnections();
        this.executorService = Executors.newCachedThreadPool(new NamedThreadFactory());
        AtomicInteger callbackCounter = new AtomicInteger(1);
        this.callbackExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("jenkins-callback-" + callbackCounter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        });
        this.httpServer.setExecutor(executorService);
        this.httpServer.createContext("/sonar/metrics",
                new SonarMetricsHandler(sonarMetricsService, gson, activeRequests, maxConnections));
        this.httpServer.createContext("/sonar/metrics_batch",
                new BatchSonarMetricsHandler(sonarMetricsService, gson, activeRequests, maxConnections));
        this.httpServer.createContext("/jenkins/sonar",
                new JenkinsSonarHandler(jenkinsSonarForwardingService, gson, activeRequests, maxConnections, callbackExecutor));
        this.httpServer.createContext("/eai/data",
                new EaiDataHandler(eaiDataService, gson, activeRequests, maxConnections));
    }

    public void start() {
        httpServer.start();
        LOGGER.info("SoftmanDevOps server started on port {}", httpServer.getAddress().getPort());
    }

    public void stop() {
        httpServer.stop(0);
        executorService.shutdownNow();
        callbackExecutor.shutdownNow();
        stopLatch.countDown();
        LOGGER.info("SoftmanDevOps server stopped");
    }

    public void awaitShutdown() {
        try {
            stopLatch.await();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("softman-worker-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
