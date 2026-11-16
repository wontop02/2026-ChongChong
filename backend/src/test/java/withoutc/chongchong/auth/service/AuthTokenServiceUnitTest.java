package withoutc.chongchong.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import withoutc.chongchong.auth.config.RefreshTokenProperties;
import withoutc.chongchong.auth.entity.AuthSession;
import withoutc.chongchong.auth.exception.AuthErrorCode;
import withoutc.chongchong.auth.exception.AuthException;
import withoutc.chongchong.auth.repository.AuthSessionRepository;
import withoutc.chongchong.auth.token.AccessTokenIssuer;
import withoutc.chongchong.auth.token.HashedRefreshToken;
import withoutc.chongchong.auth.token.IssuedAccessToken;
import withoutc.chongchong.auth.token.IssuedTokenPair;
import withoutc.chongchong.auth.token.RawRefreshToken;
import withoutc.chongchong.auth.token.RefreshTokenGenerator;
import withoutc.chongchong.auth.token.RefreshTokenHasher;
import withoutc.chongchong.user.entity.User;
import withoutc.chongchong.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00.123456789Z");
    private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-11-23T12:00:00.123456Z");
    private static final IssuedAccessToken ACCESS_TOKEN = new IssuedAccessToken(
            "issued-access-token", Instant.parse("2026-11-16T12:15:00Z"));
    private static final RawRefreshToken NEW_REFRESH_TOKEN = new RawRefreshToken("new-refresh-token");
    private static final HashedRefreshToken NEW_REFRESH_HASH = new HashedRefreshToken("b".repeat(64));

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private AccessTokenIssuer accessTokenIssuer;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @Mock
    private RefreshTokenProperties refreshTokenProperties;

    @Mock
    private Clock clock;

    @InjectMocks
    private AuthTokenService authTokenService;

    @Test
    @DisplayName("발급한 토큰 쌍을 반환하고 해당 사용자의 해시와 만료 시각을 세션에 저장한다")
    void issueTokenPairAndSaveMatchingSession() {
        User user = user(7L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        prepareNewTokenPair(7L);

        IssuedTokenPair result = authTokenService.issue(7L);

        assertThat(result).isEqualTo(new IssuedTokenPair(ACCESS_TOKEN, NEW_REFRESH_TOKEN, REFRESH_EXPIRES_AT));
        ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getRefreshTokenHash()).isEqualTo(NEW_REFRESH_HASH);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(result.refreshTokenExpiresAt());
    }

    @Test
    @DisplayName("유효한 토큰을 회전하면 새 토큰 쌍을 반환하고 기존 세션의 해시와 만료 시각을 교체한다")
    void rotateTokenPairAndReplaceSessionCredentials() {
        RawRefreshToken currentToken = new RawRefreshToken("current-refresh-token");
        HashedRefreshToken currentHash = new HashedRefreshToken("a".repeat(64));
        AuthSession session = AuthSession.create(user(7L), currentHash, NOW.plusSeconds(60));
        when(refreshTokenHasher.hash(currentToken)).thenReturn(currentHash);
        when(authSessionRepository.findByRefreshTokenHashForUpdate(currentHash)).thenReturn(Optional.of(session));
        prepareNewTokenPair(7L);

        IssuedTokenPair result = authTokenService.rotate(currentToken);

        assertThat(result).isEqualTo(new IssuedTokenPair(ACCESS_TOKEN, NEW_REFRESH_TOKEN, REFRESH_EXPIRES_AT));
        assertThat(session.getRefreshTokenHash()).isEqualTo(NEW_REFRESH_HASH).isNotEqualTo(currentHash);
        assertThat(session.getExpiresAt()).isEqualTo(result.refreshTokenExpiresAt());
        assertThat(session.getUser().getId()).isEqualTo(7L);
        verify(authSessionRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    @DisplayName("만료 시각에 도달한 세션은 재발급을 거부하고 기존 해시와 만료 시각을 보존한다")
    void rejectExpiredSessionWithoutChangingCredentials(long secondsUntilExpiry) {
        RawRefreshToken currentToken = new RawRefreshToken("expired-refresh-token");
        HashedRefreshToken currentHash = new HashedRefreshToken("a".repeat(64));
        Instant expiresAt = NOW.plusSeconds(secondsUntilExpiry);
        AuthSession session = AuthSession.create(user(7L), currentHash, expiresAt);
        when(refreshTokenHasher.hash(currentToken)).thenReturn(currentHash);
        when(authSessionRepository.findByRefreshTokenHashForUpdate(currentHash)).thenReturn(Optional.of(session));
        when(clock.instant()).thenReturn(NOW);

        assertAuthError(() -> authTokenService.rotate(currentToken), AuthErrorCode.INVALID_REFRESH_TOKEN);

        assertThat(session.getRefreshTokenHash()).isEqualTo(currentHash);
        assertThat(session.getExpiresAt()).isEqualTo(expiresAt);
        verifyNoInteractions(accessTokenIssuer, refreshTokenGenerator);
        verify(authSessionRepository, never()).save(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("양수가 아닌 사용자 ID는 저장소 조회나 Token 생성 전에 거부한다")
    void rejectInvalidUserId(Long userId) {
        assertAuthError(() -> authTokenService.issue(userId), AuthErrorCode.INVALID_USER_ID);

        verifyNoInteractions(userRepository, authSessionRepository, accessTokenIssuer,
                refreshTokenGenerator, refreshTokenHasher);
    }

    @Test
    @DisplayName("존재하지 않는 사용자에게 Token을 발급하거나 세션을 저장하지 않는다")
    void rejectMissingUser() {
        when(userRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertAuthError(() -> authTokenService.issue(999L), AuthErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(authSessionRepository, accessTokenIssuer,
                refreshTokenGenerator, refreshTokenHasher);
    }

    @Test
    @DisplayName("누락된 Refresh Token은 저장소 조회나 Token 생성 전에 거부한다")
    void rejectNullRefreshToken() {
        assertAuthError(() -> authTokenService.rotate(null), AuthErrorCode.INVALID_REFRESH_TOKEN);

        verifyNoInteractions(userRepository, authSessionRepository, accessTokenIssuer,
                refreshTokenGenerator, refreshTokenHasher);
    }

    @Test
    @DisplayName("로그아웃은 원문 토큰의 해시로 잠금 조회한 세션만 삭제한다")
    void deleteMatchingAuthSession() {
        RawRefreshToken token = new RawRefreshToken("refresh-token");
        HashedRefreshToken hash = new HashedRefreshToken("a".repeat(64));
        AuthSession session = AuthSession.create(User.create("총총이", null), hash,
                Instant.parse("2026-12-05T00:00:00Z"));
        when(refreshTokenHasher.hash(token)).thenReturn(hash);
        when(authSessionRepository.findByRefreshTokenHashForUpdate(hash)).thenReturn(Optional.of(session));

        authTokenService.logout(token);

        verify(authSessionRepository).findByRefreshTokenHashForUpdate(hash);
        verify(authSessionRepository).delete(session);
        verifyNoInteractions(userRepository, accessTokenIssuer, refreshTokenGenerator);
    }

    @Test
    @DisplayName("세션이 없는 토큰으로 반복 로그아웃해도 삭제나 토큰 발급을 하지 않는다")
    void logoutIdempotentlyWithoutCurrentSession() {
        RawRefreshToken token = new RawRefreshToken("already-logged-out-token");
        HashedRefreshToken hash = new HashedRefreshToken("b".repeat(64));
        when(refreshTokenHasher.hash(token)).thenReturn(hash);
        when(authSessionRepository.findByRefreshTokenHashForUpdate(hash)).thenReturn(Optional.empty());

        authTokenService.logout(token);
        authTokenService.logout(token);

        verify(authSessionRepository, times(2)).findByRefreshTokenHashForUpdate(hash);
        verify(authSessionRepository, never()).delete(any());
        verifyNoInteractions(userRepository, accessTokenIssuer, refreshTokenGenerator);
    }

    private void prepareNewTokenPair(Long userId) {
        when(accessTokenIssuer.issue(userId)).thenReturn(ACCESS_TOKEN);
        when(refreshTokenGenerator.generate()).thenReturn(NEW_REFRESH_TOKEN);
        when(refreshTokenHasher.hash(NEW_REFRESH_TOKEN)).thenReturn(NEW_REFRESH_HASH);
        when(refreshTokenProperties.validity()).thenReturn(Duration.ofDays(7));
        when(clock.instant()).thenReturn(NOW);
    }

    private User user(Long userId) {
        User user = User.create("총총이", null);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private void assertAuthError(Runnable invocation, AuthErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
