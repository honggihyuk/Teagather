package com.teagather.admin.controller;

import com.teagather.product.constant.DiscountSource;
import com.teagather.product.constant.DiscountType;
import com.teagather.product.entity.Discount;
import com.teagather.product.service.CatalogAdminService;
import com.teagather.product.service.DiscountService;
import com.teagather.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

// 관리자 : 카테고리 · 옵션 · 할인
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminCatalogController {

    private final CatalogAdminService catalogAdminService;
    private final DiscountService discountService;
    private final ProductService productService;

    // ===== 카테고리 =====
    @GetMapping("/categories")
    public String categories(Model model) {
        model.addAttribute("categories", catalogAdminService.categoriesWithCount());
        model.addAttribute("tones", List.of("brown", "green", "cream", "orange", "pink", "yellow"));
        model.addAttribute("icons", List.of("cup", "tea", "latte", "ade", "cake", "bread"));
        return "admin/categories";
    }

    @PostMapping("/categories")
    public String addCategory(@RequestParam("name") String name, @RequestParam(value = "sortOrder", defaultValue = "0") int sortOrder,
                              @RequestParam(value = "tone", required = false) String tone, @RequestParam(value = "icon", required = false) String icon,
                              RedirectAttributes ra) {
        catalogAdminService.saveCategory(null, name, sortOrder, tone, icon);
        ra.addFlashAttribute("adminMessage", "카테고리를 추가했어요.");
        return "redirect:/admin/categories";
    }

    @PostMapping("/categories/{id}")
    public String editCategory(@PathVariable("id") Long id, @RequestParam("name") String name,
                               @RequestParam(value = "sortOrder", defaultValue = "0") int sortOrder,
                               @RequestParam(value = "tone", required = false) String tone, @RequestParam(value = "icon", required = false) String icon,
                               RedirectAttributes ra) {
        catalogAdminService.saveCategory(id, name, sortOrder, tone, icon);
        ra.addFlashAttribute("adminMessage", "카테고리를 저장했어요.");
        return "redirect:/admin/categories";
    }

    @PostMapping("/categories/{id}/delete")
    public String deleteCategory(@PathVariable("id") Long id, RedirectAttributes ra) {
        catalogAdminService.deleteCategory(id);
        ra.addFlashAttribute("adminMessage", "카테고리를 지웠어요.");
        return "redirect:/admin/categories";
    }

    // ===== 옵션 =====
    @GetMapping("/options")
    public String options(Model model) {
        model.addAttribute("groups", catalogAdminService.optionGroups());
        return "admin/options";
    }

    @PostMapping("/options")
    public String addGroup(@RequestParam("name") String name, @RequestParam(value = "required", defaultValue = "false") boolean required,
                           @RequestParam(value = "multiple", defaultValue = "false") boolean multiple, RedirectAttributes ra) {
        catalogAdminService.addGroup(name, required, multiple);
        ra.addFlashAttribute("adminMessage", "옵션 그룹을 추가했어요.");
        return "redirect:/admin/options";
    }

    @PostMapping("/options/{id}/delete")
    public String deleteGroup(@PathVariable("id") Long id, RedirectAttributes ra) {
        catalogAdminService.deleteGroup(id);
        ra.addFlashAttribute("adminMessage", "옵션 그룹을 지웠어요.");
        return "redirect:/admin/options";
    }

    @PostMapping("/options/{id}/items")
    public String addItem(@PathVariable("id") Long id, @RequestParam("name") String name,
                          @RequestParam(value = "extraPrice", defaultValue = "0") int extraPrice, RedirectAttributes ra) {
        catalogAdminService.addItem(id, name, extraPrice);
        ra.addFlashAttribute("adminMessage", "옵션을 추가했어요.");
        return "redirect:/admin/options";
    }

    @PostMapping("/options/items/{itemId}/delete")
    public String deleteItem(@PathVariable("itemId") Long itemId, RedirectAttributes ra) {
        catalogAdminService.deleteItem(itemId);
        ra.addFlashAttribute("adminMessage", "옵션을 지웠어요.");
        return "redirect:/admin/options";
    }

    // ===== 할인 =====
    @GetMapping("/discounts")
    public String discounts(@RequestParam(value = "state", defaultValue = "ACTIVE") String state, Model model) {
        LocalDate today = LocalDate.now();
        List<Discount> all = discountService.findAll();
        model.addAttribute("discounts", all.stream().filter(d -> "ALL".equals(state) || d.stateCode(today).equals(state)).toList());
        model.addAttribute("state", state);
        model.addAttribute("today", today);
        model.addAttribute("counts", java.util.Map.of(
                "ACTIVE", all.stream().filter(d -> d.stateCode(today).equals("ACTIVE")).count(),
                "SCHEDULED", all.stream().filter(d -> d.stateCode(today).equals("SCHEDULED")).count(),
                "ENDED", all.stream().filter(d -> d.stateCode(today).equals("ENDED")).count(),
                "PAUSED", all.stream().filter(d -> d.stateCode(today).equals("PAUSED")).count(),
                "ALL", (long) all.size()));
        model.addAttribute("products", productService.findAll().stream().filter(p -> p.getStatus().isVisible()).toList());
        model.addAttribute("types", DiscountType.values());
        return "admin/discounts";
    }

    @PostMapping("/discounts")
    public String addDiscount(@RequestParam("productId") Long productId, @RequestParam(value = "name", required = false) String name,
                              @RequestParam("type") DiscountType type, @RequestParam("value") int value,
                              @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                              @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                              RedirectAttributes ra) {
        Discount d = discountService.create(productId, name, type, value, start, end, DiscountSource.MANUAL);
        ra.addFlashAttribute("adminMessage", d.getProduct().getName() + " 할인을 만들었어요.");
        return "redirect:/admin/discounts?state=" + d.stateCode(LocalDate.now());
    }

    @PostMapping("/discounts/{id}/toggle")
    public String toggleDiscount(@PathVariable("id") Long id, @RequestParam(value = "state", defaultValue = "ACTIVE") String state, RedirectAttributes ra) {
        Discount d = discountService.toggle(id);
        ra.addFlashAttribute("adminMessage", d.isActive() ? "할인을 다시 켰어요." : "할인을 멈췄어요.");
        return "redirect:/admin/discounts?state=" + safeState(state);
    }

    @PostMapping("/discounts/{id}/delete")
    public String deleteDiscount(@PathVariable("id") Long id, @RequestParam(value = "state", defaultValue = "ACTIVE") String state, RedirectAttributes ra) {
        discountService.delete(id);
        ra.addFlashAttribute("adminMessage", "할인을 지웠어요.");
        return "redirect:/admin/discounts?state=" + safeState(state);
    }

    private static String safeState(String s) {
        return List.of("ACTIVE", "SCHEDULED", "ENDED", "PAUSED", "ALL").contains(s) ? s : "ACTIVE";
    }
}
