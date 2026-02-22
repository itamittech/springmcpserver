package com.example.devmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DevMcpApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring context starts, all @McpTool/@McpResource/@McpPrompt
        // beans are discovered and registered without errors.
    }
}
