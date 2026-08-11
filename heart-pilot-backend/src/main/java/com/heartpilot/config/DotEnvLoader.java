package com.heartpilot.config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 为本地开发加载项目根目录下的 .env。 已存在的 JVM 参数或系统环境变量优先，不会被 .env 覆盖。 */
public final class DotEnvLoader {

    private DotEnvLoader() {}

    public static void load() {
        Path envFile = findEnvFile();
        if (envFile == null) {
            System.out.println(
                    "Local .env was not found. Working directory: "
                            + Path.of("").toAbsolutePath().normalize());
            return;
        }

        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                loadLine(line);
            }
            System.out.println("Loaded local configuration from " + envFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local .env file: " + envFile, e);
        }
    }

    private static Path findEnvFile() {
        // Maven、命令行或 IDEA 将工作目录设置为模块根目录时。
        Path currentDirectoryEnv = Path.of(".env").toAbsolutePath().normalize();
        if (Files.isRegularFile(currentDirectoryEnv)) {
            return currentDirectoryEnv;
        }

        // 根据 target/classes 或可执行 JAR 所在位置向上查找项目根目录。
        try {
            Path codeLocation =
                    Path.of(
                                    DotEnvLoader.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toAbsolutePath()
                            .normalize();
            Path directory =
                    Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            for (int depth = 0; directory != null && depth < 6; depth++) {
                Path candidate = directory.resolve(".env");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                directory = directory.getParent();
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // 未能从代码位置定位时，由上方诊断日志提示当前工作目录。
        }
        return null;
    }

    private static void loadLine(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).strip();
        }

        int separatorIndex = line.indexOf('=');
        if (separatorIndex <= 0) {
            return;
        }

        String key = line.substring(0, separatorIndex).strip();
        String value = stripQuotes(line.substring(separatorIndex + 1).strip());
        if (key.isEmpty() || value.isEmpty()) {
            return;
        }

        if (System.getProperty(key) == null && System.getenv(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
