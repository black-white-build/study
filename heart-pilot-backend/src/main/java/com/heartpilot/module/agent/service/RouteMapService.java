package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;

/** 路线地图渲染服务。 把任务中的地点与路线证据渲染为一张静态路线地图图片（PNG 等），供报告/页面展示。 */
public interface RouteMapService {
    /**
     * 渲染任务对应的路线地图图片。
     *
     * @param task 任务实体（含地点与路线证据）
     * @return 图片二进制内容及 MIME 类型
     */
    RouteMapImage render(AgentTask task);

    /**
     * 渲染产物：图片字节与内容类型。
     *
     * @param bytes 图片二进制内容
     * @param contentType MIME 类型（如 image/png）
     */
    public record RouteMapImage(byte[] bytes, String contentType) {}
}
