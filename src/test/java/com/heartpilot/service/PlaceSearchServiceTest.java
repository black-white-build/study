package com.heartpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.tools.WebSearchTool;
import java.util.List;
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

    @Test
    void rejectsPlacesOutsideTheRequestedCityEvenWhenAmapReturnsThem() {
        assertTrue(
                PlaceSearchService.matchesRequestedScope("南宁河池", "广西壮族自治区", "南宁市", "青秀区", "民族大道"));
        assertTrue(
                PlaceSearchService.matchesRequestedScope("南宁青秀区", "广西壮族自治区", "南宁市", "青秀区", "民族大道"));
        assertEquals(
                false,
                PlaceSearchService.matchesRequestedScope("南宁河池", "北京市", "北京市", "朝阳区", "国家体育场南路1号"));
    }

    @Test
    void swimmingSearchRejectsUnrelatedShoppingMalls() {
        assertTrue(PlaceSearchService.matchesTopic("游泳馆", "广西体育中心游泳馆", "体育休闲服务;运动场馆"));
        assertEquals(
                false,
                PlaceSearchService.matchesTopic("游泳馆", "某某健身中心", "体育休闲服务;健身中心"));
        assertEquals(false, PlaceSearchService.matchesTopic("游泳馆", "万象城", "购物服务;购物中心"));
    }

    @Test
    void preservesCustomIntentInsteadOfMappingItToFixedCategories() {
        PlaceSearchService service = new PlaceSearchService("", new WebSearchTool(""));

        PlaceSearchService.SearchResult result = service.search("上海市", "去玩游艇");

        assertEquals("游艇", result.groups().getFirst().label());
        assertTrue(result.groups().getFirst().query().contains("游艇"));
        assertEquals(false, result.keywords().contains("运动"));
    }

    @Test
    void extractsOnlyConcreteQuestionKeywords() {
        PlaceSearchService service = new PlaceSearchService("", new WebSearchTool(""));

        PlaceSearchService.SearchResult result = service.search("天津市", "有摩天轮，去酒吧\n喝啤酒");

        assertEquals(List.of("摩天轮", "酒吧", "啤酒"),
                result.groups().stream().map(PlaceSearchService.SearchGroup::label).toList());
        assertEquals(false, result.keywords().contains("目标"));
        assertEquals(false, result.keywords().contains("优先"));
    }

    @Test
    void genericCityPoisCannotSatisfyAnUnrelatedKeyword() {
        assertEquals(false, PlaceSearchService.matchesTopic("睡觉", "天津市", "地名地址信息;省级地名"));
        assertEquals(false, PlaceSearchService.matchesTopic("睡觉", "天津市第一中学", "学校"));
    }
}
