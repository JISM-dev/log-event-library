package com.library.log.core;

/**
 * 로그 전용 고유 ID 생성기 추상화.
 *
 * <p>분산 환경/단일 환경에 따라 구현 전략(Snowflake, DB 시퀀스 등)이 달라질 수 있으므로
 * 서비스 계층은 이 인터페이스에만 의존한다.</p>
 */
public interface LogIdGenerator {

    /**
     * 단조 증가에 가까운(또는 충돌 없는) 로그 ID를 생성한다.
     *
     * @return 새 로그 식별자
     */
    long generateId();
}
