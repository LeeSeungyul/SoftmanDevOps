[English Version](README.md)
# SoftmanDevOps 서비스

SoftmanDevOps는 엄격한 검증, 재시도, 타임아웃 및 동시성 정책을 적용하면서 SonarQube의 `/api/measures/component` 엔드포인트를 프록시하고 Jenkins 배포 페이로드를 다운스트림 서비스로 포워딩하는 독립형 Java 21 HTTP 서비스입니다.

## 런타임 요구사항
- OpenJDK 21+
- fat jar로 실행: `java -jar SoftmanDevOps.jar`
- Logback을 통해 설정된 로그 디렉토리(기본값: 작업 디렉토리) 내 `softmanlog-YYYY-MM-DD.log`로 로깅. 파일이 이미 존재하는 경우 추가됩니다.

## CLI 옵션
```
--port <number>        필수. HTTP 서비스를 바인딩할 포트.
--maxcon <number>      선택. 최대 동시 요청 수 (기본값 5).
--timeout <seconds>    선택. SonarQube 호출당 타임아웃 (기본값 60초).
--jobtimeout <seconds> 선택. 요청당 엔드투엔드 작업 데드라인 (기본값 180초).
--loglevel <1|2|3>     선택. 1=ERROR, 2=INFO (기본값), 3=DEBUG.
--logdir <path>        선택. 로그 파일 디렉토리 (기본값 현재 디렉토리).
--help                 이 메시지를 출력합니다.
```
`--port`가 생략되면, 서비스는 도움말 텍스트를 출력하고 종료됩니다.

## HTTP 엔드포인트

### Sonar Metrics
- **URL**: `/sonar/metrics`
- **메서드**: `POST`
- **요청 본문**: 기본 속성만 포함된 JSON.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseurl` | string | ✅ | SonarQube 기본 URL (예: `https://sonar.example.com`). |
| `token` | string | ✅ | Basic 인증에 사용되는 SonarQube 토큰. |
| `component` | string | ✅ | SonarQube 프로젝트 키. |
| `metrics` | string | ✅ | 쉼표로 구분된 소문자 메트릭 키 (공백이나 중복 없음). |
| `branch` | string | ❌ | 쿼리할 브랜치 (`pull_request`가 제공되면 무시됨). |
| `pull_request` | string | ❌ | 풀 리퀘스트 식별자. `branch`를 재정의합니다. |
| `retries` | number | ❌ | 5xx/429/네트워크 오류 시 재시도 횟수 (기본값 3). |
| `custid` | string | ❌ | 성공 시 다시 반환되는 선택적 소비자 식별자. |

빈 문자열, 대문자 메트릭 이름, 중복 메트릭 항목 또는 중첩된 JSON 구조는 `400 BAD_REQUEST` 응답을 발생시킵니다.
그 외의 원시 타입 필드는 응답에 그대로 포함되어 `customField1`, `customFlag`와 같은 사용자 메타데이터를 전달할 수 있습니다.

### 외부 SonarQube 호출
```
GET {baseurl}/api/measures/component?component=<component>&metricKeys=<metrics>[&pullRequest=<pull_request>][&branch=<branch>]
Authorization: Basic base64("{token}:")
```
`branch`는 `pull_request`가 없을 때만 추가됩니다.

### 응답 스키마
- **성공 (HTTP 200)**
```json
{
  "status": "SUCCESS",
  "custid": "optional",
  "result": [
    { "metric": "coverage", "value": "85.3", "bestValue": true }
  ]
}
```
- **실패**
```
400 BAD_REQUEST             -> 검증/JSON 오류
408 CALL_TIMEOUT            -> 재시도 소진 후 호출당 타임아웃
504 JOB_DEADLINE_EXCEEDED   -> 작업 데드라인 도달 (백오프 대기 포함)
<upstream 4xx> UPSTREAM_4XX -> 전파된 업스트림 클라이언트 실패
<upstream 5xx> UPSTREAM_5XX -> 전파된 업스트림/서버 또는 네트워크 실패
429 TOO_MANY_REQUESTS       -> 동시성 가드 제한 도달
```
실패 시 응답 본문:
```json
{ "status": "<CODE>", "message": "Description" }
```

