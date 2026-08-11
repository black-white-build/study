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

@Service
public class AgentToolExecutor {
    private final ToolCallRepository calls;
    private final ExecutorService executor;
    private final int timeoutSeconds;
    private final int maxAttempts;
    private final MeterRegistry metrics;
    private final ObjectMapper json;

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

    private <T> T executeInternal(
            AgentTask task,
            int stepNo,
            String toolName,
            String arguments,
            Callable<T> action,
            ResultWriter<T> writer,
            ResultReader<T> reader)
            throws Exception {
        String key = task.getId() + ":" + task.getVersionNo() + ":" + stepNo + ":" + toolName;
        Optional<ToolCallRecord> previous =
                calls.findByIdempotencyKey(key)
                        .filter(call -> call.getStatus() == ToolCallStatus.SUCCEEDED);
        if (previous.isPresent()) {
            metrics.counter("heartpilot.agent.tool.idempotency_hits", "tool", toolName).increment();
            return reader.read(previous.get().getResultSummary());
        }

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
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<T> future = executor.submit(action);
            try {
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
                future.cancel(true);
                last =
                        new TimeoutException(
                                "工具 " + toolName + " 在 " + timeoutSeconds + " 秒后超时并已取消");
                record.setStatus(ToolCallStatus.TIMED_OUT);
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                record.setStatus(ToolCallStatus.CANCELLED);
                throw interrupted;
            } catch (ExecutionException execution) {
                future.cancel(true);
                Throwable cause = execution.getCause();
                last = cause instanceof Exception exception ? exception : execution;
                record.setStatus(ToolCallStatus.FAILED);
            }
            record.setErrorMessage(shorten(last.getMessage(), 480));
            record.setDurationMs(elapsedMillis(startedAt));
            calls.save(record);
        }
        metrics.counter(
                        "heartpilot.agent.tool.failures",
                        "tool",
                        toolName,
                        "reason",
                        record.getStatus().name())
                .increment();
        throw last == null ? new IllegalStateException("工具调用失败") : last;
    }

    @FunctionalInterface
    private interface ResultWriter<T> {
        String write(T value) throws Exception;
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(String value) throws Exception;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }

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
