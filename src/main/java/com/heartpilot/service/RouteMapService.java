package com.heartpilot.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.domain.AgentTask;
import com.heartpilot.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Builds an authenticated AMap static image from persisted POIs and route polylines. */
@Service
public class RouteMapService {
    private static final String STATIC_MAP_URL = "https://restapi.amap.com/v3/staticmap";
    private static final List<String> PATH_COLORS =
            List.of("0xD66755", "0x43835C", "0x4678A8", "0xA06B3B");

    private final String amapKey;
    private final ObjectMapper json;

    public RouteMapService(@Value("${AMAP_MAPS_API_KEY:}") String amapKey, ObjectMapper json) {
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.json = json;
    }

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

    public record RouteMapImage(byte[] bytes, String contentType) {}
}
