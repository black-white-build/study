package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;

/**
 * 行程研究服务。
 * 负责按城市与需求检索真实地点、地点间路线，并补充公开网页信息，产出可核验的行程证据。
 * 是 Agent 流程中"地点与路线检索"环节的核心服务。
 */
public interface AgentJourneyResearchService {
    /**
     * 执行一次行程检索（地点 + 路线）。
     * @param task 任务实体（用于记录执行轨迹与版本隔离）
     * @param stepNo 当前执行步骤编号
     * @param city 目标城市
     * @param requirements 检索需求文本
     * @param toolName 工具调用名称（用于轨迹记录）
     * @return 行程检索结果（格式化文本 + 结构化证据）
     * @throws Exception 检索过程中的底层异常
     */
    JourneyResearch researchJourney(
            AgentTask task, int stepNo, String city, String requirements, String toolName)
            throws Exception;

    /**
     * 在已检索地点基础上，补充公开网页信息并做交叉核验。
     * @param task 任务实体
     * @param city 目标城市
     * @param originalPlaces 初次检索得到的地点文本
     * @return 补充后的地点文本与核验说明
     */
    PublicResearch supplementPublicInfo(AgentTask task, String city, String originalPlaces);

    /**
     * 行程检索结果：格式化文本（喂给大模型/预览）+ 结构化证据（地点、路线等，用于报告与溯源）。
     * @param formatted 格式化后的检索文本
     * @param evidence 结构化行程证据
     */
    public record JourneyResearch(String formatted, PlaceSearchService.JourneyEvidence evidence) {}

    /**
     * 公开信息补充结果。
     * @param places 补充/修订后的地点文本
     * @param verification 公开信息交叉核验说明
     */
    public record PublicResearch(String places, String verification) {}
}
