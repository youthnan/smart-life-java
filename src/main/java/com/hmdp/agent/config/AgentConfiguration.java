package com.hmdp.agent.config;

import com.hmdp.agent.ShopAssistant;
import com.hmdp.agent.StubDashScopeChatModel;
import com.hmdp.agent.memory.RedisChatMemoryStore;
import com.hmdp.agent.tools.ShopAgentTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(AgentDashScopeProperties.class)
public class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    @Bean
    public ChatLanguageModel dashScopeChatModel(AgentDashScopeProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            return new StubDashScopeChatModel();
        }
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey().trim())
                .modelName(properties.getModelName())
                .temperature(0.2)
                .build();
    }

    @Bean
    public ShopAssistant shopAssistantNoTools(
            ChatLanguageModel dashScopeChatModel,
            RedisChatMemoryStore redisChatMemoryStore,
            ShopAgentTools shopAgentTools,
            AgentDashScopeProperties properties
    ) {
        int max = Math.max(2, properties.getMaxMessages());
        AiServices<ShopAssistant> builder = AiServices.builder(ShopAssistant.class)
                .chatLanguageModel(dashScopeChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(max)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build());
        log.warn("hmdp.agent.dashscope.tools-enabled=false：未注册 ShopAgentTools（适用于不支持 Function Calling 的模型）");
        return builder.build();
    }

    @Bean
    public ShopAssistant shopAssistantWithTools(
            ChatLanguageModel dashScopeChatModel,
            RedisChatMemoryStore redisChatMemoryStore,
            ShopAgentTools shopAgentTools,
            AgentDashScopeProperties properties
    ) {
        int max = Math.max(2, properties.getMaxMessages());
        return AiServices.builder(ShopAssistant.class)
                .chatLanguageModel(dashScopeChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(max)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .tools(shopAgentTools)
                .build();
    }
}
