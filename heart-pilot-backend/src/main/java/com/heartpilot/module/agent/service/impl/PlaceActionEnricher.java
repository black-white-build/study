package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.requirement.ItineraryFeasibilityCalculator;
import com.heartpilot.module.agent.requirement.RequirementStateService;
import com.heartpilot.module.agent.requirement.StructuredRequirement;
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
 *
 * <p>Tier2 改造：接入"候选池前置 + 候选挑选 Agent"流程——
 * 高德先拉候选点位池，LLM 只从池内挑选排序（禁止凭空编造地点），
 * 并用纯 Java 计算通勤+停留总时长对比最晚返程时间（时间资源校验）。
 * 未取得可核验地点时返回降级条目，不编造店名与地址。
 */
@Service
public class PlaceActionEnricher extends AbstractActionEnricher {
    /** 默认每个点位停留分钟数（结构化需求未指定时使用） */
    private static final int DEFAULT_STAY_MINUTES = 60;

    private final AgentJourneyResearchService journeyResearch;
    /** Tier1 公共底层：结构化需求状态（约束唯一来源） */
    private final RequirementStateService requirementState;
    /** 时间资源校验器（纯 Java） */
    private final ItineraryFeasibilityCalculator feasibilityCalculator;

    public PlaceActionEnricher(
            AgentJourneyResearchService journeyResearch,
            RequirementStateService requirementState,
            ItineraryFeasibilityCalculator feasibilityCalculator,
            ObjectMapper json) {
        super(json);
        this.journeyResearch = journeyResearch;
        this.requirementState = requirementState;
        this.feasibilityCalculator = feasibilityCalculator;
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
        String base =
                draft.instruction() == null || draft.instruction().isBlank()
                        ? context.task().getObjective()
                        : draft.instruction();
        StringBuilder requirementsBuf = new StringBuilder(base);
        if (context.questions() != null && !context.questions().isEmpty()) {
            requirementsBuf.append("｜").append(String.join("；", context.questions()));
        }
        Object contextNotes = context.parameters().get("contextNotes");
        if (contextNotes != null && !String.valueOf(contextNotes).isBlank()) {
            requirementsBuf.append("｜").append(contextNotes);
        }
        requirementsBuf.append("｜预算：").append(context.budget());
        String requirements = requirementsBuf.toString();

        // Tier2：优先走候选池前置链路（结构化需求已由步骤 1 持久化）
        StructuredRequirement requirement = null;
        RequirementStateService.RequirementSnapshot snapshot = requirementState.get(context.task().getId());
        if (snapshot != null && snapshot.requirement() != null) {
            requirement = snapshot.requirement();
        }
        AgentJourneyResearchService.JourneyResearch journey;
        if (requirement != null && requirement.type() == com.heartpilot.module.agent.requirement.RequirementType.PLACE) {
            journey =
                    journeyResearch.researchJourneyFromPool(
                            context.task(),
                            context.stepNo(),
                            context.city(),
                            requirement,
                            requirements,
                            "plan-place-search");
        } else {
            // 兼容旧流程：没有结构化需求时走原有检索链路
            journey =
                    journeyResearch.researchJourney(
                            context.task(),
                            context.stepNo(),
                            context.city(),
                            requirements,
                            "plan-place-search");
        }
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
            // 按类别分组列出所有可核验地点卡片：主线卡片排最前（带路线），
            // 后面按类别 label（过山车/美食/停车/电竞…）分组，每个关键词都能看到对应地点，
            // 避免用户新加的问题（游泳/住宿等）在描述里看不到回应。
            StringBuilder placesLine = new StringBuilder();
            var cards = evidence.mapCards();
            int mainCount = places.size();
            // 先列主线
            for (int i = 0; i < Math.min(mainCount, cards.size()); i++) {
                PlaceSearchService.MapCard c = cards.get(i);
                if (i > 0) placesLine.append("\n");
                appendPlaceLine(placesLine, c, refs);
            }
            // 再按类别分组列候选
            java.util.Map<String, java.util.List<PlaceSearchService.MapCard>> byCategory =
                    new java.util.LinkedHashMap<>();
            for (int i = mainCount; i < cards.size(); i++) {
                PlaceSearchService.MapCard c = cards.get(i);
                String cat = (c.category() == null || c.category().isBlank()) ? "其他" : c.category();
                byCategory.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(c);
            }
            for (var entry : byCategory.entrySet()) {
                placesLine.append("\n【").append(entry.getKey()).append("】");
                for (PlaceSearchService.MapCard c : entry.getValue()) {
                    placesLine.append("\n");
                    appendPlaceLine(placesLine, c, refs);
                }
            }
            PlaceSearchService.Place primary = places.getFirst();
            data.put("placeName", primary.name());
            data.put("address", primary.address());
            data.put("category", primary.type());
            data.put("businessHours", primary.businessHours());
            data.put("businessStatus", primary.businessStatus());
            data.put("rating", primary.rating());
            data.put("location", primary.location());
            data.put("mapUrl", primary.mapUrl());
            if (primary.mapUrl() != null && !primary.mapUrl().isBlank()) refs.add(primary.mapUrl());
            item.setTitle(
                    draft.title() == null || draft.title().isBlank()
                            ? primary.name()
                            : draft.title());
            String intro =
                    draft.instruction() == null || draft.instruction().isBlank()
                            ? context.task().getObjective()
                            : draft.instruction();
            item.setInstruction(
                    (intro == null ? "" : intro)
                            + "\n\n已为你在不同类别下筛选到这些可核验地点：\n"
                            + placesLine);
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
        // 时间资源校验（纯 Java）：通勤耗时 + 停留总时长 vs 最晚返程时间
        if (!places.isEmpty()) {
            final int stayPerPlace;
            if (requirement != null && requirement.place() != null
                    && requirement.place().stayMinutesPerPlace() != null) {
                stayPerPlace = requirement.place().stayMinutesPerPlace();
            } else {
                stayPerPlace = DEFAULT_STAY_MINUTES;
            }
            java.util.List<Integer> commuteMinutes =
                    evidence.routes().stream()
                            .map(PlaceSearchService.RoutePlan::durationMinutes)
                            .filter(duration -> duration != null && duration > 0)
                            .map(Long::intValue)
                            .toList();
            java.util.List<Integer> stayMinutes =
                    places.stream().map(place -> stayPerPlace).toList();
            ItineraryFeasibilityCalculator.Feasibility feasibility =
                    feasibilityCalculator.calculate(
                            requirement != null && requirement.place() != null
                                    ? requirement.place().startTime()
                                    : null,
                            requirement != null && requirement.place() != null
                                    ? requirement.place().latestReturnTime()
                                    : null,
                            commuteMinutes,
                            stayMinutes,
                            15);
            data.put("feasibility", feasibility.message());
            if (!feasibility.feasible()) {
                data.put("feasibilityLevel", "WARN");
            } else {
                data.put("feasibilityLevel", "OK");
            }
        }
        item.setPayloadJson(payload(data));
        item.setSourceReferencesJson(refs(refs));
        return new EnrichedAction(item, refs);
    }

    /** 把一张地图卡片拼成 "- 名称（地址）· 评分 X · [高德地图](链接)" 一行 */
    private void appendPlaceLine(StringBuilder buf, PlaceSearchService.MapCard c, List<String> refs) {
        buf.append("- ")
                .append(c.name())
                .append("（")
                .append(c.address() == null || c.address().isBlank() ? "地址待确认" : c.address())
                .append("）");
        if (c.rating() != null && !c.rating().isBlank()) {
            buf.append(" · 评分 ").append(c.rating());
        }
        if (c.mapUrl() != null && !c.mapUrl().isBlank()) {
            buf.append(" · [高德地图](").append(c.mapUrl()).append(")");
            refs.add(c.mapUrl());
        }
    }
}
