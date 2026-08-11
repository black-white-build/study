package com.heartpilot.module.growth.service;

import com.heartpilot.infrastructure.ai.tool.WebSearchTool;
import com.heartpilot.module.growth.entity.ActionCheckin;
import com.heartpilot.module.growth.entity.ActionPlan;
import com.heartpilot.module.growth.entity.RelationshipEvent;
import com.heartpilot.module.report.entity.EmotionReport;
import com.heartpilot.module.user.entity.RelationshipProfile;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GrowthService {
    List<RelationshipEvent> events(Long userId);

    Page<RelationshipEvent> events(Long userId, Pageable pageable);

    RelationshipEvent addEvent(
            Long userId, String title, String description, String emotion, Instant happenedAt);

    RelationshipEvent pulse(Long userId, int closeness, int stress, String emotion, String answer);

    List<PlanView> plans(Long userId);

    ActionPlan createPlan(
            Long userId, String title, String goal, LocalDate start, List<String> actions);

    ActionPlan createFromReport(Long userId, Long reportId);

    ActionCheckin checkin(
            Long userId,
            Long planId,
            LocalDate date,
            boolean completed,
            String emotion,
            String note);

    Dashboard dashboard(Long userId);

    EmotionReport weeklyReview(Long userId);

    DailyTopic refreshDailyTopic(Long userId);

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
