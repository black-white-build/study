package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface AgentTaskInputService {
    String buildPreview(
            AgentTask task,
            String city,
            String budget,
            String places,
            List<String> questions,
            List<String> revisions);

    String combinedRequirements(AgentTask task, Map<String, Object> parameters);

    String searchRequirements(Map<String, Object> parameters);

    List<String> cityOptions(String province);

    List<String> asStringList(Object raw);

    List<String> mergeQuestions(List<String> existing, List<String> incoming);

    List<String> extractQuestions(String note);

    String searchCategories(String searchResult);

    String parameterText(Object value, String fallback);

    String budgetLabel(String budget);

    void normalizeStoredBudget(Map<String, Object> parameters);

    BigDecimal normalizeBudget(BigDecimal budget);

    Map<String, Object> readParameters(AgentTask task);

    String writeParameters(Map<String, Object> parameters);

    String resolveCity(Map<String, Object> parameters, String text);

    String validateAndResolveRegion(Map<String, Object> parameters);

    String findKnownCity(String text);

    String normalizeIdempotencyKey(String key);
}
