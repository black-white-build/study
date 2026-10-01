package com.heartpilot.module.knowledge.entity.enums;

/** 知识条目所依据证据的可信程度，用于检索时的可信度排序与过滤。 */
public enum KnowledgeEvidenceLevel {
    /** 高可信：来自权威来源或经过充分验证。 */
    HIGH,
    /** 中可信：来源较可靠，但尚未完全验证。 */
    MEDIUM,
    /** 低可信：仅供参考，需谨慎采用。 */
    LOW,
    /** 未验证：尚无证据支撑，仅作占位。 */
    UNVERIFIED
}
