package com.teagather.analysis.repository;

import com.teagather.analysis.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByMemberIdOrderByRankAsc(Long memberId);

    @Modifying
    @Query("delete from Recommendation r")
    void deleteAllInBulk();
}
