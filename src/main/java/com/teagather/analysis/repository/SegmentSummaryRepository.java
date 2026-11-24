package com.teagather.analysis.repository;

import com.teagather.analysis.entity.SegmentSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SegmentSummaryRepository extends JpaRepository<SegmentSummary, Long> {

    List<SegmentSummary> findByRunIdOrderBySortOrderAsc(Long runId);
}
