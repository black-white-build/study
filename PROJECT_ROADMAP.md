# HeartPilot 交付状态

## 已完成

- 产品闭环：登录 → AI 咨询 → RAG → 关系报告 → PDF → 历史记录。
- Agent 闭环：任务创建 → 步骤执行 → 工具审计 → 用户确认 → 最终方案/PDF。
- 长期成长：关系档案、事件记录、7 天计划、情绪打卡、每周复盘。
- 工程底座：PostgreSQL/PGVector、Redis、MinIO、JWT、限流、Flyway、Docker Compose、Actuator。
- 前端信息架构与响应式 UI 全量重构。

## 后续可选增强

- 将高德地图 MCP 返回的 POI 结构化为地图卡片，并接入实时路线/营业状态。
- Pexels 结构化图片检索与许可信息作为可选 MCP 能力保留，不进入行动规划任务详情。
- Redis 缓存热门知识检索与模型结果，加入消费成本仪表盘。
- Prometheus/Grafana、OpenTelemetry 与告警规则。
- Testcontainers 覆盖 PostgreSQL/PGVector/Redis/MinIO 集成测试。
- 管理员用户管理、账号停用、审计日志导出与 Key 轮换流程。
