---
document_id: hp-invalid-missing-category
title: 非法样本：缺少必填分类的文档
source: HeartPilot 测试知识组
source_url:
version: 1.0.0
confidence: HIGH
review_status: APPROVED
applicable_scenario: 通用沟通
relationship_stage: 通用
risk_tags:
updated_at: 2026-10-01
---

# 这份文档用于测试失败路径

这份文档故意缺少 category 必填字段。使用 rebuildRepository 扫描该文件时，KnowledgeMarkdownParser 会抛出"缺少 category"异常，整次重建会失败。使用手动上传接口上传时，由于 Tika 只抽取正文文本，不会解析 front matter，因此这份文档可以正常入库，但需要在 KnowledgeTaxonomy.requireCategory 处由接口层的 category 参数兜底。这份样本用于验证解析器的必填校验是否生效。
