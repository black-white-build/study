package com.heartpilot.module.agent.requirement;

/**
 * 结构化需求类型。
 * 地点见面（PLACE_VISIT）与礼物表达（GIFT_RITUAL）共用同一套
 * 结构化抽取 / 代码校验 / 持久化底层，只是业务实体字段不同。
 */
public enum RequirementType {
    /** 地点见面：抽取起点、时间窗口、出行方式、人数、点位约束 */
    PLACE,
    /** 礼物表达：抽取对象、年龄、场合、预算、风格偏好、禁止品类 */
    GIFT;

    /** 从执行方式名映射需求类型，未知类型返回 null */
    public static RequirementType fromExecutionKind(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "PLACE_VISIT" -> PLACE;
            case "GIFT_RITUAL" -> GIFT;
            default -> null;
        };
    }
}
