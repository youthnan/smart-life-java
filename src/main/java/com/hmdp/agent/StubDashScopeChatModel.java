package com.hmdp.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * 未配置 API Key 时使用，保证应用可启动、接口可返回明确提示。
 */
public class StubDashScopeChatModel implements ChatLanguageModel {

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof UserMessage) {
                lastUser = ((UserMessage) m).singleText();
                break;
            }
        }
        String body = "当前未配置 LLM API Key（请设置环境变量 LLM_API_KEY，或 DASHSCOPE_API_KEY / hmdp.agent.dashscope.api-key）。"
                + "并建议同时设置 LLM_BASE_URL、LLM_MODEL（或与 application.yaml 中 hmdp.agent.dashscope 一致）。"
                + "无法调用大模型。您刚说的摘要：" + truncate(lastUser, 200);
        return Response.from(AiMessage.from(body));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
