package com.heartpilot.module.growth.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.growth.dto.GrowthDtos;
import com.heartpilot.module.growth.service.GrowthService;
import com.heartpilot.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/growth")
public class GrowthController {
    private final GrowthService service;
    private final CurrentUser current;

    public GrowthController(GrowthService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping("/events")
    PageResponse<GrowthDtos.EventResponse> events(
            @PageableDefault(size = 30, sort = "happenedAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.events(current.id(), pageable), GrowthDtos.EventResponse::from);
    }

    @PostMapping("/events")
    GrowthDtos.EventResponse add(@Valid @RequestBody GrowthDtos.EventRequest request) {
        return GrowthDtos.EventResponse.from(
                service.addEvent(
                        current.id(),
                        request.title(),
                        request.description(),
                        request.emotion(),
                        request.happenedAt()));
    }

    @PostMapping("/pulse")
    GrowthDtos.EventResponse pulse(@Valid @RequestBody GrowthDtos.PulseRequest request) {
        return GrowthDtos.EventResponse.from(
                service.pulse(
                        current.id(),
                        request.closeness(),
                        request.stress(),
                        request.emotion(),
                        request.answer()));
    }

    @GetMapping("/dashboard")
    GrowthDtos.DashboardResponse dashboard() {
        return GrowthDtos.DashboardResponse.from(service.dashboard(current.id()));
    }

    @PostMapping("/daily-topic/refresh")
    GrowthDtos.DailyTopicResponse refreshDailyTopic() {
        return GrowthDtos.DailyTopicResponse.from(service.refreshDailyTopic(current.id()));
    }

    @GetMapping("/plans")
    List<GrowthDtos.PlanViewResponse> plans() {
        return service.plans(current.id()).stream().map(GrowthDtos.PlanViewResponse::from).toList();
    }

    @PostMapping("/plans")
    GrowthDtos.PlanResponse plan(@Valid @RequestBody GrowthDtos.PlanRequest request) {
        return GrowthDtos.PlanResponse.from(
                service.createPlan(
                        current.id(),
                        request.title(),
                        request.goal(),
                        request.startDate(),
                        request.actions()));
    }

    @PostMapping("/plans/from-report/{reportId}")
    GrowthDtos.PlanResponse fromReport(@PathVariable Long reportId) {
        return GrowthDtos.PlanResponse.from(service.createFromReport(current.id(), reportId));
    }

    @PutMapping("/plans/{id}/checkins")
    GrowthDtos.CheckinResponse checkin(
            @PathVariable Long id, @Valid @RequestBody GrowthDtos.CheckinRequest request) {
        return GrowthDtos.CheckinResponse.from(
                service.checkin(
                        current.id(),
                        id,
                        request.date(),
                        request.completed(),
                        request.emotion(),
                        request.note()));
    }

    @PostMapping("/weekly-review")
    ResourceDtos.ReportResponse review() {
        return ResourceDtos.ReportResponse.from(service.weeklyReview(current.id()));
    }
}
