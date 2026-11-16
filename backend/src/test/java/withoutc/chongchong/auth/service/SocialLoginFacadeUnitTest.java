package withoutc.chongchong.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import withoutc.chongchong.auth.social.SocialLoginClients;
import withoutc.chongchong.auth.social.SocialLoginCommand;
import withoutc.chongchong.auth.social.SocialProvider;
import withoutc.chongchong.auth.social.SocialUserInfo;
import withoutc.chongchong.auth.token.IssuedAccessToken;
import withoutc.chongchong.auth.token.IssuedTokenPair;
import withoutc.chongchong.auth.token.RawRefreshToken;

@ExtendWith(MockitoExtension.class)
class SocialLoginFacadeUnitTest {

    @Mock
    private SocialLoginClients socialLoginClients;

    @Mock
    private SocialLoginService socialLoginService;

    @InjectMocks
    private SocialLoginFacade socialLoginFacade;

    @Test
    @DisplayName("소셜 로그인에 성공하면 사용자 ID와 발급된 토큰을 반환한다")
    void returnUserIdAndIssuedTokensWhenLoginSucceeds() {
        SocialLoginCommand command = new SocialLoginCommand(SocialProvider.GOOGLE, "valid-credential");
        SocialUserInfo socialUserInfo = new SocialUserInfo(
                SocialProvider.GOOGLE, "google-user-id", "총총이", null
        );
        IssuedTokenPair tokenPair = new IssuedTokenPair(
                new IssuedAccessToken("access-token", Instant.parse("2026-11-17T01:00:00Z")),
                new RawRefreshToken("refresh-token"),
                Instant.parse("2026-12-17T00:00:00Z")
        );
        when(socialLoginClients.authenticate(command)).thenReturn(socialUserInfo);
        when(socialLoginService.login(socialUserInfo)).thenReturn(SocialLoginResult.of(42L, tokenPair));

        SocialLoginResult result = socialLoginFacade.login(command);

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.tokenPair()).isEqualTo(tokenPair);
    }
}
