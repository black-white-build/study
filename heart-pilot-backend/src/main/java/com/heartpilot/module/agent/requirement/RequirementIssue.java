package com.heartpilot.module.agent.requirement;

/**
 * 需求校验问题项。
 * 由纯 Java 代码校验器产出，不依赖大模型判断。
 *
 * @param severity 严重级别：BLOCKER=不可生成方案，必须调整约束；WARNING=可生成但需留意
 * @param code 稳定问题编码，前端可据此渲染
 * @param field 关联字段路径（如 place.budgetMax），供前端定位
 * @param message 面向用户的冲突说明
 */
public record RequirementIssue(Severity severity, String code, String field, String message) {

    public enum Severity {
        BLOCKER,
        WARNING
    }

    public static RequirementIssue blocker(String code, String field, String message) {
        return new RequirementIssue(Severity.BLOCKER, code, field, message);
    }

    public static RequirementIssue warning(String code, String field, String message) {
        return new RequirementIssue(Severity.WARNING, code, field, message);
    }
}
