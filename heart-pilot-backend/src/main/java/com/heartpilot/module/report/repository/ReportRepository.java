package com.heartpilot.module.report.repository;

import com.heartpilot.module.report.entity.EmotionReport;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<EmotionReport, Long> {
    List<EmotionReport> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<EmotionReport> findByUserId(Long userId, Pageable pageable);

    Optional<EmotionReport> findByIdAndUserId(Long id, Long userId);
}
