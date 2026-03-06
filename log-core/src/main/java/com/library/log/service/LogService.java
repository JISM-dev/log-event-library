package com.library.log.service;

import com.library.log.core.LogIdGenerator;
import com.library.log.dto.LogCursorDto;
import com.library.log.dto.LogItemDto;
import com.library.log.entity.Log;
import com.library.log.core.LogClient;
import com.library.log.exception.LogProcessException;
import com.library.log.support.LogLuaScript;
import com.library.log.repository.LogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static com.library.log.exception.LogExceptionType.LOG_PAYLOAD_DESERIALIZE_FAILED;
import static com.library.log.exception.LogExceptionType.LOG_PAYLOAD_SERIALIZE_FAILED;
import static com.library.log.support.LogCacheConstants.LOG_QUEUE_CACHE;
import static com.library.log.support.LogCacheConstants.SECTION_KEY_CACHE;

/**
 * 로그 라이브러리의 기본 구현 서비스.
 *
 * <p>역할은 크게 두 가지다.</p>
 * <p>1) 이벤트를 Redis 큐에 적재하고 섹션 최신 cursor 캐시를 갱신한다.</p>
 * <p>2) 클라이언트 cursor와 서버 캐시를 비교해 필요한 로그만 DB에서 조회한다.</p>
 *
 * <p>실제 영속화는 {@link com.library.log.batch.LogBatchService}가
 * 큐를 비동기 배치로 비우면서 처리한다.</p>
 */
@RequiredArgsConstructor
public class LogService implements LogClient {

    /**
     * 로그 ID 발급기(Snowflake 등).
     */
    private final LogIdGenerator idGenerator;
    /**
     * 로그 payload/커서 직렬화를 담당하는 JSON 매퍼.
     */
    private final ObjectMapper objectMapper;
    /**
     * 로그 조회를 담당하는 JPA 리포지토리.
     */
    private final LogRepository logRepository;
    /**
     * Redis 큐/섹션 cursor 캐시 접근 템플릿.
     */
    private final RedisTemplate<String, String> stringTemplate;

