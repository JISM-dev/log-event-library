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

    @Id
    @Column(name = "logId")
    private Long id;

    @Column(name = "sectionId", nullable = false)
    private String sectionId;

    private String createdBy;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String payload;

    public Log(String sectionId, String type, String payload, String createdBy) {
        this.sectionId = sectionId;
        this.createdBy = createdBy;
        this.type = type;
        this.payload = payload;
    }
}
