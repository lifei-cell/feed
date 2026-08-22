package com.example.feed.security;

import com.example.feed.repository.AuthSessionRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtMode mode;
    private final String secret;
    private final String issuer;
    private final String audience;
    private final String userIdClaim;
    private final String rolesClaim;
    private final String publicKeyLocation;
    private final String privateKeyLocation;
    private final String jwkSetUri;
    private final String keyId;
    private final ResourceLoader resources;

    @Autowired
    public SecurityConfig(@Value("${feed.security.jwt.mode:HMAC}") String mode,
                          @Value("${feed.security.jwt.secret:}") String secret,
                          @Value("${feed.security.jwt.issuer}") String issuer,
                          @Value("${feed.security.jwt.audience:}") String audience,
                          @Value("${feed.security.jwt.user-id-claim:sub}") String userIdClaim,
                          @Value("${feed.security.jwt.roles-claim:roles}") String rolesClaim,
                          @Value("${feed.security.jwt.public-key-location:}") String publicKeyLocation,
                          @Value("${feed.security.jwt.private-key-location:}") String privateKeyLocation,
                          @Value("${feed.security.jwt.jwk-set-uri:}") String jwkSetUri,
                          @Value("${feed.security.jwt.key-id:friend-feed-1}") String keyId,
                          ResourceLoader resources) {
        this.mode = JwtMode.valueOf(mode.strip().toUpperCase(java.util.Locale.ROOT));
        this.secret = secret;
        this.issuer = issuer;
        this.audience = audience;
        this.userIdClaim = userIdClaim;
        this.rolesClaim = rolesClaim;
        this.publicKeyLocation = publicKeyLocation;
        this.privateKeyLocation = privateKeyLocation;
        this.jwkSetUri = jwkSetUri;
        this.keyId = keyId;
        this.resources = resources;
        validateHmacSecret();
    }

    SecurityConfig(String secret, String issuer) {
        this.mode = JwtMode.HMAC;
        this.secret = secret;
        this.issuer = issuer;
        this.audience = "";
        this.userIdClaim = "sub";
        this.rolesClaim = "roles";
        this.publicKeyLocation = "";
        this.privateKeyLocation = "";
        this.jwkSetUri = "";
        this.keyId = "friend-feed-test";
        this.resources = null;
        validateHmacSecret();
    }

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/revoke",
                                "/api/auth/verification/register/request",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Bearer token 缺失、无效或已过期")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "需要登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "无权访问该资源")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(rolesClaim);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return switch (mode) {
            case HMAC -> new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey()));
            case RSA -> rsaEncoder();
            case OIDC -> parameters -> {
                throw new JwtEncodingException("Local JWT issuance is disabled in OIDC mode");
            };
        };
    }

    @Bean
    JwtDecoder jwtDecoder(ObjectProvider<AuthSessionRepository> sessionRepositories) {
        return jwtDecoder(sessionRepositories.getIfAvailable());
    }

    JwtDecoder jwtDecoder() {
        return jwtDecoder((AuthSessionRepository) null);
    }

    JwtDecoder jwtDecoder(AuthSessionRepository sessions) {
        JwtDecoder decoder = switch (mode) {
            case HMAC -> NimbusJwtDecoder.withSecretKey(secretKey())
                    .macAlgorithm(MacAlgorithm.HS256).build();
            case RSA -> NimbusJwtDecoder.withPublicKey(publicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            case OIDC -> jwkSetUri == null || jwkSetUri.isBlank()
                    ? JwtDecoders.fromIssuerLocation(issuer)
                    : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        };
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(validator(sessions));
        }
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> validator(AuthSessionRepository sessions) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> audience == null || audience.isBlank()
                || jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : failure("invalid_token", "JWT audience is not allowed");
        OAuth2TokenValidator<Jwt> userValidator = jwt -> {
            try {
                Object value = "sub".equals(userIdClaim) ? jwt.getSubject() : jwt.getClaim(userIdClaim);
                return value != null && Long.parseLong(String.valueOf(value)) > 0
                        ? OAuth2TokenValidatorResult.success()
                        : failure("invalid_token", "JWT user id claim must be a positive number");
            } catch (RuntimeException exception) {
                return failure("invalid_token", "JWT user id claim must be a positive number");
            }
        };
        OAuth2TokenValidator<Jwt> sessionValidator = jwt -> {
            if (mode == JwtMode.OIDC) {
                return OAuth2TokenValidatorResult.success();
            }
            String sessionId = jwt.getClaimAsString("sid");
            if (sessionId == null || sessionId.isBlank()) {
                return failure("invalid_token", "JWT session is missing, expired, or revoked");
            }
            try {
                return sessions == null || sessions.isActive(sessionId)
                        ? OAuth2TokenValidatorResult.success()
                        : failure("invalid_token", "JWT session is missing, expired, or revoked");
            } catch (RuntimeException exception) {
                return failure("invalid_token", "JWT session is missing, expired, or revoked");
            }
        };
        return new DelegatingOAuth2TokenValidator<>(
                defaults, audienceValidator, userValidator, sessionValidator);
    }

    private OAuth2TokenValidatorResult failure(String code, String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, message, null));
    }

    private JwtEncoder rsaEncoder() {
        RSAPublicKey publicKey = publicKey();
        RSAPrivateKey privateKey = JwtKeyLoader.privateKey(resources, privateKeyLocation);
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    private RSAPublicKey publicKey() {
        return JwtKeyLoader.publicKey(resources, publicKeyLocation);
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private void validateHmacSecret() {
        if (mode == JwtMode.HMAC && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
    }

    private void writeProblem(HttpServletResponse response, int status, String detail)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    enum JwtMode {
        HMAC, RSA, OIDC
    }
}
