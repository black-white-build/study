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

/**
 * Agent 任务核心服务接口。
 * 负责任务的完整生命周期：创建、异步执行（SSE 流式推送）、用户确认/驳回、最终报告生成、
 * PDF 导出、取消、删除，以及服务重启后的中断恢复与自动重试。
 */
public interface AgentTaskService {
    /** 应用启动时恢复一次僵死任务（心跳超时的 RUNNING 任务重置为待重试） */
    void recoverInterruptedTasks();

    /** 定时扫描：恢复僵死任务并对到期的 RETRY_WAIT 任务重新提交执行 */
    void recoverAndRetryTasks();

    /** 分页查询当前用户的任务列表 */
    Page<AgentTask> list(Long userId, Pageable pageable);

    /** 按省份返回可选城市列表 */
    List<String> cityOptions(String province);

    /**
     * 获取任务详情（聚合任务主体、步骤、工具调用、执行轨迹、最新 PDF）。
     * @param userId 用户 ID（鉴权，只能查自己的任务）
     */
    TaskDetail get(Long id, Long userId);

    /**
     * 创建任务（含幂等去重、地区校验、步骤初始化）。
     * @param requestIdempotencyKey 幂等键，同一用户 + 同一键重复请求只创建一条任务
     * @return 已持久化的任务实体
     */
    AgentTask create(
            Long userId,
            String title,
            String objective,
            Map<String, Object> inputParameters,
            String requestIdempotencyKey);

    /**
     * 启动任务执行，返回 SSE 发射器供前端实时接收进度。
     * 仅允许 WAITING/FAILED 状态，通过分布式锁保证多实例下唯一执行。
     */
    SseEmitter run(Long id, Long userId);

    /**
     * 用户确认或驳回候选计划。
     * approved=true 进入最终报告生成；false 合并修改后回到第一步重新规划。
     */
    SseEmitter confirm(
            Long id,
            Long userId,
            boolean approved,
            String note,
            String province,
            String city,
            BigDecimal budget,
            List<String> questions);

    /** 为已完成任务生成 PDF */
    GeneratedFile generatePdf(Long id, Long userId);

    /** 获取任务已生成的最新 PDF */
    GeneratedFile getPdf(Long id, Long userId);

    /** 取消任务，中断执行线程并置为 CANCELLED */
    AgentTask cancel(Long id, Long userId);

    /** 删除任务及其全部关联数据（运行中任务需先取消） */
    void delete(Long id, Long userId);

    /**
     * 任务详情聚合视图。
     * @param task 任务主体
     * @param steps 执行步骤列表
     * @param toolCalls 工具调用记录
     * @param executionEvents 执行轨迹事件
     * @param pdfFile 最新 PDF 文件
     */
    public record TaskDetail(
            AgentTask task,
            List<AgentTaskStep> steps,
            List<ToolCallRecord> toolCalls,
            List<AgentExecutionEvent> executionEvents,
            GeneratedFile pdfFile) {}
}
