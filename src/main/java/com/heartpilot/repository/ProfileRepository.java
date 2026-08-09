package com.heartpilot.repository;

import com.heartpilot.domain.RelationshipProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<RelationshipProfile, Long> {
    Optional<RelationshipProfile> findByUserId(Long userId);
}
