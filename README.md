# log-library

`log-library`는 Spring Boot 서비스에서 이벤트 로그를 공통 방식으로 적재/조회하기 위한 라이브러리입니다.

- `log-core`: 로그 큐 적재, 배치 반영, 조회 API 핵심 로직
- `log-spring-boot-starter`: 자동 설정(autoconfiguration) 모듈

이 라이브러리의 가장 중요한 목적은
**로그 기반으로 클라이언트 DB를 서버와 동기화**하는 것입니다.

- 클라이언트는 로컬 DB를 기준으로 화면/기능을 동작
- 서버는 전체 데이터를 반복 전송하지 않고 변경 로그 중심으로 전달
- 결과적으로 서버 조회 부하를 줄이면서 동기화를 유지

## 1. 핵심 설명

이 라이브러리는 로그를 바로 DB에 쓰지 않고, 먼저 Redis 큐에 적재한 뒤 배치로 DB에 반영합니다.

- 쓰기 경로: 서비스 -> Redis 큐(`log:queue:`) -> 배치 -> DB(`log`)
- 조회 경로: 클라이언트 cursor와 Redis 섹션 캐시를 비교해 DB 조회 최소화
- ID 생성: Snowflake 기반(`server.id` 사용)

동기화 모델(핵심):

1. 클라이언트 DB를 스냅샷으로 1차 구성
2. 이후 `log_id` 커서 기반으로 증분 로그를 계속 반영
3. 장애/복구 시에도 "스냅샷 + 이후 로그 재생"으로 상태를 다시 맞춤

중요:

- `enqueue()` 호출 시점은 Redis 적재 성공 시점입니다. DB 즉시 반영이 아닙니다.
- `batch.enabled=true` + 스케줄링 활성화가 되어야 큐가 DB로 주기 반영됩니다.

## 2. 라이브러리 버전

현재 배포 기준:

- `group`: `com.library`
- `artifact`: `log-spring-boot-starter`
- `version`: `0.1.0`

일반 라이브러리처럼 starter만 추가하면,
`log-core`를 바로 사용 가능합니다.

## 3. 적용 프로세스

### 3.1 저장소 + 인증 설정

해당 라이브러리를 사용할 수 있는 권한을 체크합니다.

`build.gradle` 예시:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/jism-dev/log-event-library")
        credentials {
            username = findProperty('gpr.user') ?: System.getenv('GPR_USER') ?: System.getenv('GITHUB_ACTOR')
            password = findProperty('gpr.key') ?: System.getenv('GPR_KEY') ?: System.getenv('GITHUB_TOKEN')
        }
    }
    mavenCentral()
}
```

### 3.2 의존성 추가

```groovy
dependencies {
    implementation "com.library:log-spring-boot-starter:0.1.0"
}
```

### 3.3 인증값 설정

프로젝트 내부 로컬 파일:

- 프로젝트 루트에 `gradle.local.properties` 생성 (gitignore 처리 필수)

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT
```

서버 CI/CD 환경변수:

- `GPR_USER`, `GPR_KEY` 또는
- `GITHUB_ACTOR`, `GITHUB_TOKEN`

PAT 권한은 최소 `read:packages`가 필요합니다.
패키지가 private이면 패키지/레포 접근 권한도 함께 열려 있어야 합니다.

## 4. Spring 설정

자동 설정은 기본 활성화입니다.

- 기본값: `library.log.enabled=true`
- 비활성화: `library.log.enabled=false`
- Snowflake 서버 ID: `server.id` (기본 `1`, 허용 범위 `0~31`)
- 배치 실행 토글: `batch.enabled` (기본 `false`)

`application.yml` 예시:

```yaml
server:
  id: 1

batch:
  enabled: true

library:
  log:
    enabled: true
```

스케줄 기반 배치를 쓰려면 `@EnableScheduling`이 필요합니다.

```java
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
```

## 5. 코드 적용 방법

### 5.1 단일 섹션 로그 적재

```java
@Service
@RequiredArgsConstructor
public class TeamService {

    private final LogClient logClient;

    public void updateNotice(Long teamId, Long memberId, NoticePayload payload) {
        logClient.enqueue(
                "TEAM_NOTICE_UPDATED",
                payload,
                String.valueOf(teamId),
                String.valueOf(memberId)
        );
    }
}
```

- `type`: 로그 이벤트 타입 문자열
- `dto`: JSON으로 직렬화될 payload 객체
- `sectionId`: 로그 스트림 식별자(예: 팀 ID)
- `createdBy`: 로그 생성자(시스템 로그면 `null` 가능)

