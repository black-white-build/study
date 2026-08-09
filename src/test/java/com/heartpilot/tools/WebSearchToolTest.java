package com.heartpilot.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WebSearchToolTest {

    // Spring容器自动注入装配完成的WebSearchTool（自带apiKey+WebScrapingTool）
    @Resource private WebSearchTool webSearchTool;

    @Test
    void searchWeb() {
        String query = "上海周末情侣约会地点";
        String result = webSearchTool.searchWeb(query);
        assertNotNull(result);
        System.out.println("搜索结果：" + result);
    }
}
