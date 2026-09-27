package com.heartpilot.module.file.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 已生成文件实体，对应数据库表 generated_file。
 * 记录由系统生成的文件（如 Agent 任务 PDF）在对象存储中的元数据：
 * 实际文件内容存放在本地磁盘或 MinIO，本记录只保存定位信息与关联业务。
 * 继承 BaseEntity 获得 id、创建时间等公共字段。
 */
@Entity
@Table(
        name = "generated_file",
        indexes = @Index(name = "idx_file_user", columnList = "userId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class GeneratedFile extends BaseEntity {
    /** 文件所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 下载时展示的文件名（原始文件名，可含中文） */
    @Column(nullable = false, length = 255)
    private String fileName;

    /** 文件 MIME 类型，下载时用于设置 Content-Type */
    @Column(nullable = false, length = 100)
    private String contentType;

    /** 对象存储中的键（相对路径），StorageService 据此读写实际字节 */
    @Column(nullable = false, length = 500)
    private String storageKey;

    /** 文件大小（字节） */
    @Column(nullable = false)
    private long sizeBytes;

    /** 业务类型标识（如 AGENT_TASK），区分文件来源模块 */
    @Column(nullable = false, length = 32)
    private String businessType;

    /** 关联的业务记录 ID（如某个 Agent 任务 ID） */
    private Long businessId;
}
