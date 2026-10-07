package com.heartpilot.module.agent.requirement;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 结构化需求相关请求/响应 DTO。
 * <p>GET 查询 / PATCH 增量修改 / POST 确认 三个接口共用 RequirementResponse；
 * UpdateRequest 支持单条约束局部修改（改预算、删黑名单、加必去点位等），
 * 不要求用户整段重写需求描述。
 */
public final class RequirementDtos {
    private RequirementDtos() {}

    /**
     * 结构化需求响应：约束清单 + 代码校验结果 + 状态。
     * @param requirement 结构化需求对象（四类约束 + 业务实体）
     * @param issues 校验问题列表（BLOCKER 阻断 / WARNING 提醒）
     * @param blocked 是否存在阻断级冲突
     * @param hasWarnings 是否存在提醒级问题
     * @param status DRAFT=待确认（有冲突）；CONFIRMED=已确认
     * @param extractionSource AI=大模型抽取；RULE=规则降级抽取
     * @param summary 面向用户的 Markdown 约束清单
     */
    public record RequirementResponse(
            StructuredRequirement requirement,
            List<RequirementIssue> issues,
            boolean blocked,
            boolean hasWarnings,
            String status,
            String extractionSource,
            String summary) {

        public static RequirementResponse from(RequirementStateService.RequirementSnapshot snapshot) {
            if (snapshot == null) return null;
            return new RequirementResponse(
                    snapshot.requirement(),
                    snapshot.validation().issues(),
                    snapshot.validation().blocked(),
                    snapshot.validation().hasWarnings(),
                    snapshot.status(),
                    snapshot.extractionSource(),
                    snapshot.requirement() == null ? "" : snapshot.requirement().summary());
        }
    }

    /**
     * 增量修改请求。
     * @param path 字段路径：四类约束用列表名（hardConstraints/priorityPreferences/optionalEnhancements/exclusions），
     *             实体字段用点路径（place.budgetMax / place.latestReturnTime / gift.forbiddenCategories 等）
     * @param op SET=覆盖字段；ADD=列表追加；REMOVE=列表移除
     * @param value 新值（SET）或列表元素（ADD/REMOVE）
     */
    public record UpdateRequest(
            @NotBlank(message = "字段路径不能为空") String path,
            @NotBlank(message = "操作类型不能为空") String op,
            Object value) {}
}
