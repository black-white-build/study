package com.heartpilot.repository;

import com.heartpilot.domain.ActionPlan;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<ActionPlan, Long> {
    List<ActionPlan> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ActionPlan> findByIdAndUserId(Long id, Long userId);
}
