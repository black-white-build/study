# HeartPilot 工程说明

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
Agent 工具白名单仅包含网页搜索、终止工具，以及名称匹配地图、路线、POI、图片搜索的 MCP 工具。
终端、任意文件写入、无限下载等教学工具已移除。

高德 MCP 由 `MCP_ENABLED=true` 启用。图片搜索 MCP 需要先构建子模块，并额外启用
`image-mcp` Spring Profile，避免未构建子模块时影响主应用启动。简历演示环境可启用
`demo` Profile，它会同时打开 ReAct 与高德 MCP。任务详情页会展示 Agent 阶段、工具观察、真实地点、路线距离、预计耗时和降级原因。

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
