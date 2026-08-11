package com.heartpilot.module.growth.repository;

import com.heartpilot.module.growth.entity.ActionPlan;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<ActionPlan, Long> {
    List<ActionPlan> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ActionPlan> findByIdAndUserId(Long id, Long userId);
}
