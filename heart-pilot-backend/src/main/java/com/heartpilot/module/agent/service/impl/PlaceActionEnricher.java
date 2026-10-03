package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.AgentJourneyResearchService;
import com.heartpilot.module.agent.service.PlaceSearchService;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 地点型行动富化器。
 * 复用现有行程检索链路（AgentJourneyResearchService），把草案富化为带
 * 真实地点、地址、营业时间、路线信息的可执行条目；检索证据继续持久化到
 * 任务的 journeyEvidenceJson，前端地点证据面板无需改动。
 * 未取得可核验地点时返回降级条目，不编造店名与地址。
 */
@Service
public class PlaceActionEnricher extends AbstractActionEnricher {
    private final AgentJourneyResearchService journeyResearch;

    public PlaceActionEnricher(
            AgentJourneyResearchService journeyResearch, ObjectMapper json) {
        super(json);
        this.journeyResearch = journeyResearch;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.PLACE_VISIT;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) throws Exception {
        PlanActionItem item = baseItem(draft, context);
        if (context.city() == null || context.city().isBlank()) {
            item.setInstruction(
                    (draft.instruction() == null ? "" : draft.instruction() + "\n")
                            + "地点行动需要城市信息，请补充城市后重新规划。");
            item.setPayloadJson(payload(Map.of("status", "NEEDS_CITY")));
            return new EnrichedAction(item, List.of());
        }
        String requirements =
                (draft.instruction() == null || draft.instruction().isBlank()
                        ? context.task().getObjective()
                        : draft.instruction())
                        + "｜预算："
                        + context.budget();
        AgentJourneyResearchService.JourneyResearch journey =
                journeyResearch.researchJourney(
                        context.task(),
                        context.stepNo(),
                        context.city(),
                        requirements,
                        "plan-place-search");
        PlaceSearchService.JourneyEvidence evidence = journey.evidence();
        List<String> refs = new ArrayList<>();
        List<PlaceSearchService.Place> places = evidence.places();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (places.isEmpty()) {
            item.setInstruction(
                    (draft.instruction() == null ? "" : draft.instruction() + "\n")
                            + "当前城市暂未取得可核验的地点，系统不会编造店名或地址。");
            data.put("status", "NO_PLACE");
            data.put("notice", evidence.notice());
        } else {
            PlaceSearchService.Place place = places.getFirst();
            data.put("placeName", place.name());
            data.put("address", place.address());
            data.put("category", place.type());
            data.put("businessHours", place.businessHours());
            data.put("businessStatus", place.businessStatus());
            data.put("rating", place.rating());
            data.put("location", place.location());
            data.put("mapUrl", place.mapUrl());
            if (place.mapUrl() != null && !place.mapUrl().isBlank()) refs.add(place.mapUrl());
            item.setTitle(
                    draft.title() == null || draft.title().isBlank()
                            ? place.name()
                            : draft.title());
            item.setInstruction(
                    (draft.instruction() == null || draft.instruction().isBlank()
                            ? "安排一次适合谈心的见面"
                            : draft.instruction())
                            + "\n地点："
                            + place.name()
                            + "（"
                            + place.address()
                            + "）\n营业时间："
                            + (place.businessHours() == null || place.businessHours().isBlank()
                                    ? "以地图标注为准"
                                    : place.businessHours())
                            + "\n地图："
                            + place.mapUrl());
        }
        // 路线信息：同一批证据里的第一条路线作为到达建议
        if (!evidence.routes().isEmpty()) {
            PlaceSearchService.RoutePlan route = evidence.routes().getFirst();
            data.put("routeMode", route.mode());
            data.put("distanceMeters", route.distanceMeters());
            data.put("durationMinutes", route.durationMinutes());
            data.put("navigationUrl", route.navigationUrl());
            if (route.navigationUrl() != null && !route.navigationUrl().isBlank())
                refs.add(route.navigationUrl());
            if (route.durationMinutes() > 0)
                item.setEstimatedDurationMinutes((int) route.durationMinutes());
        } else {
            data.put("travelNote", "实时路线暂不可用，出发前请在地图确认");
        }
        item.setPayloadJson(payload(data));
        item.setSourceReferencesJson(refs(refs));
        return new EnrichedAction(item, refs);
    }
}
