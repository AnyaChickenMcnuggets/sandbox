package com.rpatest.auth.service;

/** Неверный логин/пароль, отключённый пользователь, или недействительный (просроченный/отозванный/
 * неизвестный) refresh-токен — во всех случаях сообщение намеренно не различает причину для
 * клиента (не подсказывать, существует ли username), различие есть только в логах. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
