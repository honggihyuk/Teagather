package com.teagather.admin.controller;

import com.teagather.product.constant.ProductStatus;
import com.teagather.product.dto.ProductForm;
import com.teagather.product.entity.Product;
import com.teagather.product.service.CatalogAdminService;
import com.teagather.product.service.DiscountService;
import com.teagather.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

// 관리자 : 상품 (메뉴)
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/products")
public class AdminProductController {

    private final ProductService productService;
    private final CatalogAdminService catalogAdminService;
    private final DiscountService discountService;

    @GetMapping
    public String list(@RequestParam(value = "category", required = false) Long category,
                       @RequestParam(value = "status", required = false) ProductStatus status, Model model) {
        List<Product> list = productService.findAll().stream()
                .filter(p -> category == null || p.getCategory().getId().equals(category))
                .filter(p -> status == null || p.getStatus() == status)
                .toList();
        list.forEach(p -> p.getOptionGroups().size());
        model.addAttribute("products", list);
        model.addAttribute("categories", catalogAdminService.categories());
        model.addAttribute("category", category);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("discounts", discountService.activeMap(LocalDate.now()));
        return "admin/products";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        ProductForm f = new ProductForm();
        model.addAttribute("productForm", f);
        return form(model, null);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable("id") Long id, Model model) {
        model.addAttribute("productForm", productService.toForm(productService.get(id)));
        return form(model, id);
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("productForm") ProductForm f, BindingResult br, Model model, RedirectAttributes ra) {
        if (br.hasErrors()) return form(model, null);
        Product p = productService.save(null, f);
        ra.addFlashAttribute("adminMessage", p.getName() + " 을(를) 등록했어요.");
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable("id") Long id, @Valid @ModelAttribute("productForm") ProductForm f, BindingResult br,
                       Model model, RedirectAttributes ra) {
        if (br.hasErrors()) return form(model, id);
        Product p = productService.save(id, f);
        ra.addFlashAttribute("adminMessage", p.getName() + " 을(를) 저장했어요.");
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/status")
    public String toggleStatus(@PathVariable("id") Long id, RedirectAttributes ra) {
        Product p = productService.toggleSoldOut(id);
        ra.addFlashAttribute("adminMessage", p.getName() + " → " + p.getStatus().getLabel());
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") Long id, RedirectAttributes ra) {
        String name = productService.get(id).getName();
        boolean deleted = productService.delete(id);
        ra.addFlashAttribute("adminMessage", deleted ? name + " 을(를) 지웠어요." : name + " 은(는) 주문 기록이 있어 숨김 처리했어요.");
        return "redirect:/admin/products";
    }

    private String form(Model model, Long id) {
        model.addAttribute("productId", id);
        model.addAttribute("categories", catalogAdminService.categories());
        model.addAttribute("optionGroups", catalogAdminService.optionGroups());
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("tagHints", List.of("쌉쌀", "고소", "달콤", "상큼", "우유", "초코", "과일", "차", "디카페인", "시즌", "디저트"));
        return "admin/product-form";
    }
}
