package com.hospital.scheduler.service;

import com.hospital.scheduler.dto.request.BulkL01Request;
import com.hospital.scheduler.dto.request.NotificationDTO;
import com.hospital.scheduler.dto.response.BulkL01Response;
import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.exception.BadRequestException;
import com.hospital.scheduler.exception.ConflictException;
import com.hospital.scheduler.exception.ResourceNotFoundException;
import com.hospital.scheduler.repository.*;
import com.hospital.scheduler.security.AuthContextService;
import com.hospital.scheduler.util.CompensationDateCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleService - Bulk L01 Tests")
class ScheduleServiceBulkL01Test {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private SchedulePeriodRepository periodRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private ShiftTypeRepository shiftTypeRepository;
    @Mock private CompensationDayRepository compensationDayRepository;
    @Mock private ScheduleConflictRepository scheduleConflictRepository;
    @Mock private HolidayRepository holidayRepository;
    @Mock private ConflictDetectionService conflictDetectionService;
    @Mock private AuditHistoryService auditHistoryService;
    @Mock private AuthContextService authContextService;
    @Mock private CompensationDateCalculator compensationDateCalculator;
    @Mock private NotificationService notificationService;
    @Mock private ConflictBroadcastService conflictBroadcastService;
    @Mock private DatabaseCompatibilityHelper dbCompat;

    @InjectMocks
    private ScheduleService scheduleService;

    private Staff testStaff;
    private SchedulePeriod draftPeriod;
    private ShiftType l01ShiftType;

