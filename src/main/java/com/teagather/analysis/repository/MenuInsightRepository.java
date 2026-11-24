package com.teagather.analysis.repository;

import com.teagather.analysis.entity.MenuInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuInsightRepository extends JpaRepository<MenuInsight, Long> {

    @Query("select i from MenuInsight i join fetch i.product p left join fetch p.category where i.run.id = :runId order by i.kind, i.rank")
    List<MenuInsight> findByRun(@Param("runId") Long runId);
}
