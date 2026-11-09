package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class MyController {

    // Models available in the local Ollama instance
    private static final String MODEL_CODER = "deepseek-coder:6.7b";
    private static final String MODEL_QWEN = "qwen2.5:7b";

    private final ChatClient chatClient;

    public MyController(@Qualifier("defaultChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    private static final int MAX_INPUT_LENGTH = 4000;

    /**
     * Blocking endpoint (kept for compatibility / curl tests).
     */
    @GetMapping("/ai")
    String generation(@RequestParam String userInput,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = MODEL_CODER) String model) {

        validate(userInput);
        return this.chatClient.prompt()
                .user(userInput)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(DeepSeekChatOptions.builder().model(resolveModel(model)))
                .call()
                .content();
    }

    /**
     * Streaming endpoint (SSE): emits one text chunk per event, then [DONE].
     * Errors are delivered as SSE error events (stream stays text/event-stream);
     * letting them propagate to @RestControllerAdvice would try to write a JSON
     * body into an SSE response and fail with HttpMessageNotWritableException.
     */
    @GetMapping(value = "/ai/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> generationStream(@RequestParam String userInput,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = MODEL_CODER) String model) {

        validate(userInput);
        return this.chatClient.prompt()
                .user(userInput)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(DeepSeekChatOptions.builder().model(resolveModel(model)))
                .stream()
                .content()
                .onErrorResume(ex -> Flux.just("[ERROR] " + ex.getMessage()));
    }

    private void validate(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be empty");
        }
        if (userInput.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "userInput too long: " + userInput.length() + " (max " + MAX_INPUT_LENGTH + ")");
        }
    }

    private String resolveModel(String model) {
        return MODEL_QWEN.equalsIgnoreCase(model) ? MODEL_QWEN : MODEL_CODER;
    }
}
