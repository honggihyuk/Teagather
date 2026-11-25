package com.teagather.analysis;

import com.teagather.analysis.constant.AnalysisStatus;
import com.teagather.analysis.constant.InsightKind;
import com.teagather.analysis.entity.AnalysisRun;
import com.teagather.analysis.entity.MenuInsight;
import com.teagather.analysis.repository.AnalysisRunRepository;
import com.teagather.analysis.repository.MenuInsightRepository;
import com.teagather.analysis.service.AnalysisService;
import com.teagather.common.exception.BusinessException;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.DiscountRepository;
import com.teagather.product.repository.ProductRepository;
import com.teagather.product.service.DiscountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

// 분석 방어 로직 : 추천 중복 적용 · 멈춘 RUNNING 정리
// 커밋 시점 동작을 봐야 해서 테스트 트랜잭션을 쓰지 않는다 (데이터는 @AfterEach 에서 직접 지운다)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analysis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000",
        "app.seed.enabled=false",
        "app.analysis.run-on-startup=false",
        "app.analysis.timeout-seconds=60"
})
class AnalysisDefenseTest {

    @Autowired AnalysisService analysisService;
    @Autowired AnalysisRunRepository runRepository;
    @Autowired MenuInsightRepository insightRepository;
    @Autowired DiscountRepository discountRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;

    @MockitoSpyBean DiscountService discountService;

    @AfterEach
    void cleanUp() {
        discountRepository.deleteAll();
        insightRepository.deleteAll();
        runRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    void 같은_할인추천을_동시에_두번_적용해도_할인은_하나만_생긴다() throws Exception {
        Long insightId = discountInsight();

        // 할인 생성 직전에서 두 요청이 서로를 기다리게 해 경합을 확실히 만든다.
        // 방어 로직이 있으면 두 번째 요청은 여기까지 오지 못하므로 2초 뒤 혼자 진행한다.
        CountDownLatch bothInCreate = new CountDownLatch(2);
        doAnswer(inv -> {
            bothInCreate.countDown();
            bothInCreate.await(2, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(discountService).create(anyLong(), anyString(), any(), anyInt(), any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            results.add(pool.submit(() -> {
                start.await();
                return analysisService.apply(insightId);
            }));
        }
        start.countDown();

        int ok = 0, rejected = 0;
        for (Future<String> f : results) {
            try {
                f.get(20, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                rejected++;
            }
        }
        pool.shutdownNow();

        assertThat(discountRepository.count()).as("할인은 한 건만").isEqualTo(1);
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(insightRepository.findById(insightId).orElseThrow().isApplied()).isTrue();
    }

    @Test
    void 서버를_시작하면_JVM_시작_전에_남은_RUNNING_만_실패로_정리된다() {
        LocalDateTime jvmStart = analysisService.jvmStartedAt();
        Long old = runningRun(jvmStart.minusHours(2));
        Long justBefore = runningRun(jvmStart.minusSeconds(5));
        Long noStart = runningRun(null);
        // 서버가 요청을 받기 시작한 뒤(JVM 시작 이후) 관리자가 막 시작한 분석 : 정리하면 안 된다
        Long startedByAdmin = runningRun(LocalDateTime.now());
        Long done = runRepository.save(AnalysisRun.builder().startedAt(LocalDateTime.now().minusDays(1))
                .finishedAt(LocalDateTime.now().minusDays(1)).status(AnalysisStatus.SUCCESS).triggeredBy("test").build()).getId();

        int n = analysisService.recoverStaleRuns(true);

        assertThat(n).isEqualTo(3);
        assertFailed(old);
        assertFailed(justBefore);
        assertFailed(noStart);
        assertThat(runRepository.findById(startedByAdmin).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(runRepository.findById(done).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
    }

    @Test
    void 실행_중에는_제한시간을_넘긴_RUNNING_만_정리한다() {
        Long old = runningRun(LocalDateTime.now().minusHours(2));
        Long recent = runningRun(LocalDateTime.now().minusSeconds(5));

        int n = analysisService.recoverStaleRuns(false);

        assertThat(n).isEqualTo(1);
        assertFailed(old);
        assertThat(runRepository.findById(recent).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.RUNNING);
    }

    @Test
    void 다른_곳에서_돌고_있는_분석이_있으면_새로_시작하지_않는다() {
        runningRun(LocalDateTime.now().minusSeconds(5));
        long before = runRepository.count();

        Long id = analysisService.run("test");

        assertThat(id).isNull();
        assertThat(runRepository.count()).isEqualTo(before);
    }

    // ===== 준비 =====

    private Long discountInsight() {
        Category c = categoryRepository.save(Category.builder().name("테스트").sortOrder(1).build());
        Product p = productRepository.save(Product.builder().name("테스트 라떼").category(c).price(5000).cost(1500)
                .status(ProductStatus.ON_SALE).created(LocalDateTime.now()).build());
        AnalysisRun run = runRepository.save(AnalysisRun.builder().startedAt(LocalDateTime.now())
                .finishedAt(LocalDateTime.now()).status(AnalysisStatus.SUCCESS).triggeredBy("test").build());
        return insightRepository.save(MenuInsight.builder().run(run).product(p).kind(InsightKind.DISCOUNT)
                .rank(1).score(0.8).forecast(10).suggestedRate(10).reason("테스트").build()).getId();
    }

    private Long runningRun(LocalDateTime startedAt) {
        return runRepository.save(AnalysisRun.builder().startedAt(startedAt)
                .status(AnalysisStatus.RUNNING).triggeredBy("test").build()).getId();
    }

    private void assertFailed(Long id) {
        AnalysisRun r = runRepository.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(r.getFinishedAt()).isNotNull();
        assertThat(r.getMessage()).isNotBlank();
    }
}
