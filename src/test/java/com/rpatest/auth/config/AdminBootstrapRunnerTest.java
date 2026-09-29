package com.rpatest.auth.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.Role;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.auth.service.AppUserService;
import com.rpatest.config.AuthProperties;
import org.junit.jupiter.api.Test;

class AdminBootstrapRunnerTest {

    @Test
    void createsAdminWhenTableEmptyAndPasswordConfigured() throws Exception {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUserService service = mock(AppUserService.class);
        AuthProperties properties = new AuthProperties();
        properties.getBootstrapAdmin().setUsername("admin");
        properties.getBootstrapAdmin().setPassword("initial-password");
        when(repository.count()).thenReturn(0L);

        new AdminBootstrapRunner(repository, service, properties).run();

        verify(service).create("admin", "initial-password", Role.ADMIN);
    }

    @Test
    void doesNothingWhenTableAlreadyHasUsers() throws Exception {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUserService service = mock(AppUserService.class);
        AuthProperties properties = new AuthProperties();
        properties.getBootstrapAdmin().setPassword("initial-password");
        when(repository.count()).thenReturn(1L);

        new AdminBootstrapRunner(repository, service, properties).run();

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void doesNothingWhenNoPasswordConfigured() throws Exception {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUserService service = mock(AppUserService.class);
        AuthProperties properties = new AuthProperties();
        when(repository.count()).thenReturn(0L);

        new AdminBootstrapRunner(repository, service, properties).run();

        verify(service, never()).create(any(), any(), any());
    }
}
