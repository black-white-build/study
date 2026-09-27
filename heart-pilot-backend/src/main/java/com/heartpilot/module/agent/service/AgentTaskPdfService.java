package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.file.entity.GeneratedFile;

/**
 * 任务报告 PDF 服务。
 * 负责把已完成任务的最终报告渲染为 PDF 文件，存储到对象存储并登记 GeneratedFile 记录。
 * 任务修订后会使旧 PDF 失效，需要重新生成。
 */
public interface AgentTaskPdfService {
    /**
     * 为任务生成 PDF 文件（已存在则重新生成覆盖）。
     * @param task 任务实体
     * @return 生成的文件记录
     */
    GeneratedFile generate(AgentTask task);

    /**
     * 查询某任务已生成的最新 PDF 文件。
     * @param userId 用户 ID（鉴权）
     * @param taskId 任务 ID
     * @return 文件记录，不存在返回 null
     */
    GeneratedFile get(Long userId, Long taskId);

    /**
     * 使任务已有 PDF 失效（任务被驳回重规划时调用），旧文件将被清理。
     * @param task 任务实体
     */
    void invalidate(AgentTask task);
}
