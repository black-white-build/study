package com.heartpilot.module.growth.repository;

import com.heartpilot.module.growth.entity.ActionCheckin;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<ActionCheckin, Long> {
    List<ActionCheckin> findByPlanIdAndUserIdOrderByCheckinDateAsc(Long planId, Long userId);

    Optional<ActionCheckin> findByPlanIdAndUserIdAndCheckinDate(
            Long planId, Long userId, LocalDate date);
}
