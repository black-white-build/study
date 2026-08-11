package com.heartpilot.infrastructure.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.*;
import org.springframework.context.annotation.*;

@Configuration
@Profile("!prod")
public class RelationshipVectorStoreConfig {
    @Bean
    VectorStore relationshipVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        return SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
    }
}
