package com.hito.genai.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/** Titik sambung modul OAuth (Google/Bitbucket) ke rantai filter keamanan. */
public interface OAuthLoginCustomizer {

    /** true bila minimal satu penyedia terdaftar. */
    boolean isActive();

    void customize(HttpSecurity http) throws Exception;
}
