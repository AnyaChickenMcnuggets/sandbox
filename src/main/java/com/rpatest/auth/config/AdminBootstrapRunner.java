package com.rpatest.auth.config;

import com.rpatest.auth.domain.Role;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.auth.service.AppUserService;
import com.rpatest.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Заводит единственного ADMIN при старте, если {@code app_user} пуста — не через Flyway-миграцию,
 * чтобы реальный пароль не становился содержимым репозитория ни в каком виде (см. ADR 0003).
 * Идемпотентно: срабатывает только когда таблица пуста, повторные запуски приложения не трогают
 * уже заведённых пользователей.
 */
@Component
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AppUserRepository appUserRepository;
    private final AppUserService appUserService;
    private final AuthProperties.BootstrapAdmin properties;

    public AdminBootstrapRunner(
            AppUserRepository appUserRepository, AppUserService appUserService, AuthProperties properties) {
        this.appUserRepository = appUserRepository;
        this.appUserService = appUserService;
        this.properties = properties.getBootstrapAdmin();
    }

    @Override
    public void run(String... args) {
        if (appUserRepository.count() > 0) {
            return;
        }
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            log.warn("Таблица app_user пуста, но auth.bootstrap-admin.password не задан — "
                    + "первый ADMIN не создан, войти в систему будет некому");
            return;
        }
        appUserService.create(properties.getUsername(), properties.getPassword(), Role.ADMIN);
        log.warn("Создан первый ADMIN '{}' — смените пароль сразу после первого входа", properties.getUsername());
    }
}
