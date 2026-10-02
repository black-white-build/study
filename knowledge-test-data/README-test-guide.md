# HeartPilot 知识库上传功能 · 测试数据与测试指南

本目录用于测试 `KnowledgeController` 上传链路、`KnowledgeServiceImpl` 解析/切片/向量化、审核与 RAG 检索。所有文档均符合 `KnowledgeMarkdownParser` 的 front matter 解析规范。

## 目录结构

```
knowledge-test-data/
├── README-test-guide.md     本文档
├── valid/                   规范文档，9 篇，覆盖全部 8 个分类
│   ├── 01-communication-basic.md        沟通基础，HIGH，通用沟通
│   ├── 02-communication-deep-dive.md    沟通基础，MEDIUM，长文（约 2400 字，测多切片）
│   ├── 03-conflict-repair.md            冲突与修复，HIGH，冲突修复场景
│   ├── 04-boundary-consent.md           边界与同意，MEDIUM
│   ├── 05-risk-safety.md                风险与安全，HIGH，risk_tags=家暴,人身安全,暴力
│   ├── 06-digital-communication.md      数字沟通，MEDIUM，数字沟通场景
│   ├── 07-relationship-stages.md        关系阶段，LOW，恋爱期
│   ├── 08-breakup-recovery.md           分手与结束关系，MEDIUM，review_status=IN_REVIEW
│   └── 09-action-planning.md            行动设计，UNVERIFIED
└── invalid/                 非法样本，2 篇，测解析失败路径
    ├── 10-invalid-missing-category.md  缺少 category 必填字段
    └── 11-invalid-no-front-matter.md   无 front matter 头
```

## 文档规范要点（决定能否通过校验）

- 文件必须以 `---` 开头，元数据块以 `\n---` 闭合，否则 `KnowledgeMarkdownParser` 直接抛异常。
- 必填字段：`document_id`、`title`、`category`、`source`、`version`、`confidence`，缺失即报错。
- `category` 必须在白名单内：沟通基础、冲突与修复、边界与同意、关系阶段、分手与结束关系、数字沟通、行动设计、风险与安全。
- `confidence` 合法值为 HIGH、MEDIUM、LOW、UNVERIFIED；`review_status` 合法值为 DRAFT、IN_REVIEW、APPROVED、REJECTED，两者都是枚举直转，写错会抛 `IllegalArgumentException`。
- 可选字段：`applicable_scenario`（默认"通用沟通"）、`relationship_stage`（默认"通用"）、`source_url`（可为空）、`risk_tags`（可为空）。
- 正文不能为空，正文中的 `# 一级标题` 会被提取为切片章节名（`sectionTitle`）。
- 手动上传不解析 front matter（只走 Tika 抽正文）；只有 `rebuildRepository` 目录重建才解析 front matter。所以 invalid 样本在手动上传路径下反而能成功，这是设计差异，可分别验证。

## 测试前置条件

- 启动后端服务（含 PostgreSQL、Redis；MinIO 或本地存储二选一），项目根目录有 docker-compose 配置。
- 准备一个 ADMIN 角色账号。`/admin/knowledge` 前缀的接口类级 `@PreAuthorize("hasRole('ADMIN')")`，非管理员访问返回 403。
- 可选：配置 `spring.ai.dashscope.api-key`。未配置时 `aiEnabled=false`，检索自动降级为关键词匹配，不影响链路验证，但无法测向量相似度。

## 测试步骤一：管理员登录获取令牌

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"你的管理员账号","password":"你的密码"}'
```

返回体里拿到 `accessToken`（JWT），后续所有请求带上请求头 `Authorization: Bearer 该令牌`。

## 测试步骤二：手动上传文档

上传接口为 `POST /admin/knowledge/documents`，参数是 multipart 表单。`category` 必填，其余有默认值。

```bash
curl -X POST "http://localhost:8080/admin/knowledge/documents" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@S:/heart-pilot/knowledge-test-data/valid/01-communication-basic.md" \
  -F "category=沟通基础" \
  -F "applicableScenario=通用沟通" \
  -F "relationshipStage=通用" \
  -F "sourceName=HeartPilot 测试知识组" \
  -F "contentVersion=1.0.0" \
  -F "reviewStatus=IN_REVIEW" \
  -F "evidenceLevel=HIGH"
```

预期：HTTP 200，返回文档元数据；上传是同步处理，返回时 `status` 已经是 `READY`（解析切片全部完成），`chunkCount` 为切片数。可以连续上传多篇做批量测试。

需要验证的边界用例：
- 上传 `08-breakup-recovery.md` 时把 `reviewStatus` 保持 `IN_REVIEW`（默认值即是），用于验证"未审核文档不进检索池"。
- 上传 `02-communication-deep-dive.md`，观察 `chunkCount`：正文约 2400 字按 1200 字/120 重叠切，应得到 3 片（切片数 = ceil((正文长度 - 120) / 1080)，即 (2373-120)/1080 ≈ 2.09 向上取整）。
- 上传不支持的格式（如 `.zip` 改名、`.exe`），预期 400 "仅支持 Markdown、TXT、PDF 和 Word 文件"，由 `SUPPORTED_TYPES` 白名单拦截。
- 用非 ADMIN 账号上传，预期 403。

## 测试步骤三：查看已切片的文档内容

```bash
curl "http://localhost:8080/admin/knowledge/documents/{文档ID}/content" \
  -H "Authorization: Bearer $TOKEN"
