package com.library.log.repository;

import com.library.log.entity.Log;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogRepository extends JpaRepository<Log, Long> {

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
