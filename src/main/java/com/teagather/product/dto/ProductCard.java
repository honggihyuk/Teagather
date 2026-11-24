package com.teagather.product.dto;

import com.teagather.product.entity.Discount;
import com.teagather.product.entity.Product;
import lombok.Getter;

// 고객 화면의 메뉴 카드 : 메뉴 + 오늘 걸린 할인 (없으면 null)
@Getter
public class ProductCard {

    private final Product product;
    private final Discount discount;
    private final String reason;        // 추천 이유 등 한 줄 (선택)

    public ProductCard(Product product, Discount discount) {
        this(product, discount, null);
    }

    public ProductCard(Product product, Discount discount, String reason) {
        this.product = product;
        this.discount = discount;
        this.reason = reason;
    }

    public int getPrice() {
        return product.getPrice();
    }

    public int getDiscountAmount() {
        return discount == null ? 0 : discount.amountFor(product.getPrice());
    }

    public int getSalePrice() {
        return product.getPrice() - getDiscountAmount();
    }

    public boolean isDiscounted() {
        return getDiscountAmount() > 0;
    }

    // "-15%" (정액 할인도 % 로 보여 준다)
    public String getDiscountBadge() {
        if (!isDiscounted()) return "";
        return "-" + Math.round(getDiscountAmount() * 100.0 / product.getPrice()) + "%";
    }
}
