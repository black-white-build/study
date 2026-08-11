package com.heartpilot.module.file.repository;

import com.heartpilot.module.file.entity.GeneratedFile;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, Long> {
    Page<GeneratedFile> findByUserId(Long userId, Pageable pageable);

    Optional<GeneratedFile> findByIdAndUserId(Long id, Long userId);

    Optional<GeneratedFile> findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
            Long userId, String businessType, Long businessId);

    List<GeneratedFile> findByUserIdAndBusinessTypeAndBusinessId(
            Long userId, String businessType, Long businessId);
}
