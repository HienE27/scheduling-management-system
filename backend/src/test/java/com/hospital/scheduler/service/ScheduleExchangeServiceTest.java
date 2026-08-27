package com.hospital.scheduler.service;

import com.hospital.scheduler.algorithm.CSPScheduler;
import com.hospital.scheduler.algorithm.ScheduleChange;
import com.hospital.scheduler.algorithm.SchedulingResult;
import com.hospital.scheduler.algorithm.ShiftRequirementInfo;
import com.hospital.scheduler.dto.request.ScheduleExchangeDTO;
import com.hospital.scheduler.dto.response.ScheduleExchangeResponse;
import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.exception.BadRequestException;
import com.hospital.scheduler.exception.ResourceNotFoundException;
import com.hospital.scheduler.repository.*;
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
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExchangeService Tests - Đổi trực giữa nhân sự")
class ScheduleExchangeServiceTest {

    @Mock private ScheduleExchangeRepository exchangeRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private CompensationDayRepository compensationDayRepository;
    @Mock private AuditHistoryService auditHistoryService;
    @Mock private ConflictDetectionService conflictDetectionService;
    @Mock private CompensationDateCalculator compensationDateCalculator;
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private ShiftRequirementRepository shiftRequirementRepository;
    @Mock private CSPScheduler cspScheduler;
    @Mock private SchedulingResultLoader schedulingResultLoader;

    @InjectMocks
    private ScheduleExchangeService exchangeService;

    private Staff staffA;
    private Staff staffB;
    private SchedulePeriod testPeriod;
    private Schedule scheduleA;
    private Schedule scheduleB;
    private ScheduleExchange testExchange;

    @BeforeEach
    void setUp() {
        testPeriod = SchedulePeriod.builder()
                .id(1).periodName("Tháng 6/2026")
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .status(SchedulePeriod.PeriodStatus.PUBLISHED)
                .build();

        staffA = Staff.builder()
                .id(1).username("nurseA").fullName("Nguyen Van A").isActive(true).build();
        staffB = Staff.builder()
                .id(2).username("nurseB").fullName("Tran Thi B").isActive(true).build();

        ShiftType shiftL01 = ShiftType.builder()
                .id("L01").name("Lịch trực 24/24").isOvernight(true).build();

        scheduleA = Schedule.builder()
                .id(10).period(testPeriod).staff(staffA).shiftType(shiftL01)
                .workDate(LocalDate.of(2026, 6, 5))
                .build();
        scheduleB = Schedule.builder()
                .id(20).period(testPeriod).staff(staffB).shiftType(shiftL01)
                .workDate(LocalDate.of(2026, 6, 10))
                .build();

        testExchange = ScheduleExchange.builder()
                .id(1).period(testPeriod)
                .requester(staffA).target(staffB)
                .requesterSchedule(scheduleA).targetSchedule(scheduleB)
                .reason("Muốn đổi ngày trực")
                .status(ScheduleExchange.ExchangeStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(exchangeRepository.findByIdWithLock(1)).thenReturn(Optional.of(testExchange));
    }

    // ==================== getAllExchanges ====================
    @Nested
    @DisplayName("getAllExchanges - Lấy tất cả yêu cầu đổi ca")
    class GetAllExchanges {

        @Test
        @DisplayName("Có dữ liệu -> trả về danh sách")
        void hasData_shouldReturnList() {
            when(exchangeRepository.findAll()).thenReturn(List.of(testExchange));

            List<ScheduleExchangeResponse> result = exchangeService.getAllExchanges();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRequester().getFullName()).isEqualTo("Nguyen Van A");
            assertThat(result.get(0).getStatus())
                    .isEqualTo(ScheduleExchangeResponse.ExchangeStatus.PENDING);
        }

        @Test
        @DisplayName("Không có dữ liệu -> trả về danh sách rỗng")
        void noData_shouldReturnEmptyList() {
            when(exchangeRepository.findAll()).thenReturn(Collections.emptyList());

            List<ScheduleExchangeResponse> result = exchangeService.getAllExchanges();

            assertThat(result).isEmpty();
        }
    }

    // ==================== getPendingExchanges ====================
    @Nested
    @DisplayName("getPendingExchanges - Lấy yêu cầu đang chờ")
    class GetPendingExchanges {

