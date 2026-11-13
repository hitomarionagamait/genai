package com.hito.genai.security.oauth;

import com.hito.genai.security.LoginPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Login Google (OpenID Connect). Spring sudah memverifikasi tanda tangan, issuer, audience, kedaluwarsa, dan nonce
 * ID token; di sini {@link LoginPolicy} menentukan APAKAH akun tersebut boleh masuk, hanya dari klaim ID token
 * yang sudah terverifikasi (bukan dari respons userinfo).
 */
@Component
public class PolicyOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger("AUTH_AUDIT");

    private final OidcUserService delegate = new OidcUserService();
    private final LoginPolicy policy;

    public PolicyOidcUserService(LoginPolicy policy) {
        this.policy = policy;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = delegate.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();

        Map<String, Object> claims = new HashMap<>(user.getIdToken().getClaims());
        LoginPolicy.Decision decision = policy.evaluate(provider, claims);
        if (!decision.allowed()) {
            log.warn("LOGIN_DENIED provider={} reason={} user={}", provider, decision.code(),
                    LoginPolicy.mask(String.valueOf(claims.get("email"))));
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", decision.code(), null));
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        decision.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        authorities.add(new SimpleGrantedAuthority("SOCIAL_" + provider));
        // nama principal = email (sudah divalidasi ada dan terverifikasi oleh policy)
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "email");
    }
}
