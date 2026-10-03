package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.PlanSafetyChecker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规划安全检查器实现。
 * 与 AnswerSafetyPolicy 同源的确定性关键词规则，但作用于"计划草案"：
 * 逐条检查每条行动条目的 title / instruction / payload 文本，
 * 命中边界侵犯、现实危险、心理诊断或消息滥用规则时整单拒绝。
 *
 * 设计要点：
 * - 只做确定性兜底，不替代模型判断；所有规则均可被审计（reasonCode 记录）
 * - 识别否定语境："不要监控""不想跟踪"等表达不会被误判为违禁请求
 * - 观察型行动默认面向"自己"；若观察内容涉及记录对方行踪/位置/时间，判定为监控计划拒绝
 */
@Component
public class PlanSafetyCheckerImpl implements PlanSafetyChecker {
    /** 否定 + 违禁词 或 违禁词 + 否定 两种语序的预编译匹配器（借鉴 AnswerSafetyPolicy） */
    private static final Pattern NEGATED_DISALLOWED =
            Pattern.compile(
                    "(?:不要|不想|不会|别|避免)[\\u4e00-\\u9fa5]{0,3}(?:监控|跟踪|纠缠|骚扰|尾随|窥探)"
                            + "|(?:监控|跟踪|纠缠|骚扰|尾随|窥探)[\\u4e00-\\u9fa5]{0,3}(?:不要|不想|不会|别|避免)");

    /** 现实危险类关键词：命中后给出安全指引而不是继续规划 */
    private static final List<String> DANGER_TERMS =
            List.of("自杀", "自伤", "杀了", "打死", "家暴", "暴力", "持刀", "强迫");

    /** 心理诊断类关键词：拒绝诊断请求 */
    private static final List<String> DIAGNOSIS_TERMS =
            List.of("人格障碍", "自恋型人格", "心理疾病", "给他诊断", "给她诊断", "是不是精神病", "诊断");

    /** 消息行动中的攻击性表达：不进入计划 */
    private static final List<String> MESSAGE_ABUSE_TERMS =
            List.of("威胁", "恐吓", "辱骂", "骂他", "骂她", "羞辱", "贬低");

    /** 观察型行动中出现"记录对方行踪/位置/时间"等监控特征时拒绝 */
    private static final List<String> OBSERVATION_SURVEILLANCE_TERMS =
            List.of(
                    "行踪",
                    "定位",
                    "几点回家",
                    "几点出门",
                    "和谁见面",
                    "偷偷记录",
                    "暗中观察",
                    "偷看",
                    "查岗");

    @Override
    public Decision evaluate(List<DraftItem> items) {
        if (items == null || items.isEmpty()) {
            return new Decision(true, "NO_ITEMS", "计划为空，无需安全检查。", List.of());
        }
        List<String> blockedTitles = new ArrayList<>();
        for (DraftItem item : items) {
            String text = flatten(item);
            String cleaned = stripNegatedDisallowed(text);
            // 第一组：边界侵犯类（含伪装成观察/消息行动的监控与骚扰）
            if (containsAny(cleaned, "pua", "让他离不开", "让她离不开", "监控", "跟踪", "尾随", "纠缠",
                            "骚扰", "死缠烂打", "冒充", "偷看", "窥探", "代聊", "精神控制", "查岗", "监视")
                    || (item.kind() == ExecutionKind.OBSERVATION
                            && containsAny(cleaned, OBSERVATION_SURVEILLANCE_TERMS))) {
                return refusal(item, blockedTitles, "BOUNDARY_VIOLATION",
                        "这份计划包含跟踪、监控、纠缠或未经对方同意的行为，我不能生成。\n"
                                + "可以改为：一次明确、尊重的沟通，主动询问对方感受并设置自己的边界，"
                                + "或练习接受对方的选择。");
            }
            // 第二组：现实人身危险类 → 安全指引
            if (containsAny(stripNegatedDanger(text), DANGER_TERMS)) {
                return new Decision(false, "REAL_WORLD_DANGER",
                        "这首先是安全问题，不适合继续生成行动建议。\n"
                                + "如果危险正在发生，请立即离开可能受伤的环境，联系可信任的人，"
                                + "并联系当地紧急服务或专业援助。不要独自与威胁者对峙。",
                        List.of(item.title()));
            }
            // 第三组：心理诊断类
            if (containsAny(text, DIAGNOSIS_TERMS)) {
                return refusal(item, blockedTitles, "DIAGNOSIS_REQUEST",
                        "仅凭这些信息不能判断或诊断对方的人格、心理状态或疾病。\n"
                                + "计划可以改为关注可观察的行为：发生了什么、频率如何、对你有什么影响、"
                                + "你需要设置什么边界。");
            }
            // 第四组：消息行动内容审核
            if (item.kind() == ExecutionKind.MESSAGE
                    && containsAny(cleaned, MESSAGE_ABUSE_TERMS)) {
                return refusal(item, blockedTitles, "MESSAGE_ABUSE",
                        "这条消息草稿包含威胁、恐吓或贬低性表达，不能进入计划。\n"
                                + "可以改为：陈述事实与感受（“当……发生时，我感到……”），"
                                + "再提出一个具体、可执行的请求。");
            }
            blockedTitles.add(item.title());
        }
        return new Decision(true, "SAFE", "计划草案已通过安全检查。", List.of());
    }

    /** 构造一条整单拒绝的决策，附带当前已检查条目标题（供审计展示） */
    private Decision refusal(
            DraftItem item, List<String> checkedTitles, String reasonCode, String message) {
        List<String> titles = new ArrayList<>(checkedTitles);
        titles.add(item.title());
        return new Decision(false, reasonCode, message, titles);
    }

    /** 把条目字段平铺为小写检查文本（title + instruction + payload 标量值） */
    private String flatten(DraftItem item) {
        StringBuilder text = new StringBuilder();
        if (item.title() != null) text.append(item.title()).append(' ');
        if (item.instruction() != null) text.append(item.instruction()).append(' ');
        if (item.payload() != null) {
            for (Object value : item.payload().values()) {
                if (value == null) continue;
                // 列表值是结构化元数据（如消息的"禁用表达"清单、观察的"记录字段"定义），
                // 其内容是对行为边界的描述而非实际操作内容，不参与安全关键词审查；
                // 例如"禁用表达：威胁、辱骂"中的"威胁"是提醒，不应被当作消息正文命中。
                if (value instanceof Collection<?>) continue;
                text.append(value).append(' ');
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    /** 消去"否定 + 违禁"片段，避免用户表达"不想被监控"时误判为监控计划 */
    private String stripNegatedDisallowed(String text) {
        return NEGATED_DISALLOWED.matcher(text).replaceAll("");
    }

    /** 消去"不会自杀/没有自伤/不是家暴/没有暴力"等明确否定的危险表达 */
    private String stripNegatedDanger(String text) {
        return text.replace("不会自杀", "")
                .replace("没有自伤", "")
                .replace("没有家暴", "")
                .replace("不是家暴", "")
                .replace("没有暴力", "")
                .replace("不想伤害", "");
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private boolean containsAny(String text, List<String> terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
