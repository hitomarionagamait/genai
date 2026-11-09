package com.hito.genai.agents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class AgentService {

    private final ChatClient agentClient;
    private final AgentTools agentTools;

    public AgentService(ChatClient.Builder builder,
                        ChatMemory chatMemory,
                        AgentTools agentTools,
                        @Value("classpath:prompts/agent-system.txt") Resource systemPrompt) {
        this.agentTools = agentTools;
        this.agentClient = builder
                .defaultSystem(readPrompt(systemPrompt))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public AgentReply run(String conversationId, String message) {
        String id = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        String answer = agentClient.prompt()
                .user(message)
                .tools(agentTools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                .call()
                .content();

        return new AgentReply(id, answer);
    }

    private static String readPrompt(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Gagal membaca system prompt agent", e);
        }
    }

    public record AgentReply(String conversationId, String answer) {
    }
}
