[한국어 버전](README_kr.md)
# SoftmanDevOps Service

SoftmanDevOps is a standalone Java 21 HTTP service that proxies SonarQube's `/api/measures/component` endpoint while enforcing strict validation, retry, timeout, and concurrency policies.

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

## HTTP Endpoint
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

## Behaviour Highlights
- Global concurrency limit enforced with a fair semaphore; excess requests receive HTTP 429 immediately.
- Retries use exponential backoff (500ms base, capped at 5s) for network errors, 5xx, and 429. Backoff is aborted if it would violate the job timeout.
- Effective per-call timeout is `min(--timeout, remaining job deadline)` to satisfy combined timing constraints.
- JSON parsing uses Gson; external libraries are restricted to Gson and Logback.

## Building & Testing
```
./gradlew build        # or `gradle build` if the wrapper is unavailable
```
This runs compilation, unit/integration tests, and Jacoco coverage verification (line ≥ 80%, branch ≥ 70%). The assembled fat jar is produced at `build/libs/SoftmanDevOps.jar`.

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
    "custid": "ci-pipeline-01"
  }'
```

### Example Response
```json
{
  "status": "SUCCESS",
  "custid": "ci-pipeline-01",
  "result": [
    { "metric": "bugs", "value": "12", "bestValue": false },
    { "metric": "vulnerabilities", "value": "0", "bestValue": true },
    { "metric": "security_hotspots", "value": "3", "bestValue": false }
  ]
}
```

## Test Coverage
JUnit 5 tests include:
- CLI parsing and default handling
- DTO validation rules (required fields, metric formatting)
- Service layer retry/timeout/headers logic with an embedded SonarQube stub
- HTTP handler integration: validation failures, pull-request precedence, concurrency guard, and response schema
```
