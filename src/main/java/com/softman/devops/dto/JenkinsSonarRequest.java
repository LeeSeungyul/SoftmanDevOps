package com.softman.devops.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.softman.devops.handler.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JenkinsSonarRequest {
    private static final Set<String> RESERVED_FORWARD_KEYS = Set.of(
            "baseurl",
            "SM_ISID",
            "SM_DEPUSER",
            "SM_WFDCODE",
            "DEP_DATE",
            "CALL_URL",
            "callurl",
            "calltime",
            "TABLE_TYPE",
            "DEP_JOBS"
    );

    private final String baseUrl;
    private final String smIsid;
    private final String smDepUser;
    private final String smWfdCode;
    private final String depDate;
    private final String callUrl;
    private final String callbackUrl;
    private final int callTimeSeconds;
    private final String tableType;
    private final List<Job> jobs;
    private final JsonObject forwardPayload;

    private JenkinsSonarRequest(String baseUrl,
                                String smIsid,
                                String smDepUser,
                                String smWfdCode,
                                String depDate,
                                String callUrl,
                                String callbackUrl,
                                int callTimeSeconds,
                                String tableType,
                                List<Job> filteredJobs,
                                JsonObject forwardPayload) {
        this.baseUrl = baseUrl;
        this.smIsid = smIsid;
        this.smDepUser = smDepUser;
        this.smWfdCode = smWfdCode;
        this.depDate = depDate;
        this.callUrl = callUrl;
        this.callbackUrl = callbackUrl;
        this.callTimeSeconds = callTimeSeconds;
        this.tableType = tableType;
        this.jobs = List.copyOf(filteredJobs);
        this.forwardPayload = Objects.requireNonNull(forwardPayload, "forwardPayload").deepCopy();
    }

    public static JenkinsSonarRequest fromJson(JsonObject body) throws ValidationException {
        if (body == null) {
            throw new ValidationException("Request body must be a JSON object");
        }

        String baseUrl = readRequiredString(body, "baseurl");
        validateUrl(baseUrl);

        String smIsid = readRequiredString(body, "SM_ISID");
        String smDepUser = readRequiredString(body, "SM_DEPUSER");
        String smWfdCode = readRequiredString(body, "SM_WFDCODE");
        String depDate = readRequiredString(body, "DEP_DATE");
        String callUrl = readRequiredString(body, "CALL_URL");
        String callbackUrl = readRequiredString(body, "callurl");
        validateCallbackUrl(callbackUrl);
        int callTimeSeconds = readRequiredNonNegativeInt(body, "calltime");
        String tableType = readRequiredString(body, "TABLE_TYPE");

        JsonArray jobsArray = readRequiredArray(body, "DEP_JOBS");
        List<Job> jobs = parseJobs(jobsArray);
        List<Job> filteredJobs = filterSkippedJobs(jobs);

        JsonObject forwardPayload = buildForwardPayload(body, smIsid, smDepUser, smWfdCode, depDate, callUrl, callbackUrl, callTimeSeconds, tableType, filteredJobs);

        return new JenkinsSonarRequest(
                normalizeBaseUrl(baseUrl),
                smIsid,
                smDepUser,
                smWfdCode,
                depDate,
                callUrl,
                callbackUrl,
                callTimeSeconds,
                tableType,
                filteredJobs,
                forwardPayload
        );
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String smIsid() {
        return smIsid;
    }

    public String smDepUser() {
        return smDepUser;
    }

    public String smWfdCode() {
        return smWfdCode;
    }

    public String depDate() {
        return depDate;
    }

    public String callUrl() {
        return callUrl;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    public int callTimeSeconds() {
        return callTimeSeconds;
    }

    public String tableType() {
        return tableType;
    }

    public List<Job> jobs() {
        return jobs;
    }

    public boolean hasForwardJobs() {
        return !jobs.isEmpty();
    }

    public JsonObject forwardPayload() {
        return forwardPayload.deepCopy();
    }

    public JsonObject callbackPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("SM_ISID", smIsid);
        payload.addProperty("SM_DEPUSER", smDepUser);
        payload.addProperty("WFDCODE", smWfdCode);
        payload.addProperty("RESULT", "SUCCESS");
        return payload;
    }

    private static String readRequiredString(JsonObject body, String key) throws ValidationException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException("Missing required field: " + key);
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ValidationException(key + " must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new ValidationException(key + " must not be blank");
        }
        return value;
    }

    private static JsonArray readRequiredArray(JsonObject body, String key) throws ValidationException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException("Missing required field: " + key);
        }
        if (!element.isJsonArray()) {
            throw new ValidationException(key + " must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw new ValidationException(key + " must not be empty");
        }
        return array;
    }

    private static List<Job> parseJobs(JsonArray jobsArray) throws ValidationException {
        List<Job> jobs = new ArrayList<>(jobsArray.size());
        for (int index = 0; index < jobsArray.size(); index++) {
            JsonElement element = jobsArray.get(index);
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                throw new ValidationException("DEP_JOBS[" + index + "] must be a JSON object");
            }
            JsonObject object = element.getAsJsonObject();
            String name = readRequiredName(object, index);
            jobs.add(new Job(name));
        }
        return jobs;
    }

    private static List<Job> filterSkippedJobs(List<Job> jobs) {
        List<Job> filtered = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            if (!job.isSkipped()) {
                filtered.add(job);
            }
        }
        return filtered;
    }

    private static String readRequiredName(JsonObject object, int index) throws ValidationException {
        JsonElement nameElement = object.get("NAME");
        if (nameElement == null || nameElement.isJsonNull()) {
            throw new ValidationException("DEP_JOBS[" + index + "] missing required field: NAME");
        }
        if (!nameElement.isJsonPrimitive() || !nameElement.getAsJsonPrimitive().isString()) {
            throw new ValidationException("DEP_JOBS[" + index + "].NAME must be a string");
        }
        String name = nameElement.getAsString().trim();
        if (name.isEmpty()) {
            throw new ValidationException("DEP_JOBS[" + index + "].NAME must not be blank");
        }
        return name;
    }

    private static JsonObject buildForwardPayload(JsonObject original,
                                                  String smIsid,
                                                  String smDepUser,
                                                  String smWfdCode,
                                                  String depDate,
                                                  String callUrl,
                                                  String callbackUrl,
                                                  int callTimeSeconds,
                                                  String tableType,
                                                  List<Job> jobs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("SM_ISID", smIsid);
        payload.addProperty("SM_DEPUSER", smDepUser);
        payload.addProperty("SM_WFDCODE", smWfdCode);
        payload.addProperty("DEP_DATE", depDate);
        payload.addProperty("CALL_URL", callUrl);
        payload.addProperty("TABLE_TYPE", tableType);
        payload.add("DEP_JOBS", buildJobsArray(jobs));

        for (String key : original.keySet()) {
            if (RESERVED_FORWARD_KEYS.contains(key)) {
                continue;
            }
            JsonElement element = original.get(key);
            payload.add(key, element == null ? JsonNull.INSTANCE : element.deepCopy());
        }

        return payload;
    }

    private static JsonArray buildJobsArray(List<Job> jobs) {
        JsonArray array = new JsonArray();
        for (Job job : jobs) {
            JsonObject jobObject = new JsonObject();
            jobObject.addProperty("NAME", job.name());
            array.add(jobObject);
        }
        return array;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.trim();
    }

    private static void validateUrl(String url) throws ValidationException {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new ValidationException("baseurl must be an absolute URL");
            }
        } catch (URISyntaxException exception) {
            throw new ValidationException("baseurl must be a valid URL");
        }
    }

    private static void validateCallbackUrl(String url) throws ValidationException {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                throw new ValidationException("callurl must be an absolute URL");
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new ValidationException("callurl must use http or https");
            }
        } catch (URISyntaxException exception) {
            throw new ValidationException("callurl must be a valid URL");
        }
    }

    private static int readRequiredNonNegativeInt(JsonObject body, String key) throws ValidationException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ValidationException("Missing required field: " + key);
        }
        if (!element.isJsonPrimitive()) {
            throw new ValidationException(key + " must be a number");
        }
        String text = element.getAsJsonPrimitive().getAsString().trim();
        if (text.isEmpty()) {
            throw new ValidationException(key + " must not be blank");
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException numberFormatException) {
            throw new ValidationException(key + " must be a whole number");
        }
        if (value < 0) {
            throw new ValidationException(key + " must not be negative");
        }
        return value;
    }

    public record Job(String name) {
        public Job {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        public boolean isSkipped() {
            return "skipped".equals(name);
        }
    }
}
