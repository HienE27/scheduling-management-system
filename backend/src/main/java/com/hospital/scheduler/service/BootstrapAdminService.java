package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.BootstrapAdminRequest;
import com.hospital.scheduler.entity.AppRole;
import com.hospital.scheduler.entity.RoleName;
import com.hospital.scheduler.entity.Staff;
import com.hospital.scheduler.entity.StaffRole;
import com.hospital.scheduler.exception.BusinessRuleException;
import com.hospital.scheduler.repository.AppRoleRepository;
import com.hospital.scheduler.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PasswordEncoder passwordEncoder;

    /**
     * Create initial admin + MANAGER roles + ADMIN role mapping.
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

        // Ensure ADMIN + MANAGER roles exist (idempotent).
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

        // Create the admin staff record.
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

        // Assign both ADMIN and MANAGER roles so the bootstrap user can manage schedules immediately.
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
