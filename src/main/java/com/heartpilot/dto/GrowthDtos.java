package com.heartpilot.dto;

import com.heartpilot.domain.ActionCheckin;
import com.heartpilot.domain.ActionPlan;
import com.heartpilot.domain.RelationshipEvent;
import com.heartpilot.service.GrowthService;
import com.heartpilot.tools.WebSearchTool;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class GrowthDtos {
    private GrowthDtos() {}

    public record EventRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 4_000) String description,
            @Size(max = 32) String emotion,
            Instant happenedAt) {}

    public record PulseRequest(
            @Min(1) @Max(5) int closeness,
            @Min(1) @Max(5) int stress,
            @Size(max = 32) String emotion,
            @Size(max = 2_000) String answer) {}

    public record PlanRequest(
            @NotBlank @Size(max = 140) String title,
            @Size(max = 4_000) String goal,
            LocalDate startDate,
            List<@Size(max = 500) String> actions) {}

    public record CheckinRequest(
            LocalDate date,
            boolean completed,
            @Size(max = 32) String emotion,
            @Size(max = 1_000) String note) {}

    public record EventResponse(
            Long id,
            String title,
            String description,
            String emotion,
            Instant happenedAt,
            Instant createdAt) {
        public static EventResponse from(RelationshipEvent entity) {
            return new EventResponse(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getDescription(),
                    entity.getEmotion(),
                    entity.getHappenedAt(),
                    entity.getCreatedAt());
        }
    }

    public record PlanResponse(
            Long id,
            Long taskId,
            String title,
            String goal,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String dailyActionsJson,
            Instant createdAt) {
        public static PlanResponse from(ActionPlan entity) {
            return new PlanResponse(
                    entity.getId(),
                    entity.getTaskId(),
                    entity.getTitle(),
                    entity.getGoal(),
                    entity.getStartDate(),
                    entity.getEndDate(),
                    entity.getStatus(),
                    entity.getDailyActionsJson(),
                    entity.getCreatedAt());
        }
    }

    public record CheckinResponse(
            Long id,
            Long planId,
            LocalDate checkinDate,
            boolean completed,
            String emotion,
            String note,
            Instant createdAt) {
        public static CheckinResponse from(ActionCheckin entity) {
            return new CheckinResponse(
                    entity.getId(),
                    entity.getPlanId(),
                    entity.getCheckinDate(),
                    entity.isCompleted(),
                    entity.getEmotion(),
                    entity.getNote(),
                    entity.getCreatedAt());
        }
    }

    public record PlanViewResponse(PlanResponse plan, List<CheckinResponse> checkins) {
        public static PlanViewResponse from(GrowthService.PlanView view) {
            return new PlanViewResponse(
                    PlanResponse.from(view.plan()),
                    view.checkins().stream().map(CheckinResponse::from).toList());
        }
    }

    public record WebSourceResponse(String title, String snippet, String url) {
        static WebSourceResponse from(WebSearchTool.WebResult source) {
            return new WebSourceResponse(source.title(), source.snippet(), source.url());
        }
    }

    public record DailyTopicResponse(
            String question,
            String context,
            List<WebSourceResponse> sources,
            Instant updatedAt,
            boolean live) {
        public static DailyTopicResponse from(GrowthService.DailyTopic topic) {
            return new DailyTopicResponse(
                    topic.question(),
                    topic.context(),
                    topic.sources().stream().map(WebSourceResponse::from).toList(),
                    topic.updatedAt(),
                    topic.live());
        }
    }

    public record DashboardResponse(
            UserDtos.ProfileResponse profile,
            String focus,
            String dailyQuestion,
            DailyTopicResponse dailyTopic,
            long weeklyEvents,
            long weeklyCompleted,
            ResourceDtos.ReportResponse latestWeeklyReview) {
        public static DashboardResponse from(GrowthService.Dashboard dashboard) {
            return new DashboardResponse(
                    UserDtos.ProfileResponse.from(dashboard.profile()),
                    dashboard.focus(),
                    dashboard.dailyQuestion(),
                    DailyTopicResponse.from(dashboard.dailyTopic()),
                    dashboard.weeklyEvents(),
                    dashboard.weeklyCompleted(),
                    dashboard.latestWeeklyReview() == null
                            ? null
                            : ResourceDtos.ReportResponse.from(dashboard.latestWeeklyReview()));
        }
    }
}
