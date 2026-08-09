package com.heartpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.domain.AgentExecutionEvent;
import com.heartpilot.domain.AgentTask;
import com.heartpilot.domain.AgentTaskStep;
import com.heartpilot.domain.GeneratedFile;
import com.heartpilot.domain.ToolCallRecord;
import com.heartpilot.domain.enums.AgentExecutionEventStatus;
import com.heartpilot.domain.enums.AgentExecutionEventType;
import com.heartpilot.domain.enums.AgentExecutionPhase;
import com.heartpilot.domain.enums.AgentTaskStatus;
import com.heartpilot.domain.enums.AgentTaskStepStatus;
import com.heartpilot.repository.GeneratedFileRepository;
import com.heartpilot.repository.TaskRepository;
import com.heartpilot.repository.TaskStepRepository;
import com.heartpilot.repository.ToolCallRepository;
import com.heartpilot.web.ApiException;
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

@Service
public class AgentTaskService {
    private static final List<String> FLOW =
            List.of(
                    "正在分析用户需求",
                    "正在搜索地点与公开信息",
                    "正在读取并筛选候选信息",
                    "正在整理预算与时间",
                    "等待用户确认",
                    "正在生成最终方案",
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
    private final int maxSteps;
    private final int maxTaskRetries;
    private final ExecutorService executor;
    private final DistributedTaskLockService locks;
    private final AgentTaskStateMachine stateMachine;
    private final AgentTaskPdfService pdfService;
    private final AgentExecutionTraceService executionTrace;
    private final Map<Long, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public AgentTaskService(
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
            @Value("${app.agent.max-steps:10}") int maxSteps,
            @Value("${app.agent.max-task-retries:2}") int maxTaskRetries,
            @Qualifier("agentTaskExecutor") ExecutorService executor,
            DistributedTaskLockService locks,
            AgentTaskStateMachine stateMachine,
            AgentTaskPdfService pdfService,
            AgentExecutionTraceService executionTrace) {
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
        this.maxSteps = maxSteps;
        this.maxTaskRetries = maxTaskRetries;
        this.executor = executor;
        this.locks = locks;
        this.stateMachine = stateMachine;
        this.pdfService = pdfService;
        this.executionTrace = executionTrace;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        recoverStaleTasks(Instant.now().minusSeconds(90));
    }

    @Scheduled(fixedDelayString = "${app.agent.recovery-scan-millis:30000}")
    public void recoverAndRetryTasks() {
        recoverStaleTasks(Instant.now().minusSeconds(90));
        for (AgentTask task :
                tasks.findByStatusAndNextRetryAtBefore(AgentTaskStatus.RETRY_WAIT, Instant.now())) {
            if (task.getRetryCount() > task.getMaxRetries()) {
                stateMachine.transition(task, AgentTaskStatus.FAILED);
                continue;
            }
            stateMachine.transition(task, AgentTaskStatus.WAITING);
            try {
                run(task.getId(), task.getUserId());
            } catch (RuntimeException ignored) {
                // Another instance may already own the distributed lock.
            }
        }
    }

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

    public Page<AgentTask> list(Long userId, Pageable pageable) {
        return tasks.findByUserId(userId, pageable);
    }

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

    public AgentTask create(
            Long userId,
            String title,
            String objective,
            Map<String, Object> inputParameters,
            String requestIdempotencyKey) {
        String normalizedKey = taskInput.normalizeIdempotencyKey(requestIdempotencyKey);
        if (normalizedKey != null) {
            Optional<AgentTask> existing =
                    tasks.findByUserIdAndRequestIdempotencyKey(userId, normalizedKey);
            if (existing.isPresent()) return existing.get();
        }
        Map<String, Object> parameters =
                new LinkedHashMap<>(inputParameters == null ? Map.of() : inputParameters);
        String city = taskInput.resolveCity(parameters, objective);
        if (city.isBlank()) throw ApiException.badRequest("请填写行动地点或城市，例如：南宁");
        parameters.put("city", city);
        taskInput.normalizeStoredBudget(parameters);
        parameters.put("questions", taskInput.asStringList(parameters.get("questions")));
        parameters.putIfAbsent("revisions", new ArrayList<String>());

        AgentTask task = new AgentTask();
        task.setUserId(userId);
        task.setTitle(title == null || title.isBlank() ? city + "行动计划" : title.trim());
        task.setObjective(objective.trim());
        task.setMaxSteps(Math.min(maxSteps, FLOW.size()));
        task.setMaxRetries(maxTaskRetries);
        task.setRequestIdempotencyKey(normalizedKey);
        task.setParametersJson(taskInput.writeParameters(parameters));
        try {
            tasks.saveAndFlush(task);
        } catch (DataIntegrityViolationException conflict) {
            if (normalizedKey != null) {
                return tasks.findByUserIdAndRequestIdempotencyKey(userId, normalizedKey)
                        .orElseThrow(() -> conflict);
            }
            throw conflict;
        }

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

    public SseEmitter run(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        if (!Set.of(AgentTaskStatus.WAITING, AgentTaskStatus.FAILED).contains(task.getStatus())) {
            throw ApiException.badRequest("当前状态不能启动");
        }
        DistributedTaskLockService.LockHandle lock = locks.tryAcquire(id, Duration.ofMinutes(5));
        if (lock == null) throw ApiException.conflict("TASK_ALREADY_RUNNING", "任务正在运行");
        task.setCancelRequested(false);
        SseEmitter emitter = new SseEmitter(180_000L);
        Future<?> future = executor.submit(() -> executeUntilConfirmation(task, emitter, lock));
        activeFutures.put(id, future);
        emitter.onTimeout(() -> cancel(id, userId));
        return emitter;
    }

    private void executeUntilConfirmation(
            AgentTask task, SseEmitter emitter, DistributedTaskLockService.LockHandle lock) {
        try {
            Map<String, Object> parameters = taskInput.readParameters(task);
            String city = taskInput.resolveCity(parameters, task.getObjective());
            String budget = taskInput.parameterText(parameters.get("budget"), "未限定");
            List<String> questions = taskInput.asStringList(parameters.get("questions"));
            List<String> revisions = taskInput.asStringList(parameters.get("revisions"));
            String revision = revisions.isEmpty() ? "无" : String.join("；", revisions);
            String requirements = taskInput.combinedRequirements(task, parameters);

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
                    "已理解行程需求",
                    "城市：" + city + "；预算：" + budget + "；待回答问题：" + questions.size() + " 个。",
                    "ReAct",
                    null,
                    questions.size(),
                    null,
                    null,
                    Map.of(
                            "city", city,
                            "budget", budget,
                            "questionCount", questions.size(),
                            "questions", questions));

            AgentJourneyResearchService.JourneyResearch journey =
                    journeyResearch.researchJourney(
                            task, 2, city, requirements, "journey-place-route-search");
            String places = journey.formatted();
            taskSteps.complete(task, 2, places, emitter);
            AgentJourneyResearchService.PublicResearch supplement =
                    journeyResearch.supplementPublicInfo(task, city, places);
            places = supplement.places();
            taskSteps.complete(task, 3, supplement.verification(), emitter);

            String preview =
                    taskInput.buildPreview(task, city, budget, places, questions, revisions);
            task.setPlanPreview(preview);
            saveTask(task);
            taskSteps.complete(
                    task, 4, "已生成可确认的候选计划，预算上限：" + taskInput.budgetLabel(budget) + "。", emitter);

            AgentTaskStep confirmation = steps.findByTaskIdAndStepNo(task.getId(), 5).orElseThrow();
            confirmation.setStatus(AgentTaskStepStatus.WAITING_CONFIRMATION);
            confirmation.setDetail("请查看下方当前计划。没问题可直接继续；有问题时填写修改原因，任务会回到第一步重新规划。");
            confirmation.setStartedAt(Instant.now());
            steps.save(confirmation);
            task.setCurrentStep(5);
            stateMachine.transition(task, AgentTaskStatus.AWAITING_CONFIRMATION);
            event(emitter, "confirmation", Map.of("step", confirmation, "planPreview", preview));
            emitter.complete();
        } catch (Exception e) {
            fail(task, e, emitter);
        } finally {
            activeFutures.remove(task.getId());
            lock.close();
        }
    }

    public SseEmitter confirm(
            Long id,
            Long userId,
            boolean approved,
            String note,
            String city,
            BigDecimal budget,
            List<String> questions) {
        AgentTask task = owned(id, userId);
        if (task.getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION) {
            throw ApiException.badRequest("任务当前不等待确认");
        }
        DistributedTaskLockService.LockHandle lock = locks.tryAcquire(id, Duration.ofMinutes(5));
        if (lock == null) throw ApiException.conflict("TASK_ALREADY_RUNNING", "任务正在运行");

        SseEmitter emitter = new SseEmitter(180_000L);
        if (approved) {
            Future<?> future = executor.submit(() -> finish(task, note, questions, emitter, lock));
            activeFutures.put(id, future);
        } else {
            Map<String, Object> current = taskInput.readParameters(task);
            boolean editorSubmission = city != null || questions != null;
            String currentCity = taskInput.resolveCity(current, task.getObjective());
            String requestedCity = city == null ? currentCity : city.trim().replace("市", "");
            if (editorSubmission && requestedCity.isBlank()) {
                lock.close();
                throw ApiException.badRequest("地点 / 城市不能为空");
            }
            if (budget != null && budget.signum() < 0) {
                lock.close();
                throw ApiException.badRequest("预算不能小于 0");
            }
            String currentBudget = taskInput.parameterText(current.get("budget"), "");
            String requestedBudget =
                    budget == null ? "" : taskInput.normalizeBudget(budget).toPlainString();
            boolean cityChanged = !requestedCity.equals(currentCity);
            boolean budgetChanged = editorSubmission && !requestedBudget.equals(currentBudget);
            boolean questionsChanged =
                    questions != null
                            && !taskInput
                                    .asStringList(questions)
                                    .equals(taskInput.asStringList(current.get("questions")));
            if ((note == null || note.isBlank())
                    && !cityChanged
                    && !budgetChanged
                    && !questionsChanged) {
                lock.close();
                throw ApiException.badRequest("请至少修改地点、预算、问题或补充说明中的一项");
            }
            reviseAndRestart(
                    task, note == null ? "" : note.trim(), city, budget, questions, emitter, lock);
        }
        return emitter;
    }

    private void reviseAndRestart(
            AgentTask task,
            String note,
            String city,
            BigDecimal budget,
            List<String> incomingQuestions,
            SseEmitter emitter,
            DistributedTaskLockService.LockHandle lock) {
        Map<String, Object> parameters = taskInput.readParameters(task);
        List<String> revisions = taskInput.asStringList(parameters.get("revisions"));
        if (!note.isBlank()) revisions.add(note);
        parameters.put("revisions", revisions);
        String revisedCity = city == null ? "" : city.trim().replace("市", "");
        if (revisedCity.isBlank()) revisedCity = taskInput.findKnownCity(note);
        if (!revisedCity.isBlank()) parameters.put("city", revisedCity);
        boolean editorSubmission = city != null || incomingQuestions != null;
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
            AgentJourneyResearchService.JourneyResearch journey =
                    journeyResearch.researchJourney(
                            task, 6, city, allRequirements, "confirmation-live-journey-search");
            String liveSearch = journey.formatted();
            task.setPlanPreview(
                    taskInput.buildPreview(task, city, budget, liveSearch, questions, revisions));
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

    public GeneratedFile generatePdf(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        return pdfService.generate(task);
    }

    public GeneratedFile getPdf(Long id, Long userId) {
        owned(id, userId);
        return pdfService.get(userId, id);
    }

    public AgentTask cancel(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        if (task.getStatus().isTerminal()) return task;
        task.setCancelRequested(true);
        Future<?> future = activeFutures.remove(id);
        if (future != null) future.cancel(true);
        return stateMachine.transition(task, AgentTaskStatus.CANCELLED);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        AgentTask task = owned(id, userId);
        if (task.getStatus() == AgentTaskStatus.RUNNING)
            throw ApiException.badRequest("任务执行中，请先取消后再删除");
        Future<?> future = activeFutures.remove(id);
        if (future != null) future.cancel(true);
        for (GeneratedFile file :
                files.findByUserIdAndBusinessTypeAndBusinessId(userId, "AGENT_TASK", id)) {
            try {
                storage.delete(file.getStorageKey());
            } catch (Exception ignored) {
            }
            files.delete(file);
        }
        calls.deleteByTaskId(id);
        executionTrace.deleteByTaskId(id);
        steps.deleteByTaskId(id);
        tasks.delete(task);
    }

    private void fail(AgentTask task, Exception error, SseEmitter emitter) {
        AgentTask latest = tasks.findByIdAndUserId(task.getId(), task.getUserId()).orElse(task);
        latest.setErrorMessage(
                shorten(Optional.ofNullable(error.getMessage()).orElse("任务失败"), 480));
        if (error instanceof CancellationException || error instanceof InterruptedException) {
            if (latest.getStatus() != AgentTaskStatus.CANCELLED) {
                stateMachine.transition(latest, AgentTaskStatus.CANCELLED);
            }
        } else if (latest.getRetryCount() < latest.getMaxRetries()) {
            latest.setRetryCount(latest.getRetryCount() + 1);
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

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private AgentTask owned(Long id, Long userId) {
        return tasks.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("任务不存在"));
    }

    private AgentTask saveTask(AgentTask task) {
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private void event(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
        }
    }

    public record TaskDetail(
            AgentTask task,
            List<AgentTaskStep> steps,
            List<ToolCallRecord> toolCalls,
            List<AgentExecutionEvent> executionEvents,
            GeneratedFile pdfFile) {}
}