### 5.2 복수 섹션 동시 적재

```java
logClient.enqueueToSections(
        "TEAM_MEMBER_LEFT",
        payload,
        List.of("12", "19", "27"),
        String.valueOf(memberId)
);
```

- 섹션별로 개별 logId가 발급됩니다.
- `null` section은 자동 제외됩니다.

### 5.3 커서 기반 조회

단일 섹션:

```java
List<Log> logs = logClient.getLog("12", lastLogId);
```

다중 섹션:

```java
List<LogCursorDto> cursors = List.of(
        new LogCursorDto("12", 9219381293L),
        new LogCursorDto("19", 3929381295L)
);
List<Log> logs = logClient.getLog(cursors);
```

Redis 캐시의 마지막 ID와 클라이언트 커서가 같으면 DB 조회를 건너뜁니다.

즉, 클라이언트는 "내가 마지막으로 반영한 log_id"만 들고 오면 되고,
서버는 그 이후 변경분만 전달해 동기화 비용을 줄일 수 있습니다.

## 6. 데이터 모델 체크포인트

### 6.1 Redis 키

- 큐 키: `log:queue:`
- 섹션 캐시 키: `log:section:{sectionId}`

### 6.2 DB 테이블

네이티브 쿼리/배치 SQL 기준으로 아래 컬럼이 필요합니다.

- `log_id` (PK 권장)
- `section_id` (NOT NULL)
- `created_by`
- `type` (NOT NULL)
- `payload` (TEXT)
- `created_date`
- `last_modified_date`

권장 인덱스:

- `(section_id, log_id)` 복합 인덱스

추가 주의:

- 다중 커서 조회는 `JSON_TABLE`을 사용하므로 MySQL 8+ 호환이 필요합니다.

## 7. 운영 체크포인트

- 배치 스케줄 주기: 기본 1초(`@Scheduled(fixedRate = 1000)`)
- 큐 스냅샷 반영 방식: `RENAMENX` 기반 안전 rename 후 일괄 insert
- DB insert 실패 시: 스냅샷 키를 재시도 큐(`retryKeys`)에 넣고 다음 주기에 재처리
- 배치 insert SQL: `INSERT IGNORE` (중복 log_id는 무시)
- 동기화 운영 모델: 스냅샷 1차 복구 + 로그 리플레이 2차 복구

## 8. Docker/EC2 배포 시 체크포인트

의존성 다운로드는 일반적으로 **CI 빌드 단계**에서 발생합니다.

- CI가 JAR/이미지를 만든다면:
  - CI에만 패키지 인증값 있으면 됨
  - 런타임 서버에는 불필요
- 서버에서 직접 `gradle build`를 돌린다면:
  - 서버에도 같은 인증값 필요

런타임 환경 체크:

- Redis 연결 정상 여부
- `batch.enabled` 값
- `@EnableScheduling` 활성화 여부
- 멀티 인스턴스면 `server.id` 중복 금지

## 9. 로컬 개발 팁 (라이브러리 수정을 원할 경우)

프로젝트에서 라이브러리를 로컬 소스로 바로 붙이고 싶다면
`settings.gradle`에 조건부 `includeBuild`를 사용할 수 있습니다.

예시:

```groovy
def useLocalLog = gradle.startParameter.projectProperties.get('useLocalLog') != 'false'
def localLogBuild = file('../library/log')
if (useLocalLog && localLogBuild.exists()) {
    includeBuild(localLogBuild)
}
```

## 10. 빠른 트러블슈팅

### Q1. `Could not find com.library:log-spring-boot-starter:0.1.0`

- 라이브러리 `0.1.0` publish 여부 확인
- `--refresh-dependencies`로 캐시 갱신
- 저장소 URL/소유자(`jism-dev/log-event-library`) 확인

### Q2. `Username must not be null` 또는 401/403

- `gpr.user`, `gpr.key` 또는 환경변수 누락
- PAT 권한(`read:packages`) 확인
- private 패키지 접근 권한 확인

### Q3. `enqueue()`는 되는데 DB에 로그가 안 쌓임

- `batch.enabled=true`인지 확인
- `@EnableScheduling`이 적용되어 있는지 확인
- Redis 큐(`log:queue:`)에 데이터가 남아있는지 확인

### Q4. 다중 커서 조회 쿼리에서 SQL 에러 발생

- DB가 MySQL 8+인지 확인 (`JSON_TABLE` 지원 필요)
- 전달한 `cursorJson` 포맷(`sectionId`, `logId`) 확인

---

문서 기준 버전: `0.1.0`
