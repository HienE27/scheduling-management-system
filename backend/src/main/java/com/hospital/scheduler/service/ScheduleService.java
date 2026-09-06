package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.BulkL01Request;
import com.hospital.scheduler.dto.request.BulkScheduleRequest;
import com.hospital.scheduler.dto.request.ScheduleRequest;
import com.hospital.scheduler.dto.response.BulkL01Response;
import com.hospital.scheduler.dto.response.BulkScheduleResponse;
import com.hospital.scheduler.dto.response.ConflictCheckResponse;
import com.hospital.scheduler.dto.response.ExpertClinicWeeklyResponse;
import com.hospital.scheduler.dto.response.ScheduleResponse;
import com.hospital.scheduler.dto.response.StaffResponse;
import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.exception.BadRequestException;
import com.hospital.scheduler.exception.ConflictException;
import com.hospital.scheduler.exception.ResourceNotFoundException;
import com.hospital.scheduler.dto.request.NotificationDTO;
import com.hospital.scheduler.config.CacheConfig;
import com.hospital.scheduler.repository.CompensationDayRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.HolidayRepository;
import com.hospital.scheduler.repository.ScheduleConflictRepository;
import com.hospital.scheduler.repository.SchedulePeriodRepository;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.repository.ShiftTypeRepository;
import com.hospital.scheduler.repository.StaffRepository;
import com.hospital.scheduler.security.AuthContextService;
import com.hospital.scheduler.util.CompensationDateCalculator;
import com.hospital.scheduler.util.DateUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    @PersistenceContext
    private EntityManager entityManager;

    private final JdbcTemplate jdbcTemplate;

    private final ScheduleRepository scheduleRepository;
    private final SchedulePeriodRepository periodRepository;
    private final StaffRepository staffRepository;
    private final ShiftTypeRepository shiftTypeRepository;
    private final CompensationDayRepository compensationDayRepository;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;
    private final ScheduleConflictRepository scheduleConflictRepository;
    private final HolidayRepository holidayRepository;
    private final ConflictDetectionService conflictDetectionService;
    private final AuditHistoryService auditHistoryService;
    private final AuthContextService authContextService;
    private final CompensationDateCalculator compensationDateCalculator;
    private final NotificationService notificationService;
    private final ConflictBroadcastService conflictBroadcastService;

    public List<ScheduleResponse> getSchedulesByPeriod(Integer periodId) {
        List<Schedule> schedules = scheduleRepository.findByPeriodId(periodId);
        if (schedules.isEmpty()) return List.of();

        // OPTIMIZATION: batch load all compensation days for the period in ONE query
        List<Integer> scheduleIds = schedules.stream().map(Schedule::getId).collect(Collectors.toList());
        Map<Integer, LocalDate> compDateMap = compensationDayRepository.findByScheduleIds(scheduleIds)
                .stream()
                .collect(Collectors.toMap(
                        cd -> cd.getSchedule().getId(),
                        CompensationDay::getCompensationDate,
                        (a, b) -> a
                ));

        return schedules.stream()
                .map(s -> toResponse(s, compDateMap.get(s.getId())))
                .collect(Collectors.toList());
    }

    public List<ScheduleResponse> getSchedulesByStaff(Integer staffId) {
        List<Schedule> schedules = scheduleRepository.findByStaffId(staffId);
        if (schedules.isEmpty()) return List.of();
        // Filter: only PUBLISHED periods
        List<Schedule> publishedSchedules = schedules.stream()
                .filter(s -> s.getPeriod().getStatus() == SchedulePeriod.PeriodStatus.PUBLISHED)
                .collect(Collectors.toList());
        Map<Integer, List<String>> conflictMap = buildConflictReasonsMap(
                publishedSchedules, null);
        return publishedSchedules.stream()
                .map(s -> toResponse(s, null, conflictMap))
                .collect(Collectors.toList());
    }

    public List<ScheduleResponse> getSchedulesByStaffAndPeriod(Integer staffId, Integer periodId) {
        List<Schedule> schedules = scheduleRepository.findByStaffIdAndPeriodId(staffId, periodId);
        if (schedules.isEmpty()) return List.of();
        Map<Integer, List<String>> conflictMap = buildConflictReasonsMap(schedules, null);
        return schedules.stream()
                .map(s -> toResponse(s, null, conflictMap))
                .collect(Collectors.toList());
    }

    public List<ScheduleResponse> getExpertClinicSchedules(Integer periodId, Integer specialtyId) {
        List<Schedule> schedules = scheduleRepository.findExpertClinicByPeriodAndSpecialty(periodId, specialtyId);
        if (schedules.isEmpty()) return List.of();

        // OPTIMIZATION: batch load all compensation days in ONE query
        List<Integer> scheduleIds = schedules.stream().map(Schedule::getId).collect(Collectors.toList());
        Map<Integer, LocalDate> compDateMap = compensationDayRepository.findByScheduleIds(scheduleIds)
                .stream()
                .collect(Collectors.toMap(
                        cd -> cd.getSchedule().getId(),
                        CompensationDay::getCompensationDate,
                        (a, b) -> a
                ));

        return schedules.stream()
                .map(s -> toResponse(s, compDateMap.get(s.getId())))
                .collect(Collectors.toList());
    }

    public ScheduleResponse getScheduleById(Integer id) {
        Schedule schedule = scheduleRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch với ID: " + id));
        // OPTIMIZATION: batch load all comp days for this schedule in ONE query
        Map<Integer, LocalDate> compDateMap = compensationDayRepository.findByScheduleIds(List.of(id))
                .stream()
                .collect(Collectors.toMap(
                        cd -> cd.getSchedule().getId(),
                        CompensationDay::getCompensationDate,
                        (a, b) -> a
                ));
        return toResponse(schedule, compDateMap.get(id));
    }

    public ScheduleResponse createSchedule(ScheduleRequest request) {
        SchedulePeriod period = periodRepository.findById(request.getPeriodId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + request.getPeriodId()));

        if (request.getWorkDate().isBefore(period.getStartDate()) || request.getWorkDate().isAfter(period.getEndDate())) {
            throw new BadRequestException("Ngày làm việc phải nằm trong kỳ lịch");
        }

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể thêm lịch khi kỳ lịch ở trạng thái DRAFT");
        }

        if (holidayRepository.existsByHolidayDateAndIsActiveTrue(request.getWorkDate())) {
            throw new BadRequestException("Ngày " + request.getWorkDate() + " là ngày nghỉ lễ. Không thể xếp lịch vào ngày nghỉ lễ.");
        }

        Staff staff = staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + request.getStaffId()));

        if (!Boolean.TRUE.equals(staff.getIsActive())) {
            throw new BadRequestException("Không thể xếp lịch cho nhân sự đang ngừng hoạt động");
        }

        ShiftType shiftType = shiftTypeRepository.findById(request.getShiftTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy loại ca với ID: " + request.getShiftTypeId()));

        // Check unique constraint
        scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(
                request.getPeriodId(), request.getStaffId(), request.getShiftTypeId(), request.getWorkDate())
                .ifPresent(s -> {
                    throw new ConflictException("Nhân sự đã được phân công ca này trong ngày");
                });

        // Validate conflicts and send email alert if conflicts are found
        conflictDetectionService.validateAndThrowWithEmail(
                request.getStaffId(),
                request.getWorkDate(),
                request.getShiftTypeId(),
                null,
                request.getPeriodId()
        );

        Schedule schedule = Schedule.builder()
                .period(period)
                .workDate(request.getWorkDate())
                .staff(staff)
                .shiftType(shiftType)
                .hasConflict(false)
                .build();

        Schedule saved = scheduleRepository.save(schedule);

        // Auto create compensation day for L01 and compute for response + notification
        LocalDate compDate = null;
        if (ConflictDetectionService.SHIFT_TYPE_L01.equals(request.getShiftTypeId())) {
            createCompensationDay(saved);
            compDate = compensationDayRepository.findByScheduleId(saved.getId()).stream()
                    .map(CompensationDay::getCompensationDate)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        }

        auditHistoryService.logAction("schedule", saved.getId(), AuditHistory.ActionType.INSERT,
                null, saved, authContextService.getCurrentStaff().getId());

        // Notify staff about new schedule assignment
        if (compDate != null) {
            notificationService.createNotification(staff.getId(),
                    new NotificationDTO("Phân công lịch mới",
                            "Bạn được phân công lịch " + shiftType.getName() + " vào ngày " + request.getWorkDate() + ". Ngày nghỉ bù: " + compDate + "."));
        } else {
            notificationService.createNotification(staff.getId(),
                    new NotificationDTO("Phân công lịch mới",
                            "Bạn được phân công lịch " + shiftType.getName() + " vào ngày " + request.getWorkDate() + "."));
        }

        return toResponse(saved, compDate);
    }

    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public ScheduleResponse updateSchedule(Integer id, ScheduleRequest request) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch với ID: " + id));

        SchedulePeriod period = schedule.getPeriod();
        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể cập nhật lịch khi kỳ lịch ở trạng thái DRAFT");
        }

        if (request.getWorkDate().isBefore(period.getStartDate()) || request.getWorkDate().isAfter(period.getEndDate())) {
            throw new BadRequestException("Ngày làm việc phải nằm trong kỳ lịch");
        }

        if (holidayRepository.existsByHolidayDateAndIsActiveTrue(request.getWorkDate())) {
            throw new BadRequestException("Ngày " + request.getWorkDate() + " là ngày nghỉ lễ. Không thể xếp lịch vào ngày nghỉ lễ.");
        }

        // Snapshot old state for audit before any mutation
        Integer oldStaffId = schedule.getStaff().getId();
        String oldShiftTypeId = schedule.getShiftType().getId();
        LocalDate oldWorkDate = schedule.getWorkDate();

        boolean wasL01 = ConflictDetectionService.SHIFT_TYPE_L01.equals(oldShiftTypeId);
        boolean willBeL01 = ConflictDetectionService.SHIFT_TYPE_L01.equals(request.getShiftTypeId());
        boolean shiftTypeChanged = !oldShiftTypeId.equals(request.getShiftTypeId());
        boolean dateChanged = !oldWorkDate.equals(request.getWorkDate());
        boolean staffChanged = !oldStaffId.equals(request.getStaffId());

        Integer targetStaffId = request.getStaffId();
        LocalDate targetWorkDate = request.getWorkDate();
        String targetShiftTypeId = request.getShiftTypeId();
        Integer targetPeriodId = request.getPeriodId();

        // Resolve the new period (without mutating the entity) so the conflict check
        // runs against the post-change period.
        SchedulePeriod targetPeriod = period;
        if (!targetPeriodId.equals(period.getId())) {
            targetPeriod = periodRepository.findById(targetPeriodId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + targetPeriodId));
            if (targetPeriod.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
                throw new BadRequestException("Chỉ có thể chuyển lịch sang kỳ lịch ở trạng thái DRAFT");
            }
        }

        // CRITICAL FIX: Run conflict validation BEFORE mutating the entity. Use raw request
        // values so the check sees the new staff, new date, and new shift type together.
        conflictDetectionService.validateAndThrowWithEmail(
                targetStaffId, targetWorkDate, targetShiftTypeId, id, targetPeriod.getId());

        // Validation passed — now commit the new state.
        if (staffChanged) {
            Staff newStaff = staffRepository.findById(targetStaffId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhân sự với ID: " + targetStaffId));
            if (!Boolean.TRUE.equals(newStaff.getIsActive())) {
                throw new BadRequestException("Không thể chuyển lịch cho nhân sự đang ngừng hoạt động");
            }
            schedule.setStaff(newStaff);
        }
        if (!targetPeriodId.equals(period.getId())) {
            schedule.setPeriod(targetPeriod);
            period = targetPeriod;
        }

        // BUGFIX (was #7): Originally compensation days were deleted here, BEFORE
        // the schedule save. If any later step threw, the schedule kept the old
        // shift/staff/date but lost its compensation day with no replacement created.
        // Now we capture the comp-day IDs to remove, save the schedule, and only
        // delete them after the save succeeds.
        List<Integer> compDayIdsToDelete = new java.util.ArrayList<>();
        if (wasL01 && (shiftTypeChanged || staffChanged)) {
            compDayIdsToDelete.addAll(
                    compensationDayRepository.findByScheduleId(id).stream()
                            .map(CompensationDay::getId).toList());
        }
        if (wasL01 && willBeL01 && dateChanged) {
            // Same row id may be present from the previous branch — dedupe.
            compDayIdsToDelete.addAll(
                    compensationDayRepository.findByScheduleId(id).stream()
                            .map(CompensationDay::getId)
                            .filter(pid -> !compDayIdsToDelete.contains(pid))
                            .toList());
        }

        schedule.setWorkDate(targetWorkDate);

        ShiftType newShiftType = shiftTypeRepository.findById(targetShiftTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy loại ca với ID: " + targetShiftTypeId));
        schedule.setShiftType(newShiftType);

        Schedule updated = scheduleRepository.save(schedule);

        // Now safe to remove the old compensation days — the schedule row is
        // persisted with its new staff/date, so any failure past this point either
        // rolls back the entire transaction (including this delete) or completes
        // with the replacement compensation days created below.
        if (!compDayIdsToDelete.isEmpty()) {
            compensationDayRepository.deleteAllByIdInBatch(compDayIdsToDelete);
        }

        if (!wasL01 && willBeL01) {
            createCompensationDay(updated);
        }

        if (wasL01 && willBeL01 && dateChanged) {
            createCompensationDay(updated);
        }

        auditHistoryService.logAction("schedule", id, AuditHistory.ActionType.UPDATE,
                String.format("staffId=%d,shiftTypeId=%s,workDate=%s", oldStaffId, oldShiftTypeId, oldWorkDate),
                updated, authContextService.getCurrentStaff().getId());

        notificationService.createNotification(updated.getStaff().getId(),
                new NotificationDTO("Cập nhật lịch trực",
                        "Lịch trực ngày " + updated.getWorkDate() + " đã được cập nhật."));

        LocalDate compDate = null;
        if (willBeL01) {
            compDate = compensationDayRepository.findByScheduleId(id).stream()
                    .map(CompensationDay::getCompensationDate)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        }
        return toResponse(updated, compDate);
    }

    /**
     * Delete all schedules and compensation days for a given period.
     * Used by admin to clean up stale data.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllByPeriodId(Integer periodId) {
        log.info("Deleting all schedules for period {}", periodId);
        
        // Delete compensation days first
        compensationDayRepository.deleteAllByPeriodId(periodId);
        
        // Delete schedule conflicts
        jdbcTemplate.update("DELETE FROM schedule_conflict WHERE schedule_id IN (SELECT id FROM schedule WHERE period_id = ?)", periodId);
        
        // Delete schedules
        jdbcTemplate.update("DELETE FROM schedule WHERE period_id = ?", periodId);
        
        log.info("Deleted all schedules for period {}", periodId);
    }

    public List<ScheduleResponse> getSchedulesByPeriodAndDate(Integer periodId, LocalDate date) {
        List<Schedule> schedules = scheduleRepository.findByPeriodIdAndWorkDate(periodId, date);
        if (schedules.isEmpty()) return List.of();
        Map<Integer, List<String>> conflictMap = buildConflictReasonsMap(schedules, periodId);
        return schedules.stream()
                .map(s -> toResponse(s, null, conflictMap))
                .collect(Collectors.toList());
    }

    public ConflictCheckResponse checkConflictsInPeriod(Integer periodId) {
        return conflictDetectionService.checkPeriodConflicts(periodId);
    }

    /**
     * Batch-fetch all unresolved schedule conflicts for a list of schedules in one DB call.
     * Returns a map: scheduleId -> list of conflict reason strings.
     */
    private Map<Integer, List<String>> buildConflictReasonsMap(List<Schedule> schedules, Integer periodId) {
        if (schedules.isEmpty()) return Map.of();

        List<Integer> scheduleIds = schedules.stream()
                .map(Schedule::getId)
                .collect(Collectors.toList());

        List<ScheduleConflict> conflicts = (periodId != null)
                ? scheduleConflictRepository.findUnresolvedByScheduleIdsIn(scheduleIds)
                : scheduleConflictRepository.findByScheduleIdsIn(scheduleIds);

        return conflicts.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getSchedule().getId(),
                        Collectors.mapping(c -> c.getConflictType().name(), Collectors.toList())
                ));
    }

    public List<StaffResponse> findReplacements(Integer periodId, LocalDate workDate, String shiftTypeId,
                                                 Integer originalStaffId, Integer requiredCount) {
        List<Staff> replacements = conflictDetectionService.findReplacements(
                periodId, workDate, shiftTypeId, originalStaffId, requiredCount, null, true);
        return replacements.stream().map(s -> StaffResponse.builder()
                    .id(s.getId())
                    .fullName(s.getFullName())
                    .phone(s.getPhone())
                    .specialty(s.getSpecialty() != null ? StaffResponse.SpecialtyResponse.builder()
                            .id(s.getSpecialty().getId())
                            .name(s.getSpecialty().getName())
                            .build() : null)
                    .maxShiftsPerMonth(s.getMaxShiftsPerMonth())
                    .isActive(s.getIsActive())
                    .build())
                .collect(Collectors.toList());
    }

    public ScheduleResponse overrideConflict(Integer scheduleId, String reason) {
        Schedule schedule = scheduleRepository.findByIdWithDetails(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch với ID: " + scheduleId));

        Integer staffId = schedule.getStaff().getId();
        LocalDate workDate = schedule.getWorkDate();
        String shiftTypeName = schedule.getShiftType().getName();

        schedule.setHasConflict(false);
        scheduleRepository.save(schedule);

        auditHistoryService.logAction("schedule", scheduleId, AuditHistory.ActionType.UPDATE,
                schedule, Map.of("override", true, "reason", reason),
                authContextService.getCurrentStaff().getId());

        // Notify the affected staff so they are aware their conflict was overridden by a manager.
        notificationService.createNotification(staffId, new NotificationDTO(
                "Xung đột lịch trực đã được xử lý",
                "Lịch " + shiftTypeName + " ngày " + workDate
                        + " của bạn đã được ghi đè xung đột. Lý do: " + reason));

        // Broadcast conflict resolved event so the realtime UI clears the conflict badge
        conflictBroadcastService.broadcastConflictResolved(null, schedule.getId());

        return toResponse(schedule, null);
    }

    private void createCompensationDay(Schedule schedule) {
        // CRITICAL: Each L01 schedule gets ONE compensation day
        // Same staff CAN have multiple comp days on same date (e.g., Fri + Mon both → Tuesday)
        if (!compensationDayRepository.findByScheduleId(schedule.getId()).isEmpty()) {
            return;
        }

        LocalDate shiftDate = schedule.getWorkDate();
        LocalDate compensationDate = compensationDateCalculator.calculate(shiftDate);

        // BUGFIX (was S2): use INSERT IGNORE so that the (staff_id, compensation_date)
        // unique-key violation (when staff already has a comp day from a *different*
        // L01 that maps to the same date — e.g., Fri 8/22 and Mon 8/24 both map to
        // comp-day Tue 8/25) does NOT throw and poison the Hibernate session.
        // The previous code used compensationDayRepository.save() which catches
        // DataIntegrityViolationException, but by then the Session is already in a
        // broken state and any subsequent query throws AssertionFailure
        // ("Entry for instance of 'CompensationDay' has a null identifier").
        // Returning the IGNORE result also lets us audit-log only newly-created
        // rows, which is the correct semantics.
        try {
            int inserted = databaseCompatibilityHelper.insertCompensationDayIfAbsent(
                    schedule.getStaff().getId(),
                    schedule.getPeriod().getId(),
                    schedule.getId(),
                    shiftDate,
                    compensationDate,
                    "Ngày nghỉ bù tự động từ ca L01");
            if (inserted == 0) {
                // Pre-existing comp day on (staff_id, compensation_date) from another L01.
                // This is normal — same staff can stack L01s that all map to the same
                // comp day (e.g., Fri + Sat both → Tue next week). Just skip audit-log.
                org.slf4j.LoggerFactory.getLogger(ScheduleService.class)
                        .debug("Compensation day for staff {} on {} already exists (skipped, no new row)",
                                schedule.getStaff().getId(), compensationDate);
                return;
            }
        } catch (Exception e) {
            // Defence in depth — INSERT IGNORE should not throw, but if the underlying
            // driver fails for some reason (e.g. connection drop), log and continue so
            // the schedule creation itself still succeeds.
            org.slf4j.LoggerFactory.getLogger(ScheduleService.class)
                    .warn("Failed to insert compensation day for schedule {}: {}",
                            schedule.getId(), e.getMessage());
            return;
        }

        // Re-fetch the (possibly pre-existing) compensation day for audit logging.
        // We use the schedule_id join to find the row that was just created OR that
        // already existed (in which case INSERT IGNORE returned 0 and we returned
        // above). At this point inserted >= 1, so a row exists.
        compensationDayRepository.findByScheduleId(schedule.getId()).stream()
                .filter(cd -> cd.getCompensationDate() != null
                        && cd.getCompensationDate().equals(compensationDate))
                .findFirst()
                .ifPresent(saved -> {
                    if (saved.getId() != null) {
                        auditHistoryService.logAction("compensation_day", saved.getId(),
                                AuditHistory.ActionType.INSERT, null, saved,
                                authContextService.getCurrentStaff().getId());
                    }
                });
    }

    /**
     * Bulk create L01 (trực 24/24) schedules.
     * Validates: all entries must use L01, all staff must be ACTIVE.
     * For each entry: creates schedule + auto-creates compensation_day.
     */
    @Transactional
    public BulkL01Response createBulkL01(BulkL01Request request) {
        List<String> errors = new java.util.ArrayList<>();
        List<BulkL01Response.BulkL01ScheduleResult> results = new java.util.ArrayList<>();
        int successCount = 0;

        SchedulePeriod period = periodRepository.findById(request.getPeriodId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + request.getPeriodId()));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể thêm lịch khi kỳ lịch ở trạng thái DRAFT");
        }

        ShiftType l01ShiftType = shiftTypeRepository.findById(ConflictDetectionService.SHIFT_TYPE_L01)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy loại ca L01"));

        // OPTIMIZATION: batch load all staff upfront to avoid N individual findById calls
        List<Integer> staffIds = request.getEntries().stream()
                .map(BulkL01Request.L01Entry::getStaffId)
                .distinct()
                .collect(Collectors.toList());
        Map<Integer, Staff> staffMap = staffIds.isEmpty() ? Collections.emptyMap()
                : staffRepository.findAllById(staffIds).stream()
                        .collect(Collectors.toMap(Staff::getId, s -> s));

        // Track in-loop assignments to catch sibling L01↔L02 conflicts
        Map<String, Set<String>> inLoopAssignments = new java.util.HashMap<>();

        for (BulkL01Request.L01Entry entry : request.getEntries()) {
            Integer staffId = entry.getStaffId();
            LocalDate workDate = entry.getWorkDate();
            String key = staffId + "_" + workDate;

            // Validate period date range
            if (workDate.isBefore(period.getStartDate()) || workDate.isAfter(period.getEndDate())) {
                errors.add("Ngày " + workDate + " nằm ngoài kỳ lịch");
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error("Ngày nằm ngoài kỳ lịch")
                        .build());
                continue;
            }

            // Validate: do not schedule on holidays
            if (holidayRepository.existsByHolidayDateAndIsActiveTrue(workDate)) {
                errors.add("Ngày " + workDate + " là ngày nghỉ lễ");
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error("Ngày nghỉ lễ, không thể xếp lịch")
                        .build());
                continue;
            }

            // Validate staff exists and is ACTIVE — OPTIMIZATION: use pre-loaded staff map
            Staff staff = staffMap.get(staffId);
            if (staff == null) {
                errors.add("Nhân sự ID " + staffId + " không tồn tại");
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error("Nhân sự không tồn tại")
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(staff.getIsActive())) {
                errors.add("Nhân sự ID " + staffId + " không hoạt động");
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error("Nhân sự không hoạt động")
                        .build());
                continue;
            }

            // Check unique constraint
            if (scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(
                    request.getPeriodId(), staffId, ConflictDetectionService.SHIFT_TYPE_L01, workDate).isPresent()) {
                errors.add("Nhân sự " + staffId + " đã có L01 ngày " + workDate);
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error("Đã có L01 trong ngày")
                        .build());
                continue;
            }

            // Check in-loop conflict (L01 vs L02 in same batch)
            Set<String> existingShifts = inLoopAssignments.get(key);
            if (existingShifts != null && !existingShifts.isEmpty()) {
                String errMsg = "Nhân sự " + staffId + " đã có lịch xung đột trong batch ngày " + workDate;
                errors.add(errMsg);
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error(errMsg)
                        .build());
                continue;
            }

            // Validate conflicts against DB state
            try {
                conflictDetectionService.validateAndThrow(staffId, workDate,
                        ConflictDetectionService.SHIFT_TYPE_L01, null, request.getPeriodId());
            } catch (ConflictException e) {
                errors.add("Nhân sự " + staffId + " ngày " + workDate + ": " + e.getMessage());
                results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                        .staffId(staffId)
                        .workDate(workDate.toString())
                        .error(e.getMessage())
                        .build());
                continue;
            }

            // Create schedule
            Schedule schedule = Schedule.builder()
                    .period(period)
                    .workDate(workDate)
                    .staff(staff)
                    .shiftType(l01ShiftType)
                    .hasConflict(false)
                    .build();

            Schedule saved = scheduleRepository.save(schedule);
            inLoopAssignments.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(ConflictDetectionService.SHIFT_TYPE_L01);

            // Auto-create compensation day
            createCompensationDay(saved);

            // OPTIMIZATION: calculate compDate directly without extra DB query
            LocalDate compDate = compensationDateCalculator.calculate(saved.getWorkDate());

            auditHistoryService.logAction("schedule", saved.getId(), AuditHistory.ActionType.INSERT,
                    null, saved, authContextService.getCurrentStaff().getId());

            // Notify staff with compensation date if applicable
            if (compDate != null) {
                notificationService.createNotification(staffId,
                        new NotificationDTO("Phân công lịch mới",
                                "Bạn được phân công lịch L01 ngày " + workDate + ". Ngày nghỉ bù: " + compDate + "."));
            } else {
                notificationService.createNotification(staffId,
                        new NotificationDTO("Phân công lịch mới",
                                "Bạn được phân công lịch L01 ngày " + workDate + "."));
            }

            results.add(BulkL01Response.BulkL01ScheduleResult.builder()
                    .scheduleId(saved.getId())
                    .staffId(staffId)
                    .workDate(workDate.toString())
                    .build());
            successCount++;
        }

        return BulkL01Response.builder()
                .successCount(successCount)
                .failureCount(request.getEntries().size() - successCount)
                .totalCount(request.getEntries().size())
                .errors(errors)
                .results(results)
                .build();
    }

    /**
     * Bulk create schedules for any shift type (L01/L02/L03/L04).
     * Validates: staff exists, active, not already assigned same type+date,
     * not a compensation day, not a holiday, and no cross-type conflicts.
     * Creates compensation days automatically for L01 entries.
     * Does NOT auto-create compensation days for L02/L03/L04.
     *
     * @param request     the bulk request containing entries
     * @param shiftTypeId the shift type ID for all entries (L01/L02/L03/L04)
     * @return BulkScheduleResponse with partial success details
     */
    @Transactional
    public BulkScheduleResponse bulkCreateSchedules(BulkScheduleRequest request, String shiftTypeId) {
        List<BulkScheduleResponse.BulkResultEntry> results = new ArrayList<>();
        int successCount = 0;

        SchedulePeriod period = periodRepository.findById(request.getPeriodId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + request.getPeriodId()));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể thêm lịch khi kỳ lịch ở trạng thái DRAFT");
        }

        ShiftType shiftType = shiftTypeRepository.findById(shiftTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy loại ca với ID: " + shiftTypeId));

        // OPTIMIZATION: batch load all staff upfront to avoid N individual findById calls
        List<Integer> bulkStaffIds = request.getEntries().stream()
                .map(BulkScheduleRequest.BulkScheduleEntry::getStaffId)
                .distinct()
                .collect(Collectors.toList());
        Map<Integer, Staff> bulkStaffMap = bulkStaffIds.isEmpty() ? Collections.emptyMap()
                : staffRepository.findAllById(bulkStaffIds).stream()
                        .collect(Collectors.toMap(Staff::getId, s -> s));

        Map<String, Set<String>> inLoopAssignments = new HashMap<>();

        for (BulkScheduleRequest.BulkScheduleEntry entry : request.getEntries()) {
            Integer staffId = entry.getStaffId();
            LocalDate workDate = entry.getWorkDate();
            String key = staffId + "_" + workDate;

            if (workDate.isBefore(period.getStartDate()) || workDate.isAfter(period.getEndDate())) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Ngày nằm ngoài kỳ lịch")
                        .build());
                continue;
            }

            // OPTIMIZATION: use pre-loaded staff map
            Staff staff = bulkStaffMap.get(staffId);
            if (staff == null) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Nhân sự không tồn tại")
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(staff.getIsActive())) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Nhân sự không hoạt động")
                        .build());
                continue;
            }

            if (scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(
                    request.getPeriodId(), staffId, shiftTypeId, workDate).isPresent()) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Đã có lịch " + shiftTypeId + " trong ngày")
                        .build());
                continue;
            }

            Set<String> existingShifts = inLoopAssignments.get(key);
            if (existingShifts != null && !existingShifts.isEmpty()) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Nhân sự đã có lịch xung đột trong batch ngày " + workDate)
                        .build());
                continue;
            }

            if (!compensationDayRepository.findByStaffIdAndCompensationDate(staffId, workDate).isEmpty()) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Ngày này là ngày nghỉ bù của nhân sự")
                        .build());
                continue;
            }

            if (holidayRepository.existsByHolidayDateAndIsActiveTrue(workDate)) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error("Ngày là ngày nghỉ lễ")
                        .build());
                continue;
            }

            try {
                conflictDetectionService.validateAndThrow(staffId, workDate, shiftTypeId, null, request.getPeriodId());
            } catch (ConflictException e) {
                results.add(BulkScheduleResponse.BulkResultEntry.builder()
                        .workDate(workDate.toString())
                        .staffId(staffId)
                        .error(e.getMessage())
                        .build());
                continue;
            }

            Schedule schedule = Schedule.builder()
                    .period(period)
                    .workDate(workDate)
                    .staff(staff)
                    .shiftType(shiftType)
                    .hasConflict(false)
                    .build();

            Schedule saved = scheduleRepository.save(schedule);
            inLoopAssignments.computeIfAbsent(key, k -> new HashSet<>()).add(shiftTypeId);

            if (ConflictDetectionService.SHIFT_TYPE_L01.equals(shiftTypeId)) {
                createCompensationDay(saved);
            }

            // OPTIMIZATION: calculate compDate directly without extra DB query
            LocalDate compDate = null;
            if (ConflictDetectionService.SHIFT_TYPE_L01.equals(shiftTypeId)) {
                compDate = compensationDateCalculator.calculate(saved.getWorkDate());
            }

            if (compDate != null) {
                notificationService.createNotification(staffId,
                        new NotificationDTO("Phân công lịch mới",
                                "Bạn được phân công lịch " + shiftType.getName() + " vào ngày " + workDate + ". Ngày nghỉ bù: " + compDate + "."));
            } else {
                notificationService.createNotification(staffId,
                        new NotificationDTO("Phân công lịch mới",
                                "Bạn được phân công lịch " + shiftType.getName() + " vào ngày " + workDate + "."));
            }

            results.add(BulkScheduleResponse.BulkResultEntry.builder()
                    .workDate(workDate.toString())
                    .staffId(staffId)
                    .scheduleId(saved.getId())
                    .build());
            successCount++;
        }

        return BulkScheduleResponse.builder()
                .totalRequested(request.getEntries().size())
                .successCount(successCount)
                .failureCount(request.getEntries().size() - successCount)
                .results(results)
                .build();
    }

    /**
     * Get L04 (expert clinic) schedules for a specific week, grouped by day.
     */
    public ExpertClinicWeeklyResponse getExpertClinicWeeklyView(
            Integer periodId, LocalDate weekStart, Integer specialtyId) {

        SchedulePeriod period = periodRepository.findById(periodId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + periodId));

        LocalDate effectiveWeekStart = (weekStart != null) ? weekStart : period.getStartDate();
        LocalDate weekEnd = effectiveWeekStart.plusDays(6);

        // Get all L04 schedules within the period, filtered by specialty if provided
        List<Schedule> allL04 = scheduleRepository.findExpertClinicByPeriodAndSpecialty(periodId, specialtyId);

        // Filter by week range
        List<Schedule> weekSchedules = allL04.stream()
                .filter(s -> !s.getWorkDate().isBefore(effectiveWeekStart) && !s.getWorkDate().isAfter(weekEnd))
                .collect(java.util.stream.Collectors.toList());

        // OPTIMIZATION: batch load all compensation days in ONE query
        List<Integer> scheduleIds = allL04.stream().map(Schedule::getId).collect(Collectors.toList());
        Map<Integer, LocalDate> compDateMap = compensationDayRepository.findByScheduleIds(scheduleIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        cd -> cd.getSchedule().getId(),
                        CompensationDay::getCompensationDate,
                        (a, b) -> a
                ));

        // Group schedules by date
        Map<LocalDate, List<Schedule>> byDate = weekSchedules.stream()
                .collect(java.util.stream.Collectors.groupingBy(Schedule::getWorkDate));

        // Build week schedule (all 7 days, even if empty)
        List<ExpertClinicWeeklyResponse.DaySchedule> weekSchedule = new java.util.ArrayList<>();
        LocalDate current = effectiveWeekStart;
        while (!current.isAfter(weekEnd)) {
            List<ScheduleResponse> dayResponses = byDate.getOrDefault(current, java.util.Collections.emptyList()).stream()
                    .map(s -> toResponse(s, compDateMap.get(s.getId())))
                    .collect(java.util.stream.Collectors.toList());

            weekSchedule.add(ExpertClinicWeeklyResponse.DaySchedule.builder()
                    .date(current)
                    .dayOfWeek(DateUtils.getDayOfWeekVietnamese(current.getDayOfWeek()))
                    .dayOfWeekIndex(current.getDayOfWeek().getValue())
                    .schedules(dayResponses)
                    .build());
            current = current.plusDays(1);
        }

        return ExpertClinicWeeklyResponse.builder()
                .periodId(periodId)
                .periodName(period.getPeriodName())
                .weekStart(effectiveWeekStart)
                .weekEnd(weekEnd)
                .weekSchedule(weekSchedule)
                .build();
    }

    private ScheduleResponse toResponse(Schedule schedule) {
        return toResponse(schedule, null, Map.of());
    }

    private ScheduleResponse toResponse(Schedule schedule, LocalDate compDateOverride) {
        return toResponse(schedule, compDateOverride, Map.of());
    }

    private ScheduleResponse toResponse(
            Schedule schedule,
            LocalDate compDateOverride,
            Map<Integer, List<String>> conflictReasonsMap) {

        List<String> staffRoles = schedule.getStaff().getStaffRoles().stream()
                .map(StaffRole::getRole)
                .filter(java.util.Objects::nonNull)
                .map(AppRole::getName)
                .filter(java.util.Objects::nonNull)
                .map(RoleName::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<String> conflictReasons = conflictReasonsMap.getOrDefault(schedule.getId(), List.of());

        LocalDate compensationDate = compDateOverride != null ? compDateOverride
                : compensationDayRepository.findByScheduleId(schedule.getId()).stream()
                        .map(CompensationDay::getCompensationDate)
                        .filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null);

        return ScheduleResponse.builder()
                .id(schedule.getId())
                .periodId(schedule.getPeriod().getId())
                .period(ScheduleResponse.PeriodSummary.builder()
                        .id(schedule.getPeriod().getId())
                        .periodName(schedule.getPeriod().getPeriodName())
                        .startDate(schedule.getPeriod().getStartDate())
                        .endDate(schedule.getPeriod().getEndDate())
                        .status(schedule.getPeriod().getStatus().name())
                        .build())
                .workDate(schedule.getWorkDate())
                .staff(ScheduleResponse.StaffSummary.builder()
                        .id(schedule.getStaff().getId())
                        .username(schedule.getStaff().getUsername())
                        .fullName(schedule.getStaff().getFullName())
                        .specialtyName(schedule.getStaff().getSpecialty() != null ? schedule.getStaff().getSpecialty().getName() : null)
                        .roles(staffRoles)
                        .build())
                .shiftType(ScheduleResponse.ShiftTypeSummary.builder()
                        .id(schedule.getShiftType().getId())
                        .name(schedule.getShiftType().getName())
                        .description(schedule.getShiftType().getDescription())
                        .startTime(schedule.getShiftType().getStartTime())
                        .endTime(schedule.getShiftType().getEndTime())
                        .isOvernight(schedule.getShiftType().getIsOvernight())
                        .fatigueScore(schedule.getShiftType().getFatigueScore())
                        .build())
                .requirementId(null)
                .compensationDate(compensationDate)
                .conflictReasons(conflictReasons)
                .notes(null)
                .hasConflict(schedule.getHasConflict())
                .createdAt(schedule.getCreatedAt())
                .updatedAt(schedule.getUpdatedAt())
                .build();
    }
}
