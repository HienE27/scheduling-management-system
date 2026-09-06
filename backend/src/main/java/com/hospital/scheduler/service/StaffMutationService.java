package com.hospital.scheduler.service;

import com.hospital.scheduler.config.CacheConfig;
import com.hospital.scheduler.dto.request.NotificationDTO;
import com.hospital.scheduler.dto.request.StaffRequest;
import com.hospital.scheduler.dto.response.StaffResponse;
import com.hospital.scheduler.entity.AppRole;
import com.hospital.scheduler.entity.AuditHistory;
import com.hospital.scheduler.entity.RoleName;
import com.hospital.scheduler.entity.Specialty;
import com.hospital.scheduler.entity.Staff;
import com.hospital.scheduler.entity.StaffRole;
import com.hospital.scheduler.entity.StaffStatus;
import com.hospital.scheduler.exception.BadRequestException;
import com.hospital.scheduler.exception.ConflictException;
import com.hospital.scheduler.exception.ForbiddenOperationException;
import com.hospital.scheduler.exception.ResourceNotFoundException;
import com.hospital.scheduler.repository.AppRoleRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.repository.SpecialtyRepository;
import com.hospital.scheduler.repository.StaffRepository;
import com.hospital.scheduler.security.AuthContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns all state-changing operations on {@link Staff}: create, update,
 * soft-delete, reactivate, and the upcoming-schedule count helper used by the
 * staff edit page.
 *
 * <p>Extracted from {@link StaffService} in SERVICE_AUDIT.md P4 so that the
 * read path stays thin and the mutation surface (which carries the
 * authentication-critical logic) lives in one focused class. {@link StaffService}
 * delegates to this class via thin wrappers so the controller surface is
 * unchanged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StaffMutationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 10;

    private final StaffRepository staffRepository;
    private final SpecialtyRepository specialtyRepository;
    private final AppRoleRepository appRoleRepository;
    private final ScheduleRepository scheduleRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuthContextService authContextService;
    private final PasswordEncoder passwordEncoder;
    private final CacheEvictor cacheEvictor;
    @Lazy
    private final NotificationService notificationService;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;

    /**
     * Create a new staff member with optional role assignment.
     *
     * <p>BUGFIX (was #1): Wrapping the principal lookup in try/catch keeps the
     * staff create from being rolled back by a missing actor — audit must
     * NEVER block a successful business write. Matches
     * {@code LeaveRequestService.createLeaveRequest}.</p>
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public StaffResponse createStaff(StaffRequest request, List<String> roles) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BadRequestException("Username không được để trống");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BadRequestException("Password không được để trống");
        }
        if (staffRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException("Username '" + request.getUsername() + "' đã tồn tại");
        }
        if (request.getEmail() != null && staffRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email '" + request.getEmail() + "' đã tồn tại");
        }

        Specialty specialty = null;
        if (request.getSpecialtyId() != null) {
            specialty = specialtyRepository.findById(request.getSpecialtyId()).orElse(null);
        }

        String statusStr = request.getStatus() != null ? request.getStatus() : "ACTIVE";
        boolean isActive = !"INACTIVE".equalsIgnoreCase(statusStr);
        StaffStatus status = switch (statusStr.toUpperCase()) {
            case "ON_LEAVE" -> StaffStatus.ON_LEAVE;
            case "INACTIVE" -> StaffStatus.INACTIVE;
            default -> StaffStatus.ACTIVE;
        };

        Staff staff = Staff.builder()
                .staffCode(generateStaffCode())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .email(request.getEmail())
                .position(request.getPosition())
                .specialty(specialty)
                .maxShiftsPerMonth(request.getMaxShiftsPerMonth() != null ? request.getMaxShiftsPerMonth() : 5)
                .isActive(isActive)
                .status(status)
                .hireDate(request.getHireDate())
                .staffRoles(new HashSet<>())
                .build();

        Staff saved = staffRepository.save(staff);

        // Assign roles
        if (roles != null && !roles.isEmpty()) {
            for (String roleName : roles) {
                AppRole role = appRoleRepository.findByName(RoleName.valueOf(roleName.toUpperCase()))
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy role: " + roleName));
                StaffRole sr = StaffRole.builder()
                        .staffId(saved.getId())
                        .roleId(role.getId())
                        .build();
                saved.getStaffRoles().add(sr);
            }
            staffRepository.save(saved);
        }

        StaffResponse created = toResponse(staffRepository.findByIdWithRoles(saved.getId()).orElse(saved));
        Integer actorId = resolveCurrentStaffIdSafely();
        auditHistoryService.logAction("staff", saved.getId(), AuditHistory.ActionType.INSERT, null, created, actorId);
        cacheEvictor.evictDashboard();
        return created;
    }

    /**
     * BUG-C3 fix: evict dashboard cache on update.
     * BUG-M5 fix: reject update on soft-deleted (inactive) staff.
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public StaffResponse updateStaff(Integer id, StaffRequest request) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        if (!Boolean.TRUE.equals(staff.getIsActive())) {
            throw new ResourceNotFoundException("Nhân sự không tồn tại hoặc đã ngừng hoạt động");
        }

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            staffRepository.findByUsername(request.getUsername())
                    .filter(s -> !s.getId().equals(id))
                    .ifPresent(s -> {
                        throw new ConflictException("Username '" + request.getUsername() + "' đã tồn tại");
                    });
        }

        if (request.getEmail() != null) {
            staffRepository.findByEmail(request.getEmail())
                    .filter(s -> !s.getId().equals(id))
                    .ifPresent(s -> {
                        throw new ConflictException("Email '" + request.getEmail() + "' đã tồn tại");
                    });
        }

        Staff oldStaff = Staff.builder()
                .username(staff.getUsername())
                .fullName(staff.getFullName())
                .phone(staff.getPhone())
                .email(staff.getEmail())
                .position(staff.getPosition())
                .maxShiftsPerMonth(staff.getMaxShiftsPerMonth())
                .specialty(staff.getSpecialty())
                .isActive(staff.getIsActive())
                .status(staff.getStatus())
                .build();

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            staff.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            staff.setUsername(request.getUsername());
        }
        staff.setFullName(request.getFullName());
        staff.setPhone(request.getPhone());
        staff.setEmail(request.getEmail());
        staff.setPosition(request.getPosition());
        staff.setMaxShiftsPerMonth(request.getMaxShiftsPerMonth() != null ? request.getMaxShiftsPerMonth() : 5);

        if (request.getHireDate() != null) {
            staff.setHireDate(request.getHireDate());
        }

        if (request.getSpecialtyId() != null) {
            Specialty specialty = specialtyRepository.findById(request.getSpecialtyId()).orElse(null);
            staff.setSpecialty(specialty);
        }

        if (request.getStatus() != null) {
            StaffStatus newStatus = switch (request.getStatus().toUpperCase()) {
                case "ON_LEAVE" -> StaffStatus.ON_LEAVE;
                case "INACTIVE" -> StaffStatus.INACTIVE;
                default -> StaffStatus.ACTIVE;
            };
            staff.setStatus(newStatus);
            staff.setIsActive(newStatus != StaffStatus.INACTIVE);
        }

        if (request.getIsActive() != null) {
            staff.setIsActive(request.getIsActive());
            if (request.getIsActive()) {
                if (staff.getStatus() == StaffStatus.INACTIVE) {
                    staff.setStatus(StaffStatus.ACTIVE);
                }
            }
        }

        Staff saved = staffRepository.save(staff);
        StaffResponse updated = toResponse(saved);
        Integer actorId = resolveCurrentStaffIdSafely();
        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.UPDATE, oldStaff, updated, actorId);
        cacheEvictor.evictDashboard();
        return updated;
    }

    /**
     * Soft-delete a staff member. BUGFIX (was BE#11): previous version logged
     * ActionType.UPDATE; corrected to DELETE so audit queries by action type
     * don't miss staff soft-deletes.
     */
    public void deleteStaff(Integer id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        if (hasAdminRole(id)) {
            long activeAdminCount = staffRepository.countActiveAdmins();
            if (activeAdminCount <= 1) {
                throw new ForbiddenOperationException("Không thể xóa admin cuối cùng của hệ thống");
            }
        }

        Staff oldStaff = Staff.builder()
                .username(staff.getUsername())
                .fullName(staff.getFullName())
                .isActive(staff.getIsActive())
                .status(staff.getStatus())
                .build();

        staff.setIsActive(false);
        staff.setStatus(StaffStatus.INACTIVE);
        staff.setUpdatedAt(LocalDateTime.now());
        staffRepository.save(staff);

        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.DELETE,
                oldStaff, staff, resolveCurrentStaffIdSafely());

        try {
            notificationService.createNotification(staff.getId(),
                    new NotificationDTO("Tài khoản đã bị dừng hoạt động",
                            "Tài khoản của bạn đã được quản lý dừng hoạt động. Liên hệ quản trị viên để kích hoạt lại."));
        } catch (Exception ignored) {
            // Notification delivery must never fail the deletion.
        }

        cacheEvictor.evictDashboard();
    }

    /**
     * Re-activate a previously soft-deleted staff member.
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public StaffResponse reactivateStaff(Integer id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        Staff oldStaff = Staff.builder()
                .username(staff.getUsername())
                .fullName(staff.getFullName())
                .isActive(staff.getIsActive())
                .status(staff.getStatus())
                .build();

        staff.setIsActive(true);
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setUpdatedAt(LocalDateTime.now());
        Staff saved = staffRepository.save(staff);

        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.UPDATE,
                oldStaff, saved, resolveCurrentStaffIdSafely());

        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    /**
     * Count schedules for the given staff member on or after today.
     * Used by the frontend to warn before deactivating a staff who still has
     * upcoming assignments.
     */
    public long countUpcomingSchedules(Integer id) {
        if (!staffRepository.existsById(id)) {
            throw new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id);
        }
        return scheduleRepository.countByStaffIdAndWorkDateGreaterThanEqual(id, LocalDate.now());
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    private boolean hasAdminRole(Integer staffId) {
        return staffRepository.findByIdWithRoles(staffId)
                .map(s -> s.getStaffRoles().stream()
                        .anyMatch(sr -> sr.getRole() != null
                                && sr.getRole().getName() == RoleName.ADMIN))
                .orElse(false);
    }

    private String generateStaffCode() {
        // Format: NV001, NV002, ... Uses DB MAX query for efficiency.
        String prefix = "NV";
        int maxNum = databaseCompatibilityHelper.findMaxStaffCodeNumber(prefix, prefix.length());
        return prefix + String.format("%03d", maxNum + 1);
    }

    /**
     * Resolve the actor for audit logging. Returns null if no authenticated
     * principal is available. Returning null rather than throwing lets the
     * business transaction commit even when auditing can't be attributed —
     * the audit row stays nullable today but still records the action.
     */
    private Integer resolveCurrentStaffIdSafely() {
        try {
            return authContextService.getCurrentStaff().getId();
        } catch (Exception ex) {
            log.debug("No authenticated staff for audit actor ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    private StaffResponse toResponse(Staff staff) {
        return toResponse(staff, false);
    }

    private StaffResponse toResponse(Staff staff, boolean maskSensitive) {
        StaffResponse.SpecialtyResponse specialtyResp = null;
        if (staff.getSpecialty() != null) {
            specialtyResp = StaffResponse.SpecialtyResponse.builder()
                    .id(staff.getSpecialty().getId())
                    .name(staff.getSpecialty().getName())
                    .build();
        }

        List<String> roleNames = staff.getStaffRoles().stream()
                .map(sr -> sr.getRole() != null ? sr.getRole().getName().name() : null)
                .filter(r -> r != null)
                .collect(java.util.stream.Collectors.toList());

        return StaffResponse.builder()
                .id(staff.getId())
                .staffCode(staff.getStaffCode())
                .username(staff.getUsername())
                .fullName(staff.getFullName())
                .phone(staff.getPhone())
                .email(staff.getEmail())
                .position(staff.getPosition())
                .specialty(specialtyResp)
                .status(staff.getStatus() != null ? staff.getStatus().name() : "ACTIVE")
                .isActive(staff.getIsActive())
                .hireDate(staff.getHireDate())
                .maxShiftsPerMonth(staff.getMaxShiftsPerMonth())
                .roles(roleNames)
                .build();
    }
}