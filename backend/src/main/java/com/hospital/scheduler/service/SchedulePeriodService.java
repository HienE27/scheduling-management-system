package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.NotificationDTO;
import com.hospital.scheduler.dto.request.SchedulePeriodRequest;
import com.hospital.scheduler.dto.response.BulkPeriodResponse;
import com.hospital.scheduler.dto.response.ConflictCheckResponse;
import com.hospital.scheduler.dto.response.CoverageReportDTO;
import com.hospital.scheduler.dto.response.PublishDryRunResponse;
import java.util.Collections;
import java.util.List;
import com.hospital.scheduler.dto.response.SchedulePeriodResponse;
import com.hospital.scheduler.entity.AuditHistory;
import com.hospital.scheduler.entity.CompensationDay;
import com.hospital.scheduler.entity.Schedule;
import com.hospital.scheduler.entity.SchedulePeriod;
import com.hospital.scheduler.entity.Staff;
import com.hospital.scheduler.exception.BadRequestException;
import com.hospital.scheduler.exception.ResourceNotFoundException;
import com.hospital.scheduler.repository.CompensationDayRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.SchedulePeriodRepository;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.repository.ShiftRequirementRepository;
import com.hospital.scheduler.repository.StaffRepository;
import com.hospital.scheduler.security.AuthContextService;
import com.hospital.scheduler.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SchedulePeriodService {

    private static final Logger log = LoggerFactory.getLogger(SchedulePeriodService.class);

    private final SchedulePeriodRepository periodRepository;
    private final ScheduleRepository scheduleRepository;
    private final CompensationDayRepository compensationDayRepository;
    private final StaffRepository staffRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuthContextService authContextService;
    private final ConflictDetectionService conflictDetectionService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final ShiftRequirementRepository shiftRequirementRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CacheEvictor cacheEvictor;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;

    public List<SchedulePeriodResponse> getAllPeriods() {
        return periodRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<SchedulePeriodResponse> getPeriodsByStatus(SchedulePeriod.PeriodStatus status) {
        return periodRepository.findByStatusOrderByStartDateDesc(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Paginated variant — newest periods first by startDate DESC.
     * Drives the /periods/paginated endpoint that the dashboard relies on.
     */
    public Page<SchedulePeriodResponse> getPeriodsPage(Pageable pageable) {
        return periodRepository
                .findAll(PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "startDate")))
                .map(this::toResponse);
    }

    /**
     * Paginated query with optional server-side status + keyword filters.
     * BUGFIX #6: previously the frontend fetched ONE page and then filtered
     * client-side, losing matches from other pages.
     */
    public Page<SchedulePeriodResponse> getPeriodsPage(
            SchedulePeriod.PeriodStatus status,
            String keyword,
            Pageable pageable) {
        if (status == null && (keyword == null || keyword.isBlank())) {
            return getPeriodsPage(pageable);
        }
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword;
        return periodRepository.findPageWithFilters(status, kw, pageable)
                .map(this::toResponse);
    }

    public SchedulePeriodResponse getPeriodById(Integer id) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));
        return toResponse(period);
    }

    @Transactional
    public SchedulePeriodResponse createPeriod(SchedulePeriodRequest request, Integer generatedById) {
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new BadRequestException("Ngày bắt đầu phải trước ngày kết thúc");
        }

        Staff generatedBy = null;
        if (generatedById != null) {
            generatedBy = staffRepository.findById(generatedById).orElse(null);
        }

        SchedulePeriod period = SchedulePeriod.builder()
                .periodName(request.getPeriodName())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(SchedulePeriod.PeriodStatus.DRAFT)
                .generatedBy(generatedBy)
                .generatedAt(LocalDateTime.now())
                .build();

        SchedulePeriod saved = periodRepository.save(period);
        auditHistoryService.logAction("schedule_period", saved.getId(), AuditHistory.ActionType.INSERT, null, saved, null);
        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public SchedulePeriodResponse updatePeriod(Integer id, SchedulePeriodRequest request) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể sửa kỳ lịch ở trạng thái DRAFT");
        }

        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new BadRequestException("Ngày bắt đầu phải trước ngày kết thúc");
        }

        SchedulePeriod prev = period;
        period.setPeriodName(request.getPeriodName());
        period.setStartDate(request.getStartDate());
        period.setEndDate(request.getEndDate());

        SchedulePeriod saved = periodRepository.save(period);
        auditHistoryService.logAction("schedule_period", id, AuditHistory.ActionType.UPDATE, prev, saved, null);
        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public SchedulePeriodResponse publishPeriod(Integer id, Integer publishedById) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể công bố kỳ lịch ở trạng thái DRAFT");
        }

        ConflictCheckResponse conflictCheck = conflictDetectionService.checkPeriodConflicts(id);
        if (conflictCheck.isHasConflicts()) {
            String msg = conflictCheck.getConflicts().stream()
                    .map(c -> c.getStaffName() + " (" + c.getWorkDate() + "): " + String.join(", ", c.getConflictReasons()))
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Có xung đột");
            throw new BadRequestException("Kỳ lịch có xung đột, không thể công bố: " + msg);
        }

        // Warn if there are coverage gaps but do not block publication
        if (conflictCheck.isHasCoverageGaps()) {
            log.warn("Publishing period {} with {} coverage gaps: {}",
                    id, conflictCheck.getTotalCoverageGaps(), conflictCheck.getCoverageGaps());
        }

        Staff publishedBy = null;
        if (publishedById != null) {
            publishedBy = staffRepository.findById(publishedById).orElse(null);
        }

        period.setStatus(SchedulePeriod.PeriodStatus.PUBLISHED);
        period.setPublishedBy(publishedBy != null ? publishedBy.getUsername() : null);
        period.setPublishedAt(LocalDateTime.now());
        SchedulePeriod saved = periodRepository.save(period);

        // Audit: log the publish action with full context
        auditHistoryService.logAction("schedule_period", saved.getId(), AuditHistory.ActionType.PUBLISH,
                period, Map.of("action", "PUBLISH", "publishedBy", publishedBy != null ? publishedBy.getUsername() : "system"),
                publishedById);

        List<Schedule> periodSchedules = scheduleRepository.findByPeriodId(saved.getId());
        List<CompensationDay> periodCompDays = compensationDayRepository.findByPeriodId(saved.getId());

        // Send per-staff notifications with individual schedule details
        List<Staff> activeStaff = staffRepository.findByIsActiveTrue();

        // OPTIMIZATION: pre-group schedules by staff in ONE pass (O(N) instead of O(N×M))
        Map<Integer, List<Schedule>> schedulesByStaff = periodSchedules.stream()
                .collect(Collectors.groupingBy(s -> s.getStaff().getId()));
        Map<Integer, List<CompensationDay>> compDaysByStaff = periodCompDays.stream()
                .collect(Collectors.groupingBy(cd -> cd.getStaff().getId()));

        for (Staff staff : activeStaff) {
            List<Schedule> staffSchedules = schedulesByStaff.getOrDefault(staff.getId(), List.of());
            List<CompensationDay> staffCompDays = compDaysByStaff.getOrDefault(staff.getId(), List.of());

            String dutyList = staffSchedules.stream()
                    .map(s -> s.getWorkDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " (" + s.getShiftType().getName() + ")")
                    .collect(Collectors.joining("; "));
            String compList = staffCompDays.stream()
                    .map(cd -> cd.getCompensationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                    .collect(Collectors.joining(", "));

            String notifMsg = "Kỳ lịch \"" + saved.getPeriodName() + "\" (" + saved.getStartDate() + " - " + saved.getEndDate() + ") đã được công bố.\n" +
                    "Danh sách trực của bạn: " + (dutyList.isEmpty() ? "không có" : dutyList) + "\n" +
                    "Ngày nghỉ bù: " + (compList.isEmpty() ? "không có" : compList);
            notificationService.createNotification(staff.getId(),
                    new NotificationDTO("Lịch công tác đã được công bố", notifMsg));
        }

        // Send email notifications to all active staff with individual details
        emailService.sendSchedulePublishedEmail(activeStaff, period.getPeriodName(),
                period.getStartDate(), period.getEndDate(), periodSchedules, periodCompDays);

        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    /**
     * Perform a dry-run publish check without persisting anything.
     * Runs conflict detection and staffing coverage validation.
     *
     * <p>Hardened against upstream failures: each downstream call is wrapped
     * so a throw from a single sub-check surfaces as a partial result rather
     * than a 500. The response still carries {@code hasConflicts} and
     * {@code canPublish} derived from whatever subset succeeded.
     */
    public PublishDryRunResponse dryRunPublish(Integer id) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));

        ConflictCheckResponse conflictCheck = null;
        try {
            // M02-F03 "Xem trước khi phát hành": dry-run MUST be read-only.
            // Use checkPeriodConflictsReadOnly so we don't mutate schedule.hasConflict,
            // don't create ScheduleConflict rows, don't send emails, and don't broadcast.
            conflictCheck = conflictDetectionService.checkPeriodConflictsReadOnly(id);
        } catch (RuntimeException e) {
            log.warn("dryRunPublish: conflict check failed for period {}: {}", id, e.getMessage());
        }
        CoverageReportDTO staffingCoverage = null;
        try {
            staffingCoverage = conflictDetectionService.validateStaffingCoverage(id);
        } catch (Exception e) {
            log.warn("Could not generate staffing coverage for period {}: {}", id, e.getMessage());
        }

        boolean hasConflicts = conflictCheck != null && conflictCheck.isHasConflicts();
        int conflictCount = conflictCheck != null ? conflictCheck.getTotalConflicts() : 0;
        List<com.hospital.scheduler.dto.response.ConflictCheckResponse.ConflictDetail> conflicts =
                conflictCheck != null && conflictCheck.getConflicts() != null
                        ? conflictCheck.getConflicts()
                        : Collections.emptyList();
        boolean hasCoverageGaps = conflictCheck != null && conflictCheck.isHasCoverageGaps();
        List<String> coverageGaps = conflictCheck != null && conflictCheck.getCoverageGaps() != null
                ? conflictCheck.getCoverageGaps()
                : Collections.emptyList();

        return PublishDryRunResponse.builder()
                .periodId(id)
                .periodName(period.getPeriodName())
                .hasConflicts(hasConflicts)
                .conflictCount(conflictCount)
                .conflicts(conflicts)
                .hasCoverageGaps(hasCoverageGaps)
                .coverageGaps(coverageGaps)
                .staffingCoverage(staffingCoverage)
                .canPublish(conflictCheck != null && !hasConflicts)
                .build();
    }

    @Transactional
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public SchedulePeriodResponse archivePeriod(Integer id) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.PUBLISHED) {
            throw new BadRequestException("Chỉ có thể lưu trữ kỳ lịch ở trạng thái PUBLISHED");
        }

        SchedulePeriod prev = SchedulePeriod.builder()
                .id(period.getId())
                .periodName(period.getPeriodName())
                .status(period.getStatus())
                .startDate(period.getStartDate())
                .endDate(period.getEndDate())
                .publishedBy(period.getPublishedBy())
                .publishedAt(period.getPublishedAt())
                .createdAt(period.getCreatedAt())
                .build();

        period.setStatus(SchedulePeriod.PeriodStatus.ARCHIVED);
        SchedulePeriod saved = periodRepository.save(period);

        // Audit: log the archive action with current user as actor
        Integer currentStaffId = authContextService.getCurrentStaff() != null 
                ? authContextService.getCurrentStaff().getId() : null;
        auditHistoryService.logAction("schedule_period", saved.getId(), AuditHistory.ActionType.UPDATE,
                prev, saved, currentStaffId);

        cacheEvictor.evictDashboard();
        return toResponse(saved);
    }

    @Transactional
    public BulkPeriodResponse bulkPublish(List<Integer> periodIds, Integer publishedById) {
        // Batch-fetch all periods in one query
        List<SchedulePeriod> periods = periodRepository.findAllByIdIn(periodIds);
        Map<Integer, SchedulePeriod> periodMap = periods.stream()
                .collect(Collectors.toMap(SchedulePeriod::getId, p -> p));

        List<BulkPeriodResponse.PeriodResult> results = periodIds.stream()
                .map(id -> {
                    SchedulePeriod period = periodMap.get(id);
                    // Pre-validate: not found or not DRAFT
                    if (period == null) {
                        return BulkPeriodResponse.PeriodResult.builder()
                                .id(id)
                                .success(false)
                                .message("Không tìm thấy kỳ lịch với ID: " + id)
                                .processedAt(java.time.LocalDateTime.now())
                                .build();
                    }
                    if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
                        return BulkPeriodResponse.PeriodResult.builder()
                                .id(id)
                                .periodName(period.getPeriodName())
                                .success(false)
                                .message("Kỳ lịch '" + period.getPeriodName() + "' không ở trạng thái DRAFT (hiện tại: " + period.getStatus() + ")")
                                .processedAt(java.time.LocalDateTime.now())
                                .build();
                    }
                    return publishSingleResult(id, publishedById);
                })
                .toList();
        cacheEvictor.evictDashboard();
        return BulkPeriodResponse.of(results);
    }

    private BulkPeriodResponse.PeriodResult publishSingleResult(Integer id, Integer publishedById) {
        try {
            SchedulePeriodResponse published = publishPeriod(id, publishedById);
            return BulkPeriodResponse.PeriodResult.builder()
                    .id(id)
                    .periodName(published.getPeriodName())
                    .success(true)
                    .message("Công bố thành công")
                    .data(published)
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        } catch (BadRequestException e) {
            String msg = e.getMessage();
            // Try to extract conflict details from the exception message.
            // Message format: "Kỳ lịch có xung đột, không thể công bố: staffName (date): reason; ..."
            // We include the full message as-is so the frontend can display it.
            return BulkPeriodResponse.PeriodResult.builder()
                    .id(id)
                    .success(false)
                    .message(msg != null ? msg : "Có xung đột lịch trực, không thể công bố")
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            return BulkPeriodResponse.PeriodResult.builder()
                    .id(id)
                    .success(false)
                    .message(e.getMessage())
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        }
    }

    @Transactional
    public BulkPeriodResponse bulkArchive(List<Integer> periodIds) {
        // Batch-fetch all periods in one query
        List<SchedulePeriod> periods = periodRepository.findAllByIdIn(periodIds);
        Map<Integer, SchedulePeriod> periodMap = periods.stream()
                .collect(Collectors.toMap(SchedulePeriod::getId, p -> p));

        List<BulkPeriodResponse.PeriodResult> results = periodIds.stream()
                .map(id -> {
                    SchedulePeriod period = periodMap.get(id);
                    // Pre-validate: not found or not PUBLISHED
                    if (period == null) {
                        return BulkPeriodResponse.PeriodResult.builder()
                                .id(id)
                                .success(false)
                                .message("Không tìm thấy kỳ lịch với ID: " + id)
                                .processedAt(java.time.LocalDateTime.now())
                                .build();
                    }
                    if (period.getStatus() != SchedulePeriod.PeriodStatus.PUBLISHED) {
                        return BulkPeriodResponse.PeriodResult.builder()
                                .id(id)
                                .periodName(period.getPeriodName())
                                .success(false)
                                .message("Kỳ lịch '" + period.getPeriodName() + "' không ở trạng thái PUBLISHED (hiện tại: " + period.getStatus() + ")")
                                .processedAt(java.time.LocalDateTime.now())
                                .build();
                    }
                    return archiveSingleResult(id);
                })
                .toList();
        cacheEvictor.evictDashboard();
        return BulkPeriodResponse.of(results);
    }

    private BulkPeriodResponse.PeriodResult archiveSingleResult(Integer id) {
        try {
            SchedulePeriodResponse archived = archivePeriod(id);
            return BulkPeriodResponse.PeriodResult.builder()
                    .id(id)
                    .periodName(archived.getPeriodName())
                    .success(true)
                    .message("Lưu trữ thành công")
                    .data(archived)
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            return BulkPeriodResponse.PeriodResult.builder()
                    .id(id)
                    .success(false)
                    .message(e.getMessage())
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        }
    }

    @Transactional
    @CacheEvict(value = CacheConfig.DASHBOARD_STATS_CACHE, allEntries = true)
    public void deletePeriod(Integer id) {
        SchedulePeriod period = periodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + id));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể xóa kỳ lịch ở trạng thái DRAFT");
        }

        log.warn("Deleting period id={} name={} - cleaning up child rows", id, period.getPeriodName());

        Integer adminId = authContextService.getCurrentStaff() != null
                ? authContextService.getCurrentStaff().getId() : null;

        // ==== Cleanup child rows (thứ tự FK quan trọng - xóa leaf trước) ====

        // 1. Shift requirements (FK tới schedule_period, gây lỗi trước đó)
        List<com.hospital.scheduler.entity.ShiftRequirement> shiftReqs =
                shiftRequirementRepository.findByPeriodId(id);
        log.info("Deleting {} shift_requirement rows for periodId={}", shiftReqs.size(), id);
        for (com.hospital.scheduler.entity.ShiftRequirement sr : shiftReqs) {
            auditHistoryService.logAction("shift_requirement", sr.getId(),
                    AuditHistory.ActionType.DELETE, sr, null, adminId);
        }
        shiftRequirementRepository.deleteAllByPeriodIdNative(id);

        // 2. Schedules (compensation_day FK → schedule, schedule_exchange FK → schedule, schedule_conflict FK → schedule)
        // Cascade qua period_id, nhưng cần cleanup các bảng trung gian trước.
        // Batch the cleanup queries (single SQL with IN clause) instead of N round-trips
        // — saves hundreds of round-trips for a busy month (e.g. 30 days × 20 staff).
        List<Schedule> schedules = scheduleRepository.findByPeriodId(id);
        log.info("Found {} schedules for periodId={} - batch-cleanup child rows", schedules.size(), id);

        if (!schedules.isEmpty()) {
            List<Integer> scheduleIds = schedules.stream().map(Schedule::getId).toList();
            String inPlaceholder = String.join(",", Collections.nCopies(scheduleIds.size(), "?"));
            Object[] idParams = scheduleIds.toArray();

            // Compensation_day: null out the FK in one UPDATE
            int compUpdated = jdbcTemplate.update(
                    "UPDATE compensation_day SET schedule_id = NULL WHERE schedule_id IN (" + inPlaceholder + ")",
                    idParams);
            log.info("Nullified schedule_id on {} compensation_day rows", compUpdated);

            // Schedule_exchange: bulk delete
            int exchangeDeleted = jdbcTemplate.update(
                    "DELETE FROM schedule_exchange WHERE requester_schedule_id IN ("
                            + inPlaceholder + ") OR target_schedule_id IN (" + inPlaceholder + ")",
                    concatArrays(idParams, idParams));
            log.info("Deleted {} schedule_exchange rows", exchangeDeleted);

            // Schedule_conflict: bulk delete
            int conflictDeleted = jdbcTemplate.update(
                    "DELETE FROM schedule_conflict WHERE schedule_id IN (" + inPlaceholder + ")",
                    idParams);
            log.info("Deleted {} schedule_conflict rows", conflictDeleted);
        }

        // Audit for schedules (still per-row because each entry needs a unique
        // audit record; this is the only unavoidable N+1 in the delete path).
        for (Schedule s : schedules) {
            auditHistoryService.logAction("schedule", s.getId(),
                    AuditHistory.ActionType.DELETE, s, null, adminId);
        }
        // Cascade delete schedules qua period_id
        int schedulesDeleted = jdbcTemplate.update("DELETE FROM schedule WHERE period_id = ?", id);
        log.info("Deleted {} schedules for periodId={}", schedulesDeleted, id);

        // 3. Compensation days còn lại (các comp_day không có schedule nhưng vẫn FK tới period)
        int compDeleted = jdbcTemplate.update("DELETE FROM compensation_day WHERE period_id = ?", id);
        log.info("Deleted {} compensation_day rows for periodId={}", compDeleted, id);

        // 4. Audit for period
        auditHistoryService.logAction("schedule_period", id, AuditHistory.ActionType.DELETE, period, null, adminId);

        // 5. Delete period
        periodRepository.delete(period);
        log.info("Successfully deleted period id={}", id);
        cacheEvictor.evictDashboard();
    }

    /**
     * Delete L04 shift requirements for specialties that have no active staff.
     * This fixes the 39.9% L04 coverage issue by removing impossible requirements.
     *
     * @param periodId the period to clean up
     * @return count of deleted rows
     */
    @Transactional
    public int deleteL04RequirementsWithoutStaff(Integer periodId) {
        SchedulePeriod period = periodRepository.findById(periodId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kỳ lịch với ID: " + periodId));

        if (period.getStatus() != SchedulePeriod.PeriodStatus.DRAFT) {
            throw new BadRequestException("Chỉ có thể xóa requirements ở trạng thái DRAFT");
        }

        // Audit before delete
        List<com.hospital.scheduler.entity.ShiftRequirement> toDelete =
                shiftRequirementRepository.findL04RequirementsWithoutStaff(periodId);
        Integer adminId = authContextService.getCurrentStaff() != null
                ? authContextService.getCurrentStaff().getId() : null;
        for (com.hospital.scheduler.entity.ShiftRequirement sr : toDelete) {
            auditHistoryService.logAction("shift_requirement", sr.getId(),
                    AuditHistory.ActionType.DELETE, sr, null, adminId);
        }

        int deleted = databaseCompatibilityHelper.deleteL04RequirementsWithoutStaff(periodId);
        log.info("Deleted {} L04 requirements without active staff for periodId={}", deleted, periodId);
        return deleted;
    }

    private SchedulePeriodResponse toResponse(SchedulePeriod period) {
        SchedulePeriodResponse.StaffSummary generatedBySummary = null;
        if (period.getGeneratedBy() != null) {
            generatedBySummary = SchedulePeriodResponse.StaffSummary.builder()
                    .id(period.getGeneratedBy().getId())
                    .fullName(period.getGeneratedBy().getFullName())
                    .build();
        }

        return SchedulePeriodResponse.builder()
                .id(period.getId())
                .periodName(period.getPeriodName())
                .startDate(period.getStartDate())
                .endDate(period.getEndDate())
                .status(period.getStatus().name())
                .generatedBy(generatedBySummary)
                .generatedAt(period.getGeneratedAt())
                .publishedBy(period.getPublishedBy())
                .publishedAt(period.getPublishedAt())
                .createdAt(period.getCreatedAt())
                .updatedAt(period.getUpdatedAt())
                .build();
    }

    /**
     * Concatenate two arrays of the same type. Used for SQL "IN (?,?,?) OR IN (?,?,?)"
     * patterns where the same parameter list is referenced twice.
     */
    private static <T> T[] concatArrays(T[] first, T[] second) {
        @SuppressWarnings("unchecked")
        T[] result = (T[]) java.lang.reflect.Array.newInstance(
                first.getClass().getComponentType(), first.length + second.length);
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
