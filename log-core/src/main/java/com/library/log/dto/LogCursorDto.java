package com.library.log.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 섹션별 로그 조회 cursor DTO.
 *
 * <p>클라이언트가 "해당 섹션에서 마지막으로 수신한 logId"를 전달할 때 사용한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogCursorDto {

    /**
     * 조회 대상 섹션 ID.
     */
    private String sectionId;
    /**
     * 클라이언트가 마지막으로 보유한 로그 ID(cursor).
     */
    private Long logId;
}
