package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 消息型行动富化器。
 * 调用文案生成服务产出消息草稿（文本/语气/发送时机/禁用表达），
 * 全程不调用地点或地图工具，满足"消息型计划不调用地点工具"的验收要求。
 *
 * 消息生成必须融入用户在表单里输入的目标与约束（task.objective）、发送渠道、语气风格、
 * 对方回复期待（contextNotes 或结构化参数），生成的消息服务于计划目标并朝回复期待引导。
 */
@Service
public class MessageActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public MessageActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.MESSAGE;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        ActionLanguageService.MessageOptions options = messageOptions(context);
        ActionLanguageService.MessageDraft message =
                language.draftMessage(goalText(draft, context), context.task().getObjective(), options);
        StringBuilder instruction = new StringBuilder("发送这条消息：\n").append(message.text());
        instruction.append("\n\n语气：").append(message.tone());
        if (options.channel() != null && !options.channel().isBlank()) {
            instruction.append("\n发送渠道：").append(options.channel());
        }
        if (options.replyExpectation() != null && !options.replyExpectation().isBlank()) {
            instruction.append("\n回复期待：").append(options.replyExpectation());
        }
        instruction.append("\n建议时机：").append(message.sendTiming());
        instruction.append("\n注意：保持平和真诚，避免指责、翻旧账等易激化冲突的表达。");
        item.setInstruction(instruction.toString());
        item.setTimingSuggestion(message.sendTiming());
        item.setEstimatedDurationMinutes(10);
        item.setPayloadJson(
                payload(Map.of(
                        "draft",
                        message.text(),
                        "tone",
                        message.tone(),
                        "sendTiming",
                        message.sendTiming(),
                        "channel",
                        options.channel() == null ? "" : options.channel(),
                        "toneStyle",
                        options.toneStyle() == null ? "" : options.toneStyle(),
                        "replyExpectation",
                        options.replyExpectation() == null ? "" : options.replyExpectation(),
                        "forbiddenExpressions",
                        message.forbiddenExpressions())));
        return new EnrichedAction(item, List.of());
    }

    /**
     * 收集消息生成选项：优先取结构化参数（messageChannel/toneStyle/replyExpectation，
     * 前端新版可单独透传），为空时回退解析 contextNotes 中的
     * "发送渠道：/语气风格：/回复期待："行（创建页与编辑页都按该格式拼装）。
     * 补充背景剔除这三行后原样透传，避免与指定参数重复。
     */
    private ActionLanguageService.MessageOptions messageOptions(PlanningContext context) {
        Map<String, Object> parameters = context.parameters();
        String notes = String.valueOf(parameters.getOrDefault("contextNotes", "")).trim();
        if (notes.isEmpty() || "null".equals(notes)) notes = "";
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : notes.split("\n")) {
            int idx = line.indexOf('：');
            if (idx > 0) fields.putIfAbsent(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        String channel = pick(parameters.get("messageChannel"), fields.get("发送渠道"));
        String tone = pick(parameters.get("toneStyle"), fields.get("语气风格"));
        String reply = pick(parameters.get("replyExpectation"), fields.get("回复期待"));
        return new ActionLanguageService.MessageOptions(channel, tone, reply, cleanNotes(notes));
    }

    /** 结构化参数优先，未填时用 contextNotes 解析值；都为空返回 null */
    private String pick(Object structured, String fromNotes) {
        if (structured != null && !String.valueOf(structured).isBlank()) {
            return String.valueOf(structured).trim();
        }
        return fromNotes == null || fromNotes.isBlank() ? null : fromNotes;
    }

    /** 剔除渠道/语气/回复期待行，保留其余背景细节；全被剔除时返回 null */
    private String cleanNotes(String notes) {
        if (notes == null || notes.isBlank()) return null;
        List<String> kept = new ArrayList<>();
        for (String line : notes.split("\n")) {
            int idx = line.indexOf('：');
            String key = idx > 0 ? line.substring(0, idx).trim() : "";
            if ("发送渠道".equals(key) || "语气风格".equals(key) || "回复期待".equals(key)) continue;
            if (!line.trim().isBlank()) kept.add(line.trim());
        }
        return kept.isEmpty() ? null : String.join("\n", kept);
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
