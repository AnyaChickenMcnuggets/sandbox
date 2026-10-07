package com.rpatest.auth.service;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.domain.RolePermission;
import com.rpatest.auth.repository.RolePermissionRepository;
import com.rpatest.common.exception.InvalidRequestException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Единственный источник правды "какие права у роли" (ADR 0005). Читается на каждый запрос
 * (авторизация), поэтому кэшируется в памяти процесса и сбрасывается ПОСЛЕ коммита изменения —
 * иначе параллельный запрос мог бы закэшировать ещё не закоммиченное старое состояние. Кэш
 * локален процессу: при нескольких инстансах сервиса изменение матрицы на одном не дойдёт до
 * остальных без рестарта/общего механизма инвалидации — сейчас сервис односерверный.
 */
@Service
public class RolePermissionService {

    private final RolePermissionRepository repository;
    private final Map<Role, Set<Permission>> cache = new ConcurrentHashMap<>();

    public RolePermissionService(RolePermissionRepository repository) {
        this.repository = repository;
    }

    /** {@link Role#ADMIN} — всегда все права, без обращения к БД: роль, которая может лишить саму
     * себя управления матрицей, превращает опечатку в безвозвратную блокировку системы. */
    public Set<Permission> permissionsOf(Role role) {
        if (role == Role.ADMIN) {
            return Collections.unmodifiableSet(EnumSet.allOf(Permission.class));
        }
        Set<Permission> cached = cache.get(role);
        if (cached != null) {
            return cached;
        }
        Set<Permission> loaded = repository.findByRole(role).stream()
                .map(RolePermission::getPermission)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
        Set<Permission> immutable = Collections.unmodifiableSet(loaded);
        cache.put(role, immutable);
        return immutable;
    }

    public boolean has(Role role, Permission permission) {
        return permissionsOf(role).contains(permission);
    }

    public Map<Role, Set<Permission>> matrix() {
        Map<Role, Set<Permission>> matrix = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            matrix.put(role, permissionsOf(role));
        }
        return matrix;
    }

    @Transactional
    public Set<Permission> replacePermissions(Role role, Set<Permission> permissions) {
        if (role == Role.ADMIN) {
            throw new InvalidRequestException("Права роли ADMIN не редактируются: она всегда имеет все права");
        }
        Set<Permission> target = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
        repository.deleteByRole(role);
        repository.flush();
        repository.saveAll(target.stream().map(p -> new RolePermission(role, p)).toList());
        evictAfterCommit(role);
        return Collections.unmodifiableSet(target);
    }

    private void evictAfterCommit(Role role) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(role);
                }
            });
        } else {
            cache.remove(role);
        }
    }
}
