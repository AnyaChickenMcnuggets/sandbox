package com.rpatest.auth.domain;

/**
 * Закрытый список прав — задаётся кодом (каждое право привязано к конкретным эндпоинтам в
 * {@code SecurityConfig}), админ распределяет их по ролям через {@code /api/v1/admin/roles}
 * (ADR 0005), но завести новое право через API нельзя: право без привязки к эндпоинту ничего не
 * защищает. Роль {@link Role#ADMIN} всегда имеет все права и не редактируется.
 */
public enum Permission {
    SCENARIO_READ("Просмотр списка и деталей сценариев"),
    SCENARIO_WRITE("Создание и редактирование сценариев"),
    SCENARIO_DELETE("Удаление сценариев"),
    RUN_READ("Просмотр прогонов, шагов и элементов очередей"),
    RUN_START("Запуск сценариев"),
    RUN_STOP("Остановка прогонов"),
    CLEANUP("Очистка сущностей оркестратора, созданных прогоном"),
    ORCHESTRATOR_READ("Просмотр состояния оркестратора (доступность роботов)"),
    USER_MANAGE("Управление пользователями"),
    ROLE_MANAGE("Управление матрицей ролей и прав");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