```

预期：返回按 `chunkIndex` 升序拼接的纯文本、切片数、原文件名。该接口只读 `knowledge_chunk`，不重新解析原文件。

## 测试步骤四：审核通过

```bash
curl -X PATCH "http://localhost:8080/admin/knowledge/documents/{文档ID}/approve" \
  -H "Authorization: Bearer $TOKEN"
```

预期：`reviewStatus` 变为 `APPROVED`，`indexVersion` 变为 `approved-时间戳`。对 08 号文档执行该操作后，它才进入检索池。

需要验证的边界用例：对尚未 `READY`（如 FAILED）的文档执行 approve，预期 409 "文档尚未处理完成"；对已是 APPROVED 的文档重复执行，返回原文档不报错。

## 测试步骤五：对话检索验证（RAG 链路）

检索不暴露独立 HTTP 接口，通过对话流式接口间接验证。先创建会话：

```bash
curl -X POST http://localhost:8080/conversations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

拿到 `conversationId` 后发送问题（SSE 流式）：

```bash
curl -N -X POST "http://localhost:8080/conversations/{会话ID}/messages/stream" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"content":"对方冷战时我该怎么破冰"}'
```

预期：SSE 依次返回 `delta`（回答内容）和 `done`（status=COMPLETED）。回答如果引用了知识库，会带 `[来源 N｜《文档名》·章节｜发布方｜证据等级]` 样式的引用编号；对话消息落库后 `sourcesJson` 会回填实际引用的来源列表，可在数据库或管理端查看。

推荐的问题与命中预期：
- "怎么区分事实和解释" 命中 `01-communication-basic.md`。
- "冷战之后怎么破冰" 命中 `03-conflict-repair.md`。
- "对方翻我手机怎么办" 命中 `04-boundary-consent.md`。
- "已读不回是什么意思" 命中 `06-digital-communication.md`。
- "分手后要不要断联" 命中 `08-breakup-recovery.md`（审核通过后才命中，通过前应检索不到）。
- "我想自杀"、"对方打我怎么办" 命中 `05-risk-safety.md`，且对话路由应被安全策略拦截，回答走安全话术而不是普通回答（观察 `auditJson` 中的 route 是否为 SAFETY）。
- 不带向量库（未配 DashScope key）时，以上问题靠关键词兜底命中，验证 `keyword_fallback` 指标与结果相关度。

## 测试步骤六：目录全量重建（rebuild 路径）

rebuild 通过 `knowledge-reindex` profile 在启动时执行，需要把 `app.knowledge.source-directory` 指到 valid 目录：

```bash
java -jar heart-pilot-backend.jar \
  --spring.profiles.active=knowledge-reindex \
  --app.knowledge.source-directory=S:/heart-pilot/knowledge-test-data/valid \
  --app.knowledge.reindex-user-id=1
```

（实际以项目当前启动方式为准，例如 Maven 时用 `mvn spring-boot:run -Dspring-boot.run.profiles=knowledge-reindex`。）

预期：控制台输出 `Knowledge index rebuilt: version=... documents=9 chunks=...`，所有旧文档被级联删除后重新入库，`review_status` 由 front matter 决定（08 号为 IN_REVIEW，其余 APPROVED）。

需要验证的失败用例：把 `app.knowledge.source-directory` 指到 `knowledge-test-data` 根目录（含 invalid 子目录），预期解析到 10 号或缺元数据文件时抛"缺少 category / 缺少 Markdown 元数据"异常，整次重建失败，用于验证解析器强校验。

## 测试步骤七：删除与级联清理

```bash
curl -X DELETE "http://localhost:8080/admin/knowledge/documents/{文档ID}" \
  -H "Authorization: Bearer $TOKEN"
```

预期：先按 `vectorId` 批量删除 PGVector 向量，再删 `knowledge_chunk` 记录，再删对象存储文件（删除失败被吞掉不阻断），最后删 `knowledge_document` 元数据。删除后重新检索该文档相关内容应不再命中。

## 验证口径速查

- 文档状态机：UPLOADED → PROCESSING → READY，失败置 FAILED 并记录 errorMessage。
- 切片规则：1200 字符/片，120 字符重叠，切片表 `knowledge_chunk` 以 `(documentId, chunkIndex)` 唯一约束。
- 检索准入：必须 READY 且 APPROVED，且分类/场景与查询计划匹配（`eligible()` 过滤）。
- 缓存失效：审核通过时 `indexVersion` 更新，检索缓存键和模型结果缓存键自然失效，文档"通过即生效"。
- 检索三级策略：Redis 缓存命中 → PGVector 向量相似度（阈值 0.58）→ 关键词包含兜底。
