package com.teagather.product.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.OptionGroup;
import com.teagather.product.entity.OptionItem;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.OptionGroupRepository;
import com.teagather.product.repository.OptionItemRepository;
import com.teagather.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 관리자 : 카테고리 · 옵션 그룹 관리
@Service
@RequiredArgsConstructor
public class CatalogAdminService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final OptionItemRepository optionItemRepository;

    // ===== 카테고리 =====

    @Transactional(readOnly = true)
    public List<Category> categories() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    // 카테고리 → 상품 수
    @Transactional(readOnly = true)
    public Map<Category, Long> categoriesWithCount() {
        Map<Category, Long> map = new LinkedHashMap<>();
        for (Category c : categories()) map.put(c, productRepository.countByCategoryId(c.getId()));
        return map;
    }

    @Transactional
    public Category saveCategory(Long id, String name, int sortOrder, String tone, String icon) {
        if (name == null || name.isBlank()) throw new BusinessException("카테고리 이름을 입력해 주세요.");
        String n = name.trim();
        if (n.length() > 20) throw new BusinessException("카테고리 이름은 20자까지예요.");
        categoryRepository.findByName(n).ifPresent(other -> {
            if (!other.getId().equals(id)) throw new BusinessException("같은 이름의 카테고리가 있어요.");
        });
        Category c = (id == null) ? new Category() : categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("카테고리를 찾을 수 없어요."));
        c.setName(n);
        c.setSortOrder(sortOrder);
        if (tone != null && !tone.isBlank()) c.setTone(tone);
        if (icon != null && !icon.isBlank()) c.setIcon(icon);
        return categoryRepository.save(c);
    }

    @Transactional
    public void deleteCategory(Long id) {
        if (productRepository.countByCategoryId(id) > 0) throw new BusinessException("메뉴가 있는 카테고리는 지울 수 없어요. 메뉴를 먼저 옮겨 주세요.");
        categoryRepository.deleteById(id);
    }

    // ===== 옵션 =====

    @Transactional(readOnly = true)
    public List<OptionGroup> optionGroups() {
        List<OptionGroup> list = optionGroupRepository.findAllByOrderBySortOrderAscIdAsc();
        list.forEach(g -> g.getItems().size());   // 화면에서 옵션 목록을 쓰므로 미리 읽는다
        return list;
    }

    @Transactional
    public OptionGroup addGroup(String name, boolean required, boolean multiple) {
        if (name == null || name.isBlank()) throw new BusinessException("옵션 그룹 이름을 입력해 주세요.");
        OptionGroup g = OptionGroup.builder().name(name.trim()).required(required).multiple(multiple)
                .sortOrder((int) optionGroupRepository.count() + 1).build();
        return optionGroupRepository.save(g);
    }

    @Transactional
    public void deleteGroup(Long id) {
        OptionGroup g = optionGroupRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("옵션 그룹을 찾을 수 없어요."));
        for (Product p : productRepository.findAll()) p.getOptionGroups().remove(g);   // 상품 연결 해제
        optionGroupRepository.delete(g);
    }

    @Transactional
    public OptionItem addItem(Long groupId, String name, int extraPrice) {
        OptionGroup g = optionGroupRepository.findById(groupId).orElseThrow(() -> new EntityNotFoundException("옵션 그룹을 찾을 수 없어요."));
        if (name == null || name.isBlank()) throw new BusinessException("옵션 이름을 입력해 주세요.");
        if (extraPrice < 0 || extraPrice > 5000) throw new BusinessException("추가 금액은 0~5,000원이에요.");
        OptionItem it = OptionItem.builder().group(g).name(name.trim()).extraPrice(extraPrice).sortOrder(g.getItems().size() + 1).build();
        g.getItems().add(it);
        return optionItemRepository.save(it);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        OptionItem it = optionItemRepository.findById(itemId).orElseThrow(() -> new EntityNotFoundException("옵션을 찾을 수 없어요."));
        it.getGroup().getItems().remove(it);
    }
}
