package com.heartpilot.module.file.service.impl;

import com.heartpilot.module.file.service.StorageService;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageServiceImpl implements StorageService {
    private final Path root;

    public LocalStorageServiceImpl(@Value("${app.storage.local-directory}") String dir)
            throws IOException {
        root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public StoredObject store(MultipartFile f, String prefix) throws IOException {
        return store(f.getBytes(), safe(f.getOriginalFilename()), f.getContentType(), prefix);
    }

    public StoredObject store(byte[] data, String name, String type, String prefix)
            throws IOException {
        String key = safe(prefix) + "/" + UUID.randomUUID() + "-" + safe(name);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("非法文件路径");
        Files.createDirectories(target.getParent());
        Files.write(target, data, StandardOpenOption.CREATE_NEW);
        return new StoredObject(key, data.length, type, name);
    }

    public byte[] read(String key) throws IOException {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IOException("非法文件路径");
        return Files.readAllBytes(p);
    }

    public void delete(String key) throws IOException {
        Path p = root.resolve(key).normalize();
        if (p.startsWith(root)) Files.deleteIfExists(p);
    }

    private String safe(String s) {
        String v = s == null ? "file" : s.replaceAll("[^\\p{L}\\p{N}._/-]", "_").replace("..", "_");
        return v.isBlank() ? "file" : v;
    }
}
