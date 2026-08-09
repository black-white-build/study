package com.heartpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.app.RelationshipAiClient;
import com.heartpilot.domain.*;
import com.heartpilot.repository.*;
import com.heartpilot.tools.WebSearchTool;
import com.heartpilot.web.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrowthService {
    private static final List<String> DAILY_QUESTIONS =
            List.of(
                    "今天哪一个瞬间让你感到被理解？",
                    "最近有什么小事值得向对方认真说一声谢谢？",
                    "如果本周只改善一件相处小事，你最想选什么？",
                    "发生分歧时，什么做法会让你更有安全感？",
                    "你希望对方如何支持你，而不是替你解决问题？",
                    "最近一次愉快互动中，双方分别做对了什么？",
                    "下周可以共同完成哪一件 20 分钟以内的小事？");
    private static final List<String> TOPIC_SEARCH_THEMES =
            List.of(
                    "亲密关系 沟通分歧 热门讨论",
                    "伴侣 支持感 情绪价值 热门讨论",
                    "长期关系 边界 信任 热门讨论",
                    "约会 相处 共同成长 热门讨论",
                    "婚恋 生活分工 冲突修复 热门讨论",
                    "安全感 倾听 表达需要 热门讨论");
    private static final List<String> TOPIC_QUESTION_FRAMES =
            List.of(
                    "看到“%s”，你最想听听对方的哪一种真实感受？",
                    "关于“%s”，你希望对方先理解你的哪一点？",
                    "“%s”里哪一个观点最像你们最近的相处状态？为什么？",
                    "如果把“%s”带进今天的两分钟对话，你最想问对方什么？");

    private final EventRepository events;
    private final PlanRepository plans;
    private final CheckinRepository checkins;
    private final ReportRepository reports;
    private final ProfileRepository profiles;
    private final MessageRepository messages;
    private final RelationshipAiClient ai;
    private final ObjectMapper json;
    private final WebSearchTool webSearch;
    private final AppUserRepository users;
    private final Map<Long, DailyTopic> dailyTopics =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Integer> topicRefreshCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    public GrowthService(
            EventRepository events,
            PlanRepository plans,
            CheckinRepository checkins,
            ReportRepository reports,
            ProfileRepository profiles,
            MessageRepository messages,
            RelationshipAiClient ai,
            ObjectMapper json,
            WebSearchTool webSearch,
            AppUserRepository users) {
        this.events = events;
        this.plans = plans;
        this.checkins = checkins;
        this.reports = reports;
        this.profiles = profiles;
        this.messages = messages;
        this.ai = ai;
        this.json = json;
        this.webSearch = webSearch;
        this.users = users;
    }

    public List<RelationshipEvent> events(Long userId) {
        return events.findByUserIdOrderByHappenedAtDesc(userId);
    }

    public Page<RelationshipEvent> events(Long userId, Pageable pageable) {
        return events.findByUserId(userId, pageable);
    }

    public RelationshipEvent addEvent(
            Long userId, String title, String description, String emotion, Instant happenedAt) {
        RelationshipEvent event = new RelationshipEvent();
        event.setUserId(userId);
        event.setTitle(title);
        event.setDescription(description);
        event.setEmotion(emotion);
        event.setHappenedAt(happenedAt == null ? Instant.now() : happenedAt);
        return events.save(event);
    }

    public RelationshipEvent pulse(
            Long userId, int closeness, int stress, String emotion, String answer) {
        if (closeness < 1 || closeness > 5 || stress < 1 || stress > 5) {
            throw ApiException.badRequest("亲密感和压力评分必须在 1 到 5 之间");
        }
        DailyTopic topic = dailyTopic(userId);
        String question = topic.question();
        String description =
                "亲密感 "
                        + closeness
                        + "/5；压力 "
                        + stress
                        + "/5。\n今日连接问题："
                        + question
                        + "\n我的回答："
                        + value(answer, "暂未填写")
                        + (topic.sources().isEmpty()
                                ? ""
                                : "\n话题来源：" + topic.sources().getFirst().url());
        return addEvent(userId, "每日关系脉搏", description, value(emotion, "平静"), Instant.now());
    }

    public List<PlanView> plans(Long userId) {
        return plans.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(
                        plan ->
                                new PlanView(
                                        plan,
                                        checkins.findByPlanIdAndUserIdOrderByCheckinDateAsc(
                                                plan.getId(), userId)))
                .toList();
    }

    public ActionPlan createPlan(
            Long userId, String title, String goal, LocalDate start, List<String> actions) {
        RelationshipProfile profile = profiles.findByUserId(userId).orElse(null);
        String resolvedGoal =
                value(goal, profile == null ? "建立一个可观察的关系改善习惯" : profile.getConcerns());
        ActionPlan plan = new ActionPlan();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setGoal(resolvedGoal);
        plan.setStartDate(start == null ? LocalDate.now() : start);
        plan.setEndDate(plan.getStartDate().plusDays(6));
        try {
            plan.setDailyActionsJson(
                    json.writeValueAsString(
                            actions == null || actions.isEmpty()
                                    ? defaults(resolvedGoal, profile)
                                    : actions));
        } catch (Exception e) {
            plan.setDailyActionsJson("[]");
        }
        return plans.save(plan);
    }

    public ActionPlan createFromReport(Long userId, Long reportId) {
        EmotionReport report =
                reports.findByIdAndUserId(reportId, userId)
                        .orElseThrow(() -> ApiException.notFound("报告不存在"));
        List<String> actions = readActions(report.getActionsJson());
        return createPlan(
                userId,
                "7 天改善 · " + report.getTitle(),
                report.getProblemSummary(),
                LocalDate.now(),
                actions);
    }

    @Transactional
    public ActionCheckin checkin(
            Long userId,
            Long planId,
            LocalDate date,
            boolean completed,
            String emotion,
            String note) {
        plans.findByIdAndUserId(planId, userId).orElseThrow(() -> ApiException.notFound("计划不存在"));
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        ActionCheckin checkin =
                checkins.findByPlanIdAndUserIdAndCheckinDate(planId, userId, targetDate)
                        .orElseGet(ActionCheckin::new);
        checkin.setPlanId(planId);
        checkin.setUserId(userId);
        checkin.setCheckinDate(targetDate);
        checkin.setCompleted(completed);
        checkin.setEmotion(emotion);
        checkin.setNote(note);
        return checkins.save(checkin);
    }

    public Dashboard dashboard(Long userId) {
        RelationshipProfile profile = profiles.findByUserId(userId).orElse(null);
        List<PlanView> allPlans = plans(userId);
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        long eventCount =
                events(userId).stream()
                        .filter(event -> event.getHappenedAt().isAfter(since))
                        .count();
        long completed =
                allPlans.stream()
                        .flatMap(view -> view.checkins().stream())
                        .filter(ActionCheckin::isCompleted)
                        .filter(
                                checkin ->
                                        !checkin.getCheckinDate()
                                                .isBefore(LocalDate.now().minusDays(6)))
                        .count();
        EmotionReport latestReview =
                reports.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .filter(report -> "WEEKLY_REVIEW".equals(report.getReportType()))
                        .findFirst()
                        .orElse(null);
        String focus =
                profile == null
                        ? "先完善关系档案，AI 才能长期记住你的关系背景与边界"
                        : value(profile.getConcerns(), "从一次真实互动和一个足够小的行动开始");
        DailyTopic topic = dailyTopic(userId);
        return new Dashboard(
                profile, focus, topic.question(), topic, eventCount, completed, latestReview);
    }

    public EmotionReport weeklyReview(Long userId) {
        RelationshipProfile profile = profiles.findByUserId(userId).orElse(null);
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<RelationshipEvent> recentEvents =
                events(userId).stream()
                        .filter(event -> event.getHappenedAt().isAfter(since))
                        .limit(20)
                        .toList();
        List<PlanView> allPlans = plans(userId);
        List<ActionCheckin> recentCheckins =
                allPlans.stream()
                        .flatMap(view -> view.checkins().stream())
                        .filter(
                                checkin ->
                                        !checkin.getCheckinDate()
                                                .isBefore(LocalDate.now().minusDays(6)))
                        .toList();
        long completed = recentCheckins.stream().filter(ActionCheckin::isCompleted).count();
        List<AiMessage> recentMessages =
                messages.findTop30ByUserIdOrderByCreatedAtDesc(userId).stream()
                        .filter(
                                message ->
                                        message.getCreatedAt() != null
                                                && message.getCreatedAt().isAfter(since))
                        .limit(12)
                        .toList();

        String context =
                """
                请生成一份本周关系成长复盘。必须区分事实、解释与需要，指出一个有效变化、一个仍需关注的模式，
                并给出 3 到 5 个下周可打卡的小行动。不得诊断，不得虚构未提供的事件。

                关系档案：%s
                本周事件：%s
                本周行动打卡：完成 %d 次，共 %d 条
                本周咨询摘要：%s
                """
                        .formatted(
                                profileSummary(profile),
                                eventSummary(recentEvents),
                                completed,
                                recentCheckins.size(),
                                messageSummary(recentMessages));

        RelationshipAiClient.RelationshipAnalysis analysis;
        try {
            analysis = ai.analyze(context);
        } catch (Exception ignored) {
            analysis =
                    new RelationshipAiClient.RelationshipAnalysis(
                            "本周关系成长复盘",
                            "本周记录 " + recentEvents.size() + " 个关系事件，完成 " + completed + " 次行动打卡。",
                            profile == null ? "未设置" : profile.getRelationshipStatus(),
                            "成长复盘",
                            "低",
                            recentEvents.isEmpty()
                                    ? "记录较少，建议先补充一次真实互动，再区分事实、解释与需要。"
                                    : "你已经开始把关系变化转化为可观察记录。下周重点是保留有效行动，并缩小未完成行动的难度。",
                            defaults(profile == null ? "持续改善关系" : profile.getConcerns(), profile)
                                    .subList(0, 3),
                            null);
        }

        EmotionReport report = new EmotionReport();
        report.setUserId(userId);
        report.setTitle(value(analysis.title(), "本周关系成长复盘"));
        report.setReportType("WEEKLY_REVIEW");
        report.setProblemSummary(
                value(
                        analysis.problemSummary(),
                        "本周记录 " + recentEvents.size() + " 个事件，完成 " + completed + " 次打卡。"));
        report.setRelationshipStatus(
                value(
                        analysis.relationshipStatus(),
                        profile == null ? "未设置" : profile.getRelationshipStatus()));
        report.setConflictType(value(analysis.conflictType(), "成长复盘"));
        report.setRiskLevel(value(analysis.riskLevel(), "低"));
        report.setAnalysis(value(analysis.analysis(), "建议继续记录具体事实和情绪变化。"));
        try {
            report.setActionsJson(
                    json.writeValueAsString(
                            analysis.actions() == null ? List.of() : analysis.actions()));
        } catch (Exception e) {
            report.setActionsJson("[]");
        }
        report.setReviewAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return reports.save(report);
    }

    private String dailyQuestion(Long userId) {
        int offset =
                (int) Math.floorMod(LocalDate.now().toEpochDay() + userId, DAILY_QUESTIONS.size());
        return DAILY_QUESTIONS.get(offset);
    }

    public DailyTopic refreshDailyTopic(Long userId) {
        RelationshipProfile profile = profiles.findByUserId(userId).orElse(null);
        String emotion = users.findById(userId).map(AppUser::getEmotionStatus).orElse("未设置");
        String interest =
                profile == null
                        ? ""
                        : value(profile.getConcerns(), "")
                                + " "
                                + value(profile.getPreferences(), "");
        int refreshNo = topicRefreshCounts.merge(userId, 1, Integer::sum);
        String theme =
                TOPIC_SEARCH_THEMES.get(Math.floorMod(refreshNo - 1, TOPIC_SEARCH_THEMES.size()));
        // 外部搜索只发送通用主题；档案和情绪只在本地用于挑选结果，避免把私密档案提交给搜索服务。
        String query = LocalDate.now().getYear() + " " + theme + " 最新话题 知乎 微博 豆瓣 小红书";
        List<WebSearchTool.WebResult> sources = webSearch.searchWebResults(query.trim(), 4);
        DailyTopic topic;
        if (sources.isEmpty()) {
            topic =
                    new DailyTopic(
                            dailyQuestion(userId, refreshNo),
                            "实时话题来源暂不可用，已为你切换到另一条基础连接问题。",
                            List.of(),
                            Instant.now(),
                            false);
        } else {
            int selected =
                    Math.floorMod(
                            Objects.hash(emotion, interest, LocalDate.now()) + refreshNo - 1,
                            sources.size());
            List<WebSearchTool.WebResult> rotatedSources = rotateSources(sources, selected);
            WebSearchTool.WebResult lead = rotatedSources.getFirst();
            String title = shorten(lead.title().replaceAll("[\r\n]+", " "), 52);
            String frame =
                    TOPIC_QUESTION_FRAMES.get(
                            Math.floorMod(refreshNo - 1, TOPIC_QUESTION_FRAMES.size()));
            String question = frame.formatted(title);
            topic =
                    new DailyTopic(
                            question,
                            shorten(value(lead.snippet(), "从这个公开话题开始聊两分钟。"), 180),
                            rotatedSources,
                            Instant.now(),
                            true);
        }
        dailyTopics.put(userId, topic);
        return topic;
    }

    private DailyTopic dailyTopic(Long userId) {
        DailyTopic cached = dailyTopics.get(userId);
        if (cached != null && cached.updatedAt().isAfter(Instant.now().minus(6, ChronoUnit.HOURS)))
            return cached;
        return refreshDailyTopic(userId);
    }

    private String dailyQuestion(Long userId, int refreshNo) {
        int offset =
                (int)
                        Math.floorMod(
                                LocalDate.now().toEpochDay() + userId + refreshNo,
                                DAILY_QUESTIONS.size());
        return DAILY_QUESTIONS.get(offset);
    }

    private List<WebSearchTool.WebResult> rotateSources(
            List<WebSearchTool.WebResult> sources, int start) {
        if (sources.size() < 2 || start == 0) return List.copyOf(sources);
        List<WebSearchTool.WebResult> rotated = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++)
            rotated.add(sources.get((start + i) % sources.size()));
        return List.copyOf(rotated);
    }

    private List<String> defaults(String goal, RelationshipProfile profile) {
        String concern = value(goal, profile == null ? "改善相处方式" : profile.getConcerns());
        String communication =
                profile == null ? "当前常见的沟通方式" : value(profile.getCommunicationStyle(), "当前常见的沟通方式");
        String preference =
                profile == null ? "双方都舒服的方式" : value(profile.getPreferences(), "双方都舒服的方式");
        String boundary = profile == null ? "彼此明确的边界" : value(profile.getBoundaries(), "彼此明确的边界");
        return List.of(
                "写下“" + shorten(concern, 30) + "”的一个可观察成功标准",
                "围绕“" + shorten(communication, 24) + "”进行一次 10 分钟不打断、不评判的倾听",
                "使用“当……发生时，我感到……，我希望……”表达一个具体需要",
                "按“" + shorten(preference, 24) + "”安排一次 20 分钟的共同活动",
                "记录一次情绪波动，并区分事实、解释与需要",
                "给予一条具体到行为的真诚肯定",
                "在尊重“" + shorten(boundary, 24) + "”的前提下回顾本周，并约定下周只保持一个小行动");
    }

    private List<String> readActions(String raw) {
        try {
            List<String> actions = json.readValue(value(raw, "[]"), new TypeReference<>() {});
            return actions == null
                    ? List.of()
                    : actions.stream()
                            .filter(Objects::nonNull)
                            .filter(item -> !item.isBlank())
                            .limit(7)
                            .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String profileSummary(RelationshipProfile profile) {
        if (profile == null) return "未完善";
        return "状态="
                + value(profile.getRelationshipStatus(), "未设置")
                + "；相处时长="
                + (profile.getRelationshipMonths() == null
                        ? "未填写"
                        : profile.getRelationshipMonths() + "个月")
                + "；沟通风格="
                + value(profile.getCommunicationStyle(), "未填写")
                + "；关注="
                + value(profile.getConcerns(), "未填写")
                + "；偏好="
                + value(profile.getPreferences(), "未填写")
                + "；边界="
                + value(profile.getBoundaries(), "未填写");
    }

    private String eventSummary(List<RelationshipEvent> recentEvents) {
        if (recentEvents.isEmpty()) return "无记录";
        return recentEvents.stream()
                .map(
                        event ->
                                event.getTitle()
                                        + "（"
                                        + value(event.getEmotion(), "未记录情绪")
                                        + "）："
                                        + shorten(value(event.getDescription(), "无详情"), 180))
                .reduce((left, right) -> left + "\n- " + right)
                .orElse("无记录");
    }

    private String messageSummary(List<AiMessage> recentMessages) {
        if (recentMessages.isEmpty()) return "无咨询记录";
        return recentMessages.stream()
                .map(
                        message ->
                                ("USER".equals(message.getRole()) ? "用户：" : "顾问：")
                                        + shorten(value(message.getContent(), ""), 180))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无咨询记录");
    }

    private String value(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    private String shorten(String text, int length) {
        return text == null ? "" : text.substring(0, Math.min(length, text.length()));
    }

    public record PlanView(ActionPlan plan, List<ActionCheckin> checkins) {}

    public record DailyTopic(
            String question,
            String context,
            List<WebSearchTool.WebResult> sources,
            Instant updatedAt,
            boolean live) {}

    public record Dashboard(
            RelationshipProfile profile,
            String focus,
            String dailyQuestion,
            DailyTopic dailyTopic,
            long weeklyEvents,
            long weeklyCompleted,
            EmotionReport latestWeeklyReview) {}
}
