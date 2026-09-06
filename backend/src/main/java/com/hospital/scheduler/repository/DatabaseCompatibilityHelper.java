package com.hospital.scheduler.repository;

import java.time.LocalDate;

/**
 * Database-portable operations that need raw SQL because JPQL can't express
 * dialect-specific features (MySQL INSERT IGNORE, Postgres ON CONFLICT, etc).
 *
 * <p>Implementations live in {@code DbCompatMySqlImpl} (active when
 * {@code spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver}) and
 * {@code DbCompatPostgresImpl} (active when driver is
 * {@code org.postgresql.Driver}). Each implementation emits SQL that is valid
 * for its target dialect only.
 *
 * <p>The pattern is "custom repository fragment" — services inject the helper
 * directly rather than the repository interface.
 */
public interface DatabaseCompatibilityHelper {

    /**
     * Idempotent INSERT into compensation_day.
     *
     * <p>MySQL: {@code INSERT IGNORE INTO compensation_day ...} — silently
     * drops the row when the unique key collides.
     * <p>Postgres: {@code INSERT INTO compensation_day ... ON CONFLICT DO NOTHING}.
     *
     * @return 1 if inserted, 0 if skipped (duplicate).
     */
    int insertCompensationDayIfAbsent(Integer staffId,
                                      Integer periodId,
                                      Integer scheduleId,
                                      LocalDate shiftDate,
                                      LocalDate compDate,
                                      String note);

    /**
     * Find the maximum numeric suffix of staff codes with the given prefix.
     * MySQL uses {@code CAST(... AS UNSIGNED)}, Postgres uses
     * {@code CAST(... AS INTEGER)}.
     */
    int findMaxStaffCodeNumber(String prefix, int prefixLen);

    /**
     * Delete L04 requirements for specialties that have no active staff.
     * MySQL uses multi-table {@code DELETE sr FROM ...}, Postgres uses
     * {@code DELETE FROM shift_requirement sr WHERE ...}.
     *
     * @return number of rows deleted.
     */
    int deleteL04RequirementsWithoutStaff(Integer periodId);
}
