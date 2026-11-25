package com.teagather.shop.controller;

import com.teagather.common.exception.BusinessException;
import com.teagather.member.entity.Member;
import com.teagather.member.service.MemberService;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.service.OrderPlacementFacade;
import com.teagather.order.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.UUID;

// 주문서 · 내 주문
//  주문 실패는 어떤 경우든 주문서(/order)로 돌려보내고 errorMessage 토스트로 알린다 (장바구니는 그대로)
@Slf4j
@Controller
@RequiredArgsConstructor
public class OrderController {

    private final Cart cart;
    private final OrderService orderService;
    private final MemberService memberService;
    private final OrderPlacementFacade placementFacade;

    @GetMapping("/order")
    public String checkout(Model model) {
        if (cart.isEmpty()) return "redirect:/cart";
        model.addAttribute("cart", cart);
        model.addAttribute("orderToken", cart.issueToken());
        model.addAttribute("pickupTypes", PickupType.values());
        return "shop/checkout";
    }

    @PostMapping("/order")
    public String place(@RequestParam(value = "pickupType", defaultValue = "TAKEOUT") PickupType pickupType,
                        @RequestParam(value = "request", required = false) String request,
                        @RequestParam(value = "orderToken", required = false) String token,
                        Principal principal, RedirectAttributes ra) {
        if (request != null && request.length() > 200) {
            ra.addFlashAttribute("errorMessage", "요청사항은 200자까지예요.");
            return "redirect:/order";
        }
        // 1) 토큰을 원자적으로 쓴다 : 동시에 두 번 보내도 한쪽만 통과
        if (!cart.useToken(token)) {
            Long placed = cart.placedOrderIdFor(token);
            if (placed != null) {
                // 이미 접수된 주문서를 다시 보낸 진짜 중복 제출 → 그 주문으로 안내
                ra.addFlashAttribute("shopMessage", "이 주문서는 접수가 끝났어요.");
                return "redirect:/orders/" + placed;
            }
            if (cart.isInFlight(token)) {
                // 더블클릭 : 첫 요청이 아직 처리 중 → 결과가 곧 나오는 주문 내역으로 안내 (주문서로 보내면 빈 장바구니만 보인다)
                ra.addFlashAttribute("shopMessage", "주문을 처리하고 있어요. 주문 내역에서 확인해 주세요.");
                return "redirect:/orders";
            }
            // 새로 고친 뒤의 옛 주문서 등 : 주문은 없으니 주문서를 다시 보여 준다
            ra.addFlashAttribute("errorMessage", "주문서가 새로 고쳐졌어요. 내용을 확인하고 다시 주문해 주세요.");
            return "redirect:/order";
        }
        CustomerOrder o;
        try {
            // 2) 시도마다 새 트랜잭션 + 주문번호 충돌만 재시도
            o = placementFacade.place(principal.getName(), cart, pickupType, request);
        } catch (BusinessException e) {
            // 업무 오류(품절·재시도 소진 등) : 토큰을 되돌리고 장바구니는 그대로, 주문서로 돌아간다
            cart.restoreToken(token);
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/order";
        } catch (RuntimeException e) {
            // 예상 못한 오류도 같은 방식 : 원문(SQL·제약 이름)은 로그에만, 화면에는 요청 번호만
            cart.restoreToken(token);
            String rid = UUID.randomUUID().toString().substring(0, 8);
            log.error("=======> 주문 실패 [{}] {}", rid, e.getClass().getSimpleName(), e);
            ra.addFlashAttribute("errorMessage", "주문을 처리하지 못했어요. 장바구니는 그대로예요. 다시 시도해 주세요. (요청 번호 " + rid + ")");
            return "redirect:/order";
        }
        // 3) 커밋이 끝난 뒤에만 장바구니를 비운다 (실패하면 장바구니가 남는다)
        cart.markPlaced(token, o.getId());
        ra.addFlashAttribute("shopMessage", "주문을 받았어요. 주문번호 " + o.getOrderNo());
        return "redirect:/orders/" + o.getId() + "?done";
    }

    @GetMapping("/orders")
    public String myOrders(Principal principal, Model model) {
        model.addAttribute("orders", orderService.myOrders(memberService.getByUsername(principal.getName())));
        return "shop/orders";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable("id") Long id, @RequestParam(value = "done", required = false) String done,
                         Principal principal, Model model) {
        Member me = memberService.getByUsername(principal.getName());
        CustomerOrder o = orderService.get(id);
        if (o.getMember() == null || !o.getMember().getId().equals(me.getId())) throw new EntityNotFoundException("주문을 찾을 수 없어요.");
        model.addAttribute("order", o);
        model.addAttribute("done", done != null);
        model.addAttribute("steps", new OrderStatus[]{OrderStatus.PLACED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.COMPLETED});
        return "shop/order-detail";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable("id") Long id, Principal principal, RedirectAttributes ra) {
        orderService.cancelByCustomer(id, memberService.getByUsername(principal.getName()));
        ra.addFlashAttribute("shopMessage", "주문을 취소했어요.");
        return "redirect:/orders/" + id;
    }
}
