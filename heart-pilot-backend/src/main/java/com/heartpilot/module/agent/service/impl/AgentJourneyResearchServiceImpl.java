package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.runtime.PublicInfoResearchAgent;
import com.heartpilot.module.agent.service.AgentExecutionTraceService;
import com.heartpilot.module.agent.service.AgentJourneyResearchService;
import com.heartpilot.module.agent.service.PlaceSearchService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 负责外部地点/路线检索，以及可选的受约束 ReAct 补充核验。
 * Owns external place/route research and the optional constrained ReAct supplement.
 *
 * 职责拆分：
 * - researchJourney：按类别调用高德地图 + 网页检索拿到真实地点与路线，
 *   把证据序列化为 JSON 持久化到任务上，并逐类别写检索/筛选/路线轨迹
 * - supplementPublicInfo：在 ReAct/MCP 开关开启时，让 PublicInfoResearchAgent
 *   动态判断是否需要补充公开信息；失败自动降级回退到已有证据，不阻断主流程
 *
 * 可靠性设计要点：
 * - 所有外部调用都经过 AgentToolExecutor 包裹，获得幂等缓存、超时与重试能力
 * - 检索结果（JourneyEvidence）回写任务时同步乐观锁版本号，避免并发覆盖
 * - ReAct 异常只记录 WARNING 轨迹并降级，不向上抛出导致任务失败
 */
@Service
public class AgentJourneyResearchServiceImpl implements AgentJourneyResearchService {
    /** 地点检索服务（高德 + 网页搜索） */
    private final PlaceSearchService placeSearch;
    /** 工具执行器：统一处理外部调用的幂等、超时、重试与审计 */
    private final AgentToolExecutor toolExecutor;
    /** 执行轨迹记录器 */
    private final AgentExecutionTraceService executionTrace;
    /** ReAct 公开信息研究 Agent（Spring AI ToolCall/MCP） */
    private final PublicInfoResearchAgent researchAgent;
    /** 任务 Repository，用于回写行程证据 JSON */
    private final TaskRepository tasks;
    /** JSON 序列化工具，把证据对象写入任务 */
    private final ObjectMapper json;
    /** ReAct/MCP 总开关，配置项 app.agent.react-enabled，默认开启 */
    private final boolean reactEnabled;

    /**
     * 构造器注入。reactEnabled 来自配置项，关闭时 supplementPublicInfo 直接走降级路径。
     */
    public AgentJourneyResearchServiceImpl(
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

    /**
     * 执行一轮完整的地点与路线检索。
     * 流程：记录检索开始轨迹 → 经工具执行器调用 placeSearch.researchJourney（带幂等/超时/重试）→
     * 把证据序列化持久化到任务（同步乐观锁版本号）→ 逐类别写 OBSERVATION 轨迹 →
     * 写筛选结果轨迹 → 写路线结果轨迹。
     *
     * @param task 当前任务
     * @param stepNo 当前步骤编号
     * @param city 限定城市范围
     * @param requirements 检索需求文本
     * @param toolName 工具调用审计名称
     * @return 格式化后的检索文本 + 结构化证据
     */
    @Override
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
        // 把证据 JSON 持久化到任务，供后续静态路线图、最终报告复用
        task.setJourneyEvidenceJson(json.writeValueAsString(evidence));
        task.setEvidenceUpdatedAt(Instant.now());
        AgentTask saved = tasks.saveAndFlush(task);
        // saveAndFlush 后数据库 @Version 自增，回写到内存对象，避免后续更新版本冲突
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

    /**
     * 用 ReAct/MCP 补充核验公开信息（步骤 3）。
     * 开关关闭时直接返回基础筛选说明；开启时构造受限 Prompt 交给 researchAgent 动态判断，
     * 把补充观察追加到地点文本与核验说明里。
     * 任何异常都被 catch 住：降级为"补充核验暂不可用"，继续使用已有本地检索证据，绝不向上抛。
     *
     * @param task 当前任务
     * @param city 限定城市
     * @param originalPlaces 上一步已得到的地点文本
     * @return 补充后的地点文本 + 核验说明
     */
    @Override
    public PublicResearch supplementPublicInfo(AgentTask task, String city, String originalPlaces) {
        String places = originalPlaces;
        // 基础筛选说明：强调严格限定城市范围与筛选维度
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
            // ReAct/MCP 不可用：降级到高德 REST + 网页检索证据，记录 WARNING 轨迹后继续
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

    /** 记录一条检索阶段的执行轨迹，委托给 AgentExecutionTraceService */
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

    /** 纳秒时间戳差值转毫秒，用于统计工具/检索耗时 */
    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** 截断字符串到指定长度，null 转空串，避免写入 Prompt 的内容过长 */
    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }
}
