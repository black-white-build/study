# HeartPilot 工程说明

HeartPilot 是一个面向关系沟通场景的可恢复 AI 行动编排系统，提供知识增强答疑和多类型行动规划。

## 任务执行机制与边界

任务状态只允许以下核心迁移：

`WAITING -> RUNNING -> AWAITING_CONFIRMATION -> RUNNING -> SUCCEEDED`

执行失败时进入 `RETRY_WAIT`，达到退避时间后由恢复扫描重新执行；超过最大重试次数进入 `FAILED`。
取消进入 `CANCELLED`，终态任务不能再次进入运行态。

- JPA `@Version` 防止并发覆盖。
- Redis `SET NX + TTL` 保证多实例互斥，并通过 Lua 脚本校验锁所有者后续租、释放。
- Redis 不可用时降级到单实例本地锁。
- 工具使用 `Future#get(timeout)`，超时调用 `cancel(true)` 中断实际执行线程。
- `Idempotency-Key` 与用户 ID 组成唯一约束，重复创建返回原任务。
- 心跳与定时扫描恢复服务重启或实例失联留下的 `RUNNING` 任务。

## Agent 与 MCP 边界

固定状态机负责可恢复业务流程；ReAct 默认参与公开信息核验，调用失败时自动降级到结构化地图与网页检索。测试环境显式关闭该链路。
Agent 工具白名单仅包含网页搜索、终止工具，以及名称匹配地图、路线、POI 检索的 MCP 工具。
终端、任意文件写入、无限下载等教学工具已移除。

高德 MCP 由 `MCP_ENABLED=true` 启用。`demo` Profile 会同时打开 ReAct 与高德 MCP。
地点配图由主应用内的普通图片服务按需提供，不属于 Agent 工具链。任务详情页会展示 Agent 阶段、工具观察、真实地点、路线距离、预计耗时和降级原因。

## 可观测指标

启动后可通过 `/api/actuator/prometheus` 或 `/api/actuator/metrics/{metricName}` 查看：

- `heartpilot.chat.time_to_first_token`：流式对话首字延迟。
- `heartpilot.chat.generation.duration`：生成总耗时，按结果分类。
- `heartpilot.chat.active_generations`：当前生成数。
- `heartpilot.agent.tool.duration`：工具耗时。
- `heartpilot.agent.tool.failures`：工具失败与超时。
- `heartpilot.agent.tool.idempotency_hits`：工具幂等命中次数。
- `heartpilot.agent.task.transitions`：任务状态迁移次数。
- `heartpilot.rag.retrieval`：向量检索与关键词降级次数。
- `heartpilot.chat.route`：问题分流结果。
- `heartpilot.chat.safety_decision`：安全动作与语境（真实、第三方、否定、引用、假设）。
- `heartpilot.chat.citation_validation`：引用通过、修复或降级次数。
- `heartpilot.chat.citations`：总引用与无效引用计数。
- `heartpilot.chat.citation_coverage`：需要依据的结论与通过支持性校验的结论计数。

## 决策助手对话链路

对话依次经过安全语境识别、问题分流、当前会话结构化状态更新、知识检索、完整回答生成、固定结构渲染和引用校验。未经引用校验的模型输出不会发送给前端；SSE 保留为传输协议，但首个 `delta` 已是完成校验的内容。

Prompt 位于 `heart-pilot-backend/src/main/resources/prompts`，按 `classifier`、`answer`、`citation`、`safety` 分文件和版本加载。每条助手消息保存实际 Prompt 版本、知识索引版本、路由、安全语境和引用校验结果。

会话状态只在当前会话内保存，事实、推测、偏好和情绪分开存储。用户明确使用“更正”“刚才说错”“其实”等表达时，同类事实会按语义键覆盖；系统不建立跨会话心理画像。

## Git 管理的知识库

`knowledge/*.md` 是知识内容的规范仓库源，每个文件必须在 front matter 中声明来源、内容版本、可信度、审核状态和适用分类。修改 Markdown 后执行：

```powershell
.\scripts\rebuild-knowledge-index.ps1 -UserId 1
```

脚本先校验所有 Markdown，再进行全量索引重建。项目刻意不实现增量去重和知识管理后台工作流。索引版本由全部源文件内容哈希确定并进入检索、回答缓存键。

## 固定质量评测

固定样本、指标定义和改造前基线位于 `eval/`。执行：

```powershell
.\scripts\run-conversation-eval.ps1
```

评测会先回放旧关键词规则以复现基线，再运行当前安全与引用校验，报告引用虚假率、无结果降级成功率、高风险召回率和误报率。固定集是离线回归门槛，不替代真实模型和人工评审。

简历中的延迟、吞吐量和成功率应从这些指标及压测结果计算，不填写未经测量的数据。

## 验证

```bash
cd heart-pilot-backend
mvn verify
cd ../heart-pilot-frontend
npm ci
npm run format:check
npm run build
```

测试环境使用内存 H2，不读取本地开发数据库，也不调用真实 MCP 或 ReAct 链路。

上述验证只覆盖当前自动化测试中的关键路径。项目尚未完成真实 PostgreSQL/PGVector、Redis、MinIO
组合下的故障演练、容量压测和系统化 AI 效果评测，因此不据此声明生产级可靠性。