        @Test
        @DisplayName("Có PENDING -> trả về danh sách")
        void hasPending_shouldReturnList() {
            when(exchangeRepository.findByStatus(ScheduleExchange.ExchangeStatus.PENDING))
                    .thenReturn(List.of(testExchange));

            List<ScheduleExchangeResponse> result = exchangeService.getPendingExchanges();

            assertThat(result).hasSize(1);
        }
    }

    // ==================== getExchangeById ====================
    @Nested
    @DisplayName("getExchangeById - Lấy theo ID")
    class GetById {

        @Test
        @DisplayName("Tồn tại -> trả về response")
        void exists_shouldReturnResponse() {
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));

            ScheduleExchangeResponse result = exchangeService.getExchangeById(1);

            assertThat(result.getId()).isEqualTo(1);
        }

        @Test
        @DisplayName("Không tồn tại -> throw ResourceNotFoundException")
        void notFound_shouldThrow() {
            when(exchangeRepository.findById(999)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> exchangeService.getExchangeById(999))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==================== createExchange ====================
    @Nested
    @DisplayName("createExchange - Tạo yêu cầu đổi ca")
    class CreateExchange {

        @Test
        @DisplayName("Cùng loại (L01↔L01) -> tạo thành công")
        void sameTypeL01Exchange_shouldCreate() {
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10)
                    .targetScheduleId(20)
                    .reason("Muốn đổi ngày trực")
                    .build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(scheduleB));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> {
                        ScheduleExchange e = inv.getArgument(0);
                        e.setId(5);
                        e.setCreatedAt(LocalDateTime.now());
                        e.setUpdatedAt(LocalDateTime.now());
                        return e;
                    });

            ScheduleExchangeResponse result = exchangeService.createExchange(1, dto);

            assertThat(result.getId()).isEqualTo(5);
            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.PENDING);
            verify(auditHistoryService).logAction(
                    eq("schedule_exchange"), eq(5), eq(AuditHistory.ActionType.INSERT), isNull(), any(), eq(1));
        }

        @Test
        @DisplayName("Lịch yêu cầu không thuộc người gửi -> throw BadRequestException")
        void scheduleNotOwnedByRequester_shouldThrow() {
            // staffB (id=2) owns scheduleB (id=20), but tries to create exchange with scheduleA (id=10, owned by staffA)
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(2)).thenReturn(Optional.of(staffB)); // staffB requests
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA)); // scheduleA belongs to staffA
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(scheduleB));

            assertThatThrownBy(() -> exchangeService.createExchange(2, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("không thuộc về người gửi");
        }

        @Test
        @DisplayName("Đổi trực với chính mình -> throw BadRequestException")
        void swapWithSelf_shouldThrow() {
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(10).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));

            assertThatThrownBy(() -> exchangeService.createExchange(1, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chính mình");
        }

        @Test
        @DisplayName("Kỳ lịch chưa công bố -> throw BadRequestException")
        void periodNotPublished_shouldThrow() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.DRAFT);
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(scheduleB));

            assertThatThrownBy(() -> exchangeService.createExchange(1, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được công bố");
        }

        @Test
        @DisplayName("Cùng loại L02↔L02 -> hợp lệ")
        void sameTypeL02_shouldSucceed() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.PUBLISHED);
            ShiftType shiftL02 = ShiftType.builder()
                    .id("L02").name("Lịch thông tầm").isOvernight(false).build();
            Schedule l02ScheduleA = Schedule.builder()
                    .id(10).period(testPeriod).staff(staffA).shiftType(shiftL02)
                    .workDate(LocalDate.of(2026, 6, 5)).build();
            Schedule l02ScheduleB = Schedule.builder()
                    .id(20).period(testPeriod).staff(staffB).shiftType(shiftL02)
                    .workDate(LocalDate.of(2026, 6, 10)).build();

            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(l02ScheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(l02ScheduleB));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> {
                        ScheduleExchange e = inv.getArgument(0);
                        e.setId(6);
                        e.setCreatedAt(LocalDateTime.now());
                        e.setUpdatedAt(LocalDateTime.now());
                        return e;
                    });

            ScheduleExchangeResponse result = exchangeService.createExchange(1, dto);
            assertThat(result.getId()).isEqualTo(6);
            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.PENDING);
        }

        @Test
        @DisplayName("Cùng loại (L01↔L01) với schedule được build riêng -> tạo thành công")
        void crossTypeL01L01_shouldCreate() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.PUBLISHED);
            ShiftType shiftL01 = ShiftType.builder()
                    .id("L01").name("Lịch trực 24/24").isOvernight(true).build();
            Schedule l01ScheduleA = Schedule.builder()
                    .id(10).period(testPeriod).staff(staffA).shiftType(shiftL01)
                    .workDate(LocalDate.of(2026, 6, 5)).build();
            Schedule l01ScheduleB = Schedule.builder()
                    .id(20).period(testPeriod).staff(staffB).shiftType(shiftL01)
                    .workDate(LocalDate.of(2026, 6, 10)).build();

            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(l01ScheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(l01ScheduleB));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> {
                        ScheduleExchange e = inv.getArgument(0);
                        e.setId(7);
                        e.setCreatedAt(LocalDateTime.now());
                        e.setUpdatedAt(LocalDateTime.now());
                        return e;
                    });

            ScheduleExchangeResponse result = exchangeService.createExchange(1, dto);
            assertThat(result.getId()).isEqualTo(7);
            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.PENDING);
        }

        @Test
        @DisplayName("Cùng loại (L01↔L01) dùng schedule có sẵn -> tạo thành công")
        void crossTypeL02L01_shouldCreate() {
            // Requester schedule dùng L01 (giống scheduleA mặc định), target là scheduleB cũng L01
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(scheduleB));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> {
                        ScheduleExchange e = inv.getArgument(0);
                        e.setId(8);
                        e.setCreatedAt(LocalDateTime.now());
                        e.setUpdatedAt(LocalDateTime.now());
                        return e;
                    });

            ScheduleExchangeResponse result = exchangeService.createExchange(1, dto);
            assertThat(result.getId()).isEqualTo(8);
            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.PENDING);
        }

        // -------------------------------------------------------------------------
        // M3: Target staff inactive → rejection
        // -------------------------------------------------------------------------
        @Test
        @DisplayName("M3: Nhân sự được đổi đang ngừng hoạt động -> throw BadRequestException")
        void targetStaffInactive_shouldThrow() {
            Staff inactiveStaff = Staff.builder()
                    .id(2).username("nurseB").fullName("Tran Thi B").isActive(false).build();
            Schedule inactiveSchedule = Schedule.builder()
                    .id(20).period(testPeriod).staff(inactiveStaff).shiftType(scheduleB.getShiftType())
                    .workDate(LocalDate.of(2026, 6, 10)).build();

            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(inactiveSchedule));

            assertThatThrownBy(() -> exchangeService.createExchange(1, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("ngừng hoạt động");
        }

        // -------------------------------------------------------------------------
        // M4: Archived period → rejection
        // -------------------------------------------------------------------------
        @Test
        @DisplayName("M4: Kỳ lịch đang lưu trữ (ARCHIVED) -> throw BadRequestException")
        void archivedPeriod_shouldThrow() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.ARCHIVED);
            ScheduleExchangeDTO dto = ScheduleExchangeDTO.builder()
                    .requesterScheduleId(10).targetScheduleId(20).build();
            when(staffRepository.findById(1)).thenReturn(Optional.of(staffA));
            when(scheduleRepository.findById(10)).thenReturn(Optional.of(scheduleA));
            when(scheduleRepository.findById(20)).thenReturn(Optional.of(scheduleB));

            assertThatThrownBy(() -> exchangeService.createExchange(1, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được công bố");
        }
    }

    // ==================== approveExchange ====================
    @Nested
    @DisplayName("approveExchange - Duyệt đổi ca")
    class ApproveExchange {

        @Test
        @DisplayName("PENDING, không conflict -> APPROVED, staff đổi chỗ nhau")
        void validApproval_shouldApproveAndSwapStaff() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            ScheduleExchangeResponse result = exchangeService.approveExchange(1, 3, "Đồng ý đổi");

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.APPROVED);

            // Verify staff swapped
            ArgumentCaptor<Schedule> captor = ArgumentCaptor.forClass(Schedule.class);
            verify(scheduleRepository, times(2)).save(captor.capture());
            List<Schedule> savedSchedules = captor.getAllValues();
            assertThat(savedSchedules).extracting(s -> s.getStaff().getId())
                    .containsExactlyInAnyOrder(2, 1); // staffA got scheduleB's date, staffB got scheduleA's date
        }

        @Test
        @DisplayName("Không phải PENDING -> throw BadRequestException")
        void notPending_shouldThrow() {
            testExchange.setStatus(ScheduleExchange.ExchangeStatus.APPROVED);
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("đang chờ");
        }

        @Test
        @DisplayName("Target có nghỉ phép đã duyệt vào ngày trực -> throw BadRequestException")
        void targetHasApprovedLeave_shouldThrow() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            LeaveRequest approvedLeave = LeaveRequest.builder()
                    .id(1).staff(staffB)
                    .startDate(LocalDate.of(2026, 6, 10))
                    .endDate(LocalDate.of(2026, 6, 10))
                    .status(LeaveRequest.LeaveStatus.APPROVED)
                    .build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(List.of(approvedLeave));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("nghỉ phép được duyệt");
        }

        @Test
        @DisplayName("Requester có ngày nghỉ bù vào ngày trực của target -> throw BadRequestException")
        void requesterHasCompensationDay_shouldThrow() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            CompensationDay compDay = CompensationDay.builder()
                    .id(1).staff(staffA).compensationDate(LocalDate.of(2026, 6, 10))
                    .build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.of(compDay));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("ngày nghỉ bù");
        }

        @Test
        @DisplayName("Đổi xong requester bị conflict -> throw BadRequestException")
        void requesterHasConflictAfterSwap_shouldThrow() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(List.of("Trùng với lịch thông tầm")); // requester has conflict after swap

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("bị xung đột sau khi đổi");
        }

        @Test
        @DisplayName("Kỳ lịch quay về DRAFT -> throw BadRequestException")
        void periodBackToDraft_shouldThrow() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.DRAFT);
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được công bố");
        }

        // -------------------------------------------------------------------------
        // M4: Archived period in approveExchange
        // -------------------------------------------------------------------------
        @Test
        @DisplayName("M4: Kỳ lịch đang lưu trữ (ARCHIVED) trong duyệt -> throw BadRequestException")
        void archivedPeriod_shouldThrow() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.ARCHIVED);
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được công bố");
        }

        // -------------------------------------------------------------------------
        // Change 3: Post-swap CSP incremental re-solve
        // -------------------------------------------------------------------------
        @Test
        @DisplayName("Post-swap re-solve valid -> approve succeeds, reSolve() called once với 2 modified deltas")
        void postSwapReSolveValid_shouldApprove() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            SchedulingResult previous = SchedulingResult.builder()
                    .assignments(java.util.Map.of(
                            "1_2026-06-05", "L01",
                            "2_2026-06-10", "L01"))
                    .valid(true).build();
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(previous);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            SchedulingResult reSolveResult = SchedulingResult.builder()
                    .assignments(java.util.Map.of(
                            "2_2026-06-05", "L01",
                            "1_2026-06-10", "L01"))
                    .valid(true).build();
            when(cspScheduler.reSolve(any(), any(), any(), any(), any())).thenReturn(reSolveResult);

            ScheduleExchangeResponse result = exchangeService.approveExchange(1, 3, "Đồng ý đổi");

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.APPROVED);
            ArgumentCaptor<ScheduleChange> captor = ArgumentCaptor.forClass(ScheduleChange.class);
            verify(cspScheduler, times(1)).reSolve(eq(previous), captor.capture(), any(), any(), any());
            ScheduleChange captured = captor.getValue();
            assertThat(captured.getModified()).hasSize(2);
            assertThat(captured.getModified())
                    .extracting(d -> d.getStaffId() + "_" + d.getDate() + "_" + d.getShiftType())
                    .containsExactlyInAnyOrder(
                            "1_2026-06-05_L01",
                            "2_2026-06-10_L01");
            assertThat(captured.getModified())
                    .extracting(ScheduleChange.AssignmentDelta::getOldStaffId)
                    .containsExactlyInAnyOrder(2, 1);
        }

        @Test
        @DisplayName("Post-swap re-solve invalid -> throw BadRequestException với 'không còn feasible'")
        void postSwapReSolveInvalid_shouldThrow() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            SchedulingResult previous = SchedulingResult.builder()
                    .assignments(java.util.Map.of("1_2026-06-05", "L01"))
                    .valid(true).build();
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(previous);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            SchedulingResult invalid = SchedulingResult.builder()
                    .assignments(java.util.Map.of())
                    .valid(false)
                    .errors(List.of("staff_2_quota_exceeded")).build();
            when(cspScheduler.reSolve(any(), any(), any(), any(), any())).thenReturn(invalid);

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("không còn feasible")
                    .hasMessageContaining("staff_2_quota_exceeded");
        }

        @Test
        @DisplayName("Post-swap re-solve throws exception -> approve BỊ BLOCK (BUGFIX #6 rollback contract)")
        void postSwapReSolveThrows_shouldNotBlockApprove() {
            // Updated for BUGFIX #6: the production code (ScheduleExchangeService
            // line 504-516) catches re-solve exceptions and re-throws as
            // BadRequestException so the surrounding @Transactional rolls back
            // the swap. The pre-fix behavior of "log warn and continue" was a
            // silent inconsistency (swap committed even when period infeasible).
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            SchedulingResult previous = SchedulingResult.builder()
                    .assignments(java.util.Map.of("1_2026-06-05", "L01"))
                    .valid(true).build();
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(previous);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("CSP internal boom"));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, "Đồng ý đổi"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("CSP internal boom");
            // BUGFIX #6: the swap must be rejected (transaction rolled back),
            // not silently committed with a warn log.
        }

        @Test
        @DisplayName("Period trống (no staff/requirements/leaves) -> re-solve vẫn chạy best-effort, approve succeeds")
        void emptyPeriod_shouldStillApprove() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(Collections.emptyList());
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            ScheduleExchangeResponse result = exchangeService.approveExchange(1, 3, "Đồng ý đổi");

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.APPROVED);
            verify(cspScheduler, times(1)).reSolve(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reSolve() nhận ScheduleChange với đúng 2 MODIFY deltas (staff↔oldStaffId, dates khớp schedules)")
        void reSolve_calledWithTwoCorrectDeltas() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            exchangeService.approveExchange(1, 3, "Đồng ý đổi");

            ArgumentCaptor<ScheduleChange> changeCaptor = ArgumentCaptor.forClass(ScheduleChange.class);
            verify(cspScheduler).reSolve(any(), changeCaptor.capture(), any(), any(), any());
            ScheduleChange change = changeCaptor.getValue();
            assertThat(change.getModified()).hasSize(2);

            ScheduleChange.AssignmentDelta requesterSide = change.getModified().get(0);
            assertThat(requesterSide.getStaffId()).isEqualTo(1);
            assertThat(requesterSide.getOldStaffId()).isEqualTo(2);
            assertThat(requesterSide.getDate()).isEqualTo(LocalDate.of(2026, 6, 5));
            assertThat(requesterSide.getShiftType()).isEqualTo("L01");

            ScheduleChange.AssignmentDelta targetSide = change.getModified().get(1);
            assertThat(targetSide.getStaffId()).isEqualTo(2);
            assertThat(targetSide.getOldStaffId()).isEqualTo(1);
            assertThat(targetSide.getDate()).isEqualTo(LocalDate.of(2026, 6, 10));
            assertThat(targetSide.getShiftType()).isEqualTo("L01");

            assertThat(change.getRemoved()).isEmpty();
            assertThat(change.getAdded()).isEmpty();
            assertThat(change.getAddedLeaves()).isEmpty();
            assertThat(change.getRemovedLeaves()).isEmpty();
            assertThat(change.getAddedStaffIds()).isEmpty();
            assertThat(change.getRemovedStaffIds()).isEmpty();
        }

        @Test
        @DisplayName("Period DRAFT -> throw BadRequestException, reSolve() KHÔNG được gọi")
        void draftPeriod_shouldNotInvokeReSolve() {
            testPeriod.setStatus(SchedulePeriod.PeriodStatus.DRAFT);
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được công bố");

            verify(cspScheduler, never()).reSolve(any(), any(), any(), any(), any());
            verify(schedulingResultLoader, never()).loadPreviousFromDb(anyInt(), any());
            verify(scheduleRepository, never()).save(any(Schedule.class));
        }

        @Test
        @DisplayName("Approve happy path -> reSolve() được gọi đúng 1 lần")
        void happyPath_reSolveCalledExactlyOnce() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(2), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findByStaffIdAndDateRange(eq(1), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(2), any()))
                    .thenReturn(Optional.empty());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(eq(1), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(eq(1), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(conflictDetectionService.detectAllConflicts(eq(2), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            ScheduleExchangeResponse result = exchangeService.approveExchange(1, 3, "Đồng ý đổi");

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.APPROVED);
            verify(cspScheduler, times(1)).reSolve(any(), any(), any(), any(), any());
            verify(schedulingResultLoader, times(1)).loadPreviousFromDb(eq(1), any());
        }

        // ----- 3 test bổ sung: re-solve edge cases -----

        @Test
        @DisplayName("reSolve=null -> throw BadRequest, schedule KHÔNG swap (rollback)")
        void reSolveNull_throwsAndDoesNotSwap() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(anyInt(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(anyInt(), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(anyInt(), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            // Schedule swap happens BEFORE re-solve, so we must mock save() to
            // return a non-null Schedule — otherwise copyCompensationFkAndDelete
            // NPEs on saved.getId() before we ever reach the re-solve path.
            when(scheduleRepository.save(any(Schedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any())).thenReturn(null);

            assertThatThrownBy(() -> exchangeService.approveExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("no result");
            // reSolve vẫn được gọi đúng 1 lần (re-solve trigger fired),
            // throw diễn ra bên trong làm BadRequest lan lên caller
            verify(cspScheduler, times(1)).reSolve(any(), any(), any(), any(), any());
            verify(schedulingResultLoader, times(1)).loadPreviousFromDb(eq(1), any());
        }

        @Test
        @DisplayName("reSolve ScheduleChange chứa staffId CŨ (1 & 2), không phải id sau swap")
        void reSolve_receivesOriginalStaffIdsNotSwapped() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(anyInt(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(anyInt(), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(anyInt(), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(null);
            when(shiftRequirementRepository.findByPeriodId(1)).thenReturn(Collections.emptyList());
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(Collections.emptyList());
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB));
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            exchangeService.approveExchange(1, 3, "ok");

            ArgumentCaptor<ScheduleChange> cap = ArgumentCaptor.forClass(ScheduleChange.class);
            verify(cspScheduler).reSolve(any(), cap.capture(), any(), any(), any());
            List<ScheduleChange.AssignmentDelta> deltas = cap.getValue().getModified();
            assertThat(deltas).hasSize(2);
            ScheduleChange.AssignmentDelta reqSide = deltas.stream()
                    .filter(d -> d.getDate().equals(LocalDate.of(2026, 6, 5))).findFirst().orElseThrow();
            assertThat(reqSide.getStaffId()).isEqualTo(staffA.getId());
            assertThat(reqSide.getOldStaffId()).isEqualTo(staffB.getId());
            ScheduleChange.AssignmentDelta tgtSide = deltas.stream()
                    .filter(d -> d.getDate().equals(LocalDate.of(2026, 6, 10))).findFirst().orElseThrow();
            assertThat(tgtSide.getStaffId()).isEqualTo(staffB.getId());
            assertThat(tgtSide.getOldStaffId()).isEqualTo(staffA.getId());
        }

        @Test
        @DisplayName("reSolve nhận đủ 5 đối số: previous, change, staff, requirements, leaves đúng loại")
        void reSolve_receivesAllFiveArgumentsWithCorrectTypes() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            Staff staffC = Staff.builder().id(99).username("nurseC").fullName("Le Van C").isActive(true).build();
            SchedulingResult prev = SchedulingResult.builder().valid(true).assignments(new java.util.HashMap<>()).build();
            ShiftRequirement req = ShiftRequirement.builder().id(99).shiftType(shiftL01())
                    .period(testPeriod).workDate(LocalDate.of(2026, 6, 5))
                    .requiredStaffCount(1).build();
            LeaveRequest leave = LeaveRequest.builder().id(7).staff(staffC)
                    .startDate(LocalDate.of(2026, 6, 5)).endDate(LocalDate.of(2026, 6, 5))
                    .status(LeaveRequest.LeaveStatus.APPROVED).build();

            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(leaveRequestRepository.findByStaffIdAndDateRange(anyInt(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.findByStaffIdAndCompensationDate(anyInt(), any()))
                    .thenReturn(Optional.empty());
            when(conflictDetectionService.detectAllConflicts(anyInt(), any(), anyString(), any()))
                    .thenReturn(Collections.emptyList());
            when(compensationDayRepository.save(any(CompensationDay.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(schedulingResultLoader.loadPreviousFromDb(eq(1), any())).thenReturn(prev);
            when(shiftRequirementRepository.findByPeriodId(anyInt())).thenReturn(List.of(req));
            when(leaveRequestRepository.findApprovedInRange(any(), any())).thenReturn(List.of(leave));
            when(staffRepository.findByIsActiveTrue()).thenReturn(List.of(staffA, staffB, staffC));
            when(scheduleRepository.findByStaffIdAndWorkDate(anyInt(), any())).thenReturn(Collections.emptyList());
            doNothing().when(emailService).sendSwapApprovedEmail(any(), anyString(), anyString());
            when(cspScheduler.reSolve(any(), any(), any(), any(), any()))
                    .thenReturn(SchedulingResult.builder().assignments(new java.util.HashMap<>()).valid(true).build());

            exchangeService.approveExchange(1, 3, "ok");

            ArgumentCaptor<SchedulingResult> prevCap = ArgumentCaptor.forClass(SchedulingResult.class);
            ArgumentCaptor<ScheduleChange> changeCap = ArgumentCaptor.forClass(ScheduleChange.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Staff>> staffCap = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ShiftRequirementInfo>> reqCap = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LeaveRequest>> leaveCap = ArgumentCaptor.forClass(List.class);

            verify(cspScheduler).reSolve(prevCap.capture(), changeCap.capture(), staffCap.capture(),
                    reqCap.capture(), leaveCap.capture());

            assertThat(prevCap.getValue()).isSameAs(prev);
            assertThat(changeCap.getValue().getModified()).hasSize(2);
            assertThat(staffCap.getValue()).containsExactlyInAnyOrder(staffA, staffB, staffC);
            assertThat(reqCap.getValue()).hasSize(1);
            assertThat(reqCap.getValue().get(0).shiftTypeId()).isEqualTo("L01");
            assertThat(leaveCap.getValue()).hasSize(1);
            assertThat(leaveCap.getValue().get(0).getStatus()).isEqualTo(LeaveRequest.LeaveStatus.APPROVED);
        }

        private ShiftType shiftL01() {
            return ShiftType.builder().id("L01").name("Lịch trực 24/24").isOvernight(true).build();
        }
    }

    // ==================== rejectExchange ====================
    @Nested
    @DisplayName("rejectExchange - Từ chối đổi ca")
    class RejectExchange {

        @Test
        @DisplayName("PENDING -> REJECTED")
        void pending_shouldReject() {
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ScheduleExchangeResponse result = exchangeService.rejectExchange(1, 3, "Từ chối vì lý do nhân sự");

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.REJECTED);
            assertThat(result.getReviewNote()).isEqualTo("Từ chối vì lý do nhân sự");
        }

        @Test
        @DisplayName("Đã duyệt rồi -> throw BadRequestException")
        void alreadyApproved_shouldThrow() {
            testExchange.setStatus(ScheduleExchange.ExchangeStatus.APPROVED);
            Staff reviewer = Staff.builder().id(3).username("manager").fullName("Manager").build();
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(staffRepository.findById(3)).thenReturn(Optional.of(reviewer));

            assertThatThrownBy(() -> exchangeService.rejectExchange(1, 3, null))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ==================== cancelExchange ====================
    @Nested
    @DisplayName("cancelExchange - Hủy yêu cầu đổi ca")
    class CancelExchange {

        @Test
        @DisplayName("Người yêu cầu hủy -> CANCELLED")
        void requesterCancels_shouldCancel() {
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ScheduleExchangeResponse result = exchangeService.cancelExchange(1, staffA);

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.CANCELLED);
        }

        @Test
        @DisplayName("Người được đổi hủy -> CANCELLED")
        void targetCancels_shouldCancel() {
            when(exchangeRepository.findById(1)).thenReturn(Optional.of(testExchange));
            when(exchangeRepository.save(any(ScheduleExchange.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ScheduleExchangeResponse result = exchangeService.cancelExchange(1, staffB);

            assertThat(result.getStatus()).isEqualTo(ScheduleExchangeResponse.ExchangeStatus.CANCELLED);
        }
    }
}
