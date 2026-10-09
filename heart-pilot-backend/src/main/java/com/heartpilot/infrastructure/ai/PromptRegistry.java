package com.heartpilot.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 从 classpath 加载带版本元数据的提示词文件，并对外暴露不可变的提示词模板。 */
@Component
public class PromptRegistry {
    /** 按提示词名称缓存已加载的模板，枚举作 key 保证类型安全。 */
    private final Map<PromptName, PromptTemplate> prompts = new EnumMap<>(PromptName.class);

    /** 启动时注册各场景提示词文件路径。 */
    public PromptRegistry() {
        register(PromptName.CLASSIFIER, "prompts/classifier/v1.md");
        register(PromptName.ANSWER, "prompts/answer/v1.md");
        register(PromptName.CITATION, "prompts/citation/v1.md");
        register(PromptName.SAFETY, "prompts/safety/v1.md");
    }

    /** 按名称取提示词模板，未注册时返回 null。 */
    public PromptTemplate get(PromptName name) {
        return prompts.get(name);
    }

    /** 返回所有已加载提示词的 key -> 版本号映射，便于上线后核对生效版本。 */
    public Map<String, String> versions() {
        Map<String, String> result = new LinkedHashMap<>();
        prompts.forEach((name, prompt) -> result.put(name.key, prompt.version()));
        return result;
    }

    /** 读取 classpath 上的提示词文件，解析元数据后缓存；加载失败直接抛出，避免带病启动。 */
    private void register(PromptName name, String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            prompts.put(name, parse(path, raw));
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Prompt: " + path, exception);
        }
    }

    /** 解析带 YAML Front Matter 的提示词文件：--- 之间为 name/version/output_schema 等元数据，其后为正文。 */
    private PromptTemplate parse(String path, String raw) {
        if (!raw.startsWith("---")) throw new IllegalStateException("Prompt 缺少元数据: " + path);
        int end = raw.indexOf("\n---", 3);
        if (end < 0) throw new IllegalStateException("Prompt 元数据未闭合: " + path);
        Map<String, String> metadata = new LinkedHashMap<>();
        // 逐行解析 "key: value" 形式的元数据
        raw.substring(3, end)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && line.contains(":"))
                .forEach(
                        line -> {
                            int split = line.indexOf(':');
                            metadata.put(
                                    line.substring(0, split).strip(),
                                    line.substring(split + 1).strip());
                        });
        String name = required(metadata, "name", path);
        String version = required(metadata, "version", path);
        String schema = required(metadata, "output_schema", path);
        // Front Matter 结束标记之后即为提示词正文
        String body = raw.substring(end + 4).strip();
        if (body.isBlank()) throw new IllegalStateException("Prompt 正文为空: " + path);
        return new PromptTemplate(name, version, schema, body);
    }

    /** 取必填元数据项，缺失或为空时直接报错。 */
    private String required(Map<String, String> metadata, String key, String path) {
        String value = metadata.get(key);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Prompt 缺少 " + key + ": " + path);
        return value;
    }

    /** 提示词场景枚举，key 为对外暴露的简短标识。 */
    public enum PromptName {
        /** 首轮会话分类提示词。 */
        CLASSIFIER("classifier"),
        /** 生成正式回答的提示词。 */
        ANSWER("answer"),
        /** 引用校验与改写提示词。 */
        CITATION("citation"),
        /** 安全风险识别提示词。 */
        SAFETY("safety");

        /** 对外（版本映射、监控等）使用的简短 key。 */
        private final String key;

        PromptName(String key) {
            this.key = key;
        }
    }

    /** 一个提示词模板：名称、版本号、期望输出的 JSON Schema 与提示词正文，加载后不可变。 */
    public record PromptTemplate(String name, String version, String outputSchema, String body) {}
}
