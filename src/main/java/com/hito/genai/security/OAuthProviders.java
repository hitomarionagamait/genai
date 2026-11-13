package com.hito.genai.security;

import java.util.List;

/** Daftar penyedia login sosial yang aktif (untuk tombol di halaman login). Diisi oleh modul OAuth bila ada. */
public interface OAuthProviders {

    record ProviderLink(String id, String name, String url) {
    }

    List<ProviderLink> links();
}
