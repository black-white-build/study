# HeartPilot 相对上游项目改造清单

## 1. 来源与使用边界

HeartPilot 基于程序员鱼皮公开的教学项目
[`liyupi/yu-ai-agent`](https://github.com/liyupi/yu-ai-agent) 继续开发。上游提供了 Spring AI
大模型接入、恋爱咨询示例、RAG、工具调用、MCP 图片搜索和 ReAct/Manus 教学骨架。

本项目不把上述教学骨架声明为个人原创。简历和面试重点应放在下列二次工程化工作，并能够通过代码、测试和演示说明实现细节。

## 2. 领域命名与代码映射

命名调整是为了让代码与 HeartPilot 产品领域一致，本身不作为技术贡献：

| 上游/旧命名 | HeartPilot 命名 | 说明 |
|---|---|---|
| `com.yupi.yuaiagent` | `com.heartpilot` | 主应用根包 |
| `YuAiAgentApplication` | `HeartPilotApplication` | Spring Boot 启动类 |
| `LoveApp` | `RelationshipAiClient` | 关系咨询模型客户端 |
| `YuManus` | `PublicInfoResearchAgent` | 受限公开信息研究 Agent |
| `loveAppVectorStore` | `relationshipVectorStore` | 关系知识向量库 Bean |
| `yu-image-search-mcp-server` | `heart-pilot-image-mcp-server` | 图片搜索 MCP 子模块 |
| `yu-ai-agent` artifact | `heart-pilot-backend` | Maven 构件名 |

代码仍保留本文件和 README 中的上游链接，避免把改名误解为原创证明。

## 3. 主要二次开发内容

### 3.1 从单次 Demo 到多用户业务闭环

- 新增注册、登录、BCrypt、JWT、USER/ADMIN 权限和用户资料。
- 会话、消息、报告、任务、计划、打卡、文件全部持久化，并按当前用户 ID 查询。
- 新增关系档案、关系事件、结构化报告、行动计划、每日打卡和周复盘。
- 新增报告与行动方案 PDF 生成、历史记录和下载。

### 3.2 数据库会话与流式交互

- 将会话和消息从教学内存/文件记忆改为数据库持久化。
- 使用 POST-SSE 返回模型增量内容和任务状态事件。
- 支持停止生成、重新生成、上下文消息数/字符数限制和失败状态保存。
- 增加首字耗时、生成耗时、活动生成数等 Micrometer 指标。

### 3.3 可恢复的确定性任务编排

- 使用明确状态机管理 `WAITING`、`RUNNING`、`AWAITING_CONFIRMATION`、`RETRY_WAIT` 和终态。
- 增加 JPA `@Version` 乐观锁、请求幂等键和数据库唯一约束。
- 增加 Redis `SET NX + TTL` 任务锁，以及校验持有者的 Lua 续租/释放。
- 增加心跳、服务启动恢复扫描、退避重试、取消标志和运行线程中断。
- Thought/Action/Observation/Result 作为执行事件持久化，可在刷新页面后继续查看。
- 支持人工确认和带新参数的重新规划，旧版本与新版本轨迹分支可区分。

### 3.4 Agent 权限收敛

- 固定状态机负责业务状态，ReAct 只负责必要的公开信息补充核验。
- 删除/不注册终端、任意文件写入、无限下载等高风险教学工具。
- 仅允许网页搜索、终止和名称匹配地图/路线/POI/图片的 MCP 工具。
- 地点和路线优先使用高德可核验结果，失败时显式降级，不用模型虚构 POI。

### 3.5 RAG 与存储链路

- 管理员上传 Markdown、TXT、PDF、Word，使用 Tika 解析和清洗。
- 文档按固定窗口和重叠长度切片，保存片段、关键词、来源和向量 ID。
- 生产 Profile 使用 PGVector/HNSW；向量检索异常或未配置模型时使用关键词降级。
- 文件存储支持本地和 MinIO 两种实现。

### 3.6 安全、部署与可观测性

- Spring Security + JWT + BCrypt + RBAC。
- 关键业务查询携带用户 ID，并有跨用户读取失败的集成测试。
- Redis 分布式限流不可用时退化为单实例本地限流。
- Flyway 管理 PostgreSQL 结构；Docker Compose 编排 PGVector、Redis、MinIO、后端和前端。
- Actuator/Prometheus 暴露聊天、检索、工具和状态迁移指标。

### 3.7 前端重构

- 使用 Vue 3/Vite 重做产品信息架构和响应式界面。
- 新增登录、咨询、报告、行动规划、可观测任务详情、档案、成长计划和知识库页面。
- 自行实现 POST-SSE 解析、终止请求、断线状态处理和按路由懒加载。
- 引入 Prettier，CI 执行 `format:check` 后再构建。

### 3.8 本轮代码结构重构

原 `AgentTaskService` 约 1172 行，现拆为：

- `AgentTaskService`：用例入口、任务编排、确认/取消和资源清理。
- `AgentTaskInputService`：参数归一化、问题合并、城市/预算处理和计划预览。
- `AgentJourneyResearchService`：地点/路线检索、证据保存和受限 ReAct 补充。
- `AgentTaskStepService`：步骤状态、心跳和 SSE 进度事件。
- `AgentFinalReportService`：最终提示词、模型生成、降级文本和生成轨迹。

## 4. 当前验证范围

已经验证：

- Java 源码干净编译；现有 12 个主应用测试通过。
- 图片 MCP 子模块 2 个测试通过。
- Spotless 格式检查、前端 Prettier 检查和 Vite 生产构建。
- H2 测试环境下的 JWT 数据隔离、任务幂等、人工确认、重规划和工具中断等关键路径。

尚未证明：

- 未使用 Testcontainers 验证 PostgreSQL/PGVector、Redis、MinIO 的完整组合。
- 未进行多实例故障演练、容量规划、正式压测或长时间稳定性测试。
- 未建立系统化 RAG/Agent 评测集，因此不声明检索准确率、任务成功率或模型质量指标。
- `cancel(true)` 只发出线程中断，第三方 HTTP/SDK 是否立即停止仍取决于底层实现。

因此合适的表述是“实现并测试了关键执行机制”，而不是“已达到生产级高可用”。

## 5. 简历推荐表述

> 基于开源 Spring AI 教学项目进行二次工程化，将其扩展为多用户关系成长系统，独立完成数据库会话、JWT 数据隔离、RAG 文档管理、关系报告与行动计划闭环。

> 设计确定性 Agent 任务状态机，引入乐观锁、Redis 租约锁、幂等键、心跳恢复、退避重试、人工确认和执行事件持久化；受限 ReAct Agent 仅用于白名单公开信息核验。

> 构建 Tika 文档解析、切片、PGVector/HNSW 检索和关键词降级链路，并通过 Docker Compose、Flyway、MinIO、Actuator/Prometheus 和 CI 完成交付配置。

不要写“独立从零开发”“完全自主智能体”“生产级高可用”或未经测量的性能百分比。
