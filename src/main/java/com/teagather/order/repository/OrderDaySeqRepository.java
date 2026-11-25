package com.teagather.order.repository;

import com.teagather.order.entity.OrderDaySeq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface OrderDaySeqRepository extends JpaRepository<OrderDaySeq, LocalDate> {

    // 그날 줄이 없으면 seed+1 로 만들고, 있으면 +1 (한 문장이라 원자적).
    //  - SELECT ... FOR UPDATE 로 없는 줄을 잠그면 MySQL 에서 gap lock 끼리 교착되므로 쓰지 않는다
    //  - H2 는 MODE=MySQL 에서 같은 문법을 지원한다
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into order_day_seq (seq_day, last_no) values (:day, :seed + 1) "
            + "on duplicate key update last_no = last_no + 1", nativeQuery = true)
    int increment(@Param("day") LocalDate day, @Param("seed") int seed);

    // 방금 올린 값 (같은 트랜잭션이 잠금을 쥐고 있으므로 다른 주문이 바꿀 수 없다)
    @Query(value = "select last_no from order_day_seq where seq_day = :day", nativeQuery = true)
    int currentNo(@Param("day") LocalDate day);
}
