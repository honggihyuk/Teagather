package com.teagather.analysis.repository;

import com.teagather.analysis.constant.AnalysisStatus;
import com.teagather.analysis.entity.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    Optional<AnalysisRun> findFirstByStatusOrderByIdDesc(AnalysisStatus status);

    Optional<AnalysisRun> findFirstByOrderByIdDesc();

    List<AnalysisRun> findTop10ByOrderByIdDesc();

    boolean existsByStatus(AnalysisStatus status);
}
