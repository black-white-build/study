package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务步骤服务。
 * 负责任务执行步骤的状态推进（开始/完成/结束/重置），并通过 SSE 实时向前端推送步骤进度。
 */
public interface AgentTaskStepService {
    /**
     * 标记某步骤完成并推送进度。
     * @param task 任务实体
     * @param number 步骤编号
     * @param detail 步骤详情文本
     * @param emitter SSE 发射器
     */
    void complete(AgentTask task, int number, String detail, SseEmitter emitter);

    /**
     * 标记某步骤开始执行并推送进度。
     */
    void start(AgentTask task, int number, String detail, SseEmitter emitter);

    /**
     * 标记某步骤结束（一般用于最后一步的收尾描述）。
     */
    void finish(AgentTask task, int number, String detail, SseEmitter emitter);

    /**
     * 重置某任务的全部步骤为初始状态（驳回重规划时调用）。
     * @param taskId 任务 ID
     */
    void reset(Long taskId);
}
