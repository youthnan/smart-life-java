package com.hmdp.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j 高阶服务接口：ReAct / 工具调用由框架与模型协同完成。
 */
public interface ShopAssistant {

    @SystemMessage("你是本地生活平台的智能助手，帮助用户查询店铺信息或登记「到店预约意向」（演示数据写入 Redis，非真实线下履约）。"
            + "涉及具体店铺数据时必须调用工具，不要编造不存在的店铺 ID 或名称。"
            + "回答使用简体中文，简洁有条理。")
    @UserMessage("{{msg}}")
    String chat(@MemoryId String sessionId, @V("msg") String userMessage);
}
