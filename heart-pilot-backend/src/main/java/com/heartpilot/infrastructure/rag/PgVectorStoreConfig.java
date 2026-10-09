package com.heartpilot.infrastructure.rag;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 生产环境（prod）向量库配置：使用 PostgreSQL + pgvector 持久化向量。 与 RelationshipVectorStoreConfig（非 prod 用内存版
 * SimpleVectorStore）互为 Profile 互补， 保证生产环境的向量检索在重启后数据不丢失。
 */
@Configuration
@Profile("prod")
public class PgVectorStoreConfig {

    /**
     * 注册关系领域的向量存储 Bean。 配置项来源：环境变量 AI_EMBEDDING_DIMENSIONS（默认 1024，需与 Embedding 模型输出维度一致）。
     * 使用余弦距离（COSINE_DISTANCE）+ HNSW 近似最近邻索引，initializeSchema=true 自动建表。
     *
     * @param jdbc JdbcTemplate，用于连接 pgvector 扩展的 PostgreSQL
     * @param embedding Embedding 模型，负责把文本转向量
     * @param dimensions 向量维度
     */
    @Bean("relationshipVectorStore")
    VectorStore relationshipVectorStore(
            JdbcTemplate jdbc,
            EmbeddingModel embedding,
            @Value("${AI_EMBEDDING_DIMENSIONS:1024}") int dimensions) {
        return PgVectorStore.builder(jdbc, embedding)
                .dimensions(dimensions)
                // 余弦距离：语义相似度检索常用度量
                .distanceType(COSINE_DISTANCE)
                // HNSW 索引：适合高维向量的近似检索，查询性能优于暴力扫描
                .indexType(HNSW)
                // 启动时自动创建向量表与索引，无需手工执行 SQL
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .build();
    }
}
