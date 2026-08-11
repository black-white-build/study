package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRequirementAnalysisServiceTest {
    @Test
    void removesSystemProseAndKeepsConcreteSearchIntents() {
        assertEquals(
                List.of("住宿", "酒店", "民宿", "电竞馆", "网咖"),
                AgentRequirementAnalysisService.sanitize(
                        List.of("住宿 酒店 民宿", "当前有效参数", "目标", "电竞馆 网咖")));
    }

    @Test
    void rejectsOversizedOrMeaninglessModelOutput() {
        assertEquals(
                List.of("摩天轮", "酒吧"),
                AgentRequirementAnalysisService.sanitize(
                        List.of("摩天轮", "酒吧", "问题分析", "这是一个超过二十个汉字而不应进入地图搜索的模型解释文本")));
    }

    @Test
    void globallyDeduplicatesCategoriesWithoutCreatingRepeatedSearches() {
        assertEquals(
                List.of("游戏", "酒吧", "过山车", "住宿", "网吧"),
                AgentRequirementAnalysisService.sanitize(
                        List.of("游戏", "酒吧", "过山车", "酒吧", "住宿", "网吧", "住宿")));
    }
}
