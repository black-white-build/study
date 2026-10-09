package com.heartpilot.module.agent.requirement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heartpilot.common.exception.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结构化需求状态服务：持久化 + 增量局部修改。
 *
 * <p>设计要点（对应 ITINERA / Vaiage 的持久化方案）：
 *
 * <ul>
 *   <li>结构化 JSON 落库（agent_requirement_state），与对话文本分离，是约束的唯一权威来源
 *   <li>用户修改单条约束（改预算、删黑名单、加必去点位）通过 PATCH 直接更新 JSON 对应字段， 无需整段重写 prompt、无需重新抽取全部需求
 *   <li>每次修改后立即用纯 Java 校验器重新校验，冲突实时反馈
 * </ul>
 */
@Service
public class RequirementStateService {
    /** 修改操作：set=覆盖字段；add=列表追加；remove=列表移除 */
    public enum Operation {
        SET,
        ADD,
        REMOVE
    }

    private final AgentRequirementStateRepository states;
    private final ObjectMapper json;
    private final RequirementValidator validator;

    public RequirementStateService(
            AgentRequirementStateRepository states,
            ObjectMapper json,
            RequirementValidator validator) {
        this.states = states;
        this.json = json;
        this.validator = validator;
    }

    /** 对外快照：结构化需求 + 校验结果 + 状态。 */
    public record RequirementSnapshot(
            StructuredRequirement requirement,
            ValidationResult validation,
            String status,
            String extractionSource) {}

    /** 查询任务的结构化需求，无记录时返回 null。 */
    public RequirementSnapshot get(Long taskId) {
        return states.findByTaskId(taskId).map(this::snapshot).orElse(null);
    }

    /** 保存（新建或覆盖）结构化需求与校验结果，返回最新快照。 */
    @Transactional
    public RequirementSnapshot save(Long taskId, Long userId, StructuredRequirement requirement) {
        AgentRequirementState state =
                states.findByTaskId(taskId).orElseGet(AgentRequirementState::new);
        if (state.getTaskId() == null) {
            state.setTaskId(taskId);
            state.setUserId(userId);
        }
        state.setRequirementType(requirement.type() == null ? "" : requirement.type().name());
        state.setStructuredJson(write(requirement));
        state.setValidationJson(write(validator.validate(requirement)));
        state.setExtractionSource(requirement.aiGenerated() ? "AI" : "RULE");
        states.save(state);
        return snapshot(state);
    }

    /**
     * 增量局部修改约束：按字段路径更新 JSON 后重新校验。 路径支持：硬性/优先/可选/排除四类列表（add/remove）、实体字段（set）， 例如 {@code
     * "place.budgetMax"}、{@code "exclusions"}、{@code "gift.forbiddenCategories"}。
     *
     * @param taskId 任务 ID
     * @param path 字段路径
     * @param operation set/add/remove
     * @param value 新值或列表元素
     * @return 修改并重新校验后的快照
     */
    @Transactional
    public RequirementSnapshot updateConstraint(
            Long taskId, String path, Operation operation, Object value) {
        AgentRequirementState state =
                states.findByTaskId(taskId)
                        .orElseThrow(() -> ApiException.notFound("任务的结构化需求不存在，请先运行任务完成需求解析"));
        try {
            JsonNode root = json.readTree(state.getStructuredJson());
            JsonNode target = resolvePath(root, path);
            if (target == null) throw ApiException.badRequest("字段路径不存在：" + path);
            applyOperation(root, path, target, operation, value);
            StructuredRequirement requirement = json.treeToValue(root, StructuredRequirement.class);
            if (requirement == null || requirement.type() == null)
                throw ApiException.badRequest("修改后结构化需求不完整，请检查提交内容");
            state.setStructuredJson(write(requirement));
            state.setValidationJson(write(validator.validate(requirement)));
            states.save(state);
            return snapshot(state);
        } catch (ApiException api) {
            throw api;
        } catch (Exception exception) {
            throw ApiException.badRequest("约束修改失败：" + exception.getMessage());
        }
    }

    /** 标记需求已确认（用户核对无误后调用），并返回快照。 */
    @Transactional
    public RequirementSnapshot confirm(Long taskId) {
        AgentRequirementState state =
                states.findByTaskId(taskId).orElseThrow(() -> ApiException.notFound("任务的结构化需求不存在"));
        state.setStatus("CONFIRMED");
        states.save(state);
        return snapshot(state);
    }

    /** 删除任务的结构化需求（任务删除时级联调用）。 */
    @Transactional
    public void delete(Long taskId) {
        states.deleteByTaskId(taskId);
    }

    /** 按点分路径解析 JSON 节点："place.budgetMax" → root.place.budgetMax */
    private JsonNode resolvePath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            current = current.get(segment);
        }
        return current;
    }

    /** 对目标节点执行操作，并回写 ObjectNode */
    private void applyOperation(
            JsonNode root, String path, JsonNode target, Operation operation, Object value) {
        String[] segments = path.split("\\.");
        JsonNode parent = root;
        for (int i = 0; i < segments.length - 1; i++) parent = parent.get(segments[i]);
        String leaf = segments[segments.length - 1];
        if (!(parent instanceof ObjectNode objectParent)) {
            throw ApiException.badRequest("字段路径不支持修改：" + path);
        }
        switch (operation) {
            case SET -> {
                JsonNode node = json.valueToTree(value);
                // 空字符串/空值按删除处理，保持 JSON 干净
                if (node == null
                        || node.isNull()
                        || (node.isTextual() && node.asText().isBlank())) {
                    objectParent.remove(leaf);
                } else {
                    objectParent.set(leaf, node);
                }
            }
            case ADD, REMOVE -> {
                if (!(target instanceof ArrayNode array)) {
                    throw ApiException.badRequest("该字段不是列表，不能使用 add/remove：" + path);
                }
                String item = String.valueOf(value).trim();
                if (operation == Operation.ADD) {
                    if (!item.isBlank() && !contains(array, item)) array.add(item);
                } else {
                    // ArrayNode 不支持 removeIf，手动按内容移除
                    int index = -1;
                    for (int i = 0; i < array.size(); i++) {
                        JsonNode node = array.get(i);
                        if (node.isTextual() && node.asText().trim().equals(item)) {
                            index = i;
                            break;
                        }
                    }
                    if (index >= 0) array.remove(index);
                }
            }
            default -> throw ApiException.badRequest("不支持的操作：" + operation);
        }
    }

    private boolean contains(ArrayNode array, String value) {
        for (JsonNode node : array) {
            if (node.isTextual() && node.asText().trim().equals(value)) return true;
        }
        return false;
    }

    private RequirementSnapshot snapshot(AgentRequirementState state) {
        StructuredRequirement requirement = read(state.getStructuredJson());
        ValidationResult validation =
                readValidation(state.getValidationJson(), validator.validate(requirement));
        return new RequirementSnapshot(
                requirement, validation, state.getStatus(), state.getExtractionSource());
    }

    private StructuredRequirement read(String value) {
        try {
            return json.readValue(value, StructuredRequirement.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ValidationResult readValidation(String value, ValidationResult fallback) {
        try {
            List<RequirementIssue> issues =
                    json.readValue(value == null ? "[]" : value, new TypeReference<>() {});
            return new ValidationResult(issues);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存结构化需求", exception);
        }
    }
}
