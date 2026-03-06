# log-library 동작 프로세스

이 문서는 `log-library`가 실제 런타임에서 어떻게 동작하는지,
각 프로세스의 역할이 무엇인지, 그리고 왜 그렇게 설계했는지를 설명합니다.

- 대상 버전: `0.1.0`
- 대상 모듈:
  - `log-core`
  - `log-spring-boot-starter`

## 1. 보고서 목적

이 라이브러리는 서비스 코드에서 로그 저장/조회 공통 로직을 분리하고,
Redis + DB 조합으로 로그 처리량과 조회 비용을 안정화하도록 설계되어 있습니다.

가장 중요한 목적은
**클라이언트 DB를 로그 기반으로 서버 상태와 동기화**하는 것입니다.

핵심 목표는 아래 3가지입니다.

1. 클라이언트 DB 동기화를 위해 섹션(section) 단위 로그 스트림을 커서 기반으로 표준화
2. 도메인 서비스 코드에서 로그 직렬화/큐잉/조회 최적화 로직 제거
3. Redis 캐시를 이용해 불필요한 DB 조회를 줄이고, 배치 반영으로 쓰기 부하를 완화

## 2. 아키텍처 구성

### 2.1 모듈 책임 분리

- `log-core`
  - `LogClient`, `LogService`
  - `LogBatchService`
  - `LogRepository`, `LogBulkRepository`
  - `SnowflakeLogIdGenerator`
  - `LogLuaScript`, `LogCacheConstants`
- `log-spring-boot-starter`
  - `LogJpaPackageAutoConfiguration`
  - `LogAutoConfiguration`
  - `AutoConfiguration.imports` 등록

## 3. 전체 동작 플로우

요청 하나가 로그 생성/조회로 들어오면 아래 순서로 처리됩니다.

1. Spring Boot가 starter의 자동 설정을 로딩
2. 서비스가 `LogClient.enqueue()`로 로그를 Redis 큐에 적재
3. Lua 스크립트로 섹션별 마지막 logId 캐시를 원자적으로 갱신
4. `LogBatchService`가 주기적으로 큐 스냅샷을 DB에 벌크 반영
5. 조회 시 `LogClient.getLog()`가 Redis 캐시와 커서를 비교
6. 최신이면 DB 조회 생략, 아니면 필요한 범위만 조회
7. 조회 결과의 최대 logId를 Redis 섹션 캐시에 재반영

클라이언트 동기화 관점에서는 아래처럼 해석됩니다.

1. 초기/대용량 복구: 스냅샷 데이터로 클라이언트 DB를 먼저 구성
2. 이후 증분 동기화: 마지막 `log_id` 이후 로그만 반영
3. 재복구 시나리오: 다시 스냅샷 적용 후 로그 리플레이로 최신 상태 복원

## 4. 프로세스별 상세 동작

### 4.1 자동 설정 초기화

`LogAutoConfiguration`은 아래 조건에서 활성화됩니다.

- `library.log.enabled=true` 또는 설정 누락(matchIfMissing=true)
- `DataSource`, `RedisConnectionFactory`, `RedisTemplate` 클래스가 classpath에 존재

생성되는 기본 빈:

- `LogIdGenerator` (`SnowflakeLogIdGenerator`)
- `LogBulkRepository`
- `LogService` (동시에 `LogClient`로 노출)
- `LogBatchService`

`LogJpaPackageAutoConfiguration`은 `com.library.log` 패키지를
`@AutoConfigurationPackage`로 등록하여, 라이브러리 내부 JPA 타입 인식을 보조합니다.

설계 이유:

- 소비 서비스에서 수동 `@Configuration`/`@EntityScan` 부담 축소
- `@ConditionalOnMissingBean` 기반으로 커스텀 빈으로 점진 교체 가능
- 프로퍼티 한 줄(`library.log.enabled=false`)로 전체 비활성화 가능

### 4.2 ID 생성 프로세스

`SnowflakeLogIdGenerator`는 아래 규칙으로 logId를 생성합니다.

- epoch: `2026-01-27T00:00:00Z`
- `serverId` 비트: 5 (범위 `0~31`)
- 시퀀스 비트: 12
- 같은 밀리초 내 시퀀스 증가, 오버플로우 시 다음 밀리초 대기

