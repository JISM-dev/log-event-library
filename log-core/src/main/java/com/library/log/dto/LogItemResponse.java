package com.library.log.dto;

import com.library.log.entity.Log;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static com.library.log.utils.StringUtil.toStringOrNull;

/**
 * 외부 응답 전송용 로그 DTO.
 *
 * <p>엔티티를 그대로 노출하지 않고 응답 전용 포맷으로 변환할 때 사용한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogItemResponse {

    /**
     * 로그 ID 문자열.
     */
    private String id;
    /**
     * 섹션 ID.
     */
    private String sectionId;
    /**
     * 생성 주체 ID.
     */
    private String createdBy;
    /**
     * 로그 타입.
     */
    private String type;
    /**
     * 직렬화된 payload.
     */
    private String payload;
    /**
     * 생성 시각 문자열.
     */
    private String createdTime;

    /**
     * 엔티티를 응답 DTO로 매핑한다.
     *
     * @param log 변환할 로그 엔티티
     */
    public LogItemResponse(Log log) {
        if (log == null) {
            return;
        }

        this.id = toStringOrNull(log.getId());
        this.sectionId = log.getSectionId();
        this.createdBy = log.getCreatedBy();
        this.type = log.getType();
        this.payload = log.getPayload();
        this.createdTime = log.getCreatedDate() == null ? null : log.getCreatedDate().toString();
    }
}
