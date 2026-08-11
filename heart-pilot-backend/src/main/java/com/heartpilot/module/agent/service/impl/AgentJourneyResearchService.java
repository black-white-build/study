package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.runtime.PublicInfoResearchAgent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Owns external place/route research and the optional constrained ReAct supplement. */
@Service
public class AgentJourneyResearchService {
    private final PlaceSearchService placeSearch;
    private final AgentToolExecutor toolExecutor;
    private final AgentExecutionTraceService executionTrace;
    private final PublicInfoResearchAgent researchAgent;
    private final TaskRepository tasks;
    private final ObjectMapper json;
    private final boolean reactEnabled;

    public AgentJourneyResearchService(
            PlaceSearchService placeSearch,
            AgentToolExecutor toolExecutor,
            AgentExecutionTraceService executionTrace,
            PublicInfoResearchAgent researchAgent,
            TaskRepository tasks,
            ObjectMapper json,
            @Value("${app.agent.react-enabled:true}") boolean reactEnabled) {
        this.placeSearch = placeSearch;
        this.toolExecutor = toolExecutor;
        this.executionTrace = executionTrace;
        this.researchAgent = researchAgent;
        this.tasks = tasks;
        this.json = json;
        this.reactEnabled = reactEnabled;
    }

    public JourneyResearch researchJourney(
            AgentTask task, int stepNo, String city, String requirements, String toolName)
            throws Exception {
        trace(
                task,
                stepNo,
                AgentExecutionPhase.SEARCH,
                AgentExecutionEventType.ACTION,
                AgentExecutionEventStatus.RUNNING,
                "正在检索真实地点与公开来源",
                "系统正在按需求拆分类别，并限定在“" + city + "”范围内检索。",
                "高德地图 + Web Search",
                toolName,
                null,
                null,
                "https://www.amap.com",
                Map.of("city", city));
        long started = System.nanoTime();
        PlaceSearchService.JourneyResearchResult result =
                toolExecutor.executeJson(
                        task,
                        stepNo,
                        toolName,
                        city + "｜" + requirements,
                        PlaceSearchService.JourneyResearchResult.class,
                        () -> placeSearch.researchJourney(city, requirements));
        long durationMs = elapsedMillis(started);

        PlaceSearchService.JourneyEvidence evidence = result.evidence();
        task.setJourneyEvidenceJson(json.writeValueAsString(evidence));
        task.setEvidenceUpdatedAt(Instant.now());
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());

        for (PlaceSearchService.SearchGroup group : result.searchResult().groups()) {
            trace(
                    task,
                    stepNo,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.OBSERVATION,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "已检索“" + group.label() + "”类别",
                    group.places().isEmpty()
                            ? "暂未取得地图 POI，已保留公开网页核验结果。"
                            : "取得 " + group.places().size() + " 个真实地图地点。",
                    group.places().isEmpty() ? "Web Search" : "高德地图",
                    toolName,
                    group.places().size(),
                    durationMs,
                    "https://www.amap.com",
                    Map.of("query", group.query()));
        }
        trace(
                task,
                stepNo,
                AgentExecutionPhase.FILTER,
                AgentExecutionEventType.RESULT,
                AgentExecutionEventStatus.SUCCEEDED,
                "已筛选可执行候选地点",
                evidence.places().isEmpty()
                        ? "没有取得可核验的地图地点，系统不会编造店名或地址。"
                        : String.join(
                                "、",
                                evidence.places().stream()
                                        .map(PlaceSearchService.Place::name)
                                        .toList()),
                "规则筛选器",
                null,
                evidence.places().size(),
                null,
                null,
                Map.of("topics", evidence.topics()));

