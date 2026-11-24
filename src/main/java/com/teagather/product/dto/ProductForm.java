package com.teagather.product.dto;

import com.teagather.product.constant.ProductStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

// 관리자 : 상품 등록·수정 폼
@Getter
@Setter
public class ProductForm {

    @NotBlank(message = "메뉴 이름을 입력해 주세요.")
    @Size(max = 40, message = "이름은 40자까지예요.")
    private String name;

    @NotNull(message = "카테고리를 골라 주세요.")
    private Long categoryId;

    @Min(value = 100, message = "가격은 100원 이상이에요.")
    @Max(value = 100000, message = "가격은 10만 원까지예요.")
    private int price;

    @Min(value = 0, message = "원가는 0원 이상이에요.")
    private int cost;

    @Size(max = 300, message = "설명은 300자까지예요.")
    private String description;

    @Size(max = 200)
    private String tags;

    @NotNull(message = "상태를 골라 주세요.")
    private ProductStatus status = ProductStatus.ON_SALE;

    private boolean todayMenu;
    private boolean newMenu;
    private int sortOrder;

    private List<Long> optionGroupIds = new ArrayList<>();
}
