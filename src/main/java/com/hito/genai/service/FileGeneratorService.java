package com.hito.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
public class FileGeneratorService {

    // Membaca path folder dari application.yml
    @Value("${app.file.output-dir-spike}")
    private String outputDirectorySpike;

    public String generateFileFromTemplate(String fileName, Map<String, String> replacements) {
        try {
            // 1. Baca isi template dari src/main/resources/templates/ai-response-template.txt
            ClassPathResource templateResource = new ClassPathResource("templates/ai-response-template.txt");
            String templateContent = new String(templateResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 2. Ganti placeholder (misal: {{ROLE}}) dengan nilai dinamis
            String finalContent = templateContent;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                finalContent = finalContent.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }

            // 3. Buat folder tujuan jika belum ada
            Path outputDirPath = Paths.get(outputDirectorySpike);
            if (!Files.exists(outputDirPath)) {
                Files.createDirectories(outputDirPath);
            }

            // 4. Tentukan lokasi dan nama file output
            Path targetFilePath = outputDirPath.resolve(fileName + ".csv");

            // 5. Tulis konten ke dalam file baru
            Files.writeString(targetFilePath, finalContent, StandardCharsets.UTF_8);

            return "File berhasil dibuat di: " + targetFilePath.toAbsolutePath();

        } catch (IOException e) {
            throw new RuntimeException("Gagal memproses file template: " + e.getMessage(), e);
        }
    }
}