package com.teagather.analysis.repository;

import com.teagather.analysis.constant.AnalysisStatus;
import com.teagather.analysis.entity.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    Optional<AnalysisRun> findFirstByStatusOrderByIdDesc(AnalysisStatus status);

    Optional<AnalysisRun> findFirstByOrderByIdDesc();

    List<AnalysisRun> findTop10ByOrderByIdDesc();

    boolean existsByStatus(AnalysisStatus status);

    // 멈춘 분석 정리 : cutoff 보다 먼저 시작해 아직 RUNNING 인 실행을 FAILED 로 바꾼다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AnalysisRun r set r.status = :failed, r.finishedAt = :now, r.message = :message " +
            "where r.status = :running and (r.startedAt is null or r.startedAt < :cutoff)")
    int failRunningStartedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now,
                                 @Param("message") String message,
                                 @Param("running") AnalysisStatus running, @Param("failed") AnalysisStatus failed);
}
