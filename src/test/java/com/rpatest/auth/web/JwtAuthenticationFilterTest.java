package com.rpatest.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.JwtService;
import com.rpatest.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final AuthCookies authCookies = new AuthCookies(new AuthProperties());
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, authCookies);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsAuthenticationWithRoleAuthorityForValidBearerToken() throws Exception {
        when(jwtService.parse("valid-token")).thenReturn(new JwtService.AccessTokenClaims("alice", Role.OPERATOR));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
        verify(chain).doFilter(request, response);
    }

    @Test
    void setsAuthenticationFromAccessTokenCookieWhenNoHeaderPresent() throws Exception {
        when(jwtService.parse("cookie-token")).thenReturn(new JwtService.AccessTokenClaims("bob", Role.VIEWER));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN_COOKIE, "cookie-token"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("bob");
    }

    @Test
    void prefersCookieOverHeaderWhenBothPresent() throws Exception {
        when(jwtService.parse("cookie-token")).thenReturn(new JwtService.AccessTokenClaims("cookie-user", Role.VIEWER));
        when(jwtService.parse("header-token")).thenReturn(new JwtService.AccessTokenClaims("header-user", Role.ADMIN));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN_COOKIE, "cookie-token"));
        request.addHeader("Authorization", "Bearer header-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("cookie-user");
    }

    @Test
    void leavesContextEmptyForMissingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesContextEmptyForInvalidToken() throws Exception {
        when(jwtService.parse("bad-token")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresNonBearerAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
