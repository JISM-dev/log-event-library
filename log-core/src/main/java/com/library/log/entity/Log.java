package com.library.log.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그 영속 엔티티.
 *
 * <p>하나의 로그 레코드는 "섹션(sectionId) + 로그 ID(logId)" 축으로 조회되며,
 * payload는 JSON 문자열로 저장된다.</p>
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        indexes = {
                @Index(name = "idx_section_id_log_id", columnList = "sectionId, logId")
        }
)
public class Log extends LogBaseEntity {

    /**
     * 로그 고유 ID(Snowflake 등).
     */
    @Id
    @Column(name = "logId")
    private Long id;

    /**
     * 로그 스트림 구분용 섹션 ID.
     */
    @Column(name = "sectionId", nullable = false)
    private String sectionId;

    /**
     * 로그 생성 주체 ID(시스템 로그는 null 가능).
     */
    private String createdBy;

    /**
     * 로그 타입(도메인 이벤트 이름 등).
     */
    @Column(nullable = false)
    private String type;

    /**
     * 직렬화된 이벤트 본문(JSON 문자열).
     */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * 로그 엔티티를 생성한다.
     *
     * @param sectionId 로그가 귀속되는 섹션 ID
     * @param type 로그 타입
     * @param payload 직렬화된 이벤트 본문
     * @param createdBy 생성 주체 ID
     */
    public Log(String sectionId, String type, String payload, String createdBy) {
        this.sectionId = sectionId;
        this.createdBy = createdBy;
        this.type = type;
        this.payload = payload;
    }
}
