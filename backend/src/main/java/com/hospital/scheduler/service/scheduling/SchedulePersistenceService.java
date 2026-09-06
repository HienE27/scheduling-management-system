package com.hospital.scheduler.service.scheduling;

import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.repository.CompensationDayRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.service.AuditHistoryService;
import com.hospital.scheduler.util.CompensationDateCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists individual schedules and handles compensation-day side effects.
 */
@Slf4j
@Component
public class SchedulePersistenceService {

    private final AuditHistoryService auditHistoryService;
    private final CompensationDateCalculator compensationDateCalculator;
    private final SchedulingStateAccessor stateAccessor;
    private final ScheduleRepository scheduleRepository;
    private final CompensationDayRepository compensationDayRepository;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;

    public SchedulePersistenceService(AuditHistoryService auditHistoryService,
                                      CompensationDateCalculator compensationDateCalculator,
                                      SchedulingStateAccessor stateAccessor,
                                      @Lazy ScheduleRepository scheduleRepository,
                                      CompensationDayRepository compensationDayRepository,
                                      DatabaseCompatibilityHelper databaseCompatibilityHelper) {
        this.auditHistoryService = auditHistoryService;
        this.compensationDateCalculator = compensationDateCalculator;
        this.stateAccessor = stateAccessor;
        this.scheduleRepository = scheduleRepository;
        this.compensationDayRepository = compensationDayRepository;
        this.databaseCompatibilityHelper = databaseCompatibilityHelper;
    }

    /**
     * Build a Schedule entity (does not save).
     */
    public Schedule buildSchedule(SchedulePeriod period, Staff staff, ShiftRequirement req, LocalDate workDate) {
        return Schedule.builder()
                .period(period)
                .staff(staff)
                .shiftType(req.getShiftType())
                .workDate(workDate)
                .requirement(req)
                .hasConflict(false)
                .build();
    }

    /**
     * Build a Schedule entity without a requirement (used by GA-fallback top-up).
     */
    public Schedule buildNewSchedule(Staff staff, ShiftRequirement req, LocalDate workDate) {
        return Schedule.builder()
                .staff(staff)
                .shiftType(req.getShiftType())
                .workDate(workDate)
                .period(req.getPeriod())
                .requirement(req)
                .hasConflict(false)
                .isPreview(false)
                .build();
    }

    /**
     * Track a staff→date→shift assignment in memory (used during preview/algorithm run).
     */
    public void trackAssignment(Staff staff, LocalDate workDate, String shiftTypeId) {
        stateAccessor.addAssignment(staff.getId(), workDate, shiftTypeId);
        // Also track compensation day for L01
        if ("L01".equals(shiftTypeId)) {
            LocalDate compDate = compensationDateCalculator.calculate(workDate);
            if (compDate != null) {
                stateAccessor.addCompensationShiftDate(staff.getId(), compDate);
            }
        }
    }

    /**
     * Create a compensation day for a saved L01 schedule.
     * Idempotent: checks both in-memory cache and DB before inserting.
     */
    public void createCompensationDayForAuto(com.hospital.scheduler.repository.CompensationDayRepository compensationDayRepository,
                                            Schedule schedule) {
        if (schedule == null || schedule.getWorkDate() == null) {
            log.warn("createCompensationDayForAuto: schedule or workDate is null");
            return;
        }
        LocalDate shiftDate = schedule.getWorkDate();
        LocalDate compensationDate = compensationDateCalculator.calculate(shiftDate);

        log.info("createCompensationDayForAuto: staffId={}, shiftDate={}, compDate={}",
                schedule.getStaff().getId(), shiftDate, compensationDate);

        String compKey = schedule.getStaff().getId() + "_" + compensationDate.toString();

        if (stateAccessor.getAllCompensationShiftDates().contains(compKey)) {
            log.debug("Compensation day already tracked in memory for {}", compKey);
            return;
        }

        // Check DB
        if (compensationDayRepository.existsByStaffIdAndCompensationDate(
                schedule.getStaff().getId(), compensationDate)) {
            log.warn("Compensation day already exists in DB for staff {} on {}",
                    schedule.getStaff().getId(), compensationDate);
            stateAccessor.addAllCompensationShiftDate(compKey);
            return;
        }

        if (schedule.getId() != null && compensationDayRepository.existsByScheduleId(schedule.getId())) {
            log.warn("Schedule {} already has a compensation day", schedule.getId());
            stateAccessor.addAllCompensationShiftDate(compKey);
            return;
        }

        try {
            int inserted = databaseCompatibilityHelper.insertCompensationDayIfAbsent(
                    schedule.getStaff().getId(),
                    schedule.getPeriod().getId(),
                    schedule.getId(),
                    shiftDate,
                    compensationDate,
                    "Ngày nghỉ bù tự động từ ca L01"
            );
            if (inserted > 0) {
                log.info("Compensation day INSERTED via INSERT IGNORE: staffId={}, compDate={}",
                        schedule.getStaff().getId(), compensationDate);
                stateAccessor.addAllCompensationShiftDate(compKey);
            } else {
                log.debug("Compensation day already existed (INSERT IGNORE): staffId={}, compDate={}",
                        schedule.getStaff().getId(), compensationDate);
                stateAccessor.addAllCompensationShiftDate(compKey);
            }
        } catch (Exception e) {
            log.warn("Failed to insert compensation day for staff {} on {}: {}",
                    schedule.getStaff().getId(), compensationDate, e.getMessage());
            stateAccessor.addAllCompensationShiftDate(compKey);
        }
    }

