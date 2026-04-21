package com.hmdp.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hmdp.agent.dashscope")
public class AgentDashScopeProperties {

    /**
     * 通义百炼 / DashScope OpenAI 兼容端点。
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * API Key；由 {@code application.yaml} 注入，默认优先环境变量 {@code LLM_API_KEY}，其次 {@code DASHSCOPE_API_KEY}。
     */
    private String apiKey = "";

    private String modelName = "qwen-turbo";

    private int memoryTtlMinutes = 30;

    private int maxMessages = 20;

    /**
     * 是否为 AiServices 注册 {@link com.hmdp.agent.tools.ShopAgentTools}。
     * 部分端点/模型不支持 tools，会报 “Tools are currently not supported by this model”；需要工具时设为 true。
     */
    private boolean toolsEnabled = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getMemoryTtlMinutes() {
        return memoryTtlMinutes;
    }

    public void setMemoryTtlMinutes(int memoryTtlMinutes) {
        this.memoryTtlMinutes = memoryTtlMinutes;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public boolean isToolsEnabled() {
        return toolsEnabled;
    }

    public void setToolsEnabled(boolean toolsEnabled) {
        this.toolsEnabled = toolsEnabled;
    }
}