### Jenkins Sonar 포워딩
- **URL**: `/jenkins/sonar`
- **메서드**: `POST`
- **Consumes / Produces**: `application/json; charset=utf-8`
- **역할**: Jenkins에서 전달되는 배포 메타데이터를 검증한 뒤 `baseurl`만 제외한 본문을 다운스트림 API로 전달합니다. 다운스트림의 HTTP 상태/헤더/본문을 그대로 다시 응답합니다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseurl` | string (URL) | ✅ | 타깃 서비스 URL. 공백을 제거한 후 이 URL로 바로 요청을 전송합니다. |
| `SM_ISID` | string | ✅ | 고유 식별자. |
| `SM_DEPUSER` | string | ✅ | 배포 사용자 ID. |
| `SM_WFDCODE` | string | ✅ | 워크플로 코드. |
| `DEP_DATE` | string | ✅ | 배포 시각 문자열 (서버는 파싱하지 않고 그대로 전달). |
| `CALL_URL` | string (path) | ✅ | 다운스트림 참조 경로. 요청 본문에 그대로 포함되지만 실제 호출 URL 구성에는 사용되지 않습니다. |
| `TABLE_TYPE` | string | ✅ | 테이블 코드. |
| `DEP_JOBS` | array | ✅ | 비어 있지 않은 잡 객체 배열. |
| `DEP_JOBS[].NAME` | string | ✅ | 포워딩할 Jenkins 잡 이름. |

그 외 추가적인 원시 타입 필드는 모두 보존되며 원본 키 이름 그대로 전달됩니다. 필수 필드 누락, 공백 문자열, 문자열이 아닌 `TABLE_TYPE`, 빈 `DEP_JOBS` 등 검증 오류가 발생하면 `400 BAD_REQUEST`를 반환합니다.

**포워딩 규칙**
- 타깃 URL = 공백을 제거한 `baseurl` 값 자체입니다. (경로/쿼리가 필요하면 `baseurl`에 모두 포함해 전달하세요.)
- 포워딩 본문 = 원본 JSON에서 `baseurl`만 제거한 나머지 필드 전체.
- 다운스트림 요청 헤더: `Content-Type: application/json; charset=utf-8`, `Accept: application/json`.
- 다운스트림 응답의 HTTP 상태/헤더/본문을 그대로 클라이언트에 전달하며, `content-length`/`transfer-encoding`은 서버가 재계산합니다.

**Jenkins 요청 예시**
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

**Jenkins 응답 예시 (Passthrough)**
```
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
X-Trace-Id: trace-001

