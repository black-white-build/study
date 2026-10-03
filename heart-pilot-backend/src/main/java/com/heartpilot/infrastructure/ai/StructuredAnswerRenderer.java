package com.heartpilot.infrastructure.ai;

import com.heartpilot.infrastructure.ai.ConversationClassifier.Route;
import org.springframework.stereotype.Component;

/**
 * 输出轻量规整器：不再强制把模型输出套进固定章节模板，而是原样保留模型生成的自然回答，
 * 只做必要的文本清理（去首尾空白、压缩连续空行），避免把内部评估过程暴露给用户。
 * 引用编号的校验与降级仍由 {@link CitationValidator} 单独完成。
 */
@Component
public class StructuredAnswerRenderer {

    /**
     * 规整模型输出：去除首尾空白并压缩连续空行。参数保留是为了兼容调用方，
     * 路由与来源信息不再参与输出结构决策。
     *
     * @param raw        模型原始输出
     * @param route      当前会话路由（仅作占位保留，不影响输出）
     * @param hasSources 是否存在检索来源（仅作占位保留，不影响输出）
     * @return 清理后的自然文本
     */
    public String normalize(String raw, Route route, boolean hasSources) {
        String content = raw == null ? "" : raw.strip();
        // 压缩连续空行（3 个及以上换行折叠为 2 个），保留段落边界但不留大段空白
        return content.replaceAll("\\n{3,}", "\n\n").strip();
    }
}
