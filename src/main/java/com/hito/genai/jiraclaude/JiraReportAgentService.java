package com.hito.genai.jiraclaude;

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

/** Agent reporting: menjawab pertanyaan & menyusun narasi release memakai tool yang membaca data Jira. */
@Service
public class JiraReportAgentService {

    private static final int MAX_MESSAGE = 2000;

    private final ChatClient client;
    private final JiraReportAgentTools tools;

    public JiraReportAgentService(ChatClient.Builder builder, ChatMemory chatMemory, JiraReportAgentTools tools,
                                  @Value("classpath:prompts/jira-report-agent-system.txt") Resource systemPrompt) {
        this.tools = tools;
        this.client = builder
                .defaultSystem(read(systemPrompt))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Reply ask(String conversationId, String fixVersion, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Pertanyaan tidak boleh kosong.");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("Pertanyaan terlalu panjang (maks " + MAX_MESSAGE + " karakter).");
        }
        String id = (conversationId == null || conversationId.isBlank()) ? UUID.randomUUID().toString() : conversationId;
        String versionLine = (fixVersion == null || fixVersion.isBlank())
                ? "fixVersion terpilih: (belum dipilih)"
                : "fixVersion terpilih: " + fixVersion.trim();
        String answer = client.prompt()
                .user(versionLine + "\n\nPermintaan:\n" + message.trim())
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                .call()
                .content();
        return new Reply(id, answer == null ? "" : answer);
    }

    private static String read(Resource r) {
        try {
            return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Gagal membaca system prompt jira report agent", e);
        }
    }

    public record Reply(String conversationId, String answer) {
    }
}
