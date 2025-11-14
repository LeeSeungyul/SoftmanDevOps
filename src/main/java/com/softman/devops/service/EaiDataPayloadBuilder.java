package com.softman.devops.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;

public final class EaiDataPayloadBuilder {
    private static final List<String> RAW_FILTER_FIELDS = List.of(
            "tx_id",
            "bizsystem_id",
            "if_id",
            "atcl_nm",
            "version",
            "if_aprc_cd",
            "src_sys_cd",
            "src_sys_cd",
            "dev_src_tbl_nm",
            "prd_src_tbl_nm",
            "trgt_sys_cd",
            "dev_trgt_tbl_nm",
            "prd_trgt_tbl_nm",
            "ext_if_yn",
            "crnt_stng_cn",
            "prs_info_yn",
            "cre_dttm",
            "rgst_id",
            "mod_dttm",
            "mdfr_id"
    );

    private final List<String> filterFields;

    public EaiDataPayloadBuilder() {
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>(RAW_FILTER_FIELDS);
        this.filterFields = List.copyOf(deduplicated);
    }

    public JsonObject buildDataSection(String value) {
        JsonObject data = new JsonObject();
        data.addProperty("table", "v_if_master");
        data.add("filters", buildFilters(value));
        data.add("orderBy", buildOrderBy());
        data.addProperty("limit", 100);
        return data;
    }

    private JsonObject buildFilters(String value) {
        JsonObject filters = new JsonObject();
        filters.addProperty("logic", "OR");
        JsonArray conditions = new JsonArray();
        String wildcardValue = "%" + value + "%";
        for (String field : filterFields) {
            JsonObject condition = new JsonObject();
            condition.addProperty("field", field);
            condition.addProperty("operator", "LIKE");
            condition.addProperty("value", wildcardValue);
            conditions.add(condition);
        }
        filters.add("conditions", conditions);
        return filters;
    }

    private JsonArray buildOrderBy() {
        JsonArray array = new JsonArray();
        JsonObject order = new JsonObject();
        order.addProperty("field", "if_id");
        order.addProperty("direction", "ASC");
        array.add(order);
        return array;
    }

    List<String> getFilterFields() {
        return filterFields;
    }
}
