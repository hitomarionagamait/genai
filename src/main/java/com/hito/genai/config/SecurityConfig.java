package com.hito.genai.config;

import com.hito.genai.security.LoginThrottle;
import com.hito.genai.security.LoginThrottleFilter;
import com.hito.genai.security.OAuthLoginCustomizer;
import com.hito.genai.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.util.Locale;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

//    @Bean
//    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
//        http
//                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
//
//                .authorizeHttpRequests(auth -> auth
//                        .anyRequest().authenticated()
//                )
//
//                .formLogin(withDefaults())
//                .httpBasic(withDefaults());
//
//        return http.build();
//    }
//
//    // Bean UserDetailsService dan PasswordEncoder tetap dipertahankan
//    @Bean
//    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
//        UserDetails user = User.builder()
//                .username("marionaga")
//                .password(passwordEncoder.encode("password123"))
//                .roles("USER")
//                .build();
//
//        return new InMemoryUserDetailsManager(user);
//    }
//
//    @Bean
//    public PasswordEncoder passwordEncoder() {
//        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
//    }

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; "
            + "object-src 'none'";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityProperties props, LoginThrottle throttle,
                                           ObjectProvider<OAuthLoginCustomizer> oauthProvider) throws Exception {
        OAuthLoginCustomizer oauth = oauthProvider.getIfAvailable();
        boolean oauthActive = oauth != null && oauth.isActive();

        // Fail-fast: jangan sampai aplikasi hidup tanpa satu pun cara login (terkunci total).
        if (!props.isLocalLoginEnabled() && !oauthActive) {
            throw new IllegalStateException("Tidak ada metode login aktif: app.security.local-login-enabled=false "
                    + "tetapi tidak ada penyedia Google/Bitbucket yang terdaftar. Aktifkan profile 'google' dan/atau "
                    + "'bitbucket' beserta client-id/secret-nya, atau nyalakan kembali login lokal.");
        }
        if (oauthActive && props.getAllowedEmails().stream().allMatch(String::isBlank)
                && props.getAllowedDomains().stream().allMatch(String::isBlank)) {
            log.warn("Login sosial aktif tetapi app.security.allowed-emails/allowed-domains kosong: "
                    + "SEMUA login Google/Bitbucket akan ditolak (fail closed).");
        }

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .headers(h -> {
                    h.addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "strict-origin-when-cross-origin"));
                    h.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
                    String mode = props.getCspMode() == null ? "" : props.getCspMode().toLowerCase(Locale.ROOT);
                    if (mode.equals("enforce")) {
                        h.addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy", CSP));
                    } else if (mode.equals("report-only")) {
                        h.addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy-Report-Only", CSP));
                    }
                })
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                // Permintaan /api/** tanpa login dijawab 401 (bukan redirect HTML) agar mudah ditangani JavaScript.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/")))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));

        if (props.isLocalLoginEnabled()) {
            http.formLogin(f -> f.loginPage("/login").permitAll())
                    .httpBasic(withDefaults())
                    .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class);
        }
        if (oauthActive) {
            oauth.customize(http);
        }
        return http.build();
    }

//    @Bean
//    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder, SecurityProperties props,
//                                                 @Value("${spring.security.user.name}") String username,
//                                                 @Value("${spring.security.user.password}") String password) {
//        if (props.isLocalLoginEnabled() && "change-me".equals(password)) {
//            log.warn("Login lokal aktif dengan password bawaan 'change-me'. Set APP_PASSWORD, atau matikan login lokal "
//                    + "(APP_LOCAL_LOGIN=false) bila memakai Google/Bitbucket.");
//        }
//        UserDetails user = User.builder()
//                .username(username)
//                .password(passwordEncoder.encode(password))
//                .roles("USER")
//                .build();
//        return new InMemoryUserDetailsManager(user);
//    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}