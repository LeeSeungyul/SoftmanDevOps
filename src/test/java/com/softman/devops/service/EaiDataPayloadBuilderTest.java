package com.softman.devops.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EaiDataPayloadBuilderTest {

    @Test
    void buildDataSectionCreatesAllConditions() {
        EaiDataPayloadBuilder builder = new EaiDataPayloadBuilder();
        JsonObject data = builder.buildDataSection("keyword");

        assertEquals("v_if_master", data.get("table").getAsString());
        JsonObject filters = data.getAsJsonObject("filters");
        assertEquals("OR", filters.get("logic").getAsString());
        JsonArray conditions = filters.getAsJsonArray("conditions");
        Set<String> fields = new HashSet<>();
        conditions.forEach(element -> fields.add(element.getAsJsonObject().get("field").getAsString()));
        assertEquals(builder.getFilterFields().size(), conditions.size());
        assertEquals(builder.getFilterFields().size(), fields.size());
        assertTrue(conditions.get(0).getAsJsonObject().get("value").getAsString().startsWith("%"));

        JsonArray orderBy = data.getAsJsonArray("orderBy");
        assertEquals(1, orderBy.size());
        JsonObject order = orderBy.get(0).getAsJsonObject();
        assertEquals("if_id", order.get("field").getAsString());
        assertEquals("ASC", order.get("direction").getAsString());
        assertEquals(100, data.get("limit").getAsInt());
    }
}