설계 이유:

- 시간 순 정렬 가능한 숫자 ID로 커서 처리 단순화
- 멀티 인스턴스 환경에서 `server.id`로 충돌 가능성 완화

### 4.3 로그 적재(`enqueue`, `enqueueToSections`)

`LogService.enqueue()` 동작:

1. `sectionId` null이면 즉시 종료
2. logId 생성
3. payload를 JSON 직렬화해 `LogItemDto` 생성
4. Lua(`LUA_ENQUEUE`) 실행
5. Redis 큐(`log:queue:`)에 RPUSH
6. `log:section:{sectionId}` 값을 max(logId)로 갱신

`enqueueToSections()`는 복수 섹션 버전입니다.

- null 섹션 제거
- 섹션마다 고유 logId 생성
- Lua(`LUA_ENQUEUE_MULTI`)로 큐 적재 + 섹션 캐시 갱신을 원자 처리

설계 이유:

- 큐 적재와 섹션 캐시 업데이트를 단일 스크립트로 묶어 경쟁 상태 최소화
- 도메인 서비스는 `type/payload/sectionId`만 전달하면 됨

### 4.4 배치 반영 프로세스

`LogBatchService.batch()`는 1초 주기로 실행됩니다.

- `batch.enabled=false`이면 아무 것도 하지 않음
- `batch.enabled=true`일 때만 `commitQueueMessage()` 실행

`commitQueueMessage()` 상세:

1. 이전 실패 키(`retryKeys`) 재처리 시도
2. 스냅샷 키 생성: `log:queue:snap:{nanoTime}`
3. Lua(`LUA_SAFE_RENAME`)로 큐를 스냅샷 키로 안전 rename
4. rename 성공 시 스냅샷 리스트를 읽어 벌크 insert
5. 성공하면 스냅샷 키 삭제
6. 실패하면 재시도 큐에 키 보관

`LogBulkRepository.bulkInsert()`는 `INSERT IGNORE`를 사용합니다.

설계 이유:

- 본 큐와 처리 중 큐를 분리해 소비 중 데이터 유실/중복 리스크 감소
- 실패 시 키 단위 재시도로 일시 장애 흡수
- 중복 ID 삽입은 무시해 재처리 내구성 확보

### 4.5 로그 조회 프로세스

단일 섹션 조회(`getLog(sectionId, cursorId)`):

1. `sectionId` null이면 빈 리스트 반환
2. Redis에서 섹션 마지막 ID 조회(`LUA_GET_SECTION_ID`)
3. cursor와 캐시 값이 같으면 최신으로 판단하고 DB 조회 생략
4. 필요 시 DB에서 `log_id > cursorId` 조건으로 조회
5. 조회 결과 최대 logId를 Redis 섹션 캐시에 반영(`LUA_SET_SECTION_ID`)

다중 섹션 조회(`getLog(List<LogCursorDto>)`):

1. null/잘못된 커서 정리
2. `multiGet`으로 섹션 캐시를 일괄 조회
3. 최신 커서는 제외하고 필요한 섹션만 남김
4. 남은 커서를 JSON으로 묶어 `JSON_TABLE` native query 실행
5. 조회 결과 섹션별 최대 logId를 계산
6. Lua(`LUA_SET_SECTION_IDS`)로 캐시를 일괄 갱신

설계 이유:

- 커서가 최신이면 DB 접근 자체를 줄일 수 있음
- 다중 섹션 요청을 단일 쿼리로 처리해 왕복 비용 절감

## 5. 클래스별 역할 정의

### 5.1 `LogClient`

- 외부 서비스가 사용하는 표준 API 인터페이스
- 적재(`enqueue`, `enqueueToSections`)와 조회(`getLog`) 노출

### 5.2 `LogService`

- `LogClient` 구현체
- Redis Lua 호출, 직렬화, 캐시/DB 조회 분기 처리

### 5.3 `LogBatchService`

- 큐 스냅샷 커밋 오케스트레이션
- 재시도 큐(`retryKeys`) 관리

### 5.4 `LogRepository`

