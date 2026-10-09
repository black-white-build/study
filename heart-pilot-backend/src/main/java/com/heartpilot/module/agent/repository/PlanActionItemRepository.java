package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.PlanActionItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 计划行动条目（PlanActionItem）数据访问层。 条目归属计划版本（versionId），并冗余 planId 便于按计划直接查询。 */
public interface PlanActionItemRepository extends JpaRepository<PlanActionItem, Long> {
    /** 按版本列出条目，保持用户调整后的顺序 */
    List<PlanActionItem> findByVersionIdOrderBySequenceNoAsc(Long versionId);

    /** 按计划列出最新版本的条目顺序（冗余查询用） */
    List<PlanActionItem> findByPlanIdOrderBySequenceNoAsc(Long planId);

    /** 删除版本时级联清理条目 */
    void deleteByVersionId(Long versionId);

    /** 删除计划时级联清理条目 */
    void deleteByPlanId(Long planId);
}
