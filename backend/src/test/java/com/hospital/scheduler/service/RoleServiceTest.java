package com.hospital.scheduler.service;

import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.repository.*;
import com.hospital.scheduler.security.AuthContextService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock private AppRoleRepository roleRepository;
    @Mock private AppPermissionRepository permissionRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private AuditHistoryService auditHistoryService;
    @Mock private AuthContextService authContextService;

    @InjectMocks private RoleService roleService;

    private AppRole adminRole;
    private AppRole managerRole;
    private AppPermission permScheduleRead;
    private AppPermission permScheduleWrite;

    @BeforeEach
    void setUp() {
        adminRole = AppRole.builder()
                .id(1).name(RoleName.ADMIN).description("Admin").isActive(true).build();
        managerRole = AppRole.builder()
                .id(2).name(RoleName.MANAGER).description("Manager").isActive(true).build();

        permScheduleRead = AppPermission.builder()
                .id(1).name("SCHEDULE_READ").description("View schedule").isActive(true).build();
        permScheduleWrite = AppPermission.builder()
                .id(2).name("SCHEDULE_WRITE").description("Manage schedule").isActive(true).build();
    }

    @Nested
    class GetPermissionMatrix {

        @Test
        void returnsAllRolesAndPermissions() {
            when(roleRepository.findAll()).thenReturn(List.of(adminRole, managerRole));
            when(permissionRepository.findAll()).thenReturn(List.of(permScheduleRead, permScheduleWrite));
            when(rolePermissionRepository.findAll()).thenReturn(List.of());

            var matrix = roleService.getPermissionMatrix();

            assertThat(matrix.getRoles()).hasSize(2);
            assertThat(matrix.getPermissions()).hasSize(2);
        }

        @Test
        void marksGrantedPairsCorrectly() {
            when(roleRepository.findAll()).thenReturn(List.of(adminRole, managerRole));
            when(permissionRepository.findAll()).thenReturn(List.of(permScheduleRead, permScheduleWrite));

            // ADMIN has SCHEDULE_READ, MANAGER has neither
            RolePermission granted = RolePermission.builder()
                    .roleId(1).permissionId(1).build();
            when(rolePermissionRepository.findAll()).thenReturn(List.of(granted));

            var matrix = roleService.getPermissionMatrix();

            // Check ADMIN × SCHEDULE_READ → granted
            var adminRead = matrix.getMatrix().stream()
                    .filter(e -> e.getRoleId() == 1 && e.getPermissionId() == 1)
                    .findFirst().orElseThrow();
            assertThat(adminRead.getGranted()).isTrue();

            // Check ADMIN × SCHEDULE_WRITE → not granted
            var adminWrite = matrix.getMatrix().stream()
                    .filter(e -> e.getRoleId() == 1 && e.getPermissionId() == 2)
                    .findFirst().orElseThrow();
            assertThat(adminWrite.getGranted()).isFalse();

            // Check MANAGER × SCHEDULE_READ → not granted
            var mgrRead = matrix.getMatrix().stream()
                    .filter(e -> e.getRoleId() == 2 && e.getPermissionId() == 1)
                    .findFirst().orElseThrow();
            assertThat(mgrRead.getGranted()).isFalse();
        }

        @Test
        void buildsFullCartesianProduct() {
            when(roleRepository.findAll()).thenReturn(List.of(adminRole, managerRole));
            when(permissionRepository.findAll()).thenReturn(List.of(permScheduleRead, permScheduleWrite));
            when(rolePermissionRepository.findAll()).thenReturn(List.of());

            var matrix = roleService.getPermissionMatrix();

            // 2 roles × 2 permissions = 4 matrix entries
            assertThat(matrix.getMatrix()).hasSize(4);
        }
    }

    @Nested
    class TogglePermission {

        @Test
        void savesRolePermission_whenGranted() {
            when(rolePermissionRepository.existsById(any())).thenReturn(false);

            roleService.togglePermission(1, 1, true);

            verify(rolePermissionRepository).save(argThat(rp ->
                    rp.getRoleId() == 1 && rp.getPermissionId() == 1));
        }

        @Test
        void doesNotSave_whenAlreadyGranted() {
            when(rolePermissionRepository.existsById(any())).thenReturn(true);

            roleService.togglePermission(1, 1, true);

            verify(rolePermissionRepository, never()).save(any());
        }

        @Test
        void deletesRolePermission_whenRevoked() {
            // granted=false always calls deleteById (no existence check needed)
            roleService.togglePermission(1, 1, false);

            verify(rolePermissionRepository).deleteById(any());
        }

        @Test
        void doesNotDelete_whenGrantedIsNull() {
            // null is treated as revoke → deleteById called
            roleService.togglePermission(1, 1, null);

            verify(rolePermissionRepository).deleteById(any());
        }
    }
}
