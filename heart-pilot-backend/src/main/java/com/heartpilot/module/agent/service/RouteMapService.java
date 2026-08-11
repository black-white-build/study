package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;

public interface RouteMapService {
    RouteMapImage render(AgentTask task);

    public record RouteMapImage(byte[] bytes, String contentType) {}
}
