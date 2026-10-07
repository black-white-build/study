package com.heartpilot.module.agent.requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 领域提示提供器（Tier3 RAG 的轻量简化版）。
 * <p>原型阶段先用内置规则词库补充领域常识（礼品优缺点、地点避坑），
 * 以纯文本约束形式注入候选挑选 / 关键词生成 Prompt，减少编造内容；
 * 完整 PGVector RAG 知识库（Vaiage 方案）作为后续增强保留扩展点，
 * 原型演示不需要接入。
 */
@Service
public class DomainTipsProvider {

    /** 送礼领域提示：品类 → 优缺点与适用场景 */
    private static final Map<String, String> GIFT_TIPS =
            Map.ofEntries(
                    Map.entry("茶", "茶叶礼盒百搭但需确认对方是否喝茶；绿茶忌闷泡，送茶附茶具更贴心"),
                    Map.entry("游戏", "游戏外设使用频率高，但要确认设备接口与电脑配置，避免买错型号"),
                    Map.entry("香", "香薰蜡烛/香氛不贴身不易踩雷；敏感体质者慎选浓香，选低敏植物精油"),
                    Map.entry("书", "送书要选对方感兴趣的主题，附手写卡片能显著提升心意感"),
                    Map.entry("花", "鲜花仪式感强但保质期短；预算有限时优先选择搭配小礼物的花束"),
                    Map.entry("饰品", "饰品贴身，需了解对方风格（简约/复古/轻奢），避免过度贵重给对方压力"));

    /** 地点领域提示：类别 → 避坑常识 */
    private static final Map<String, String> PLACE_TIPS =
            Map.ofEntries(
                    Map.entry("咖啡", "咖啡馆工作日比周末安静；热门店下午常满座，可提前电话确认"),
                    Map.entry("公园", "户外公园受天气影响大，雨天需备室内替代方案；部分公园夜场关闭"),
                    Map.entry("餐厅", "热门餐厅周末晚高峰需排队，建议避开 18:00-19:30 或提前预约"),
                    Map.entry("看展", "美术馆/展览多数周一闭馆，出发前确认当日开放时间与预约要求"),
                    Map.entry("散步", "沿江/沿湖步行道夜晚灯光与安全条件不同，优先选择成熟步道"));

    /**
     * 按需求类型返回领域提示文本（纯文本约束，注入 Prompt）。
     * @param requirement 结构化需求
     * @return 提示行列表（可能为空）
     */
    public List<String> tipsFor(StructuredRequirement requirement) {
        List<String> tips = new ArrayList<>();
        if (requirement == null) return tips;
        if (requirement.type() == RequirementType.PLACE && requirement.place() != null) {
            for (String keyword : requirement.place().recommendedVisit()) {
                String tip = matchTip(keyword, PLACE_TIPS);
                if (tip != null) tips.add(tip);
            }
        }
        if (requirement.type() == RequirementType.GIFT && requirement.gift() != null) {
            for (String keyword : requirement.gift().stylePreferences()) {
                String tip = matchTip(keyword, GIFT_TIPS);
                if (tip != null) tips.add(tip);
            }
        }
        return tips;
    }

    private String matchTip(String keyword, Map<String, String> tips) {
        if (keyword == null) return null;
        for (Map.Entry<String, String> entry : tips.entrySet()) {
            if (keyword.contains(entry.getKey()) || entry.getKey().contains(keyword)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
