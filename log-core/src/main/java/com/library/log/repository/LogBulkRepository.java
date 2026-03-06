package com.library.log.repository;

import com.library.log.dto.LogItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

@RequiredArgsConstructor
public class LogBulkRepository {

    private static final String INSERT_LOG_ITEM_SQL = """
        INSERT IGNORE INTO log
          (log_id, section_id, created_by, type, payload, created_date, last_modified_date)
        VALUES
          (:id, :sectionId, :createdBy, :type, :payload, :createdDate, :lastModifiedDate)
    """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

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
