package com.teagather.product.repository;

import com.teagather.product.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DiscountRepository extends JpaRepository<Discount, Long> {

    // 오늘 걸려 있는 할인 (사용 중)
    @Query("select d from Discount d join fetch d.product p where d.active = true and d.startDate <= :day and d.endDate >= :day")
    List<Discount> findOn(@Param("day") LocalDate day);

    @Query("select d from Discount d join fetch d.product p order by d.startDate desc, d.id desc")
    List<Discount> findAllWithProduct();

    // 분석 입력 : 기간이 걸친 할인 (과거 포함)
    @Query("select d from Discount d join fetch d.product p where d.endDate >= :from")
    List<Discount> findEndingAfter(@Param("from") LocalDate from);

    void deleteByProductId(Long productId);
}
