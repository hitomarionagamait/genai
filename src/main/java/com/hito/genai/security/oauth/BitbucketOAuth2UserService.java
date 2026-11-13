package com.hito.genai.security.oauth;

import com.hito.genai.security.BitbucketProfileClient;
import com.hito.genai.security.LoginPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Login Bitbucket Cloud. Bitbucket tidak mendukung OpenID Connect (tidak ada ID token), jadi identitas dibuktikan
 * dengan memakai access token untuk memanggil API Bitbucket: akun, email TERKONFIRMASI, dan workspace.
 * {@link LoginPolicy} lalu memutuskan akses berdasarkan allowlist email/domain dan/atau workspace.
 */
@Component
public class BitbucketOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger("AUTH_AUDIT");

    private final BitbucketProfileClient client;
    private final LoginPolicy policy;

    public BitbucketOAuth2UserService(BitbucketProfileClient client, LoginPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        String provider = request.getClientRegistration().getRegistrationId();

        BitbucketProfileClient.Profile profile;
        try {
            profile = client.fetch(request.getAccessToken().getTokenValue());
        } catch (BitbucketProfileClient.BitbucketException e) {
            log.warn("LOGIN_DENIED provider={} reason=profile_unavailable status={}", provider, e.status());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_user_info_response", LoginPolicy.DENY_PROVIDER, null), e);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("account_id", profile.accountId());
        claims.put("confirmed_emails", profile.confirmedEmails());
        claims.put("workspaces", profile.workspaces());
        LoginPolicy.Decision decision = policy.evaluate(provider, claims);
        if (!decision.allowed()) {
            log.warn("LOGIN_DENIED provider={} reason={} user={}", provider, decision.code(),
                    LoginPolicy.mask(profile.primaryEmail()));
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", decision.code(), null));
        }

        // nama principal: email utama bila ada, jika tidak nama tampilan, jika tidak account_id
        String loginName = !profile.primaryEmail().isBlank() ? profile.primaryEmail()
                : !profile.displayName().isBlank() ? profile.displayName() : profile.accountId();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("account_id", profile.accountId());
        attributes.put("uuid", profile.uuid());
        attributes.put("display_name", profile.displayName());
        attributes.put("nickname", profile.nickname());
        attributes.put("email", profile.primaryEmail());
        attributes.put("login_name", loginName);

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        decision.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        authorities.add(new SimpleGrantedAuthority("SOCIAL_" + provider));
        return new DefaultOAuth2User(authorities, attributes, "login_name");
    }
}
