package com.library.log.repository;

import com.library.log.entity.Log;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 로그 조회 전용 JPA 리포지토리.
 *
 * <p>조회 성능/유연성을 위해 주요 메서드는 native SQL 기반으로 제공한다.</p>
 */
@Repository
public interface LogRepository extends JpaRepository<Log, Long> {

    /**
     * 단일 섹션에서 cursor 이후 로그를 오름차순으로 조회한다.
     *
     * @param sectionId 조회 대상 섹션 ID
     * @param cursorId 클라이언트 마지막 수신 ID(null이면 전체)
     * @return 조건에 맞는 로그 목록
     */
    @Query(
            value = """
            SELECT *
            FROM log
            WHERE section_id = :sectionId
              AND (:cursorId IS NULL OR log_id > :cursorId)
            ORDER BY log_id ASC
            """,
            nativeQuery = true
    )
    List<Log> findBySectionId(
            @Param("sectionId") String sectionId,
            @Param("cursorId") Long cursorId
    );

    /**
     * 여러 섹션의 마지막 로그 1건씩을 조회한다.
     *
     * <p>각 section_id별 MAX(log_id)를 계산한 뒤 원본 테이블과 조인한다.</p>
     *
     * @param sectionIds 조회할 섹션 ID 목록
     * @return 섹션별 최신 로그 목록
     */
    @Query(
            value = """
            SELECT l.*
            FROM log l
            JOIN (
                SELECT section_id, MAX(log_id) AS max_log_id
                FROM log
                WHERE section_id IN (:sectionIds)
                GROUP BY section_id
            ) latest
              ON l.section_id = latest.section_id
             AND l.log_id = latest.max_log_id
            """,
            nativeQuery = true
    )
    List<Log> findLastBySectionIds(@Param("sectionIds") List<String> sectionIds);

    /**
     * 다중 cursor JSON을 전달받아 각 섹션의 cursor 이후 로그를 조회한다.
     *
     * <p>MySQL JSON_TABLE을 사용해 (sectionId, logId) 임시 테이블을 만들고
     * 원본 로그와 조인해 단일 쿼리로 결과를 반환한다.</p>
     *
     * @param cursorJson cursor DTO 배열을 직렬화한 JSON 문자열
     * @return 모든 섹션의 신규 로그 목록
     */
    @Query(
            value = """
            SELECT l.*
            FROM log l
            JOIN JSON_TABLE(
                CAST(:cursorJson AS JSON),
                '$[*]' COLUMNS (
                    section_id VARCHAR(64) PATH '$.sectionId',
                    log_id BIGINT PATH '$.logId' NULL ON EMPTY NULL ON ERROR
                )
            ) jt
              ON l.section_id = (jt.section_id COLLATE utf8mb4_unicode_ci)
            WHERE (jt.log_id IS NULL OR l.log_id > jt.log_id)
            ORDER BY l.log_id ASC
            """,
            nativeQuery = true
    )
    List<Log> findBySectionIdAndLogIdJson(@Param("cursorJson") String cursorJson);
}
