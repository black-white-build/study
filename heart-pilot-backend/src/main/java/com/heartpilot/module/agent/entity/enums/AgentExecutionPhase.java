package com.heartpilot.module.agent.entity.enums;

/** Agent 任务执行阶段枚举，描述任务从开始到完成的宏观阶段划分。 */
public enum AgentExecutionPhase {
    /** 分析用户需求 */
    ANALYZE,
    /** 检索地点/信息 */
    SEARCH,
    /** 筛选与整理候选结果 */
    FILTER,
    /** 规划出行路线 */
    ROUTE,
    /** 生成最终行动报告 */
    GENERATE,
    /** 任务完成 */
    COMPLETE
}
