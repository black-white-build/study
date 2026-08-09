package com.heartpilot.rag;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("prod")
public class PgVectorStoreConfig {
    @Bean("relationshipVectorStore")
    VectorStore relationshipVectorStore(
            JdbcTemplate jdbc,
            EmbeddingModel embedding,
            @Value("${AI_EMBEDDING_DIMENSIONS:1024}") int dimensions) {
        return PgVectorStore.builder(jdbc, embedding)
                .dimensions(dimensions)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .build();
    }
}
