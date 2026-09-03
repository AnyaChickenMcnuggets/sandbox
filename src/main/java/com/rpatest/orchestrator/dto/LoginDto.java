package com.rpatest.orchestrator.dto;

/**
 * Тело запроса POST /api/Account. Схема LTools.Dto.Orchestrator.Security.LoginDto в swagger
 * дополнительно объявляет robotEdition/refreshToken, но подтверждено на реальном стенде, что
 * оркестратор аутентифицирует только по userName/password — лишние поля не отправляем.
 */
public record LoginDto(String userName, String password) {
}
