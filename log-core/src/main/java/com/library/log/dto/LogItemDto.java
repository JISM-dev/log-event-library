package com.library.log.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Redis 큐에 저장되는 로그 아이템 DTO.
 *
 * <p>배치 처리 전까지 큐에 머무르는 중간 표현이며,
 * DB 적재 시 {@code log} 테이블 컬럼으로 매핑된다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogItemDto {

    /**
     * 로그 고유 ID.
     */
    private Long id;
    /**
     * 로그 대상 섹션 ID.
     */
    private String sectionId;
    /**
     * 로그 타입.
     */
    private String type;
    /**
     * 직렬화된 payload(JSON 문자열).
     */
    private String payload;
    /**
     * 로그 생성 주체 ID.
     */
    private String createdBy;
    /**
     * 로그 생성 시각(문자열).
     */
    private String createdTime;
}