    /**
     * Overload with an explicit compensation date (bypasses calculator).
     * Used when the CSP has already chosen the best option among flexible
     * compensation days (e.g. Tue/Wed/Thu for Fri/Sat duty).
     */
    public void createCompensationDayForAuto(
            com.hospital.scheduler.repository.CompensationDayRepository compensationDayRepository,
            Schedule schedule,
            LocalDate compensationDate) {
        if (schedule == null || schedule.getWorkDate() == null || compensationDate == null) {
            log.warn("createCompensationDayForAuto(override): schedule or date is null");
            return;
        }
        LocalDate shiftDate = schedule.getWorkDate();

        log.info("createCompensationDayForAuto(override): staffId={}, shiftDate={}, compDate={}",
                schedule.getStaff().getId(), shiftDate, compensationDate);

        String compKey = schedule.getStaff().getId() + "_" + compensationDate.toString();

        if (stateAccessor.getAllCompensationShiftDates().contains(compKey)) {
            log.debug("Compensation day already tracked in memory for {}", compKey);
            return;
        }

        if (compensationDayRepository.existsByStaffIdAndCompensationDate(
                schedule.getStaff().getId(), compensationDate)) {
            log.warn("Compensation day already exists in DB for staff {} on {}",
                    schedule.getStaff().getId(), compensationDate);
            stateAccessor.addAllCompensationShiftDate(compKey);
            return;
        }

        if (schedule.getId() != null && compensationDayRepository.existsByScheduleId(schedule.getId())) {
            log.warn("Schedule {} already has a compensation day", schedule.getId());
            stateAccessor.addAllCompensationShiftDate(compKey);
            return;
        }

        try {
            int inserted = databaseCompatibilityHelper.insertCompensationDayIfAbsent(
                    schedule.getStaff().getId(),
                    schedule.getPeriod().getId(),
                    schedule.getId(),
                    shiftDate,
                    compensationDate,
                    "Ngày nghỉ bù tự động từ ca L01"
            );
            if (inserted > 0) {
                log.info("Compensation day INSERTED (override): staffId={}, compDate={}",
                        schedule.getStaff().getId(), compensationDate);
                stateAccessor.addAllCompensationShiftDate(compKey);
            } else {
                log.debug("Compensation day already existed (override): staffId={}, compDate={}",
                        schedule.getStaff().getId(), compensationDate);
                stateAccessor.addAllCompensationShiftDate(compKey);
            }
        } catch (Exception e) {
            log.warn("Failed to insert compensation day (override) for staff {} on {}: {}",
                    schedule.getStaff().getId(), compensationDate, e.getMessage());
            stateAccessor.addAllCompensationShiftDate(compKey);
        }
    }

    /**
     * BUGFIX (was AS1/AS2): Try to save a schedule in a NESTED transaction so
     * a unique-key violation (DuplicateKeyException on the SA-perfected
     * schedule.uk_schedule_unique constraint) rolls back only this single save.
     * Without nested transaction, the SQL exception poisons the caller's
     * outer transaction and the whole auto-schedule run aborts with HTTP 500
     * "Transaction silently rolled back because it has been marked as rollback-only".
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Pre-check: skip if a different schedule already occupies the same
     *       (period, staff, date, shiftType) tuple.</li>
     *   <li>Run the save in a NEW transaction (`REQUIRES_NEW`) so the catch
     *       block can swallow the DataIntegrityViolationException without
     *       marking the caller's transaction for rollback.</li>
     *   <li>Returns true if the save succeeded, false otherwise.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean trySaveOrSkip(Schedule schedule, Integer periodId, Integer staffId,
                                 String shiftTypeId, LocalDate workDate) {
        if (schedule == null) return false;
        // Pre-check: skip if the target slot is already occupied by another
        // (non-self) schedule row. The caller still owns this in-memory entity
        // so the pre-check may still collide with itself; if so, the catch
        // handles it.
        try {
            if (scheduleRepository != null
                    && scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(
                            periodId, staffId, shiftTypeId, workDate).isPresent()) {
                // Slot already filled — caller will skip + count.
                log.debug("trySaveOrSkip: skip staff={} date={} shift={} (slot occupied)",
                        staffId, workDate, shiftTypeId);
                return false;
            }
        } catch (Exception preCheckEx) {
            log.warn("trySaveOrSkip: pre-check failed for staff={} date={} shift={}: {}",
                    staffId, workDate, shiftTypeId, preCheckEx.getMessage());
            // Continue — the catch on save will catch the duplicate.
        }
        try {
            scheduleRepository.save(schedule);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            log.warn("trySaveOrSkip: duplicate-key on staff={} date={} shift={} — mutated row skipped: {}",
                    staffId, workDate, shiftTypeId, ex.getMostSpecificCause().getMessage());
            return false;
        }
    }
}
