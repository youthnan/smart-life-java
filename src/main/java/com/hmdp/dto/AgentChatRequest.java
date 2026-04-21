package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AgentChatRequest {

    /**
     * 会话 ID，用于 Redis 中的多轮记忆。
     */
    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    @NotBlank(message = "message 不能为空")
    private String message;
}
