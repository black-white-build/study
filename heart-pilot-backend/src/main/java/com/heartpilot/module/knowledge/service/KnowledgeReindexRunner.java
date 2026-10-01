package com.heartpilot.module.knowledge.service;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 一次性重建知识库索引的入口，仅在 knowledge-reindex profile 下启用。 */
@Component
@Profile("knowledge-reindex")
public class KnowledgeReindexRunner implements ApplicationRunner {
    private final KnowledgeService knowledge;
    private final ConfigurableApplicationContext context;
    /** 知识库 Markdown 源文件目录，启动时规范化为绝对路径。 */
    private final Path sourceDirectory;
    /** 重建索引时归属的系统用户 ID。 */
    private final long userId;

    public KnowledgeReindexRunner(
            KnowledgeService knowledge,
            ConfigurableApplicationContext context,
            @Value("${app.knowledge.source-directory:../knowledge}") String sourceDirectory,
            @Value("${app.knowledge.reindex-user-id:1}") long userId) {
        this.knowledge = knowledge;
        this.context = context;
        this.sourceDirectory = Path.of(sourceDirectory).toAbsolutePath().normalize();
        this.userId = userId;
    }

    /** 启动后执行一次全量重建，打印结果并关闭 Spring 容器后退出。 */
    @Override
    public void run(ApplicationArguments args) {
        KnowledgeService.RebuildResult result =
                knowledge.rebuildRepository(sourceDirectory, userId);
        System.out.printf(
                "Knowledge index rebuilt: version=%s documents=%d chunks=%d%n",
                result.indexVersion(), result.documentCount(), result.chunkCount());
        context.close();
    }
}
