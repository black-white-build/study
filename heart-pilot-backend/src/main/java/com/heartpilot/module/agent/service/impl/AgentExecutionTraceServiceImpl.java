package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.repository.AgentExecutionEventRepository;
import com.heartpilot.module.agent.service.AgentExecutionTraceService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 执行轨迹服务实现。 负责把任务执行过程中的思考、工具调用、观察、结果、错误等事件逐条持久化， 供前端渲染完整的执行时间线（Trace），实现过程可观测、可审计。
 *
 * <p>可靠性设计要点： - record 使用 REQUIRES_NEW 独立事务，即使外层业务事务回滚，轨迹事件也尽量落库， 保证失败现场可追溯 - 标题、来源 URL
 * 等字段在写入前截断，避免超长内容撑爆数据库列 - metadata 序列化为 JSON 字符串，序列化失败降级为 null，不阻断主流程
 */
@Service
public class AgentExecutionTraceServiceImpl implements AgentExecutionTraceService {
    /** 执行事件 Repository，按 taskId 倒序/正序查询 */
    private final AgentExecutionEventRepository events;

    /** 用于把 metadata Map 序列化为 JSON 字符串 */
    private final ObjectMapper json;

    /** 构造器注入 Repository 与 ObjectMapper。 */
    public AgentExecutionTraceServiceImpl(AgentExecutionEventRepository events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    /**
     * 按创建时间正序查询某个任务的全部执行轨迹事件。
     *
     * @param taskId 任务 ID
     * @return 事件列表（时间线顺序）
     */
    @Override
    public List<AgentExecutionEvent> list(Long taskId) {
        return events.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    /**
     * 记录一条执行轨迹事件。 使用 REQUIRES_NEW 开启独立事务：轨迹记录属于审计信息，不应随外层业务回滚而丢失， 即使后续步骤失败，也要保留"执行到哪一步、为什么失败"的现场。
     *
     * @param taskId 任务 ID
     * @param taskVersion 任务版本号（驳回重规划后自增，用于区分不同轮次的轨迹）
     * @param stepNo 所属步骤编号，可为空（任务级事件）
     * @param phase 执行阶段（检索/筛选/路线/生成/完成等）
     * @param eventType 事件类型（思考/动作/观察/结果/错误等）
     * @param status 事件状态（运行中/成功/失败等）
     * @param title 事件标题（截断到 160 字符）
     * @param detail 事件详情正文
     * @param provider 数据来源（如 DashScope、高德地图），截断到 80 字符
     * @param toolName 触发该事件的工具名，截断到 80 字符
     * @param itemCount 本次涉及的条目数量（如地点数）
     * @param durationMs 耗时毫秒数
     * @param sourceUrl 可核验的来源链接，截断到 500 字符
     * @param metadata 结构化附加数据，序列化为 JSON
     * @return 已持久化的事件实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public AgentExecutionEvent record(
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
            Map<String, ?> metadata) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setTaskId(taskId);
        event.setTaskVersion(taskVersion);
        event.setStepNo(stepNo);
        event.setPhase(phase);
        event.setEventType(eventType);
        event.setStatus(status);
        // 数据库列长度有限，标题/来源等短字段写入前统一截断
        event.setTitle(shorten(title, 160));
        event.setDetail(detail);
        event.setProvider(shorten(provider, 80));
        event.setToolName(shorten(toolName, 80));
        event.setItemCount(itemCount);
        event.setDurationMs(durationMs);
        event.setSourceUrl(shorten(sourceUrl, 500));
        event.setMetadataJson(writeMetadata(metadata));
        return events.save(event);
    }

    /**
     * 删除某个任务的全部轨迹事件，随任务级联清理时调用。
     *
     * @param taskId 任务 ID
     */
    @Transactional
    @Override
    public void deleteByTaskId(Long taskId) {
        events.deleteByTaskId(taskId);
    }

    /** 把 metadata Map 序列化为 JSON 字符串。 空 Map 返回 null 节省存储；序列化失败静默返回 null，不影响主流程。 */
    private String writeMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return json.writeValueAsString(metadata);
        } catch (Exception ignored) {
            // 序列化失败降级为 null，不阻断轨迹记录
            return null;
        }
    }

    /** 截断字符串到指定最大长度，null 原样返回。 用于控制短文本列（标题、来源等）的入库长度。 */
    private String shorten(String value, int maxLength) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), maxLength));
    }
}
