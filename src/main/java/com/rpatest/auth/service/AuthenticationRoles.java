package com.rpatest.auth.service;

import com.rpatest.auth.domain.Role;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Единственное место, где {@code ROLE_<ROLE>}-authority из {@code Authentication} превращается
 * обратно в {@link Role} (кладёт её туда {@code JwtAuthenticationFilter}). У анонимного
 * пользователя authority {@code ROLE_ANONYMOUS} — это не {@link Role}, результат пустой. */
public final class AuthenticationRoles {

    private static final String PREFIX = "ROLE_";

    private AuthenticationRoles() {
    }

    public static Optional<Role> roleOf(Authentication authentication) {
        if (authentication == null) {
            return Optional.empty();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(PREFIX))
                .map(a -> a.substring(PREFIX.length()))
                .flatMap(name -> Arrays.stream(Role.values()).filter(r -> r.name().equals(name)))
                .findFirst();
    }
}
