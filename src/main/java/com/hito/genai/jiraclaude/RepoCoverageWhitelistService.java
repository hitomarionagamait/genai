package com.hito.genai.jiraclaude;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Persists the repository coverage whitelist outside the source tree. */
@Service
public class RepoCoverageWhitelistService {

    private final Path file;

    public RepoCoverageWhitelistService(RepositoryQualityProperties properties) {
        String configuredFile = properties.getWhitelist().getFile();
        if (configuredFile == null || configuredFile.isBlank()) {
            configuredFile = "./data/repository-coverage-whitelist.txt";
        }
        this.file = Path.of(configuredFile).toAbsolutePath().normalize();
    }

    public Set<String> load() {
        if (!Files.exists(file)) return Set.of();
        try {
            Set<String> values = new TreeSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = normalize(line);
                if (!value.isBlank() && !value.startsWith("#")) values.add(value);
            }
            return Set.copyOf(values);
        } catch (IOException ex) {
            throw new IllegalStateException("Gagal membaca repository coverage whitelist: " + file, ex);
        }
    }

    /** Replace the complete whitelist. Kept for administrative maintenance jobs. */
    public Set<String> replace(Collection<String> repositories) {
        Set<String> values = normalizeAll(repositories);
        return write(values);
    }

    /**
     * Update only repositories visible in the current release report. Existing whitelist entries
     * for repositories outside that report remain untouched, so selecting another fixVersion
     * cannot accidentally erase the workspace-wide whitelist.
     */
    public Set<String> replaceVisible(Collection<String> visibleRepositories,
                                      Collection<String> selectedRepositories) {
        Set<String> visible = normalizeAll(visibleRepositories);
        Set<String> selected = normalizeAll(selectedRepositories);
        selected.retainAll(visible); // never accept repository names outside the current report
        Set<String> values = new TreeSet<>(load());
        values.removeAll(visible);
        values.addAll(selected);
        return write(values);
    }

    private Set<String> normalizeAll(Collection<String> repositories) {
        Set<String> values = new TreeSet<>();
        if (repositories != null) {
            for (String value : repositories) {
                String normalized = normalize(value);
                if (!normalized.isBlank()) values.add(normalized);
            }
        }
        return values;
    }

    private Set<String> write(Set<String> values) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(file, values, StandardCharsets.UTF_8);
            return Set.copyOf(values);
        } catch (IOException ex) {
            throw new IllegalStateException("Gagal menyimpan repository coverage whitelist: " + file, ex);
        }
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        return normalized.trim().toLowerCase(Locale.ROOT);
    }
}
