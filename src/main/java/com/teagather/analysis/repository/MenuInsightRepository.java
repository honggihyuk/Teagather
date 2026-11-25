package com.teagather.analysis.repository;

import com.teagather.analysis.entity.MenuInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MenuInsightRepository extends JpaRepository<MenuInsight, Long> {

    @Query("select i from MenuInsight i join fetch i.product p left join fetch p.category where i.run.id = :runId order by i.kind, i.rank")
    List<MenuInsight> findByRun(@Param("runId") Long runId);

    // 적용 표시를 한 번만 : 아직 적용 전일 때만 바꾸고 바뀐 행 수를 돌려준다 (0 이면 이미 누가 적용함)
    // 같은 행을 동시에 바꾸려는 두 번째 트랜잭션은 행 잠금에서 기다렸다가 0 을 받는다
    @Modifying(flushAutomatically = true)
    @Query("update MenuInsight i set i.applied = true, i.appliedAt = :now where i.id = :id and i.applied = false")
    int markApplied(@Param("id") Long id, @Param("now") LocalDateTime now);
}
