package com.heartpilot.module.agent.service.impl;

import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import com.heartpilot.module.agent.service.ActionLanguageService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 行动文案生成服务实现。
 * 每个方法都是"模型优先 + 规则降级"：模型可用时走结构化输出，
 * 不可用或调用失败时返回内置的通用安全文案（基于目标文本），
 * 确保多类型草案生成不因 AI 故障中断。
 *
 * 安全约束（与 P11 一致）：
 * - 沟通脚本/观察任务只关注"可观察的行为与自己的感受"，不诊断对方心理状态
 * - 消息草稿禁用威胁、指责、翻旧账等表达
 */
@Service
public class ActionLanguageServiceImpl implements ActionLanguageService {
    /** 通用系统提示：所有文案都遵守的边界 */
    private static final String SYSTEM_PROMPT =
            """
            你是关系沟通领域的小步行动文案助手。只输出给定结构的内容，不解释。
            必须遵守：
            1. 只关注可观察的行为和自己的感受，不诊断、不推断对方的人格或心理疾病。
            2. 不提供操控、纠缠、跟踪、监控、威胁或贬低性表达。
            3. 表达采用“事实 + 感受 + 具体请求”的结构，温和、具体、可执行。
            4. 中文输出，口语自然，不要说教。
            """;

    private final ChatClient client;
    private final boolean enabled;
    private final CapabilityStatusRecorder statusRecorder;

