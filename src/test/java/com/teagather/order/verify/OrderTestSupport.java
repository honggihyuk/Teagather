package com.teagather.order.verify;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.order.service.OrderService;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 주문 롤백 검증 테스트 공통 준비물
//  - 테스트 클래스에 @Transactional 을 붙이지 않는다 (테스트 트랜잭션이 커밋 시점 실패를 가린다)
//  - 그래서 데이터는 테스트 전후로 직접 지운다
abstract class OrderTestSupport {

    @Autowired protected OrderService orderService;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;
    @Autowired protected MemberRepository memberRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected CategoryRepository categoryRepository;
    @Autowired protected PlatformTransactionManager txManager;

    protected Product product;

    @BeforeEach
    void prepareCatalog() {
        cleanUp();
        Category c = categoryRepository.save(Category.builder().name("검증-" + UUID.randomUUID().toString().substring(0, 8))
                .sortOrder(1).tone("brown").icon("cup").build());
        product = productRepository.save(Product.builder().name("검증 아메리카노").category(c)
                .price(4500).cost(1500).status(ProductStatus.ON_SALE).sortOrder(1)
                .created(LocalDateTime.now()).build());
    }

    @AfterEach
    void cleanUpAfter() {
        cleanUp();
    }

    // 주문 → 고객 → 메뉴 → 카테고리 순으로 지운다 (관리자 계정은 남긴다)
    protected void cleanUp() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            orderItemRepository.deleteAllInBatch();
            orderRepository.deleteAllInBatch();
            memberRepository.deleteAll(memberRepository.findByRole(MemberRole.CUSTOMER));
            productRepository.deleteAll();
            categoryRepository.deleteAll();
        });
    }

    protected Member newCustomer(String username) {
        return memberRepository.save(Member.builder().username(username).password("{noop}1234").name(username)
                .role(MemberRole.CUSTOMER).created(LocalDateTime.now()).build());
    }

    protected Cart cartWithOneLine() {
        Cart cart = new Cart();
        cart.add(orderService.makeLine(product.getId(), List.of(), 1));
        return cart;
    }

    protected Member reload(Member m) {
        return memberRepository.findById(m.getId()).orElseThrow();
    }

    // 회원 집계를 실제 주문(취소 제외)에서 다시 계산한 값
    protected record Expected(int orderCount, long totalSpent, int point) {}

    protected Expected recompute(Member m) {
        List<CustomerOrder> valid = orderRepository.findAll().stream()
                .filter(o -> o.getMember() != null && o.getMember().getId().equals(m.getId()))
                .filter(o -> o.getStatus() != OrderStatus.CANCELED)
                .toList();
        return new Expected(valid.size(),
                valid.stream().mapToLong(CustomerOrder::getPayAmount).sum(),
                valid.stream().mapToInt(CustomerOrder::getEarnedPoint).sum());
    }
}
