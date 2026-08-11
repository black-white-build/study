package com.heartpilot.module.agent.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public interface PlaceSearchService {
    DateTimeFormatter SEARCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    SearchResult search(String city, String objective);

    JourneyResearchResult researchJourney(String city, String objective);

    JourneyEvidence buildJourneyEvidence(SearchResult searchResult);

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
            String photoUrl,
            String intentCategory,
            String amapTypeCode) {
        public Place {
            intentCategory = intentCategory == null ? "CUSTOM" : intentCategory;
            amapTypeCode = amapTypeCode == null ? "" : amapTypeCode;
        }

        public Place(
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
            this(
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
                    statusCheckedAt,
                    photoUrl,
                    "CUSTOM",
                    "");
        }

        public Place(
                String poiId,
                String name,
                String address,
                String type,
                String tel,
                String location,
                String mapUrl) {
            this(
                    poiId, name, address, type, tel, location, mapUrl, "", "", "UNKNOWN", "", "",
                    "CUSTOM", "");
        }
    }

    public record SearchGroup(
            String label,
            String query,
            List<Place> places,
            String webSources,
            String intentCategory,
            List<String> amapTypeCodes,
            List<String> searchKeywords) {
        public SearchGroup {
            intentCategory = intentCategory == null ? "CUSTOM" : intentCategory;
            amapTypeCodes = amapTypeCodes == null ? List.of() : amapTypeCodes;
            searchKeywords = searchKeywords == null ? List.of() : searchKeywords;
        }

        public SearchGroup(String label, String query, List<Place> places, String webSources) {
            this(label, query, places, webSources, "CUSTOM", List.of(), List.of(query));
        }
    }

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
                    if (group.places().isEmpty())
                        out.append("地图地点：未找到与“").append(group.label()).append("”相关且位于指定城市的地点。\n");
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
                    + "："
                    + switch (mode) {
                        case "BICYCLING" -> "骑行";
                        case "DRIVING" -> "驾车";
                        case "TRANSIT" -> "地铁/公交";
                        default -> "步行";
                    }
                    + "约 "
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
