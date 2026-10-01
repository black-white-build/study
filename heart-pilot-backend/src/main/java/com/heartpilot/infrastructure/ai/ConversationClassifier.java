package com.heartpilot.infrastructure.ai;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 基于关键词的确定性首轮路由，结果可测、不依赖大模型；同时记录所用提示词版本供后续模型路由参考。 */
@Component
public class ConversationClassifier {
    /** 对用户输入做确定性的首轮分类，判定走哪条处理路由及是否需要知识库/追问澄清。 */
    public Classification classify(String input) {
        String text = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        // 非关系沟通类话题（写代码、天气、股票等），直接判定超出范围
        if (containsAny(text, "写代码", "天气", "股票", "数学题", "翻译")) {
            return result(Route.OUT_OF_SCOPE, "非关系沟通", false, false, 0.96, "OUT_OF_SCOPE_TOPIC");
        }
        // 明显的情绪倾诉诉求，走情绪支持路由
        if (containsAny(text, "我好难过", "我很难过", "我很崩溃", "只想倾诉", "陪我聊聊")) {
            return result(Route.SUPPORT, "情绪支持", false, false, 0.9, "EMOTIONAL_SUPPORT");
        }
        // 询问概念/原则/依据，走知识问答路由并需要检索知识库
        if (containsAny(text, "什么是", "为什么", "有什么原则", "有研究", "依据是什么")) {
            return result(Route.KNOWLEDGE_QA, topic(text), true, false, 0.86, "KNOWLEDGE_QUESTION");
        }
        // 输入过短或只抛"怎么办"却缺关键背景，需要先追问澄清
        if (text.length() < 8
                || (text.length() < 20 && text.matches(".*(怎么办|怎么做|我该怎么办)[？?。!！]*$"))) {
            return result(
                    Route.CLARIFY, topic(text), false, true, 0.78, "MISSING_DECISION_CONTEXT");
        }
        // 其余默认按需要比较具体选择的决策类请求处理
        return result(Route.DECISION, topic(text), true, false, 0.82, "DECISION_REQUEST");
    }

    /** 组装一条分类结果，把原因码包成单元素列表。 */
    private Classification result(
            Route route,
            String topic,
            boolean needsKnowledge,
            boolean needsClarification,
            double confidence,
            String reason) {
        return new Classification(
                route, topic, needsKnowledge, needsClarification, confidence, List.of(reason));
    }

    /** 根据关键词粗判话题归属，命中不到时默认归入"沟通基础"。 */
    private String topic(String text) {
        if (containsAny(text, "分手", "复合", "结束关系")) return "分手与结束关系";
        if (containsAny(text, "边界", "拒绝", "同意", "隐私")) return "边界与同意";
        if (containsAny(text, "吵架", "冲突", "道歉", "冷战")) return "冲突与修复";
        if (containsAny(text, "消息", "微信", "已读", "回复")) return "数字沟通";
        return "沟通基础";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    /** 会话处理路由：安全/禁止作答/先澄清/决策建议/知识问答/情绪支持/超出范围。 */
    public enum Route {
        /** 涉及自伤、暴力等安全风险，走安全干预流程。 */
        SAFETY,
        /** 涉及不应提供帮助的内容，拒绝作答。 */
        DISALLOWED,
        /** 关键信息不足，需要先向用户追问澄清。 */
        CLARIFY,
        /** 需要比较具体做法、给出决策建议。 */
        DECISION,
        /** 用户在询问概念/原则，需要检索知识库作答。 */
        KNOWLEDGE_QA,
        /** 用户主要在倾诉情绪，优先给予陪伴与支持。 */
        SUPPORT,
        /** 与关系沟通无关的话题，超出本产品范围。 */
        OUT_OF_SCOPE
    }

    /** 一次分类的完整结果：路由、话题、是否需知识库、是否需追问、置信度与原因码。 */
    public record Classification(
            Route route,
            String topic,
            boolean needsKnowledge,
            boolean needsClarification,
            double confidence,
            List<String> reasonCodes) {}
}
