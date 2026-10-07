package com.heartpilot.module.agent.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import com.heartpilot.module.agent.dto.AgentTaskDtos;
import com.heartpilot.module.agent.requirement.RequirementDtos;
import com.heartpilot.module.agent.requirement.RequirementStateService;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.agent.service.RouteMapService;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 智能行程任务 Controller。
 * 路径前缀 /agent-tasks，负责智能行程任务的全生命周期接口：
 * 创建、运行（SSE 流式输出）、用户确认、取消、删除、执行事件查询、路线图渲染与 PDF 导出。
 * 所有接口均要求登录，且通过 current.id() 限定只能访问当前用户自己的任务。
 */
@RestController
@RequestMapping("/agent-tasks")
public class AgentTaskController {
    private final AgentTaskService service;
    private final CurrentUser current;
    private final StorageService storage;
    private final RouteMapService routeMaps;
    private final CapabilityStatusRecorder capabilityStatus;
    /** Tier1 公共底层：结构化需求状态服务（持久化 + 增量修改） */
    private final RequirementStateService requirementState;

    public AgentTaskController(
            AgentTaskService service,
            CurrentUser current,
            StorageService storage,
            RouteMapService routeMaps,
            CapabilityStatusRecorder capabilityStatus,
            RequirementStateService requirementState) {
        this.service = service;
        this.current = current;
        this.storage = storage;
        this.routeMaps = routeMaps;
        this.capabilityStatus = capabilityStatus;
        this.requirementState = requirementState;
    }

    /**
     * GET /agent-tasks/capabilities
     * 暴露 AI 对话与网页搜索两项外部能力的运行时状态（正常/未配置/额度耗尽），
     * 供前端在任务页可视化提示，避免 key 额度用尽时静默降级成空结果。
     */
    @GetMapping("/capabilities")
    java.util.Map<String, Object> capabilities() {
        return capabilityStatus.snapshot();
    }

