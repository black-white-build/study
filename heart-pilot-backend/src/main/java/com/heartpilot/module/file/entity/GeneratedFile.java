package com.heartpilot.module.file.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "generated_file",
        indexes = @Index(name = "idx_file_user", columnList = "userId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class GeneratedFile extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false, length = 500)
    private String storageKey;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 32)
    private String businessType;

    private Long businessId;
}
