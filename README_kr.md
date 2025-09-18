# SoftmanDevOps 서비스

SoftmanDevOps는 엄격한 검증, 재시도, 타임아웃 및 동시성 정책을 적용하면서 SonarQube의 `/api/measures/component` 엔드포인트를 프록시하는 독립형 Java 21 HTTP 서비스입니다.

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

## 동작 하이라이트
- 공정한 세마포어로 글로벌 동시성 제한 적용; 초과 요청은 즉시 HTTP 429를 받습니다.
- 네트워크 오류, 5xx 및 429에 대해 지수 백오프(500ms 기준, 최대 5초)를 사용하여 재시도. 작업 타임아웃을 위반할 경우 백오프가 중단됩니다.
- 효과적인 호출당 타임아웃은 결합된 타이밍 제약을 충족하기 위해 `min(--timeout, 남은 작업 데드라인)`입니다.
- JSON 파싱은 Gson 사용; 외부 라이브러리는 Gson과 Logback으로 제한됩니다.

## 빌드 및 테스트
```
./gradlew build        # 래퍼를 사용할 수 없는 경우 `gradle build`
```
이것은 컴파일, 단위/통합 테스트 및 Jacoco 커버리지 검증(라인 ≥ 80%, 브랜치 ≥ 70%)을 실행합니다. 조립된 fat jar는 `build/libs/SoftmanDevOps.jar`에 생성됩니다.

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
    "custid": "ci-pipeline-01"
  }'
```

## 테스트 커버리지
JUnit 5 테스트에는 다음이 포함됩니다:
- CLI 파싱 및 기본 처리
- DTO 검증 규칙 (필수 필드, 메트릭 포맷팅)
- 임베디드 SonarQube 스텁을 사용한 서비스 레이어 재시도/타임아웃/헤더 로직
- HTTP 핸들러 통합: 검증 실패, 풀 리퀘스트 우선순위, 동시성 가드 및 응답 스키마
```