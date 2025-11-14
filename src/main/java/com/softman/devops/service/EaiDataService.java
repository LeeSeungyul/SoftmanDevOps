package com.softman.devops.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.softman.devops.dto.EaiDataRequest;
import java.util.Objects;

public final class EaiDataService {
    private final EaiDataPayloadBuilder payloadBuilder;
    private final EaiDataForwardingClient forwardingClient;

    public EaiDataService(EaiDataPayloadBuilder payloadBuilder, EaiDataForwardingClient forwardingClient) {
        this.payloadBuilder = Objects.requireNonNull(payloadBuilder, "payloadBuilder");
        this.forwardingClient = Objects.requireNonNull(forwardingClient, "forwardingClient");
    }

    public JsonArray fetchResultSet(EaiDataRequest request) throws EaiForwardingException {
        JsonObject dataSection = payloadBuilder.buildDataSection(request.getValue());
        return forwardingClient.forward(request.getBaseUrl(), request.getHeader(), dataSection);
    }
}
