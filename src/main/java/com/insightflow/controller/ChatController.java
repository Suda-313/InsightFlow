package com.insightflow.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.util.UUID;

/**
 * AI 对话的 HTTP 边界，使用 SSE 流式输出。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送消息并获取 AI 流式回复。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@PathVariable UUID workspaceId, @Valid @RequestBody ChatRequest request) {
        return chatService.chat(workspaceId, request.message());
    }

    public record ChatRequest(@NotBlank @JsonProperty("message") String message) {}
}