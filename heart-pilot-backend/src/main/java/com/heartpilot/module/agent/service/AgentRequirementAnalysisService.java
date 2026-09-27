package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.List;

/**
 * 需求分析服务。
 * 把用户的城市、预算、问题列表、历史修订等输入，整理为结构化的检索需求文本与关键词列表。
 * 优先调用大模型生成检索关键词，失败时降级为规则提取。
 */
public interface AgentRequirementAnalysisService {
    /**
     * 分析用户需求，产出检索文本与关键词。
     *
     * @param task 任务实体
     * @param city 目标城市
     * @param budget 预算描述
     * @param questions 需要逐项回答的问题列表
     * @param revisions 历史修订记录列表
     * @return 分析结果（检索文本、关键词、是否由 AI 生成）
     */
    Analysis analyze(
            AgentTask task,
            String city,
            String budget,
            List<String> questions,
            List<String> revisions);

    /**
     * 需求分析结果。
     * @param searchText 拼接后的完整检索需求文本
     * @param keywords 提取出的检索关键词列表
     * @param aiGenerated true=由大模型生成；false=规则降级
     */
    public record Analysis(String searchText, List<String> keywords, boolean aiGenerated) {}

    /**
     * 大模型返回的结构化分析结果（仅关键词部分由模型输出）。
     * @param keywords 模型提取的关键词列表
     */
    public record ModelAnalysis(List<String> keywords) {}
}