        String routeDetail =
                evidence.routes().isEmpty()
                        ? evidence.notice()
                        : String.join(
                                "\n",
                                evidence.routes().stream()
                                        .map(PlaceSearchService.RoutePlan::formatted)
                                        .toList());
        trace(
                task,
                stepNo,
                AgentExecutionPhase.ROUTE,
                evidence.routes().isEmpty()
                        ? AgentExecutionEventType.WARNING
                        : AgentExecutionEventType.RESULT,
                AgentExecutionEventStatus.SUCCEEDED,
                evidence.routes().isEmpty() ? "实时路线暂不可用" : "已计算地点间路线",
                routeDetail,
                "高德地图",
                "distance-aware-route",
                evidence.routes().size(),
                durationMs,
                evidence.routes().isEmpty()
                        ? "https://www.amap.com"
                        : evidence.routes().getFirst().navigationUrl(),
                Map.of(
                        "modes",
                        evidence.routes().stream()
                                .map(PlaceSearchService.RoutePlan::mode)
                                .distinct()
                                .toList()));
        return new JourneyResearch(result.formatted(), evidence);
    }

    public PublicResearch supplementPublicInfo(AgentTask task, String city, String originalPlaces) {
        String places = originalPlaces;
        String verification =
                "已严格限定在“"
                        + city
                        + "”范围内筛选候选地点；其他城市结果不会进入计划。\n"
                        + "筛选维度：地点真实性、地址完整度、活动匹配度、预算适配度和公开信息可信度。";
        if (!reactEnabled) {
            trace(
                    task,
                    3,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.WARNING,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "ReAct/MCP 当前未启用",
                    "本次继续使用高德地图 REST 与公开网页检索结果；启用后可看到额外工具观察。",
                    "Feature Flag",
                    null,
                    null,
                    null,
                    null,
                    Map.of("flag", "AGENT_REACT_ENABLED"));
            return new PublicResearch(places, verification);
        }

        String publicPrompt =
                "请核验以下公开行动需求，只搜索必要的动态信息。城市："
                        + city
                        + "；目标："
                        + task.getObjective()
                        + "；已有检索结果："
                        + shorten(places, 2_000);
        try {
            trace(
                    task,
                    3,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.ACTION,
                    AgentExecutionEventStatus.RUNNING,
                    "ReAct 正在判断是否需要补充公开信息",
                    "Agent 可通过 ToolCallAgent 调用已注册的搜索或 MCP 工具。",
                    "Spring AI",
                    "react-mcp-public-research",
                    null,
                    null,
                    null,
                    Map.of());
            long researchStarted = System.nanoTime();
            String agentResearch =
                    toolExecutor.execute(
                            task,
                            3,
                            "react-mcp-public-research",
                            publicPrompt,
                            () -> researchAgent.research(publicPrompt));
            verification += "\n\nReAct/MCP 补充核验：\n" + shorten(agentResearch, 3_000);
            places += "\n\n### ReAct/MCP 补充核验\n" + shorten(agentResearch, 3_000);
            trace(
                    task,
                    3,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.OBSERVATION,
                    AgentExecutionEventStatus.SUCCEEDED,
                    "ReAct/MCP 已返回补充观察",
                    shorten(agentResearch, 1_200),
                    "Spring AI MCP",
                    "react-mcp-public-research",
                    null,
                    elapsedMillis(researchStarted),
                    null,
                    Map.of());
        } catch (Exception exception) {
            verification += "\nReAct/MCP 补充核验暂不可用，继续使用已取得的本地检索证据。";
            trace(
                    task,
                    3,
                    AgentExecutionPhase.SEARCH,
                    AgentExecutionEventType.WARNING,
                    AgentExecutionEventStatus.FAILED,
                    "ReAct/MCP 暂不可用，已自动降级",
                    shorten(Optional.ofNullable(exception.getMessage()).orElse("外部能力不可用"), 500),
                    "Spring AI MCP",
                    "react-mcp-public-research",
                    null,
                    null,
                    null,
                    Map.of("fallback", "AMAP_REST_AND_WEB_SEARCH"));
        }
        return new PublicResearch(places, verification);
    }

    private void trace(
            AgentTask task,
            Integer stepNo,
            AgentExecutionPhase phase,
            AgentExecutionEventType eventType,
            AgentExecutionEventStatus status,
            String title,
            String detail,
            String provider,
            String toolName,
            Integer itemCount,
            Long durationMs,
            String sourceUrl,
            Map<String, ?> metadata) {
        executionTrace.record(
                task.getId(),
                task.getVersionNo(),
                stepNo,
                phase,
                eventType,
                status,
                title,
                detail,
                provider,
                toolName,
                itemCount,
                durationMs,
                sourceUrl,
                metadata);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }

    public record JourneyResearch(String formatted, PlaceSearchService.JourneyEvidence evidence) {}

    public record PublicResearch(String places, String verification) {}
}
