package com.library.log.core;

import com.library.log.dto.LogCursorDto;
import com.library.log.entity.Log;

import java.util.List;

/**
 * 로그 라이브러리의 외부 진입점 인터페이스.
 *
 * <p>애플리케이션 서비스 계층에서는 구현체에 직접 의존하지 않고
 * 이 인터페이스만 주입받아 로그 적재/조회 기능을 사용할 수 있다.</p>
 */
public interface LogClient {

    /**
     * 단일 섹션에 로그 이벤트를 큐잉한다.
     *
     * <p>구현체는 일반적으로 "Redis 큐 적재 + 섹션 마지막 로그 ID 캐시 갱신"을
     * 하나의 원자적 흐름으로 처리한다.</p>
     *
     * @param type 로그 타입(도메인 이벤트 분류값)
     * @param dto 직렬화할 로그 페이로드 객체
     * @param sectionId 로그를 귀속시킬 섹션 식별자
     * @param createdBy 로그를 발생시킨 사용자 ID(시스템 로그는 null 가능)
     * @param <T> 페이로드 객체 타입
     */
    <T> void enqueue(
            String type,
            T dto,
            String sectionId,
            String createdBy
    );

    /**
     * 동일 로그 이벤트를 여러 섹션에 일괄 큐잉한다.
     *
     * <p>팬아웃(fan-out) 시나리오를 위한 API로, 구현체는 성능을 위해
     * 다중 키 Lua 스크립트 등 배치 전략을 사용할 수 있다.</p>
     *
     * @param type 로그 타입(도메인 이벤트 분류값)
     * @param dto 직렬화할 로그 페이로드 객체
     * @param sectionIds 이벤트를 전파할 섹션 식별자 목록
     * @param createdBy 로그를 발생시킨 사용자 ID(시스템 로그는 null 가능)
     * @param <T> 페이로드 객체 타입
     */
    <T> void enqueueToSections(
            String type,
            T dto,
            List<String> sectionIds,
            String createdBy
    );

    /**
     * 단일 섹션 기준으로 cursor 이후 로그를 조회한다.
     *
     * @param sectionId 조회 대상 섹션 ID
     * @param clientLogId 클라이언트가 마지막으로 확인한 로그 ID(cursor)
     * @return cursor 이후 로그 목록(없으면 빈 리스트)
     */
    List<Log> getLog(
            String sectionId,
            Long clientLogId
    );

    /**
     * 여러 섹션의 cursor를 한 번에 전달받아 로그를 조회한다.
     *
     * @param cursors 섹션별 마지막 수신 로그 ID 목록
     * @return 각 섹션의 cursor 이후 로그 목록(없으면 빈 리스트)
     */
    List<Log> getLog(
            List<LogCursorDto> cursors
    );
}
