package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.AgentTaskStep;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.ActionPlan;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.AgentTaskStepStatus;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.repository.TaskStepRepository;
import com.heartpilot.module.agent.repository.ToolCallRepository;
import com.heartpilot.module.agent.service.ActionDraftProposer;
import com.heartpilot.module.agent.service.ActionEnrichmentService;
import com.heartpilot.module.agent.service.ActionEnricher;
import com.heartpilot.module.agent.service.AgentExecutionTraceService;
import com.heartpilot.module.agent.service.AgentFinalReportService;
import com.heartpilot.module.agent.service.AgentJourneyResearchService;
import com.heartpilot.module.agent.service.AgentRequirementAnalysisService;
import com.heartpilot.module.agent.service.AgentTaskInputService;
import com.heartpilot.module.agent.service.AgentTaskPdfService;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.agent.service.AgentTaskStepService;
import com.heartpilot.module.agent.service.DistributedTaskLockService;
import com.heartpilot.module.agent.service.PlanModelService;
import com.heartpilot.module.agent.service.PlanSafetyChecker;
import com.heartpilot.module.agent.service.PlanningContext;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.repository.GeneratedFileRepository;
import com.heartpilot.module.file.service.StorageService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 任务核心服务实现。
 * 负责任务的完整生命周期管理：创建、异步执行（SSE 流式推送）、用户确认/驳回、
 * 最终报告生成、取消、删除，以及服务重启后的中断恢复与自动重试。
 *
 * 可靠性设计要点：
 * - 分布式锁（DistributedTaskLockService）保证同一任务在多实例下只有一个执行器
 * - 状态机（AgentTaskStateMachine）统一管理状态流转，禁止直接 setStatus
 * - 心跳 + 定时恢复扫描检测僵死任务，指数退避自动重试
 * - 乐观锁（@Version）防止并发更新覆盖
 * - 幂等键防止重复投稿
 */
@Service
public class AgentTaskServiceImpl implements AgentTaskService {
    /** 任务执行流程的步骤名称列表，索引对应步骤编号（从 1 开始），第 5 步为用户确认节点 */
    private static final List<String> FLOW =
            List.of(
                    "正在分析目标与约束",
                    "正在识别行动类型并生成草案",
                    "正在按行动类型检索信息",
                    "正在生成候选计划并检查安全",
                    "等待用户确认",
                    "正在生成正式计划版本",
                    "任务已完成");
    private final TaskRepository tasks;
    private final TaskStepRepository steps;
    private final ToolCallRepository calls;
    private final GeneratedFileRepository files;
    private final StorageService storage;
    private final ObjectMapper json;
    private final AgentTaskInputService taskInput;
    private final AgentJourneyResearchService journeyResearch;
    private final AgentTaskStepService taskSteps;
    private final AgentFinalReportService finalReports;
    private final AgentRequirementAnalysisService requirementAnalysis;
    /** 单任务最大执行步骤数，配置项 app.agent.max-steps，默认 10 */
    private final int maxSteps;
    /** 单任务最大重试次数，配置项 app.agent.max-task-retries，默认 2 */
    private final int maxTaskRetries;
    /** Agent 任务专用线程池，Bean 名称 agentTaskExecutor */
    private final ExecutorService executor;
    private final DistributedTaskLockService locks;
    private final AgentTaskStateMachine stateMachine;
    private final AgentTaskPdfService pdfService;
    private final AgentExecutionTraceService executionTrace;
    private final ActionDraftProposer draftProposer;
    private final ActionEnrichmentService enrichmentService;
    private final PlanSafetyChecker planSafetyChecker;
    private final PlanModelService planModel;
    /** 当前正在执行的任务 Future 映射（taskId -> Future），用于取消操作中断线程 */
    private final Map<Long, Future<?>> activeFutures = new ConcurrentHashMap<>();

    /**
     * 构造器注入所有依赖。
     * maxSteps 和 maxTaskRetries 从配置项读取，executor 通过 @Qualifier 指定专用线程池。
     */
    public AgentTaskServiceImpl(
            TaskRepository tasks,
            TaskStepRepository steps,
            ToolCallRepository calls,
            GeneratedFileRepository files,
            StorageService storage,
            ObjectMapper json,
            AgentTaskInputService taskInput,
            AgentJourneyResearchService journeyResearch,
            AgentTaskStepService taskSteps,
            AgentFinalReportService finalReports,
            AgentRequirementAnalysisService requirementAnalysis,
            @Value("${app.agent.max-steps:10}") int maxSteps,
            @Value("${app.agent.max-task-retries:2}") int maxTaskRetries,
            @Qualifier("agentTaskExecutor") ExecutorService executor,
            DistributedTaskLockService locks,
            AgentTaskStateMachine stateMachine,
            AgentTaskPdfService pdfService,
            AgentExecutionTraceService executionTrace,
            ActionDraftProposer draftProposer,
            ActionEnrichmentService enrichmentService,
            PlanSafetyChecker planSafetyChecker,
            PlanModelService planModel) {
        this.tasks = tasks;
        this.steps = steps;
        this.calls = calls;
        this.files = files;
        this.storage = storage;
        this.json = json;
        this.taskInput = taskInput;
        this.journeyResearch = journeyResearch;
        this.taskSteps = taskSteps;
        this.finalReports = finalReports;
        this.requirementAnalysis = requirementAnalysis;
        this.maxSteps = maxSteps;
        this.maxTaskRetries = maxTaskRetries;
        this.executor = executor;
        this.locks = locks;
        this.stateMachine = stateMachine;
        this.pdfService = pdfService;
        this.executionTrace = executionTrace;
        this.draftProposer = draftProposer;
        this.enrichmentService = enrichmentService;
        this.planSafetyChecker = planSafetyChecker;
        this.planModel = planModel;
    }

