package com.heartpilot.module.agent.requirement;

import java.util.List;

/** 需求校验结果。 存在 BLOCKER 级问题时 blocked() 为 true，流程停止并交由用户调整约束， 不会让大模型强行生成不可行方案。 */
public record ValidationResult(List<RequirementIssue> issues) {

    public ValidationResult {
        issues = issues == null ? List.of() : issues;
    }

    public static ValidationResult ok() {
        return new ValidationResult(List.of());
    }

    /** 是否存在阻断级冲突（BLOCKER） */
    public boolean blocked() {
        return issues.stream().anyMatch(i -> i.severity() == RequirementIssue.Severity.BLOCKER);
    }

    public boolean hasWarnings() {
        return issues.stream().anyMatch(i -> i.severity() == RequirementIssue.Severity.WARNING);
    }

    /** 全部 BLOCKER 级问题 */
    public List<RequirementIssue> blockers() {
        return issues.stream()
                .filter(i -> i.severity() == RequirementIssue.Severity.BLOCKER)
                .toList();
    }

    /** 全部 WARNING 级问题 */
    public List<RequirementIssue> warnings() {
        return issues.stream()
                .filter(i -> i.severity() == RequirementIssue.Severity.WARNING)
                .toList();
    }
}
