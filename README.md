[한국어 버전](README_kr.md)
# SoftmanDevOps Service

SoftmanDevOps is a standalone Java 21 HTTP service that proxies SonarQube's `/api/measures/component` endpoint and forwards Jenkins deployment payloads while enforcing strict validation, retry, timeout, and concurrency policies.

## Runtime Requirements
- OpenJDK 21+
- Executed as a fat jar: `java -jar SoftmanDevOps.jar`
- Logging via Logback to `softmanlog-YYYY-MM-DD.log` inside the configured log directory (default: working directory). Logs are appended if the file already exists.

## CLI Options
```
--port <number>        Required. HTTP port to bind the service.
--maxcon <number>      Optional. Maximum concurrent requests (default 5).
--timeout <seconds>    Optional. Per SonarQube call timeout (default 60s).
--jobtimeout <seconds> Optional. End-to-end job deadline per request (default 180s).
--loglevel <1|2|3>     Optional. 1=ERROR, 2=INFO (default), 3=DEBUG.
--logdir <path>        Optional. Directory for log files (default current directory).
--help                 Prints this message.
```
If `--port` is omitted, the service prints the help text and exits.

## HTTP Endpoints

### Sonar Metrics
- **URL**: `/sonar/metrics`
- **Method**: `POST`
- **Request Body**: JSON with primitive properties only.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `baseurl` | string | ✅ | SonarQube base URL (e.g. `https://sonar.example.com`). |
| `token` | string | ✅ | SonarQube token used for Basic auth. |
| `component` | string | ✅ | SonarQube project key. |
| `metrics` | string | ✅ | Comma-separated lowercase metric keys (no blanks or duplicates). |
| `branch` | string | ❌ | Branch to query (ignored when `pull_request` is supplied). |
| `pull_request` | string | ❌ | Pull request identifier. Overrides `branch`. |
| `retries` | number | ❌ | Number of retry attempts on 5xx/429/network errors (default 3). |
| `custid` | string | ❌ | Optional consumer identifier echoed back on success. |

Blank strings, uppercase metric names, duplicate metric entries, or nested JSON structures cause a `400 BAD_REQUEST` response.
All other primitive fields are echoed in the response payload, enabling callers to attach custom correlation metadata (e.g. `customField1`, `customFlag`).

### Outbound SonarQube Call
```
GET {baseurl}/api/measures/component?component=<component>&metricKeys=<metrics>[&pullRequest=<pull_request>][&branch=<branch>]
Authorization: Basic base64("{token}:")
```
`branch` is only added when `pull_request` is absent.

### Response Schema
- **Success (HTTP 200)**
```json
{
  "status": "SUCCESS",
  "custid": "optional",
  "result": [
    { "metric": "coverage", "value": "85.3", "bestValue": true }
  ]
}
```
- **Failures**
```
400 BAD_REQUEST             -> validation/JSON errors
408 CALL_TIMEOUT            -> per-call timeout after exhausting retries
504 JOB_DEADLINE_EXCEEDED   -> job deadline reached (including back-off waits)
<upstream 4xx> UPSTREAM_4XX -> propagated upstream client failure
<upstream 5xx> UPSTREAM_5XX -> propagated upstream/server or network failure
429 TOO_MANY_REQUESTS       -> concurrency guard limit reached
```
Response body on failure:
```json
{ "status": "<CODE>", "message": "Description" }
```

### Jenkins Sonar Forwarding
- **URL**: `/jenkins/sonar`
- **Method**: `POST`
- **Consumes / Produces**: `application/json; charset=utf-8`
- **Purpose**: Accept deployment metadata from Jenkins, validate it, and forward the payload (minus `baseurl`) to a downstream service. HTTP status, headers, and body from the downstream call are returned verbatim.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `baseurl` | string (URL) | ✅ | Target service URL. The request is sent directly to this URL after trimming whitespace. |
| `SM_ISID` | string | ✅ | Unique identifier. |
| `SM_DEPUSER` | string | ✅ | Deployment user ID. |
| `SM_WFDCODE` | string | ✅ | Workflow code. |
| `DEP_DATE` | string | ✅ | Deployment timestamp (opaque string, no parsing). |
| `CALL_URL` | string (path) | ✅ | Downstream reference path. Included in the forwarded body but not used to build the outbound URL. |
| `TABLE_TYPE` | string | ✅ | Table code. |
| `DEP_JOBS` | array | ✅ | Non-empty array of job objects. |
| `DEP_JOBS[].NAME` | string | ✅ | Jenkins job name to forward. |

All additional primitive fields are preserved and forwarded with their original casing. Validation failures (missing fields, blank strings, non-string `TABLE_TYPE`, empty `DEP_JOBS`, etc.) result in `400 BAD_REQUEST`.

**Forwarding rules**
- Target URL = trimmed `baseurl`. (Provide the complete upstream URL, including any path segments or query parameters.)
- Forwarded body = original JSON minus `baseurl`.
- Downstream request headers: `Content-Type: application/json; charset=utf-8`, `Accept: application/json`.
- Upstream HTTP status/headers/body are proxied back to the caller (content-length/transfer-encoding are recalculated).

**Jenkins Example Request**
```bash
curl -X POST http://localhost:5050/jenkins/sonar \
  -H "Content-Type: application/json" \
  -d '{
    "baseurl": "http://192.168.0.1/jenkins-result-sonar",
    "SM_ISID": "175",
    "SM_DEPUSER": "lee.hyun-ju",
    "SM_WFDCODE": "DEP_JEN",
    "DEP_DATE": "2025-10-21 오전 10:46:44",
    "CALL_URL": "/jenkins-result-sonar",
    "TABLE_TYPE": "3",
    "DEP_JOBS": [
      { "NAME": "sm-test2" },
      { "NAME": "sm-test3" }
    ]
  }'
```

