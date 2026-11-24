package com.teagather.analysis.repository;

import com.teagather.analysis.entity.MenuMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuMetricRepository extends JpaRepository<MenuMetric, Long> {

    @Query("select m from MenuMetric m join fetch m.product p left join fetch p.category where m.run.id = :runId order by m.qty30 desc")
    List<MenuMetric> findByRun(@Param("runId") Long runId);
}
