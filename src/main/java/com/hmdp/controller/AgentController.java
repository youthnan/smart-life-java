package com.hmdp.controller;

import com.hmdp.agent.ShopAssistant;
import com.hmdp.agent.config.AgentDashScopeProperties;
import com.hmdp.agent.tools.ShopAgentTools;
import com.hmdp.dto.AgentChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.ratelimit.RateLimit;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j + 百炼（DashScope OpenAI 兼容）演示接口；预约为 Redis 演示数据，非真实履约。
 */
@RestController
@RequestMapping("/agent")
@Validated
public class AgentController {
    private static final Pattern SHOP_ID_PATTERN = Pattern.compile("(?:店铺\\s*ID\\s*[=:：]?\\s*|\\bid\\s*[=:：]?\\s*)(\\d+)", Pattern.CASE_INSENSITIVE);

    @Resource
    private AgentDashScopeProperties properties;

    @Resource(name = "shopAssistantWithTools")
    private ShopAssistant shopAssistantWithTools;

    @Resource(name = "shopAssistantNoTools")
    private ShopAssistant shopAssistantNoTools;
    @Resource
    private ShopAgentTools shopAgentTools;

    @PostMapping("/chat")
    @RateLimit(windowSeconds = 60, maxRequests = 30, key = "agentChat")
    public Result chat(@Valid @RequestBody AgentChatRequest request) {
        String reply;
        if (properties.isToolsEnabled()) {
            try {
                reply = shopAssistantWithTools.chat(request.getSessionId(), request.getMessage());
            } catch (RuntimeException e) {
                if (isToolLoopExceeded(e)) {
                    String fallback = fallbackFromShopId(request.getMessage());
                    return Result.ok(sanitizeThink("工具调用陷入循环，已自动降级为单次工具查询。\n\n" + fallback));
                }
                if (isToolsUnsupported(e)) {
                    String fallback = shopAssistantNoTools.chat(request.getSessionId(), request.getMessage());
                    String msg = "当前模型/网关不支持 tools/function calling，已自动降级为纯对话模式。"
                            + "如需查店铺工具：换用支持 tools 的模型后再开启 HMDP_AGENT_DASHSCOPE_TOOLS_ENABLED=true。"
                            + "\n\n" + fallback;
                    return Result.ok(sanitizeThink(msg));
                }
                throw e;
            }
        } else {
            reply = shopAssistantNoTools.chat(request.getSessionId(), request.getMessage());
        }
        return Result.ok(sanitizeThink(reply));
    }

    private static boolean isToolsUnsupported(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && m.contains("Tools are currently not supported by this model")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isToolLoopExceeded(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && m.contains("exceeded 100 sequential tool executions")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String fallbackFromShopId(String message) {
        if (message == null) {
            return "未识别到店铺ID，请按“店铺ID=1”格式重试。";
        }
        Matcher matcher = SHOP_ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return "未识别到店铺ID，请按“店铺ID=1”格式重试。";
        }
        long shopId = Long.parseLong(matcher.group(1));
        return shopAgentTools.getShopJsonById(shopId);
    }

    private static String sanitizeThink(String s) {
        if (s == null) {
            return null;
        }
        // Some gateways leak internal tags like <think>...</think> or stray </think>
        return s.replaceAll("(?s)<think>.*?</think>", "")
                .replace("</think>", "")
                .trim();
    }
}