    @BeforeEach
    void setUp() {
        testStaff = Staff.builder()
                .id(1).username("nurse1").fullName("Nguyen Van A").isActive(true)
                .maxShiftsPerMonth(5)
                .build();
        testStaff.setStaffRoles(new HashSet<>());

        draftPeriod = SchedulePeriod.builder()
                .id(1).periodName("Tháng 6/2026")
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .status(SchedulePeriod.PeriodStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        l01ShiftType = ShiftType.builder()
                .id("L01").name("Lịch trực 24/24").isOvernight(true).fatigueScore(3)
                .startTime(java.time.LocalTime.of(7, 30))
                .endTime(java.time.LocalTime.of(7, 30))
                .build();

        // Default stubbing for HolidayRepository and ScheduleConflictRepository
        when(holidayRepository.existsByHolidayDateAndIsActiveTrue(any(LocalDate.class))).thenReturn(false);
        when(scheduleConflictRepository.findUnresolvedByScheduleIdsIn(anyList())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("createBulkL01 - Bulk tạo L01")
    class CreateBulkL01 {

        @Test
        @DisplayName("Hợp lệ: 3 entries -> tạo 3 schedule + 3 compensation day")
        void validEntries_shouldCreateAll() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(
                            BulkL01Request.L01Entry.builder()
                                    .staffId(1).workDate(LocalDate.of(2026, 6, 1)).build(),
                            BulkL01Request.L01Entry.builder()
                                    .staffId(1).workDate(LocalDate.of(2026, 6, 2)).build(),
                            BulkL01Request.L01Entry.builder()
                                    .staffId(1).workDate(LocalDate.of(2026, 6, 3)).build()
                    ))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of(testStaff));
            when(scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(anyInt(), anyInt(), anyString(), any()))
                    .thenReturn(Optional.empty());
            doNothing().when(conflictDetectionService).validateAndThrow(anyInt(), any(), anyString(), any(), anyInt());
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> {
                        Schedule s = inv.getArgument(0);
                        s.setId(100);
                        return s;
                    });
            when(compensationDayRepository.findByScheduleId(anyInt())).thenReturn(List.of());
            when(compensationDateCalculator.calculate(any())).thenReturn(LocalDate.of(2026, 6, 2));
            when(compensationDayRepository.findByStaffIdAndCompensationDate(anyInt(), any())).thenReturn(Optional.empty());
            when(authContextService.getCurrentStaff()).thenReturn(testStaff);

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(3);
            assertThat(result.getFailureCount()).isEqualTo(0);
            assertThat(result.getTotalCount()).isEqualTo(3);
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getResults()).hasSize(3);
            verify(scheduleRepository, times(3)).save(any(Schedule.class));
            // L01 creates compensation days via insertIgnoreCompensationDay (batch insert)
            verify(dbCompat, times(3)).insertCompensationDayIfAbsent(anyInt(), anyInt(), anyInt(), any(), any(), anyString());
        }

        @Test
        @DisplayName("Kỳ lịch không tồn tại -> throw ResourceNotFoundException")
        void periodNotFound_shouldThrow() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(999)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(1).workDate(LocalDate.of(2026, 6, 1)).build()))
                    .build();

            when(periodRepository.findById(999)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.createBulkL01(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Không tìm thấy kỳ lịch");
        }

        @Test
        @DisplayName("Kỳ lịch đã PUBLISHED -> throw BadRequestException")
        void periodPublished_shouldThrow() {
            SchedulePeriod publishedPeriod = SchedulePeriod.builder()
                    .id(2).periodName("Tháng 5/2026")
                    .startDate(LocalDate.of(2026, 5, 1))
                    .endDate(LocalDate.of(2026, 5, 31))
                    .status(SchedulePeriod.PeriodStatus.PUBLISHED)
                    .build();

            BulkL01Request request = BulkL01Request.builder()
                    .periodId(2)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(1).workDate(LocalDate.of(2026, 5, 15)).build()))
                    .build();

            when(periodRepository.findById(2)).thenReturn(Optional.of(publishedPeriod));

            assertThatThrownBy(() -> scheduleService.createBulkL01(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("DRAFT");
        }

        @Test
        @DisplayName("Nhân sự không tồn tại -> ghi lỗi, không throw, tiếp tục")
        void staffNotFound_shouldRecordError() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(999).workDate(LocalDate.of(2026, 6, 1)).build()))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of());

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0)).contains("không tồn tại");
            assertThat(result.getResults().get(0).getError()).contains("không tồn tại");
        }

        @Test
        @DisplayName("Nhân sự không ACTIVE -> ghi lỗi")
        void staffInactive_shouldRecordError() {
            Staff inactiveStaff = Staff.builder()
                    .id(2).username("nurse2").fullName("Tran Thi B").isActive(false)
                    .build();
            inactiveStaff.setStaffRoles(new HashSet<>());

            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(2).workDate(LocalDate.of(2026, 6, 1)).build()))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of(testStaff, inactiveStaff));

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0)).contains("không hoạt động");
        }

        @Test
        @DisplayName("Ngày ngoài kỳ lịch -> ghi lỗi")
        void dateOutsidePeriod_shouldRecordError() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(1).workDate(LocalDate.of(2026, 7, 1)).build()))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of(testStaff));

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0)).contains("nằm ngoài kỳ lịch");
        }

        @Test
        @DisplayName("Có xung đột -> ghi lỗi, không throw")
        void hasConflict_shouldRecordError() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(BulkL01Request.L01Entry.builder()
                            .staffId(1).workDate(LocalDate.of(2026, 6, 1)).build()))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of(testStaff));
            when(scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(anyInt(), anyInt(), anyString(), any()))
                    .thenReturn(Optional.empty());
            doThrow(new ConflictException("Ngày này là ngày nghỉ bù"))
                    .when(conflictDetectionService).validateAndThrow(anyInt(), any(), anyString(), any(), anyInt());

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0)).contains("nghỉ bù");
        }

        @Test
        @DisplayName("Mix hợp lệ + lỗi -> chỉ tạo phần hợp lệ")
        void mixedValidAndErrors_shouldCreateValidOnly() {
            BulkL01Request request = BulkL01Request.builder()
                    .periodId(1)
                    .entries(List.of(
                            BulkL01Request.L01Entry.builder()
                                    .staffId(1).workDate(LocalDate.of(2026, 6, 1)).build(),
                            BulkL01Request.L01Entry.builder()
                                    .staffId(999).workDate(LocalDate.of(2026, 6, 2)).build(),
                            BulkL01Request.L01Entry.builder()
                                    .staffId(1).workDate(LocalDate.of(2026, 6, 3)).build()
                    ))
                    .build();

            when(periodRepository.findById(1)).thenReturn(Optional.of(draftPeriod));
            when(shiftTypeRepository.findById("L01")).thenReturn(Optional.of(l01ShiftType));
            when(staffRepository.findAllById(anyList())).thenReturn(List.of(testStaff));
            when(scheduleRepository.findByPeriodIdAndStaffIdAndShiftTypeIdAndWorkDate(anyInt(), anyInt(), anyString(), any()))
                    .thenReturn(Optional.empty());
            doNothing().when(conflictDetectionService).validateAndThrow(anyInt(), any(), anyString(), any(), anyInt());
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> {
                        Schedule s = inv.getArgument(0);
                        s.setId(100);
                        return s;
                    });
            when(compensationDayRepository.findByScheduleId(anyInt())).thenReturn(List.of());
            when(compensationDateCalculator.calculate(any())).thenReturn(LocalDate.of(2026, 6, 2));
            when(compensationDayRepository.findByStaffIdAndCompensationDate(anyInt(), any())).thenReturn(Optional.empty());
            when(authContextService.getCurrentStaff()).thenReturn(testStaff);

            BulkL01Response result = scheduleService.createBulkL01(request);

            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getTotalCount()).isEqualTo(3);
            verify(scheduleRepository, times(2)).save(any(Schedule.class));
            // L01 creates compensation days via insertIgnoreCompensationDay (batch insert)
            verify(dbCompat, times(2)).insertCompensationDayIfAbsent(anyInt(), anyInt(), anyInt(), any(), any(), anyString());
        }
    }
}
