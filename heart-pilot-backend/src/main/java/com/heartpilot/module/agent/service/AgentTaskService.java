package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.AgentTaskStep;
import com.heartpilot.module.agent.entity.ToolCallRecord;
import com.heartpilot.module.file.entity.GeneratedFile;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentTaskService {
    void recoverInterruptedTasks();

    void recoverAndRetryTasks();

    Page<AgentTask> list(Long userId, Pageable pageable);

    List<String> cityOptions(String province);

    TaskDetail get(Long id, Long userId);

    AgentTask create(
            Long userId,
            String title,
            String objective,
            Map<String, Object> inputParameters,
            String requestIdempotencyKey);

    SseEmitter run(Long id, Long userId);

    SseEmitter confirm(
            Long id,
            Long userId,
            boolean approved,
            String note,
            String province,
            String city,
            BigDecimal budget,
            List<String> questions);

    GeneratedFile generatePdf(Long id, Long userId);

    GeneratedFile getPdf(Long id, Long userId);

    AgentTask cancel(Long id, Long userId);

    void delete(Long id, Long userId);

    public record TaskDetail(
            AgentTask task,
            List<AgentTaskStep> steps,
            List<ToolCallRecord> toolCalls,
            List<AgentExecutionEvent> executionEvents,
            GeneratedFile pdfFile) {}
}
