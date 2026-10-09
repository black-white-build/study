package com.heartpilot.common.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 所有 JPA 实体的公共基类。 集中维护主键、创建时间、更新时间三个跨表通用字段， 子类只需关注业务字段，避免重复声明。 标注 @MappedSuperclass
 * 表示本身不映射为数据库表，字段被子类继承。
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseEntity {
    /** 主键，自增策略由数据库生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建时间，由 JPA Auditing 在实体首次持久化时自动写入，后续不可更新 */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 最后更新时间，由 JPA Auditing 在每次更新实体时自动刷新 */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
