package com.rpatest.auth.repository;

import com.rpatest.auth.domain.Role;
import com.rpatest.auth.domain.RolePermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRole(Role role);

    void deleteByRole(Role role);
}
