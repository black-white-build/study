package com.heartpilot.tools;

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

@Configuration
public class ToolRegistration {
    private static final Set<String> ALLOWED_MCP_NAME_PARTS =
            Set.of("map", "amap", "geo", "route", "poi", "search", "image", "pexels");

    @Bean("safeAgentTools")
    public ToolCallback[] safeAgentTools(
            WebSearchTool webSearchTool,
            TerminateTool terminateTool,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        List<ToolCallback> callbacks =
                new ArrayList<>(Arrays.asList(ToolCallbacks.from(webSearchTool, terminateTool)));
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            Arrays.stream(provider.getToolCallbacks())
                    .filter(this::isAllowedMcpTool)
                    .forEach(callbacks::add);
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    private boolean isAllowedMcpTool(ToolCallback callback) {
        String name = callback.getToolDefinition().name().toLowerCase(Locale.ROOT);
        return ALLOWED_MCP_NAME_PARTS.stream().anyMatch(name::contains);
    }
}
