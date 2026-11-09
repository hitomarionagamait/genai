package com.hito.genai.service;

import com.hito.genai.model.ChatRequest;
import com.hito.genai.model.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ChatClient chatClient;

    // Jika service Anda menerima ChatClient.Builder, gunakan mock untuk builder-nya juga

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        // Setup inisialisasi jika ada variabel tambahan
    }

    @Test
    void testProsesChat_Success() {
        // 1. Persiapan Data (Arrange)
        String userMessage = "Halo, siapa kamu?";
        String mockAiResponse = "Saya adalah asisten AI yang dibangun dengan Spring Boot.";

//        ChatRequest request = new ChatRequest();
        // Sesuaikan dengan setter yang ada di ChatRequest Anda
        // request.setMessage(userMessage);

        // CATATAN: Karena Spring AI ChatClient menggunakan Fluent API (builder pattern),
        // Mocking secara langsung pada layer Service terkadang membutuhkan mock berantai.
        // Jika kode Anda menggunakan ChatModel secara langsung, Anda bisa mem-mock ChatModel.

        // Asumsi skenario: Service ini mengembalikan string atau objek ChatResponse
        // Mock perilaku dari API (Anda perlu menyesuaikan mock ini dengan implementasi fluent API Anda)
        // Saat ini ini hanya simulasi kerangka test.

        // 2. Aksi (Act)
        // ChatResponse response = conversationService.chat(request);

        // 3. Verifikasi (Assert)
        // assertNotNull(response);
        // assertEquals(mockAiResponse, response.getReply());
        // verify(chatClient, times(1)).prompt(); // Sesuaikan verifikasi
    }
}