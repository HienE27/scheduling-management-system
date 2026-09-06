package com.hospital.scheduler.command;

import com.hospital.scheduler.entity.*;
import com.hospital.scheduler.repository.*;
import com.hospital.scheduler.security.Permissions;
import com.hospital.scheduler.util.CompensationDateCalculator;
import com.hospital.scheduler.algorithm.AutoGenConfig;
import com.hospital.scheduler.service.AlgorithmConfigService;
import com.hospital.scheduler.service.HospitalSpecialtyRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Transactional
@Profile("!test & !local & !postgres")
public class DataSeeder implements CommandLineRunner {

    private final AppRoleRepository appRoleRepository;
    private final AppPermissionRepository appPermissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final StaffRepository staffRepository;
    private final SpecialtyRepository specialtyRepository;
    private final ShiftTypeRepository shiftTypeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SchedulePeriodRepository periodRepository;
    private final ScheduleRepository scheduleRepository;
    private final CompensationDayRepository compensationDayRepository;
    private final ScheduleTemplateRepository scheduleTemplateRepository;
    private final com.hospital.scheduler.repository.LeaveRequestRepository leaveRequestRepository;
    private final com.hospital.scheduler.repository.ScheduleExchangeRepository scheduleExchangeRepository;
    private final com.hospital.scheduler.repository.NotificationRepository notificationRepository;
    private final com.hospital.scheduler.repository.AuditHistoryRepository auditHistoryRepository;
    private final ShiftRequirementRepository shiftRequirementRepository;
    private final CompensationDateCalculator compensationDateCalculator;
    private final HolidayRepository holidayRepository;
    private final AlgorithmConfigRepository algorithmConfigRepository;
    private final AlgorithmConfigService algorithmConfigService;
    private final HospitalSpecialtyRegistry hospitalSpecialtyRegistry;
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    // ⚠️  Muốn re-seed (thêm staff mới) → drop database + restart backend
    @Override
    public void run(String... args) {
        seedRoles();
        seedPermissions();
        seedRolePermissions();
        seedSpecialties();
        seedShiftTypes();
        seedHolidays();
        seedAlgorithmConfig();
        seedScheduleTemplates();
        seedAdminUser();
        seedPeriodsAndSchedules();
        seedLeaveRequests();
        seedSwapRequests();
        seedNotifications();
        seedAuditHistory();
    }

    private void seedRoles() {
        if (appRoleRepository.count() > 0) return;

        appRoleRepository.save(AppRole.builder().name(RoleName.ADMIN).description("Quản trị hệ thống").isActive(true).build());
        appRoleRepository.save(AppRole.builder().name(RoleName.MANAGER).description("Quản lý và duyệt lịch").isActive(true).build());
        appRoleRepository.save(AppRole.builder().name(RoleName.STAFF).description("Nhân viên sử dụng hệ thống").isActive(true).build());

        log.info("✅ Seeded roles: ADMIN, MANAGER, STAFF");
    }

    /**
     * M01-F05 "Phân quyền hệ thống": seed toàn bộ permission catalog from the
     * central {@link Permissions} constants so backend and frontend stay in
     * sync.
     *
     * <p>Idempotent: skips insertion when the permission already exists by
     * name — so re-running the seeder after a schema upgrade will add any
     * newly-introduced permissions without touching existing rows.
     *
     * <p>Cũng dọn các permission mồ côi (tồn tại trong DB nhưng không còn
     * trong catalog) cùng với các liên kết role_permission trỏ tới chúng.
     */
    private void seedPermissions() {
        Map<String, String> catalog = Permissions.catalog();

        // Cleanup orphan permissions
        for (AppPermission old : appPermissionRepository.findAll()) {
            if (!catalog.containsKey(old.getName())) {
                rolePermissionRepository.deleteByPermissionId(old.getId());
                appPermissionRepository.delete(old);
                log.info("Removed orphan permission: {}", old.getName());
            }
        }

        int created = 0;
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            String name = entry.getKey();
            if (appPermissionRepository.existsByName(name)) {
                continue;
            }
            appPermissionRepository.save(AppPermission.builder()
                    .name(name)
                    .description(entry.getValue())
                    .isActive(true)
                    .build());
            created++;
        }