- 커서 기반 native 조회
- 다중 섹션은 `JSON_TABLE` 기반 조회 지원

### 5.5 `LogBulkRepository`

- NamedParameterJdbcTemplate 기반 벌크 insert
- DB write 경로 최적화

### 5.6 `SnowflakeLogIdGenerator`

- 시간 기반 고유 logId 생성
- 서버 식별 비트를 포함한 충돌 완화

### 5.7 `LogLuaScript`

- Redis 원자 연산 스크립트 집합
- 큐 적재/키 rename/섹션 캐시 갱신 일관성 보장

### 5.8 `LogAutoConfiguration`, `LogJpaPackageAutoConfiguration`

- starter 진입점
- 기본 빈 자동 등록 + JPA 패키지 등록

## 6. 실패 모델 및 일관성 포인트

현재 구현의 핵심 포인트:

- `sectionId == null` 입력은 안전하게 no-op 처리
- 직렬화/역직렬화 실패 시 `IllegalStateException`으로 즉시 실패
- 배치 DB 반영 실패는 큐 삭제 대신 재시도 키 보관
- `INSERT IGNORE`로 재시도 중 중복 삽입을 흡수
- 캐시는 "기존 값보다 큰 logId만 갱신" 규칙으로 역행 업데이트 방지

## 7. 핵심 설계 의도

### 7.1 왜 Redis 큐 -> DB 배치인가

- 요청 경로에서 DB write 비용을 분리해 응답 경로를 가볍게 유지
- 순간 트래픽 피크를 큐로 완충

### 7.2 왜 Lua 스크립트를 적극 사용했는가

- 큐 적재와 섹션 캐시 갱신을 원자적으로 처리
- 다중 키 갱신을 네트워크 왕복 최소화로 처리

### 7.3 왜 스냅샷 rename 후 처리하는가

- 처리 중에도 새 로그는 원본 큐에 계속 쌓일 수 있음
- 배치 대상 고정(스냅샷)으로 경계가 명확해짐

### 7.4 왜 커서 + 섹션 캐시를 함께 쓰는가

- "이미 최신" 요청은 DB 접근 없이 종료 가능
- 읽기 트래픽이 큰 구간에서 DB 부하를 안정화

### 7.5 왜 `INSERT IGNORE`를 선택했는가

- 재시도 과정에서 같은 logId 재삽입 시 장애로 확대되지 않음
- 적어도 한 번(at-least-once) 처리 전략에 실용적

### 7.6 왜 "스냅샷 + 로그 재생" 모델인가

- 스냅샷만으로는 스냅샷 시점 이후 변경분을 복구할 수 없음
- 로그만으로는 초기 구축/대량 복구 시간이 길어질 수 있음
- 두 방식을 결합하면 초기 복구 속도와 최신성 동기화를 동시에 확보 가능
- 클라이언트 로컬 DB를 적극 활용해 서버의 반복 조회/전송 부하를 줄일 수 있음

## 8. 운영 관점 체크포인트

- `batch.enabled=false`면 Redis 큐만 누적됨
- `@EnableScheduling` 누락 시 배치 메서드가 실행되지 않음
- 멀티 인스턴스에서 `server.id` 충돌 시 ID 중복 위험 존재
- Redis 장애 시 enqueue/조회 캐시 로직 영향 발생
- 다중 커서 조회는 MySQL 8+ (`JSON_TABLE`) 전제
- 동기화 운영 기준은 "스냅샷 기준시점 + 마지막 반영 log_id"를 함께 관리하는 방식이 권장됨

## 9. 확장 포인트

확장 시나리오:

1. `LogIdGenerator` 커스텀 구현 교체 (UUID/외부 ID 등)
2. `LogBatchService` 커밋 전략 커스터마이징 (주기/사이즈 기반 flush)
3. 실패 로그를 별도 DLQ로 분리 저장
4. `LogRepository`를 DB 특성(PostgreSQL 등)에 맞춰 커스텀 구현

현재 구조는 `@ConditionalOnMissingBean` 기반이므로
커스텀 구현으로 점진 전환이 가능합니다.

---

문서 기준 버전: `0.1.0`
