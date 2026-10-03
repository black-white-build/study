package com.heartpilot.infrastructure.ai;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 针对那些不能只依赖模型自觉合规的请求，所提供的确定性（规则式）护栏。 */
@Component
public class AnswerSafetyPolicy {
    /**
     * 对用户输入做规则式安全分流：先识别上下文场景（引用/假设/否定等），再按关键词规则
     * 返回拒绝（REFUSAL）、安全指引（SAFETY）或放行模型（CONTINUE）三类决策。
     * 仅作确定性关键词兜底，不替代模型判断。
     */
    public Decision evaluate(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        // 识别用户输入文本所处的上下文场景，返回对应的Context枚举
        Context context = context(text);

        // 第一组规则：边界侵犯类关键词
        if (containsAny(
                        // 去掉否定词
                        stripNegatedDisallowed(text),
                        "pua",
                        "让他离不开",
                        "让她离不开",
                        "监控",
                        "跟踪",
                        "尾随",
                        "纠缠",
                        "冒充",
                        "偷看手机",
                        "代聊骗")
                && context != Context.QUOTED) {
            return Decision.refusal(
                    "我不能帮助操控、纠缠、跟踪、监控、冒充或绕过对方同意。可以改为讨论尊重边界的表达方式、一次清晰的沟通，或如何接受拒绝并安全退出联系。",
                    "BOUNDARY_VIOLATION",
                    context);
        }

        // 第二组规则：现实人身危险类关键词
        if (containsAny(text, "自杀", "自伤", "杀了", "打死", "家暴", "暴力", "持刀", "威胁", "强迫")
                && context != Context.NEGATED
                && context != Context.QUOTED
                && context != Context.HYPOTHETICAL) {
            return Decision.safety(
                    "我听到你提到的情况可能涉及人身安全。如果你或你身边的人正面临即时危险，请立即离开当前环境，联系可信任的人，并拨打当地紧急电话（中国内地可拨 110/120）或心理危机热线（如 400-161-9995）。不要独自与威胁者对峙。\n\n如果你现在安全，愿意的话可以告诉我发生了什么，我会陪你一起梳理。",
                    "REAL_WORLD_DANGER",
                    context);
        }

        // 第三组规则：请求诊断心理/人格疾病
        if (containsAny(text, "人格障碍", "自恋型人格", "是不是精神病", "心理疾病", "给他诊断", "给她诊断")) {
            return Decision.refusal(
                    "仅凭这些信息不能判断或诊断对方的人格、心理状态或疾病。我们可以只看可观察到的行为：发生了什么、频率如何、它对你造成了什么影响，以及你需要设置什么边界。",
                    "DIAGNOSIS_REQUEST",
                    context);
        }
        // 所有规则都没有命中，放行，把请求交给大模型处理，带上上下文标记
        return Decision.continueWithModel(context);
    }

    /**
     * 识别用户输入文本所处的上下文场景，返回对应的Context枚举，给上层安全策略做判断依据
     * @param text 用户原始输入文本
     * @return 上下文类型：NEGATED/QUOTED/HYPOTHETICAL/THIRD_PARTY/REAL
     */
    private Context context(String text) {
        String remainingRisk = stripNegatedRisk(text);
        boolean mentionedNegation = !remainingRisk.equals(text);
        if (mentionedNegation
                && !containsAny(
                        remainingRisk, "自杀", "自伤", "杀了", "打死", "家暴", "暴力", "持刀", "威胁", "强迫"))
            return Context.NEGATED;
        if (containsAny(text, "小说", "电影", "剧里", "台词", "新闻里写", "引用")) return Context.QUOTED;
        if (containsAny(text, "假设", "如果以后", "万一", "如果将来", " hypothetically "))
            return Context.HYPOTHETICAL;
        if (containsAny(text, "朋友说", "同事说", "室友说", "她告诉我", "他告诉我")) return Context.THIRD_PARTY;
        return Context.REAL;
    }

