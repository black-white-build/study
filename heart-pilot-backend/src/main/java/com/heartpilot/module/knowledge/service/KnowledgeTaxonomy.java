package com.heartpilot.module.knowledge.service;

import com.heartpilot.common.exception.ApiException;
import java.util.List;

/** 稳定的产品级知识分类，上传与检索过滤共用。 */
public final class KnowledgeTaxonomy {
    /** 产品允许的固定知识分类白名单，新增分类需同步此处与前端选项。 */
    public static final List<String> CATEGORIES =
            List.of("沟通基础", "冲突与修复", "边界与同意", "关系阶段", "分手与结束关系", "数字沟通", "行动设计", "风险与安全");

    private KnowledgeTaxonomy() {}

    /** 校验并返回合法分类；为空或不在白名单内时抛 400。 */
    public static String requireCategory(String value) {
        String category = value == null ? "" : value.strip();
        if (!CATEGORIES.contains(category)) throw ApiException.badRequest("知识分类不在允许范围内");
        return category;
    }
}
