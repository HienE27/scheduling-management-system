package com.hospital.scheduler.service;

import com.hospital.scheduler.entity.AppPermission;
import com.hospital.scheduler.entity.AppRole;
import com.hospital.scheduler.entity.RoleName;
import com.hospital.scheduler.entity.RolePermission;
import com.hospital.scheduler.exception.BusinessRuleException;
import com.hospital.scheduler.repository.AppPermissionRepository;
import com.hospital.scheduler.repository.AppRoleRepository;
import com.hospital.scheduler.repository.RolePermissionRepository;
import com.hospital.scheduler.security.Permissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Companion to {@link BootstrapAdminService}: seeds the permission catalog and
 * role-permission matrix without touching the staff table.
 *
 * <p>Needed when an admin user was bootstrapped first (e.g. via the old SQL
 * script or an early {@code /bootstrap-admin} call) and the permission
 * catalog is still empty. Idempotent — re-running with the catalog already
 * populated only resets the role-permission matrix.</p>
 *
 * <p>Refuses to run if any role-permission mapping already exists, to avoid
 * clobbering a production RBAC matrix that was hand-tuned.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapPermissionsService {

    private final AppRoleRepository appRoleRepository;
    private final AppPermissionRepository appPermissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Transactional
    public Map<String, Object> bootstrapPermissions() {
        long existingMatrixCount = rolePermissionRepository.count();
        if (existingMatrixCount > 0) {
            throw new BusinessRuleException(
                "Bootstrap permissions endpoint is disabled — role_permission table already has " +
                existingMatrixCount + " row(s). Use the existing RBAC or reset the database."
            );
        }

        // ── 1. Ensure all 3 roles exist (idempotent) ──────────────────────
        AppRole adminRole = appRoleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.ADMIN).description("Quản trị hệ thống").isActive(true).build()));
        AppRole managerRole = appRoleRepository.findByName(RoleName.MANAGER)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.MANAGER).description("Quản lý và duyệt lịch").isActive(true).build()));
        AppRole staffRole = appRoleRepository.findByName(RoleName.STAFF)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.STAFF).description("Nhân viên sử dụng hệ thống").isActive(true).build()));

        // ── 2. Seed permission catalog ────────────────────────────────────
        int permsCreated = 0;
        Map<String, String> catalog = Permissions.catalog();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            if (!appPermissionRepository.existsByName(entry.getKey())) {
                appPermissionRepository.save(AppPermission.builder()
                        .name(entry.getKey())
                        .description(entry.getValue())
                        .isActive(true)
                        .build());
                permsCreated++;
            }
        }

        // ── 3. Build role-permission matrix ───────────────────────────────
        Map<String, Integer> permIds = new HashMap<>();
        for (AppPermission p : appPermissionRepository.findAll()) {
            permIds.put(p.getName(), p.getId());
        }

        int adminCount = 0, managerCount = 0, staffCount = 0;
        for (Map.Entry<String, Integer> entry : permIds.entrySet()) {
            String permName = entry.getKey();
            Integer permId = entry.getValue();

            if (Permissions.allPermissions().contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(adminRole.getId()).permissionId(permId).build());
                adminCount++;
            }
            if (Permissions.managerPermissions().contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(managerRole.getId()).permissionId(permId).build());
                managerCount++;
            }
            if (Permissions.staffPermissions().contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(staffRole.getId()).permissionId(permId).build());
                staffCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("permissionsCreated", permsCreated);
        result.put("permissionsTotal", catalog.size());
        result.put("adminPermissions", adminCount);
        result.put("managerPermissions", managerCount);
        result.put("staffPermissions", staffCount);
        log.info("✅ Bootstrap permissions complete: catalog {}/{} new, matrix ADMIN={} MANAGER={} STAFF={}",
                permsCreated, catalog.size(), adminCount, managerCount, staffCount);
        return result;
    }
}
