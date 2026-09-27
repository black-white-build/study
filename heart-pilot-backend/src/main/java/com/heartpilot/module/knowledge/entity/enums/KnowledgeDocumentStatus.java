package com.heartpilot.module.knowledge.entity.enums;

/**
 * 知识库文档处理状态枚举，字符串持久化。
 * 状态流转：UPLOADED → PROCESSING → READY（成功）或 FAILED（解析/向量化失败）。
 * 由 KnowledgeServiceImpl.process 统一维护，禁止业务方直接跳过状态。
 */
public enum KnowledgeDocumentStatus {
    /** 已上传到对象存储，尚未开始解析切片 */
    UPLOADED,
    /** 正在解析文本、切片、向量化写入 PGVector */
    PROCESSING,
    /** 全部切片已向量化，可被检索 */
    READY,
    /** 解析或向量化失败，errorMessage 记录原因 */
    FAILED
}
