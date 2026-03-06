package com.library.log.core;

public class SnowflakeLogIdGenerator implements LogIdGenerator {

    // 2026-01-27T00:00:00Z
    private static final long EPOCH = 1769472000000L;
    private static final long SERVER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_SERVER_ID = ~(-1L << SERVER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private final long serverId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeLogIdGenerator(long serverId) {
        if (serverId < 0 || serverId > MAX_SERVER_ID) {
            throw new IllegalArgumentException("serverId must be between 0 and " + MAX_SERVER_ID);
        }
        this.serverId = serverId;
    }

    @Override
    public synchronized long generateId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("System clock moved backwards.");
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

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long waitForNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
