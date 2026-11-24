package com.teagather.order.cart;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

// 장바구니 한 줄 (메뉴 + 고른 옵션 + 수량). 가격은 주문할 때 DB 기준으로 다시 계산한다.
@Getter
@Setter
@AllArgsConstructor
public class CartLine implements Serializable {

    private Long productId;
    private String productName;
    private List<Long> optionIds;
    private String optionText;
    private int unitPrice;              // 담을 때 가격 (메뉴 + 옵션, 할인 전)
    private int unitDiscount;           // 담을 때 할인 (메뉴 1개당)
    private int quantity;
    private String tone;
    private String icon;

    public int getLineAmount() {
        return (unitPrice - unitDiscount) * quantity;
    }

    public boolean sameChoice(Long productId, List<Long> optionIds) {
        return this.productId.equals(productId) && this.optionIds.equals(optionIds);
    }
}
