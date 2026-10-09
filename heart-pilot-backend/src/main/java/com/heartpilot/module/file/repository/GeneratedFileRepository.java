package com.heartpilot.module.file.repository;

import com.heartpilot.module.file.entity.GeneratedFile;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 已生成文件（GeneratedFile）数据访问层。 */
public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, Long> {
    /** 分页查询某用户指定业务类型的文件列表 */
    Page<GeneratedFile> findByUserIdAndBusinessType(
            Long userId, String businessType, Pageable pageable);

    /** 按 ID + 用户 + 业务类型查询文件，用于下载鉴权与能力隔离 */
    Optional<GeneratedFile> findByIdAndUserIdAndBusinessType(
            Long id, Long userId, String businessType);

    /** 取某用户某业务最新的一条文件记录（如任务最新 PDF） */
    Optional<GeneratedFile> findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
            Long userId, String businessType, Long businessId);

    /** 列出某用户某业务关联的全部文件，删除业务时级联清理用 */
    List<GeneratedFile> findByUserIdAndBusinessTypeAndBusinessId(
            Long userId, String businessType, Long businessId);
}
