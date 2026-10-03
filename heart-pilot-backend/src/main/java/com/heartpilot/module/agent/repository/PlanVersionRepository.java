package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.enums.PlanVersionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 计划版本（PlanVersion）数据访问层。
 * 同一计划下 versionNo 唯一；每次重规划产生新版本，旧版本只读保留。
 */
public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {
    /** 按计划列出全部版本，最新在前 */
    List<PlanVersion> findByPlanIdOrderByVersionNoDesc(Long planId);

    /** 取某计划的最新版本 */
    Optional<PlanVersion> findFirstByPlanIdOrderByVersionNoDesc(Long planId);

    /** 按状态查询（如当前草稿 / 已确认版本） */
    Optional<PlanVersion> findByPlanIdAndStatus(Long planId, PlanVersionStatus status);

    /** 删除计划时级联清理其全部版本 */
    void deleteByPlanId(Long planId);
}