    @Override
    public PlanDetail plan(Long id, Long userId) {
        AgentTask task = tasks.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("任务不存在"));
        ActionPlan plan = planModel.findByTask(task);
        if (plan == null) return new PlanDetail(null, List.of(), List.of());
        List<PlanVersion> versions = planModel.versions(plan.getId());
        List<PlanActionItem> currentItems =
                versions.isEmpty() ? List.of() : planModel.itemsOf(versions.getFirst());
        return new PlanDetail(plan, versions, currentItems);
    }

    /**
     * 应用启动完成后触发一次中断恢复。
     * 扫描心跳超过 90 秒未更新的 RUNNING 任务，将其重置为待重试状态，
     * 避免服务宕机或重启后任务永久卡在 RUNNING。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Override
    public void recoverInterruptedTasks() {
        recoverStaleTasks(Instant.now().minusSeconds(90));
    }

    /**
     * 定时任务恢复与重试扫描（默认每 30 秒执行一次）。
     * 两步操作：
     * 1. 恢复心跳超时的僵死任务为 RETRY_WAIT
     * 2. 对已到重试时间的 RETRY_WAIT 任务，未超最大重试次数则重新提交执行，超过则标记 FAILED
     * 捕获 RuntimeException 是因为多实例环境下其他节点可能已抢到分布式锁。
     */
    @Scheduled(fixedDelayString = "${app.agent.recovery-scan-millis:30000}")
    @Override
    public void recoverAndRetryTasks() {
        recoverStaleTasks(Instant.now().minusSeconds(90));
        for (AgentTask task :
                tasks.findByStatusAndNextRetryAtBefore(AgentTaskStatus.RETRY_WAIT, Instant.now())) {
            // 超过最大重试次数，彻底失败
            if (task.getRetryCount() > task.getMaxRetries()) {
                stateMachine.transition(task, AgentTaskStatus.FAILED);
                continue;
            }
            stateMachine.transition(task, AgentTaskStatus.WAITING);
            try {
                run(task.getId(), task.getUserId());
            } catch (RuntimeException ignored) {
                // 其他实例可能已持有该任务的分布式锁，忽略冲突
            }
        }
    }

    /**
     * 将心跳超时的 RUNNING 任务恢复为 RETRY_WAIT。
     * 同时把处于 RUNNING 状态的步骤重置为 PENDING 并增加重试计数，
     * 确保恢复后能从该步骤重新执行。
     * @param heartbeatBefore 心跳早于此时间的任务视为僵死
     */
    private void recoverStaleTasks(Instant heartbeatBefore) {
        for (AgentTask task : tasks.findStaleTasks(AgentTaskStatus.RUNNING, heartbeatBefore)) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setNextRetryAt(Instant.now());
            task.setErrorMessage("检测到服务中断或心跳超时，任务将自动恢复");
            stateMachine.transition(task, AgentTaskStatus.RETRY_WAIT);
            for (AgentTaskStep step : steps.findByTaskIdOrderByStepNoAsc(task.getId())) {
                if (step.getStatus() == AgentTaskStepStatus.RUNNING) {
                    step.setStatus(AgentTaskStepStatus.PENDING);
                    step.setRetryCount(step.getRetryCount() + 1);
                    steps.save(step);
                }
            }
        }
    }

    /**
     * 分页查询当前用户的任务列表，按创建时间倒序（由 Repository 默认排序保证）。
     * @param userId 用户 ID
     * @param pageable 分页参数
     * @return 任务分页结果
     */
    @Override
    public Page<AgentTask> list(Long userId, Pageable pageable) {
        return tasks.findByUserId(userId, pageable);
    }

    /**
     * 实现：直接委托 taskInput.cityOptions，地区校验与高德接口调用逻辑见 AgentTaskInputServiceImpl。
     */
    @Override
    public List<String> cityOptions(String province) {
        return taskInput.cityOptions(province);
    }

    /**
     * 获取任务详情，聚合任务主体、步骤列表、工具调用记录、执行轨迹和最新 PDF 文件。
     * @param id 任务 ID
     * @param userId 用户 ID（用于鉴权，确保只能查自己的任务）
     * @return 聚合后的任务详情
     */
    @Override
    public TaskDetail get(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        GeneratedFile pdfFile =
                files.findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                                userId, "AGENT_TASK", id)
                        .orElse(null);
        return new TaskDetail(
                task,
                steps.findByTaskIdOrderByStepNoAsc(id),
                calls.findByTaskIdOrderByCreatedAtAsc(id),
                executionTrace.list(id),
                pdfFile);
    }

    /**
     * 创建 Agent 任务。
     * 流程：幂等键去重 → 校验并解析地区 → 校验问题列表非空 → 持久化任务 → 初始化 7 个执行步骤 → 记录执行轨迹。
     * 幂等设计：同一用户 + 同一幂等键的重复请求直接返回已有任务，不重复创建；
     * 并发场景下靠唯一索引兜底，捕获 DataIntegrityViolationException 后回查已有记录。
     *
     * @param userId 用户 ID
     * @param title 任务标题，为空时默认"城市+行动计划"
     * @param objective 目标描述
     * @param inputParameters 输入参数（城市、预算、问题列表等）
     * @param requestIdempotencyKey 幂等键，可为 null
     * @return 创建好的任务实体（已持久化，含自增 ID）
     */
    @Override
    public AgentTask create(
            Long userId,
            String title,
            String objective,
            Map<String, Object> inputParameters,
            String requestIdempotencyKey) {
        // 规范化幂等键（去空格等），null 表示不启用幂等
        String normalizedKey = taskInput.normalizeIdempotencyKey(requestIdempotencyKey);
        if (normalizedKey != null) {
            Optional<AgentTask> existing =
                    tasks.findByUserIdAndRequestIdempotencyKey(userId, normalizedKey);
            // 幂等命中：直接返回已有任务，不重复创建
            if (existing.isPresent()) return existing.get();
        }
        // 拷贝输入参数，避免修改调用方传入的原始 Map
        Map<String, Object> parameters =
                new LinkedHashMap<>(inputParameters == null ? Map.of() : inputParameters);
        // 校验并解析省市为具体城市，结果写回 parameters.searchRegion。
        // 只有用户明确提供地点约束时才要求省份+城市+问题列表；
        // 消息型/沟通型等计划无需地点检索，允许不填省/市/问题。
        String city = "";
        if (!String.valueOf(parameters.getOrDefault("province", "")).isBlank()) {
            city = taskInput.validateAndResolveRegion(parameters);
            parameters.put("searchRegion", city);
            taskInput.normalizeStoredBudget(parameters);
            // 问题列表是地点检索关键词的来源，提供地点时必须至少有一个
            List<String> searchQuestions = taskInput.asStringList(parameters.get("questions"));
            if (searchQuestions.isEmpty())
                throw ApiException.badRequest("请至少填写一个需要方案逐项回答的问题，搜索关键词只从这里提取");
            parameters.put("questions", searchQuestions);
        }
        // 修订记录列表，用户驳回时追加，初始为空列表
        parameters.putIfAbsent("revisions", new ArrayList<String>());

        AgentTask task = new AgentTask();
        task.setUserId(userId);
        task.setTitle(title == null || title.isBlank() ? city + "行动计划" : title.trim());
        task.setObjective(objective.trim());
        // 实际步骤数取配置上限和 FLOW 定义的较小值
        task.setMaxSteps(Math.min(maxSteps, FLOW.size()));
        task.setMaxRetries(maxTaskRetries);
        task.setRequestIdempotencyKey(normalizedKey);
        task.setParametersJson(taskInput.writeParameters(parameters));
        try {
            tasks.saveAndFlush(task);
        } catch (DataIntegrityViolationException conflict) {
            // 并发场景：唯一索引兜底，回查已有记录返回
            if (normalizedKey != null) {
                return tasks.findByUserIdAndRequestIdempotencyKey(userId, normalizedKey)
                        .orElseThrow(() -> conflict);
            }
            throw conflict;
        }

        // 按 FLOW 定义初始化 7 个步骤，第 5 步（索引 4）需要用户确认
        for (int i = 0; i < FLOW.size(); i++) {
            AgentTaskStep step = new AgentTaskStep();
            step.setTaskId(task.getId());
            step.setStepNo(i + 1);
            step.setName(FLOW.get(i));
            step.setConfirmationRequired(i == 4);
            steps.save(step);
        }
        trace(
                task,
                null,
                AgentExecutionPhase.ANALYZE,
                AgentExecutionEventType.THOUGHT,
                AgentExecutionEventStatus.SUCCEEDED,
                "任务已进入 Agent 执行队列",
                "系统将依次分析需求、检索真实地点、筛选候选、计算路线并生成计划。",
                "HeartPilot",
                null,
                null,
                null,
                null,
                Map.of("city", city));
        return task;
    }

    /**
     * 启动任务执行，返回 SseEmitter 供前端实时接收执行进度。
     * 仅允许 WAITING 或 FAILED 状态的任务启动；通过分布式锁保证多实例下唯一执行。
     * 执行在专用线程池中异步进行，SSE 连接超时（180秒）自动触发取消。
     *
     * @param id 任务 ID
     * @param userId 用户 ID（鉴权）
     * @return SSE 发射器，前端通过它接收 step/confirmation/error/done 等事件
     */
    @Override
    public SseEmitter run(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        // 只有等待中或失败的任务可以启动，其他状态（运行中/已完成/等待确认等）拒绝
        if (!Set.of(AgentTaskStatus.WAITING, AgentTaskStatus.FAILED).contains(task.getStatus())) {
            throw ApiException.badRequest("当前状态不能启动");
        }
        // 尝试获取 5 分钟的分布式锁，获取失败说明任务正在其他实例/线程执行
        DistributedTaskLockService.LockHandle lock = locks.tryAcquire(id, Duration.ofMinutes(5));
        if (lock == null) throw ApiException.conflict("TASK_ALREADY_RUNNING", "任务正在运行");
        task.setCancelRequested(false);
        // SSE 超时 180 秒，超时后自动取消任务
        SseEmitter emitter = new SseEmitter(180_000L);
        Future<?> future = executor.submit(() -> executeUntilConfirmation(task, emitter, lock));
        activeFutures.put(id, future);
        emitter.onTimeout(() -> cancel(id, userId));
        return emitter;
    }

    /**
     * 执行任务直到用户确认阶段（步骤 1-5）。
     * 流程：需求分析 → 地点与路线检索 → 公开信息补充 → 生成候选计划预览 → 进入等待确认。
     * 在发送 confirmation 事件后主动释放分布式锁并完成 SSE 流，
     * 避免用户立即点击确认时与当前 worker 的 finally 块竞争锁导致 TASK_ALREADY_RUNNING。
     *
     * @param task 任务实体
     * @param emitter SSE 发射器
     * @param lock 分布式锁句柄，执行完毕或异常时释放
     */
    private void executeUntilConfirmation(
            AgentTask task, SseEmitter emitter, DistributedTaskLockService.LockHandle lock) {
        try {
            Map<String, Object> parameters = taskInput.readParameters(task);
            String city = taskInput.resolveCity(parameters, task.getObjective());
            String budget = taskInput.parameterText(parameters.get("budget"), "未限定");
            List<String> questions = taskInput.asStringList(parameters.get("questions"));
            List<String> revisions = taskInput.asStringList(parameters.get("revisions"));
            String revision = revisions.isEmpty() ? "无" : String.join("；", revisions);

            // 第 1 步：分析目标与约束（保留原有需求分析，产出检索关键词）
            AgentRequirementAnalysisService.Analysis analyzed =
                    requirementAnalysis.analyze(task, city, budget, questions, revisions);
            parameters.put("searchKeywords", analyzed.keywords());
            task.setParametersJson(taskInput.writeParameters(parameters));

            stateMachine.transition(task, AgentTaskStatus.RUNNING);
            task.setErrorMessage(null);
            saveTask(task);
            taskSteps.complete(
                    task,
                    1,
                    "目标："
                            + task.getObjective()
                            + "\n地点："
                            + city
                            + "\n预算："
                            + budget
                            + (questions.isEmpty()
                                    ? ""
                                    : "\n需要逐项回答：\n- " + String.join("\n- ", questions))
                            + ("无".equals(revision) ? "" : "\n累计修改要求：" + revision),
                    emitter);
            trace(
                    task,
                    1,
                    AgentExecutionPhase.ANALYZE,
                    AgentExecutionEventType.THOUGHT,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "已完成结构化需求分析",
                    "城市："
                            + city
                            + "；预算："
                            + budget
                            + "；提取检索意图："
                            + String.join("、", analyzed.keywords())
                            + "。",
                    analyzed.aiGenerated() ? "DashScope" : "规则降级",
                    null,
                    questions.size(),
                    null,
                    null,
                    Map.of(
                            "city",
                            city,
                            "budget",
                            budget,
                            "questionCount",
                            questions.size(),
                            "searchKeywords",
                            analyzed.keywords(),
                            "aiGenerated",
                            analyzed.aiGenerated()));

            // 第 2 步：识别行动目标并生成多类型行动草案
            PlanningContext context =
                    new PlanningContext(task, city, budget, questions, revisions, parameters, 3);
            ActionDraftProposer.ActionProposal proposal = draftProposer.propose(context);
            String draftSummary =
                    proposal.drafts().stream()
                            .map(draft -> draft.kind().label() + "：" + draft.title())
                            .reduce((left, right) -> left + "；" + right)
                            .orElse("无");
            taskSteps.complete(
                    task,
                    2,
                    "已识别行动目标："
                            + (proposal.goalType() == null ? "未识别" : proposal.goalType().label())
                            + "；拟生成 "
                            + proposal.drafts().size()
                            + " 条行动草案："
                            + draftSummary,
                    emitter);
            trace(
                    task,
                    2,
                    AgentExecutionPhase.ANALYZE,
                    AgentExecutionEventType.ACTION,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "已生成多类型行动草案",
                    draftSummary,
                    proposal.aiGenerated() ? "DashScope" : "规则降级",
                    null,
                    proposal.drafts().size(),
                    null,
                    null,
                    Map.of(
                            "goalType",
                            proposal.goalType() == null ? "" : proposal.goalType().name(),
                            "draftCount",
                            proposal.drafts().size()));

            // 第 3 步：按行动类型调用工具与检索知识（富化）
            List<ActionEnricher.EnrichedAction> enriched =
                    enrichmentService.enrichAll(proposal.drafts(), context);
            long degradedCount =
                    enriched.stream()
                            .filter(
                                    action ->
                                            action.item().getPayloadJson().contains("ENRICHMENT_FAILED")
                                                    || action.item().getPayloadJson().contains("UNSUPPORTED"))
                            .count();
            taskSteps.complete(
                    task,
                    3,
                    "已完成行动信息检索与富化，共 "
                            + enriched.size()
                            + " 条行动"
                            + (degradedCount > 0 ? "（其中 " + degradedCount + " 条信息暂不可用）" : "")
                            + "。",
                    emitter);
            trace(
                    task,
                    3,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.RESULT,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "已完成按行动类型的信息富化",
                    "共 " + enriched.size() + " 条行动条目"
                            + (degradedCount > 0 ? "，" + degradedCount + " 条降级" : "") + "。",
                    "ActionEnricher",
                    null,
                    enriched.size(),
                    null,
                    null,
                    Map.of("degradedCount", degradedCount));

            // 第 4 步：执行安全检查（P11），通过后才保存草稿版本
            List<PlanSafetyChecker.DraftItem> safetyItems = new ArrayList<>();
            for (ActionEnricher.EnrichedAction enrichedAction : enriched) {
                PlanActionItem item = enrichedAction.item();
                safetyItems.add(
                        new PlanSafetyChecker.DraftItem(
                                item.getExecutionKind(),
                                item.getTitle(),
                                item.getInstruction(),
                                parsePayload(item.getPayloadJson())));
            }
            PlanSafetyChecker.Decision safety = planSafetyChecker.evaluate(safetyItems);
            if (!safety.approved()) {
                // 高风险计划直接拒绝生成：不重试、不进确认流程
                AgentTask latest =
                        tasks.findByIdAndUserId(task.getId(), task.getUserId()).orElse(task);
                latest.setErrorMessage(shorten(safety.message(), 480));
                stateMachine.transition(latest, AgentTaskStatus.FAILED);
                trace(
                        latest,
                        4,
                        AgentExecutionPhase.COMPLETE,
                        AgentExecutionEventType.ERROR,
                        AgentExecutionEventStatus.FAILED,
                        "计划未通过安全检查",
                        safety.message(),
                        "PlanSafetyChecker",
                        null,
                        safety.blockedTitles().size(),
                        null,
                        null,
                        Map.of("reasonCode", safety.reasonCode()));
                event(emitter, "error", Map.of("message", safety.message()));
                emitter.complete();
                return;
            }

            PlanVersion draft =
                    planModel.saveDraft(
                            task, proposal.goalType(), enriched, taskInput.budgetLabel(budget));
            task.setPlanPreview(draft.getPreviewText());
            saveTask(task);
            taskSteps.complete(
                    task,
                    4,
                    "已生成可确认的候选计划，预算上限：" + taskInput.budgetLabel(budget) + "。",
                    emitter);
            trace(
                    task,
                    4,
                    AgentExecutionPhase.FILTER,
                    AgentExecutionEventType.RESULT,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "候选计划已通过安全检查并保存为草稿版本",
                    "版本 " + draft.getVersionNo() + "，共 " + enriched.size() + " 条行动。",
                    "PlanModelService",
                    null,
                    enriched.size(),
                    null,
                    null,
                    Map.of(
                            "versionNo",
                            draft.getVersionNo(),
                            "goalType",
                            proposal.goalType() == null ? "" : proposal.goalType().name()));

            // 第 5 步：等待用户确认
            AgentTaskStep confirmation = steps.findByTaskIdAndStepNo(task.getId(), 5).orElseThrow();
            confirmation.setStatus(AgentTaskStepStatus.WAITING_CONFIRMATION);
            confirmation.setDetail("请查看下方当前计划。没问题可直接继续；有问题时填写修改原因，任务会回到第一步重新规划。");
            confirmation.setStartedAt(Instant.now());
            steps.save(confirmation);
            task.setCurrentStep(5);
            stateMachine.transition(task, AgentTaskStatus.AWAITING_CONFIRMATION);
            event(emitter, "confirmation", Map.of("step", confirmation, "planPreview", draft.getPreviewText()));
            // The client may render the confirmation event immediately. Release execution
            // ownership before completing the stream so an immediate click cannot race this
            // worker's finally block and receive TASK_ALREADY_RUNNING.
            lock.close();
            emitter.complete();
        } catch (Exception e) {
            fail(task, e, emitter);
        } finally {
            activeFutures.remove(task.getId());
            lock.close();
        }
    }

    /** 把 payload JSON 字符串解析为 Map，解析失败返回空 Map（安全检查兜底不阻断） */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            return json.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * 用户确认或驳回候选计划。
     * - approved=true：直接进入最终报告生成阶段（finish）
     * - approved=false：校验修改项（地点/预算/问题/说明至少改一项），合并修改后回到第一步重新规划（reviseAndRestart）
     * 两种路径都通过 SSE 流式推送进度。
     *
     * @param id 任务 ID
     * @param userId 用户 ID（鉴权）
     * @param approved true=确认计划，false=驳回并要求修改
     * @param note 修改说明，驳回时追加到修订记录
     * @param province 修改后的省份
     * @param city 修改后的城市
     * @param budget 修改后的预算
     * @param questions 修改后的问题列表
     * @return SSE 发射器
     */
    @Override
    public SseEmitter confirm(
            Long id,
            Long userId,
            boolean approved,
            String note,
            String province,
            String city,
            BigDecimal budget,
            List<String> questions) {
        AgentTask task = owned(id, userId);
        // 仅允许在等待确认状态下调用
        if (task.getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION) {
            throw ApiException.badRequest("任务当前不等待确认");
        }
        DistributedTaskLockService.LockHandle lock = locks.tryAcquire(id, Duration.ofMinutes(5));
        if (lock == null) throw ApiException.conflict("TASK_ALREADY_RUNNING", "任务正在运行");

        SseEmitter emitter = new SseEmitter(180_000L);
        if (approved) {
            try {
                // 用户确认：提交最终报告生成任务
                Future<?> future =
                        executor.submit(() -> finish(task, note, questions, emitter, lock));
                activeFutures.put(id, future);
            } catch (RuntimeException submissionFailure) {
                // 线程池提交失败时必须释放锁，否则任务永久卡死
                lock.close();
                throw submissionFailure;
            }
        } else {
            try {
                Map<String, Object> current = taskInput.readParameters(task);
                // 是否通过编辑器提交了字段（省份/城市/问题任一非空）
                boolean editorSubmission = province != null || city != null || questions != null;
                String currentCity = taskInput.resolveCity(current, task.getObjective());
                String requestedCity = currentCity;
                if (province != null || city != null) {
                    Map<String, Object> requestedRegion = new LinkedHashMap<>();
                    requestedRegion.put("province", province == null ? "" : province);
                    requestedRegion.put("city", city == null ? "" : city);
                    requestedCity = taskInput.validateAndResolveRegion(requestedRegion);
                }
                if (editorSubmission && requestedCity.isBlank())
                    throw ApiException.badRequest("地点 / 城市不能为空");
                if (budget != null && budget.signum() < 0)
                    throw ApiException.badRequest("预算不能小于 0");
                String currentBudget = taskInput.parameterText(current.get("budget"), "");
                String requestedBudget =
                        budget == null ? "" : taskInput.normalizeBudget(budget).toPlainString();
                // 检测哪些字段实际发生了变化
                boolean cityChanged = !requestedCity.equals(currentCity);
                boolean budgetChanged = editorSubmission && !requestedBudget.equals(currentBudget);
                boolean questionsChanged =
                        questions != null
                                && !taskInput
                                        .asStringList(questions)
                                        .equals(taskInput.asStringList(current.get("questions")));
                // 驳回时必须至少有一项实际修改，否则无意义
                if ((note == null || note.isBlank())
                        && !cityChanged
                        && !budgetChanged
                        && !questionsChanged)
                    throw ApiException.badRequest("请至少修改地点、预算、问题或补充说明中的一项");
                reviseAndRestart(
                        task,
                        note == null ? "" : note.trim(),
                        province,
                        city,
                        budget,
                        questions,
                        emitter,
                        lock);
            } catch (RuntimeException preparationFailure) {
                lock.close();
                throw preparationFailure;
            }
        }
        return emitter;
    }

    /**
     * 合并用户修改要求并回到第一步重新规划。
     * 将修改说明追加到 revisions，更新地区/预算/问题参数，版本号自增，
     * 重置步骤进度和最终结果，使旧 PDF 失效，然后重新提交 executeUntilConfirmation。
     */
    private void reviseAndRestart(
            AgentTask task,
            String note,
            String province,
            String city,
            BigDecimal budget,
            List<String> incomingQuestions,
            SseEmitter emitter,
            DistributedTaskLockService.LockHandle lock) {
        Map<String, Object> parameters = taskInput.readParameters(task);
        List<String> revisions = taskInput.asStringList(parameters.get("revisions"));
        if (!note.isBlank()) revisions.add(note);
        parameters.put("revisions", revisions);
        if (province != null || city != null) {
            Map<String, Object> requestedRegion = new LinkedHashMap<>();
            requestedRegion.put("province", province == null ? "" : province);
            requestedRegion.put("city", city == null ? "" : city);
            String revisedRegion = taskInput.validateAndResolveRegion(requestedRegion);
            parameters.putAll(requestedRegion);
            parameters.put("searchRegion", revisedRegion);
        }
        boolean editorSubmission = province != null || city != null || incomingQuestions != null;
        if (budget == null && editorSubmission) parameters.remove("budget");
        else if (budget != null && budget.signum() >= 0)
            parameters.put("budget", taskInput.normalizeBudget(budget));
        List<String> revisedQuestions =
                incomingQuestions == null
                        ? taskInput.asStringList(parameters.get("questions"))
                        : taskInput.asStringList(incomingQuestions);
        parameters.put(
                "questions",
                taskInput.mergeQuestions(revisedQuestions, taskInput.extractQuestions(note)));
        task.setParametersJson(taskInput.writeParameters(parameters));
        // 驳回的草稿版本置为 REJECTED 并记录原因，历史版本保留
        planModel.rejectCurrentDraft(task, note);
        stateMachine.transition(task, AgentTaskStatus.WAITING);
        task.setCurrentStep(0);
        task.setFinalResult(null);
        task.setPlanPreview(null);
        task.setErrorMessage(null);
        task.setCancelRequested(false);
        task.setVersionNo(task.getVersionNo() + 1);
        pdfService.invalidate(task);
        saveTask(task);
        taskSteps.reset(task.getId());
        event(emitter, "revision", Map.of("message", "已合并新要求，回到第一步重新分析"));
        Future<?> future = executor.submit(() -> executeUntilConfirmation(task, emitter, lock));
        activeFutures.put(task.getId(), future);
    }

    /**
     * 用户确认后执行最终阶段（步骤 6-7）：重新实时检索 → 生成最终报告 → 标记完成。
     * 确认阶段可能补充了新问题，需要合并后重新提取检索类别并刷新地点信息。
     */
    private void finish(
            AgentTask task,
            String note,
            List<String> incomingQuestions,
            SseEmitter emitter,
            DistributedTaskLockService.LockHandle lock) {
        try {
            Map<String, Object> parameters = taskInput.readParameters(task);
            List<String> existingQuestions = taskInput.asStringList(parameters.get("questions"));
            List<String> questions =
                    incomingQuestions == null
                            ? existingQuestions
                            : taskInput.asStringList(incomingQuestions);
            questions = taskInput.mergeQuestions(questions, taskInput.extractQuestions(note));
            int addedQuestionCount = Math.max(0, questions.size() - existingQuestions.size());
            parameters.put("questions", questions);
            task.setParametersJson(taskInput.writeParameters(parameters));

            AgentTaskStep confirmation = steps.findByTaskIdAndStepNo(task.getId(), 5).orElseThrow();
            confirmation.setStatus(AgentTaskStepStatus.COMPLETED);
            confirmation.setDetail(
                    addedQuestionCount == 0
                            ? "用户已确认当前计划，系统正在按全部问题实时刷新检索信息。"
                            : "用户已确认当前计划，并补充 " + addedQuestionCount + " 个问题；系统正在重新提取类别并实时检索。");
            confirmation.setCompletedAt(Instant.now());
            steps.save(confirmation);
            stateMachine.transition(task, AgentTaskStatus.RUNNING);
            task.setCurrentStep(6);
            saveTask(task);
            taskSteps.start(task, 6, "正在从确认阶段的全部问题中提取检索类别，并刷新地点与公开网页信息。", emitter);

            String allRequirements = taskInput.combinedRequirements(task, parameters);
            String city = taskInput.resolveCity(parameters, task.getObjective());
            String budget = taskInput.parameterText(parameters.get("budget"), "未限定");
            List<String> revisions = taskInput.asStringList(parameters.get("revisions"));
            AgentRequirementAnalysisService.Analysis analyzed =
                    requirementAnalysis.analyze(task, city, budget, questions, revisions);
            String searchRequirements = analyzed.searchText();
            parameters.put("searchKeywords", analyzed.keywords());
            task.setParametersJson(taskInput.writeParameters(parameters));
            AgentJourneyResearchService.JourneyResearch journey =
                    journeyResearch.researchJourney(
                            task, 6, city, searchRequirements, "confirmation-live-journey-search");
            String liveSearch = journey.formatted();
            // 生成正式计划版本：先写最终报告全文，再把当前草稿版本置为 APPROVED
            task.setFinalResult(
                    finalReports.generate(task, allRequirements, questions, budget, note, journey));
            planModel.approveCurrentDraft(task, task.getFinalResult());
            // 用已确认版本的行动条目重新渲染预览，与正式计划保持一致
            ActionPlan plan = planModel.findByTask(task);
            if (plan != null) {
                List<PlanVersion> allVersions = planModel.versions(plan.getId());
                if (!allVersions.isEmpty()) {
                    PlanVersion approved = allVersions.getFirst();
                    task.setPlanPreview(
                            planModel.renderPreview(
                                    planModel.itemsOf(approved), taskInput.budgetLabel(budget)));
                }
            }
            confirmation.setDetail(
                    (addedQuestionCount == 0 ? "已按全部问题" : "已合并 " + addedQuestionCount + " 个补充问题并")
                            + "实时刷新检索；动态类别："
                            + taskInput.searchCategories(liveSearch)
                            + "。继续生成最终报告。");
            steps.save(confirmation);
            saveTask(task);
            event(emitter, "step", confirmation);

            task.setFinalResult(
                    finalReports.generate(task, allRequirements, questions, budget, note, journey));
            taskSteps.finish(task, 6, "行动报告与逐项问题解答已生成。", emitter);
            taskSteps.complete(task, 7, "任务已完成。确认报告内容后，可在本页单独生成 PDF 文件。", emitter);
            stateMachine.transition(task, AgentTaskStatus.SUCCEEDED);
            trace(
                    task,
                    7,
                    AgentExecutionPhase.COMPLETE,
                    AgentExecutionEventType.RESULT,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "行程任务执行完成",
                    "地点证据、路线证据、工具审计和最终报告均已持久化。",
                    "HeartPilot",
                    null,
                    journey.evidence().places().size(),
                    null,
                    null,
                    Map.of("routeCount", journey.evidence().routes().size()));
            event(emitter, "done", task);
            emitter.complete();
        } catch (Exception e) {
            fail(task, e, emitter);
        } finally {
            activeFutures.remove(task.getId());
            lock.close();
        }
    }

    /** 为已完成的任务生成 PDF 文件，委托给 pdfService */
    @Override
    public GeneratedFile generatePdf(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        return pdfService.generate(task);
    }

    /** 获取任务已生成的最新 PDF 文件 */
    @Override
    public GeneratedFile getPdf(Long id, Long userId) {
        owned(id, userId);
        return pdfService.get(userId, id);
    }

    /**
     * 取消任务。设置取消标志并中断执行线程，状态流转为 CANCELLED。
     * 已处于终态（SUCCEEDED/FAILED/CANCELLED）的任务直接返回，不重复操作。
     */
    @Override
    public AgentTask cancel(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        if (task.getStatus().isTerminal()) return task;
        task.setCancelRequested(true);
        // 从活跃任务表移除并中断执行线程（mayInterruptIfRunning=true）
        Future<?> future = activeFutures.remove(id);
        if (future != null) future.cancel(true);
        return stateMachine.transition(task, AgentTaskStatus.CANCELLED);
    }

    /**
     * 删除任务及其全部关联数据（步骤、工具调用、执行轨迹、PDF 文件）。
     * 运行中的任务不允许删除，需先取消。
     * 存储文件删除失败不阻断数据库删除（catch 后继续），避免孤立文件阻塞清理。
     */
    @Transactional
    @Override
    public void delete(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        if (task.getStatus() == AgentTaskStatus.RUNNING)
            throw ApiException.badRequest("任务执行中，请先取消后再删除");
        Future<?> future = activeFutures.remove(id);
        if (future != null) future.cancel(true);
        // 级联删除关联的 PDF 文件（存储对象 + 数据库记录）
        for (GeneratedFile file :
                files.findByUserIdAndBusinessTypeAndBusinessId(userId, "AGENT_TASK", id)) {
            try {
                storage.delete(file.getStorageKey());
            } catch (Exception ignored) {
                // 存储删除失败不阻断，数据库记录仍删除
            }
            files.delete(file);
        }
        calls.deleteByTaskId(id);
        executionTrace.deleteByTaskId(id);
        steps.deleteByTaskId(id);
        // 级联删除计划产物（计划、版本、行动条目），与任务一起清理
        planModel.deleteByTask(id);
        tasks.delete(task);
    }

    /**
     * 统一的任务失败处理。
     * 根据异常类型和重试次数决定后续状态：
     * - 取消/中断异常 → CANCELLED
     * - 未超最大重试次数 → RETRY_WAIT（指数退避，下次重试时间 = 5 * 2^(retryCount-1) 秒，上限 60 秒）
     * - 超过最大重试次数 → FAILED
     * 同时记录执行轨迹和 SSE error 事件。
     */
    private void fail(AgentTask task, Exception error, SseEmitter emitter) {
        // 重新从数据库读取最新状态，避免使用过期的内存对象
        AgentTask latest = tasks.findByIdAndUserId(task.getId(), task.getUserId()).orElse(task);
        latest.setErrorMessage(
                shorten(Optional.ofNullable(error.getMessage()).orElse("任务失败"), 480));
        if (error instanceof CancellationException || error instanceof InterruptedException) {
            // 用户主动取消或线程被中断
            if (latest.getStatus() != AgentTaskStatus.CANCELLED) {
                stateMachine.transition(latest, AgentTaskStatus.CANCELLED);
            }
        } else if (latest.getRetryCount() < latest.getMaxRetries()) {
            latest.setRetryCount(latest.getRetryCount() + 1);
            // 指数退避：5s, 10s, 20s, 40s... 上限 60s
            latest.setNextRetryAt(
                    Instant.now().plusSeconds(Math.min(60, 5L << (latest.getRetryCount() - 1))));
            stateMachine.transition(latest, AgentTaskStatus.RETRY_WAIT);
        } else {
            stateMachine.transition(latest, AgentTaskStatus.FAILED);
        }
        trace(
                latest,
                latest.getCurrentStep(),
                AgentExecutionPhase.COMPLETE,
                AgentExecutionEventType.ERROR,
                AgentExecutionEventStatus.FAILED,
                "任务执行异常",
                latest.getErrorMessage(),
                "HeartPilot",
                null,
                null,
                null,
                null,
                Map.of("nextStatus", latest.getStatus().name()));
        event(emitter, "error", Map.of("message", latest.getErrorMessage()));
        emitter.complete();
    }

    /**
     * 记录一条执行轨迹事件，委托给 AgentExecutionTraceService。
     * 轨迹用于前端展示任务执行的完整时间线（思考、工具调用、结果、错误等）。
     */
    private void trace(
            AgentTask task,
            Integer stepNo,
            AgentExecutionPhase phase,
            AgentExecutionEventType eventType,
            AgentExecutionEventStatus status,
            String title,
            String detail,
            String provider,
            String toolName,
            Integer itemCount,
            Long durationMs,
            String sourceUrl,
            Map<String, ?> metadata) {
        executionTrace.record(
                task.getId(),
                task.getVersionNo(),
                stepNo,
                phase,
                eventType,
                status,
                title,
                detail,
                provider,
                toolName,
                itemCount,
                durationMs,
                sourceUrl,
                metadata);
    }

    /**
     * 按 ID + 用户 ID 查询任务，不存在则抛出 404。
     * 所有需要鉴权的操作都通过此方法获取任务，确保用户只能操作自己的任务。
     */
    private AgentTask owned(Long id, Long userId) {
        return tasks.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("任务不存在"));
    }

    /**
     * 保存任务并同步乐观锁版本号到内存对象。
     * saveAndFlush 后数据库的 @Version 字段会自增，需要回写到 task 对象，
     * 否则后续更新会因版本号不匹配抛出 OptimisticLockingFailureException。
     */
    private AgentTask saveTask(AgentTask task) {
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }

    /** 截断字符串到指定长度，null 返回空串，用于错误信息存储前的长度控制 */
    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }

    /**
     * 向 SSE 流发送一个命名事件。
     * 发送失败（客户端已断开）静默忽略，因为执行流程不应因前端断开而中断。
     */
    private void event(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            // 客户端已断开，忽略
        }
    }
}
