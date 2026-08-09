package com.heartpilot.repository;

import com.heartpilot.domain.EmotionReport;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<EmotionReport, Long> {
    List<EmotionReport> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<EmotionReport> findByUserId(Long userId, Pageable pageable);

    Optional<EmotionReport> findByIdAndUserId(Long id, Long userId);
}
