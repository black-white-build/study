package com.heartpilot.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heartpilot.infrastructure.ai.ConversationClassifier.Route;
import org.junit.jupiter.api.Test;

/** 会话分类器：将用户输入路由到决策建议、知识问答、澄清追问、情感支持四条通道。 */
class ConversationClassifierTest {
    private final ConversationClassifier classifier = new ConversationClassifier();

    /** 覆盖四类典型输入，断言分别被路由到 DECISION / KNOWLEDGE_QA / CLARIFY / SUPPORT。 */
    @Test
    void routesDecisionKnowledgeClarificationAndSupport() {
        assertEquals(Route.DECISION, classifier.classify("伴侣连续三天不回复，我要直接问还是先等等？").route());
        assertEquals(Route.KNOWLEDGE_QA, classifier.classify("什么是尊重边界的请求？").route());
        assertEquals(Route.CLARIFY, classifier.classify("怎么办").route());
        assertEquals(Route.SUPPORT, classifier.classify("我好难过，只想倾诉一下").route());
    }
}
