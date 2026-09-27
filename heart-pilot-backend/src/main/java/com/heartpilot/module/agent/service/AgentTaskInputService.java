package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 任务输入参数处理服务。
 * 集中负责任务输入参数的规范化、校验、序列化/反序列化、城市解析、预算归一化、问题列表合并等，
 * 是 AgentTaskService 与各子服务之间处理用户输入的工具层。
 */
public interface AgentTaskInputService {
    /**
     * 构造等待用户确认阶段展示的候选计划预览文本。
     */
    String buildPreview(
            AgentTask task,
            String city,
            String budget,
            String places,
            List<String> questions,
            List<String> revisions);

    /**
     * 合并全部需求（目标、问题、修订）为完整检索需求文本。
     */
    String combinedRequirements(AgentTask task, Map<String, Object> parameters);

    /** 从参数中提取检索需求文本（不含任务目标） */
    String searchRequirements(Map<String, Object> parameters);

    /** 按省份返回可选城市列表 */
    List<String> cityOptions(String province);

    /** 把原始对象安全转为字符串列表（兼容数组、逗号分隔字符串等） */
    List<String> asStringList(Object raw);

    /** 合并新旧问题列表，去重并保留顺序 */
    List<String> mergeQuestions(List<String> existing, List<String> incoming);

    /** 从用户修改说明中抽取隐含的问题/诉求 */
    List<String> extractQuestions(String note);

    /** 从检索结果文本中提取动态检索类别，用于前端展示 */
    String searchCategories(String searchResult);

    /** 参数转文本，为空时返回兜底文案 */
    String parameterText(Object value, String fallback);

    /** 把预算数值转为展示用文案（如"200 元以内"） */
    String budgetLabel(String budget);

    /** 规范化持久化前的预算参数（统一单位/格式），直接修改入参 Map */
    void normalizeStoredBudget(Map<String, Object> parameters);

    /** 归一化预算数值，避免脏数据 */
    BigDecimal normalizeBudget(BigDecimal budget);

    /** 读取任务持久化的参数 JSON 为 Map */
    Map<String, Object> readParameters(AgentTask task);

    /** 把参数 Map 序列化为 JSON 字符串持久化 */
    String writeParameters(Map<String, Object> parameters);

    /** 解析出实际搜索城市，优先取参数中的城市，缺失时从文本中识别 */
    String resolveCity(Map<String, Object> parameters, String text);

    /**
     * 校验并解析地区参数（省/市），返回标准化城市名。
     * @throws com.heartpilot.common.exception.ApiException 地区非法时抛出 400
     */
    String validateAndResolveRegion(Map<String, Object> parameters);

    /** 从自由文本中识别已知城市名，识别不到返回空串 */
    String findKnownCity(String text);

    /** 规范化幂等键（去空白等），为空返回 null */
    String normalizeIdempotencyKey(String key);
}
