package com.library.log.batch;

import com.library.log.dto.LogItemDto;
import com.library.log.exception.LogProcessException;
import com.library.log.repository.LogBulkRepository;
import com.library.log.support.LogLuaScript;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.library.log.exception.LogExceptionType.LOG_PAYLOAD_DESERIALIZE_FAILED;
import static com.library.log.support.LogCacheConstants.LOG_QUEUE_CACHE;

/**
 * Redis 로그 큐를 DB로 플러시하는 배치 서비스.
 *
 * <p>운영 흐름:</p>
 * <p>1) 큐 키를 snapshot 키로 안전하게 분리(RENAMENX)</p>
 * <p>2) snapshot 리스트를 역직렬화해 DB bulk insert</p>
 * <p>3) 실패 시 snapshot 키를 재시도 큐에 저장</p>
 *
 * <p>원본 큐를 직접 순회하지 않고 snapshot으로 분리해 처리하므로,
 * 배치 처리 중에도 신규 로그 enqueue가 계속 가능하다.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class LogBatchService {

    /**
     * Redis 큐/snapshot 접근 템플릿.
     */
    private final RedisTemplate<String, String> stringTemplate;
    /**
     * 로그 벌크 INSERT 전용 저장소.
     */
    private final LogBulkRepository bulkRepository;
    /**
     * Redis JSON payload 역직렬화 매퍼.
     */
    private final ObjectMapper objectMapper;

    /**
     * 처리 실패한 snapshot 키 재시도 큐.
     *
     * <p>앞쪽으로 넣으면 즉시 재시도, 뒤쪽으로 넣으면 후순위 재시도를 의미한다.</p>
     */
    @Getter
    private final Deque<String> retryKeys = new ConcurrentLinkedDeque<>();

    /**
     * 배치 활성화 플래그.
     *
     * <p>{@code batch.enabled=true}일 때만 스케줄 실행 로직이 동작한다.</p>
     */
    @Value("${batch.enabled:false}")
    private boolean enabled;

    /**
     * 1초 간격으로 큐 커밋을 트리거한다.
     *
     * <p>비활성화 상태면 즉시 반환한다.</p>
     */
    @Scheduled(fixedRate = 1000)
    @Transactional
    public void batch() {
        if (enabled) {
            commitQueueMessage();
        }
    }

    /**
     * 로그 큐 커밋 사이클 1회를 수행한다.
     *
     * <p>처리 순서:</p>
     * <p>1) 이전 실패 snapshot 재시도</p>
     * <p>2) 현재 queue 키를 snapshot 키로 안전 분리</p>
     * <p>3) 분리 성공 시 snapshot을 DB에 반영</p>
     */
    public void commitQueueMessage() {
        drainRetryCommitKeys();

        String snapKey = LOG_QUEUE_CACHE + "snap:" + System.nanoTime();

        Long moved = stringTemplate.execute(
                new DefaultRedisScript<>(LogLuaScript.LUA_SAFE_RENAME, Long.class),
                List.of(LOG_QUEUE_CACHE, snapKey)
        );

        if (Long.valueOf(1).equals(moved)) {
            handleCommit(snapKey, false);
        }
    }

    /**
     * snapshot 키에 담긴 로그들을 DB에 저장하고 키를 정리한다.
     *
     * @param key 처리 대상 snapshot Redis 키
     * @param pushFront 실패 시 재시도 큐 앞쪽에 넣을지 여부
     */
    private void handleCommit(String key, boolean pushFront) {
        try {
            List<String> jsonList = stringTemplate.opsForList().range(key, 0, -1);

            if (jsonList == null || jsonList.isEmpty()) {
                stringTemplate.delete(key);
                return;
            }

            List<LogItemDto> items = jsonList.stream()
                    .map(json -> fromJson(json, LogItemDto.class))
                    .toList();

            bulkRepository.bulkInsert(items);
            stringTemplate.delete(key);
        } catch (Exception e) {
            if (pushFront) retryKeys.addFirst(key);
            else           retryKeys.addLast(key);
            log.warn("log batch commit failed. key={}", key, e);
        }
    }

    /**
     * 실패 이력이 있는 snapshot 키를 순회하며 재시도한다.
     *
     * <p>동일 키가 순환 참조처럼 반복될 때 무한 루프를 막기 위해
     * visited 집합으로 1회 방문만 허용한다.</p>
     */
    private void drainRetryCommitKeys() {
        Set<String> visited = new HashSet<>();
        String key;

        while ((key = retryKeys.pollFirst()) != null) {
            if (!visited.add(key)) break;
            handleCommit(key, true);
        }
    }

    /**
     * JSON 문자열을 DTO로 역직렬화한다.
     *
     * @param json Redis에 저장된 JSON 문자열
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
