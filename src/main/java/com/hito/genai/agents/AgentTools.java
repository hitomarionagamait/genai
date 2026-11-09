package com.hito.genai.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Kumpulan "tangan" untuk agent. Model yang memutuskan kapan method ini dipanggil;
 * aplikasi yang mengeksekusinya. Semua akses file dibatasi ke satu folder output.
 */
@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "txt", "md", "json");
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final int MAX_CONTENT_CHARS = 200_000;

    private final Path outputDir;

    public AgentTools(@Value("${app.file.output-dir-spike}") String outputDir) {
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
    }

    @Tool(description = "Ambil tanggal dan jam saat ini (zona waktu Asia/Jakarta). "
            + "Gunakan ini kalau butuh tanggal, misalnya untuk nama file atau isi dokumen.")
    public String getCurrentDateTime() {
        return ZonedDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'WIB'"));
    }

    @Tool(description = "Baca isi sebuah template dokumen yang tersedia di aplikasi. "
            + "Berikan nama file template saja, contoh: ai-response-template.txt")
    public String readTemplate(
            @ToolParam(description = "Nama file template, tanpa folder") String templateName) {
        if (templateName == null || !SAFE_NAME.matcher(templateName).matches()) {
            return "ERROR: nama template tidak valid.";
        }
        try {
            ClassPathResource resource = new ClassPathResource("templates/" + templateName);
            if (!resource.exists()) {
                return "ERROR: template '" + templateName + "' tidak ditemukan.";
            }
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Gagal membaca template {}", templateName, e);
            return "ERROR: gagal membaca template: " + e.getMessage();
        }
    }

    @Tool(description = "Simpan isi teks ke sebuah file di folder output. "
            + "Ekstensi yang diizinkan: csv, txt, md, json. Kalau file sudah ada, akan ditimpa. "
            + "Kembalikan path lengkap file yang tersimpan.")
    public String saveFile(
            @ToolParam(description = "Nama file beserta ekstensi, contoh: test-steps.csv. Tanpa folder.") String fileName,
            @ToolParam(description = "Isi lengkap file") String content) {
        if (fileName == null || !SAFE_NAME.matcher(fileName).matches()) {
            return "ERROR: nama file tidak valid. Pakai huruf, angka, titik, minus, underscore saja.";
        }
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return "ERROR: ekstensi harus salah satu dari " + ALLOWED_EXTENSIONS;
        }
        if (content == null || content.isBlank()) {
            return "ERROR: isi file kosong.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "ERROR: isi file terlalu besar (maks " + MAX_CONTENT_CHARS + " karakter).";
        }
        try {
            Files.createDirectories(outputDir);
            Path target = outputDir.resolve(fileName).normalize();
            if (!target.startsWith(outputDir)) {
                return "ERROR: lokasi file tidak diizinkan.";
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("Agent menyimpan file {}", target);
            return "OK: file tersimpan di " + target;
        } catch (IOException e) {
            log.warn("Gagal menyimpan file {}", fileName, e);
            return "ERROR: gagal menyimpan file: " + e.getMessage();
        }
    }

    @Tool(description = "Tampilkan daftar file yang sudah pernah disimpan di folder output.")
    public List<String> listGeneratedFiles() {
        if (!Files.isDirectory(outputDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .limit(200)
                    .toList();
        } catch (IOException e) {
            log.warn("Gagal membaca folder output", e);
            return List.of();
        }
    }
}
