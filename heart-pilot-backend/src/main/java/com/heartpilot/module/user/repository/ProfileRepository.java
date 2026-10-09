package com.heartpilot.module.user.repository;

import com.heartpilot.module.user.entity.RelationshipProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 关系档案 Repository，操作实体 RelationshipProfile（表 relationship_profile）。 */
public interface ProfileRepository extends JpaRepository<RelationshipProfile, Long> {
    /** 按用户 ID 查询关系档案，与用户一对一 */
    Optional<RelationshipProfile> findByUserId(Long userId);
}
