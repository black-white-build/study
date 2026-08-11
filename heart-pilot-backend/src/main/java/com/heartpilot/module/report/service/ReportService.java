package com.heartpilot.module.report.service;

import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.report.entity.EmotionReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReportService {
    Page<EmotionReport> list(Long userId, Pageable pageable);

    EmotionReport get(Long id, Long userId);

    void delete(Long id, Long userId);

    EmotionReport generate(Long conversationId, Long userId);

    GeneratedFile exportPdf(Long reportId, Long userId);
}
