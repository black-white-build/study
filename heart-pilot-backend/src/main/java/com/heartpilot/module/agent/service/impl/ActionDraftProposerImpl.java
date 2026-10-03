package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.service.ActionDraftProposer;
import com.heartpilot.module.agent.service.ActionEnricher.ActionDraft;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 行动草案提议器实现。
 * 把用户目标与约束转换为"计划目标类型 + 多类型行动草案列表"。
 * 模型可用时按结构化输出生成（执行方式、标题、指令）；不可用时按规则降级，
 * 保证任何输入都能得到至少一条可执行的草案（默认一次坦诚沟通）。
 *
 * 规则降级策略（关键词命中，不依赖模型）：
 * - 地点/见面/约会 → PLACE_VISIT（需城市）
 * - 消息/微信/短信 → MESSAGE
 * - 聊/谈/沟通/说开/道歉 → CONVERSATION
 * - 礼物/送 → GIFT_RITUAL
 * - 练习/复盘/自己/情绪/冷静 → SELF_PRACTICE
 * - 观察/留意 → OBSERVATION
 * - 均未命中 → CONVERSATION（最通用、最安全的一步）
 */
@Service
public class ActionDraftProposerImpl implements ActionDraftProposer {
    /** 每条草案的指令文本上限，防止模型输出超长污染预览 */
    private static final int MAX_INSTRUCTION_LENGTH = 500;
    /** 单份计划最多生成的草案条数 */
    private static final int MAX_DRAFTS = 8;

    private static final String SYSTEM_PROMPT =
            """
            你是关系行动规划师。根据用户的计划目标与约束，识别本次计划的行动目标类型，
            并提议一组小步、可执行、尊重边界的行动草案。
            必须遵守：
            1. 行动目标类型只能从以下选择：CONNECTION(加深连接) REPAIR(修复关系) BOUNDARY(设定边界)
               CELEBRATION(庆祝肯定) DECISION(做出决定) SELF_GROWTH(自我成长)，只输出枚举名。
            2. 每条草案的执行方式只能从以下选择：PLACE_VISIT(地点行动) MESSAGE(消息行动)
               CONVERSATION(沟通行动) GIFT_RITUAL(表达行动) SELF_PRACTICE(自我练习) OBSERVATION(观察行动)。
            3. 不提供跟踪、监控、纠缠、操控、威胁或诊断他人的内容；观察/练习只针对自己。
            4. 草案 3-6 条为宜，指令用中文，具体、可执行、一句话说清。
            """;

    private final ChatClient client;
    private final boolean enabled;

    public ActionDraftProposerImpl(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }

