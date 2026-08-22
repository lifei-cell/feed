package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ConflictException;
import com.example.feed.api.InvalidRefreshTokenException;
import com.example.feed.repository.AuthSessionRepository;
import com.example.feed.repository.AuthSessionRepository.RefreshTokenRecord;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.UserRepository.AuthUser;
import com.example.feed.security.JwtTokenService;
import com.example.feed.security.JwtTokenService.AccessToken;
import com.example.feed.security.LoginRateLimiter;
import com.example.feed.security.RefreshTokenService;
import com.example.feed.service.AccountVerificationService.VerifiedContact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final AuthSessionRepository sessions;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter rateLimiter;
    private final AccountVerificationService verification;
    private final Clock clock;
    private final Duration refreshTtl;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this(users, passwordEncoder, tokens, null, null, null, null,
                Clock.systemUTC(), Duration.ofDays(30));
    }

    @Autowired
    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
                       AuthSessionRepository sessions, RefreshTokenService refreshTokens,
                       LoginRateLimiter rateLimiter, AccountVerificationService verification,
                       @Value("${feed.security.refresh-token.ttl:30d}") Duration refreshTtl) {
        this(users, passwordEncoder, tokens, sessions, refreshTokens, rateLimiter, verification,
                Clock.systemUTC(), refreshTtl);
    }

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
                AuthSessionRepository sessions, RefreshTokenService refreshTokens,
                LoginRateLimiter rateLimiter, Clock clock, Duration refreshTtl) {
        this(users, passwordEncoder, tokens, sessions, refreshTokens, rateLimiter, null, clock, refreshTtl);
    }

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
                AuthSessionRepository sessions, RefreshTokenService refreshTokens,
                LoginRateLimiter rateLimiter, AccountVerificationService verification,
                Clock clock, Duration refreshTtl) {
        if (refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("刷新令牌有效期必须为正数");
        }
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.verification = verification;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    /**
     * Kept for source compatibility with service-level callers. HTTP registration requires a
     * verified contact and uses the overload below.
     */
    @Transactional
    public AccessToken register(String username, String nickname, String password) {
        return tokens.issue(createUser(username, nickname, password));
    }

    /** Kept for source compatibility with non-HTTP callers. */
    @Transactional
    public AuthTokens register(String username, String nickname, String password,
                               String clientAddress, String userAgent) {
        return issueSession(createUser(username, nickname, password), clientAddress, userAgent);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthTokens registerVerified(String username, String nickname, String password,
                                       String channel, String target, String challengeId,
                                       String verificationCode, String clientAddress, String userAgent) {
        VerifiedContact contact = requireVerification().consumeRegistration(
                challengeId, verificationCode, channel, target);
        return issueSession(createVerifiedUser(username, nickname, password, contact),
                clientAddress, userAgent);
    }

    /**
     * Kept for source compatibility with service-level callers. HTTP login is rate limited and
     * uses the metadata overload below.
     */
    @Transactional(readOnly = true)
    public AccessToken login(String username, String password) {
        return tokens.issue(requireValidCredentials(normalize(username), password));
    }

    @Transactional
    public AuthTokens login(String username, String password, String clientAddress, String userAgent) {
        String normalized = normalize(username);
        rateLimiter.checkAllowed(normalized, clientAddress);
        AuthUser user;
        try {
            user = requireValidCredentials(normalized, password);
        } catch (BadCredentialsException exception) {
            rateLimiter.recordFailure(normalized, clientAddress);
            throw exception;
        }
        rateLimiter.recordSuccess(normalized);
        return issueSession(user, clientAddress, userAgent);
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshTokenRecord current = sessions.findRefreshTokenForUpdate(refreshTokens.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (current.sessionRevokedAt() != null || current.tokenRevokedAt() != null
                || current.usedAt() != null || !current.sessionExpiresAt().isAfter(now)
                || !current.tokenExpiresAt().isAfter(now)) {
            sessions.revoke(current.sessionId(), now);
            throw new InvalidRefreshTokenException();
        }

        String replacement = refreshTokens.generate();
        UUID replacementId = UUID.randomUUID();
        if (!sessions.rotate(current.tokenId(), replacementId, current.sessionId(),
                refreshTokens.hash(replacement), current.sessionExpiresAt(), now)) {
            sessions.revoke(current.sessionId(), now);
            throw new InvalidRefreshTokenException();
        }
        AccessToken access = tokens.issue(current.user(), current.sessionId());
        return AuthTokens.from(access, replacement,
                remainingSeconds(now, current.sessionExpiresAt()));
    }

    @Transactional
    public void logout(String sessionId, long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessions.revoke(UUID.fromString(sessionId), userId, clock.instant());
        } catch (IllegalArgumentException exception) {
            // A valid application JWT always has a UUID sid; keep logout idempotent.
        }
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        sessions.findRefreshTokenForUpdate(refreshTokens.hash(rawRefreshToken))
                .ifPresent(token -> sessions.revoke(token.sessionId(), clock.instant()));
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPassword(String challengeId, String verificationCode, String newPassword) {
        long userId = requireVerification().consumePasswordReset(challengeId, verificationCode);
        users.updatePasswordAndRevokeSessions(userId, passwordEncoder.encode(newPassword), clock.instant());
    }

    private AuthUser createUser(String username, String nickname, String password) {
        String normalized = normalize(username);
        if (users.existsByUsername(normalized)) {
            throw new ConflictException("用户名已存在");
        }
        String cleanNickname = nickname.strip();
        String passwordHash = passwordEncoder.encode(password);
        try {
            long userId = users.create(normalized, cleanNickname, passwordHash);
            return new AuthUser(userId, normalized, cleanNickname, passwordHash, "USER");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("用户名已存在");
        }
    }

    private AuthUser createVerifiedUser(String username, String nickname, String password,
                                        VerifiedContact contact) {
        String normalized = normalize(username);
        if (users.existsByUsername(normalized)) {
            throw new ConflictException("用户名已存在");
        }
        String cleanNickname = nickname.strip();
        String passwordHash = passwordEncoder.encode(password);
        try {
            long userId = users.createVerified(normalized, cleanNickname, passwordHash,
                    contact.channel(), contact.target(), contact.verifiedAt());
            return new AuthUser(userId, normalized, cleanNickname, passwordHash, "USER");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("用户名、邮箱或手机号已存在");
        }
    }

    private AuthUser requireValidCredentials(String normalizedUsername, String password) {
        AuthUser user = users.findByUsername(normalizedUsername)
                .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (!user.passwordHash().startsWith("$2")
                || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }
        return user;
    }

    private AuthTokens issueSession(AuthUser user, String clientAddress, String userAgent) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(refreshTtl);
        UUID sessionId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();
        String refreshToken = refreshTokens.generate();
        sessions.create(sessionId, user.id(), refreshTokenId, refreshTokens.hash(refreshToken), expiresAt,
                truncate(clientAddress, 64), truncate(userAgent, 255));
        return AuthTokens.from(tokens.issue(user, sessionId), refreshToken,
                Math.max(1, refreshTtl.toSeconds()));
    }

    private AccountVerificationService requireVerification() {
        if (verification == null) {
            throw new IllegalStateException("账户验证服务不可用");
        }
        return verification;
    }

    private long remainingSeconds(Instant now, Instant expiresAt) {
        return Math.max(1, Duration.between(now, expiresAt).toSeconds());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    private String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public record AuthTokens(String accessToken, String tokenType, long expiresIn,
                             String refreshToken, long refreshExpiresIn,
                             long userId, String username, String nickname) {
        static AuthTokens from(AccessToken access, String refreshToken, long refreshExpiresIn) {
            return new AuthTokens(access.accessToken(), access.tokenType(), access.expiresIn(),
                    refreshToken, refreshExpiresIn, access.userId(), access.username(), access.nickname());
        }
    }
}
