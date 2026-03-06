package com.library.log.support;

/**
 * 로그 라이브러리 Redis 키 prefix 상수 모음.
 */
public final class LogCacheConstants {

    /**
     * Redis 로그 큐 리스트 키 prefix.
     */
    public static final String LOG_QUEUE_CACHE = "log:queue:";
    /**
     * 섹션 최신 cursor 키 prefix.
     */
    public static final String SECTION_KEY_CACHE = "log:section:";

    /**
     * 유틸 클래스 생성 방지.
     */
    private LogCacheConstants() {
    }
}
