package com.heartpilot.service;

import io.minio.*;
import io.minio.errors.*;
import java.io.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "minio")
public class MinioStorageService implements StorageService {
    private final MinioClient client;
    private final String bucket;

    public MinioStorageService(
            @Value("${app.storage.minio.endpoint}") String endpoint,
            @Value("${app.storage.minio.access-key}") String access,
            @Value("${app.storage.minio.secret-key}") String secret,
            @Value("${app.storage.minio.bucket}") String bucket)
            throws Exception {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
        this.bucket = bucket;
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    public StoredObject store(MultipartFile f, String prefix) throws IOException {
        return store(f.getBytes(), f.getOriginalFilename(), f.getContentType(), prefix);
    }

    public StoredObject store(byte[] data, String name, String type, String prefix)
            throws IOException {
        String key =
                prefix
                        + "/"
                        + UUID.randomUUID()
                        + "-"
                        + (name == null ? "file" : name.replace("/", "_"));
        try {
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                    new ByteArrayInputStream(data), data.length, -1)
                            .contentType(type)
                            .build());
            return new StoredObject(key, data.length, type, name);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public byte[] read(String key) throws IOException {
        try (var in =
                client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
