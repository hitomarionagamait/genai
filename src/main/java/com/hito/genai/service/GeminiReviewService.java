package com.hito.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Service
public class GeminiReviewService {

    private final RestClient restClient;
    private final String geminiApiKey;

    private static final String SYSTEM_INSTRUCTION = """
            Kamu adalah Lead Backend Assistant Agent, asisten Lead Backend Engineering yang bertugas membantu melakukan review terhadap file project (source code) yang sudah ada maupun dari pull request (PR) tim.
            Gunakan Bahasa Indonesia dalam setiap interaksi.
            
            TUJUAN UTAMA
            Membantu melakukan review kode dari tim (baik dari file yang diunggah, repository, maupun email PR) dan menghasilkan saran perbaikan jika menemukan kode yang kurang tepat, ada yang missing, atau tidak sesuai best practice.
            
            ALUR KERJA
            1. Identifikasi Kode: Baca dan pahami file project atau potongan kode (diff/changes) yang diminta untuk di-review.
            2. Analisis Mendalam: Lakukan review terhadap struktur, logika, efisiensi, dan keamanan kode tersebut.
            3. Buat Komentar: Buat komentar, feedback, atau saran perbaikan yang konstruktif terhadap kode tersebut.
            4. Tampilkan Hasil: Tampilkan komentar tersebut secara spesifik. Sebutkan nama file yang ter-impact, dan tunjukkan di baris (line) atau di fungsi mana kode tersebut perlu diperbaiki.
            
            ATURAN DATA
            - Review kode yang sudah dibuat kemudian berikan comment di line/bagian mana yang ter-impact atau belum sesuai standar.
            - Fokus pada clean code, efisiensi, dan stabilitas backend.
            
            KEAMANAN (MUTLAK)
            - JANGAN menampilkan API token apa pun.
            - JANGAN menampilkan password, secret keys, atau data sensitif lainnya.
            - JANGAN menyimpan atau menuliskan credential ke dalam file hasil report maupun di teks jawaban akhir.
            """;

    public GeminiReviewService(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.google.genai.api-key}") String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    public String reviewCode(String diffContent) {
        if (geminiApiKey == null || geminiApiKey.trim().isEmpty()) {
            return "Error: API Key Gemini belum dikonfigurasi di application.yml";
        }
        
        String url = "/v1beta/models/gemini-3.5-flash-lite:generateContent?key=" + geminiApiKey;

        Map<String, Object> requestBody = Map.of(
            "systemInstruction", Map.of(
                "parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))
            ),
            "contents", List.of(
                Map.of("parts", List.of(Map.of("text", "Tolong review perubahan kode berikut:\n\n" + diffContent)))
            ),
            "generationConfig", Map.of(
                "temperature", 0.2
            )
        );

        try {
            Map response = restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            return extractTextFromResponse(response);
        } catch (Exception e) {
            return "Terjadi kesalahan saat menghubungi API Gemini: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map response) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return (String) parts.get(0).get("text");
        } catch (Exception e) {
            return "Gagal memparsing respons dari agen: " + e.getMessage();
        }
    }
}
