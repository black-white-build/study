package com.heartpilot.repository;

import com.heartpilot.domain.ActionCheckin;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<ActionCheckin, Long> {
    List<ActionCheckin> findByPlanIdAndUserIdOrderByCheckinDateAsc(Long planId, Long userId);

    Optional<ActionCheckin> findByPlanIdAndUserIdAndCheckinDate(
            Long planId, Long userId, LocalDate date);
}
