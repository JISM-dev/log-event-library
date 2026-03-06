package com.library.log.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 로그 엔티티 공통 감사(auditing) 필드 베이스 클래스.
 *
 * <p>Spring Data JPA Auditing을 통해 생성/수정 시각을 자동으로 채운다.</p>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class LogBaseEntity {

    /**
     * 엔티티 생성 시각.
     */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdDate;

    /**
     * 엔티티 마지막 수정 시각.
     */
    @LastModifiedDate
    private LocalDateTime lastModifiedDate;
}
