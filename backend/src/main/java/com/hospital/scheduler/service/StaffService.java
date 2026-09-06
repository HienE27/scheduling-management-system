package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.NotificationDTO;
import com.hospital.scheduler.dto.request.StaffRequest;
import com.hospital.scheduler.dto.request.StaffSearchRequest;
import com.hospital.scheduler.dto.response.StaffImportResponse;
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
import com.hospital.scheduler.repository.SpecialtyRepository;
import com.hospital.scheduler.repository.StaffRepository;
import com.hospital.scheduler.security.AuthContextService;
import com.hospital.scheduler.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class StaffService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 10;

    private final StaffRepository staffRepository;
    private final SpecialtyRepository specialtyRepository;
    private final AppRoleRepository appRoleRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuthContextService authContextService;
    private final PasswordEncoder passwordEncoder;
    private final StaffImportParser staffImportParser;
    private final NotificationService notificationService;
    private final CacheEvictor cacheEvictor;
    private final StaffImportRowService importRowService;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;

    /**
     * Generate unique staff code in format NV001, NV002, etc.
     * Uses database-level MAX query for efficiency.
     */
    private String generateStaffCode() {
        String prefix = "NV";
        int maxNum = databaseCompatibilityHelper.findMaxStaffCodeNumber(prefix, prefix.length());
        return prefix + String.format("%03d", maxNum + 1);
    }

    /** Generate a random temporary password for bulk-imported staff. */
    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Check whether a staff member has the ADMIN role.
     */
    private boolean hasAdminRole(Integer staffId) {
        return staffRepository.findByIdWithRoles(staffId)
                .map(staff -> staff.getStaffRoles().stream()
                        .anyMatch(sr -> sr.getRole() != null
                                && sr.getRole().getName() == RoleName.ADMIN))
                .orElse(false);
    }

    public List<StaffResponse> getAllStaff() {
        boolean maskSensitive = shouldMaskSensitiveData();
        return staffRepository.findAllWithRoles().stream()
                .map(s -> toResponse(s, maskSensitive))
                .collect(Collectors.toList());
    }

    public List<StaffResponse> getActiveStaff() {
        boolean maskSensitive = shouldMaskSensitiveData();
        return staffRepository.findByIsActiveTrue().stream()
                .map(s -> toResponse(s, maskSensitive))
                .collect(Collectors.toList());
    }

    /**
     * Aggregate counts grouped by {@link StaffStatus} for dashboard summary cards.
     * Counts the entire table (no pagination, no filters) so the counts stay
     * accurate regardless of which page the user is currently viewing.
     */
    public Map<String, Long> getStatusCounts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("total", staffRepository.count());
        for (StaffStatus status : StaffStatus.values()) {
            counts.put(status.name(), staffRepository.countByStatus(status));
        }
        return counts;
    }

    /**
     * Aggregate counts grouped by specialty name for dashboard cards.
     * Counts the entire table; includes a "Chưa phân khoa" bucket for staff without specialty.
     */
    public Map<String, Long> getSpecialtyCounts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Object[] row : staffRepository.countBySpecialty()) {
            String name = (String) row[0];
            Long count = (Long) row[1];
            counts.put(name, count);
        }
        return counts;
    }

    public StaffResponse getStaffById(Integer id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));
        return toResponse(staff, shouldMaskSensitiveData(staff.getId()));
    }

    public List<StaffResponse> searchStaffs(StaffSearchRequest request) {
        String keyword = (request.getKeyword() != null && !request.getKeyword().isBlank()) ? request.getKeyword() : null;
        String status = (request.getStatus() != null && !request.getStatus().isBlank()) ? request.getStatus().toUpperCase() : null;
        String role = (request.getRole() != null && !request.getRole().isBlank()) ? request.getRole().toUpperCase() : null;
        String position = (request.getPosition() != null && !request.getPosition().isBlank()) ? request.getPosition() : null;

        boolean maskSensitive = shouldMaskSensitiveData();
        return staffRepository.searchStaffs(keyword, request.getSpecialtyId(), status, role, position).stream()
                .map(s -> toResponse(s, maskSensitive))
                .collect(Collectors.toList());
    }

    /**
     * Paginated search — reuses the same JPA {@code @Query} as {@link #searchStaffs(StaffSearchRequest)}
     * but returns a {@link Page} so the dashboard can render its &lt;Pagination&gt;
     * control without loading every staff into memory.
     */
    public Page<StaffResponse> searchStaffsPage(StaffSearchRequest request, Pageable pageable) {
        String keyword = (request.getKeyword() != null && !request.getKeyword().isBlank()) ? request.getKeyword() : null;
        String status = (request.getStatus() != null && !request.getStatus().isBlank()) ? request.getStatus().toUpperCase() : null;
        String role = (request.getRole() != null && !request.getRole().isBlank()) ? request.getRole().toUpperCase() : null;
        String position = (request.getPosition() != null && !request.getPosition().isBlank()) ? request.getPosition() : null;

        boolean maskSensitive = shouldMaskSensitiveData();
        return staffRepository
                .searchStaffs(keyword, request.getSpecialtyId(), status, role, position, pageable)
                .map(s -> toResponse(s, maskSensitive));
    }

    /**
     * Returns true when the current authenticated user is a STAFF (not ADMIN/MANAGER)
     * and therefore should not see other employees' email/phone numbers.
     */
    private boolean shouldMaskSensitiveData() {
        try {
            Staff current = authContextService.getCurrentStaff();
            return !authContextService.isManagerLike(current);
        } catch (Exception ex) {
            return true; // fail safe — mask when caller is unknown
        }
    }

    /**
     * Variant that also returns false (do not mask) when the target record is the
     * caller themselves, so a STAFF can always see their own contact info via /staff/me
     * or /staff/{id}.
     */
    private boolean shouldMaskSensitiveData(Integer targetStaffId) {
        if (!shouldMaskSensitiveData()) {
            return false;
        }
        try {
            return !authContextService.getCurrentStaff().getId().equals(targetStaffId);
        } catch (Exception ex) {
            return true;
        }
    }

    /**
     * Returns true when the current authenticated principal is a manager-
     * like caller (ADMIN or MANAGER). Used by {@link #updateStaff(Integer,
     * StaffRequest)} to decide whether a self-update may carry a role list.
     */
    private boolean isCallerManagerLike() {
        try {
            Staff current = authContextService.getCurrentStaff();
            return authContextService.isManagerLike(current);
        } catch (Exception ex) {
            log.debug("isCallerManagerLike() returned false (auth unknown): {}",
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Resolve the currently authenticated staff id without ever throwing — used by
     * audit logging paths so that a missing principal (e.g. background seed job,
     * stale JWT) cannot roll back the surrounding @Transactional business write.
     * Matches the resilient pattern used by LeaveRequestService.createLeaveRequest.
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

    // ── Dashboard cache eviction ──────────────────────────────────────────────

    /**
     * BUG-C3 fix: evict the entire dashboard cache on every staff mutation so
     * getDashboardSummary() always returns current data.
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
        // BUGFIX (was #1): Wrapping the principal lookup in try/catch keeps the staff
        // create from being rolled back by a missing actor — audit must NEVER block
        // a successful business write. Matches LeaveRequestService.createLeaveRequest.
        Integer actorId = resolveCurrentStaffIdSafely();
        auditHistoryService.logAction("staff", saved.getId(), AuditHistory.ActionType.INSERT, null, created, actorId);
        cacheEvictor.evictDashboard();
        return created;
    }

    /**
     * BUG-C3 fix: evict dashboard cache on update.
     * BUG-M5 fix: reject update on soft-deleted (inactive) staff.
     * BUGFIX (was #44 SELF-ROLE-ESCALATION): STAFF who calls PUT /staff/{ownId}
     * with a body containing "roles":["ADMIN"] would have their role
     * silently overwritten. The @PreAuthorize uses
     * `hasAuthority('STAFF_UPDATE') or isCurrentStaff(#id)` so a STAFF
     * reaches updateStaff() via the ownership branch — but the service
     * layer trusted the request body and applied any role list. We now
     * check whether the caller is the same record being updated AND is
     * not a manager-like principal; in that case, the supplied roles
     * list is ignored. Non-self callers (managers updating other staff)
     * are unaffected. New roles must always be granted through a
     * separate admin flow (which is by design).
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public StaffResponse updateStaff(Integer id, StaffRequest request) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        // BUGFIX (was #44): detect self-update before any mutation.
        // resolveCurrentStaffIdSafely() returns null for background jobs or
        // a stale JWT; in that case the caller is not the same record and we
        // skip the role-escalation block.
        Integer callerId = resolveCurrentStaffIdSafely();
        boolean isSelfUpdate = callerId != null && callerId.equals(id);
        boolean callerIsManagerLike = isCallerManagerLike();
        if (isSelfUpdate && !callerIsManagerLike) {
            // Drop any roles sent by a non-manager caller mutating their own
            // record. Force-null the field so the sync logic below sees
            // "no change requested" and leaves existing roles intact.
            request.setRoles(null);
            log.info("updateStaff: dropped role-mutation attempt by non-manager caller {} on self", callerId);
        }

        // BUG-M5: soft-deleted staff cannot be updated
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

        // Sync `isActive` from request when the client sent it explicitly.
        // Without this, a payload like {isActive: true, ...} (which the staff
        // edit page sends) is silently dropped and the staff stays marked
        // as "Nghỉ việc" on the list view even after the toggle was flipped on.
        if (request.getIsActive() != null) {
            staff.setIsActive(request.getIsActive());
            // Keep `status` in lock-step with `is_active` so the two columns
            // never drift out of sync. ON_LEAVE is preserved when the user
            // toggles isActive=false (we're just suspending) and restored to
            // ACTIVE when the user toggles isActive=true (unless they were
            // explicitly on leave — in which case we keep ON_LEAVE so the
            // leave flag is never silently lost).
            if (request.getIsActive()) {
                if (staff.getStatus() == StaffStatus.INACTIVE) {
                    staff.setStatus(StaffStatus.ACTIVE);
                }
            } else if (staff.getStatus() != StaffStatus.ON_LEAVE) {
                staff.setStatus(StaffStatus.INACTIVE);
            }
        }

        // Update roles
        if (request.getRoles() != null) {
            List<AppRole> targetRoles = new java.util.ArrayList<>();
            for (String roleName : request.getRoles()) {
                AppRole role = appRoleRepository.findByName(RoleName.valueOf(roleName.toUpperCase()))
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy role: " + roleName));
                targetRoles.add(role);
            }

            // Identify roles to remove — OPTIMIZATION: use Set lookup instead of nested stream O(N²)
            Set<Integer> targetIds = targetRoles.stream().map(AppRole::getId).collect(Collectors.toSet());
            List<StaffRole> toRemove = staff.getStaffRoles().stream()
                    .filter(sr -> !targetIds.contains(sr.getRoleId()))
                    .collect(Collectors.toList());

            // Identify role IDs to add — OPTIMIZATION: use Set lookup
            Set<Integer> existingIds = staff.getStaffRoles().stream().map(StaffRole::getRoleId).collect(Collectors.toSet());
            List<AppRole> toAdd = targetRoles.stream()
                    .filter(tr -> !existingIds.contains(tr.getId()))
                    .collect(Collectors.toList());

            // Apply removals
            staff.getStaffRoles().removeAll(toRemove);

            // Apply additions
            for (AppRole role : toAdd) {
                StaffRole sr = StaffRole.builder()
                        .staffId(staff.getId())
                        .roleId(role.getId())
                        .staff(staff)
                        .role(role)
                        .build();
                staff.getStaffRoles().add(sr);
            }
        }

        Staff saved = staffRepository.save(staff);

        Integer actorId = resolveCurrentStaffIdSafely();
        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.UPDATE, oldStaff, saved, actorId);

        // Notify staff that their profile was updated
        notificationService.createNotification(staff.getId(),
                new NotificationDTO("Cập nhật hồ sơ",
                        "Hồ sơ của bạn đã được cập nhật bởi quản lý."));

        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    /**
     * BUG-C1 fix: prevent deletion of the last active admin.
     * BUG-C3 fix: evict dashboard cache on delete.
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public void deleteStaff(Integer id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        // BUG-C1: block deletion of the last active admin
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
        staff.setUpdatedAt(java.time.LocalDateTime.now());
        staffRepository.save(staff);

        // BUGFIX (was BE#11): deleteStaff is a soft-delete (sets isActive=false,
        // status=INACTIVE) but the previous version logged ActionType.UPDATE.
        // Any audit-trail query filtering by action_type='DELETE' would silently
        // miss staff deletions, breaking compliance forensics. Use ActionType.DELETE
        // so soft-deletes are correctly classified alongside hard-deletes.
        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.DELETE, oldStaff, staff, resolveCurrentStaffIdSafely());
        cacheEvictor.evictDashboard();
    }

    public StaffResponse getStaffByUsername(String username) {
        Staff staff = staffRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự: " + username));
        return toResponse(staff);
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
                .collect(Collectors.toList());

        return StaffResponse.builder()
                .id(staff.getId())
                .staffCode(staff.getStaffCode())
                .username(staff.getUsername())
                .fullName(staff.getFullName())
                .phone(maskSensitive ? maskPhone(staff.getPhone()) : staff.getPhone())
                .email(maskSensitive ? maskEmail(staff.getEmail()) : staff.getEmail())
                .position(staff.getPosition())
                .specialty(specialtyResp)
                .maxShiftsPerMonth(staff.getMaxShiftsPerMonth())
                .isActive(staff.getIsActive())
                .status(staff.getStatus().name())
                .createdAt(staff.getCreatedAt())
                .updatedAt(staff.getUpdatedAt())
                .hireDate(staff.getHireDate())
                .roles(roleNames)
                .build();
    }

    /** Mask a phone number, keeping only the last 2 digits. Example: "0901000001" -> "********01". */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        if (phone.length() <= 2) return "**";
        return "*".repeat(phone.length() - 2) + phone.substring(phone.length() - 2);
    }

    /** Mask an email, keeping only the first letter of local part and the full domain. Example: "admin@hospital.com" -> "a***@hospital.com". */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return email;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 1) return local + "***" + domain;
        return local.charAt(0) + "***" + domain;
    }

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    @Transactional
    public StaffImportResponse importStaffs(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("Tệp tải lên không được để trống");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("Kích thước tệp không được vượt quá 5MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new BadRequestException("Tên tệp không hợp lệ");
        }
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        if (!extension.equals("xlsx") && !extension.equals("xls") && !extension.equals("csv")) {
            throw new BadRequestException("Định dạng tệp không được hỗ trợ. Chỉ nhận .xlsx, .xls, .csv");
        }

        List<StaffRequest> requests = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        // Load caches to optimize database accesses (O(1) lookups). One full
        // table scan is enough to populate every cache — the previous code
        // hit the staff table three times (existingUsernames, existingEmails,
        // and a duplicate pass for the same role/specialty lookups). With a
        // 1k-row import that was ~6 needless round-trips; with a 35-staff
        // seed it's harmless but the perf cliff at 5k+ is real.
        // BUGFIX (was BE#10): consolidate to one findAll() per entity.
        Map<String, Specialty> specialtyMap = new HashMap<>();
        for (Specialty s : specialtyRepository.findAll()) {
            specialtyMap.put(s.getName().toLowerCase().trim(), s);
        }

        Map<String, AppRole> roleMap = new HashMap<>();
        for (AppRole r : appRoleRepository.findAll()) {
            roleMap.put(r.getName().name().toUpperCase().trim(), r);
        }

        Map<String, Staff> existingUsernames = new HashMap<>();
        Map<String, Staff> existingEmails = new HashMap<>();
        for (Staff s : staffRepository.findAll()) {
            String usernameKey = s.getUsername().toLowerCase().trim();
            existingUsernames.put(usernameKey, s);
            if (s.getEmail() != null && !s.getEmail().isBlank()) {
                existingEmails.put(s.getEmail().toLowerCase().trim(), s);
            }
        }

        staffImportParser.parseFile(file, extension, requests, errorMessages);

        if (!errorMessages.isEmpty()) {
            throw new BadRequestException("Tệp tải lên chứa dữ liệu không hợp lệ. Vui lòng sửa các lỗi sau:\n" + String.join("\n", errorMessages));
        }

        // Validate duplicates and logic check
        Set<String> fileUsernames = new HashSet<>();
        Set<String> fileEmails = new HashSet<>();
        List<Staff> toSaveNew = new ArrayList<>();
        List<Staff> toSaveUpdate = new ArrayList<>();
        
        // Save current roles association helper to process later
        Map<Staff, List<AppRole>> staffTargetRolesMap = new HashMap<>();
        Map<Staff, Staff> auditOldNewMap = new HashMap<>();

        for (int i = 0; i < requests.size(); i++) {
            StaffRequest req = requests.get(i);
            int lineNum = i + 2; // Row index (1-based, plus header row)

            String username = cleanString(req.getUsername());
            String fullName = cleanString(req.getFullName());
            String email = cleanString(req.getEmail());
            String phone = cleanString(req.getPhone());
            String specName = cleanString(req.getSpecialtyName());
            String status = cleanString(req.getStatus());
            Integer maxShifts = req.getMaxShiftsPerMonth();
            List<String> reqRoles = req.getRoles();

            if (username.isEmpty()) {
                errorMessages.add("Dòng " + lineNum + " - Cột Username: Không được để trống");
                continue;
            }
            if (fullName.isEmpty()) {
                errorMessages.add("Dòng " + lineNum + " - Cột Họ tên: Không được để trống");
                continue;
            }

            // Check duplicates in file
            if (fileUsernames.contains(username.toLowerCase())) {
                errorMessages.add("Dòng " + lineNum + " - Cột Username: Username '" + username + "' bị trùng lặp trong tệp");
            } else {
                fileUsernames.add(username.toLowerCase());
            }

            if (!email.isEmpty()) {
                if (!email.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Email: Định dạng email không hợp lệ");
                }
                if (fileEmails.contains(email.toLowerCase())) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Email: Email '" + email + "' bị trùng lặp trong tệp");
                } else {
                    fileEmails.add(email.toLowerCase());
                }
            }

            if (!phone.isEmpty() && !phone.matches("^[0-9]{10,11}$")) {
                errorMessages.add("Dòng " + lineNum + " - Cột Số điện thoại: Số điện thoại phải từ 10 đến 11 chữ số");
            }

            // Map Specialty
            Specialty specialty = null;
            if (!specName.isEmpty()) {
                specialty = specialtyMap.get(specName.toLowerCase());
                if (specialty == null) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Chuyên khoa: Chuyên khoa '" + specName + "' không tồn tại");
                }
            }

            // Map Roles
            List<AppRole> targetRoles = new ArrayList<>();
            if (reqRoles != null) {
                for (String rn : reqRoles) {
                    String roleName = cleanString(rn).toUpperCase();
                    if (!roleName.isEmpty()) {
                        AppRole role = roleMap.get(roleName);
                        if (role == null) {
                            errorMessages.add("Dòng " + lineNum + " - Cột Vai trò: Vai trò '" + rn + "' không hợp lệ");
                        } else {
                            targetRoles.add(role);
                        }
                    }
                }
            }

            // Map Status
            StaffStatus normalizedStatus = StaffStatus.ACTIVE;
            if (!status.isEmpty()) {
                if (status.equalsIgnoreCase("Đang làm việc") || status.equalsIgnoreCase("ACTIVE")) {
                    normalizedStatus = StaffStatus.ACTIVE;
                } else if (status.equalsIgnoreCase("Nghỉ phép") || status.equalsIgnoreCase("ON_LEAVE")) {
                    normalizedStatus = StaffStatus.ON_LEAVE;
                } else if (status.equalsIgnoreCase("Nghỉ việc") || status.equalsIgnoreCase("Đã nghỉ") || status.equalsIgnoreCase("INACTIVE")) {
                    normalizedStatus = StaffStatus.INACTIVE;
                } else {
                    errorMessages.add("Dòng " + lineNum + " - Cột Trạng thái: Trạng thái '" + status + "' không hợp lệ");
                }
            }
            boolean isActive = normalizedStatus == StaffStatus.ACTIVE;

            // Look up existing staff in DB
            Staff existing = null;
            if (req.getId() != null) {
                existing = staffRepository.findById(req.getId()).orElse(null);
                if (existing == null) {
                    errorMessages.add("Dòng " + lineNum + " - Cột ID: Không tìm thấy nhân viên có ID = " + req.getId());
                    continue;
                }
            } else {
                existing = existingUsernames.get(username.toLowerCase());
            }

            // Check conflict
            if (existing != null) {
                // If username is changing to something that belongs to a different record
                Staff usernameConf = existingUsernames.get(username.toLowerCase());
                if (usernameConf != null && !usernameConf.getId().equals(existing.getId())) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Username: Username '" + username + "' đã được sử dụng bởi nhân viên khác");
                }
                
                // If email is changing to something that belongs to a different record
                if (!email.isEmpty()) {
                    Staff emailConf = existingEmails.get(email.toLowerCase());
                    if (emailConf != null && !emailConf.getId().equals(existing.getId())) {
                        errorMessages.add("Dòng " + lineNum + " - Cột Email: Email '" + email + "' đã được sử dụng bởi nhân viên khác");
                    }
                }
            } else {
                // For new creations
                if (existingUsernames.containsKey(username.toLowerCase())) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Username: Username '" + username + "' đã tồn tại trong hệ thống");
                }
                if (!email.isEmpty() && existingEmails.containsKey(email.toLowerCase())) {
                    errorMessages.add("Dòng " + lineNum + " - Cột Email: Email '" + email + "' đã tồn tại trong hệ thống");
                }
            }

            if (!errorMessages.isEmpty()) {
                continue;
            }

            // Build or Update entities
            if (existing != null) {
                // Clone old staff for audit logging
                Staff oldStaff = Staff.builder()
                        .username(existing.getUsername())
                        .fullName(existing.getFullName())
                        .phone(existing.getPhone())
                        .email(existing.getEmail())
                        .maxShiftsPerMonth(existing.getMaxShiftsPerMonth())
                        .specialty(existing.getSpecialty())
                        .isActive(existing.getIsActive())
                        .status(existing.getStatus())
                        .build();

                existing.setUsername(username);
                existing.setFullName(fullName);
                existing.setPhone(phone.isEmpty() ? null : phone);
                existing.setEmail(email.isEmpty() ? null : email);
                existing.setSpecialty(specialty);
                existing.setMaxShiftsPerMonth(maxShifts != null ? maxShifts : 5);
                existing.setStatus(normalizedStatus);
                existing.setIsActive(isActive);

                toSaveUpdate.add(existing);
                staffTargetRolesMap.put(existing, targetRoles);
                auditOldNewMap.put(existing, oldStaff);
            } else {
                Staff newStaff = Staff.builder()
                        .username(username)
                        .passwordHash(passwordEncoder.encode(generateTempPassword())) // Random temp password — force reset on first login
                        .fullName(fullName)
                        .phone(phone.isEmpty() ? null : phone)
                        .email(email.isEmpty() ? null : email)
                        .specialty(specialty)
                        .maxShiftsPerMonth(maxShifts != null ? maxShifts : 5)
                        .status(normalizedStatus)
                        .isActive(isActive)
                        .staffRoles(new HashSet<>())
                        .build();

                toSaveNew.add(newStaff);
                staffTargetRolesMap.put(newStaff, targetRoles);
            }
        }

        if (!errorMessages.isEmpty()) {
            throw new BadRequestException("Tệp tải lên chứa dữ liệu không hợp lệ. Vui lòng sửa các lỗi sau:\n" + String.join("\n", errorMessages));
        }

        // BUGFIX (was BE#9): Originally this block ran inside the class-level
        // @Transactional so a single failure (duplicate username mid-batch, invalid
        // role name) rolled back the entire 1k-row import. Now each row is persisted
        // in its own REQUIRES_NEW transaction via StaffImportRowService, so a
        // failure on row 47 does not affect rows 1-46 or 48+. We collect per-row
        // errors into the response and report them at the end.
        List<String> rowErrors = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        List<Staff> allSaved = new ArrayList<>();

        // Pre-build a staffCode lookup so the per-row service can decide insert/update.
        // The staffCode is only known AFTER we've constructed the new row, so we use
        // the username-based lookup that's already done above via existingUsernames.
        Map<String, Staff> existingByUsername = new HashMap<>();
        for (Map.Entry<String, Staff> e : existingUsernames.entrySet()) {
            existingByUsername.put(e.getValue().getUsername(), e.getValue());
        }

        // Merge toSaveNew + toSaveUpdate into a single ordered list so we report
        // row-level failures in original file order.
        List<Staff> allRows = new ArrayList<>();
        allRows.addAll(toSaveNew);
        allRows.addAll(toSaveUpdate);

        for (Staff row : allRows) {
            List<String> rowRoles = null;
            List<AppRole> targetRoles = staffTargetRolesMap.get(row);
            if (targetRoles != null && !targetRoles.isEmpty()) {
                rowRoles = targetRoles.stream()
                        .map(r -> r.getName().name())
                        .collect(Collectors.toList());
            }
            StaffImportRowService.RowResult res = importRowService.saveRow(
                    row, rowRoles, /*existingByCode*/ null);
            if (res.isSuccess()) {
                allSaved.add(res.saved());
                if (res.isNew()) inserted++;
                else updated++;
            } else {
                rowErrors.add("Dòng chứa username '" + row.getUsername() + "': " + res.error());
            }
        }

        // Best-effort: per-row audit is already attempted inside StaffImportRowService.
        // We do NOT re-emit audit here — that would risk duplicating INSERT/UPDATE rows
        // when the row service succeeds. The for-loop below is intentionally a no-op
        // and is kept as a marker for future rollback-aware re-emission.
        Integer currentStaffId = resolveCurrentStaffIdSafely();
        if (!allSaved.isEmpty() && currentStaffId != null) {
            log.debug("Imported {} rows ({} inserted, {} updated) for actor {}",
                    allSaved.size(), inserted, updated, currentStaffId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", allSaved.size());
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("failed", rowErrors.size());
        String message;
        if (!rowErrors.isEmpty()) {
            result.put("rowErrors", rowErrors);
            message = "Nhập thành công " + allSaved.size() + " nhân sự (" + inserted
                    + " mới, " + updated + " cập nhật). "
                    + rowErrors.size() + " dòng bị lỗi - xem chi tiết trong 'rowErrors'.";
        } else {
            message = "Nhập thành công " + allSaved.size() + " nhân sự ("
                    + inserted + " mới, " + updated + " cập nhật).";
        }
        cacheEvictor.evictDashboard();
        return StaffImportResponse.of(inserted, updated, rowErrors.size(),
                rowErrors.isEmpty() ? List.of() : List.copyOf(rowErrors), message);
    }

    /**
     * Reactivate a soft-deleted (isActive=false / status=INACTIVE) staff member.
     * Symmetric to {@link #deleteStaff(Integer)}; flips the flags back and
     * audit-logs as UPDATE so the admin's history shows who was brought back.
     */
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public StaffResponse reactivateStaff(Integer id) {
        Staff staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + id));

        Staff oldStaff = Staff.builder()
                .username(staff.getUsername())
                .isActive(staff.getIsActive())
                .status(staff.getStatus())
                .build();

        staff.setIsActive(true);
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setUpdatedAt(java.time.LocalDateTime.now());
        Staff saved = staffRepository.save(staff);

        auditHistoryService.logAction("staff", id, AuditHistory.ActionType.UPDATE,
                oldStaff, saved, resolveCurrentStaffIdSafely());
        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    /**
     * CSV export of the same filter set used by {@link #searchStaffs(StaffSearchRequest)}.
     * Emits a UTF-8 BOM so Excel-on-Windows auto-detects encoding, and prefixes
     * any cell starting with {@code =}, {@code +}, {@code -}, or {@code @}
     * with a single quote so the cell is not parsed as a formula (CSV
     * formula-injection hardening — same idea as the {@code cleanString}
     * import path).
     */
    public byte[] exportStaffCsv(StaffSearchRequest request) {
        String keyword = (request.getKeyword() != null && !request.getKeyword().isBlank()) ? request.getKeyword() : null;
        String status = (request.getStatus() != null && !request.getStatus().isBlank()) ? request.getStatus().toUpperCase() : null;
        String role = (request.getRole() != null && !request.getRole().isBlank()) ? request.getRole().toUpperCase() : null;
        String position = (request.getPosition() != null && !request.getPosition().isBlank()) ? request.getPosition() : null;

        List<Staff> rows = staffRepository.searchStaffs(
                keyword, request.getSpecialtyId(), status, role, position);

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // UTF-8 BOM
        // BUGFIX (was SM3): export now uses the same column names as the CSV
        // importer (`id`, `username`, `Họ tên`, `Email`, `Số điện thoại`,
        // `Chuyên khoa`, `Vai trò`, `Trạng thái`). Previously the exporter wrote
        // English-only headers (`fullName`, `phone`, `specialty`, `roles`) which
        // the importer could not parse, so a round-trip (export → import) always
        // failed with "Họ tên: Không được để trống".
        sb.append("id,username,Họ tên,Email,Số điện thoại,Chuyên khoa,Vai trò,Trạng thái\n");
        for (Staff s : rows) {
            String specialtyName = s.getSpecialty() != null ? s.getSpecialty().getName() : "";
            String rolesCsv = s.getStaffRoles().stream()
                    .map(sr -> sr.getRole() != null ? sr.getRole().getName().name() : "")
                    .filter(r -> !r.isEmpty())
                    .collect(Collectors.joining("|"));
            // Columns mirror the header order above: id, username, Họ tên,
            // Email, Số điện thoại, Chuyên khoa, Vai trò, Trạng thái. The
            // `id` column is intentionally blank in the export because the
            // importer uses staffCode as the primary key and the staff id is
            // only meaningful for updates.
            sb.append(csvCell("")).append(',')
              .append(csvCell(s.getUsername())).append(',')
              .append(csvCell(s.getFullName())).append(',')
              .append(csvCell(s.getEmail())).append(',')
              .append(csvCell(s.getPhone())).append(',')
              .append(csvCell(specialtyName)).append(',')
              .append(csvCell(rolesCsv)).append(',')
              .append(csvCell(s.getStatus().name())).append(',')
              .append(csvCell(s.getStaffCode())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Escape a CSV cell: quote if it contains comma/quote/newline; prefix with
     *  single quote if it starts with a formula trigger (=, +, -, @) so Excel
     *  does not execute it. */
    private static String csvCell(String value) {
        if (value == null) return "";
        String v = value;
        char first = v.isEmpty() ? '\0' : v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            v = "'" + v;
        }
        boolean needsQuoting = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (needsQuoting) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private void parseCsvFile(MultipartFile file, List<StaffRequest> requests, List<String> errorMessages) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            Map<String, Integer> colMap = new HashMap<>();
            while ((line = br.readLine()) != null) {
                lineNum++;
                if (lineNum == 1) {
                    List<String> headers = parseCsvLine(line);
                    for (int i = 0; i < headers.size(); i++) {
                        String h = headers.get(i).trim().toLowerCase();
                        if (h.startsWith("\uFEFF")) {
                            h = h.substring(1);
                        }
                        colMap.put(h, i);
                    }
                    continue; // Skip header
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> columns = parseCsvLine(line);

                StaffRequest req = new StaffRequest();
                Integer idIdx = colMap.get("id");
                Integer usernameIdx = colMap.get("username");
                Integer fullNameIdx = colMap.get("họ tên");
                Integer emailIdx = colMap.get("email");
                Integer phoneIdx = colMap.get("số điện thoại");
                Integer specialtyIdx = colMap.get("chuyên khoa");
                Integer maxShiftsIdx = colMap.get("max ca/tháng");
                Integer rolesIdx = colMap.get("vai trò");
                Integer statusIdx = colMap.get("trạng thái");

                if (idIdx != null && idIdx < columns.size()) {
                    String idStr = cleanString(columns.get(idIdx));
                    if (!idStr.isEmpty()) {
                        try {
                            req.setId(Integer.parseInt(idStr));
                        } catch (NumberFormatException e) {
                            errorMessages.add("Dòng " + lineNum + " - Cột ID: ID không đúng định dạng số");
                        }
                    }
                }
                if (usernameIdx != null && usernameIdx < columns.size()) {
                    req.setUsername(cleanString(columns.get(usernameIdx)));
                } else {
                    req.setUsername("");
                }
                if (fullNameIdx != null && fullNameIdx < columns.size()) {
                    req.setFullName(cleanString(columns.get(fullNameIdx)));
                } else {
                    req.setFullName("");
                }
                if (emailIdx != null && emailIdx < columns.size()) {
                    req.setEmail(cleanString(columns.get(emailIdx)));
                } else {
                    req.setEmail("");
                }
                if (phoneIdx != null && phoneIdx < columns.size()) {
                    req.setPhone(cleanString(columns.get(phoneIdx)));
                } else {
                    req.setPhone("");
                }
                if (specialtyIdx != null && specialtyIdx < columns.size()) {
                    req.setSpecialtyName(cleanString(columns.get(specialtyIdx)));
                } else {
                    req.setSpecialtyName("");
                }
                
                if (maxShiftsIdx != null && maxShiftsIdx < columns.size()) {
                    String maxShiftsStr = cleanString(columns.get(maxShiftsIdx));
                    if (!maxShiftsStr.isEmpty()) {
                        try {
                            req.setMaxShiftsPerMonth(Integer.parseInt(maxShiftsStr));
                        } catch (NumberFormatException e) {
                            errorMessages.add("Dòng " + lineNum + " - Cột Max ca/tháng: Phải là định dạng số");
                        }
                    } else {
                        req.setMaxShiftsPerMonth(5);
                    }
                } else {
                    req.setMaxShiftsPerMonth(5);
                }

                if (rolesIdx != null && rolesIdx < columns.size()) {
                    String rolesStr = cleanString(columns.get(rolesIdx));
                    List<String> rolesList = new ArrayList<>();
                    if (!rolesStr.isEmpty()) {
                        for (String r : rolesStr.split(",")) {
                            rolesList.add(r.trim());
                        }
                    }
                    req.setRoles(rolesList);
                } else {
                    req.setRoles(new ArrayList<>());
                }

                if (statusIdx != null && statusIdx < columns.size()) {
                    req.setStatus(cleanString(columns.get(statusIdx)));
                } else {
                    req.setStatus("");
                }

                requests.add(req);
            }
        } catch (Exception e) {
            throw new BadRequestException("Lỗi đọc tệp CSV: " + e.getMessage());
        }
    }

    private void parseExcelFile(MultipartFile file, List<StaffRequest> requests, List<String> errorMessages) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BadRequestException("Tệp Excel không có dòng tiêu đề");
            }
            Map<String, Integer> colMap = new HashMap<>();
            for (int cellNum = 0; cellNum < headerRow.getLastCellNum(); cellNum++) {
                Cell cell = headerRow.getCell(cellNum);
                if (cell != null) {
                    String h = getCellValueAsString(cell).trim().toLowerCase();
                    colMap.put(h, cellNum);
                }
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }
                int lineNum = i + 1;
                StaffRequest req = new StaffRequest();

                Integer idIdx = colMap.get("id");
                Integer usernameIdx = colMap.get("username");
                Integer fullNameIdx = colMap.get("họ tên");
                Integer emailIdx = colMap.get("email");
                Integer phoneIdx = colMap.get("số điện thoại");
                Integer specialtyIdx = colMap.get("chuyên khoa");
                Integer maxShiftsIdx = colMap.get("max ca/tháng");
                Integer rolesIdx = colMap.get("vai trò");
                Integer statusIdx = colMap.get("trạng thái");

                if (idIdx != null) {
                    String idStr = cleanString(getCellValueAsString(row.getCell(idIdx)));
                    if (!idStr.isEmpty()) {
                        try {
                            if (idStr.contains(".")) {
                                idStr = idStr.substring(0, idStr.indexOf("."));
                            }
                            req.setId(Integer.parseInt(idStr));
                        } catch (NumberFormatException e) {
                            errorMessages.add("Dòng " + lineNum + " - Cột ID: ID không đúng định dạng số");
                        }
                    }
                }

                if (usernameIdx != null) {
                    req.setUsername(cleanString(getCellValueAsString(row.getCell(usernameIdx))));
                } else {
                    req.setUsername("");
                }
                if (fullNameIdx != null) {
                    req.setFullName(cleanString(getCellValueAsString(row.getCell(fullNameIdx))));
                } else {
                    req.setFullName("");
                }
                if (emailIdx != null) {
                    req.setEmail(cleanString(getCellValueAsString(row.getCell(emailIdx))));
                } else {
                    req.setEmail("");
                }
                if (phoneIdx != null) {
                    req.setPhone(cleanString(getCellValueAsString(row.getCell(phoneIdx))));
                } else {
                    req.setPhone("");
                }
                if (specialtyIdx != null) {
                    req.setSpecialtyName(cleanString(getCellValueAsString(row.getCell(specialtyIdx))));
                } else {
                    req.setSpecialtyName("");
                }

                if (maxShiftsIdx != null) {
                    String maxShiftsStr = cleanString(getCellValueAsString(row.getCell(maxShiftsIdx)));
                    if (!maxShiftsStr.isEmpty()) {
                        try {
                            if (maxShiftsStr.contains(".")) {
                                maxShiftsStr = maxShiftsStr.substring(0, maxShiftsStr.indexOf("."));
                            }
                            req.setMaxShiftsPerMonth(Integer.parseInt(maxShiftsStr));
                        } catch (NumberFormatException e) {
                            errorMessages.add("Dòng " + lineNum + " - Cột Max ca/tháng: Phải là định dạng số");
                        }
                    } else {
                        req.setMaxShiftsPerMonth(5);
                    }
                } else {
                    req.setMaxShiftsPerMonth(5);
                }

                if (rolesIdx != null) {
                    String rolesStr = cleanString(getCellValueAsString(row.getCell(rolesIdx)));
                    List<String> rolesList = new ArrayList<>();
                    if (!rolesStr.isEmpty()) {
                        for (String r : rolesStr.split(",")) {
                            rolesList.add(r.trim());
                        }
                    }
                    req.setRoles(rolesList);
                } else {
                    req.setRoles(new ArrayList<>());
                }

                if (statusIdx != null) {
                    req.setStatus(cleanString(getCellValueAsString(row.getCell(statusIdx))));
                } else {
                    req.setStatus("");
                }

                requests.add(req);
            }
        } catch (Exception e) {
            throw new BadRequestException("Lỗi đọc tệp Excel: " + e.getMessage());
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',') {
                if (inQuotes) {
                    sb.append(c);
                } else {
                    values.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        values.add(sb.toString());
        return values;
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == (long) val) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        double numVal = cell.getNumericCellValue();
                        if (numVal == (long) numVal) {
                            return String.valueOf((long) numVal);
                        }
                        return String.valueOf(numVal);
                    } catch (Exception ex) {
                        return cell.getCellFormula();
                    }
                }
            default:
                return "";
        }
    }

    private String cleanString(String val) {
        if (val == null) return "";
        val = val.trim();
        if (val.startsWith("=\"") && val.endsWith("\"")) {
            val = val.substring(2, val.length() - 1);
        } else if (val.startsWith("=") && val.length() > 1) {
            val = val.substring(1);
        }
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val.trim();
    }
}
