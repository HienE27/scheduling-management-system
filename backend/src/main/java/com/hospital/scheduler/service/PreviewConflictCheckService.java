package com.hospital.scheduler.service;

import com.hospital.scheduler.entity.CompensationDay;
import com.hospital.scheduler.entity.Schedule;
import com.hospital.scheduler.repository.CompensationDayRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.util.CompensationDateCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for running preview conflict checks in a separate transaction.
 * This avoids Spring proxy self-invocation issues when called from AutoSchedulingService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewConflictCheckService {

    private final ScheduleRepository scheduleRepository;
    private final CompensationDayRepository compensationDayRepository;
    private final DatabaseCompatibilityHelper databaseCompatibilityHelper;
    private final ConflictDetectionService conflictDetectionService;
    private final CompensationDateCalculator compensationDateCalculator;

    /**
     * Temporarily saves schedules with isPreview=true, runs conflict check,
     * then deletes all preview data. Returns the actual conflict count.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int checkConflictsForPreview(List<Schedule> schedulesToPreview, Integer periodId) {
        List<Schedule> saved = new ArrayList<>();
        try {
            // Save all preview schedules
            for (Schedule s : schedulesToPreview) {
                s.setIsPreview(true);
                Schedule savedSchedule = scheduleRepository.save(s);
                s.setId(savedSchedule.getId());
                saved.add(savedSchedule);
                
                // Create compensation days for L01 schedules
                // CRITICAL FIX: Check duplicate before creating to avoid assertion failure
                if ("L01".equals(s.getShiftType().getId())) {
                    LocalDate compDate = compensationDateCalculator.calculate(s.getWorkDate());
                    
                    // Check if compensation day already exists
                    boolean exists = compensationDayRepository.existsByStaffIdAndCompensationDate(
                            s.getStaff().getId(), compDate);
                    if (!exists) {
                        // Use INSERT IGNORE via native query to avoid DataIntegrityViolationException
                        databaseCompatibilityHelper.insertCompensationDayIfAbsent(
                                s.getStaff().getId(),
                                s.getPeriod().getId(),
                                savedSchedule.getId(),
                                s.getWorkDate(),
                                compDate,
                                "Preview comp day"
                        );
                    }
                }
            }
            
            // Flush to ensure all data is visible
            scheduleRepository.flush();
            compensationDayRepository.flush();
            
            // Run conflict check
            int conflictCount = conflictDetectionService.checkPeriodConflicts(periodId).getTotalConflicts();
            log.info("Preview conflict check found {} conflicts for {} schedules", conflictCount, saved.size());
            return conflictCount;
            
        } finally {
            // Clean up: delete preview data in reverse order (compensation days first, then schedules)
            for (Schedule s : saved) {
                try {
                    compensationDayRepository.deleteByScheduleId(s.getId());
                } catch (Exception e) {
                    log.debug("Error deleting preview compensation day: {}", e.getMessage());
                }
            }
            for (Schedule s : saved) {
                try {
                    scheduleRepository.deleteByIdQuery(s.getId());
                } catch (Exception e) {
                    log.debug("Error deleting preview schedule: {}", e.getMessage());
                }
            }
            scheduleRepository.flush();
            compensationDayRepository.flush();
        }
    }
}
