package com.hospital.scheduler.service;

import com.hospital.scheduler.entity.AppRole;
import com.hospital.scheduler.entity.Holiday;
import com.hospital.scheduler.entity.RoleName;
import com.hospital.scheduler.entity.ScheduleTemplate;
import com.hospital.scheduler.entity.ShiftType;
import com.hospital.scheduler.entity.Specialty;
import com.hospital.scheduler.entity.Staff;
import com.hospital.scheduler.entity.StaffRole;
import com.hospital.scheduler.exception.BusinessRuleException;
import com.hospital.scheduler.repository.AppRoleRepository;
import com.hospital.scheduler.repository.HolidayRepository;
import com.hospital.scheduler.repository.ScheduleTemplateRepository;
import com.hospital.scheduler.repository.ShiftTypeRepository;
import com.hospital.scheduler.repository.SpecialtyRepository;
import com.hospital.scheduler.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * One-time demo data seeder for fresh production databases (Neon PostgreSQL).
 *
 * <p>Mirrors the local {@code DataSeeder} catalog but skips the heavy
 * {@code schedule_period + schedule + compensation_day + shift_requirement}
 * insertion that exceeded Render free tier's 5-minute port-scan window on
 * the first deploy attempt.</p>
 *
 * <p>What gets seeded (idempotent on existing rows):</p>
 * <ul>
 *   <li>6 specialties: Ngoại, Nội, Sản, Nhi, Mắt, Răng</li>
 *   <li>4 shift types: L01 (24/24), L02 (thông tầm), L03 (PK dịch vụ), L04 (PK chuyên gia)</li>
 *   <li>8 holidays for 2026 (Tết, 30/4, 1/5, 2/9, ...)</li>
 *   <li>8 schedule templates (per day-of-week)</li>
 *   <li>2 demo managers + 17 demo staff (admin user is NOT touched — already created by /auth/bootstrap-admin)</li>
 * </ul>
 *
 * <p>Refuses to run if specialties already exist (idempotency guard).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapDemoDataService {

    private final SpecialtyRepository specialtyRepository;
    private final ShiftTypeRepository shiftTypeRepository;
    private final HolidayRepository holidayRepository;
    private final ScheduleTemplateRepository scheduleTemplateRepository;
    private final StaffRepository staffRepository;
    private final AppRoleRepository appRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Map<String, Object> bootstrapDemoData() {
        if (specialtyRepository.count() > 0) {
            throw new BusinessRuleException(
                "Demo data already seeded (specialty table has " + specialtyRepository.count() + " rows). " +
                "Use the existing data or reset the database to bootstrap again."
            );
        }

        // ── 1. Specialties ───────────────────────────────────────────────
        Specialty ngoai = saveSpecialty("Ngoại", "Khoa Ngoại tổng hợp");
        Specialty noi = saveSpecialty("Nội", "Khoa Nội tổng hợp");
        Specialty san = saveSpecialty("Sản", "Khoa Sản phụ khoa");
        Specialty nhi = saveSpecialty("Nhi", "Khoa Nhi");
        Specialty mat = saveSpecialty("Mắt", "Khoa Mắt");
        Specialty rang = saveSpecialty("Răng", "Khoa Răng hàm mặt");
        log.info("✅ Seeded 6 specialties");

        // ── 2. Shift types ───────────────────────────────────────────────
        ShiftType l01 = saveShiftType("L01", "Lịch trực 24/24",
                "Ca trực 24/24 từ 7h30 ngày N đến 7h30 ngày N+1, có nghỉ bù", true, 3);
        ShiftType l02 = saveShiftType("L02", "Lịch thông tầm",
                "Ca ngày, không nghỉ trưa", false, 1);
        ShiftType l03 = saveShiftType("L03", "Lịch phòng khám dịch vụ",
                "Ca khám dịch vụ", false, 1);
        ShiftType l04 = saveShiftType("L04", "Lịch phòng khám chuyên gia",
                "Ca khám chuyên sâu", false, 2);
        log.info("✅ Seeded 4 shift types (L01-L04)");

        // ── 3. Holidays ──────────────────────────────────────────────────
        record HolidaySeed(String name, int month, int day, String description) {}
        List<HolidaySeed> holidaySeeds = List.of(
            new HolidaySeed("Tết Dương lịch",       1,  1,  "Năm mới Dương lịch"),
            new HolidaySeed("Tết Nguyên đán",        2,  14, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Tết Nguyên đán",        2,  15, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Tết Nguyên đán",        2,  16, "Tết Nguyên đán Ất Tỵ"),
            new HolidaySeed("Giỗ Tổ Hùng Vương",    4,  27, "Giỗ Tổ Hùng Vương"),
            new HolidaySeed("Ngày Giải phóng miền Nam", 4, 30, "Ngày Giải phóng miền Nam 30/4"),
            new HolidaySeed("Quốc tế Lao động",       5,  1,  "Ngày Quốc tế Lao động"),
            new HolidaySeed("Ngày Quốc khánh",         9,  2,  "Ngày Quốc khánh Việt Nam")
        );
        int holidaysAdded = 0;
        for (HolidaySeed h : holidaySeeds) {
            LocalDate date = LocalDate.of(2026, h.month, h.day);
            if (holidayRepository.findByHolidayDate(date).isEmpty()) {
                holidayRepository.save(Holiday.builder()
                        .name(h.name)
                        .holidayDate(date)
                        .year(date.getYear())
                        .isNationalHoliday(true)
                        .description(h.description)
                        .isActive(true)
                        .build());
                holidaysAdded++;
            }
        }
        log.info("✅ Seeded {} holidays for 2026", holidaysAdded);

        // ── 4. Schedule templates ───────────────────────────────────────
        record TemplateSeed(String name, String desc, int dayOfWeek, String shiftTypeId, Specialty specialty, int count) {}
        List<TemplateSeed> templates = List.of(
            new TemplateSeed("Trực 24/24 thứ 2", "Lịch trực 24/24 vào thứ 2", 1, "L01", ngoai, 1),
            new TemplateSeed("Trực 24/24 thứ 3", "Lịch trực 24/24 vào thứ 3", 2, "L01", ngoai, 1),
            new TemplateSeed("Trực 24/24 thứ 4", "Lịch trực 24/24 vào thứ 4", 3, "L01", ngoai, 1),
            new TemplateSeed("Trực 24/24 thứ 5", "Lịch trực 24/24 vào thứ 5", 4, "L01", ngoai, 1),
            new TemplateSeed("Trực 24/24 thứ 6", "Lịch trực 24/24 vào thứ 6", 5, "L01", ngoai, 1),
            new TemplateSeed("Thông tầm thứ 2–6", "Ca thông tầm các ngày trong tuần", 1, "L02", ngoai, 2),
            new TemplateSeed("PK dịch vụ thứ 2–6", "Phòng khám dịch vụ các ngày trong tuần", 1, "L03", noi, 1),
            new TemplateSeed("PK chuyên gia thứ 7", "Phòng khám chuyên gia vào thứ 7", 6, "L04", ngoai, 1)
        );
        for (TemplateSeed t : templates) {
            scheduleTemplateRepository.save(ScheduleTemplate.builder()
                    .name(t.name)
                    .description(t.desc)
                    .dayOfWeek(t.dayOfWeek)
                    .shiftTypeId(t.shiftTypeId)
                    .specialty(t.specialty)
                    .requiredStaffCount(t.count)
                    .isActive(true)
                    .build());
        }
        log.info("✅ Seeded {} schedule templates", templates.size());

        // ── 5. Demo managers + staff (skip admin — already created) ───────
        AppRole managerRole = appRoleRepository.findByName(RoleName.MANAGER)
                .orElseThrow(() -> new IllegalStateException("MANAGER role missing"));
        AppRole staffRole = appRoleRepository.findByName(RoleName.STAFF)
                .orElseThrow(() -> new IllegalStateException("STAFF role missing"));

        // Manager 1 (only if username not taken)
        if (staffRepository.findByUsername("manager1").isEmpty()) {
            Staff mgr1 = staffRepository.save(Staff.builder()
                    .username("manager1")
                    .passwordHash(passwordEncoder.encode("123456"))
                    .fullName("Trần Thị Bình")
                    .phone("0901000002")
                    .email("manager1@hospital.com")
                    .specialty(ngoai)
                    .maxShiftsPerMonth(4)
                    .isActive(true)
                    .staffRoles(new HashSet<>())
                    .build());
            mgr1.getStaffRoles().add(StaffRole.builder()
                    .staffId(mgr1.getId()).roleId(managerRole.getId()).build());
            staffRepository.save(mgr1);
        }

        // Manager 2
        if (staffRepository.findByUsername("manager2").isEmpty()) {
            Staff mgr2 = staffRepository.save(Staff.builder()
                    .username("manager2")
                    .passwordHash(passwordEncoder.encode("123456"))
                    .fullName("Lê Hoàng Cường")
                    .phone("0901000003")
                    .email("manager2@hospital.com")
                    .specialty(noi)
                    .maxShiftsPerMonth(4)
                    .isActive(true)
                    .staffRoles(new HashSet<>())
                    .build());
            mgr2.getStaffRoles().add(StaffRole.builder()
                    .staffId(mgr2.getId()).roleId(managerRole.getId()).build());
            staffRepository.save(mgr2);
        }

        // 17 staff users
        record StaffSeed(String username, String fullName, String phone, String email,
                         Specialty specialty, int maxShifts) {}
        List<StaffSeed> staffSeeds = List.of(
            new StaffSeed("nvminh",    "Nguyễn Văn Minh",     "0901000004", "nvminh@hospital.com",    ngoai,  5),
            new StaffSeed("tthuhien",  "Trần Thu Hiền",       "0901000005", "tthuhien@hospital.com",  noi,   5),
            new StaffSeed("lbthanhtam","Lê Bùi Thanh Tâm",    "0901000006", "lbthanhtam@hospital.com",ngoai,  6),
            new StaffSeed("hpdat",     "Hoàng Phú Đạt",        "0901000007", "hpdat@hospital.com",     nhi,   5),
            new StaffSeed("ntphuong",  "Ngô Thị Phượng",       "0901000008", "ntphuong@hospital.com",  san,   4),
            new StaffSeed("cmtuan",    "Chu Minh Tuấn",        "0901000009", "cmtuan@hospital.com",    ngoai, 5),
            new StaffSeed("dvanh",     "Đỗ Văn Anh",           "0901000010", "dvanh@hospital.com",     noi,   5),
            new StaffSeed("nthuylinh", "Nguyễn Thị Huyền Linh","0901000011", "nthuylinh@hospital.com", ngoai, 6),
            new StaffSeed("vtquan",    "Vũ Trọng Quân",        "0901000012", "vtquan@hospital.com",    nhi,   5),
            new StaffSeed("btdthu",    "Bùi Thị Diễm Thu",     "0901000013", "btdthu@hospital.com",    mat,   4),
            new StaffSeed("nhduy",     "Nguyễn Hữu Duy",       "0901000014", "nhduy@hospital.com",     ngoai, 5),
            new StaffSeed("lthanhha",  "Lý Thị Thanh Hà",      "0901000015", "lthanhha@hospital.com",  noi,   5),
            new StaffSeed("dtqhieu",   "Đinh Trần Quang Hiếu","0901000016", "dtqhieu@hospital.com",   ngoai, 6),
            new StaffSeed("pthanh",    "Phạm Thị Thanh",        "0901000017", "pthanh@hospital.com",     san,   4),
            new StaffSeed("vhhuy",     "Võ Hoàng Huy",          "0901000018", "vhhuy@hospital.com",      nhi,   5),
            new StaffSeed("atducd",    "Anh Trần Đức",          "0901000019", "atducd@hospital.com",     ngoai, 5),
            new StaffSeed("dttthuy",   "Đặng Trần Thanh Thúy", "0901000020", "dttthuy@hospital.com",    noi,   5)
        );
        int staffCreated = 0;
        for (StaffSeed s : staffSeeds) {
            if (staffRepository.findByUsername(s.username).isPresent()) continue;
            Staff stf = staffRepository.save(Staff.builder()
                    .username(s.username)
                    .passwordHash(passwordEncoder.encode("123456"))
                    .fullName(s.fullName)
                    .phone(s.phone)
                    .email(s.email)
                    .specialty(s.specialty)
                    .maxShiftsPerMonth(s.maxShifts)
                    .isActive(true)
                    .staffRoles(new HashSet<>())
                    .build());
            stf.getStaffRoles().add(StaffRole.builder()
                    .staffId(stf.getId()).roleId(staffRole.getId()).build());
            staffRepository.save(stf);
            staffCreated++;
        }
        log.info("✅ Seeded {} staff (skipped existing)", staffCreated);

        Map<String, Object> result = new HashMap<>();
        result.put("specialties", 6);
        result.put("shiftTypes", 4);
        result.put("holidays", holidaysAdded);
        result.put("scheduleTemplates", templates.size());
        result.put("staffCreated", staffCreated);
        result.put("managerCredentials", "manager1 / manager2 → password 123456");
        result.put("staffCredentials", "nvminh, tthuhien, ... → password 123456");
        return result;
    }

    private Specialty saveSpecialty(String name, String desc) {
        return specialtyRepository.save(Specialty.builder()
                .name(name).description(desc).isActive(true).build());
    }

    private ShiftType saveShiftType(String id, String name, String desc, boolean overnight, int fatigue) {
        return shiftTypeRepository.save(ShiftType.builder()
                .id(id).name(name).description(desc)
                .isOvernight(overnight).fatigueScore(fatigue).isActive(true).build());
    }
}
