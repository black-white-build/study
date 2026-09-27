package com.heartpilot.infrastructure.ai.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 工具注册配置类。
 * 组装一组经过安全白名单过滤的 ToolCallback，供自主规划智能体（Agent）调用。
 * 包含两类工具：本地 @Tool 注解工具（网页搜索、终止），以及外部 MCP Server 暴露的工具。
 */
@Configuration
public class ToolRegistration {

    /**
     * MCP 工具名白名单关键词（小写匹配）。
     * 只放行与地图、地点和路线检索相关的工具，避免外部 MCP Server 暴露的危险工具被 Agent 误调用。
     */
    private static final Set<String> ALLOWED_MCP_NAME_PARTS =
            Set.of("map", "amap", "geo", "route", "poi", "search");

    /**
     * 组装并注册"安全版" Agent 工具集合。
     *
     * @param webSearchTool 网页搜索工具
     * @param terminateTool 任务终止工具
     * @param mcpProvider   MCP 工具回调提供者（可选，未配置 MCP 时为 null）
     * @return 过滤后的 ToolCallback 数组
     */
    @Bean("safeAgentTools")
    public ToolCallback[] safeAgentTools(
            WebSearchTool webSearchTool,
            TerminateTool terminateTool,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        List<ToolCallback> callbacks =
                new ArrayList<>(Arrays.asList(ToolCallbacks.from(webSearchTool, terminateTool)));
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            // MCP 工具逐个过白名单，只放行地图/检索类安全工具
            Arrays.stream(provider.getToolCallbacks())
                    .filter(this::isAllowedMcpTool)
                    .forEach(callbacks::add);
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    /**
     * 判断某个 MCP 工具是否在白名单内：工具名（小写）命中任一关键词即放行。
     */
    private boolean isAllowedMcpTool(ToolCallback callback) {
        String name = callback.getToolDefinition().name().toLowerCase(Locale.ROOT);
        return ALLOWED_MCP_NAME_PARTS.stream().anyMatch(name::contains);
    }
}
