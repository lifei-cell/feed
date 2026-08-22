package com.example.feed.security;

import com.example.feed.repository.AuthSessionRepository;
import com.example.feed.repository.UserRepository.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenServiceTest {
    private static final String SECRET = "test-secret-with-at-least-thirty-two-bytes";

    @Test
    void issuesTokenThatDecoderValidates() {
        SecurityConfig config = new SecurityConfig(SECRET, "https://friend-feed.test");
        JwtTokenService tokens = new JwtTokenService(
                config.jwtEncoder(), Clock.systemUTC(), "https://friend-feed.test", Duration.ofHours(2));
        JwtDecoder decoder = config.jwtDecoder();

        UUID sessionId = UUID.randomUUID();
        JwtTokenService.AccessToken accessToken = tokens.issue(
                new AuthUser(42, "alice", "Alice", "not-exposed", "USER"), sessionId);
        Jwt decoded = decoder.decode(accessToken.accessToken());

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://friend-feed.test");
        assertThat(decoded.getClaimAsString("username")).isEqualTo("alice");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(decoded.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(accessToken.expiresIn()).isEqualTo(7200);
    }

    @Test
    void rejectsSecretThatIsTooShortForHs256() {
        assertThatThrownBy(() -> new SecurityConfig("too-short", "issuer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void rejectsAccessTokenAfterItsSessionIsRevoked() {
        SecurityConfig config = new SecurityConfig(SECRET, "https://friend-feed.test");
        AuthSessionRepository sessions = mock(AuthSessionRepository.class);
        JwtTokenService tokens = new JwtTokenService(
                config.jwtEncoder(), Clock.systemUTC(), "https://friend-feed.test", Duration.ofHours(2));
        UUID sessionId = UUID.randomUUID();
        String encoded = tokens.issue(
                new AuthUser(42, "alice", "Alice", "not-exposed", "USER"), sessionId).accessToken();
        when(sessions.isActive(sessionId.toString())).thenReturn(true, false);
        JwtDecoder decoder = config.jwtDecoder(sessions);

        assertThat(decoder.decode(encoded).getSubject()).isEqualTo("42");
        assertThatThrownBy(() -> decoder.decode(encoded))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void rsaTokenIsSignedWithPrivateKeyAndValidatedWithPublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        ResourceLoader resources = mock(ResourceLoader.class);
        when(resources.getResource("memory:public")).thenReturn(new ByteArrayResource(
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        when(resources.getResource("memory:private")).thenReturn(new ByteArrayResource(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        SecurityConfig config = new SecurityConfig("RSA", "", "https://friend-feed.test",
                "friend-feed-api", "sub", "roles", "memory:public", "memory:private", "",
                "rsa-test-1", resources);
        JwtTokenService tokens = new JwtTokenService(config.jwtEncoder(), Clock.systemUTC(),
                "https://friend-feed.test", Duration.ofMinutes(15), true, "friend-feed-api");

        String encoded = tokens.issue(
                new AuthUser(42, "alice", "Alice", "not-exposed", "USER"), UUID.randomUUID())
                .accessToken();
        Jwt decoded = config.jwtDecoder().decode(encoded);

        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256");
        assertThat(decoded.getAudience()).containsExactly("friend-feed-api");
        assertThat(decoded.getSubject()).isEqualTo("42");
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
