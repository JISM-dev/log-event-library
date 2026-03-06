package com.library.log.batch;

import com.library.log.dto.LogItemDto;
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

import static com.library.log.support.LogCacheConstants.LOG_QUEUE_CACHE;

@RequiredArgsConstructor
@Slf4j
public class LogBatchService {

    private final RedisTemplate<String, String> stringTemplate;
    private final LogBulkRepository bulkRepository;
    private final ObjectMapper objectMapper;

    @Getter
    private final Deque<String> retryKeys = new ConcurrentLinkedDeque<>();

    @Value("${batch.enabled:false}")
    private boolean enabled;

    @Scheduled(fixedRate = 1000)
    @Transactional
    public void batch() {
        if (enabled) {
            commitQueueMessage();
        }
    }

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

    private void drainRetryCommitKeys() {
        Set<String> visited = new HashSet<>();
        String key;

        while ((key = retryKeys.pollFirst()) != null) {
            if (!visited.add(key)) break;
            handleCommit(key, true);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize log item.", e);
        }
    }
}