**Jenkins Example Passthrough Response**
```
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
X-Trace-Id: trace-001

{ ... original downstream body ... }
```

## Behaviour Highlights
- Global concurrency limit enforced with a fair semaphore; excess requests receive HTTP 429 immediately.
- Retries use exponential backoff (500ms base, capped at 5s) for network errors, 5xx, and 429. Backoff is aborted if it would violate the job timeout.
- Effective per-call timeout is `min(--timeout, remaining job deadline)` to satisfy combined timing constraints.
- JSON parsing uses Gson; external libraries are restricted to Gson and Logback.
- Jenkins forwarding endpoint reuses the same concurrency limiter and masks destination details in logs while preserving opaque payload fields.

## Building & Testing
```
./gradlew build        # or `gradle build` if the wrapper is unavailable
```
This command must be executed from the repository root and requires JDK 21. It performs compilation, unit/integration tests, and Jacoco coverage verification (line ≥ 80%, branch ≥ 70%), then emits the runnable fat jar at `build/libs/SoftmanDevOps.jar`.

```
./gradlew test         # run tests only
```

## Example Request
```bash
curl -X POST http://localhost:5050/sonar/metrics \
  -H "Content-Type: application/json" \
  -d '{
    "baseurl": "https://sonar.example.com",
    "token": "sonar-token",
    "component": "project-key",
    "metrics": "coverage,bugs",
    "branch": "main",
    "retries": 3,
    "custid": "ci-pipeline-01",
    "customField1": "batch-trigger-007"
  }'
```

### Example Response
```json
{
  "status": "SUCCESS",
  "custid": "ci-pipeline-01",
  "customField1": "batch-trigger-007",
  "result": [
    { "metric": "bugs", "value": "12", "bestValue": false },
    { "metric": "vulnerabilities", "value": "0", "bestValue": true },
    { "metric": "security_hotspots", "value": "3", "bestValue": false }
  ]
}
```

## Batch Endpoint
- **URL**: `/sonar/metrics_batch`
- **Method**: `POST`
- **Purpose**: Execute multiple SonarQube lookups with one HTTP call. Batch items run sequentially and share the same configured job deadline.

### Top-Level Fields
| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `baseurl` | string | ❌ | Default SonarQube base URL; each item must supply one either here or inline. |
| `token` | string | ❌ | Default SonarQube token; items can override it per entry. |
| `retries` | number | ❌ | Default retry count for items that omit `retries` (defaults to 3). |
| `data` / `DATA` | array | ✅ | Ordered array of batch items. |

### Item Object Fields (`data[]`)
| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `baseurl` | string | ❌ | Overrides the top-level `baseurl` for this item. |
| `token` | string | ❌ | Overrides the top-level `token` for this item. |
| `component` | string | ✅ | SonarQube project key. |
| `metrics` | string | ✅ | Comma-separated lowercase metric keys. |
| `branch` | string | ❌ | Branch to query (`pull_request` takes precedence). |
| `pull_request` | string | ❌ | Pull request identifier. |
| `retries` | number | ❌ | Retry count for this item only. |
| `custid` | string | ❌ | Optional identifier echoed in the item response. |

Missing `baseurl` or `token` on an item is resolved from the top-level values, and all item fields must stay primitive (no nested objects/arrays). The usual concurrency limiter still applies, so a batch call consumes one slot regardless of the number of items.
Unknown primitive keys are preserved per item and reappear in the corresponding result object.

### Batch Example Request
```bash
curl -X POST http://localhost:5050/sonar/metrics_batch \
  -H "Content-Type: application/json" \
  -d '{
    "baseurl": "https://sonar.example.com",
    "token": "sonar-token",
    "retries": 2,
    "data": [
      {
        "component": "project-a",
        "metrics": "coverage,bugs",
        "custid": "ci-batch-01",
        "customField1": "release-203"
      },
      {
        "component": "project-b",
        "metrics": "security_hotspots",
        "branch": "dev",
        "retries": 0
      }
    ]
  }'
```

### Batch Example Response
```json
{
  "status": "PARTIAL_SUCCESS",
  "results": [
    {
      "component": "project-a",
      "custid": "ci-batch-01",
      "customField1": "release-203",
      "status": "SUCCESS",
      "metric01": "coverage",
      "value01": "81.0",
      "bestValue01": true,
      "metric02": "bugs",
      "value02": "5",
      "bestValue02": false
    },
    {
      "component": "project-b",
      "status": "UPSTREAM_5XX",
      "metric01": null,
      "value01": null,
      "bestValue01": null
    }
  ]
}
```

Batch-level `status` values are `SUCCESS` (all items succeeded), `PARTIAL_SUCCESS` (mixture), or `FAILED` (all items failed). Individual item statuses re-use the single-request status codes. Successful metrics are flattened into `metricNN` / `valueNN` / `bestValueNN` fields (`NN` = `01`, `02`, ...); failed items reuse the same keys with `null` values.

The flattened entries follow the same order you provide in `data[].metrics`. Even if SonarQube responds with a different ordering, the handler re-aligns each metric/value pair so `metric01` matches the first requested key, `metric02` the second, and so on.

## Test Coverage
JUnit 5 tests include:
- CLI parsing and default handling
- DTO validation rules (required fields, metric formatting)
- Service layer retry/timeout/headers logic with an embedded SonarQube stub
- HTTP handler integration: validation failures, pull-request precedence, Jenkins forwarding passthrough, concurrency guard, and response schema
```
