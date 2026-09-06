package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.BootstrapAdminRequest;
import com.hospital.scheduler.entity.AppPermission;
import com.hospital.scheduler.entity.AppRole;
import com.hospital.scheduler.entity.RoleName;
import com.hospital.scheduler.entity.RolePermission;
import com.hospital.scheduler.entity.Staff;
import com.hospital.scheduler.entity.StaffRole;
import com.hospital.scheduler.exception.BusinessRuleException;
import com.hospital.scheduler.repository.AppPermissionRepository;
import com.hospital.scheduler.repository.AppRoleRepository;
import com.hospital.scheduler.repository.RolePermissionRepository;
import com.hospital.scheduler.repository.StaffRepository;
import com.hospital.scheduler.security.Permissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * One-time bootstrap service for fresh production databases.
 *
 * <p>Creates the very first admin staff account when the {@code staff} table
 * is empty. Subsequent calls throw {@link BusinessRuleException} to prevent
 * accidental re-use on populated databases.</p>
 *
 * <p>Replaces the need to run {@code DataSeeder} on Render (which would
 * insert ~400 rows and exceed Render free tier's 5-minute port-scan window).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapAdminService {

    private final StaffRepository staffRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppPermissionRepository appPermissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Create initial admin + all roles + full permission catalog + role-permission matrix.
     *
     * <p>Idempotent across partial bootstraps: if staff table already has data,
     * throws {@link BusinessRuleException}. Otherwise runs every step but
     * skips inserts that would conflict with existing rows.</p>
     *
     * @throws BusinessRuleException if staff table is not empty
     */
    @Transactional
    public Staff bootstrapAdmin(BootstrapAdminRequest request) {
        long existingStaffCount = staffRepository.count();
        if (existingStaffCount > 0) {
            throw new BusinessRuleException(
                "Bootstrap endpoint is disabled — staff table already has " + existingStaffCount + " user(s). " +
                "Use the existing admin account or reset the database to bootstrap again."
            );
        }

        // ── 1. Seed permission catalog (idempotent) ────────────────────────
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
        log.info("✅ Seeded {} new permissions (catalog size: {})", permsCreated, catalog.size());

        // ── 2. Seed roles (idempotent) ────────────────────────────────────
        AppRole adminRole = appRoleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.ADMIN)
                        .description("Quản trị hệ thống")
                        .isActive(true)
                        .build()));

        AppRole managerRole = appRoleRepository.findByName(RoleName.MANAGER)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.MANAGER)
                        .description("Quản lý và duyệt lịch")
                        .isActive(true)
                        .build()));

        AppRole staffRole = appRoleRepository.findByName(RoleName.STAFF)
                .orElseGet(() -> appRoleRepository.save(AppRole.builder()
                        .name(RoleName.STAFF)
                        .description("Nhân viên sử dụng hệ thống")
                        .isActive(true)
                        .build()));

        // ── 3. Seed role-permission matrix (idempotent reset) ─────────────
        // Reset existing role-permission mappings so any new permissions added
        // to the catalog are applied cleanly.
        rolePermissionRepository.deleteByRoleId(adminRole.getId());
        rolePermissionRepository.deleteByRoleId(managerRole.getId());
        rolePermissionRepository.deleteByRoleId(staffRole.getId());

        // Cache permission id theo tên
        Map<String, Integer> permIds = new java.util.HashMap<>();
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
        log.info("✅ Seeded role-permission matrix: ADMIN={}, MANAGER={}, STAFF={}",
                adminCount, managerCount, staffCount);

        // ── 4. Create the admin staff record ──────────────────────────────
        Staff admin = Staff.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .maxShiftsPerMonth(5)
                .isActive(true)
                .build();
        admin = staffRepository.save(admin);

        // ── 5. Assign ADMIN + MANAGER roles to bootstrap admin ────────────
        admin.getStaffRoles().add(StaffRole.builder()
                .staffId(admin.getId()).roleId(adminRole.getId()).build());
        admin.getStaffRoles().add(StaffRole.builder()
                .staffId(admin.getId()).roleId(managerRole.getId()).build());
        staffRepository.save(admin);

        log.info("✅ Bootstrap admin '{}' (id={}) created with ADMIN + MANAGER roles",
                admin.getUsername(), admin.getId());
        return admin;
    }
}
