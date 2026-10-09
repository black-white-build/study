package com.heartpilot.module.file.service.impl;

import com.heartpilot.module.file.service.StorageService;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地磁盘文件存储实现。 当配置 app.storage.provider=local（或缺省）时生效，文件写入配置的本地目录。 适合开发/单机部署；生产环境建议切换到 MinIO。
 * 安全要点：每次读写都规范化路径并校验是否落在根目录内，防止路径穿越攻击。
 */
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageServiceImpl implements StorageService {
    /** 存储根目录，来自配置 app.storage.local-directory */
    private final Path root;

    /** 初始化时确保根目录存在 */
    public LocalStorageServiceImpl(@Value("${app.storage.local-directory}") String dir)
            throws IOException {
        root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /** 上传表单文件：转字节后委托给字节上传方法 */
    public StoredObject store(MultipartFile f, String prefix) throws IOException {
        return store(f.getBytes(), safe(f.getOriginalFilename()), f.getContentType(), prefix);
    }

    /** 上传字节数组。 存储键 = 安全前缀/UUID-安全文件名，UUID 前缀避免文件名冲突。 CREATE_NEW 选项保证文件不存在才写入，防止覆盖。 */
    public StoredObject store(byte[] data, String name, String type, String prefix)
            throws IOException {
        String key = safe(prefix) + "/" + UUID.randomUUID() + "-" + safe(name);
        Path target = root.resolve(key).normalize();
        // 路径穿越防护：解析后的真实路径必须仍在根目录下
        if (!target.startsWith(root)) throw new IOException("非法文件路径");
        Files.createDirectories(target.getParent());
        Files.write(target, data, StandardOpenOption.CREATE_NEW);
        return new StoredObject(key, data.length, type, name);
    }

    /** 按存储键读取文件字节，同样做路径穿越校验 */
    public byte[] read(String key) throws IOException {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IOException("非法文件路径");
        return Files.readAllBytes(p);
    }

    /** 按存储键删除文件，存在才删 */
    public void delete(String key) throws IOException {
        Path p = root.resolve(key).normalize();
        if (p.startsWith(root)) Files.deleteIfExists(p);
    }

    /** 清洗文件名/前缀：保留字母数字 . _ / -，其余替换为下划线； 同时把 ".." 替换掉，杜绝目录穿越。空值兜底为 "file"。 */
    private String safe(String s) {
        String v = s == null ? "file" : s.replaceAll("[^\\p{L}\\p{N}._/-]", "_").replace("..", "_");
        return v.isBlank() ? "file" : v;
    }
}
