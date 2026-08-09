package com.heartpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AmbienceImageServiceTest {
    private final AmbienceImageService service = new AmbienceImageService("");

    @Test
    void sendsOnlyGenericAtmosphereCategoriesToPexels() {
        String privatePlan = "我和张三在南宁吃晚餐，预算 300 元";

        String safeQuery = service.safeAtmosphereQuery(privatePlan);

        assertEquals("romantic dinner ambience", safeQuery);
        assertTrue(!safeQuery.contains("张三") && !safeQuery.contains("南宁"));
    }

    @Test
    void missingKeyReturnsStructuredLicenseAwareEmptyResult() {
        AmbienceImageService.SearchResult result = service.search("看展览", 4);

        assertEquals("DISABLED", result.sourceStatus());
        assertTrue(result.images().isEmpty());
    }
}
