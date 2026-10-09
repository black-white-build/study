package com.heartpilot.infrastructure.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.*;
import org.springframework.context.annotation.*;

/**
 * 非生产环境（!prod，本地开发/测试）向量库配置。 使用内存版 SimpleVectorStore，数据随进程重启丢失，免去本地搭建 PostgreSQL+pgvector 的成本；
 * 生产环境由 PgVectorStoreConfig 接管同一 Bean 名 relationshipVectorStore。
 */
@Configuration
@Profile("!prod")
public class RelationshipVectorStoreConfig {

    /**
     * 注册内存版向量存储 Bean。
     *
     * @param dashscopeEmbeddingModel 通义千问 Embedding 模型，用于把文本向量化
     */
    @Bean
    VectorStore relationshipVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        return SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
    }
}
