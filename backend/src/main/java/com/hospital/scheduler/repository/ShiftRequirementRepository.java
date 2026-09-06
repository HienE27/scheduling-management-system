package com.hospital.scheduler.repository;

import com.hospital.scheduler.entity.ShiftRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

public interface ShiftRequirementRepository extends JpaRepository<ShiftRequirement, Integer> {
    List<ShiftRequirement> findByPeriodId(Integer periodId);

    @Query("SELECT sr FROM ShiftRequirement sr WHERE sr.period.id = :periodId AND sr.workDate = :workDate")
    List<ShiftRequirement> findByPeriodIdAndWorkDate(@Param("periodId") Integer periodId, @Param("workDate") LocalDate workDate);

    @Query("SELECT sr FROM ShiftRequirement sr WHERE sr.period.id = :periodId AND sr.workDate BETWEEN :startDate AND :endDate")
    List<ShiftRequirement> findByPeriodIdAndDateRange(
            @Param("periodId") Integer periodId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT sr FROM ShiftRequirement sr WHERE sr.period.id = :periodId AND sr.workDate = :workDate AND sr.shiftType.id = :shiftTypeId")
    java.util.Optional<ShiftRequirement> findByPeriodIdAndWorkDateAndShiftTypeId(
            @Param("periodId") Integer periodId,
            @Param("workDate") LocalDate workDate,
            @Param("shiftTypeId") String shiftTypeId);

    /**
     * Delete all requirements for a period using native SQL.
     * Bypasses JPA entity cache to avoid stale data issues and works reliably
     * even when schedules still reference these requirements.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM shift_requirement WHERE period_id = :periodId", nativeQuery = true)
    void deleteAllByPeriodIdNative(@Param("periodId") Integer periodId);

    /**
     * Set schedule.requirement_id = NULL for all schedules pointing at this requirement,
     * so the FK does not block delete. Must be called BEFORE deleteById on shift_requirement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE schedule SET requirement_id = NULL WHERE requirement_id = :requirementId", nativeQuery = true)
    void detachScheduleReferencesNative(@Param("requirementId") Integer requirementId);

    /**
     * Set schedule.requirement_id = NULL for all schedules whose requirement belongs to the
     * given period. Must be called BEFORE bulk-delete of shift_requirement rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE schedule SET requirement_id = NULL
        WHERE requirement_id IN (SELECT id FROM shift_requirement WHERE period_id = :periodId)
        """, nativeQuery = true)
    void detachScheduleReferencesByPeriodNative(@Param("periodId") Integer periodId);

    /**
     * Delete L04 requirements for specialties that have no active staff.
     *
     * <p>Moved to {@link DatabaseCompatibilityHelper} because MySQL multi-table
     * {@code DELETE alias FROM ...} syntax is rejected by Postgres. Callers
     * should inject the helper.
     */

    /**
     * Find L04 requirements for specialties that have no active staff.
     */
    @Query(value = """
        SELECT sr.* FROM shift_requirement sr
        WHERE sr.period_id = :periodId
          AND sr.shift_type_id = 'L04'
          AND sr.specialty_id IN (
              SELECT s.id FROM specialty s
              LEFT JOIN staff st ON st.specialty_id = s.id AND st.active = true
              GROUP BY s.id
              HAVING COUNT(st.id) = 0
          )
        """, nativeQuery = true)
	    List<ShiftRequirement> findL04RequirementsWithoutStaff(@Param("periodId") Integer periodId);

	    /**
	     * Bulk delete requirements for given shift types within a period.
	     * Used when shift types are added to removedShiftTypes.
	     */
	    @Modifying(clearAutomatically = true, flushAutomatically = true)
	    @Query(value = "DELETE FROM shift_requirement WHERE period_id = :periodId AND shift_type_id IN (:shiftTypeIds)", nativeQuery = true)
	    int deleteByPeriodIdAndShiftTypeIds(@Param("periodId") Integer periodId, @Param("shiftTypeIds") java.util.Set<String> shiftTypeIds);
	}
