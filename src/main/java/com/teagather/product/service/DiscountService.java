package com.teagather.product.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.product.constant.DiscountSource;
import com.teagather.product.constant.DiscountType;
import com.teagather.product.entity.Discount;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.DiscountRepository;
import com.teagather.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountRepository discountRepository;
    private final ProductRepository productRepository;

    // 오늘 메뉴별로 적용할 할인 : 겹치면 할인 금액이 큰 하나
    @Transactional(readOnly = true)
    public Map<Long, Discount> activeMap(LocalDate day) {
        Map<Long, Discount> map = new HashMap<>();
        for (Discount d : discountRepository.findOn(day)) {
            Long pid = d.getProduct().getId();
            Discount cur = map.get(pid);
            int price = d.getProduct().getPrice();
            if (cur == null || d.amountFor(price) > cur.amountFor(price)) map.put(pid, d);
        }
        return map;
    }

    @Transactional(readOnly = true)
    public List<Discount> findAll() {
        return discountRepository.findAllWithProduct();
    }

    @Transactional
    public Discount create(Long productId, String name, DiscountType type, int value,
                           LocalDate start, LocalDate end, DiscountSource source) {
        Product p = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("메뉴를 찾을 수 없어요."));
        if (type == null) throw new BusinessException("할인 방식을 골라 주세요.");
        if (type == DiscountType.RATE && (value < 1 || value > 90)) throw new BusinessException("할인율은 1~90% 예요.");
        if (type == DiscountType.AMOUNT && (value < 10 || value > p.getPrice())) throw new BusinessException("할인 금액은 10원 ~ 메뉴 가격이에요.");
        if (start == null || end == null) throw new BusinessException("기간을 입력해 주세요.");
        if (end.isBefore(start)) throw new BusinessException("끝나는 날은 시작일과 같거나 뒤여야 해요.");
        String n = (name == null || name.isBlank()) ? p.getName() + " 할인" : name.trim();
        Discount d = Discount.builder()
                .name(n.length() > 40 ? n.substring(0, 40) : n)
                .product(p).type(type).value(value)
                .startDate(start).endDate(end)
                .active(true).source(source)
                .created(LocalDateTime.now())
                .build();
        return discountRepository.save(d);
    }

    @Transactional
    public Discount toggle(Long id) {
        Discount d = discountRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("할인을 찾을 수 없어요."));
        d.setActive(!d.isActive());
        return d;
    }

    @Transactional
    public void delete(Long id) {
        discountRepository.deleteById(id);
    }
}
