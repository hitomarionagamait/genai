package com.hito.genai.k6;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tool untuk K6 agent.
 * - Baca : file project yang diizinkan di dalam workspace root; file .env dan CSV user/credential diblokir.
 * - Tulis: HANYA ke folder tujuan yang dipilih pengguna (dikirim lewat ToolContext,
 *          bukan oleh model), sehingga model tidak bisa menulis ke tempat lain.
 */
@Component
public class K6Tools {

    public static final String CTX_TARGET_DIR = "targetDir";
    public static final String CTX_OVERWRITE = "overwrite";
    public static final String CTX_SAVED_FILES = "savedFiles";
    public static final String CTX_BACKUP_BEFORE_OVERWRITE = "backupBeforeOverwrite";
    public static final String CTX_BACKUP_FILES = "backupFiles";

    private static final Logger log = LoggerFactory.getLogger(K6Tools.class);

    private static final Set<String> READ_EXT =
            Set.of("js", "mjs", "cjs", "ts", "json", "yaml", "yml", "md", "txt", "csv");
    private static final Set<String> WRITE_EXT = Set.of("js", "mjs", "json", "md", "csv", "txt");
    private static final Set<String> SKIP_DIRS =
            Set.of("node_modules", ".git", ".idea", "target", "build", "dist", ".gradle", ".k6-agent-backups", "genai-agent");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]{1,80}$");
    private static final int MAX_READ_CHARS = 60_000;
    private static final int MAX_WRITE_CHARS = 200_000;
    private static final int MAX_LIST = 200;

    private final Path root;

    public K6Tools(@Value("${app.k6.workspace-root}") String workspaceRoot) {
        this.root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Workspace root k6 tidak bisa dibuat: {} ({})", root, e.getMessage());
        }
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ helper (bukan tool)

    /** Ubah path relatif (dari pengguna/model) menjadi path absolut yang dijamin berada di dalam root. */
    public Path resolveInsideRoot(String relative) {
        String rel = relative == null ? "" : relative.trim().replace('\\', '/');
        if (rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Path harus relatif terhadap workspace root, bukan path absolut.");
        }
        for (String seg : rel.split("/")) {
            if (seg.equals("..")) {
                throw new IllegalArgumentException("Path tidak boleh mengandung '..'.");
            }
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path berada di luar workspace root.");
        }
        try {
            // Cegah lolos lewat symlink: cek ancestor terdekat yang sudah ada.
            Path existing = resolved;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Path (symlink) mengarah ke luar workspace root.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Path tidak bisa diverifikasi: " + e.getMessage());
        }
        return resolved;
    }

    public String relative(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    /** Daftar folder (maks 3 level) untuk dropdown di UI. */
    public List<String> listFolders() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root, 3)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .filter(p -> !containsSkipped(root.relativize(p)))
                    .map(this::relative)
                    .sorted()
                    .limit(300)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean containsSkipped(Path rel) {
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveFileName(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.startsWith(".env") || lower.contains("credential") || lower.contains("secret")
                || lower.contains("token") || lower.contains("account")) return true;
        return lower.endsWith(".csv") && lower.contains("user");
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    // ------------------------------------------------------------------ tools

    @Tool(description = "Lihat daftar file di sebuah folder (rekursif, maks 4 level) di dalam workspace k6. "
            + "Gunakan untuk mempelajari struktur project k6 yang sudah ada sebelum membuat script baru.")
    public String listK6Files(
            @ToolParam(description = "Folder relatif terhadap workspace root. Kosongkan untuk root.", required = false)
            String folder) {
        try {
            Path dir = resolveInsideRoot(folder);
            if (!Files.isDirectory(dir)) {
                return "ERROR: folder tidak ditemukan: " + (folder == null ? "" : folder);
            }
            List<String> out = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir, 4)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !containsSkipped(root.relativize(p)))
                        .filter(p -> READ_EXT.contains(extOf(p.getFileName().toString())))
                        .filter(p -> !isSensitiveFileName(p.getFileName().toString()))
                        .sorted()
                        .limit(MAX_LIST)
                        .forEach(p -> {
                            long size;
                            try {
                                size = Files.size(p);
                            } catch (IOException e) {
                                size = -1;
                            }
                            out.add(relative(p) + " (" + size + " bytes)");
                        });
            }
            return out.isEmpty() ? "(tidak ada file yang relevan)" : String.join("\n", out);
        } catch (IllegalArgumentException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Baca isi satu file di workspace k6 (script .js, config .json/.yaml, README, data .csv). "
            + "Gunakan untuk meniru gaya, helper, dan konvensi script k6 yang sudah ada, atau membaca spesifikasi API (OpenAPI).")
    public String readK6File(
            @ToolParam(description = "Path file relatif terhadap workspace root, contoh: payment/k6/login.js") String path) {
        try {
            Path file = resolveInsideRoot(path);
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (name.startsWith(".env") || isSensitiveFileName(name) || !READ_EXT.contains(extOf(name))) {
                return "ERROR: jenis file tidak boleh dibaca. File credential/user CSV dan .env tidak dapat dibuka oleh agent. Yang diizinkan: " + READ_EXT;
            }
            if (!Files.isRegularFile(file)) {
                return "ERROR: file tidak ditemukan: " + path;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_READ_CHARS) {
                return content.substring(0, MAX_READ_CHARS) + "\n...[dipotong, file lebih panjang dari " + MAX_READ_CHARS + " karakter]";
            }
            return content;
        } catch (IllegalArgumentException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Simpan satu file hasil generate (script k6 .js, README.md, data .csv/.json) ke folder tujuan yang sudah dipilih pengguna. "
            + "Folder tujuan sudah ditentukan sistem; kamu hanya memberi nama file (boleh dengan subfolder, contoh: scenarios/orders.js). "
            + "Panggil sekali per file.")
    public String saveK6Script(
            @ToolParam(description = "Nama file relatif terhadap folder tujuan, contoh: orders-load-test.js") String fileName,
            @ToolParam(description = "Isi lengkap file") String content,
            ToolContext toolContext) {
        try {
            Map<String, Object> ctx = toolContext == null ? Map.of() : toolContext.getContext();
            Object targetObj = ctx.get(CTX_TARGET_DIR);
            if (!(targetObj instanceof Path target)) {
                return "ERROR: folder tujuan belum ditentukan oleh sistem.";
            }
            boolean overwrite = Boolean.TRUE.equals(ctx.get(CTX_OVERWRITE));
            boolean backupBeforeOverwrite = !Boolean.FALSE.equals(ctx.get(CTX_BACKUP_BEFORE_OVERWRITE));

            if (fileName == null || fileName.isBlank()) {
                return "ERROR: nama file kosong.";
            }
            String cleanName = fileName.trim().replace('\\', '/');
            for (String seg : cleanName.split("/")) {
                if (!SAFE_SEGMENT.matcher(seg).matches() || seg.equals("..") || seg.equals(".")) {
                    return "ERROR: nama file tidak valid. Pakai huruf, angka, titik, minus, underscore (boleh subfolder dengan '/').";
                }
            }
            String baseName = cleanName.substring(cleanName.lastIndexOf('/') + 1);
            if (baseName.startsWith(".env") || isSensitiveFileName(baseName)) {
                return "ERROR: agent tidak diizinkan membuat atau mengubah file .env/credential/user CSV.";
            }
            if (!WRITE_EXT.contains(extOf(cleanName))) {
                return "ERROR: ekstensi harus salah satu dari " + WRITE_EXT;
            }
            if (content == null || content.isBlank()) {
                return "ERROR: isi file kosong.";
            }
            if (content.length() > MAX_WRITE_CHARS) {
                return "ERROR: isi file terlalu besar (maks " + MAX_WRITE_CHARS + " karakter).";
            }

            Path dest = target.resolve(cleanName).normalize();
            if (!dest.startsWith(target)) {
                return "ERROR: lokasi file di luar folder tujuan.";
            }
            // Pastikan tetap di dalam root meski ada symlink di tengah jalan.
            resolveInsideRoot(relative(dest));

            if (Files.exists(dest)) {
                if (!overwrite) {
                    return "ERROR: file '" + cleanName + "' sudah ada dan overwrite tidak diizinkan. "
                            + "Gunakan nama file lain, atau minta pengguna mengaktifkan overwrite.";
                }
                if (Files.isDirectory(dest)) {
                    return "ERROR: tujuan file merupakan folder: " + cleanName;
                }
                if (backupBeforeOverwrite) {
                    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                            .withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID();
                    Path backup = root.resolve(".k6-agent-backups").resolve(stamp).resolve(relative(dest)).normalize();
                    if (!backup.startsWith(root)) {
                        return "ERROR: lokasi backup tidak valid.";
                    }
                    // Validasi ancestor backup sebelum membuat direktori untuk mencegah symlink escape.
                    resolveInsideRoot(relative(backup));
                    Files.createDirectories(backup.getParent());
                    Files.copy(dest, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    Object backupObj = ctx.get(CTX_BACKUP_FILES);
                    if (backupObj instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<String> typed = (List<String>) list;
                        typed.add(relative(backup));
                    }
                }
            }
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, content, StandardCharsets.UTF_8);

            String rel = relative(dest);
            Object saved = ctx.get(CTX_SAVED_FILES);
            if (saved instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<String> typed = (List<String>) list;
                typed.add(rel);
            }
            log.info("K6 agent menyimpan {}", dest);
            return "OK: tersimpan di " + rel;
        } catch (IllegalArgumentException | IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
