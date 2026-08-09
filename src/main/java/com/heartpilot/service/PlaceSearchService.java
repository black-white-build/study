package com.heartpilot.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.heartpilot.tools.WebSearchTool;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PlaceSearchService {
    private static final String AMAP_URL = "https://restapi.amap.com/v3/place/text";
    private static final String AMAP_WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final DateTimeFormatter SEARCH_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TOPICS = 5;
    private static final List<TopicRule> TOPIC_RULES =
            List.of(
                    new TopicRule("餐厅", List.of("吃", "美食", "餐厅", "饭店", "午餐", "晚餐", "用餐")),
                    new TopicRule("酒店", List.of("酒店", "宾馆", "住宿", "民宿", "旅馆")),
                    new TopicRule("超市", List.of("超市", "便利店", "买东西", "采购")),
                    new TopicRule("景点", List.of("景点", "公园", "展馆", "博物馆", "游览", "参观", "散步")),
                    new TopicRule("停车", List.of("停车", "车位", "停车场", "开车")),
                    new TopicRule("咖啡", List.of("咖啡", "下午茶", "聊天")),
                    new TopicRule("影院", List.of("电影", "影院", "电影院")),
                    new TopicRule("商场", List.of("商场", "购物中心", "逛街")),
                    new TopicRule("交通", List.of("地铁", "公交", "打车", "路线", "怎么走", "交通")),
                    new TopicRule("医疗", List.of("医院", "诊所", "急诊", "药店")),
                    new TopicRule("学校", List.of("学校", "大学", "学院", "校园")),
                    new TopicRule("运动", List.of("健身", "运动", "球馆", "游泳")),
                    new TopicRule("演出活动", List.of("演出", "音乐会", "展览", "活动")));
    private final String amapKey;
    private final WebSearchTool webSearch;

    public PlaceSearchService(
            @Value("${AMAP_MAPS_API_KEY:}") String amapKey, WebSearchTool webSearch) {
        this.amapKey = amapKey;
        this.webSearch = webSearch;
    }

    public SearchResult search(String city, String objective) {
        List<SearchTopic> topics = inferTopics(objective);
        List<SearchGroup> groups = new ArrayList<>();
        Map<String, Place> allPlaces = new LinkedHashMap<>();
        for (SearchTopic topic : topics) {
            List<Place> places =
                    amapKey == null || amapKey.isBlank()
                            ? List.of()
                            : searchAmap(city, topic.label());
            places.forEach(
                    place ->
                            allPlaces.putIfAbsent(
                                    place.poiId().isBlank()
                                            ? place.name() + "|" + place.address()
                                            : place.poiId(),
                                    place));
            String webSources = webSearch.searchLocalPlaces(city, topic.query(), 4);
            groups.add(new SearchGroup(topic.label(), topic.query(), places, webSources));
        }
        String keywords = String.join("、", topics.stream().map(SearchTopic::label).toList());
        return new SearchResult(
                "按类别独立检索", city, keywords, new ArrayList<>(allPlaces.values()), "", groups);
    }

    public JourneyResearchResult researchJourney(String city, String objective) {
        SearchResult searchResult = search(city, objective);
        return new JourneyResearchResult(searchResult, buildJourneyEvidence(searchResult));
    }

    public JourneyEvidence buildJourneyEvidence(SearchResult searchResult) {
        List<Place> selectedPlaces = selectItineraryPlaces(searchResult, 4);
        List<RoutePlan> routes = new ArrayList<>();
        if (amapKey != null && !amapKey.isBlank()) {
            for (int i = 0; i + 1 < selectedPlaces.size(); i++) {
                RoutePlan route =
                        planWalkingRoute(selectedPlaces.get(i), selectedPlaces.get(i + 1));
                if (route != null) routes.add(route);
            }
        }
        String notice;
        if (selectedPlaces.isEmpty()) {
            notice = "当前未取得可核验的地图地点，请检查高德地图密钥或调整检索条件。";
        } else if (selectedPlaces.size() == 1) {
            notice = "已取得一个真实地点，至少需要两个地点才能计算地点间路线。";
        } else if (routes.isEmpty()) {
            notice = "地点已取得，但实时路线暂不可用；出发前请打开地图链接核验。";
        } else {
            notice = "距离和耗时来自高德地图实时步行路线，出发前仍建议核验路况与营业状态。";
        }
        return new JourneyEvidence(
                searchResult.provider(),
                searchResult.city(),
                searchResult.keywords(),
                selectedPlaces,
                routes,
                selectedPlaces.isEmpty() ? "DEGRADED" : "LIVE",
                notice,
                SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))),
                buildMapCards(selectedPlaces, routes));
    }

    private List<MapCard> buildMapCards(List<Place> places, List<RoutePlan> routes) {
        List<MapCard> cards = new ArrayList<>();
        for (int index = 0; index < places.size(); index++) {
            Place place = places.get(index);
            RoutePlan routeFromPrevious =
                    index == 0 || index - 1 >= routes.size() ? null : routes.get(index - 1);
            String[] coordinates = place.location().split(",", 2);
            cards.add(
                    new MapCard(
                            place.poiId(),
                            place.name(),
                            place.address(),
                            place.type(),
                            place.tel(),
                            coordinates.length > 0 ? coordinates[0] : "",
                            coordinates.length > 1 ? coordinates[1] : "",
                            place.mapUrl(),
                            place.photoUrl(),
                            place.rating(),
                            place.businessHours(),
                            place.businessStatus(),
                            place.statusCheckedAt(),
                            place.businessHours().isBlank()
                                    ? "NO_OPENING_HOURS"
                                    : "DERIVED_FROM_AMAP_OPENING_HOURS",
                            "高德地图",
                            place.mapUrl(),
                            routeFromPrevious));
        }
        return cards;
    }

    private List<Place> selectItineraryPlaces(SearchResult searchResult, int limit) {
        Map<String, Place> selected = new LinkedHashMap<>();
        for (SearchGroup group : searchResult.groups()) {
            if (group.places().isEmpty()) continue;
            Place place = group.places().getFirst();
            selected.putIfAbsent(placeKey(place), place);
            if (selected.size() >= limit) return new ArrayList<>(selected.values());
        }
        for (Place place : searchResult.places()) {
            selected.putIfAbsent(placeKey(place), place);
            if (selected.size() >= limit) break;
        }
        return new ArrayList<>(selected.values());
    }

    private String placeKey(Place place) {
        return place.poiId().isBlank()
                ? place.name() + "|" + place.address() + "|" + place.location()
                : place.poiId();
    }

    private RoutePlan planWalkingRoute(Place origin, Place destination) {
        if (origin.location().isBlank() || destination.location().isBlank()) return null;
        try {
            String response =
                    HttpUtil.get(
                            AMAP_WALKING_URL,
                            Map.of(
                                    "key",
                                    amapKey,
                                    "origin",
                                    origin.location(),
                                    "destination",
                                    destination.location(),
                                    "show_fields",
                                    "cost"));
            JSONObject root = JSONUtil.parseObj(response);
            JSONObject route = root.getJSONObject("route");
            JSONArray paths = route == null ? null : route.getJSONArray("paths");
            if (paths == null || paths.isEmpty()) return null;
            JSONObject path = paths.getJSONObject(0);
            long distanceMeters = path.getLong("distance", 0L);
            long durationSeconds = path.getLong("duration", 0L);
            String polyline = routePolyline(path);
            if (distanceMeters <= 0 || durationSeconds <= 0) return null;
            String navigationUrl =
                    "https://uri.amap.com/navigation?from="
                            + origin.location()
                            + ","
                            + URLEncoder.encode(origin.name(), StandardCharsets.UTF_8)
                            + "&to="
                            + destination.location()
                            + ","
                            + URLEncoder.encode(destination.name(), StandardCharsets.UTF_8)
                            + "&mode=walk&policy=1&src=heart-pilot&coordinate=gaode&callnative=0";
            return new RoutePlan(
                    origin.name(),
                    destination.name(),
                    distanceMeters,
                    Math.max(1, Math.round(durationSeconds / 60.0)),
                    "WALKING",
                    navigationUrl,
                    "LIVE",
                    SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))),
                    "高德地图",
                    "FASTEST_WALKING",
                    polyline);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Place> searchAmap(String city, String keywords) {
        try {
            String response =
                    HttpUtil.get(
                            AMAP_URL,
                            Map.of(
                                    "key", amapKey,
                                    "keywords", keywords,
                                    "city", city,
                                    "citylimit", "true",
                                    "offset", "12",
                                    "page", "1",
                                    "extensions", "all"));
            JSONObject root = JSONUtil.parseObj(response);
            JSONArray pois = root.getJSONArray("pois");
            Map<String, Place> uniquePlaces = new LinkedHashMap<>();
            if (pois != null) {
                for (int i = 0; i < Math.min(10, pois.size()); i++) {
                    JSONObject poi = pois.getJSONObject(i);
                    String poiId = poi.getStr("id", "").trim();
                    String name = poi.getStr("name", "未命名地点");
                    String address = poi.getStr("address", "地址待确认");
                    String location = poi.getStr("location", "");
                    String type = poi.getStr("type", "本地生活");
                    String tel = poi.getStr("tel", "");
                    JSONObject business = poi.getJSONObject("business");
                    JSONObject bizExt = poi.getJSONObject("biz_ext");
                    String businessHours =
                            firstNotBlank(
                                    business == null ? "" : business.getStr("opentime_today", ""),
                                    business == null ? "" : business.getStr("opentime_week", ""),
                                    bizExt == null ? "" : bizExt.getStr("opentime2", ""),
                                    bizExt == null ? "" : bizExt.getStr("open_time", ""));
                    String rating =
                            firstNotBlank(
                                    business == null ? "" : business.getStr("rating", ""),
                                    bizExt == null ? "" : bizExt.getStr("rating", ""));
                    JSONArray photos = poi.getJSONArray("photos");
                    String photoUrl =
                            photos == null || photos.isEmpty()
                                    ? ""
                                    : photos.getJSONObject(0).getStr("url", "");
                    String checkedAt =
                            SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
                    String businessStatus = inferBusinessStatus(businessHours);
                    String mapUrl =
                            !poiId.isBlank()
                                    ? "https://www.amap.com/place/" + poiId
                                    : location.isBlank()
                                            ? ""
                                            : "https://uri.amap.com/marker?position="
                                                    + location
                                                    + "&name="
                                                    + URLEncoder.encode(
                                                            name, StandardCharsets.UTF_8);
                    String uniqueKey =
                            !poiId.isBlank() ? poiId : name + "|" + address + "|" + location;
                    uniquePlaces.putIfAbsent(
                            uniqueKey,
                            new Place(
                                    poiId,
                                    name,
                                    address,
                                    type,
                                    tel,
                                    location,
                                    mapUrl,
                                    rating,
                                    businessHours,
                                    businessStatus,
                                    checkedAt,
                                    photoUrl));
                }
            }
            return new ArrayList<>(uniquePlaces.values());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SearchTopic> inferTopics(String objective) {
        String safeObjective = objective == null ? "" : objective.trim();
        String cleanedObjective = fallbackKeywords(safeObjective);
        String constraints =
                List.of(safeObjective.split("[｜；。！？?\\n]+")).stream()
                        .filter(value -> value.trim().matches("^关系档案偏好：.*"))
                        .map(this::fallbackKeywords)
                        .filter(value -> !value.isBlank())
                        .reduce((left, right) -> left + " " + right)
                        .orElse("");
        List<String> clauses =
                List.of(safeObjective.split("[｜；。！？?\\n]+")).stream()
                        .filter(value -> !value.trim().matches("^(?:地点|预算|关系档案偏好|必须遵守的关系边界)：.*"))
                        .map(this::fallbackKeywords)
                        .filter(value -> !value.isBlank())
                        .toList();
        String primaryContext = clauses.isEmpty() ? cleanedObjective : clauses.getFirst();
        Map<String, SearchTopic> topics = new LinkedHashMap<>();
        for (String clause : clauses) {
            for (TopicRule rule : TOPIC_RULES) {
                if (containsAny(clause, rule.aliases().toArray(String[]::new))) {
                    String query =
                            rule.label()
                                    + " "
                                    + shorten(
                                            primaryContext + " " + clause + " " + constraints, 100);
                    topics.putIfAbsent(rule.label(), new SearchTopic(rule.label(), query.trim()));
                    if (topics.size() >= MAX_TOPICS) return new ArrayList<>(topics.values());
                }
            }
        }
        if (topics.isEmpty()) {
            for (String clause : clauses) {
                if (clause.matches("^(?:\\d+(?:\\.\\d+)?元?|未限定)$")) continue;
                String label = shorten(clause.replaceAll("^(?:请问|我想知道|帮我找|有没有|有哪些)", ""), 18);
                if (label.isBlank()) continue;
                topics.putIfAbsent(
                        label,
                        new SearchTopic(label, shorten(clause + " " + constraints, 100).trim()));
                if (topics.size() >= 3) break;
            }
        }
        if (topics.isEmpty()) topics.put("本地信息", new SearchTopic("本地信息", "本地信息"));
        return new ArrayList<>(topics.values());
    }

    private String fallbackKeywords(String objective) {
        String keywords =
                objective
                        .replace('｜', ' ')
                        .replaceAll("(?:初始目标|地点|预算|需要解决的问题|历次补充要求|关系档案偏好|必须遵守的关系边界)：", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        if (keywords.isBlank()) return "本地地点";
        return keywords.length() <= 80 ? keywords : keywords.substring(0, 80);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String firstNotBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private String routePolyline(JSONObject path) {
        JSONArray steps = path.getJSONArray("steps");
        if (steps == null || steps.isEmpty()) return "";
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            String segment = steps.getJSONObject(index).getStr("polyline", "").trim();
            if (segment.isBlank()) continue;
            if (!segments.isEmpty()) {
                int separator = segment.indexOf(';');
                segment = separator < 0 ? "" : segment.substring(separator + 1);
            }
            if (!segment.isBlank()) segments.add(segment);
        }
        return String.join(";", segments);
    }

    /** Best-effort status derived from the opening-hours field returned by AMap. */
    private String inferBusinessStatus(String hours) {
        if (hours == null || hours.isBlank()) return "UNKNOWN";
        String normalized = hours.replace('：', ':');
        if (normalized.contains("24小时") || normalized.contains("00:00-24:00")) return "OPEN";
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")
                        .matcher(normalized);
        if (!matcher.find()) return "UNKNOWN";
        LocalTime start =
                LocalTime.of(
                        Integer.parseInt(matcher.group(1)) % 24,
                        Integer.parseInt(matcher.group(2)));
        LocalTime end =
                LocalTime.of(
                        Integer.parseInt(matcher.group(3)) % 24,
                        Integer.parseInt(matcher.group(4)));
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Shanghai"));
        boolean open =
                end.isAfter(start)
                        ? !now.isBefore(start) && now.isBefore(end)
                        : !now.isBefore(start) || now.isBefore(end);
        return open ? "OPEN" : "CLOSED";
    }

    private String shorten(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }

    private record TopicRule(String label, List<String> aliases) {}

    private record SearchTopic(String label, String query) {}

    public record Place(
            String poiId,
            String name,
            String address,
            String type,
            String tel,
            String location,
            String mapUrl,
            String rating,
            String businessHours,
            String businessStatus,
            String statusCheckedAt,
            String photoUrl) {
        public Place(
                String poiId,
                String name,
                String address,
                String type,
                String tel,
                String location,
                String mapUrl) {
            this(poiId, name, address, type, tel, location, mapUrl, "", "", "UNKNOWN", "", "");
        }
    }

    public record SearchGroup(String label, String query, List<Place> places, String webSources) {}

    public record SearchResult(
            String provider,
            String city,
            String keywords,
            List<Place> places,
            String fallbackText,
            List<SearchGroup> groups) {
        public SearchResult(
                String provider,
                String city,
                String keywords,
                List<Place> places,
                String fallbackText) {
            this(provider, city, keywords, places, fallbackText, List.of());
        }

        public String formatted() {
            StringBuilder out = new StringBuilder();
            out.append("检索城市：")
                    .append(city)
                    .append("\n动态检索类别：")
                    .append(keywords)
                    .append("\n检索时间：")
                    .append(SEARCH_TIME.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))))
                    .append("（实时刷新）")
                    .append("\n检索方式：每个类别分别搜索地点与公开网页来源\n\n");
            if (!groups.isEmpty()) {
                for (SearchGroup group : groups) {
                    out.append("### ")
                            .append(group.label())
                            .append("\n检索词：")
                            .append(group.query())
                            .append("\n");
                    if (!group.places().isEmpty()) {
                        out.append("地图地点：\n");
                        for (int i = 0; i < group.places().size(); i++) {
                            Place p = group.places().get(i);
                            out.append(i + 1)
                                    .append(". ")
                                    .append(p.name())
                                    .append("\n")
                                    .append("   地址：")
                                    .append(p.address())
                                    .append("\n")
                                    .append("   类型：")
                                    .append(p.type())
                                    .append("\n");
                            if (!p.tel().isBlank())
                                out.append("   电话：").append(p.tel()).append("\n");
                            if (!p.mapUrl().isBlank())
                                out.append("   地图：").append(p.mapUrl()).append("\n");
                        }
                    }
                    out.append("公开网页来源：\n").append(group.webSources()).append("\n\n");
                }
            } else {
                out.append(fallbackText == null ? "" : fallbackText);
            }
            return out.toString().trim();
        }
    }

    public record RoutePlan(
            String originName,
            String destinationName,
            long distanceMeters,
            long durationMinutes,
            String mode,
            String navigationUrl,
            String routeStatus,
            String routeCheckedAt,
            String provider,
            String strategy,
            String polyline) {
        public RoutePlan(
                String originName,
                String destinationName,
                long distanceMeters,
                long durationMinutes,
                String mode,
                String navigationUrl) {
            this(
                    originName,
                    destinationName,
                    distanceMeters,
                    durationMinutes,
                    mode,
                    navigationUrl,
                    "LIVE",
                    "",
                    "高德地图",
                    "",
                    "");
        }

        public String formatted() {
            String distance =
                    distanceMeters >= 1_000
                            ? String.format("%.1f 公里", distanceMeters / 1_000.0)
                            : distanceMeters + " 米";
            return originName
                    + " → "
                    + destinationName
                    + "：步行约 "
                    + distance
                    + "，约 "
                    + durationMinutes
                    + " 分钟\n路线："
                    + navigationUrl;
        }
    }

    public record MapCard(
            String poiId,
            String name,
            String address,
            String category,
            String phone,
            String longitude,
            String latitude,
            String mapUrl,
            String coverImageUrl,
            String rating,
            String businessHours,
            String businessStatus,
            String statusCheckedAt,
            String businessStatusBasis,
            String sourceProvider,
            String sourceUrl,
            RoutePlan routeFromPrevious) {}

    public record JourneyEvidence(
            String provider,
            String city,
            String topics,
            List<Place> places,
            List<RoutePlan> routes,
            String sourceStatus,
            String notice,
            String searchedAt,
            List<MapCard> mapCards) {
        public JourneyEvidence {
            places = places == null ? List.of() : places;
            routes = routes == null ? List.of() : routes;
            mapCards = mapCards == null ? List.of() : mapCards;
        }

        public JourneyEvidence(
                String provider,
                String city,
                String topics,
                List<Place> places,
                List<RoutePlan> routes,
                String sourceStatus,
                String notice,
                String searchedAt) {
            this(
                    provider,
                    city,
                    topics,
                    places,
                    routes,
                    sourceStatus,
                    notice,
                    searchedAt,
                    List.of());
        }

        public String formatted() {
            StringBuilder text = new StringBuilder();
            text.append("\n\n## 可核验地点与路线\n");
            if (!places.isEmpty()) {
                text.append("### 推荐地点\n");
                for (Place place : places) {
                    text.append("- ")
                            .append(place.name())
                            .append("｜")
                            .append(place.address())
                            .append("\n  地图：")
                            .append(place.mapUrl())
                            .append("\n");
                }
            }
            if (!routes.isEmpty()) {
                text.append("\n### 地点间路线\n");
                for (RoutePlan route : routes) {
                    text.append("- ").append(route.formatted()).append("\n");
                }
            }
            text.append("\n数据说明：").append(notice);
            return text.toString();
        }
    }

    public record JourneyResearchResult(SearchResult searchResult, JourneyEvidence evidence) {
        public String formatted() {
            return searchResult.formatted() + evidence.formatted();
        }
    }
}
