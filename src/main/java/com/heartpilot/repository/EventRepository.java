package com.heartpilot.repository;

import com.heartpilot.domain.RelationshipEvent;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<RelationshipEvent, Long> {
    List<RelationshipEvent> findByUserIdOrderByHappenedAtDesc(Long userId);

    Page<RelationshipEvent> findByUserId(Long userId, Pageable pageable);
}
