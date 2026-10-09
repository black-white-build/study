package com.heartpilot.module.agent.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 地点检索服务。 封装高德地图等外部地图/搜索能力：按城市与需求检索 POI 地点、地点间路线规划， 并把检索结果组装为结构化证据（JourneyEvidence）供大模型与报告使用。 */
public interface PlaceSearchService {
    /** 检索时间统一格式（Asia/Shanghai 时区），用于在输出文本中标注实时检索时刻 */
    DateTimeFormatter SEARCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 按城市与需求检索地点（可分组、可附公开网页来源）。
     *
     * @param city 目标城市
     * @param objective 检索需求
     * @return 检索结果
     */
    SearchResult search(String city, String objective);

    /**
     * 拉取候选点位池（Tier2：候选池前置，参考 ITINERA）。 与 search 的区别：每个检索主题取更多 POI（扩大候选池）， 后续由候选挑选 Agent
     * 只从池内挑选排序，禁止凭空编造地点。
     *
     * @param city 目标城市
     * @param objective 检索需求
     * @return 候选池检索结果（分组，每类候选数量更多）
     */
    SearchResult searchPool(String city, String objective);

    /**
     * 基于已挑选的点位构建行程证据（候选挑选 Agent 输出 → 路线规划 → 证据）。 对挑选出的点位逐个规划相邻路线，并生成地图卡片。
     *
     * @param city 目标城市
     * @param selectedPlaces 已挑选并按序排列的点位
     * @return 行程证据（路线 + 地图卡片 + 说明）
     */
    JourneyEvidence researchFromPool(String city, List<Place> selectedPlaces);

    /**
     * 执行完整行程研究：地点检索 + 路线规划。
     *
     * @param city 目标城市
     * @param objective 检索需求
     * @return 研究结果（检索文本 + 证据）
     */
    JourneyResearchResult researchJourney(String city, String objective);

    /**
     * 把单次地点检索结果组装为结构化行程证据（供报告与溯源）。
     *
     * @param searchResult 地点检索结果
     * @return 行程证据
     */
    JourneyEvidence buildJourneyEvidence(SearchResult searchResult);

    /**
     * 基于已持久化的候选池，重新随机抽取一批候选地点卡片（不调外部 API）。 行程主线（places）与路线（routes）保持不变，仅替换 mapCards：
     * 主线卡片排最前并保留路线信息，其余位置按类别均衡随机抽样填充， 用 seed 控制随机性，多次调用传入不同 seed 可得到不同批次（允许与上一批部分重合）。
     *
     * @param current 当前行程证据（保留其 places/routes/notice 等不变字段）
     * @param pool 首次检索时持久化的候选池（分组结果）
     * @param seed 随机种子，每次"换一批"传入新值
     * @return 替换了 mapCards 的新证据
     */
    JourneyEvidence reshuffleCandidateCards(JourneyEvidence current, SearchResult pool, long seed);

    /**
     * 单个 POI 地点。
     *
     * @param poiId 地点 ID
     * @param name 地点名称
     * @param address 地址
     * @param type 地点类型
     * @param tel 联系电话
     * @param location 经纬度坐标
     * @param mapUrl 地图跳转链接
     * @param rating 评分
     * @param businessHours 营业时间
     * @param businessStatus 营业状态
     * @param statusCheckedAt 营业状态核验时间
     * @param photoUrl 封面图
     * @param intentCategory 检索意图类别（默认 CUSTOM）
     * @param amapTypeCode 高德地图 POI 类型编码
     */
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

    /**
     * 一组同类别检索结果（一个意图类别对应一组地点 + 网页来源）。
     *
     * @param label 类别展示名
     * @param query 该类别实际使用的检索词
     * @param places 该类别下的地点列表
     * @param webSources 公开网页来源文本
     * @param intentCategory 意图类别（默认 CUSTOM）
     * @param amapTypeCodes 该类别用到的高德 POI 类型编码
     * @param searchKeywords 该类别用到的检索关键词
     */
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

    /**
     * 地点检索结果。
     *
     * @param provider 数据提供方
     * @param city 检索城市
     * @param keywords 动态检索类别关键词
     * @param places 扁平地点列表（兼容旧调用）
     * @param fallbackText 无分组时的兜底文本
     * @param groups 分组检索结果
     */
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

    /**
     * 两地之间的路线规划。
     *
     * @param originName 起点名称
     * @param destinationName 终点名称
     * @param distanceMeters 距离（米）
     * @param durationMinutes 预计耗时（分钟）
     * @param mode 出行方式（BICYCLING/DRIVING/TRANSIT/步行）
     * @param navigationUrl 导航链接
     * @param routeStatus 路线数据状态（LIVE=实时）
     * @param routeCheckedAt 路线核验时间
     * @param provider 路线提供方
     * @param strategy 路线策略
     * @param polyline 路线折线坐标（可选，用于前端画地图）
     */
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
                    + "[打开导航]("
                    + navigationUrl
                    + ")";
        }
    }

    /**
     * 地图卡片：用于前端地图上逐个展示的地点信息。
     *
     * @param poiId 地点 ID
     * @param name 名称
     * @param address 地址
     * @param category 类别
     * @param phone 电话
     * @param longitude 经度
     * @param latitude 纬度
     * @param mapUrl 地图链接
     * @param coverImageUrl 封面图
     * @param rating 评分
     * @param businessHours 营业时间
     * @param businessStatus 营业状态
     * @param statusCheckedAt 状态核验时间
     * @param businessStatusBasis 营业状态判定依据
     * @param sourceProvider 数据来源
     * @param sourceUrl 来源页
     * @param routeFromPrevious 从上一地点到此处的路线
     */
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

    /**
     * 可核验的行程证据（地点 + 路线 + 地图卡片），是报告生成与前端溯源的结构化依据。
     *
     * @param provider 数据提供方
     * @param city 城市
     * @param topics 检索主题
     * @param places 推荐地点列表
     * @param routes 地点间路线列表
     * @param sourceStatus 数据来源状态
     * @param notice 数据说明
     * @param searchedAt 检索时间
     * @param mapCards 地图卡片列表
     */
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

    /**
     * 行程研究完整结果：检索文本 + 结构化证据。
     *
     * @param searchResult 地点检索结果
     * @param evidence 行程证据
     */
    public record JourneyResearchResult(SearchResult searchResult, JourneyEvidence evidence) {
        public String formatted() {
            return searchResult.formatted() + evidence.formatted();
        }
    }
}
