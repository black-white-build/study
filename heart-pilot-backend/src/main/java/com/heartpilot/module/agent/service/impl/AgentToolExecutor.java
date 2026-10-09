package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.ToolCallRecord;
import com.heartpilot.module.agent.entity.enums.ToolCallStatus;
import com.heartpilot.module.agent.repository.ToolCallRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent 工具调用执行器。 是所有外部工具调用（地点检索、ReAct/MCP 等）的统一入口，负责： 审计落库（ToolCallRecord）、超时控制、失败重试、幂等复用与指标统计。
 *
 * <p>可靠性设计要点： - 幂等：以 taskId:versionNo:stepNo:toolName 作为幂等键，若该组合已有 SUCCEEDED 记录，
 * 直接反序列化上次结果返回，重试/恢复时不重复打外部接口 - 超时：每次调用都在独立线程池中执行，future.get 阻塞等待 timeoutSeconds， 超时后
 * future.cancel(true) 中断底层线程 - 重试：失败最多尝试 maxAttempts 次，期间持续更新审计记录状态（TIMED_OUT/FAILED/CANCELLED） -
 * 异常分类：超时→TIMED_OUT 重试；线程中断→CANCELLED 立即向上抛并复位中断标志； 业务异常→FAILED 重试；全部失败后记失败指标并抛出最后一次异常
 */
@Service
public class AgentToolExecutor {
    /** 工具调用审计 Repository */
    private final ToolCallRepository calls;

    /** Agent 任务专用线程池，工具动作在其中执行以便超时中断 */
    private final ExecutorService executor;

    /** 单次工具调用超时秒数，配置项 app.agent.tool-timeout-seconds，默认 30 */
    private final int timeoutSeconds;

    /** 失败最大尝试次数，配置项 app.agent.tool-max-attempts，默认 2 */
    private final int maxAttempts;

    /** 指标注册中心，统计幂等命中、耗时与失败 */
    private final MeterRegistry metrics;

    /** JSON 序列化，用于 executeJson 的结果落库与回读 */
    private final ObjectMapper json;

    /** 构造器注入。executor 通过 @Qualifier 指定 Agent 任务专用线程池。 */
    public AgentToolExecutor(
            ToolCallRepository calls,
            @Qualifier("agentTaskExecutor") ExecutorService executor,
            @Value("${app.agent.tool-timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.agent.tool-max-attempts:2}") int maxAttempts,
            MeterRegistry metrics,
            ObjectMapper json) {
        this.calls = calls;
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
        this.json = json;
    }

    /** 执行一个返回纯文本的工具调用。结果落库前截断到 8000 字符。 */
    public String execute(
            AgentTask task, int stepNo, String toolName, String arguments, Callable<String> action)
            throws Exception {
        return executeInternal(
                task,
                stepNo,
                toolName,
                arguments,
                action,
                value -> shorten(value, 8_000),
                value -> value);
    }

    /** 执行一个返回结构化 JSON 的工具调用。 成功结果序列化为 JSON 字符串落库；幂等复用时再反序列化回 resultType。 */
    public <T> T executeJson(
            AgentTask task,
            int stepNo,
            String toolName,
            String arguments,
            Class<T> resultType,
            Callable<T> action)
            throws Exception {
        return executeInternal(
                task,
                stepNo,
                toolName,
                arguments,
                action,
                json::writeValueAsString,
                value -> json.readValue(value, resultType));
    }

