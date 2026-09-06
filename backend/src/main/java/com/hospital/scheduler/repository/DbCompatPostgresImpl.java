package com.hospital.scheduler.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * PostgreSQL implementation of {@link DatabaseCompatibilityHelper}.
 *
 * <p>Active when {@code spring.datasource.driver-class-name=org.postgresql.Driver}.
 * Emits Postgres-specific SQL: {@code ON CONFLICT DO NOTHING},
 * {@code CAST(... AS INTEGER)}, {@code DELETE FROM table alias WHERE ...}.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.driver-class-name",
        havingValue = "org.postgresql.Driver")
public class DbCompatPostgresImpl implements DatabaseCompatibilityHelper {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public int insertCompensationDayIfAbsent(Integer staffId, Integer periodId,
                                             Integer scheduleId, LocalDate shiftDate,
                                             LocalDate compDate, String note) {
        return em.createNativeQuery(
                "INSERT INTO compensation_day " +
                "(staff_id, period_id, schedule_id, shift_date, compensation_date, note, created_at, updated_at) " +
                "VALUES (:staffId, :periodId, :scheduleId, :shiftDate, :compDate, :note, NOW(), NOW()) " +
                "ON CONFLICT (staff_id, compensation_date) DO NOTHING")
                .setParameter("staffId", staffId)
                .setParameter("periodId", periodId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("shiftDate", shiftDate)
                .setParameter("compDate", compDate)
                .setParameter("note", note)
                .executeUpdate();
    }

    @Override
    public int findMaxStaffCodeNumber(String prefix, int prefixLen) {
        Object result = em.createNativeQuery(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(staff_code, :prefixLen + 1) AS INTEGER)), 0) " +
                "FROM staff WHERE staff_code LIKE CONCAT(:prefix, '%')")
                .setParameter("prefix", prefix)
                .setParameter("prefixLen", prefixLen)
                .getSingleResult();
        return result == null ? 0 : ((Number) result).intValue();
    }

    @Override
    @Transactional
    public int deleteL04RequirementsWithoutStaff(Integer periodId) {
        return em.createNativeQuery(
                "DELETE FROM shift_requirement sr " +
                "WHERE sr.period_id = :periodId " +
                "  AND sr.shift_type_id = 'L04' " +
                "  AND sr.specialty_id IN (" +
                "      SELECT s.id FROM specialty s " +
                "      LEFT JOIN staff st ON st.specialty_id = s.id AND st.active = true " +
                "      GROUP BY s.id HAVING COUNT(st.id) = 0" +
                "  )")
                .setParameter("periodId", periodId)
                .executeUpdate();
    }
}