{ ... 다운스트림 원본 본문 ... }
```

## 동작 하이라이트
- 공정한 세마포어로 글로벌 동시성 제한 적용; 초과 요청은 즉시 HTTP 429를 받습니다.
- 네트워크 오류, 5xx 및 429에 대해 지수 백오프(500ms 기준, 최대 5초)를 사용하여 재시도. 작업 타임아웃을 위반할 경우 백오프가 중단됩니다.
- 효과적인 호출당 타임아웃은 결합된 타이밍 제약을 충족하기 위해 `min(--timeout, 남은 작업 데드라인)`입니다.
- JSON 파싱은 Gson 사용; 외부 라이브러리는 Gson과 Logback으로 제한됩니다.
- Jenkins 포워딩 엔드포인트 역시 동일한 동시성 제한을 공유하며, 로그에는 목적지 정보를 마스킹한 상태로 남습니다. 원본 페이로드의 추가 필드는 손실 없이 유지됩니다.

## 빌드 및 테스트
```
./gradlew build        # 래퍼를 사용할 수 없는 경우 `gradle build`
```
저장소 루트에서 JDK 21 환경으로 위 명령을 실행하면 컴파일, 단위/통합 테스트, Jacoco 커버리지 검증(라인 ≥ 80%, 브랜치 ≥ 70%)이 이루어지고 실행 가능한 fat jar가 `build/libs/SoftmanDevOps.jar`에 생성됩니다.

```
./gradlew test         # 테스트만 실행
```

## 예제 요청
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

## 결과 예시
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

## 배치 엔드포인트
- **URL**: `/sonar/metrics_batch`
- **메서드**: `POST`
- **역할**: 여러 SonarQube 조회를 단일 HTTP 호출로 순차 실행합니다. 모든 항목은 동일한 서비스 레벨 작업 데드라인을 공유합니다.

### 최상위 필드
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseurl` | string | ❌ | 모든 항목에 적용되는 기본 SonarQube URL. 항목이 직접 제공하지 않으면 여기 값을 사용합니다. |
| `token` | string | ❌ | 모든 항목에 적용되는 기본 SonarQube 토큰. 항목별로 재정의할 수 있습니다. |
| `retries` | number | ❌ | 항목에서 `retries`를 생략했을 때 사용할 기본 재시도 횟수(기본 3). |
| `data` / `DATA` | array | ✅ | 순서를 유지하는 배치 항목 배열. |

### 항목 객체 필드 (`data[]`)
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `baseurl` | string | ❌ | 이 항목에만 적용되는 SonarQube URL. |
| `token` | string | ❌ | 이 항목에만 적용되는 SonarQube 토큰. |
| `component` | string | ✅ | 조회할 SonarQube 프로젝트 키. |
| `metrics` | string | ✅ | 쉼표로 구분된 소문자 메트릭 키. |
| `branch` | string | ❌ | 조회할 브랜치 (`pull_request`가 있으면 무시). |
| `pull_request` | string | ❌ | 풀 리퀘스트 식별자 (`branch`를 대체). |
| `retries` | number | ❌ | 이 항목 전용 재시도 횟수. |
| `custid` | string | ❌ | 항목 응답에 그대로 전달되는 선택적 식별자. |

항목에는 중첩 구조 없이 원시 타입만 허용되며, `baseurl`/`token`은 최상위 값으로 보정됩니다. `/sonar/metrics`와 동일한 동시성 제한이 적용되므로 배치 호출도 단일 슬롯만 사용합니다.
알 수 없는 원시 키도 각 항목 결과에 그대로 복사됩니다.

### 배치 요청 예시
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

### 배치 응답 예시
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

배치 응답의 `status`는 모든 항목 성공 시 `SUCCESS`, 일부 실패 시 `PARTIAL_SUCCESS`, 전부 실패 시 `FAILED`입니다. 항목별 `status`는 단일 엔드포인트에서 사용하는 코드와 동일하며, 성공한 항목의 메트릭은 `metricNN` / `valueNN` / `bestValueNN` (`NN` = `01`, `02`, ...) 형태로 평탄화되고 실패한 항목도 동일한 키를 `null` 값으로 채워 반환합니다.

이 평탄화된 항목들은 항상 `data[].metrics`에 적은 순서를 그대로 따릅니다. SonarQube 응답 순서가 달라지더라도 서버가 다시 정렬해 `metric01`은 첫 번째 요청 메트릭, `metric02`는 두 번째 요청 메트릭과 일치하도록 맞춰 줍니다.

## 테스트 커버리지
JUnit 5 테스트에는 다음이 포함됩니다:
- CLI 파싱 및 기본 처리
- DTO 검증 규칙 (필수 필드, 메트릭 포맷팅)
- 임베디드 SonarQube 스텁을 사용한 서비스 레이어 재시도/타임아웃/헤더 로직
- HTTP 핸들러 통합: 검증 실패, 풀 리퀘스트 우선순위, Jenkins 포워딩 Passthrough, 동시성 가드 및 응답 스키마
```