    /**
     * 工具调用核心流程：幂等检查 → 落 RUNNING 审计记录 → 循环重试（超时控制）→ 更新状态与指标。
     *
     * @param writer 把结果序列化为落库字符串
     * @param reader 从落库字符串还原结果（用于幂等复用）
     */
    private <T> T executeInternal(
            AgentTask task,
            int stepNo,
            String toolName,
            String arguments,
            Callable<T> action,
            ResultWriter<T> writer,
            ResultReader<T> reader)
            throws Exception {
        // 幂等键：任务 + 版本 + 步骤 + 工具，定位同一次逻辑调用
        String key = task.getId() + ":" + task.getVersionNo() + ":" + stepNo + ":" + toolName;
        Optional<ToolCallRecord> previous =
                calls.findByIdempotencyKey(key)
                        .filter(call -> call.getStatus() == ToolCallStatus.SUCCEEDED);
        if (previous.isPresent()) {
            // 命中上次成功结果：直接复用，不再打外部接口
            metrics.counter("heartpilot.agent.tool.idempotency_hits", "tool", toolName).increment();
            return reader.read(previous.get().getResultSummary());
        }

        // 复用或新建审计记录，先落一条 RUNNING，便于排查"卡在哪一步"
        ToolCallRecord record = previous.orElseGet(ToolCallRecord::new);
        record.setTaskId(task.getId());
        record.setToolName(toolName);
        record.setArgumentsJson("{\"query\":" + quote(arguments) + "}");
        record.setStatus(ToolCallStatus.RUNNING);
        record.setIdempotencyKey(key);
        record.setErrorMessage(null);
        calls.saveAndFlush(record);

        long startedAt = System.nanoTime();
        Exception last = null;
        // 失败重试循环，最多 maxAttempts 次
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<T> future = executor.submit(action);
            try {
                // 阻塞等待结果，超时即 TimeoutException
                T result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                record.setStatus(ToolCallStatus.SUCCEEDED);
                record.setResultSummary(writer.write(result));
                record.setDurationMs(elapsedMillis(startedAt));
                calls.save(record);
                Timer.builder("heartpilot.agent.tool.duration")
                        .tag("tool", toolName)
                        .tag("outcome", "success")
                        .register(metrics)
                        .record(record.getDurationMs(), TimeUnit.MILLISECONDS);
                return result;
            } catch (TimeoutException timeout) {
                // 超时：中断底层线程，标记 TIMED_OUT 后进入下一次重试
                future.cancel(true);
                last =
                        new TimeoutException(
                                "工具 " + toolName + " 在 " + timeoutSeconds + " 秒后超时并已取消");
                record.setStatus(ToolCallStatus.TIMED_OUT);
            } catch (InterruptedException interrupted) {
                // 线程被中断（通常是任务取消）：恢复中断标志，标记 CANCELLED 后立即向上抛，不重试
                future.cancel(true);
                Thread.currentThread().interrupt();
                record.setStatus(ToolCallStatus.CANCELLED);
                throw interrupted;
            } catch (ExecutionException execution) {
                // 工具内部抛异常：解包出真实异常，标记 FAILED 后重试
                future.cancel(true);
                Throwable cause = execution.getCause();
                last = cause instanceof Exception exception ? exception : execution;
                record.setStatus(ToolCallStatus.FAILED);
            }
            // 无论失败原因，都更新审计记录的错误信息与耗时
            record.setErrorMessage(shorten(last.getMessage(), 480));
            record.setDurationMs(elapsedMillis(startedAt));
            calls.save(record);
        }
        // 重试用尽：记失败指标并抛出最后一次异常，让外层任务进入重试/失败流程
        metrics.counter(
                        "heartpilot.agent.tool.failures",
                        "tool",
                        toolName,
                        "reason",
                        record.getStatus().name())
                .increment();
        throw last == null ? new IllegalStateException("工具调用失败") : last;
    }

    /** 函数式接口：把工具结果序列化为落库字符串 */
    @FunctionalInterface
    private interface ResultWriter<T> {
        String write(T value) throws Exception;
    }

    /** 函数式接口：从落库字符串还原工具结果（幂等复用时） */
    @FunctionalInterface
    private interface ResultReader<T> {
        T read(String value) throws Exception;
    }

    /** 纳秒时间戳差值转毫秒 */
    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** 截断字符串到指定长度，null 转空串 */
    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }

    /** 把参数字符串手动转义为 JSON 字符串字面量（带双引号）。 用于拼接 argumentsJson，转义反斜杠、引号与换行。 */
    private String quote(String value) {
        if (value == null) return "null";
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                + "\"";
    }
}
