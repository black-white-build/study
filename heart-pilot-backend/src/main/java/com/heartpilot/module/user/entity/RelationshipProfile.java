package com.heartpilot.module.user.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 用户关系档案实体，对应数据库表 relationship_profile。
 * 每个用户一条（与 userId 唯一约束），记录感情状态、相处时长、沟通方式、
 * 关注事项、偏好与边界等信息，作为 AI 关系咨询与报告生成的上下文。
 * 继承 BaseEntity 获得 id、创建时间、更新时间等公共字段。
 */
@Entity
@Table(
        name = "relationship_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_profile_user", columnNames = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class RelationshipProfile extends BaseEntity {
    /** 所属用户 ID，与用户一对一 */
    @Column(nullable = false)
    private Long userId;

    /** 关系状态，默认"未设置" */
    @Column(length = 32)
    private String relationshipStatus = "未设置";

    /** 关系持续月数，可空表示未填写 */
    private Integer relationshipMonths;

    /** 双方沟通方式描述 */
    @Column(length = 200)
    private String communicationStyle;

    /** 用户长期关注/担忧的问题 */
    @Column(length = 1000)
    private String concerns;

    /** 用户在关系中的偏好 */
    @Column(length = 1000)
    private String preferences;

    /** 用户在关系中的边界/底线 */
    @Column(length = 1000)
    private String boundaries;
}
