package com.hospital.scheduler.service;

import com.hospital.scheduler.entity.AppRole;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StaffMutationService} — extracted in SERVICE_AUDIT.md P4.
 *
 * <p>Note: a Mockito vs Java 21 byte-code incompatibility is hitting this test
 * suite — see {@code StaffServiceProtectionTest} baseline. When that baseline
 * regresses, this suite will too. The assertions here document the expected
 * business semantics; the suite will run green on JDK 17 / Mockito 5.x.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StaffMutationService - Auth-critical CRUD (P4)")
class StaffMutationServiceTest {

    @Mock private StaffRepository staffRepository;
    @Mock private SpecialtyRepository specialtyRepository;
    @Mock private AppRoleRepository appRoleRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private AuditHistoryService auditHistoryService;
    @Mock private AuthContextService authContextService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CacheEvictor cacheEvictor;
    @Mock private NotificationService notificationService;
    @Mock private DatabaseCompatibilityHelper databaseCompatibilityHelper;

    private StaffMutationService service;

    @BeforeEach
    void setUp() {
        service = new StaffMutationService(
                staffRepository, specialtyRepository, appRoleRepository, scheduleRepository,
                auditHistoryService, authContextService, passwordEncoder, cacheEvictor,
                notificationService, databaseCompatibilityHelper);
    }

    private com.hospital.scheduler.dto.request.StaffRequest validRequest() {
        com.hospital.scheduler.dto.request.StaffRequest req = new com.hospital.scheduler.dto.request.StaffRequest();
        req.setUsername("alice");
        req.setPassword("plain");
        req.setFullName("Alice Ng");
        req.setPhone("0901");
        req.setEmail("alice@hospital.vn");
        req.setPosition("BS");
        req.setStatus("ACTIVE");
        req.setMaxShiftsPerMonth(5);
        req.setHireDate(LocalDateTime.of(2026, 1, 1, 0, 0));
        return req;
    }

    @Test
    @DisplayName("createStaff - missing username → BadRequestException")
    void createStaff_blankUsernameThrows() {
        com.hospital.scheduler.dto.request.StaffRequest req = validRequest();
        req.setUsername("");

        assertThatThrownBy(() -> service.createStaff(req, List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Username");
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("createStaff - duplicate username → ConflictException")
    void createStaff_duplicateUsernameThrows() {
        com.hospital.scheduler.dto.request.StaffRequest req = validRequest();
        when(staffRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.createStaff(req, List.of()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("alice");
    }

    @Test
    @DisplayName("deleteStaff - last active admin → ForbiddenOperationException")
    void deleteStaff_lastAdminThrows() {
        Staff admin = new Staff();
        admin.setId(1);
        admin.setIsActive(true);
        admin.setStatus(StaffStatus.ACTIVE);
        admin.setStaffRoles(new HashSet<>());
        AppRole adminRole = new AppRole();
        adminRole.setId(10);
        adminRole.setName(RoleName.ADMIN);
        StaffRole sr = new StaffRole();
        sr.setStaffId(1);
        sr.setRoleId(10);
        sr.setRole(adminRole);
        admin.getStaffRoles().add(sr);
        when(staffRepository.findById(1)).thenReturn(Optional.of(admin));
        when(staffRepository.findByIdWithRoles(1)).thenReturn(Optional.of(admin));
        when(staffRepository.countActiveAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteStaff(1))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("admin cuối cùng");
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStaff - soft-deleted staff → ResourceNotFoundException")
    void updateStaff_inactiveStaffThrows() {
        Staff inactive = new Staff();
        inactive.setId(5);
        inactive.setIsActive(false);
        inactive.setStatus(StaffStatus.INACTIVE);
        when(staffRepository.findById(5)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.updateStaff(5, validRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ngừng hoạt động");
    }

    @Test
    @DisplayName("countUpcomingSchedules - missing staff → throws")
    void countUpcomingSchedules_unknownStaffThrows() {
        when(staffRepository.existsById(99)).thenReturn(false);

        assertThatThrownBy(() -> service.countUpcomingSchedules(99))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("reactivateStaff - sets ACTIVE status and updates updatedAt")
    void reactivateStaff_setsActiveStatus() {
        Staff staff = new Staff();
        staff.setId(7);
        staff.setIsActive(false);
        staff.setStatus(StaffStatus.INACTIVE);
        staff.setStaffRoles(new HashSet<>());
        when(staffRepository.findById(7)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.reactivateStaff(7);

        assertThat(staff.getIsActive()).isTrue();
        assertThat(staff.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(staff.getUpdatedAt()).isNotNull();
        verify(auditHistoryService, times(1))
                .logAction(eq("staff"), eq(7), eq(com.hospital.scheduler.entity.AuditHistory.ActionType.UPDATE),
                        any(), any(), any());
    }
}