    /**
     * 단일 섹션 로그 이벤트를 Redis 큐에 적재한다.
     *
     * <p>동작 순서:</p>
     * <p>1) 로그 ID 생성</p>
     * <p>2) {@link LogItemDto} 구성</p>
     * <p>3) Lua 스크립트로 queue push + section cursor 갱신을 원자적으로 수행</p>
     *
     * @param type 로그 타입(도메인 이벤트 분류값)
     * @param dto 직렬화할 payload 객체
     * @param sectionId 로그 귀속 섹션 ID(null이면 무시)
     * @param createdBy 로그 생성 주체 ID(시스템 로그는 null 가능)
     * @param <T> payload 타입
     */
    @Override
    public <T> void enqueue(
            String type,
            T dto,
            String sectionId,
            String createdBy
    ) {
        if (sectionId == null) {
            return;
        }

        long id = idGenerator.generateId();

        LogItemDto item = new LogItemDto(
                id,
                sectionId,
                type,
                toJson(dto),
                createdBy,
                LocalDateTime.now().toString()
        );

        stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_ENQUEUE, Long.class),
                List.of(LOG_QUEUE_CACHE, SECTION_KEY_CACHE + sectionId),
                toJson(item),
                String.valueOf(id)
        );
    }

    /**
     * 동일 로그 이벤트를 여러 섹션으로 fan-out 적재한다.
     *
     * <p>처리 규칙:</p>
     * <p>- null sectionId는 제거</p>
     * <p>- 유효 섹션이 없으면 즉시 반환</p>
     * <p>- Lua 다중 enqueue 스크립트로 큐 적재/section cursor 갱신을 일괄 수행</p>
     *
     * @param type 로그 타입(도메인 이벤트 분류값)
     * @param dto 직렬화할 payload 객체
     * @param sectionIds 대상 섹션 ID 목록
     * @param createdBy 로그 생성 주체 ID(시스템 로그는 null 가능)
     * @param <T> payload 타입
     */
    @Override
    public <T> void enqueueToSections(
            String type,
            T dto,
            List<String> sectionIds,
            String createdBy
    ) {
        if (sectionIds == null || sectionIds.isEmpty()) {
            return;
        }

        List<String> filteredList = new ArrayList<>(sectionIds.size());

        for (String sectionId : sectionIds) {
            if (sectionId == null) {
                continue;
            }
            filteredList.add(sectionId);
        }

        if (filteredList.isEmpty()) {
            return;
        }

        List<String> keys = new ArrayList<>(filteredList.size() + 1);
        keys.add(LOG_QUEUE_CACHE);

        List<Object> args = new ArrayList<>(filteredList.size() * 2);
        String payloadJson = toJson(dto);

        filteredList.forEach(sectionId -> {
            long id = idGenerator.generateId();
            LogItemDto item = new LogItemDto(
                    id,
                    sectionId,
                    type,
                    payloadJson,
                    createdBy,
                    LocalDateTime.now().toString()
            );
            args.add(toJson(item));
            args.add(String.valueOf(id));
            keys.add(sectionKey(sectionId));
        });

        stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_ENQUEUE_MULTI, Long.class),
                keys,
                args.toArray()
        );
    }

    /**
     * 단일 섹션의 cursor 이후 로그를 조회한다.
     *
     * <p>Redis에 저장된 섹션 최신 cursor와 클라이언트 cursor가 같으면
     * 신규 데이터가 없다고 판단해 DB 조회를 생략한다.</p>
     *
     * @param sectionId 조회 대상 섹션
     * @param clientLogId 클라이언트가 보유한 마지막 로그 ID(cursor)
     * @return cursor 이후 로그 목록(없으면 빈 리스트)
     */
    @Transactional(readOnly = true)
    @Override
    public List<Log> getLog(
            String sectionId,
            Long clientLogId
    ) {
        if (sectionId == null) {
            return new ArrayList<>();
        }

        String serverLastLogId = getCachedSectionLastId(sectionId);

        boolean isLatestLog = isLatestLog(clientLogId, serverLastLogId);
        if (isLatestLog) {
            return new ArrayList<>();
        }

        List<Log> logList = logRepository.findBySectionId(sectionId, clientLogId);
        markSectionLastIdIfPresent(sectionId, logList);

        return logList;
    }

    /**
     * 다중 섹션 cursor를 한 번에 받아 필요한 로그만 조회한다.
     *
     * <p>동작 순서:</p>
     * <p>1) null/invalid cursor 정리</p>
     * <p>2) Redis 캐시 기준으로 최신인 섹션 제외</p>
     * <p>3) 남은 cursor를 JSON으로 전달해 단일 SQL로 조회</p>
     * <p>4) 조회 결과의 섹션별 최대 ID를 Redis 캐시에 반영</p>
     *
     * @param cursors 섹션별 cursor 목록
     * @return 모든 섹션의 신규 로그 목록(없으면 빈 리스트)
     */
    @Transactional(readOnly = true)
    @Override
    public List<Log> getLog(
            List<LogCursorDto> cursors
    ) {
        List<LogCursorDto> cursorList = sanitizeCursors(cursors);
        if (cursorList.isEmpty()) {
            return new ArrayList<>();
        }

        List<LogCursorDto> filtered = filterCursorsByCache(cursorList);
        if (filtered.isEmpty()) {
            return new ArrayList<>();
        }

        String cursorJson = toJson(filtered);
        List<Log> logList = logRepository.findBySectionIdAndLogIdJson(cursorJson);

        markSectionLastIds(findMaxLogBySection(logList));

        return logList;
    }

    /**
     * Redis 큐에서 로그 이벤트를 1건 꺼낸다.
     *
     * @return 큐에서 꺼낸 로그 아이템, 비어 있으면 null
     */
    public LogItemDto dequeue() {
        String json = stringTemplate.opsForList().leftPop(LOG_QUEUE_CACHE);

        if (json == null) {
            return null;
        }

        return fromJson(json, LogItemDto.class);
    }

    /**
     * 섹션의 서버 cursor(마지막 로그 ID)를 Redis에서 조회한다.
     *
     * <p>키가 없으면 Lua 스크립트에서 0으로 초기화하고 "0"을 반환한다.</p>
     *
     * @param sectionId 조회 대상 섹션 ID
     * @return 섹션 마지막 로그 ID 문자열
     */
    private String getCachedSectionLastId(String sectionId) {
        return stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_GET_SECTION_ID, String.class),
                List.of(sectionKey(sectionId))
        );
    }

    /**
     * 클라이언트 cursor가 서버 cursor와 동일한지 판별한다.
     *
     * @param cursorId 클라이언트 cursor
     * @param cachedId Redis에 저장된 서버 cursor
     * @return 둘 다 null이 아니고 값이 동일하면 true
     */
    private boolean isLatestLog(Long cursorId, String cachedId) {
        return cursorId != null
                && cachedId != null
                && String.valueOf(cursorId).equals(cachedId);
    }

    /**
     * 입력 cursor 목록에서 조회 가능한 항목만 남긴다.
     *
     * <p>제거 대상:</p>
     * <p>- null cursor 객체</p>
     * <p>- sectionId가 null인 cursor</p>
     *
     * @param cursors 원본 cursor 목록
     * @return 정제된 cursor 목록
     */
    private List<LogCursorDto> sanitizeCursors(List<LogCursorDto> cursors) {
        if (cursors == null || cursors.isEmpty()) {
            return new ArrayList<>();
        }

        return cursors.stream()
                .filter(cursor -> cursor != null && cursor.getSectionId() != null)
                .toList();
    }

    /**
     * Redis cursor 캐시를 기준으로 DB 조회 대상 cursor만 필터링한다.
     *
     * @param sanitized sanitize 완료된 cursor 목록
     * @return 실제 조회가 필요한 cursor 목록
     */
    private List<LogCursorDto> filterCursorsByCache(List<LogCursorDto> sanitized) {
        List<String> keys = sanitized.stream()
                .map(cursor -> sectionKey(cursor.getSectionId()))
                .toList();

        List<String> cachedIds = stringTemplate.opsForValue().multiGet(keys);

        List<LogCursorDto> filtered = new ArrayList<>(sanitized.size());

        for (int i = 0; i < sanitized.size(); i++) {
            LogCursorDto cursor = sanitized.get(i);
            String cachedId = cachedIdAt(cachedIds, i);

            if (!isLatestLog(cursor.getLogId(), cachedId)) {
                filtered.add(cursor);
            }
        }

        return filtered;
    }

    /**
     * multiGet 결과에서 인덱스 범위를 안전하게 검사해 cursor 값을 반환한다.
     *
     * @param cachedIds multiGet 결과 목록
     * @param index 조회할 인덱스
     * @return 해당 인덱스 값, 없으면 null
     */
    private String cachedIdAt(List<String> cachedIds, int index) {
        if (cachedIds == null || cachedIds.size() <= index) {
            return null;
        }
        return cachedIds.get(index);
    }

    /**
     * 조회 결과에서 최대 logId를 계산해 섹션 cursor 캐시에 반영한다.
     *
     * @param sectionId cursor를 갱신할 섹션 ID
     * @param logList 조회된 로그 목록
     */
    private void markSectionLastIdIfPresent(String sectionId, List<Log> logList) {
        OptionalLong maxLogIdOpt = logList.stream()
                .mapToLong(Log::getId)
                .max();

        if (maxLogIdOpt.isEmpty()) {
            return;
        }

        markSectionLastId(sectionId, maxLogIdOpt.getAsLong());
    }

    /**
     * 단일 섹션의 마지막 logId를 Redis에 기록한다.
     *
     * <p>Lua 스크립트 내부에서 기존 값보다 큰 경우에만 갱신한다.</p>
     *
     * @param sectionId 갱신 대상 섹션 ID
     * @param lastLogId 새 마지막 로그 ID
     */
    private void markSectionLastId(String sectionId, Long lastLogId) {
        stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_SET_SECTION_ID, Long.class),
                List.of(sectionKey(sectionId)),
                String.valueOf(lastLogId)
        );
    }

    /**
     * 로그 목록에서 섹션별 최대 logId를 계산한다.
     *
     * @param logList 조회된 로그 목록
     * @return "sectionId -> maxLogId" 매핑
     */
    private Map<String, Long> findMaxLogBySection(List<Log> logList) {
        Map<String, Long> maxLogBySection = new HashMap<>();
        for (Log log : logList) {
            maxLogBySection.merge(log.getSectionId(), log.getId(), Math::max);
        }
        return maxLogBySection;
    }

    /**
     * 다중 섹션의 마지막 logId를 Redis에 일괄 갱신한다.
     *
     * <p>Lua 스크립트 내부에서 각 키별로 "기존 값보다 큰 경우만 갱신" 규칙을 적용한다.</p>
     *
     * @param maxLogBySection "sectionId -> maxLogId" 매핑
     */
    private void markSectionLastIds(Map<String, Long> maxLogBySection) {
        if (maxLogBySection.isEmpty()) {
            return;
        }

        List<String> updateKeys = new ArrayList<>(maxLogBySection.size());
        List<String> updateArgs = new ArrayList<>(maxLogBySection.size());

        maxLogBySection.forEach((key, value) -> {
            updateKeys.add(sectionKey(key));
            updateArgs.add(String.valueOf(value));
        });

        stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_SET_SECTION_IDS, Long.class),
                updateKeys,
                updateArgs.toArray()
        );
    }

    /**
     * 섹션 cursor 캐시의 Redis 키를 생성한다.
     *
     * @param sectionId 섹션 ID
     * @return "log:section:{sectionId}" 형태 키
     */
    private String sectionKey(String sectionId) {
        return SECTION_KEY_CACHE + sectionId;
    }

    /**
     * 객체를 JSON 문자열로 직렬화한다.
     *
     * @param value 직렬화 대상 객체
     * @return JSON 문자열
     * @throws LogProcessException 직렬화 실패 시
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new LogProcessException(LOG_PAYLOAD_SERIALIZE_FAILED);
        }
    }

    /**
     * JSON 문자열을 지정 타입으로 역직렬화한다.
     *
     * @param json JSON 문자열
     * @param type 대상 클래스
     * @param <T> 반환 타입
     * @return 역직렬화된 객체
     * @throws LogProcessException 역직렬화 실패 시
     */
    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new LogProcessException(LOG_PAYLOAD_DESERIALIZE_FAILED);
        }
    }
}
