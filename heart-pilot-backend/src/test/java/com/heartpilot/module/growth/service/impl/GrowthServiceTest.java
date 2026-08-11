package com.heartpilot.module.growth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.infrastructure.ai.tool.WebSearchTool;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.growth.repository.CheckinRepository;
import com.heartpilot.module.growth.repository.EventRepository;
import com.heartpilot.module.growth.repository.PlanRepository;
import com.heartpilot.module.growth.service.GrowthService;
import com.heartpilot.module.report.repository.ReportRepository;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.entity.RelationshipProfile;
import com.heartpilot.module.user.repository.AppUserRepository;
import com.heartpilot.module.user.repository.ProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GrowthServiceTest {

    @Test
    void refreshesDailyTopicWithClickableLiveSources() {
        ProfileRepository profiles = mock(ProfileRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        WebSearchTool webSearch = mock(WebSearchTool.class);
        RelationshipProfile profile = new RelationshipProfile();
        profile.setConcerns("希望减少争吵");
        profile.setPreferences("喜欢轻松的话题");
        AppUser user = new AppUser();
        user.setEmotionStatus("期待");
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(webSearch.searchWebResults(anyString(), eq(4)))
                .thenReturn(
                        List.of(
                                new WebSearchTool.WebResult(
                                        "年轻伴侣正在讨论的沟通方式",
                                        "从具体的小事表达支持。",
                                        "https://example.com/topic-1")))
                .thenReturn(
                        List.of(
                                new WebSearchTool.WebResult(
                                        "如何让伴侣感到被支持",
                                        "先倾听，再讨论解决办法。",
                                        "https://example.com/topic-2")));

        GrowthService service =
                new GrowthServiceImpl(
                        mock(EventRepository.class),
                        mock(PlanRepository.class),
                        mock(CheckinRepository.class),
                        mock(ReportRepository.class),
                        profiles,
                        mock(MessageRepository.class),
                        mock(RelationshipAiClient.class),
                        new ObjectMapper(),
                        webSearch,
                        users);

        GrowthService.DailyTopic topic = service.refreshDailyTopic(7L);
        GrowthService.DailyTopic refreshed = service.refreshDailyTopic(7L);

        assertTrue(topic.live());
        assertTrue(topic.question().contains("年轻伴侣正在讨论的沟通方式"));
        assertEquals("https://example.com/topic-1", topic.sources().getFirst().url());
        assertNotEquals(topic.question(), refreshed.question());
        assertEquals("https://example.com/topic-2", refreshed.sources().getFirst().url());
    }
}
