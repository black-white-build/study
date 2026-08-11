package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;

public interface AgentJourneyResearchService {
    JourneyResearch researchJourney(
            AgentTask task, int stepNo, String city, String requirements, String toolName)
            throws Exception;

    PublicResearch supplementPublicInfo(AgentTask task, String city, String originalPlaces);

    public record JourneyResearch(String formatted, PlaceSearchService.JourneyEvidence evidence) {}

    public record PublicResearch(String places, String verification) {}
}
