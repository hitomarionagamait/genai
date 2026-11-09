package com.hito.genai.k6;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class K6AgentService {

    private static final Set<String> TEST_TYPES = Set.of("smoke", "load", "stress", "spike", "soak");
    private static final int MAX_API_LIST = 20_000;
    private static final int MAX_MESSAGE = 5_000;

    private final ChatClient client;
    private final K6Tools k6Tools;

    public K6AgentService(ChatClient.Builder builder,
                          ChatMemory chatMemory,
                          K6Tools k6Tools,
                          @Value("classpath:prompts/k6-agent-system.txt") Resource systemPrompt) {
        this.k6Tools = k6Tools;
        this.client = builder
                .defaultSystem(readPrompt(systemPrompt))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public K6Reply run(K6Request req) {
        // --- validasi di sisi server (jangan percaya input UI) ---
        if (req.targetFolder() == null || req.targetFolder().isBlank()) {
            throw new IllegalArgumentException("targetFolder wajib diisi (relatif terhadap workspace root).");
        }
        boolean hasInput = notBlank(req.apiList()) || notBlank(req.referenceFolder()) || notBlank(req.message());
        if (!hasInput) {
            throw new IllegalArgumentException("Isi minimal salah satu: daftar API, folder referensi, atau instruksi.");
        }
        if (req.apiList() != null && req.apiList().length() > MAX_API_LIST) {
            throw new IllegalArgumentException("Daftar API terlalu panjang (maks " + MAX_API_LIST + " karakter).");
        }
        if (req.message() != null && req.message().length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("Instruksi terlalu panjang (maks " + MAX_MESSAGE + " karakter).");
        }
        String testType = notBlank(req.testType()) ? req.testType().trim().toLowerCase() : "load";
        if (!TEST_TYPES.contains(testType)) {
            throw new IllegalArgumentException("testType harus salah satu dari " + TEST_TYPES);
        }
        String generationMode = notBlank(req.generationMode()) ? req.generationMode().trim().toLowerCase() : "new";
        if (!Set.of("new", "extend").contains(generationMode)) {
            throw new IllegalArgumentException("generationMode harus 'new' atau 'extend'.");
        }
        boolean overwrite = Boolean.TRUE.equals(req.overwrite());
        boolean backupBeforeOverwrite = req.backupBeforeOverwrite() == null || Boolean.TRUE.equals(req.backupBeforeOverwrite());
        if ("extend".equals(generationMode) && !overwrite) {
            throw new IllegalArgumentException("Mode update project memerlukan izin overwrite. Aktifkan checkbox overwrite; file lama akan dicadangkan terlebih dahulu.");
        }

        Path target = k6Tools.resolveInsideRoot(req.targetFolder());
        if (target.equals(k6Tools.root()) && !"extend".equals(generationMode)) {
            throw new IllegalArgumentException("Untuk membuat file baru, pilih subfolder tujuan. Workspace root hanya boleh dipakai pada mode update project.");
        }
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new IllegalArgumentException("Folder tujuan tidak bisa dibuat: " + e.getMessage());
        }

        String referenceRel = "";
        if (notBlank(req.referenceFolder())) {
            Path ref = k6Tools.resolveInsideRoot(req.referenceFolder());
            if (!Files.isDirectory(ref)) {
                throw new IllegalArgumentException("Folder referensi tidak ditemukan: " + req.referenceFolder());
            }
            referenceRel = k6Tools.relative(ref);
        }

        String conversationId = notBlank(req.conversationId()) ? req.conversationId() : UUID.randomUUID().toString();
        List<String> saved = new CopyOnWriteArrayList<>();
        List<String> backups = new CopyOnWriteArrayList<>();

        String answer = client.prompt()
                .user(buildPrompt(req, testType, generationMode, k6Tools.relative(target), referenceRel))
                .tools(k6Tools)
                .toolContext(Map.of(
                        K6Tools.CTX_TARGET_DIR, target,
                        K6Tools.CTX_OVERWRITE, overwrite,
                        K6Tools.CTX_BACKUP_BEFORE_OVERWRITE, backupBeforeOverwrite,
                        K6Tools.CTX_SAVED_FILES, saved,
                        K6Tools.CTX_BACKUP_FILES, backups))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        return new K6Reply(conversationId, answer == null ? "" : answer, List.copyOf(saved),
                List.copyOf(backups), k6Tools.relative(target));
    }

    private static String buildPrompt(K6Request r, String testType, String generationMode, String targetRel, String referenceRel) {
        StringBuilder sb = new StringBuilder("Buat/perbarui implementasi k6 dengan ketentuan berikut.\n");
        sb.append("- Mode: ").append("extend".equals(generationMode)
                ? "UPDATE PROJECT YANG SUDAH ADA (integrasikan endpoint ke file existing)"
                : "GENERATE FILE BARU (jangan mengubah file existing)").append('\n');
        sb.append("- Folder tujuan (hasil disimpan di sini oleh sistem): ").append(targetRel).append('\n');
        sb.append("- Folder referensi project k6 yang sudah ada: ")
                .append(referenceRel.isEmpty() ? "(tidak ada)" : referenceRel).append('\n');
        sb.append("- Base URL: ").append(notBlank(r.baseUrl()) ? r.baseUrl().trim() : "(tidak diberikan, pakai __ENV.BASE_URL)").append('\n');
        sb.append("- Jenis tes: ").append(testType).append('\n');
        sb.append("- Boleh menimpa file yang sudah ada: ").append(Boolean.TRUE.equals(r.overwrite()) ? "ya" : "tidak").append('\n');
        sb.append("- Backup sebelum overwrite: ").append(r.backupBeforeOverwrite() == null || Boolean.TRUE.equals(r.backupBeforeOverwrite()) ? "ya" : "tidak").append('\n');
        if ("extend".equals(generationMode)) {
            sb.append("\nATURAN UPDATE PROJECT EXISTING:\n")
                    .append("1. WAJIB baca file yang relevan sebelum mengubahnya; gunakan listK6Files/readK6File.\n")
                    .append("2. Project memakai main.js, config.js, setupData.js, patientAPI.js, dan adminAPI.js. Pertahankan arsitektur dan naming yang sudah ada.\n")
                    .append("3. Untuk endpoint baru, integrasikan constant URL ke config.js, fungsi request ke modul API sesuai group (patientAPI.js/adminAPI.js atau file baru bila lebih tepat), import dan pemanggilan ke main.js, serta activeEndpoints/threshold tag bila sesuai.\n")
                    .append("4. Jangan menghapus endpoint/skenario lama, jangan mengubah payload existing tanpa instruksi, dan jangan menulis ulang file yang tidak perlu.\n")
                    .append("5. Jika body, header, variable path, atau data test tidak tersedia, gunakan TODO yang jelas dan jelaskan asumsi; jangan mengarang.\n")
                    .append("6. Tulis ulang file yang memang berubah secara lengkap melalui saveK6Script. File yang existing hanya boleh ditimpa karena pengguna telah mengizinkan overwrite; sistem akan membuat backup otomatis sebelum overwrite.\n")
                    .append("7. Setelah update, cek kembali semua import/export, nama fungsi, endpoint tag dan threshold agar konsisten.\n");
        } else {
            sb.append("\nATURAN GENERATE FILE BARU: jangan mengubah file project yang sudah ada; hasilkan file terpisah yang siap diintegrasikan.\n");
        }
        sb.append("\nDaftar API / endpoint spec:\n").append(notBlank(r.apiList()) ? r.apiList().trim() : "(tidak diberikan)").append('\n');
        if (notBlank(r.message())) {
            sb.append("\nInstruksi tambahan:\n").append(r.message().trim()).append('\n');
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String readPrompt(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Gagal membaca system prompt k6 agent", e);
        }
    }

    public record K6Request(String conversationId, String message, String targetFolder, String referenceFolder,
                            String baseUrl, String testType, String apiList, Boolean overwrite,
                            String generationMode, Boolean backupBeforeOverwrite) {
    }

    public record K6Reply(String conversationId, String answer, List<String> savedFiles,
                          List<String> backupFiles, String targetFolder) {
    }
}
