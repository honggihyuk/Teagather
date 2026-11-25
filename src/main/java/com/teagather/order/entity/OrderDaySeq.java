package com.teagather.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// 주문번호 일자별 카운터 (250101-0001 의 뒤 네 자리)
//  - 하루에 한 줄. 주문 트랜잭션이 이 줄을 +1 하면서 행 잠금을 잡으므로 같은 날 주문번호는 겹치지 않는다
//  - 주문이 롤백되면 +1 도 같이 롤백되어 번호가 비지 않는다
@Entity
@Table(name = "order_day_seq")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderDaySeq {

    @Id
    @Column(name = "seq_day")
    private LocalDate seqDay;

    @Column(name = "last_no", nullable = false)
    private int lastNo;
}
