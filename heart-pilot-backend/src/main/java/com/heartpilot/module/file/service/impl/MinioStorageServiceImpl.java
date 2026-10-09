package com.heartpilot.module.file.service.impl;

import com.heartpilot.module.file.service.StorageService;
import io.minio.*;
import io.minio.errors.*;
import java.io.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * MinIO 对象存储实现。 当配置 app.storage.provider=minio 时生效，文件存入 MinIO（S3 兼容对象存储）。 启动时检查 bucket
 * 是否存在，不存在则自动创建。
 */
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "minio")
public class MinioStorageServiceImpl implements StorageService {
    /** MinIO 客户端 */
    private final MinioClient client;

    /** 存储桶名称，来自配置 app.storage.minio.bucket */
    private final String bucket;

    /**
     * 构造 MinIO 客户端并确保 bucket 存在。
     *
     * @param endpoint MinIO 服务地址
     * @param access 访问密钥
     * @param secret 私有密钥
     * @param bucket 存储桶名
     */
    public MinioStorageServiceImpl(
            @Value("${app.storage.minio.endpoint}") String endpoint,
            @Value("${app.storage.minio.access-key}") String access,
            @Value("${app.storage.minio.secret-key}") String secret,
            @Value("${app.storage.minio.bucket}") String bucket)
            throws Exception {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
        this.bucket = bucket;
        // bucket 不存在则自动创建，避免首次部署手动建桶
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    /**
     * 上传表单文件：转字节后委托给字节上传方法。
     *
     * @param f 上传的文件
     * @param prefix 存储前缀（业务目录）
     */
    public StoredObject store(MultipartFile f, String prefix) throws IOException {
        return store(f.getBytes(), f.getOriginalFilename(), f.getContentType(), prefix);
    }

    /**
     * 上传字节数组到 MinIO。 存储键 = prefix/UUID-文件名，UUID 前缀避免重名；文件名中的 "/" 替换为下划线防止路径异常。
     *
     * @param data 文件字节内容
     * @param name 原始文件名
     * @param type 文件 MIME 类型
     * @param prefix 存储前缀（业务目录）
     */
    public StoredObject store(byte[] data, String name, String type, String prefix)
            throws IOException {
        String key =
                prefix
                        + "/"
                        + UUID.randomUUID()
                        + "-"
                        + (name == null ? "file" : name.replace("/", "_"));
        try {
            // partSize=-1 表示由 SDK 根据流长度自动分片
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                    new ByteArrayInputStream(data), data.length, -1)
                            .contentType(type)
                            .build());
            return new StoredObject(key, data.length, type, name);
        } catch (Exception e) {
            // MinIO 受检异常统一包装为 IOException，与 StorageService 接口签名一致
            throw new IOException(e);
        }
    }

    /**
     * 按存储键从 MinIO 读取对象全部字节。
     *
     * @param key 对象存储键
     */
    public byte[] read(String key) throws IOException {
        // try-with-resources 自动关闭 GetObject 流
        try (var in =
                client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * 按存储键删除 MinIO 对象。
     *
     * @param key 对象存储键
     */
    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
