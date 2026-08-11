package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.List;

public interface AgentFinalReportService {
    String generate(
            AgentTask task,
            String allRequirements,
            List<String> questions,
            String budget,
            String note,
            AgentJourneyResearchService.JourneyResearch journey);
}
