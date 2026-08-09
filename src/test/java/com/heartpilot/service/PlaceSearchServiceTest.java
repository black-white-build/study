package com.heartpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.tools.WebSearchTool;
import org.junit.jupiter.api.Test;

class PlaceSearchServiceTest {
    @Test
    void missingExternalKeysProducesExplicitDegradedEvidenceWithoutInventingPlaces() {
        PlaceSearchService service = new PlaceSearchService("", new WebSearchTool(""));

        PlaceSearchService.JourneyResearchResult result =
                service.researchJourney("南宁", "看展览、吃晚餐并散步");

        assertEquals("DEGRADED", result.evidence().sourceStatus());
        assertTrue(result.evidence().places().isEmpty());
        assertTrue(result.evidence().routes().isEmpty());
        assertTrue(result.evidence().mapCards().isEmpty());
        assertTrue(result.evidence().notice().contains("地图地点"));
    }
}
