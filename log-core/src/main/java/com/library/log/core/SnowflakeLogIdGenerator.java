package com.library.log.core;

import com.library.log.exception.LogProcessException;

import static com.library.log.exception.LogExceptionType.LOG_ID_CLOCK_BACKWARD;

/**
 * Snowflake 규칙 기반 로그 ID 생성기.
 *
 * <p>ID 구성은 "timestamp delta + serverId + sequence"이며,
 * 동일 밀리초 내에서는 sequence를 증가시켜 충돌을 방지한다.</p>
 *
 * <p>현재 구현은 단일 JVM 인스턴스에서 스레드 안전하도록
 * {@link #generateId()}를 synchronized로 제공한다.</p>
 */
public class SnowflakeLogIdGenerator implements LogIdGenerator {

    /**
     * Snowflake 기준 시각(Epoch, UTC millis).
     *
     * <p>값: 2026-01-27T00:00:00Z</p>
     */
    private static final long EPOCH = 1769472000000L;
    /**
     * 서버 식별자 비트 수.
     */
    private static final long SERVER_ID_BITS = 5L;
    /**
     * 같은 millisecond 내 시퀀스 비트 수.
     */
    private static final long SEQUENCE_BITS = 12L;
    /**
     * 허용 가능한 최대 서버 ID.
     */
    private static final long MAX_SERVER_ID = ~(-1L << SERVER_ID_BITS);
    /**
     * 같은 millisecond 내 허용 가능한 최대 시퀀스.
     */
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    /**
     * 현재 인스턴스 서버 ID.
     */
    private final long serverId;
    /**
     * 같은 millisecond에서 증가시키는 순번.
     */
    private long sequence = 0L;
    /**
     * 마지막으로 ID를 발급한 시각(ms).
     */
    private long lastTimestamp = -1L;

    /**
     * 생성기 인스턴스를 초기화한다.
     *
     * @param serverId 노드 식별자(0 ~ {@link #MAX_SERVER_ID})
     * @throws IllegalArgumentException serverId 범위를 벗어날 때
     */
    public SnowflakeLogIdGenerator(long serverId) {
        if (serverId < 0 || serverId > MAX_SERVER_ID) {
            throw new IllegalArgumentException("serverId must be between 0 and " + MAX_SERVER_ID);
        }
        this.serverId = serverId;
    }

    /**
     * Snowflake 포맷의 고유 ID를 생성한다.
     *
     * <p>처리 규칙:</p>
     * <p>1) 시간이 역행하면 예외를 발생시켜 중복 가능성을 차단</p>
     * <p>2) 같은 ms면 sequence를 증가</p>
     * <p>3) sequence overflow면 다음 ms까지 대기</p>
     * <p>4) timestamp/serverId/sequence를 비트 결합해 반환</p>
     *
     * @return 생성된 고유 ID
     * @throws LogProcessException 시스템 시계가 역행한 경우
     */
    @Override
    public synchronized long generateId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new LogProcessException(LOG_ID_CLOCK_BACKWARD);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << (SERVER_ID_BITS + SEQUENCE_BITS))
                | (serverId << SEQUENCE_BITS)
                | sequence;
    }

    /**
     * 현재 시스템 시각(ms)을 반환한다.
     *
     * @return 현재 epoch millis
     */
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * lastTimestamp보다 큰 다음 millisecond까지 스핀 대기한다.
     *
     * @param lastTimestamp 직전 발급 시각
     * @return lastTimestamp보다 큰 현재 시각(ms)
     */
    private long waitForNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
