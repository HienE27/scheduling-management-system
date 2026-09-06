package com.hospital.scheduler.service.scheduling;

import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.repository.CompensationDayRepository;
import com.hospital.scheduler.repository.DatabaseCompatibilityHelper;
import com.hospital.scheduler.repository.HolidayRepository;
import com.hospital.scheduler.repository.ScheduleRepository;
import com.hospital.scheduler.service.AuditHistoryService;
import com.hospital.scheduler.util.CompensationDateCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchedulePersistenceService}.
 *
 * <p>Verifies the duplicate-prevention contract: in-memory cache first, then DB,
 * then INSERT IF ABSENT. Each branch must mark the slot as known in
 * {@link SchedulingStateAccessor#getAllCompensationShiftDates()} so the
 * auto-scheduler never asks twice.
 */
@ExtendWith(MockitoExtension.class)
class SchedulePersistenceServiceTest {

    @Mock private AuditHistoryService auditHistoryService;
    @Mock private CompensationDayRepository compensationDayRepository;
    @Mock private HolidayRepository holidayRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private DatabaseCompatibilityHelper dbCompat;

    private CompensationDateCalculator calculator;
    private SchedulingStateAccessor stateAccessor;
    private SchedulePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        calculator = new CompensationDateCalculator(holidayRepository);
        stateAccessor = new SchedulingStateAccessor();
        persistenceService = new SchedulePersistenceService(
                auditHistoryService, calculator, stateAccessor, scheduleRepository,
                compensationDayRepository, dbCompat);
    }

    private Schedule buildL01Schedule(SchedulePeriod period, Staff staff, LocalDate workDate) {
        ShiftType l01 = new ShiftType();
        l01.setId("L01");
        l01.setName("Trực 24/24");
        return Schedule.builder()
                .id(42)
                .period(period)
                .staff(staff)
                .shiftType(l01)
                .workDate(workDate)
                .hasConflict(false)
                .build();
    }

    @Test
    void createCompensationDayForAuto_returnsEarlyWhenScheduleIsNull() {
        persistenceService.createCompensationDayForAuto(compensationDayRepository, null);
        verifyNoInteractions(compensationDayRepository, dbCompat);
    }

    @Test
    void createCompensationDayForAuto_returnsEarlyWhenWorkDateIsNull() {
        Schedule s = Schedule.builder().staff(new Staff()).build();
        persistenceService.createCompensationDayForAuto(compensationDayRepository, s);
        verifyNoInteractions(compensationDayRepository, dbCompat);
    }

    @Test
    void createCompensationDayForAuto_skipsInsertWhenInMemoryCacheHit() {
        LocalDate workDate = LocalDate.of(2026, 7, 6); // Monday
        LocalDate compDate = calculator.calculate(workDate); // Tuesday
        Schedule s = buildL01Schedule(new SchedulePeriod(), new Staff(), workDate);
        try {
            s.getStaff().setId(99);
        } catch (Exception ignore) {}

        // Pre-seed cache via accessor
        stateAccessor.addAllCompensationShiftDate(99 + "_" + compDate);

        persistenceService.createCompensationDayForAuto(compensationDayRepository, s);

        verifyNoInteractions(compensationDayRepository, dbCompat);
    }

    @Test
    void createCompensationDayForAuto_skipsInsertWhenDbAlreadyHasCompDay() {
        LocalDate workDate = LocalDate.of(2026, 7, 6);
        LocalDate compDate = calculator.calculate(workDate);
        Schedule s = buildL01Schedule(new SchedulePeriod(), new Staff(), workDate);
        try {
            s.getStaff().setId(100);
        } catch (Exception ignore) {}

        when(compensationDayRepository.existsByStaffIdAndCompensationDate(100, compDate))
                .thenReturn(true);

        persistenceService.createCompensationDayForAuto(compensationDayRepository, s);

        verify(dbCompat, never()).insertCompensationDayIfAbsent(anyInt(), anyInt(), any(), any(), any(), any());
        verify(compensationDayRepository, never()).existsByScheduleId(any());
        assertTrue(stateAccessor.getAllCompensationShiftDates().contains(100 + "_" + compDate));
    }

    @Test
    void createCompensationDayForAuto_insertsWhenNoExistingCompDay() {
        LocalDate workDate = LocalDate.of(2026, 7, 6);
        LocalDate compDate = calculator.calculate(workDate);
        Schedule s = buildL01Schedule(new SchedulePeriod(), new Staff(), workDate);
        try {
            s.getStaff().setId(101);
        } catch (Exception ignore) {}
        SchedulePeriod period = new SchedulePeriod();
        try {
            period.setId(7);
        } catch (Exception ignore) {}
        s.setPeriod(period);

        when(compensationDayRepository.existsByStaffIdAndCompensationDate(101, compDate))
                .thenReturn(false);
        when(compensationDayRepository.existsByScheduleId(42)).thenReturn(false);
        when(dbCompat.insertCompensationDayIfAbsent(
                eq(101), eq(7), eq(42), eq(workDate), eq(compDate), any()))
                .thenReturn(1);

        persistenceService.createCompensationDayForAuto(compensationDayRepository, s);

        verify(dbCompat).insertCompensationDayIfAbsent(
                eq(101), eq(7), eq(42), eq(workDate), eq(compDate), any());
        assertTrue(stateAccessor.getAllCompensationShiftDates().contains(101 + "_" + compDate));
    }

    @Test
    void createCompensationDayForAuto_marksCacheWhenInsertIgnoreReturnsZero() {
        LocalDate workDate = LocalDate.of(2026, 7, 6);
        LocalDate compDate = calculator.calculate(workDate);
        Schedule s = buildL01Schedule(new SchedulePeriod(), new Staff(), workDate);
        try {
            s.getStaff().setId(102);
        } catch (Exception ignore) {}
        SchedulePeriod period = new SchedulePeriod();
        try {
            period.setId(7);
        } catch (Exception ignore) {}
        s.setPeriod(period);

        when(compensationDayRepository.existsByStaffIdAndCompensationDate(102, compDate))
                .thenReturn(false);
        when(compensationDayRepository.existsByScheduleId(42)).thenReturn(false);
        when(dbCompat.insertCompensationDayIfAbsent(
                eq(102), eq(7), eq(42), eq(workDate), eq(compDate), any()))
                .thenReturn(0);

        persistenceService.createCompensationDayForAuto(compensationDayRepository, s);

        assertTrue(stateAccessor.getAllCompensationShiftDates().contains(102 + "_" + compDate),
                "INSERT IF ABSENT returning 0 means row already existed — cache must still be marked");
    }

    @Test
    void createCompensationDayForAuto_swallowsRepositoryExceptionAndMarksCache() {
        LocalDate workDate = LocalDate.of(2026, 7, 6);
        LocalDate compDate = calculator.calculate(workDate);
        Schedule s = buildL01Schedule(new SchedulePeriod(), new Staff(), workDate);
        try {
            s.getStaff().setId(103);
        } catch (Exception ignore) {}
        SchedulePeriod period = new SchedulePeriod();
        try {
            period.setId(7);
        } catch (Exception ignore) {}
        s.setPeriod(period);

        when(compensationDayRepository.existsByStaffIdAndCompensationDate(103, compDate))
                .thenReturn(false);
        when(compensationDayRepository.existsByScheduleId(42)).thenReturn(false);
        when(dbCompat.insertCompensationDayIfAbsent(
                eq(103), eq(7), eq(42), eq(workDate), eq(compDate), any()))
                .thenThrow(new RuntimeException("DB unreachable"));

        assertDoesNotThrow(() ->
                persistenceService.createCompensationDayForAuto(compensationDayRepository, s));
        assertTrue(stateAccessor.getAllCompensationShiftDates().contains(103 + "_" + compDate),
                "Even on exception we must cache the slot so we don't retry forever");
    }

    @Test
    void buildSchedule_setsPeriodStaffShiftTypeRequirement() {
        ShiftType type = new ShiftType();
        type.setId("L01");
        type.setName("Trực 24/24");
        Staff staff = new Staff();
        SchedulePeriod period = new SchedulePeriod();
        ShiftRequirement req = new ShiftRequirement();
        req.setShiftType(type);
        req.setPeriod(period);
        req.setWorkDate(LocalDate.of(2026, 7, 6));

        Schedule s = persistenceService.buildSchedule(period, staff, req, LocalDate.of(2026, 7, 6));

        assertSame(period, s.getPeriod());
        assertSame(staff, s.getStaff());
        assertSame(type, s.getShiftType());
        assertSame(req, s.getRequirement());
        assertEquals(LocalDate.of(2026, 7, 6), s.getWorkDate());
        assertFalse(s.getHasConflict());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
