package com.hospital.scheduler.repository;

import com.hospital.scheduler.entity.CompensationDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompensationDayRepository extends JpaRepository<CompensationDay, Integer> {
    List<CompensationDay> findByStaffId(Integer staffId);
    List<CompensationDay> findByCompensationDate(LocalDate compensationDate);
    Optional<CompensationDay> findByStaffIdAndCompensationDate(Integer staffId, LocalDate compensationDate);
    boolean existsByStaffIdAndCompensationDate(Integer staffId, LocalDate compensationDate);
    @Query("SELECT cd FROM CompensationDay cd JOIN FETCH cd.staff WHERE cd.staff.id = :staffId AND cd.period.id = :periodId")
    List<CompensationDay> findByStaffIdAndPeriodId(@Param("staffId") Integer staffId, @Param("periodId") Integer periodId);
    @Query("SELECT cd FROM CompensationDay cd JOIN FETCH cd.staff JOIN FETCH cd.schedule WHERE cd.schedule.id = :scheduleId")
    List<CompensationDay> findByScheduleId(@Param("scheduleId") Integer scheduleId);

    @Query("SELECT cd FROM CompensationDay cd JOIN FETCH cd.staff WHERE cd.period.id = :periodId")
    List<CompensationDay> findByPeriodId(@Param("periodId") Integer periodId);

    @Query("SELECT cd FROM CompensationDay cd JOIN FETCH cd.schedule JOIN FETCH cd.staff WHERE cd.period.id = :periodId")
    List<CompensationDay> findByPeriodIdWithStaff(@Param("periodId") Integer periodId);

    @Query("SELECT cd FROM CompensationDay cd WHERE cd.staff.id = :staffId AND cd.compensationDate BETWEEN :startDate AND :endDate")
    List<CompensationDay> findByStaffIdAndDateRange(
            @Param("staffId") Integer staffId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    boolean existsByScheduleId(Integer scheduleId);

    /** Batch query: all compensation days for a specific date (no staff filter). */
    @Query("SELECT cd FROM CompensationDay cd WHERE cd.compensationDate = :date")
    List<CompensationDay> findByDate(@Param("date") LocalDate date);

    /** Batch query: all compensation days within a date range (no staff filter, for period-level batch checks). */
    @Query("SELECT cd FROM CompensationDay cd WHERE cd.compensationDate BETWEEN :startDate AND :endDate")
    List<CompensationDay> findInRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Batch query: comp days within date range, scoped to a specific period.
     * Used by conflict detector so we don't pull comp days from other periods
     * that happen to fall inside the same calendar window.
     */
    @Query("SELECT cd FROM CompensationDay cd WHERE cd.period.id = :periodId AND cd.compensationDate BETWEEN :startDate AND :endDate")
    List<CompensationDay> findInRangeByPeriod(@Param("periodId") Integer periodId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    @Query("SELECT cd FROM CompensationDay cd JOIN FETCH cd.staff JOIN FETCH cd.schedule WHERE cd.schedule.id IN :scheduleIds")
    List<CompensationDay> findByScheduleIds(@Param("scheduleIds") List<Integer> scheduleIds);

    @Modifying
    @Query("DELETE FROM CompensationDay cd WHERE cd.period.id = :periodId")
    void deleteAllByPeriodId(@Param("periodId") Integer periodId);

    @Modifying
    @Query("DELETE FROM CompensationDay cd WHERE cd.schedule.id = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Integer scheduleId);

    /**
     * Insert compensation day idempotently.
     *
     * <p>Implementation lives in {@link DatabaseCompatibilityHelper} because
     * MySQL ({@code INSERT IGNORE}) and Postgres ({@code ON CONFLICT DO NOTHING})
     * use different syntax. Callers should inject the helper instead of using
     * this method directly.
     */
}
