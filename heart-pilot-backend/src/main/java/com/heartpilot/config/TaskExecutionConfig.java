package com.heartpilot.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 任务执行线程池配置类。 为 Agent 任务异步执行提供独立的线程池 Bean，与 Web 请求线程池隔离， 避免长耗时 AI 任务拖垮 Tomcat 工作线程。 */
@Configuration
public class TaskExecutionConfig {

    /**
     * Agent 任务执行器 Bean。 使用 Java 虚拟线程（newVirtualThreadPerTaskExecutor）：每个任务占用一个轻量级虚拟线程， 适合大量 IO
     * 密集型等待（调用大模型、外部 API）的并发场景。 destroyMethod = close 确保应用关闭时优雅终止线程池。
     */
    @Bean(name = "agentTaskExecutor", destroyMethod = "close")
    ExecutorService agentTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 分布式锁续租调度器 Bean。 单线程、daemon 的调度执行器：Agent 任务持锁执行期间，每隔固定间隔续租一次， 防止任务执行超过锁租期（5
     * 分钟）后锁自动过期、其他实例重复执行同一任务。 destroyMethod = shutdownNow 确保应用关闭时停止续租调度。
     */
    @Bean(name = "lockRenewScheduler", destroyMethod = "shutdownNow")
    ScheduledExecutorService lockRenewScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "task-lock-renew");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
