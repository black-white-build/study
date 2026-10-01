package com.heartpilot.module.knowledge.service;

import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 解析知识库自带 Markdown 文件顶部极简 YAML 前置元数据（front matter）。 */
@Component
public class KnowledgeMarkdownParser {
    /** 读取 Markdown 文件，解析前置元数据与正文，组装为 ParsedDocument。 */
    public ParsedDocument parse(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (!raw.startsWith("---")) throw new IllegalArgumentException(path + " 缺少 Markdown 元数据");
        int end = raw.indexOf("\n---", 3);
        if (end < 0) throw new IllegalArgumentException(path + " 的 Markdown 元数据未闭合");
        // 逐行解析 key: value 形式的前置元数据
        Map<String, String> values = new LinkedHashMap<>();
        raw.substring(3, end)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && line.contains(":"))
                .forEach(
                        line -> {
                            int split = line.indexOf(':');
                            values.put(
                                    line.substring(0, split).strip(),
                                    line.substring(split + 1).strip());
                        });
        String body = raw.substring(end + 4).strip();
        if (body.isBlank()) throw new IllegalArgumentException(path + " 正文为空");
        // 组装文档元数据：必填项缺失即报错，可选项带默认值
        KnowledgeService.DocumentMetadata metadata =
                new KnowledgeService.DocumentMetadata(
                        required(values, "category", path),
                        value(values, "applicable_scenario", "通用沟通"),
                        value(values, "relationship_stage", "通用"),
                        required(values, "source", path),
                        values.get("source_url"),
                        required(values, "version", path),
                        KnowledgeReviewStatus.valueOf(value(values, "review_status", "APPROVED")),
                        values.get("risk_tags"),
                        KnowledgeEvidenceLevel.valueOf(required(values, "confidence", path)));
        return new ParsedDocument(
                required(values, "document_id", path),
                required(values, "title", path),
                path,
                raw,
                body,
                metadata,
                sha256(raw));
    }

    /** 读取必填元数据项，缺失或为空时抛出带文件路径的异常。 */
    private String required(Map<String, String> values, String key, Path path) {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(path + " 缺少 " + key);
        return value;
    }

    /** 读取可选元数据项，缺失或为空时返回默认值。 */
    private String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 计算全文内容的 SHA-256 十六进制摘要，用于检测文件是否变更。 */
    private String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    /** 解析后的知识库文档：原始全文、正文与元数据一并携带，便于后续切片入库。 */
    public record ParsedDocument(
            String documentId,
            String title,
            Path path,
            String raw,
            String body,
            KnowledgeService.DocumentMetadata metadata,
            String contentHash) {}
}
