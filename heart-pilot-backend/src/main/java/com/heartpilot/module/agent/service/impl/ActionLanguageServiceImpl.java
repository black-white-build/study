package com.heartpilot.module.agent.service.impl;

import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.WebSearchService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 行动文案生成服务实现。 每个方法都是"模型优先 + 规则降级"：模型可用时走结构化输出， 不可用或调用失败时返回内置的通用安全文案（基于目标文本）， 确保多类型草案生成不因 AI 故障中断。
 *
 * <p>安全约束（与 P11 一致）： - 沟通脚本/观察任务只关注"可观察的行为与自己的感受"，不诊断对方心理状态 - 消息草稿禁用威胁、指责、翻旧账等表达
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
            statusRecorder.recordAiChat(
                    CapabilityStatusRecorder.Status.NO_KEY, "未配置 DASHSCOPE_API_KEY");
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
                    CapabilityStatusRecorder.Status.QUOTA_EXHAUSTED, "对话 key 额度不足或已欠费，请检查通义账户余额");
        } else {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            statusRecorder.recordAiChat(
                    CapabilityStatusRecorder.Status.ERROR,
                    detail.length() > 120 ? detail.substring(0, 120) : detail);
        }
    }

    @Override
    public MessageDraft draftMessage(String goal, String background, MessageOptions options) {
        if (!enabled) return fallbackMessage(goal, background);
        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("计划目标：").append(goal).append("\n");
            userPrompt.append("背景：").append(background).append("\n");
            if (options != null) {
                if (options.channel() != null && !options.channel().isBlank())
                    userPrompt.append("发送渠道：").append(options.channel()).append("\n");
                if (options.toneStyle() != null && !options.toneStyle().isBlank())
                    userPrompt.append("语气风格：").append(options.toneStyle()).append("\n");
                if (options.replyExpectation() != null && !options.replyExpectation().isBlank())
                    userPrompt.append("对方回复期待：").append(options.replyExpectation()).append("\n");
                if (options.notes() != null && !options.notes().isBlank())
                    userPrompt.append("补充背景：").append(options.notes()).append("\n");
            }
            userPrompt.append("\n请生成一条可以发给对方的消息草稿，并给出语气、建议发送时机、禁用表达。");
            MessageModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(userPrompt.toString())
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
                    blankTo(model.exitCondition(), "如果对方现在不想聊，尊重并约定一个更合适的时间"));
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackConversation(goal, background);
        }
    }

    @Override
    public GiftPlan draftGift(
            String goal,
            String background,
            String budget,
            List<GiftSearchEvidence> searchEvidence) {
        if (!enabled) return fallbackGift(goal, background, budget);
        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("计划目标：").append(goal).append("\n");
            userPrompt.append("背景（含送礼场合、预算、对方喜好或禁忌）：").append(background).append("\n");
            userPrompt.append("预算：").append(budget).append("\n");
            if (searchEvidence != null && !searchEvidence.isEmpty()) {
                userPrompt.append("\n以下是从公开网页搜索到的参考（用于校准价格与真实品牌名）：\n");
                for (GiftSearchEvidence ev : searchEvidence) {
                    if (ev == null || ev.keyword() == null) continue;
                    userPrompt.append("关键词：").append(ev.keyword()).append("\n");
                    if (ev.results() != null) {
                        for (WebSearchService.SearchResult r : ev.results()) {
                            if (r == null) continue;
                            userPrompt
                                    .append("- ")
                                    .append(blankTo(r.title(), ""))
                                    .append(": ")
                                    .append(blankTo(r.snippet(), ""))
                                    .append("\n");
                        }
                    }
                }
            }
            userPrompt.append(
                    """

                    你先像懂行的朋友一样分析这次送礼：
                    - 送礼对象是谁、什么场合；
                    - 对方的兴趣爱好、生活方式与明确禁忌；
                    - 预算区间能买到什么档次的东西。
                    然后给出准备事项、预算安排、执行步骤（3-5 步），
                    以及 5-8 个具体的礼物候选（ideas）：
                    - title 要具体到商品品类或方向，不要空泛；
                    - reason 一句话说清为什么适合这次送礼；
                    - priceHint 给出大致价位。
                    """);
            GiftModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(userPrompt.toString())
                            .call()
                            .entity(GiftModel.class);
            if (model == null || model.preparation() == null || model.preparation().isBlank())
                return fallbackGift(goal, background, budget);
            recordAiSuccess();
            List<GiftIdea> ideas = new ArrayList<>();
            if (model.ideas() != null) {
                for (GiftIdeaModel raw : model.ideas()) {
                    if (raw == null || raw.title() == null || raw.title().isBlank()) continue;
                    ideas.add(
                            new GiftIdea(
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
    public PracticePlan draftPractice(
            String goal, String background, String practiceNotes, int variant) {
        if (!enabled) return fallbackPractice(goal, background, practiceNotes, variant);
        try {
            PracticeModel model =
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(practicePrompt(goal, background, practiceNotes, variant))
                            .call()
                            .entity(PracticeModel.class);
            if (model == null
                    || model.practiceContent() == null
                    || model.practiceContent().isBlank())
                return fallbackPractice(goal, background, practiceNotes, variant);
            recordAiSuccess();
            Integer minutes = model.durationMinutes();
            return new PracticePlan(
                    blankTo(model.name(), "候选方案 " + variant),
                    model.practiceContent().trim(),
                    minutes == null || minutes <= 0 ? 15 : minutes,
                    blankTo(model.completionCriteria(), "按计划完成一次练习，并记录当天的完成情况与感受"));
        } catch (Exception e) {
            recordAiFailure(e);
            return fallbackPractice(goal, background, practiceNotes, variant);
        }
    }

    private String practicePrompt(
            String goal, String background, String practiceNotes, int variant) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("计划目标：").append(blankTo(goal, "")).append("\n");
        prompt.append("用户目标：").append(blankTo(background, "")).append("\n");
        if (practiceNotes != null && !practiceNotes.isBlank()) {
            prompt.append("\n===== 用户填写的硬性要求（必须逐条分析并严格遵守）=====\n")
                    .append(practiceNotes)
                    .append("\n");
            prompt.append(
                    """

                    你必须先做以下分析，再输出方案：
                    1. "计划内容"这一行写的具体是什么活动？按字面意思判断，不要泛化。
                    2. "期望效果"这一行想要什么结果？练习的强度、时长、频率都要服务于这个效果。
                    3. "频率"这一行要求多久练一次？方案中的天数安排必须匹配这个频率。
                    4. 如果存在"历史修改要求"，说明用户上一轮否决了旧方案，必须按新要求重新思考。
                    禁止输出与上述关键词无关的通用模板（如情绪复盘、呼吸觉察等）。
                    """);
        }
        String[] directions = {
            "方案一请走循序渐进打基础思路：从最容易坚持的小动作起步，设计可量化的最低门槛与递增节奏。",
            "方案二请走效果导向加反馈思路：围绕期望效果设计可直接衡量的练习，并加入每周自我反馈。",
            "方案三请走场景融入生活思路：把练习嵌进日常固定场景（起床后/通勤/午休/睡前）。",
            "方案四请走组合多任务思路：把多个相关小练习组合成一组循环，覆盖不同维度。"
        };
        String direction = directions[(variant - 1) % directions.length];
        prompt.append("\n本轮要求：").append(direction).append("\n");
        prompt.append(
                """

                基于以上分析，生成一份围绕计划内容、服务于期望效果、匹配频率的可执行练习计划，
                只输出 JSON：
                { "name": "方案名（必须带第N方案标记并引用用户关键词）",
                  "practiceContent": "练习内容（具体到动作/步骤/时长安排，中文，紧扣关键词）",
                  "durationMinutes": 建议单次时长分钟数,
                  "completionCriteria": "完成标准（可检查、可量化，与期望效果直接挂钩）" }
                约束：只输出 JSON；练习只针对自己的行动与状态，不要求改变他人、不诊断他人。
                """);
        return prompt.toString();
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
                    blankTo(model.forbiddenInferences(), "不推断对方的动机或人格，不把观察结果当作对方有问题的证据"));
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
        return new MessageDraft(
                text, "平静、真诚", "关系自然的时候，避免在对方忙碌或情绪激动时发送", List.of("指责", "翻旧账", "威胁", "比较"));
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
                List.of("写下 3 个候选想法，选最贴合对方偏好的 1 个", "准备一张手写卡片，写一句具体的话（为什么选它）", "在自然时机送出，不强调价格"),
                fallbackIdeas(background));
    }

    /** 规则降级的礼物候选：从用户背景里抽喜好词，给通用但不空泛的方向 */
    private List<GiftIdea> fallbackIdeas(String background) {
        List<GiftIdea> ideas = new ArrayList<>();
        String text = background == null ? "" : background;
        if (text.contains("茶")) ideas.add(new GiftIdea("对方常喝的茶或一套小品茶具", "围绕喝茶的日常爱好，实用且有仪式感", ""));
        if (text.contains("游戏") || text.contains("外设") || text.contains("电脑"))
            ideas.add(new GiftIdea("游戏外设（键盘/鼠标/耳机）", "贴合游戏爱好，使用频率高", ""));
        if (text.contains("香") || text.contains("香水"))
            ideas.add(new GiftIdea("香薰蜡烛或家居香氛", "提升日常幸福感，不贴身不易踩雷", ""));
        if (ideas.isEmpty()) ideas.add(new GiftIdea("手写卡片 + 对方提过的小物", "记录一起经历的细节，心意具体", ""));
        return ideas;
    }

    private PracticePlan fallbackPractice(
            String goal, String background, String practiceNotes, int variant) {
        String text =
                (blankTo(goal, "")
                                + " "
                                + blankTo(background, "")
                                + " "
                                + blankTo(practiceNotes, ""))
                        .toLowerCase(java.util.Locale.ROOT);
        int index = Math.max(0, variant - 1) % 3;
        String keyword =
                firstMatch(
                        text,
                        List.of(
                                "运动", "健身", "跑步", "锻炼", "减肥", "减脂", "瑜伽", "有氧", "跳绳", "骑行", "游泳",
                                "拉伸"));
        if (keyword != null) return sportVariant(keyword, index);
        keyword = firstMatch(text, List.of("沟通", "表达", "说话", "开口", "聊天", "紧张"));
        if (keyword != null) return talkVariant(keyword, index);
        keyword = firstMatch(text, List.of("情绪", "焦虑", "急躁", "冷静", "压力", "内耗", "静坐", "觉察"));
        if (keyword != null) return moodVariant(keyword, index);
        keyword = firstMatch(text, List.of("学习", "阅读", "英语", "考试", "专注", "自律", "早起"));
        if (keyword != null) return studyVariant(keyword, index);
        return new PracticePlan(
                "方案" + variant + "：围绕" + blankTo(background, "自我成长") + "的练习",
                "每天花 10-15 分钟，围绕" + blankTo(background, "目标") + "做一次专注练习，结束后记录感受。",
                15,
                "连续 7 天完成练习并记录一次感受");
    }

    private String firstMatch(String text, List<String> terms) {
        for (String t : terms) if (text.contains(t)) return t;
        return null;
    }

    private PracticePlan sportVariant(String keyword, int index) {
        return switch (index) {
            case 0 ->
                    new PracticePlan(
                            "方案一：循序渐进的" + keyword + "习惯计划",
                            "每天固定时间做 15-20 分钟低强度" + keyword + "，前 3 天只求完成不求强度，之后每 3 天增加 5 分钟。",
                            20,
                            "连续 7 天每天完成最低时长" + keyword);
            case 1 ->
                    new PracticePlan(
                            "方案二：围绕" + keyword + "效果的打卡计划",
                            "每周 3 次中高强度" + keyword + "，每周固定时间记录体重或围度变化。",
                            40,
                            "4 周内完成 12 次" + keyword + "打卡");
            default ->
                    new PracticePlan(
                            "方案三：场景化" + keyword + "融入计划",
                            "把" + keyword + "嵌入日常场景：通勤快走、午休爬楼、晚饭后散步。",
                            30,
                            "一周内完成 5 次场景化" + keyword);
        };
    }

    private PracticePlan talkVariant(String keyword, int index) {
        return switch (index) {
            case 0 ->
                    new PracticePlan(
                            "方案一：循序渐进的" + keyword + "练习",
                            "每天挑一个低压力场景主动" + keyword + "一次，只要求开口。",
                            15,
                            "连续 7 天主动" + keyword);
            case 1 ->
                    new PracticePlan(
                            "方案二：围绕" + keyword + "的反馈练习",
                            "每周 3 次主动" + keyword + "，结束后记录对方反应和自己的感受。",
                            20,
                            "完成 12 次" + keyword + "并记录");
            default ->
                    new PracticePlan(
                            "方案三：场景化" + keyword,
                            "在日常对话中刻意练习" + keyword + "，用手机提醒自己。",
                            15,
                            "一周内 5 次有意识地" + keyword);
        };
    }

    private PracticePlan moodVariant(String keyword, int index) {
        return switch (index) {
            case 0 ->
                    new PracticePlan(
                            "方案一：每日" + keyword + "练习",
                            "每天睡前花 10 分钟静坐，觉察呼吸和身体感受。",
                            15,
                            "连续 7 天完成静坐");
            case 1 ->
                    new PracticePlan(
                            "方案二：" + keyword + "日记",
                            "每天记录一次情绪波动：触发事件、身体感受、我想说的话。",
                            15,
                            "连续 7 天写情绪日记");
            default ->
                    new PracticePlan(
                            "方案三：场景化" + keyword, "情绪上来时先停 3 秒，深呼吸，再决定怎么回应。", 10, "一周内 5 次在情绪来临时停顿");
        };
    }

    private PracticePlan studyVariant(String keyword, int index) {
        return switch (index) {
            case 0 ->
                    new PracticePlan(
                            "方案一：每日" + keyword,
                            "每天固定时间专注" + keyword + "20 分钟，关闭通知。",
                            25,
                            "连续 7 天完成" + keyword);
            case 1 ->
                    new PracticePlan(
                            "方案二：" + keyword + "打卡",
                            "每周 5 次" + keyword + "，每次 30 分钟，记录学习内容。",
                            35,
                            "4 周内完成 20 次" + keyword);
            default ->
                    new PracticePlan(
                            "方案三：场景化" + keyword,
                            "把" + keyword + "嵌入早晨或通勤时间，用闹钟提醒。",
                            20,
                            "一周内 5 次" + keyword);
        };
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

    public record PracticeModel(
            String name,
            String practiceContent,
            Integer durationMinutes,
            String completionCriteria) {}

    public record ObservationModel(
            String observeContent, List<String> recordFields, String forbiddenInferences) {}
}
