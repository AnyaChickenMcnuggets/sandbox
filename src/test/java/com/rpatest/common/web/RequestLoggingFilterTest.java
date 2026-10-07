package com.rpatest.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsStartAndEndWithCallerStatusAndDuration() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scenarios/5/run");
        request.setRemoteAddr("10.1.2.3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(202);

        filter.doFilter(request, response, chain);

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo("--> POST /api/v1/scenarios/5/run from user=alice ip=10.1.2.3");
        assertThat(messages.get(1))
                .startsWith("<-- POST /api/v1/scenarios/5/run status=202 user=alice ip=10.1.2.3 took ")
                .endsWith(" ms");
    }

    @Test
    void logsAnonymousCallerAndQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.setQueryString("page=2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));

        assertThat(appender.list.get(0).getFormattedMessage()).contains("GET /api/v1/runs?page=2").contains("user=anonymous");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("status=401").contains("user=anonymous");
    }

    @Test
    void logsErrorWithDurationAndRethrowsWhenChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).hasMessageContaining("boom");

        ILoggingEvent last = appender.list.get(appender.list.size() - 1);
        assertThat(last.getLevel()).isEqualTo(Level.ERROR);
        assertThat(last.getFormattedMessage()).contains("unhandled error").contains(" took ");
    }

    @Test
    void neverLogsHeadersOrCookies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setCookies(new jakarta.servlet.http.Cookie("access_token", "secret-cookie"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("secret-token").doesNotContain("secret-cookie"));
    }
}
