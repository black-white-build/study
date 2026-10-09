package com.heartpilot.module.agent.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.service.PlaceSearchService;
import com.heartpilot.module.agent.service.RouteMapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 基于已持久化的 POI 与路线折线，调用高德静态图接口生成带鉴权的路线地图图片。 Builds an authenticated AMap static image from persisted
 * POIs and route polylines.
 *
 * <p>设计要点： - 数据来源是任务上已落库的 JourneyEvidence JSON，不重新检索 - 路线折线点过多时等距抽样到 70 个点，控制静态图 URL/参数长度 - 最多画 10
 * 个标号点（A~J）和 4 条路线，循环配色 - 未配 Key 返回 503，高德返回非图片内容返回 502，便于前端降级提示
 */
@Service
public class RouteMapServiceImpl implements RouteMapService {
    /** 高德静态图接口地址 */
    private static final String STATIC_MAP_URL = "https://restapi.amap.com/v3/staticmap";

    /** 多条路线的循环配色 */
    private static final List<String> PATH_COLORS =
            List.of("0xD66755", "0x43835C", "0x4678A8", "0xA06B3B");

    /** 高德 Web 服务 Key */
    private final String amapKey;

    /** 解析任务上的 JourneyEvidence JSON */
    private final ObjectMapper json;

    /** 构造器注入。 */
    public RouteMapServiceImpl(@Value("${AMAP_MAPS_API_KEY:}") String amapKey, ObjectMapper json) {
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.json = json;
    }

    /** 渲染任务的路线地图图片字节。 读取证据 → 构造静态图参数（标号点 + 路线折线）→ 12 秒超时请求高德 → 校验返回确为图片。 */
    @Override
    public RouteMapImage render(AgentTask task) {
        if (amapKey.isBlank()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AMAP_KEY_MISSING", "未配置高德 Web 服务 Key");
        }
        PlaceSearchService.JourneyEvidence evidence = readEvidence(task);
        Map<String, Object> parameters = buildParameters(evidence);
        parameters.put("key", amapKey);
        try (HttpResponse response =
                HttpRequest.get(STATIC_MAP_URL).form(parameters).timeout(12_000).execute()) {
            String contentType = response.header("Content-Type");
            byte[] bytes = response.bodyBytes();
            // 高德可能返回错误页而非图片，必须校验 Content-Type 是 image/*
            if (!response.isOk()
                    || bytes == null
                    || bytes.length == 0
                    || contentType == null
                    || !contentType.startsWith("image/")) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY, "AMAP_STATIC_MAP_FAILED", "高德路线图生成失败");
            }
            return new RouteMapImage(bytes, contentType);
        }
    }

    /** 构造高德静态图请求参数。至少需要 2 个地点和至少 1 条路线，否则 400。 输出 900*420、2 倍清晰度、关闭路况，附带标号点与路径。 */
    Map<String, Object> buildParameters(PlaceSearchService.JourneyEvidence evidence) {
        List<String> locations = locations(evidence);
        if (locations.size() < 2 || evidence.routes().isEmpty()) {
            throw ApiException.badRequest("当前任务没有足够的地点与路线数据");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", "900*420");
        parameters.put("scale", "2");
        parameters.put("traffic", "0");
        parameters.put("markers", markers(locations));
        parameters.put("paths", paths(evidence, locations));
        return parameters;
    }

    private PlaceSearchService.JourneyEvidence readEvidence(AgentTask task) {
        try {
            return json.readValue(
                    task.getJourneyEvidenceJson(), PlaceSearchService.JourneyEvidence.class);
        } catch (Exception exception) {
            throw ApiException.badRequest("任务路线证据无法解析");
        }
    }

    private List<String> locations(PlaceSearchService.JourneyEvidence evidence) {
        List<String> locations = new ArrayList<>();
        for (PlaceSearchService.MapCard card : evidence.mapCards()) {
            String location = coordinate(card.longitude(), card.latitude());
            if (!location.isBlank()) locations.add(location);
        }
        if (!locations.isEmpty()) return locations;
        for (PlaceSearchService.Place place : evidence.places()) {
            if (validLocation(place.location())) locations.add(place.location());
        }
        return locations;
    }

    private String markers(List<String> locations) {
        List<String> markers = new ArrayList<>();
        for (int index = 0; index < Math.min(10, locations.size()); index++) {
            char label = (char) ('A' + index);
            markers.add("mid,0xD66755," + label + ":" + locations.get(index));
        }
        return String.join("|", markers);
    }

    private String paths(
            PlaceSearchService.JourneyEvidence evidence, List<String> fallbackLocations) {
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < Math.min(4, evidence.routes().size()); index++) {
            PlaceSearchService.RoutePlan route = evidence.routes().get(index);
            String polyline = simplifyPolyline(route.polyline(), 70);
            if (polyline.isBlank() && index + 1 < fallbackLocations.size()) {
                polyline = fallbackLocations.get(index) + ";" + fallbackLocations.get(index + 1);
            }
            if (!polyline.isBlank()) {
                paths.add(
                        "8," + PATH_COLORS.get(index % PATH_COLORS.size()) + ",0.9,,:" + polyline);
            }
        }
        if (paths.isEmpty()) throw ApiException.badRequest("当前路线没有可绘制的坐标轨迹");
        return String.join("|", paths);
    }

    /** 把折线坐标等距抽样到最多 maxPoints 个点。 点不多时原样返回；过多时按索引均匀采样（保留首尾），控制静态图参数长度。 */
    String simplifyPolyline(String polyline, int maxPoints) {
        if (polyline == null || polyline.isBlank()) return "";
        List<String> points =
                List.of(polyline.split(";")).stream().filter(this::validLocation).toList();
        if (points.size() <= maxPoints) return String.join(";", points);
        LinkedHashSet<String> sampled = new LinkedHashSet<>();
        for (int index = 0; index < maxPoints; index++) {
            int pointIndex = (int) Math.round(index * (points.size() - 1.0) / (maxPoints - 1.0));
            sampled.add(points.get(pointIndex));
        }
        return String.join(";", sampled);
    }

    private String coordinate(String longitude, String latitude) {
        String location = value(longitude) + "," + value(latitude);
        return validLocation(location) ? location : "";
    }

    private boolean validLocation(String location) {
        return location != null
                && location.matches("-?\\d{1,3}(?:\\.\\d+)?,-?\\d{1,2}(?:\\.\\d+)?");
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
