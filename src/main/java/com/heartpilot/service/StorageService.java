package com.heartpilot.service;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {
    StoredObject store(MultipartFile file, String prefix) throws IOException;

    StoredObject store(byte[] data, String name, String contentType, String prefix)
            throws IOException;

    byte[] read(String key) throws IOException;

    void delete(String key) throws IOException;

    record StoredObject(String key, long size, String contentType, String fileName) {}
}
