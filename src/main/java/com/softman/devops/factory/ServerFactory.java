package com.softman.devops.factory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.softman.devops.SoftmanDevOpsServer;
import com.softman.devops.config.ServiceConfiguration;
import com.softman.devops.service.EaiDataForwardingClient;
import com.softman.devops.service.EaiDataPayloadBuilder;
import com.softman.devops.service.EaiDataService;
import com.softman.devops.service.JenkinsSonarForwardingService;
import com.softman.devops.service.SonarMetricsService;

public final class ServerFactory {

    private ServerFactory() {
    }

    public static SoftmanDevOpsServer createServer(ServiceConfiguration configuration) {
        validateConfiguration(configuration);

        Gson gson = createGson();
        SonarMetricsService sonarMetricsService = createSonarMetricsService(configuration);
        JenkinsSonarForwardingService jenkinsSonarForwardingService = createJenkinsSonarForwardingService(configuration);
        EaiDataService eaiDataService = createEaiDataService(configuration, gson);

        return new SoftmanDevOpsServer(configuration, sonarMetricsService, jenkinsSonarForwardingService, eaiDataService, gson);
    }

    private static Gson createGson() {
        return new GsonBuilder()
            .serializeNulls()
            .create();
    }

    private static SonarMetricsService createSonarMetricsService(ServiceConfiguration configuration) {
        return new SonarMetricsService(
            configuration.getRequestTimeout(),
            configuration.getJobTimeout()
        );
    }

    private static JenkinsSonarForwardingService createJenkinsSonarForwardingService(ServiceConfiguration configuration) {
        return new JenkinsSonarForwardingService(configuration.getRequestTimeout());
    }

    private static EaiDataService createEaiDataService(ServiceConfiguration configuration, Gson gson) {
        EaiDataPayloadBuilder payloadBuilder = new EaiDataPayloadBuilder();
        EaiDataForwardingClient forwardingClient = new EaiDataForwardingClient(configuration.getRequestTimeout(), gson);
        return new EaiDataService(payloadBuilder, forwardingClient);
    }

    private static void validateConfiguration(ServiceConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Service configuration cannot be null");
        }
    }
}
