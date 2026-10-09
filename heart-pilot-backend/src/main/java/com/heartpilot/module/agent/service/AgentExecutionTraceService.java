package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import java.util.List;
import java.util.Map;

/** Agent 执行轨迹服务。 负责记录与查询任务执行过程中的每一个事件（思考、工具调用、结果、错误等）， 用于前端时间线展示与执行溯源。每条轨迹绑定任务版本号，便于区分同一任务多次修订。 */
public interface AgentExecutionTraceService {
    /**
     * 查询某任务的全部执行轨迹事件（按时间顺序）。
     *
     * @param taskId 任务 ID
     * @return 事件列表
     */
    List<AgentExecutionEvent> list(Long taskId);

    /**
     * 记录一条执行轨迹事件。
     *
     * @param taskId 任务 ID
     * @param taskVersion 任务版本号，区分多次修订
     * @param stepNo 关联步骤编号，可为空
     * @param phase 执行阶段（分析/检索/补全/完成等）
     * @param eventType 事件类型（思考/工具调用/结果/错误等）
     * @param status 事件状态（成功/失败等）
     * @param title 事件标题
     * @param detail 事件详情
     * @param provider 模型或工具提供方
     * @param toolName 工具名称
     * @param itemCount 涉及条目数量
     * @param durationMs 耗时（毫秒）
     * @param sourceUrl 来源 URL
     * @param metadata 扩展元数据
     * @return 已持久化的事件实体
     */
    AgentExecutionEvent record(
            Long taskId,
            int taskVersion,
            Integer stepNo,
            AgentExecutionPhase phase,
            AgentExecutionEventType eventType,
            AgentExecutionEventStatus status,
            String title,
            String detail,
            String provider,
            String toolName,
            Integer itemCount,
            Long durationMs,
            String sourceUrl,
            Map<String, ?> metadata);

    /**
     * 删除某任务的全部轨迹（随任务删除级联清理）。
     *
     * @param taskId 任务 ID
     */
    void deleteByTaskId(Long taskId);
}