    /**
     * 消去"没有/没/从未/不是/不会/非/不/拒绝/反对"等否定式风险表达（如"没有威胁""不会自杀""非暴力沟通"），
     * 用于判断上下文是否属于 NEGATED——用户明确否定了风险，不应触发安全拦截。
     * 注意先替换更长、更具体的搭配（如"非暴力沟通"），再替换一般性否定搭配，避免残余词被误判。
     */
    private String stripNegatedRisk(String text) {
        return text.replace("非暴力沟通", "")
                .replace("不暴力沟通", "")
                .replace("非暴力", "")
                .replace("不暴力", "")
                .replace("拒绝暴力", "")
                .replace("反对暴力", "")
                .replace("没有威胁", "")
                .replace("没威胁", "")
                .replace("从未威胁", "")
                .replace("不是家暴", "")
                .replace("没有自伤", "")
                .replace("不会自杀", "")
                .replace("没有暴力", "")
                .replace("没有强迫", "");
    }

    /**
     * 否定词 + 违禁词 或 违禁词 + 否定词 两种语序的预编译匹配器。
     * 否定词与违禁词之间最多允许 {0,3} 个汉字；绝不放宽为 .*?，否则会跨句误删大量文本。
     */
    private static final Pattern NEGATED_DISALLOWED =
            Pattern.compile(
                    "(?:不要|不想|不会|别)[\\u4e00-\\u9fa5]{0,3}(?:监控|跟踪|纠缠)"
                            + "|(?:监控|跟踪|纠缠)[\\u4e00-\\u9fa5]{0,3}(?:不要|不想|不会|别)");

    /**
     * 消去"否定 + 违禁"两类语序的片段：既支持否定在前（"不要监控"），也支持违禁词在前、
     * 否定在后（"监控我不想做"）。目的是避免用户表达"不想被监控/不想跟踪"时被第一组规则
     * 误判为违禁请求。仅作轻量规则兜底，复杂长句与多重否定不处理。
     */
    private String stripNegatedDisallowed(String text) {
        return NEGATED_DISALLOWED.matcher(text).replaceAll("");
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    /**
     * 一次安全评估的决策结果：决策类型、需要直接回复给用户的文案（放行时为 null）、
     * 命中的原因码，以及识别出的输入上下文场景。
     */
    public record Decision(Kind kind, String directResponse, String reasonCode, Context context) {
        /** 未命中任何拦截规则，把请求交给大模型继续处理。 */
        public static Decision continueWithModel(Context context) {
            return new Decision(Kind.CONTINUE, null, "NO_BLOCKING_RISK", context);
        }

        /** 命中边界侵犯/诊断等规则，用给定文案直接拒绝作答。 */
        public static Decision refusal(String response, String reasonCode, Context context) {
            return new Decision(Kind.REFUSAL, response, reasonCode, context);
        }

        /** 命中现实人身危险类规则，给出安全指引并引导求助。 */
        public static Decision safety(String response, String reasonCode, Context context) {
            return new Decision(Kind.SAFETY, response, reasonCode, context);
        }

        /** 是否需要由本策略直接回复用户（而非放行给模型）。 */
        public boolean respondsDirectly() {
            return directResponse != null;
        }
    }

    /** 决策类型：放行模型 / 拒绝 / 触发安全指引。 */
    public enum Kind {
        /** 无拦截风险，放行给大模型正常作答。 */
        CONTINUE,
        /** 命中不应提供帮助的请求，直接拒绝。 */
        REFUSAL,
        /** 涉及人身安全风险，直接给出安全指引。 */
        SAFETY
    }

    /** 输入所属上下文场景，用于判断关键词是否应被放行（如引用、假设、否定）。 */
    public enum Context {
        /** 描述真实发生在自己身上的事。 */
        REAL,
        /** 转述他人（朋友/同事等）遇到的情况。 */
        THIRD_PARTY,
        /** 用户明确否定了风险表述（如"没有威胁""不会自杀"）。 */
        NEGATED,
        /** 引用小说/影视/新闻中的台词或情节。 */
        QUOTED,
        /** 假设性、未发生的设想（如"如果以后""万一"）。 */
        HYPOTHETICAL
    }
}
