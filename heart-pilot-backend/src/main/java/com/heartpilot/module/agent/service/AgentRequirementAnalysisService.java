package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.List;

public interface AgentRequirementAnalysisService {
    Analysis analyze(
            AgentTask task,
            String city,
            String budget,
            List<String> questions,
            List<String> revisions);

    public record Analysis(String searchText, List<String> keywords, boolean aiGenerated) {}

    public record ModelAnalysis(List<String> keywords) {}
}
