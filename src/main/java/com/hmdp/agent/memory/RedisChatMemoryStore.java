package com.hmdp.agent.memory;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.agent.config.AgentDashScopeProperties;
import com.hmdp.utils.RedisConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 使用 Redis 字符串存会话消息 JSON 数组，TTL 与 {@link AgentDashScopeProperties#getMemoryTtlMinutes()} 一致。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AgentDashScopeProperties agentDashScopeProperties;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = redisKey(memoryId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        JSONArray arr;
        try {
            arr = JSONUtil.parseArray(json);
        } catch (Exception e) {
            // 防止历史脏数据导致会话不可读，降级为空并删除坏数据
            stringRedisTemplate.delete(key);
            return new ArrayList<>();
        }
        List<ChatMessage> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String role = o.getStr("role");
            String text = o.getStr("text");
            if (text == null) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                out.add(UserMessage.from(text));
            } else if ("ai".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) {
                out.add(AiMessage.from(text));
            }
        }
        return out;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        int maxMessages = Math.max(2, agentDashScopeProperties.getMaxMessages());
        int from = Math.max(0, messages.size() - maxMessages);
        JSONArray arr = new JSONArray();
        for (int i = from; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            JSONObject o = new JSONObject();
            if (m instanceof UserMessage) {
                o.set("role", "user");
                o.set("text", ((UserMessage) m).singleText());
            } else if (m instanceof AiMessage) {
                o.set("role", "ai");
                o.set("text", ((AiMessage) m).text());
            } else {
                continue;
            }
            arr.add(o);
        }
        String key = redisKey(memoryId);
        int ttl = Math.max(1, agentDashScopeProperties.getMemoryTtlMinutes());
        stringRedisTemplate.opsForValue().set(key, arr.toString(), ttl, TimeUnit.MINUTES);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(redisKey(memoryId));
    }

    private static String redisKey(Object memoryId) {
        return RedisConstants.AGENT_MEMORY_KEY + memoryId;
    }
}