        log.info("Seeded {} new permissions (catalog size: {})", created, catalog.size());
    }

    /**
     * M01-F05 "Phân quyền hệ thống": gán permission cho từng role theo ma trận
     * defined in {@link Permissions}.
     *
     * <ul>
     *   <li>ADMIN: tất cả permission trong {@link Permissions#allPermissions()}</li>
     *   <li>MANAGER: {@link Permissions#managerPermissions()} (xem + phê duyệt + xếp lịch M02–M05, M07)</li>
     *   <li>STAFF: {@link Permissions#staffPermissions()} (xem lịch cá nhân + tự đăng ký nghỉ/đổi ca)</li>
     * </ul>
     *
     * <p>Idempotent: xóa các liên kết role_permission cũ trước khi seed lại
     * để khi thêm permission mới vào catalog, role được gán đầy đủ.
     */
    private void seedRolePermissions() {
        AppRole adminRole = appRoleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role not seeded yet"));
        AppRole managerRole = appRoleRepository.findByName(RoleName.MANAGER)
                .orElseThrow(() -> new IllegalStateException("MANAGER role not seeded yet"));
        AppRole staffRole = appRoleRepository.findByName(RoleName.STAFF)
                .orElseThrow(() -> new IllegalStateException("STAFF role not seeded yet"));

        // Cache permission id theo tên
        Map<String, Integer> permIds = new HashMap<>();
        for (AppPermission p : appPermissionRepository.findAll()) {
            permIds.put(p.getName(), p.getId());
        }

        Set<String> adminPerms = Permissions.allPermissions();
        Set<String> managerPerms = Permissions.managerPermissions();
        Set<String> staffPerms = Permissions.staffPermissions();

        // Reset role_permission để catalog mới được apply nguyên vẹn.
        rolePermissionRepository.deleteByRoleId(adminRole.getId());
        rolePermissionRepository.deleteByRoleId(managerRole.getId());
        rolePermissionRepository.deleteByRoleId(staffRole.getId());

        int adminCount = 0, managerCount = 0, staffCount = 0;
        for (Map.Entry<String, Integer> entry : permIds.entrySet()) {
            String permName = entry.getKey();
            Integer permId = entry.getValue();

            if (adminPerms.contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(adminRole.getId()).permissionId(permId).build());
                adminCount++;
            }
            if (managerPerms.contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(managerRole.getId()).permissionId(permId).build());
                managerCount++;
            }
            if (staffPerms.contains(permName)) {
                rolePermissionRepository.save(RolePermission.builder()
                        .roleId(staffRole.getId()).permissionId(permId).build());
                staffCount++;
            }
        }

        log.info("Seeded role-permission matrix: ADMIN={}, MANAGER={}, STAFF={}",
                adminCount, managerCount, staffCount);
    }

    private void seedSpecialties() {
        if (specialtyRepository.count() > 0) return;

        specialtyRepository.save(Specialty.builder().name("Ngoại").description("Khoa Ngoại tổng hợp").isActive(true).build());
        specialtyRepository.save(Specialty.builder().name("Nội").description("Khoa Nội tổng hợp").isActive(true).build());
        specialtyRepository.save(Specialty.builder().name("Sản").description("Khoa Sản phụ khoa").isActive(true).build());
        specialtyRepository.save(Specialty.builder().name("Nhi").description("Khoa Nhi").isActive(true).build());
        specialtyRepository.save(Specialty.builder().name("Mắt").description("Khoa Mắt").isActive(true).build());
        specialtyRepository.save(Specialty.builder().name("Răng").description("Khoa Răng hàm mặt").isActive(true).build());

        // BUGFIX: Evict HospitalSpecialtyRegistry cache so StaffShiftTypeEligibility
        // picks up the freshly-seeded specialties instead of the empty @PostConstruct set.
        hospitalSpecialtyRegistry.evictCache();

        log.info("✅ Seeded specialties: Ngoại, Nội, Sản, Nhi, Mắt, Răng");
    }

    private void seedShiftTypes() {
        if (shiftTypeRepository.count() > 0) return;

        shiftTypeRepository.save(ShiftType.builder()
                .id("L01").name("Lịch trực 24/24")
                .description("Ca trực 24/24 từ 7h30 ngày N đến 7h30 ngày N+1, có nghỉ bù")
                .isOvernight(true).fatigueScore(3).isActive(true).build());

        shiftTypeRepository.save(ShiftType.builder()
                .id("L02").name("Lịch thông tầm")
                .description("Ca ngày, không nghỉ trưa")
                .isOvernight(false).fatigueScore(1).isActive(true).build());

        shiftTypeRepository.save(ShiftType.builder()
                .id("L03").name("Lịch phòng khám dịch vụ")
                .description("Ca khám dịch vụ")
                .isOvernight(false).fatigueScore(1).isActive(true).build());

        shiftTypeRepository.save(ShiftType.builder()
                .id("L04").name("Lịch phòng khám chuyên gia")
                .description("Ca khám chuyên sâu")
                .isOvernight(false).fatigueScore(2).isActive(true).build());

        log.info("✅ Seeded shift types: L01, L02, L03, L04");
    }

    private void seedHolidays() {
        if (holidayRepository.count() > 0) return;

        record HolidaySeed(String name, int month, int day, String description) {}
        HolidaySeed[] holidays = new HolidaySeed[]{
            new HolidaySeed("Tết Dương lịch",       1,  1,  "Năm mới Dương lịch"),
            new HolidaySeed("Tết Nguyên đán",        2,  14, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Tết Nguyên đán",        2,  15, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Tết Nguyên đán",        2,  16, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Giỗ Tổ Hùng Vương",    4,  27, "Giỗ Tổ Hùng Vương"),
            new HolidaySeed("Ngày Giải phóng miền Nam", 4, 30, "Ngày Giải phóng miền Nam 30/4"),
            new HolidaySeed("Quốc tế Lao động",       5,  1,  "Ngày Quốc tế Lao động"),
            new HolidaySeed("Ngày Quốc khánh",         9,  2,  "Ngày Quốc khánh Việt Nam"),
        };

        for (HolidaySeed h : holidays) {
            LocalDate date = LocalDate.of(2026, h.month, h.day);
            if (!holidayRepository.findByHolidayDate(date).isPresent()) {
                holidayRepository.save(Holiday.builder()
                        .name(h.name).holidayDate(date).year(date.getYear()).description(h.description).isActive(true).build());
            }
        }
        log.info("✅ Seeded " + holidays.length + " holidays for 2026");
    }

    private void seedAlgorithmConfig() {
        // Check trực tiếp table count thay vì getAutoGenConfig() (luôn return present)
        if (algorithmConfigRepository.count() > 0) {
            log.info("Algorithm config already exists, skipping seed");
            return;
        }

        // Seed auto-gen config with defaults
        AutoGenConfig defaults = new AutoGenConfig(
                true,   // enabled = true (auto-gen sẵn sàng ngay khi seed)
                // min per day
                1,      // l01MinPerDay
                1,      // l02MinPerDay
                1,      // l03MinPerDay
                1,      // l04MinPerDay
                // max per day (0 = unlimited)
                0,      // l01MaxPerDay
                0,      // l02MaxPerDay
                0,      // l03MaxPerDay
                0,      // l04MaxPerDay
                // max per week (0 = unlimited) — fairness cap per staff per week
                0,      // l01MaxPerWeek
                0,      // l02MaxPerWeek
                0,      // l03MaxPerWeek
                0,      // l04MaxPerWeek
                "SKIP",  // holidayMode
                List.of()  // removedShiftTypes (none by default)
        );
        algorithmConfigService.saveAutoGenConfig(defaults);

        log.info("✅ Seeded algorithm auto-gen config with defaults");
    }

    private void seedScheduleTemplates() {
        if (scheduleTemplateRepository.count() > 0) return;
        Specialty ngoai = specialtyRepository.findByName("Ngoại").orElse(null);
        Specialty noi = specialtyRepository.findByName("Nội").orElse(null);

        record TemplateSeed(String name, String desc, int dayOfWeek, String shiftTypeId, Specialty specialty, int count) {}
        TemplateSeed[] templates = new TemplateSeed[]{
                new TemplateSeed("Trực 24/24 thứ 2", "Lịch trực 24/24 vào thứ 2", 1, "L01", ngoai, 1),
                new TemplateSeed("Trực 24/24 thứ 3", "Lịch trực 24/24 vào thứ 3", 2, "L01", ngoai, 1),
                new TemplateSeed("Trực 24/24 thứ 4", "Lịch trực 24/24 vào thứ 4", 3, "L01", ngoai, 1),
                new TemplateSeed("Trực 24/24 thứ 5", "Lịch trực 24/24 vào thứ 5", 4, "L01", ngoai, 1),
                new TemplateSeed("Trực 24/24 thứ 6", "Lịch trực 24/24 vào thứ 6", 5, "L01", ngoai, 1),
                new TemplateSeed("Thông tầm thứ 2–6", "Ca thông tầm các ngày trong tuần", 1, "L02", ngoai, 2),
                new TemplateSeed("PK dịch vụ thứ 2–6", "Phòng khám dịch vụ các ngày trong tuần", 1, "L03", noi, 1),
                new TemplateSeed("PK chuyên gia thứ 7", "Phòng khám chuyên gia vào thứ 7", 6, "L04", ngoai, 1),
        };

        for (TemplateSeed t : templates) {
            scheduleTemplateRepository.save(ScheduleTemplate.builder()
                    .name(t.name).description(t.desc)
                    .dayOfWeek(t.dayOfWeek).shiftTypeId(t.shiftTypeId)
                    .specialty(t.specialty).requiredStaffCount(t.count)
                    .isActive(true).build());
        }
        log.info("✅ Seeded " + templates.length + " schedule templates");
    }

    private void seedAdminUser() {
        if (staffRepository.count() > 0) return;

        AppRole adminRole = appRoleRepository.findByName(RoleName.ADMIN).orElse(null);
        AppRole managerRole = appRoleRepository.findByName(RoleName.MANAGER).orElse(null);
        AppRole staffRole = appRoleRepository.findByName(RoleName.STAFF).orElse(null);
        Specialty ngoai = specialtyRepository.findByName("Ngoại").orElse(null);
        Specialty noi = specialtyRepository.findByName("Nội").orElse(null);
        Specialty san = specialtyRepository.findByName("Sản").orElse(null);
        Specialty nhi = specialtyRepository.findByName("Nhi").orElse(null);
        Specialty mat = specialtyRepository.findByName("Mắt").orElse(null);
        Specialty rang = specialtyRepository.findByName("Răng").orElse(null);

        // ── ADMIN (ADMIN + MANAGER) ─────────────────────────────────────────
        Staff admin = staffRepository.save(Staff.builder()
                .username("admin").passwordHash(passwordEncoder.encode("admin123"))
                .fullName("Nguyễn Văn An").phone("0901000001")
                .email("admin@hospital.com").specialty(ngoai)
                .maxShiftsPerMonth(5).isActive(true).staffRoles(new HashSet<>()).build());
        addRoles(admin, adminRole, managerRole);

        // ── MANAGER (2 người) ──────────────────────────────────────────────
        Staff mgr1 = staffRepository.save(Staff.builder()
                .username("manager1").passwordHash(passwordEncoder.encode("123456"))
                .fullName("Trần Thị Bình").phone("0901000002")
                .email("manager1@hospital.com").specialty(ngoai)
                .maxShiftsPerMonth(4).isActive(true).staffRoles(new HashSet<>()).build());
        addRoles(mgr1, managerRole);

        Staff mgr2 = staffRepository.save(Staff.builder()
                .username("manager2").passwordHash(passwordEncoder.encode("123456"))
                .fullName("Lê Hoàng Cường").phone("0901000003")
                .email("manager2@hospital.com").specialty(noi)
                .maxShiftsPerMonth(4).isActive(true).staffRoles(new HashSet<>()).build());
        addRoles(mgr2, managerRole);

        // ── STAFF (17 người) ────────────────────────────────────────────────
        record StaffSeed(String username, String password, String fullName, String phone,
                        String email, Specialty specialty, int maxShifts) {}

        StaffSeed[] seeds = new StaffSeed[]{
                new StaffSeed("nvminh",    "123456", "Nguyễn Văn Minh",    "0901000004", "nvminh@hospital.com",    ngoai,  5),
                new StaffSeed("tthuhien",  "123456", "Trần Thu Hiền",     "0901000005", "tthuhien@hospital.com",  noi,   5),
                new StaffSeed("lbthanhtam","123456", "Lê Bùi Thanh Tâm",   "0901000006", "lbthanhtam@hospital.com",ngoai,  6),
                new StaffSeed("hpdat",     "123456", "Hoàng Phú Đạt",      "0901000007", "hpdat@hospital.com",     nhi,   5),
                new StaffSeed("ntphuong",  "123456", "Ngô Thị Phượng",     "0901000008", "ntphuong@hospital.com",  san,   4),
                new StaffSeed("cmtuan",    "123456", "Chu Minh Tuấn",      "0901000009", "cmtuan@hospital.com",    ngoai, 5),
                new StaffSeed("dvanh",     "123456", "Đỗ Văn Anh",         "0901000010", "dvanh@hospital.com",     noi,   5),
                new StaffSeed("nthuylinh", "123456", "Nguyễn Thị Huyền Linh","0901000011","nthuylinh@hospital.com", ngoai, 6),
                new StaffSeed("vtquan",    "123456", "Vũ Trọng Quân",      "0901000012", "vtquan@hospital.com",    nhi,   5),
                new StaffSeed("btdthu",    "123456", "Bùi Thị Diễm Thu",   "0901000013", "btdthu@hospital.com",    mat,   4),
                new StaffSeed("nhduy",     "123456", "Nguyễn Hữu Duy",     "0901000014", "nhduy@hospital.com",     ngoai, 5),
                new StaffSeed("lthanhha",  "123456", "Lý Thị Thanh Hà",    "0901000015", "lthanhha@hospital.com",  noi,   5),
                new StaffSeed("dtqhieu",   "123456", "Đinh Trần Quang Hiếu","0901000016","dtqhieu@hospital.com",   ngoai, 6),
                new StaffSeed("pthanh",    "123456", "Phạm Thị Thanh",      "0901000017", "pthanh@hospital.com",     san,   4),
                new StaffSeed("vhhuy",     "123456", "Võ Hoàng Huy",        "0901000018", "vhhuy@hospital.com",      nhi,   5),
                new StaffSeed("atducd",    "123456", "Anh Trần Đức",        "0901000019", "atducd@hospital.com",     ngoai, 5),
                new StaffSeed("dttthuy",   "123456", "Đặng Trần Thanh Thúy","0901000020","dttthuy@hospital.com",    noi,   5),
        };

        for (StaffSeed s : seeds) {
            Staff staff = staffRepository.save(Staff.builder()
                    .username(s.username).passwordHash(passwordEncoder.encode(s.password))
                    .fullName(s.fullName).phone(s.phone)
                    .email(s.email).specialty(s.specialty)
                    .maxShiftsPerMonth(s.maxShifts)
                    .isActive(true).staffRoles(new HashSet<>()).build());
            addRoles(staff, staffRole);
        }

        log.info("✅ Seeded: 1 admin + 2 manager + 17 staff = 20 total users");
    }

    private void addRoles(Staff staff, AppRole... roles) {
        for (AppRole role : roles) {
            if (role != null) {
                StaffRole sr = StaffRole.builder()
                        .staffId(staff.getId()).roleId(role.getId()).build();
                staff.getStaffRoles().add(sr);
            }
        }
        staffRepository.save(staff);
    }

    private void seedPeriodsAndSchedules() {
        Staff admin = staffRepository.findByUsername("admin").orElse(null);
        Staff manager1 = staffRepository.findByUsername("manager1").orElse(null);
        ShiftType l01 = shiftTypeRepository.findById("L01").orElse(null);
        ShiftType l02 = shiftTypeRepository.findById("L02").orElse(null);
        ShiftType l03 = shiftTypeRepository.findById("L03").orElse(null);
        ShiftType l04 = shiftTypeRepository.findById("L04").orElse(null);

        Specialty ngoai = specialtyRepository.findByName("Ngoại").orElse(null);
        Specialty noi = specialtyRepository.findByName("Nội").orElse(null);

        // ── 1. June 2026 — PUBLISHED ─────────────────────────────────────────
        if (periodRepository.findAllByStartDateAndEndDate(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).isEmpty()) {
            SchedulePeriod period = SchedulePeriod.builder()
                    .periodName("Kỳ tháng 06/2026")
                    .startDate(LocalDate.of(2026, 6, 1))
                    .endDate(LocalDate.of(2026, 6, 30))
                    .status(SchedulePeriod.PeriodStatus.PUBLISHED)
                    .generatedBy(admin)
                    .generatedAt(java.time.LocalDateTime.now())
                    .publishedAt(java.time.LocalDateTime.now())
                    .build();
            SchedulePeriod savedPeriod = periodRepository.save(period);

            List<Staff> allStaff = staffRepository.findByIsActiveTrue();
            List<Staff> ngoais = allStaff.stream()
                    .filter(s -> s.getSpecialty() != null && s.getSpecialty().getName().equals("Ngoại"))
                    .toList();
            List<Staff> nois = allStaff.stream()
                    .filter(s -> s.getSpecialty() != null && s.getSpecialty().getName().equals("Nội"))
                    .toList();

            int ngoaiIdx = 0;
            int noiIdx = 0;

            for (int day = 1; day <= 30; day++) {
                LocalDate date = LocalDate.of(2026, 6, day);
                if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) continue;

                // L01 — 2 Ngoại
                for (int k = 0; k < 2; k++) {
                    Staff s = ngoais.get(ngoaiIdx % ngoais.size());
                    ngoaiIdx++;
                    Schedule sch = scheduleRepository.save(Schedule.builder()
                            .period(savedPeriod).workDate(date).staff(s).shiftType(l01).hasConflict(false).build());
                    createCompensationDayForSeed(sch);
                }
                // L02 — 2 Ngoại
                for (int k = 0; k < 2; k++) {
                    Staff s = ngoais.get(ngoaiIdx % ngoais.size());
                    ngoaiIdx++;
                    scheduleRepository.save(Schedule.builder()
                            .period(savedPeriod).workDate(date).staff(s).shiftType(l02).hasConflict(false).build());
                }
                // L03 — 1 Nội
                Staff nur = nois.get(noiIdx % nois.size());
                noiIdx++;
                scheduleRepository.save(Schedule.builder()
                        .period(savedPeriod).workDate(date).staff(nur).shiftType(l03).hasConflict(false).build());
                // L04 — 1 Ngoại
                Staff expert = ngoais.get(ngoaiIdx % ngoais.size());
                ngoaiIdx++;
                scheduleRepository.save(Schedule.builder()
                        .period(savedPeriod).workDate(date).staff(expert).shiftType(l04).hasConflict(false).build());
            }

            // ── Inject 1 real conflict: same person on same day — L03 + L04 ──
            if (!ngoais.isEmpty()) {
                Staff conflictStaff = ngoais.get(3 % ngoais.size());
                scheduleRepository.save(Schedule.builder()
                        .period(savedPeriod).workDate(LocalDate.of(2026, 6, 10))
                        .staff(conflictStaff).shiftType(l03).hasConflict(true).build());
                scheduleRepository.save(Schedule.builder()
                        .period(savedPeriod).workDate(LocalDate.of(2026, 6, 10))
                        .staff(conflictStaff).shiftType(l04).hasConflict(true).build());
            }

            // Seed ShiftRequirement cho June (khớp với schedule đã tạo)
            seedShiftRequirementsForPeriod(savedPeriod, l01, l02, l03, l04, ngoai, noi);
            log.info("✅ Seeded published period June 2026 (full 30-day schedule + 1 conflict + requirements)");
        }

        // ── 2. July 2026 — DRAFT (for M07 auto-scheduling) ─────────────────
        if (periodRepository.findAllByStartDateAndEndDate(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)).isEmpty()) {
            SchedulePeriod draftPeriod = SchedulePeriod.builder()
                    .periodName("Kỳ tháng 07/2026")
                    .startDate(LocalDate.of(2026, 7, 1))
                    .endDate(LocalDate.of(2026, 7, 31))
                    .status(SchedulePeriod.PeriodStatus.DRAFT)
                    .generatedBy(admin)
                    .generatedAt(java.time.LocalDateTime.now())
                    .build();
            SchedulePeriod savedDraftPeriod = periodRepository.save(draftPeriod);

            // Seed ShiftRequirement cho July draft
            seedShiftRequirementsForPeriod(savedDraftPeriod, l01, l02, l03, l04, ngoai, noi);
            log.info("✅ Seeded draft period July 2026 with full requirements");
        }
    }

    /** Seed ShiftRequirement rows khớp với cấu trúc schedule mẫu. */
    private void seedShiftRequirementsForPeriod(SchedulePeriod period, ShiftType l01, ShiftType l02,
                                                 ShiftType l03, ShiftType l04, Specialty ngoai, Specialty noi) {
        if (shiftRequirementRepository.findByPeriodId(period.getId()).size() > 0) return;

        // Chỉ seed cho 1 chuyên khoa (Ngoại) để tránh feasibility timeout
        List<Specialty> l04Specialties = List.of(ngoai);
        List<ShiftRequirement> reqs = new ArrayList<>();
        LocalDate date = period.getStartDate();
        while (!date.isAfter(period.getEndDate())) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) { date = date.plusDays(1); continue; }

            reqs.add(buildReq(period, l01, date, null, 2));
            reqs.add(buildReq(period, l02, date, null, 2));
            reqs.add(buildReq(period, l03, date, null, 1));
            // L04: chỉ seed 1 chuyên khoa (Ngoại) cho gọn, khớp schedule mẫu
            reqs.add(buildReq(period, l04, date, ngoai, 1));
            date = date.plusDays(1);
        }
        shiftRequirementRepository.saveAll(reqs);
        log.info("Seeded {} shift requirements for period {}", reqs.size(), period.getId());
    }

    private ShiftRequirement buildReq(SchedulePeriod period, ShiftType shiftType, LocalDate date,
                                       Specialty specialty, int count) {
        return ShiftRequirement.builder()
                .period(period).shiftType(shiftType).workDate(date)
                .specialty(specialty).requiredStaffCount(count)
                .note("SEEDED")
                .build();
    }

    private void seedLeaveRequests() {
        if (leaveRequestRepository.count() > 0) return;

        List<Staff> staffList = staffRepository.findByIsActiveTrue();
        if (staffList.isEmpty()) return;

        Staff s = staffList.get(4 % staffList.size());
        Staff reviewer = staffRepository.findByUsername("manager1").orElse(staffList.get(0));

        // 1 PENDING request
        leaveRequestRepository.save(com.hospital.scheduler.entity.LeaveRequest.builder()
                .staff(s)
                .startDate(LocalDate.of(2026, 6, 15))
                .endDate(LocalDate.of(2026, 6, 17))
                .reason("Du lịch gia đình")
                .status(com.hospital.scheduler.entity.LeaveRequest.LeaveStatus.PENDING)
                .build());

        // 1 APPROVED request
        Staff s2 = staffList.get(6 % staffList.size());
        leaveRequestRepository.save(com.hospital.scheduler.entity.LeaveRequest.builder()
                .staff(s2)
                .startDate(LocalDate.of(2026, 6, 8))
                .endDate(LocalDate.of(2026, 6, 9))
                .reason("Ốm đau")
                .status(com.hospital.scheduler.entity.LeaveRequest.LeaveStatus.APPROVED)
                .reviewedBy(reviewer)
                .reviewedAt(java.time.LocalDateTime.now().minusDays(5))
                .build());

        // 1 REJECTED request
        Staff s3 = staffList.get(8 % staffList.size());
        leaveRequestRepository.save(com.hospital.scheduler.entity.LeaveRequest.builder()
                .staff(s3)
                .startDate(LocalDate.of(2026, 6, 20))
                .endDate(LocalDate.of(2026, 6, 22))
                .reason("Việc riêng")
                .status(com.hospital.scheduler.entity.LeaveRequest.LeaveStatus.REJECTED)
                .reviewedBy(reviewer)
                .reviewedAt(java.time.LocalDateTime.now().minusDays(2))
                .reviewNote("Đang trong giai đoạn cao điểm, thiếu nhân sự thay thế.")
                .build());

        log.info("✅ Seeded 3 leave requests (pending, approved, rejected)");
    }

    private void seedSwapRequests() {
        if (scheduleExchangeRepository.count() > 0) return;

        List<Schedule> schedules = scheduleRepository.findAll();
        if (schedules.size() < 4) return;

        Schedule s1 = schedules.get(0);
        Schedule s2 = schedules.get(1);
        Staff manager = staffRepository.findByUsername("manager1").orElse(null);
        com.hospital.scheduler.entity.SchedulePeriod period = s1.getPeriod();

        // 1 PENDING swap
        scheduleExchangeRepository.save(com.hospital.scheduler.entity.ScheduleExchange.builder()
                .period(period)
                .requester(s1.getStaff())
                .requesterSchedule(s1)
                .target(s2.getStaff())
                .targetSchedule(s2)
                .status(com.hospital.scheduler.entity.ScheduleExchange.ExchangeStatus.PENDING)
                .reason("Trùng lịch họp khoa")
                .build());

        // 1 APPROVED swap (historical)
        if (schedules.size() >= 4) {
            Schedule s3 = schedules.get(2);
            Schedule s4 = schedules.get(3);
            scheduleExchangeRepository.save(com.hospital.scheduler.entity.ScheduleExchange.builder()
                    .period(period)
                    .requester(s3.getStaff())
                    .requesterSchedule(s3)
                    .target(s4.getStaff())
                    .targetSchedule(s4)
                    .status(com.hospital.scheduler.entity.ScheduleExchange.ExchangeStatus.APPROVED)
                    .reason("Cần đổi ngày nghỉ")
                    .reviewedBy(manager)
                    .reviewedAt(java.time.LocalDateTime.now().minusDays(10))
                    .build());
        }

        log.info("✅ Seeded 2 swap requests (pending, approved)");
    }

    private void seedNotifications() {
        if (notificationRepository.count() > 0) return;

        List<Staff> staffList = staffRepository.findByIsActiveTrue();
        if (staffList.isEmpty()) return;

        Staff s = staffList.get(3 % staffList.size());
        Staff admin = staffRepository.findByUsername("admin").orElse(s);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        // Recent: conflict alert
        notificationRepository.save(com.hospital.scheduler.entity.Notification.builder()
                .staff(s)
                .title("Cảnh báo xung đột lịch trực")
                .message("Phát hiện xung đột lịch ngày 10/06/2026 — bạn được phân công cả L03 và L04 cùng ngày.")
                .isRead(false)
                .build());

        // Recent: schedule published
        notificationRepository.save(com.hospital.scheduler.entity.Notification.builder()
                .staff(s)
                .title("Lịch công tác tháng 6 đã công bố")
                .message("Kỳ tháng 06/2026 đã được công bố. Vui lòng kiểm tra lịch trực của bạn.")
                .isRead(false)
                .createdAt(now.minusHours(2))
                .build());

        // Older: read
        notificationRepository.save(com.hospital.scheduler.entity.Notification.builder()
                .staff(s)
                .title("Yêu cầu nghỉ phép đã được duyệt")
                .message("Yêu cầu nghỉ phép ngày 08–09/06 đã được duyệt.")
                .isRead(true)
                .createdAt(now.minusDays(3))
                .readAt(now.minusDays(2))
                .build());

        // Different staff
        Staff s2 = staffList.get(7 % staffList.size());
        notificationRepository.save(com.hospital.scheduler.entity.Notification.builder()
                .staff(s2)
                .title("Phân công lịch trực mới")
                .message("Bạn được phân công lịch L01 ngày 05/06/2026. Ngày nghỉ bù: 11/06/2026.")
                .isRead(false)
                .build());

        log.info("✅ Seeded 4 notifications (2 unread, 2 read)");
    }

    private void seedAuditHistory() {
        if (auditHistoryRepository.count() > 0) return;

        Staff admin = staffRepository.findByUsername("admin").orElse(null);
        Staff manager = staffRepository.findByUsername("manager1").orElse(null);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        if (admin != null) {
            auditHistoryRepository.save(com.hospital.scheduler.entity.AuditHistory.builder()
                    .tableName("schedule_period")
                    .recordId(1)
                    .actionType(com.hospital.scheduler.entity.AuditHistory.ActionType.INSERT)
                    .newData("{\"periodName\":\"Kỳ tháng 06/2026\"}")
                    .changedBy(admin)
                    .createdAt(now.minusDays(10))
                    .build());

            auditHistoryRepository.save(com.hospital.scheduler.entity.AuditHistory.builder()
                    .tableName("schedule")
                    .recordId(1)
                    .actionType(com.hospital.scheduler.entity.AuditHistory.ActionType.INSERT)
                    .newData("{\"shiftTypeId\":\"L01\",\"workDate\":\"2026-06-01\"}")
                    .changedBy(admin)
                    .createdAt(now.minusDays(9))
                    .build());
        }

        if (manager != null) {
            auditHistoryRepository.save(com.hospital.scheduler.entity.AuditHistory.builder()
                    .tableName("leave_request")
                    .recordId(1)
                    .actionType(com.hospital.scheduler.entity.AuditHistory.ActionType.UPDATE)
                    .oldData("{\"status\":\"PENDING\"}")
                    .newData("{\"status\":\"APPROVED\"}")
                    .changedBy(manager)
                    .createdAt(now.minusDays(5))
                    .build());
        }

        log.info("✅ Seeded audit history entries");
    }

    private void createCompensationDayForSeed(Schedule schedule) {
        LocalDate shiftDate = schedule.getWorkDate();
        LocalDate compensationDate = compensationDateCalculator.calculateWithoutHolidays(shiftDate);

        // Check if compensation day already exists to avoid duplicates
        if (compensationDayRepository.findByStaffIdAndCompensationDate(
                schedule.getStaff().getId(), compensationDate).isPresent()) {
            return;
        }

        CompensationDay compDay = CompensationDay.builder()
                .schedule(schedule)
                .staff(schedule.getStaff())
                .period(schedule.getPeriod())
                .shiftDate(shiftDate)
                .compensationDate(compensationDate)
                .note("Ngày nghỉ bù tự động từ ca L01 (Seed)")
                .build();
        compensationDayRepository.save(compDay);
    }
}
