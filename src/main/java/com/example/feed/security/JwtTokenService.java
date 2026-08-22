package com.example.feed.security;

import com.example.feed.repository.UserRepository.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;
    private final boolean rsa;
    private final String audience;

    @Autowired
    public JwtTokenService(JwtEncoder encoder,
                           @Value("${feed.security.jwt.issuer}") String issuer,
                           @Value("${feed.security.jwt.ttl:2h}") Duration ttl,
                           @Value("${feed.security.jwt.mode:HMAC}") String mode,
                           @Value("${feed.security.jwt.audience:}") String audience) {
        this(encoder, Clock.systemUTC(), issuer, ttl, "RSA".equalsIgnoreCase(mode), audience);
    }

    JwtTokenService(JwtEncoder encoder, Clock clock, String issuer, Duration ttl) {
        this(encoder, clock, issuer, ttl, false, "");
    }

    JwtTokenService(JwtEncoder encoder, Clock clock, String issuer, Duration ttl, boolean rsa) {
        this(encoder, clock, issuer, ttl, rsa, "");
    }

    JwtTokenService(JwtEncoder encoder, Clock clock, String issuer, Duration ttl,
                    boolean rsa, String audience) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
        this.rsa = rsa;
        this.audience = audience;
    }

    public AccessToken issue(AuthUser user) {
        return issue(user, UUID.randomUUID());
    }

    public AccessToken issue(AuthUser user, UUID sessionId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Long.toString(user.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString())
                .claim("username", user.username())
                .claim("nickname", user.nickname())
                .claim("roles", java.util.List.of(user.role()));
        if (audience != null && !audience.isBlank()) {
            claimsBuilder.audience(java.util.List.of(audience));
        }
        JwtClaimsSet claims = claimsBuilder.build();
        JwsHeader header = rsa
                ? JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build()
                : JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, "Bearer", ttl.toSeconds(), user.id(), user.username(), user.nickname());
    }

    public record AccessToken(String accessToken, String tokenType, long expiresIn,
                              long userId, String username, String nickname) {
    }
}
