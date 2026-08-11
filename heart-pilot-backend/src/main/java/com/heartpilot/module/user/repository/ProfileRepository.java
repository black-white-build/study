package com.heartpilot.module.user.repository;

import com.heartpilot.module.user.entity.RelationshipProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<RelationshipProfile, Long> {
    Optional<RelationshipProfile> findByUserId(Long userId);
}