    @Override
    public ActionProposal propose(PlanningContext context) {
        // 用户显式指定了目标类型/行动类型：走确定性的提示路径，不依赖模型
        GoalType goalHint = parseGoal(String.valueOf(context.parameters().get("goalType")));
        List<ExecutionKind> kindHints = parseKinds(context.parameters().get("preferredActionKinds"));
        if (goalHint != null || !kindHints.isEmpty()) {
            return proposeFromHints(goalHint, kindHints, context);
        }
        if (!enabled) return fallbackPropose(context);
        try {
            String contextNotes =
                    String.valueOf(context.parameters().getOrDefault("contextNotes", "")).trim();
            ProposalModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    地点范围：%s
                                    预算：%s
                                    需要逐项回答的问题：
                                    %s
                                    %s

                                    请返回 goalType（行动目标枚举名）和 drafts（行动草案列表）。
                                    """
                                            .formatted(
                                                    context.task().getObjective(),
                                                    blank(context.city()),
                                                    blank(context.budget()),
                                                    context.questions().isEmpty()
                                                            ? "无"
                                                            : String.join("\n", context.questions()),
                                                    contextNotes.isBlank()
                                                            ? ""
                                                            : "背景补充（用户填写，须尊重，不得包含操控/监控意图）：\n"
                                                                    + contextNotes))
                            .call()
                            .entity(ProposalModel.class);
            return normalizeModel(model, context);
        } catch (Exception ignored) {
            return fallbackPropose(context);
        }
    }

    /**
     * 用户指定提示路径：目标类型直接采用（未指定则从目标文本猜测），
     * 行动类型按提示逐条生成默认草案；提示为空时回退规则降级。
     */
    private ActionProposal proposeFromHints(
            GoalType goalHint, List<ExecutionKind> kindHints, PlanningContext context) {
        String text = context.task().getObjective();
        GoalType goalType = goalHint != null ? goalHint : guessGoal(text);
        List<ActionDraft> drafts = new ArrayList<>();
        for (ExecutionKind kind : kindHints) {
            if (drafts.size() >= MAX_DRAFTS) break;
            ActionDraft template = draftTemplate(kind, goalType, context);
            if (template != null) drafts.add(template);
        }
        if (drafts.isEmpty()) return fallbackPropose(context);
        return new ActionProposal(goalType, drafts, false);
    }

    /** 每种执行方式的默认草案模板（提示路径与规则降级共用） */
    private ActionDraft draftTemplate(ExecutionKind kind, GoalType goalType, PlanningContext context) {
        boolean hasCity = context.city() != null && !context.city().isBlank();
        return switch (kind) {
            case PLACE_VISIT -> hasCity
                    ? new ActionDraft(
                            kind,
                            goalType,
                            "安排一次适合谈心的见面",
                            "选一个安静、不被打扰的地点见面，提前确认对方时间与偏好。",
                            Map.of("placeCount", 3))
                    : null;
            case MESSAGE -> new ActionDraft(
                    kind,
                    goalType,
                    "发送一条真诚的消息",
                    "用“事实+感受+请求”写一条短消息，先表达在乎，再提出一个具体的小请求。",
                    Map.of());
            case CONVERSATION -> new ActionDraft(
                    kind,
                    goalType,
                    "安排一次坦诚的沟通",
                    "约一个双方都放松的时间，用开场白表达感受，给对方留出回应空间，约定不打断。",
                    Map.of());
            case GIFT_RITUAL -> new ActionDraft(
                    kind,
                    goalType,
                    "准备一份用心的小表达",
                    "回想对方最近提过的细节，准备一份贴合偏好的小礼物或仪式，附一张手写卡片。",
                    Map.of());
            case SELF_PRACTICE -> new ActionDraft(
                    kind,
                    goalType,
                    "做一次情绪复盘练习",
                    "用“事实—感受—需求”三段式写下这次经历，梳理自己想表达的核心内容。",
                    Map.of());
            case OBSERVATION -> new ActionDraft(
                    kind,
                    goalType,
                    "记录自己的反应模式",
                    "接下来几次互动中，只观察并记录自己的情绪与身体信号，不推断对方。",
                    Map.of());
        };
    }

    /** 解析 preferredActionKinds：支持列表/单个字符串/逗号分隔，忽略非法值 */
    private List<ExecutionKind> parseKinds(Object raw) {
        if (raw == null) return List.of();
        List<String> values;
        if (raw instanceof List<?> list) {
            values = list.stream().map(String::valueOf).toList();
        } else {
            values = List.of(String.valueOf(raw).split("[,\\s]+"));
        }
        List<ExecutionKind> result = new ArrayList<>();
        for (String value : values) {
            ExecutionKind kind = parseKind(value);
            if (kind != null && !result.contains(kind)) result.add(kind);
        }
        return result;
    }

    /** 解析并清洗模型输出：非法枚举/超长指令/空列表都回退规则降级 */
    private ActionProposal normalizeModel(ProposalModel model, PlanningContext context) {
        if (model == null || model.drafts() == null || model.drafts().isEmpty()) {
            return fallbackPropose(context);
        }
        GoalType goalType = parseGoal(model.goalType());
        List<ActionDraft> drafts = new ArrayList<>();
        for (DraftModel raw : model.drafts()) {
            if (drafts.size() >= MAX_DRAFTS) break;
            ExecutionKind kind = parseKind(raw.kind());
            if (kind == null) continue;
            String instruction = shorten(raw.instruction(), MAX_INSTRUCTION_LENGTH);
            if (instruction.isBlank()) instruction = context.task().getObjective();
            drafts.add(new ActionDraft(
                    kind,
                    goalType,
                    shorten(raw.title(), 120),
                    instruction,
                    Map.of()));
        }
        if (drafts.isEmpty()) return fallbackPropose(context);
        return new ActionProposal(goalType, drafts, true);
    }

    /** 规则降级：按关键词识别目标类型与行动类型，保证任何输入都有草案 */
    private ActionProposal fallbackPropose(PlanningContext context) {
        String text = (context.task().getObjective()
                        + " "
                        + String.join(" ", context.questions()))
                .toLowerCase(Locale.ROOT);
        GoalType goalType = guessGoal(text);
        List<ActionDraft> drafts = new ArrayList<>();
        boolean hasCity = context.city() != null && !context.city().isBlank();

        if (hasCity && matchesAny(text, "见", "约会", "约", "见面", "出去", "逛", "散步", "吃", "地点", "餐厅", "咖啡馆")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.PLACE_VISIT, goalType, context));
        }
        if (matchesAny(text, "消息", "微信", "短信", "发信息", "发个", "打招呼")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.MESSAGE, goalType, context));
        }
        if (matchesAny(text, "聊", "谈", "沟通", "说开", "道歉", "解释", "吵架", "矛盾")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.CONVERSATION, goalType, context));
        }
        if (matchesAny(text, "礼物", "送", "纪念日", "生日", "表白")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.GIFT_RITUAL, goalType, context));
        }
        if (matchesAny(text, "练习", "复盘", "自己", "情绪", "冷静", "焦虑", "成长")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.SELF_PRACTICE, goalType, context));
        }
        if (matchesAny(text, "观察", "留意", "记录")) {
            addIfNotNull(drafts, draftTemplate(ExecutionKind.OBSERVATION, goalType, context));
        }
        if (drafts.isEmpty()) {
            if (hasCity) {
                // 提供了城市但未命中具体类型：默认给一次地点见面 + 一次坦诚沟通，保持旧版"地点优先"行为
                addIfNotNull(drafts, draftTemplate(ExecutionKind.PLACE_VISIT, goalType, context));
                addIfNotNull(drafts, draftTemplate(ExecutionKind.CONVERSATION, goalType, context));
            } else {
                addIfNotNull(drafts, draftTemplate(ExecutionKind.CONVERSATION, goalType, context));
            }
        }
        return new ActionProposal(goalType, drafts, false);
    }

    private void addIfNotNull(List<ActionDraft> drafts, ActionDraft draft) {
        if (draft != null) drafts.add(draft);
    }

    /** 从目标文本猜测计划目标类型 */
    private GoalType guessGoal(String text) {
        if (matchesAny(text, "边界", "拒绝", "停止", "别", "尊重我")) return GoalType.BOUNDARY;
        if (matchesAny(text, "道歉", "和好", "吵架", "修复", "矛盾", "冷战")) return GoalType.REPAIR;
        if (matchesAny(text, "表白", "庆祝", "生日", "纪念", "感谢")) return GoalType.CELEBRATION;
        if (matchesAny(text, "决定", "选择", "要不要", "是否", "分手")) return GoalType.DECISION;
        if (matchesAny(text, "成长", "自己", "情绪", "焦虑", "练习")) return GoalType.SELF_GROWTH;
        return GoalType.CONNECTION;
    }

    private GoalType parseGoal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return GoalType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ExecutionKind parseKind(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ExecutionKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean matchesAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "未限定" : value;
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length())).trim();
    }

    /** 模型结构化输出记录 */
    public record ProposalModel(String goalType, List<DraftModel> drafts) {}

    public record DraftModel(String kind, String title, String instruction) {}
}
