package com.heartpilot.module.knowledge.entity.enums;

/** 知识条目的人工审核流转状态。 */
public enum KnowledgeReviewStatus {
    /** 草稿：刚录入，尚未送审。 */
    DRAFT,
    /** 审核中：已提交，等待人工复核。 */
    IN_REVIEW,
    /** 已通过：审核完成，可对外检索使用。 */
    APPROVED,
    /** 已驳回：审核未通过，不予采用。 */
    REJECTED
}
