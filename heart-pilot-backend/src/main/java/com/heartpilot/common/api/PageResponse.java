package com.heartpilot.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 统一分页响应结构（泛型 record）。 对 Spring Data 的 Page 做一层对外封装，避免直接把持久层分页对象暴露给前端； 同时通过 mapper 完成实体 Entity →
 * 视图对象 VO 的转换，实现分层隔离。
 *
 * @param <T> 列表元素的对外视图类型
 */
public record PageResponse<T>(
        /* 当前页数据列表（已转换为 VO） */
        List<T> content,
        /* 当前页码（从 0 开始） */
        int page,
        /* 每页大小 */
        int size,
        /* 满足条件的总记录数 */
        long totalElements,
        /* 总页数 */
        int totalPages,
        /* 是否为第一页 */
        boolean first,
        /* 是否为最后一页 */
        boolean last) {

    /**
     * 由 Spring Data Page 转换为对外分页响应。
     *
     * @param source 持久层查询得到的分页结果
     * @param mapper 实体到 VO 的转换函数
     * @param <S> 源实体类型
     * @param <T> 目标视图类型
     * @return 包装好的分页响应
     */
    public static <S, T> PageResponse<T> from(Page<S> source, Function<S, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isFirst(),
                source.isLast());
    }
}
