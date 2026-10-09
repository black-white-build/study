package com.heartpilot.infrastructure.ai;

import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 在答案输出前校验引用是否真实存在、证据强度是否足够，并做轻量词法支撑校验与自动修复。 */
@Component
public class CitationValidator {
    /** 匹配答案中的句内引用标记，如 [来源 1]，捕获来源编号。 */
    private static final Pattern CITATION = Pattern.compile("\\[来源\\s*(\\d+)]");

    /** 校验答案全文的引用：替换掉无效或证据不足的引用，统计引用与知识主张数量，并给出整体通过/修复/降级状态。 */
    public Result validate(String answer, List<KnowledgeService.Source> sources) {
        String value = answer == null ? "" : answer;
        Matcher matcher = CITATION.matcher(value);
        StringBuffer repaired = new StringBuffer();
        Set<Integer> used = new LinkedHashSet<>();
        List<Issue> issues = new ArrayList<>();
        int total = 0;
        int supportedCitations = 0;
        while (matcher.find()) {
            total++;
            int number = Integer.parseInt(matcher.group(1));
            // 截取该引用所在的整句话，作为待核验的主张文本
            String claim = surroundingSentence(value, matcher.start());
            String replacement = matcher.group();
            if (number < 1 || number > sources.size()) {
                // 引用编号越界：来源不存在
                issues.add(new Issue(number, "SOURCE_NOT_FOUND", claim));
                replacement = "（该表述暂无可核验来源）";
            } else {
                KnowledgeService.Source source = sources.get(number - 1);
                if (source.evidenceLevel() == KnowledgeEvidenceLevel.LOW
                        || source.evidenceLevel() == KnowledgeEvidenceLevel.UNVERIFIED) {
                    // 证据强度过低：降级为一般性建议
                    issues.add(new Issue(number, "EVIDENCE_TOO_LOW", claim));
                    replacement = "（该表述仅作一般性建议）";
                } else if (!supports(claim, source.content())) {
                    // 词法上无法在来源内容中找到支撑
                    issues.add(new Issue(number, "CONTENT_NOT_SUPPORTED", claim));
                    replacement = "（该表述暂无充分依据，仅作一般性建议）";
                } else {
                    // 引用有效：记录已使用来源并计入受支撑引用数
                    used.add(number);
                    supportedCitations++;
                }
            }
            matcher.appendReplacement(repaired, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(repaired);
        String repairedAnswer = repaired.toString();
        // 完全没有来源时，在末尾统一追加免责说明
        if (sources.isEmpty() && !repairedAnswer.contains("当前知识库没有可靠依据")) {
            repairedAnswer += "\n\n当前知识库没有可靠依据；未引用内容仅作为一般性沟通建议。";
        }
        // 全部无问题则通过；有修复项但仍有有效引用则修复后放行；一个有效引用都没有则降级
        Status status =
                issues.isEmpty()
                        ? Status.PASSED
                        : (used.isEmpty() ? Status.DEGRADED : Status.REPAIRED);
        return new Result(
                repairedAnswer,
                List.copyOf(used),
                total,
                Math.max(total, countKnowledgeClaims(value)),
                supportedCitations,
                issues,
                status);
    }

    /** 粗略统计答案中带有知识断言语气的句子数量，用于后续评估引用覆盖率。 */
    private int countKnowledgeClaims(String answer) {
        int count = 0;
        for (String sentence : answer.split("[。！？!?\\n]+")) {
            if (containsAny(
                    sentence, "研究", "数据显示", "通常", "往往", "一定", "表明", "证明", "原则", "有助于", "会导致",
                    "意味着")) count++;
        }
        return count;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    /** 基于二元字组（bigram）词法重叠，粗判主张是否能在来源内容中找到支撑。 */
    private boolean supports(String claim, String source) {
        Set<String> claimTerms = bigrams(clean(claim));
        Set<String> sourceTerms = bigrams(clean(source));
        claimTerms.retainAll(sourceTerms);
        return !claimTerms.isEmpty();
    }

    /** 取引用标记所在位置之前的最后一个句号/换行，到下一个句号之间的句子，即该引用所支撑的那句话。 */
    private String surroundingSentence(String value, int citationStart) {
        int start =
                Math.max(
                        value.lastIndexOf('。', citationStart),
                        value.lastIndexOf('\n', citationStart));
        int end = value.indexOf('。', citationStart);
        return value.substring(start < 0 ? 0 : start + 1, end < 0 ? citationStart : end).strip();
    }

    /** 去除引用标记与标点符号，只保留字母数字字符，便于做词法比对。 */
    private String clean(String value) {
        return value.replaceAll("\\[来源\\s*\\d+]", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 把字符串切成相邻两字组成的二元组集合（对中文检索友好）。 */
    private Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++)
            result.add(value.substring(index, index + 2));
        return result;
    }

    /** 校验整体状态：全部引用有效 / 修复后放行 / 无任何有效引用需降级。 */
    public enum Status {
        /** 所有引用均通过校验，无需修复。 */
        PASSED,
        /** 存在问题但仍有有效引用，已就地修复后放行。 */
        REPAIRED,
        /** 一个有效引用都没有，答案需整体降级为一般性建议。 */
        DEGRADED
    }

    /** 单条引用存在的问题：来源编号、问题原因码、对应主张句子。 */
    public record Issue(int sourceNumber, String reason, String claim) {}

    /** 校验结果：修复后答案、实际使用的来源编号、各类计数与问题明细。 */
    public record Result(
            String answer,
            List<Integer> usedSourceNumbers,
            int totalCitations,
            int knowledgeClaimCount,
            int supportedCitedClaimCount,
            List<Issue> issues,
            Status status) {}
}
