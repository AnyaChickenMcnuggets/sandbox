package com.rpatest.orchestrator.dto;

/** Mirrors LTools.Dto.Orchestrator.Security.LoginDto from orc_swagger.json. */
public record LoginDto(String userName, String password, int robotEdition, String refreshToken) {

    public static LoginDto of(String userName, String password, int robotEdition) {
        return new LoginDto(userName, password, robotEdition, null);
    }
}
