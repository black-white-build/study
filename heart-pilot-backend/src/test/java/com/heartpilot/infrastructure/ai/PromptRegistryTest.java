package com.heartpilot.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.heartpilot.infrastructure.ai.PromptRegistry.PromptName;
import org.junit.jupiter.api.Test;

/** 提示词注册表：校验四个独立的版本化提示词均能被正确加载且非空。 */
class PromptRegistryTest {
    /** 四个提示词全部加载、版本号统一为 1.0.0，且正文均不为空。 */
    @Test
    void loadsFourIndependentVersionedPrompts() {
        PromptRegistry registry = new PromptRegistry();
        assertEquals(4, registry.versions().size());
        for (PromptName name : PromptName.values()) {
            assertEquals("1.0.0", registry.get(name).version());
            assertFalse(registry.get(name).body().isBlank());
        }
    }
}
