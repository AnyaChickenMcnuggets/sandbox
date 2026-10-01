package com.rpatest.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.service.AuthService;
import com.rpatest.auth.service.InvalidCredentialsException;
import com.rpatest.auth.service.JwtService;
import com.rpatest.common.web.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

// addFilters=false: /api/v1/auth/** уже permitAll в SecurityConfig (см. SecurityConfigAuthorizationTest),
// здесь тестируется только HTTP-маппинг/сериализация/куки
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthCookies authCookies;

    // JwtAuthenticationFilter (Filter-бин) попадает в @WebMvcTest slice даже при addFilters=false
    @MockBean
    private JwtService jwtService;

    @Test
    void loginSetsAccessAndRefreshCookiesAndReturnsExpiry() throws Exception {
        when(authService.login("alice", "pw")).thenReturn(new AuthService.TokenPair("access-raw", "refresh-raw", 900));
        when(authCookies.accessTokenCookie("access-raw")).thenReturn(cookie(AuthCookies.ACCESS_TOKEN_COOKIE, "access-raw"));
        when(authCookies.refreshTokenCookie("refresh-raw")).thenReturn(cookie(AuthCookies.REFRESH_TOKEN_COOKIE, "refresh-raw"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", "pw"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(result -> assertThat(result.getResponse().getHeaders("Set-Cookie"))
                        .anyMatch(h -> h.contains("access_token=access-raw"))
                        .anyMatch(h -> h.contains("refresh_token=refresh-raw")));
    }

    @Test
    void loginResponseBodyNeverContainsRawTokens() throws Exception {
        when(authService.login("alice", "pw")).thenReturn(new AuthService.TokenPair("secret-access", "secret-refresh", 900));
        when(authCookies.accessTokenCookie("secret-access")).thenReturn(cookie("access_token", "secret-access"));
        when(authCookies.refreshTokenCookie("secret-refresh")).thenReturn(cookie("refresh_token", "secret-refresh"));

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", "pw"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-access").doesNotContain("secret-refresh");
    }

    @Test
    void loginReturnsUnauthorizedForBadCredentials() throws Exception {
        when(authService.login(eq("alice"), eq("wrong"))).thenThrow(new InvalidCredentialsException("Неверный логин или пароль"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginRejectsBlankFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshReadsTokenFromCookieNotBody() throws Exception {
        when(authCookies.readRefreshToken(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of("old-refresh"));
        when(authService.refresh("old-refresh")).thenReturn(new AuthService.TokenPair("new-access", "new-refresh", 900));
        when(authCookies.accessTokenCookie("new-access")).thenReturn(cookie("access_token", "new-access"));
        when(authCookies.refreshTokenCookie("new-refresh")).thenReturn(cookie("refresh_token", "new-refresh"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(AuthCookies.REFRESH_TOKEN_COOKIE, "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(900));

        verify(authService).refresh("old-refresh");
    }

    @Test
    void refreshWithoutCookieReturnsUnauthorized() throws Exception {
        when(authCookies.readRefreshToken(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        verify(authService, never()).refresh(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void logoutRevokesCookieTokenAndClearsCookies() throws Exception {
        when(authCookies.readRefreshToken(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of("some-refresh"));
        when(authCookies.clearAccessTokenCookie()).thenReturn(cookie("access_token", ""));
        when(authCookies.clearRefreshTokenCookie()).thenReturn(cookie("refresh_token", ""));

        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(AuthCookies.REFRESH_TOKEN_COOKIE, "some-refresh")))
                .andExpect(status().isNoContent());

        verify(authService).logout("some-refresh");
    }

    @Test
    @WithMockUser(username = "alice", roles = "OPERATOR")
    void meReturnsUsernameAndRoleFromAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    @WithMockUser(username = "bob", roles = "ADMIN")
    void meReflectsAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void logoutWithoutCookieStillReturnsNoContent() throws Exception {
        when(authCookies.readRefreshToken(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        when(authCookies.clearAccessTokenCookie()).thenReturn(cookie("access_token", ""));
        when(authCookies.clearRefreshTokenCookie()).thenReturn(cookie("refresh_token", ""));

        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());

        verify(authService, never()).logout(org.mockito.ArgumentMatchers.any());
    }

    private ResponseCookie cookie(String name, String value) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Strict").path("/").build();
    }
}
