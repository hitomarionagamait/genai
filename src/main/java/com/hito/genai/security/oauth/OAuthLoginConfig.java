package com.hito.genai.security.oauth;

import com.hito.genai.security.LoginPolicy;
import com.hito.genai.security.OAuthLoginCustomizer;
import com.hito.genai.security.OAuthProviders;
import com.hito.genai.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Menyambungkan login Google/Bitbucket ke rantai keamanan. Aktif hanya bila minimal satu penyedia terdaftar
 * (lewat profile Spring 'google' dan/atau 'bitbucket'); tanpa itu semua bean di sini menjadi "kosong".
 */
@Configuration
public class OAuthLoginConfig {

    private static final String AUTHORIZATION_BASE = "/oauth2/authorization";
    private static final Set<String> KNOWN_REASONS = Set.of(LoginPolicy.DENY_NOT_ALLOWED, LoginPolicy.DENY_UNVERIFIED,
            LoginPolicy.DENY_MISSING_EMAIL, LoginPolicy.DENY_ISSUER, LoginPolicy.DENY_IDENTITY,
            LoginPolicy.DENY_NOT_CONFIGURED, LoginPolicy.DENY_PROVIDER);

    private static List<ClientRegistration> registrations(ClientRegistrationRepository repo) {
        List<ClientRegistration> out = new ArrayList<>();
        if (repo instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o instanceof ClientRegistration cr) {
                    out.add(cr);
                }
            }
        }
        return out;
    }

    @Bean
    public OAuthProviders oauthProviders(ObjectProvider<ClientRegistrationRepository> repoProvider) {
        ClientRegistrationRepository repo = repoProvider.getIfAvailable();
        List<OAuthProviders.ProviderLink> links = repo == null ? List.of() : registrations(repo).stream()
                .map(cr -> new OAuthProviders.ProviderLink(cr.getRegistrationId(), cr.getClientName(),
                        AUTHORIZATION_BASE + "/" + cr.getRegistrationId()))
                .toList();
        return () -> links;
    }

    @Bean
    public OAuthLoginCustomizer oauthLoginCustomizer(ObjectProvider<ClientRegistrationRepository> repoProvider,
                                                     PolicyOidcUserService oidcUserService,
                                                     BitbucketOAuth2UserService bitbucketUserService,
                                                     SecurityProperties props) {
        ClientRegistrationRepository repo = repoProvider.getIfAvailable();
        boolean active = repo != null && !registrations(repo).isEmpty();
        return new OAuthLoginCustomizer() {
            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public void customize(HttpSecurity http) throws Exception {
                http.oauth2Login(o -> o
                        .loginPage("/login")
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver(repo, props)))
                        .userInfoEndpoint(u -> u.oidcUserService(oidcUserService).userService(bitbucketUserService))
                        .failureHandler((request, response, exception) -> {
                            // hanya kode alasan dari daftar tetap yang boleh diteruskan ke halaman login
                            String reason = LoginPolicy.DENY_PROVIDER;
                            if (exception instanceof OAuth2AuthenticationException oae
                                    && "access_denied".equals(oae.getError().getErrorCode())
                                    && KNOWN_REASONS.contains(String.valueOf(oae.getError().getDescription()))) {
                                reason = oae.getError().getDescription();
                            }
                            response.sendRedirect(request.getContextPath() + "/login?error=oauth&reason=" + reason);
                        }));
            }
        };
    }

    /** Resolver yang menambahkan PKCE hanya untuk penyedia di app.security.pkce-providers (default: google). */
    private static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository repo, SecurityProperties props) {
        DefaultOAuth2AuthorizationRequestResolver plain =
                new DefaultOAuth2AuthorizationRequestResolver(repo, AUTHORIZATION_BASE);
        DefaultOAuth2AuthorizationRequestResolver pkce =
                new DefaultOAuth2AuthorizationRequestResolver(repo, AUTHORIZATION_BASE);
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        Set<String> pkceIds = props.getPkceProviders().stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                String id = registrationId(request);
                return (id != null && pkceIds.contains(id.toLowerCase(Locale.ROOT)) ? pkce : plain).resolve(request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                boolean usePkce = clientRegistrationId != null && pkceIds.contains(clientRegistrationId.toLowerCase(Locale.ROOT));
                return (usePkce ? pkce : plain).resolve(request, clientRegistrationId);
            }
        };
    }

    /** /oauth2/authorization/{id} -> id; null bila bukan URL authorization. */
    private static String registrationId(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = AUTHORIZATION_BASE + "/";
        return uri.startsWith(prefix) && uri.length() > prefix.length() ? uri.substring(prefix.length()) : null;
    }
}
