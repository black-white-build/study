package com.heartpilot.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回放改造前的关键词安全门禁与空实现引用行为，在固定集上复现历史基线指标作为对照。 */
class LegacyBaselineReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 用旧逻辑重跑分验收集，得到的四项指标须与签入的 baseline-v0.json 完全一致。 */
    @Test
    void checkedInBaselineIsReproducible() throws Exception {
        JsonNode expectedBaseline =
                json.readTree(Files.readString(Path.of("../eval/baseline-v0.json")));
        List<JsonNode> safetyCases = read("../eval/safety-cases.jsonl");
        int positives = 0;
        int truePositives = 0;
        int negatives = 0;
        int falsePositives = 0;
        for (JsonNode item : safetyCases) {
            String expected = item.get("expected").asText();
            String actual = legacySafety(item.get("input").asText());
            if ("SAFETY".equals(expected)) {
                positives++;
                if ("SAFETY".equals(actual)) truePositives++;
            } else {
                negatives++;
                if ("SAFETY".equals(actual)) falsePositives++;
            }
        }
        List<JsonNode> citationCases = read("../eval/citation-cases.jsonl");
        long invalidCitations =
                citationCases.stream()
                        .filter(item -> !item.get("expectedValid").asBoolean())
                        .count();
        double recall = truePositives / (double) positives;
        double falsePositiveRate = falsePositives / (double) negatives;
        double falseCitationRate = invalidCitations / (double) citationCases.size();
        double citationCoverage =
                citationCases.stream().filter(item -> item.get("expectedValid").asBoolean()).count()
                        / (double) citationCases.size();
        System.out.printf(
                "Legacy baseline: safetyRecall=%.3f safetyFpr=%.3f citationCoverage=%.3f falseCitationRate=%.3f noResultFallback=0.000%n",
                recall, falsePositiveRate, citationCoverage, falseCitationRate);
        assertEquals(expectedBaseline.get("safetyRecall").asDouble(), recall, 0.0001);
        assertEquals(
                expectedBaseline.get("safetyFalsePositiveRate").asDouble(),
                falsePositiveRate,
                0.0001);
        assertEquals(
                expectedBaseline.get("falseCitationRate").asDouble(), falseCitationRate, 0.0001);
        assertEquals(expectedBaseline.get("citationCoverage").asDouble(), citationCoverage, 0.0001);
    }

    /** 改造前的关键词匹配安全判定（仅用于基线回放，不代表现网逻辑）。 */
    private String legacySafety(String input) {
        if (containsAny(input, "自杀", "自伤", "杀了", "打死", "家暴", "暴力", "持刀", "威胁", "强迫"))
            return "SAFETY";
        if (containsAny(
                input, "pua", "让他离不开", "让她离不开", "监控", "跟踪", "尾随", "纠缠", "冒充", "偷看手机", "代聊骗"))
            return "REFUSAL";
        if (containsAny(input, "人格障碍", "自恋型人格", "是不是精神病", "心理疾病", "给他诊断", "给她诊断")) return "REFUSAL";
        return "CONTINUE";
    }

    /** 基线回放用：忽略大小写判断文本是否包含任一关键词。 */
    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.toLowerCase().contains(value)) return true;
        return false;
    }

    /** 按行读取 JSONL 验收集，跳过空行后逐条解析为 JsonNode。 */
    private List<JsonNode> read(String relativePath) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(relativePath))) {
            if (!line.isBlank()) result.add(json.readTree(line));
        }
        return result;
    }
}
