package com.heartpilot.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户上下文工具。 从 SecurityContextHolder 中读取 JWT 过滤器写入的认证信息， 供 Controller/Service 获取当前用户 ID
 * 与管理员角色，实现"只能访问自己数据"的数据隔离。
 */
@Component
public class CurrentUser {
    /**
     * 获取当前登录用户 ID。
     *
     * @return 认证主体中保存的用户 ID（JWT 的 subject）
     * @throws org.springframework.security.access.AccessDeniedException 未登录或为匿名用户时抛出
     */
    public Long id() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        // 未认证或匿名用户一律拒绝，避免后续按 null 身份查到越权数据
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName()))
            throw new org.springframework.security.access.AccessDeniedException("请先登录");
        return Long.valueOf(a.getName());
    }

    /**
     * 判断当前用户是否为管理员（是否拥有 ROLE_ADMIN 权限）。
     *
     * @return 是管理员返回 true，否则 false
     */
    public boolean isAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null
                && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
    }
}
