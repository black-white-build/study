package com.heartpilot.module.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.conversation.entity.ConversationContextState;
import com.heartpilot.module.conversation.repository.ConversationContextStateRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 会话上下文服务：校验事实/假设/偏好/情绪四类状态的分离存储，以及"更正"对旧事实的覆盖。 */
class ConversationContextServiceTest {
    /** 首轮写入混合语句后再发"更正"，断言关系状态只保留最新事实，其余三类状态各留存一条。 */
    @Test
    void separatesStateKindsAndCorrectionOverwritesFact() {
        ConversationContextStateRepository repository =
                mock(ConversationContextStateRepository.class);
        // 用 AtomicReference 充当内存库：save 时写入，find 时回读，模拟真实持久化的读写循环
        AtomicReference<ConversationContextState> stored = new AtomicReference<>();
        when(repository.findByConversationIdAndUserId(1L, 2L))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(ConversationContextState.class)))
                .thenAnswer(
                        invocation -> {
                            ConversationContextState state = invocation.getArgument(0);
                            stored.set(state);
                            return state;
                        });
        ConversationContextService service =
                new ConversationContextService(repository, new ObjectMapper());

        service.update(1L, 2L, 10L, "我们正在交往。我觉得他可能不在乎我。我希望先冷静。我很难过。");
        ConversationContextService.Snapshot corrected = service.update(1L, 2L, 11L, "更正，我们已经分手。");

        assertEquals(
                1,
                corrected.facts().stream()
                        .filter(item -> "relationship_status".equals(item.get("key")))
                        .count());
        assertTrue(
                corrected.facts().stream()
                        .anyMatch(item -> String.valueOf(item.get("value")).contains("已经分手")));
        assertEquals(1, corrected.assumptions().size());
        assertEquals(1, corrected.preferences().size());
        assertEquals(1, corrected.emotions().size());
    }
}
