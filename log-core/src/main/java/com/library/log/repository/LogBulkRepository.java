package com.library.log.repository;

import com.library.log.dto.LogItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

/**
 * 로그 배치 INSERT를 담당하는 JDBC 저장소.
 *
 * <p>JPA 단건 persist 대신 {@link NamedParameterJdbcTemplate#batchUpdate(String, SqlParameterSource[])}
 * 를 사용해 대량 적재 비용을 낮춘다.</p>
 */
@RequiredArgsConstructor
public class LogBulkRepository {

    /**
     * 로그 배치 저장 SQL.
     *
     * <p>동일 PK(log_id)가 존재할 때 중복 삽입을 무시하기 위해 INSERT IGNORE를 사용한다.</p>
     */
    private static final String INSERT_LOG_ITEM_SQL = """
        INSERT IGNORE INTO log
          (log_id, section_id, created_by, type, payload, created_date, last_modified_date)
        VALUES
          (:id, :sectionId, :createdBy, :type, :payload, :createdDate, :lastModifiedDate)
    """;

    /**
     * Named parameter 기반 JDBC 템플릿.
     */
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 로그 DTO 목록을 DB에 배치 삽입한다.
     *
     * @param items 저장할 로그 아이템 목록
     */
    public void bulkInsert(List<LogItemDto> items) {
        if (items == null || items.isEmpty()) return;

        SqlParameterSource[] batch = items.stream()
                .map(i -> new MapSqlParameterSource()
                        .addValue("id",               i.getId())
                        .addValue("sectionId",        i.getSectionId())
                        .addValue("createdBy",        i.getCreatedBy())
                        .addValue("type",             i.getType())
                        .addValue("payload",          i.getPayload())
                        .addValue("createdDate",      i.getCreatedTime())
                        .addValue("lastModifiedDate", i.getCreatedTime())
                ).toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(INSERT_LOG_ITEM_SQL, batch);
    }
}