    public ActionLanguageServiceImpl(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            CapabilityStatusRecorder statusRecorder) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        this.statusRecorder = statusRecorder;
        if (!enabled) {
            statusRecorder.recordAiChat(CapabilityStatusRecorder.Status.NO_KEY, "未配置 DASHSCOPE_API_KEY");
        }
    }

    /** AI 调用异常是否属于额度/限流类（额度不足、QPS 超限、欠费） */
    private boolean isQuotaError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("429")
                || msg.contains("quota")
                || msg.contains("insufficient")
                || msg.contains("billing")
                || msg.contains("throttl")
                || msg.contains("rate limit");
    }

    /** 记录 AI 调用结果：成功标记 OK，失败按是否额度问题分类 */
    private void recordAiSuccess() {
        statusRecorder.recordAiChat(CapabilityStatusRecorder.Status.OK, "");
    }

    private void recordAiFailure(Exception e) {
        if (isQuotaError(e)) {
            statusRecorder.recordAiChat(
                    CapabilityStatusRecorder.Status.QUOTA_EXHAUSTED,
                    "对话 key 额度不足或已欠费，请检查通义账户余额");
        } else {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            statusRecorder.recordAiChat(
                    CapabilityStatusRecorder.Status.ERROR,
                    detail.length() > 120 ? detail.substring(0, 120) : detail);
        }
    }

    @Override
    public MessageDraft draftMessage(String goal, String background) {
        if (!enabled) return fallbackMessage(goal, background);
        try {
            MessageModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    背景：%s

                                    请生成一条可以发给对方的消息草稿，并给出语气、建议发送时机、禁用表达。
                                    """
                                            .formatted(goal, background))
                            .call()
                            .entity(MessageModel.class);
            if (model == null || model.text() == null || model.text().isBlank())
                return fallbackMessage(goal, background);
            recordAiSuccess();
            return new MessageDraft(
                    model.text().trim(),
                    blankTo(model.tone(), "平静、真诚"),
                    blankTo(model.sendTiming(), "关系自然的时候，避免在对方忙碌或情绪激动时发送"),
                    model.forbiddenExpressions() == null
                            ? List.of("指责", "翻旧账", "威胁")
                            : model.forbiddenExpressions());
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackMessage(goal, background);
        }
    }

    @Override
    public ConversationScript draftConversation(String goal, String background) {
        if (!enabled) return fallbackConversation(goal, background);
        try {
            ConversationModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    背景：%s

                                    请生成一次沟通的完整脚本：沟通目标、开场白、关键表达（3-5 条）、
                                    具体请求、退出条件（如果对方不愿继续怎么办）。
                                    """
                                            .formatted(goal, background))
                            .call()
                            .entity(ConversationModel.class);
            if (model == null || model.opening() == null || model.opening().isBlank())
                return fallbackConversation(goal, background);
            recordAiSuccess();
            return new ConversationScript(
                    blankTo(model.goal(), goal),
                    model.opening().trim(),
                    model.keyExpressions() == null ? List.of() : model.keyExpressions(),
                    blankTo(model.concreteRequest(), "问问对方此刻愿意怎么聊这件事"),
                    blankTo(
                            model.exitCondition(),
                            "如果对方现在不想聊，尊重并约定一个更合适的时间"));
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackConversation(goal, background);
        }
    }

    @Override
    public GiftPlan draftGift(String goal, String background, String budget) {
        if (!enabled) return fallbackGift(goal, background, budget);
        try {
            GiftModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    背景（含送礼场合、预算、对方喜好或禁忌）：%s
                                    预算：%s

                                    你先像懂行的朋友一样分析这次送礼：
                                    - 送礼对象是谁、什么场合（生日/节日/道歉/感谢/纪念）；
                                    - 对方的兴趣爱好、生活方式与明确禁忌；
                                    - 预算区间能买到什么档次的东西。
                                    然后给出准备事项、预算安排、执行步骤（3-5 步），
                                    以及 5-8 个具体的礼物候选（ideas）：
                                    - title 要具体到商品品类或方向，不要空泛（例如"明前龙井礼盒"、
                                      "客制化机械键盘"、"手写手账套装"，而不是"一份心意"）；
                                    - 对方喜欢游戏就推荐游戏外设/耳机/键帽，喜欢喝茶就推荐茶具/好茶，
                                      严格围绕背景里透露的喜好推断，不要自说自话；
                                    - reason 一句话说清为什么适合这次送礼；
                                    - priceHint 给出大致价位（如"200-400元"）。
                                    强调心意与用心，不鼓励价格攀比。
                                    """
                                            .formatted(goal, background, budget))
                            .call()
                            .entity(GiftModel.class);
            if (model == null || model.preparation() == null || model.preparation().isBlank())
                return fallbackGift(goal, background, budget);
            recordAiSuccess();
            List<GiftIdea> ideas = new ArrayList<>();
            if (model.ideas() != null) {
                for (GiftIdeaModel raw : model.ideas()) {
                    if (raw == null || raw.title() == null || raw.title().isBlank()) continue;
                    ideas.add(new GiftIdea(
                            raw.title().trim(),
                            raw.reason() == null ? "" : raw.reason().trim(),
                            raw.priceHint() == null ? "" : raw.priceHint().trim()));
                    if (ideas.size() >= 8) break;
                }
            }
            if (ideas.isEmpty()) ideas = fallbackIdeas(background);
            return new GiftPlan(
                    model.preparation().trim(),
                    blankTo(model.budgetText(), budget),
                    model.steps() == null ? List.of() : model.steps(),
                    ideas);
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackGift(goal, background, budget);
        }
    }

    @Override
    public PracticePlan draftPractice(String goal, String background) {
        if (!enabled) return fallbackPractice(goal, background);
        try {
            PracticeModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    背景：%s

                                    请生成一份面向自己的练习计划：练习内容、建议时长（分钟）、完成标准。
                                    练习只针对自己的情绪与表达，不要求改变对方。
                                    """
                                            .formatted(goal, background))
                            .call()
                            .entity(PracticeModel.class);
            if (model == null || model.practiceContent() == null || model.practiceContent().isBlank())
                return fallbackPractice(goal, background);
            recordAiSuccess();
            Integer minutes = model.durationMinutes();
            return new PracticePlan(
                    model.practiceContent().trim(),
                    minutes == null || minutes <= 0 ? 15 : minutes,
                    blankTo(
                            model.completionCriteria(),
                            "按结构写完一次，并读出来感受是否自然"));
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackPractice(goal, background);
        }
    }

    @Override
    public ObservationPlan draftObservation(String goal, String background) {
        if (!enabled) return fallbackObservation(goal, background);
        try {
            ObservationModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(
                                    """
                                    计划目标：%s
                                    背景：%s

                                    请生成一份观察任务：观察内容（只观察自己的反应、感受与情境事实）、
                                    记录字段、禁止推断事项（禁止猜测对方意图、禁止把观察当作诊断）。
                                    """
                                            .formatted(goal, background))
                            .call()
                            .entity(ObservationModel.class);
            if (model == null || model.observeContent() == null || model.observeContent().isBlank())
                return fallbackObservation(goal, background);
            recordAiSuccess();
            return new ObservationPlan(
                    model.observeContent().trim(),
                    model.recordFields() == null ? List.of() : model.recordFields(),
                    blankTo(
                            model.forbiddenInferences(),
                            "不推断对方的动机或人格，不把观察结果当作对方有问题的证据"));
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackObservation(goal, background);
        }
    }

    // ---------------- 规则降级文案 ----------------

    private MessageDraft fallbackMessage(String goal, String background) {
        String text =
                "我想和你说说"
                        + (background.isBlank() ? "这件事" : "最近发生的这件事")
                        + "。"
                        + "我现在的感受是："
                        + (goal.isBlank() ? "有点在意，也有些不确定" : goal)
                        + "。如果你愿意，我想听听你的想法。";
        return new MessageDraft(text, "平静、真诚", "关系自然的时候，避免在对方忙碌或情绪激动时发送",
                List.of("指责", "翻旧账", "威胁", "比较"));
    }

    private ConversationScript fallbackConversation(String goal, String background) {
        return new ConversationScript(
                goal.isBlank() ? "坦诚地聊一次" : goal,
                "最近有一件事我想和你聊聊，不是要分对错，只是想说说我的感受，也听听你的。",
                List.of("当……发生时，我感到……", "我理解你可能不是故意的", "我想知道你是怎么想的"),
                "接下来 20 分钟，我们只聊这一件事，可以吗？",
                "如果你现在不想聊，我们可以约一个更合适的时间，我尊重你。");
    }

    private GiftPlan fallbackGift(String goal, String background, String budget) {
        return new GiftPlan(
                "先回想对方最近提到过但没买的小东西，或一起经历过的某个细节，作为礼物灵感来源",
                budget.isBlank() ? "量力而行，心意优先" : budget,
                List.of("写下 3 个候选想法，选最贴合对方偏好的 1 个",
                        "准备一张手写卡片，写一句具体的话（为什么选它）",
                        "在自然时机送出，不强调价格"),
                fallbackIdeas(background));
    }

    /** 规则降级的礼物候选：从用户背景里抽喜好词，给通用但不空泛的方向 */
    private List<GiftIdea> fallbackIdeas(String background) {
        List<GiftIdea> ideas = new ArrayList<>();
        String text = background == null ? "" : background;
        if (text.contains("茶"))
            ideas.add(new GiftIdea("对方常喝的茶或一套小品茶具", "围绕喝茶的日常爱好，实用且有仪式感", ""));
        if (text.contains("游戏") || text.contains("外设") || text.contains("电脑"))
            ideas.add(new GiftIdea("游戏外设（键盘/鼠标/耳机）", "贴合游戏爱好，使用频率高", ""));
        if (text.contains("香") || text.contains("香水"))
            ideas.add(new GiftIdea("香薰蜡烛或家居香氛", "提升日常幸福感，不贴身不易踩雷", ""));
        if (ideas.isEmpty())
            ideas.add(new GiftIdea("手写卡片 + 对方提过的小物", "记录一起经历的细节，心意具体", ""));
        return ideas;
    }

    private PracticePlan fallbackPractice(String goal, String background) {
        return new PracticePlan(
                "用“事实—感受—需求”三段式写下这次想表达的内容：\n"
                        + "1. 事实：发生了什么（只写可观察的行为）\n"
                        + "2. 感受：我当时和现在的感受\n"
                        + "3. 需求：我希望对方了解什么 / 希望我们怎么做",
                15,
                "按结构写完一次，并读出来感受是否自然；不确定的地方标记出来，沟通时只说确定的部分");
    }

    private ObservationPlan fallbackObservation(String goal, String background) {
        return new ObservationPlan(
                "在接下来几次互动中，只观察自己的反应：什么时候情绪波动、身体有什么信号、想说什么但没说出口",
                List.of("触发事件（可观察的事实）", "我的情绪（命名它）", "身体感受", "我真实想表达的需求"),
                "不推断对方的动机或人格，不把观察结果当作对方有问题的证据，不做情绪分析之外的诊断");
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ---------------- 模型结构化输出记录 ----------------

    public record MessageModel(
            String text, String tone, String sendTiming, List<String> forbiddenExpressions) {}

    public record ConversationModel(
            String goal,
            String opening,
            List<String> keyExpressions,
            String concreteRequest,
            String exitCondition) {}

    public record GiftModel(
            String preparation, String budgetText, List<String> steps, List<GiftIdeaModel> ideas) {}

    public record GiftIdeaModel(String title, String reason, String priceHint) {}

    public record PracticeModel(String practiceContent, Integer durationMinutes, String completionCriteria) {}

    public record ObservationModel(
            String observeContent, List<String> recordFields, String forbiddenInferences) {}
}