    /**
     * GET /agent-tasks
     * 分页查询当前用户的任务列表，按创建时间倒序。
     */
    @GetMapping
    PageResponse<AgentTaskDtos.TaskResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(current.id(), pageable), AgentTaskDtos.TaskResponse::from);
    }

    /**
     * GET /agent-tasks/{id}
     * 查询任务详情，聚合任务基本信息、步骤列表、工具调用记录、执行事件与 PDF 文件信息。
     */
    @GetMapping("/{id}")
    AgentTaskDtos.TaskDetailResponse get(@PathVariable Long id) {
        AgentTaskService.TaskDetail detail = service.get(id, current.id());
        return new AgentTaskDtos.TaskDetailResponse(
                AgentTaskDtos.TaskResponse.from(detail.task()),
                detail.steps().stream().map(AgentTaskDtos.StepResponse::from).toList(),
                detail.toolCalls().stream().map(AgentTaskDtos.ToolCallResponse::from).toList(),
                detail.executionEvents().stream()
                        .map(AgentTaskDtos.ExecutionEventResponse::from)
                        .toList(),
                AgentTaskDtos.FileResponse.from(detail.pdfFile()));
    }

    /**
     * GET /agent-tasks/{id}/execution-events
     * 查询任务的执行事件时间线（思考/行动/观察等），用于前端展示 Agent 执行轨迹。
     */
    @GetMapping("/{id}/execution-events")
    List<AgentTaskDtos.ExecutionEventResponse> executionEvents(@PathVariable Long id) {
        return service.get(id, current.id()).executionEvents().stream()
                .map(AgentTaskDtos.ExecutionEventResponse::from)
                .toList();
    }

    /**
     * GET /agent-tasks/{id}/plan
     * 查询任务对应的计划产物：计划信息、历史版本列表（最新在前）与最新版本的行动条目。
     * 供前端按行动类型渲染卡片、展示版本历史与逐条完成状态。
     */
    @GetMapping("/{id}/plan")
    AgentTaskDtos.PlanDetailResponse plan(@PathVariable Long id) {
        return AgentTaskDtos.PlanDetailResponse.from(service.plan(id, current.id()));
    }

    /**
     * GET /agent-tasks/{id}/route-map
     * 渲染任务对应的行程路线图图片并返回二进制流，私有缓存 5 分钟。
     */
    @GetMapping("/{id}/route-map")
    ResponseEntity<byte[]> routeMap(@PathVariable Long id) {
        RouteMapService.RouteMapImage image =
                routeMaps.render(service.get(id, current.id()).task());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(
                        org.springframework.http.CacheControl.maxAge(
                                        java.time.Duration.ofMinutes(5))
                                .cachePrivate())
                .body(image.bytes());
    }

    /**
     * POST /agent-tasks
     * 创建智能行程任务。支持 Idempotency-Key 请求头做幂等，防止网络重试重复创建任务。
     */
    @PostMapping
    AgentTaskDtos.TaskResponse create(
            @Valid @RequestBody AgentTaskDtos.CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return AgentTaskDtos.TaskResponse.from(
                service.create(
                        current.id(),
                        request.title(),
                        request.objective(),
                        request.parameters(),
                        idempotencyKey));
    }

    /**
     * GET /agent-tasks/{id}/requirement
     * 查询任务的结构化需求（四类约束 + 业务实体 + 代码校验结果）。
     * 供前端在需求检查点展示约束清单与冲突，任务尚未解析时返回 null。
     */
    @GetMapping("/{id}/requirement")
    RequirementDtos.RequirementResponse requirement(@PathVariable Long id) {
        return RequirementDtos.RequirementResponse.from(requirementState.get(id));
    }

    /**
     * PATCH /agent-tasks/{id}/requirement
     * 增量局部修改单条约束（改预算、删黑名单、加必去点位等），修改后立即用
     * 纯 Java 校验器重新校验并返回最新冲突。无需整段重写需求描述。
     */
    @PatchMapping("/{id}/requirement")
    RequirementDtos.RequirementResponse updateRequirement(
            @PathVariable Long id, @Valid @RequestBody RequirementDtos.UpdateRequest request) {
        RequirementStateService.Operation operation =
                switch (request.op().toUpperCase()) {
                    case "SET" -> RequirementStateService.Operation.SET;
                    case "ADD" -> RequirementStateService.Operation.ADD;
                    case "REMOVE" -> RequirementStateService.Operation.REMOVE;
                    default -> throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "不支持的操作：" + request.op());
                };
        return RequirementDtos.RequirementResponse.from(
                requirementState.updateConstraint(id, request.path(), operation, request.value()));
    }

    /**
     * POST /agent-tasks/{id}/requirement/approve
     * 需求检查点确认：用户核对结构化需求无误后调用。标记需求已确认，
     * 并基于已确认需求重新执行流水线（增量解析 + 重新校验），SSE 推送后续进度。
     */
    @PostMapping(
            value = "/{id}/requirement/approve",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter approveRequirement(@PathVariable Long id) {
        return service.approveRequirement(id, current.id());
    }

    /**
     * POST /agent-tasks/{id}/run
     * 启动任务执行，返回 SSE 流式响应，实时推送执行进度事件。
     */
    @PostMapping(value = "/{id}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter run(@PathVariable Long id) {
        return service.run(id, current.id());
    }

    /**
     * GET /agent-tasks/region-cities?province=xx
     * 按省份查询可选城市列表，供创建任务时下拉选择。
     */
    @GetMapping("/region-cities")
    List<String> regionCities(@RequestParam String province) {
        return service.cityOptions(province);
    }

    /**
     * POST /agent-tasks/{id}/confirm
     * 用户对候选计划做出确认/驳回。驳回时可附带修改后的城市、预算、问题列表，
     * 服务端据此进入重规划；同样以 SSE 流式返回执行进度。
     */
    @PostMapping(value = "/{id}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter confirm(
            @PathVariable Long id, @Valid @RequestBody AgentTaskDtos.ConfirmRequest request) {
        return service.confirm(
                id,
                current.id(),
                request.approved(),
                request.note(),
                request.province(),
                request.city(),
                request.budget(),
                request.questions(),
                request.contextNotes());
    }

    /**
     * POST /agent-tasks/{id}/cancel
     * 请求取消任务，仅设置取消标志，由执行循环在下一轮检测后真正停止。
     */
    @PostMapping("/{id}/cancel")
    AgentTaskDtos.TaskResponse cancel(@PathVariable Long id) {
        return AgentTaskDtos.TaskResponse.from(service.cancel(id, current.id()));
    }

    /**
     * POST /agent-tasks/{id}/reshuffle-places
     * 等待确认阶段"换一批候选地点"：从已持久化的候选池里按类别均衡随机重抽卡片，
     * 不调高德 API、不改行程主线与路线；相邻两批允许部分重合。
     */
    @PostMapping("/{id}/reshuffle-places")
    AgentTaskDtos.TaskResponse reshufflePlaces(@PathVariable Long id) {
        return AgentTaskDtos.TaskResponse.from(service.reshufflePlaces(id, current.id()));
    }

    /**
     * DELETE /agent-tasks/{id}
     * 删除任务及其关联的步骤、工具调用、执行事件与生成文件。
     */
    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id, current.id());
    }

    /**
     * POST /agent-tasks/{id}/pdf
     * 为任务生成最终行动报告 PDF 文件并返回文件信息。
     */
    @PostMapping("/{id}/pdf")
    AgentTaskDtos.FileResponse generatePdf(@PathVariable Long id) {
        return AgentTaskDtos.FileResponse.from(service.generatePdf(id, current.id()));
    }

    /**
     * GET /agent-tasks/{id}/pdf
     * 下载已生成的 PDF 报告，以附件形式返回二进制流。
     */
    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) throws Exception {
        GeneratedFile file = service.getPdf(id, current.id());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=action-plan-" + id + ".pdf")
                .body(storage.read(file.getStorageKey()));
    }
}
