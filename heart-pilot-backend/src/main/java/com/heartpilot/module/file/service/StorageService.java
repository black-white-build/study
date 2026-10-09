package com.heartpilot.module.file.service;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

/** 文件存储服务抽象。 屏蔽底层存储实现差异（本地磁盘 / MinIO 对象存储），业务层只面向此接口编程。 上传时生成带 UUID 的存储键避免重名，下载/删除均通过该键定位对象。 */
public interface StorageService {
    /**
     * 上传 multipart 表单文件。
     *
     * @param file 上传的文件
     * @param prefix 存储前缀（业务目录），如 agent-task
     * @return 存储结果（存储键、大小、类型、原始文件名）
     */
    StoredObject store(MultipartFile file, String prefix) throws IOException;

    /**
     * 上传字节数组（用于服务端生成的内容，如内存中渲染的 PDF）。
     *
     * @param data 文件字节内容
     * @param name 原始文件名
     * @param contentType 文件 MIME 类型
     * @param prefix 存储前缀（业务目录）
     */
    StoredObject store(byte[] data, String name, String contentType, String prefix)
            throws IOException;

    /** 按存储键读取文件全部字节 */
    byte[] read(String key) throws IOException;

    /** 按存储键删除对象，对象不存在时静默忽略 */
    void delete(String key) throws IOException;

    /**
     * 存储结果记录。
     *
     * @param key 对象存储键（后续 read/delete 凭此定位）
     * @param size 文件大小（字节）
     * @param contentType MIME 类型
     * @param fileName 原始文件名
     */
    record StoredObject(String key, long size, String contentType, String fileName) {}
}
