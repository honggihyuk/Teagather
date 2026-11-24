package com.teagather.product.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.dto.ProductCard;
import com.teagather.product.dto.ProductForm;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.Discount;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.DiscountRepository;
import com.teagather.product.repository.OptionGroupRepository;
import com.teagather.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

// 메뉴 조회(고객 화면) + 관리(관리자)
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final DiscountRepository discountRepository;
    private final OrderItemRepository orderItemRepository;
    private final DiscountService discountService;

    public Product get(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("메뉴를 찾을 수 없어요."));
    }

    // ===== 고객 화면 =====

    // 고객에게 보이는 메뉴 (판매 중 + 품절), 카테고리 필터
    @Transactional(readOnly = true)
    public List<ProductCard> visibleCards(Long categoryId) {
        Map<Long, Discount> disc = discountService.activeMap(LocalDate.now());
        return productRepository.findByStatusIn(List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT)).stream()
                .filter(p -> categoryId == null || p.getCategory().getId().equals(categoryId))
                .map(p -> new ProductCard(p, disc.get(p.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductCard card(Long productId) {
        Product p = get(productId);
        if (!p.getStatus().isVisible()) throw new EntityNotFoundException("지금은 판매하지 않는 메뉴예요.");
        return new ProductCard(p, discountService.activeMap(LocalDate.now()).get(p.getId()));
    }

    // 오늘의 메뉴
    @Transactional(readOnly = true)
    public List<ProductCard> todayCards() {
        Map<Long, Discount> disc = discountService.activeMap(LocalDate.now());
        return visibleCards(null).stream().filter(c -> c.getProduct().isTodayMenu())
                .map(c -> new ProductCard(c.getProduct(), disc.get(c.getProduct().getId()))).toList();
    }

    // 오늘의 할인 메뉴
    @Transactional(readOnly = true)
    public List<ProductCard> discountCards() {
        return visibleCards(null).stream().filter(ProductCard::isDiscounted).toList();
    }

    // 신메뉴 (출시 30일 안)
    @Transactional(readOnly = true)
    public List<ProductCard> newCards() {
        return visibleCards(null).stream().filter(c -> c.getProduct().isFresh())
                .sorted(Comparator.comparing((ProductCard c) -> c.getProduct().getLaunchedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // 상품 id 순서대로 카드 (추천·인기 메뉴)
    @Transactional(readOnly = true)
    public List<ProductCard> cardsByIds(List<Long> ids, Map<Long, String> reasons) {
        Map<Long, Discount> disc = discountService.activeMap(LocalDate.now());
        Map<Long, Product> byId = new HashMap<>();
        productRepository.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
        List<ProductCard> out = new ArrayList<>();
        for (Long id : ids) {
            Product p = byId.get(id);
            if (p != null && p.getStatus().isVisible()) {
                out.add(new ProductCard(p, disc.get(id), reasons == null ? null : reasons.get(id)));
            }
        }
        return out;
    }

    // ===== 관리자 =====

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return productRepository.findAllOrdered();
    }

    public ProductForm toForm(Product p) {
        ProductForm f = new ProductForm();
        f.setName(p.getName());
        f.setCategoryId(p.getCategory() == null ? null : p.getCategory().getId());
        f.setPrice(p.getPrice());
        f.setCost(p.getCost());
        f.setDescription(p.getDescription());
        f.setTags(p.getTags());
        f.setStatus(p.getStatus());
        f.setTodayMenu(p.isTodayMenu());
        f.setNewMenu(p.isNewMenu());
        f.setSortOrder(p.getSortOrder());
        f.setOptionGroupIds(p.getOptionGroups().stream().map(g -> g.getId()).toList());
        return f;
    }

    @Transactional
    public Product save(Long id, ProductForm f) {
        if (f.getCost() > f.getPrice()) throw new BusinessException("원가가 판매가보다 클 수 없어요.");
        Category c = categoryRepository.findById(f.getCategoryId())
                .orElseThrow(() -> new BusinessException("카테고리를 골라 주세요."));
        Product p = (id == null) ? Product.builder().created(LocalDateTime.now()).build() : get(id);
        boolean becameNew = f.isNewMenu() && !p.isNewMenu();
        p.setName(f.getName().trim());
        p.setCategory(c);
        p.setPrice(f.getPrice());
        p.setCost(f.getCost());
        p.setDescription(f.getDescription());
        p.setTags(normalizeTags(f.getTags()));
        p.setStatus(f.getStatus());
        p.setTodayMenu(f.isTodayMenu());
        p.setNewMenu(f.isNewMenu());
        p.setSortOrder(f.getSortOrder());
        if ((becameNew || p.getLaunchedAt() == null) && f.getStatus() == ProductStatus.ON_SALE) {
            p.setLaunchedAt(LocalDate.now());
        }
        p.getOptionGroups().clear();
        if (f.getOptionGroupIds() != null) p.getOptionGroups().addAll(optionGroupRepository.findAllById(f.getOptionGroupIds()));
        return productRepository.save(p);
    }

    // 판매 중 ↔ 품절
    @Transactional
    public Product toggleSoldOut(Long id) {
        Product p = get(id);
        if (p.getStatus() == ProductStatus.ON_SALE) p.setStatus(ProductStatus.SOLD_OUT);
        else if (p.getStatus() == ProductStatus.SOLD_OUT) p.setStatus(ProductStatus.ON_SALE);
        else throw new BusinessException("판매 중이거나 품절인 메뉴만 바꿀 수 있어요.");
        return p;
    }

    // 지우기 : 주문 이력이 있으면 숨김 처리만 (true = 지움, false = 숨김)
    @Transactional
    public boolean delete(Long id) {
        Product p = get(id);
        if (orderItemRepository.existsByProductId(id)) {
            p.setStatus(ProductStatus.HIDDEN);
            p.setTodayMenu(false);
            return false;
        }
        discountRepository.deleteByProductId(id);
        productRepository.delete(p);
        return true;
    }

    // 오늘의 메뉴 표시
    @Transactional
    public void setTodayMenu(Long id, boolean on) {
        get(id).setTodayMenu(on);
    }

    @Transactional
    public int clearTodayMenus() {
        List<Product> list = productRepository.findByTodayMenuTrue();
        list.forEach(p -> p.setTodayMenu(false));
        return list.size();
    }

    // 신메뉴 출시 : 후보 → 판매 중 + 신메뉴 표시
    @Transactional
    public Product launch(Long id) {
        Product p = get(id);
        p.setStatus(ProductStatus.ON_SALE);
        p.setNewMenu(true);
        p.setLaunchedAt(LocalDate.now());
        return p;
    }

    private static String normalizeTags(String tags) {
        if (tags == null) return null;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : tags.replace('#', ' ').split("[,\\s]+")) {
            if (!t.isBlank()) set.add(t.trim());
        }
        return set.isEmpty() ? null : String.join(",", set);
    }
}
