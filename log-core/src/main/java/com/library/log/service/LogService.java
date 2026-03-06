package com.library.log.service;

import com.library.log.core.LogIdGenerator;
import com.library.log.dto.LogCursorDto;
import com.library.log.dto.LogItemDto;
import com.library.log.entity.Log;
import com.library.log.core.LogClient;
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

import static com.library.log.support.LogCacheConstants.LOG_QUEUE_CACHE;
import static com.library.log.support.LogCacheConstants.SECTION_KEY_CACHE;

@RequiredArgsConstructor
public class LogService implements LogClient {

    private final LogIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final LogRepository logRepository;
    private final RedisTemplate<String, String> stringTemplate;

    /**
     * 로그 이벤트를 Redis 큐에 적재하고,
     * 해당 섹션의 마지막 로그 ID 캐시를 최신으로 갱신한다.
     * createdBy는 로그를 생성한 요청 회원 ID이며, 시스템 로그면 null일 수 있다.
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
     * 여러 섹션에 동일한 로그 이벤트를 적재한다.
     * null 섹션은 건너뛰며, 리스트가 비어 있으면 아무 것도 하지 않는다.
     * createdBy는 로그를 생성한 요청 회원 ID이며, 시스템 로그면 null일 수 있다.
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
     * 단일 섹션의 로그를 cursor 이후로 조회한다.
     * Redis 캐시가 cursor와 같으면 DB 조회를 생략한다.
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
     * 여러 섹션의 커서를 한 번에 받아 로그를 조회한다.
     * Redis 캐시와 같은 커서는 제외하고, 필요한 섹션만 DB 조회한다.
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
     * Redis 큐에서 로그 이벤트를 하나 꺼낸다.
     * 큐가 비어 있으면 null을 반환한다.
     */
    public LogItemDto dequeue() {
        String json = stringTemplate.opsForList().leftPop(LOG_QUEUE_CACHE);

        if (json == null) {
            return null;
        }

        return fromJson(json, LogItemDto.class);
    }

    /**
     * Redis에서 섹션의 마지막 로그 ID를 읽는다.
     * 값이 없으면 "0"이 기록되어 반환된다.
     */
    private String getCachedSectionLastId(String sectionId) {
        return stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_GET_SECTION_ID, String.class),
                List.of(sectionKey(sectionId))
        );
    }

    /**
     * cursor와 캐시된 마지막 ID가 동일한지 확인한다.
     * 둘 중 하나라도 null이면 최신이라고 판단하지 않는다.
     */
    private boolean isLatestLog(Long cursorId, String cachedId) {
        return cursorId != null
                && cachedId != null
                && String.valueOf(cursorId).equals(cachedId);
    }

    /**
     * 커서 리스트에서 null 항목이나 sectionId가 없는 항목을 제거한다.
     * 입력이 비어 있으면 빈 리스트를 반환한다.
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
     * Redis 캐시와 비교해, 실제 DB 조회가 필요한 커서만 남긴다.
     * 캐시의 마지막 ID와 cursor가 같으면 제외한다.
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
     * multiGet 결과에서 안전하게 값을 꺼낸다.
     * 인덱스가 범위를 벗어나면 null을 반환한다.
     */
    private String cachedIdAt(List<String> cachedIds, int index) {
        if (cachedIds == null || cachedIds.size() <= index) {
            return null;
        }
        return cachedIds.get(index);
    }

    /**
     * 조회된 로그들에서 최대 ID가 있으면 Redis 캐시에 기록한다.
     * 로그가 비어 있으면 아무 것도 하지 않는다.
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
     * 섹션의 마지막 로그 ID를 Redis 캐시에 기록한다.
     * 기존 값보다 큰 경우에만 갱신된다.
     */
    private void markSectionLastId(String sectionId, Long lastLogId) {
        stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_SET_SECTION_ID, Long.class),
                List.of(sectionKey(sectionId)),
                String.valueOf(lastLogId)
        );
    }

    /**
     * 로그 리스트에서 섹션별 최대 logId를 계산해 Map으로 만든다.
     */
    private Map<String, Long> findMaxLogBySection(List<Log> logList) {
        Map<String, Long> maxLogBySection = new HashMap<>();
        for (Log log : logList) {
            maxLogBySection.merge(log.getSectionId(), log.getId(), Math::max);
        }
        return maxLogBySection;
    }

    /**
     * 여러 섹션의 마지막 logId를 Lua 스크립트로 한 번에 갱신한다.
     * 기존 값보다 큰 경우에만 갱신된다.
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
     * 섹션 캐시 키를 만드는 공통 메서드.
     */
    private String sectionKey(String sectionId) {
        return SECTION_KEY_CACHE + sectionId;
    }

    /**
     * 객체를 JSON 문자열로 직렬화한다.
     * 실패 시 공통 예외를 던진다.
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize log payload.", e);
        }
    }

    /**
     * JSON 문자열을 객체로 역직렬화한다.
     * 실패 시 공통 예외를 던진다.
     */
    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize log payload.", e);
        }
    }

}
