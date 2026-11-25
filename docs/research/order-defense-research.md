# 주문 생성 방어 로직 & 롤백 검증 — 리서치 결과

- 작성일: 2026-11-25
- 브랜치: `feature/order-defense`
- 범위: 예상치 못한 오류에서 안전하게 동작하는 방어 로직 설계 → 롤백 검증 테스트
- 방식: 9개 섹터를 동시에 리서치한 뒤 통합 (각 섹터 요약은 부록 A)

---

## 1. 처음 분석과 달라진 점

| 처음 생각 | 실제 |
|---|---|
| 주문번호 충돌은 커밋 시점에 터진다 | **`save()` 호출 순간에 바로 터진다.** `CustomerOrder`가 `IDENTITY` 방식이라 INSERT가 즉시 실행된다 (섹터 2·3 확인, 섹터 4의 설명은 틀림) |
| 충돌하면 장바구니가 사라진다 | 지금 코드에서는 예외가 `cart.clear()`(`OrderService.java:99`)보다 먼저 나서 **장바구니는 남는다.** 실제 피해는 다른 곳에 있다 |
| (몰랐던 문제) | **주문서 토큰을 주문 처리 전에 폐기한다** (`OrderController.java:43`). 주문이 실패한 뒤 다시 제출하면 주문이 없는데도 "이미 처리한 주문서예요"라고 안내한다. 지금 당장 사용자가 겪는 결함 |
| (몰랐던 문제) | 토큰 확인(`Cart.useToken`)에 동기화가 없어서, 같은 세션에서 동시에 제출하면 **주문이 2건** 생길 수 있다 |
| (몰랐던 문제) | 회원 집계 갱신이 `open-in-view: true` 설정에 의존한다. 이 설정을 끄면 `applyToMember`의 변경이 오류 없이 사라진다 |

**끝까지 파고든 예외 하나의 연쇄**

동시 주문 → 같은 주문번호 발급 → `save()`에서 `DataIntegrityViolationException` 발생 → DB는 정상 롤백 → **세션에 있는 토큰은 롤백되지 않음** → 고객은 다시 주문할 수 없고 잘못된 안내를 받음

> 원칙: **세션 상태는 DB 트랜잭션 밖에 있다.**

---

## 2. 최종 결정안 (섹터 간 충돌 정리)

| 쟁점 | 결정 | 근거 |
|---|---|---|
| 주문번호 발급 | **일자별 카운터 테이블을 원자적 upsert로 갱신 + `order_no` unique 제약 유지 + 트랜잭션 바깥에서 2~3회 재시도** | 없는 행에 `SELECT … FOR UPDATE`를 하면 MySQL REPEATABLE READ에서 gap lock 때문에 데드락이 난다. DB 시퀀스는 MySQL에 없다 |
| 회원 집계 | `@Version` 대신 **원자적 UPDATE** (`point = GREATEST(0, point + :d)` 등) | 충돌이 생기지 않아 재시도가 필요 없다. `ddl-auto: update`로 version 컬럼을 추가하면 기존 행이 NULL로 남는 문제도 피한다. `@Version`은 관리자가 직접 수정하는 화면에만 쓴다 |
| AI 추천 적용 | **조건부 UPDATE** (`WHERE id=? AND applied=false`, 바뀐 행이 0건이면 "이미 적용됨") | 재시도하지 않는다. 할인 생성과 같은 트랜잭션이라 함께 롤백된다 |
| 재시도 도구 | **Spring Framework 7 내장 `@Retryable` / `RetryTemplate`**을 별도 Facade 빈에 둔다 | advice 순서는 Retry(바깥) → Transaction(안쪽)이어야 한다. 서비스 안에서 catch하면 rollback-only 때문에 `UnexpectedRollbackException`이 나고, 자기 호출이면 프록시를 거치지 않는다. spring-retry는 아카이브됐다 |
| 토큰과 장바구니 | **주문이 성공한 뒤에만** 토큰을 폐기하고 장바구니를 비운다. 실패하면 토큰을 다시 발급한다. 토큰 확인은 원자적으로 처리한다 | 재시도 2회차에 장바구니가 비어 있으면 안 되므로 재시도 도입의 선행 조건이다 |
| 예외 분류 | `GlobalExceptionHandler`에 DB 예외 처리기를 추가한다. 재시도가 다 실패하면 `/checkout`으로 보내고 "장바구니는 그대로예요" flash를 띄운다. 그 외에는 500 + 요청 번호 | 제약 이름, SQL, 테이블명이 화면에 노출되지 않게 막는다 |
| Fail-fast / Fail-safe | 주문은 fail-fast(완전 커밋 아니면 완전 롤백), 분석은 fail-safe(Java 대체 계산, 직전 SUCCESS 결과 유지) | 돈·포인트에는 부분 성공을 허용하지 않는다 |

---

## 3. 롤백 검증 테스트 계획 (보정본)

섹터 7의 R1 계획에는 "장바구니 유지 단언이 현재 코드에서 실패(red)해야 한다"는 전제가 있었다. 하지만 1번 표대로 현재는 장바구니가 남으므로 이 단언은 **통과한다.** 그래서 먼저 실패해야 하는(red) 테스트를 다시 잡았다.

| 순서 | 테스트 | 검증 내용 | 현재 코드 |
|---|---|---|---|
| 1 | `OrderControllerRollbackTest` (MockMvc + MockHttpSession) | 주문번호 충돌을 주입한 뒤 같은 토큰으로 다시 제출하면 "이미 처리한 주문서"가 나오지 않아야 함. 장바구니 유지, 안내 메시지 확인 | **red (1순위)** |
| 2 | `OrderNoConcurrencyTest` | 50건 동시 주문 (`newFixedThreadPool(16)` + `CountDownLatch`): 실패 0건, `orderNo` 중복 0건, 저장 건수 = N | red |
| 3 | 회원 집계 동시성 테스트 | 같은 회원으로 주문과 취소를 동시에 실행 → 포인트, 누적금액, 주문 수가 기대값과 같음 | red |
| 4 | `OrderPlaceRollbackTest` | 정합성 4가지 확인 (아래) | 회귀 방지 |
| 5 | MySQL IT (Testcontainers, 선택) | 1~4를 InnoDB에서 다시 실행 (데드락·gap lock은 H2로 재현되지 않음) | — |

**롤백을 증명하는 정합성 4가지**
1. 주문·주문상품 행 수가 그대로
2. 회원 `point`, `totalSpent`, `orderCount`를 **DB에서 다시 읽어** 원상태 확인
3. 장바구니와 주문서 토큰이 보존됨
4. 사용자 메시지와 HTTP 상태 코드

**테스트 작성 규칙**
- 테스트 클래스에 `@Transactional`을 붙이지 않는다. 테스트 트랜잭션은 끝나면 롤백되기 때문에 커밋 실패가 가려지고, 다른 스레드의 작업은 커밋되어 버린다.
- `@SpringBootTest` + 테스트 전용 프로필(`jdbc:h2:mem:`, 시드 최소화)을 쓰고, 테스트가 끝나면 데이터를 직접 정리한다(`deleteAll` 또는 `@Sql(executionPhase = AFTER_TEST_METHOD)`).
- 장애 주입은 `@MockitoSpyBean`으로 한다. `applyToMember`는 static이라 직접 스파이할 수 없으므로 저장소를 스파이한다.
- 세션 빈은 세션 속성 `scopedTarget.cart`에 저장되므로, 같은 `MockHttpSession`을 재사용하면 같은 장바구니가 유지된다.
- 수정 전 커밋에서 red 결과(실패 건수)를 먼저 기록한다. 이 숫자가 성과의 before 값이 된다.

---

## 4. 운영 준비

- **기능 플래그:** `app.order.safe-place.enabled`(기본 true), `app.order.safe-place.max-retries`(기본 2). false로 두면 재시도 없이 한 번만 시도한다. 안정화되면 플래그를 삭제한다. 플래그를 영구히 남기면 그것도 운영 부채다.
- **지표:** Actuator + Micrometer. `/actuator/health`, `/actuator/metrics`만 노출하고 ADMIN 권한으로 제한한다.

  | 이름 | 종류 | 태그 |
  |---|---|---|
  | `order.place.attempt` | Counter | `pickup` |
  | `order.place.conflict` | Counter | `cause=order_no\|lock` |
  | `order.place.retry` | Counter | `attempt` |
  | `order.place.failed` | Counter | `reason` |
  | `order.place` | Timer (Observation) | `outcome` |
  | `analysis.run.stale` | Counter | — |

  주문번호나 회원 ID처럼 값이 끝없이 늘어나는 항목은 태그로 쓰지 않고 로그로 보낸다.
- **로그:** MDC에 `attemptId`, `memberId`, `orderNo`, `retryAttempt`, `analysisRunId`를 넣는다. 재시도는 WARN, 최종 실패는 ERROR(스택 포함)로 남긴다. `@Async` 분석 스레드는 `TaskDecorator`로 MDC를 복사해야 한다.
- **스키마:** 이번 범위에서는 `ddl-auto: update`를 유지하고 카운터 테이블은 새로 만든다. **Flyway 도입은 이번 범위에서 제외하고 후속 작업으로 둔다**(부록 섹터 8 참고).
- **정합성 점검 SQL**(부록 섹터 8)로 수정 전후 불일치 건수를 기록한다.
- **별도로 할 일:** `application-mysql.yml`의 평문 root 비밀번호를 환경변수로 옮긴다.

---

## 5. 진행 순서

1. red 테스트 1~3 작성 → 실패를 수치로 기록 (before)
2. 토큰·장바구니 처리를 성공 후로 이동, 토큰 확인을 원자적으로 변경
3. 회원 집계를 원자적 UPDATE로 변경
4. 일자별 카운터 테이블 + 재시도 Facade
5. 예외 처리기 추가
6. 테스트 통과 확인 (가능하면 MySQL에서도 검증)
7. 지표와 정합성 SQL로 before/after 비교
8. ADR-0001과 포트폴리오 서술 완성 (부록 섹터 9 초안에 숫자 채우기)
9. 같은 원칙을 AI 추천 적용과 분석 RUNNING 고착에 적용

---

## 6. 쓰기 전에 공식 문서로 확인할 것

- [ ] `@MockitoSpyBean` 패키지명 (`org.springframework.test.context.bean.override.mockito` 추정)
- [ ] Testcontainers 2.x 아티팩트명·패키지명 (`org.testcontainers.mysql.MySQLContainer` 추정, 제3자 자료)
- [ ] Flyway `baseline-on-migrate`의 Boot 4 속성명
- [ ] `@Retryable` 속성명 (`includes`, `maxRetries`, `delay`, `jitter`, `multiplier`) — 7.0과 7.1 차이
- [ ] H2 `MODE=MySQL`에서 `INSERT … ON DUPLICATE KEY UPDATE` 지원 여부
- [ ] 섹터 2가 인용한 URL (에이전트가 직접 열어 확인하지 않음)
- [ ] `@ControllerAdvice` 처리기에서 `RedirectAttributes` flash가 실제로 전달되는지 (대안: `RequestContextUtils.getOutputFlashMap`)

---

## 7. 구현 섹터 분할

4개 섹터를 별도 worktree(`../Teagather-wt/<섹터>`, 브랜치 `feature/od-<섹터>`)에서 동시에 구현한 뒤 `feature/order-defense`로 합친다. **파일 소유권이 겹치지 않게** 나누었다. 공통 설정(`build.gradle`의 actuator, `application.yml`의 `management.*`, `app.order.safe-place.*`)은 기준 커밋에 미리 넣었다.

| 섹터 | 내용 | 소유 파일 |
|---|---|---|
| **1. 주문 데이터 계층** | 일자별 카운터 기반 주문번호 발급, 회원 집계 원자적 UPDATE, `place()` 안의 `cart.clear()` 제거 (시그니처 `place(Member, Cart, PickupType, String)`는 유지) | `OrderService`, `MemberRepository`, `OrderRepository`, 새 `OrderDaySeq` 엔티티와 리포지토리 |
| **2. 주문 웹 계층** | 원자적 토큰 확인, 성공 후 토큰 폐기와 장바구니 비우기, 실패 시 토큰 재발급 + `/checkout` 리다이렉트, `OrderPlacementFacade`(주문번호 충돌만 새 트랜잭션으로 재시도, 지표, MDC), 예외 분류, `/actuator/**` ADMIN 제한 | `Cart`, `OrderController`, 새 `OrderPlacementFacade`, `GlobalExceptionHandler`, `SecurityConfig` |
| **3. 분석 방어** | `AnalysisService.apply` 조건부 업데이트, 서버 시작 시 RUNNING 고착 행 정리, Python 강제 종료 시 자식 프로세스까지 종료 | `AnalysisService`, `AnalysisLauncher`(또는 새 정리 컴포넌트), `PythonAnalysisRunner`, `MenuInsightRepository`, `AnalysisRunRepository` |
| **4. 롤백 검증 테스트** | 3번 계획의 테스트를 red-first로 작성. 기준 커밋에서 실패 수치를 기록하고, 합친 뒤 통과를 확인 | `src/test/**`, `src/test/resources/application-test.yml` |

**제외(후속):** Flyway 도입, Testcontainers MySQL IT(Docker 필요), ADR 문서 작성.

---

## 부록 A. 섹터별 핵심 결론

### 섹터 1. 장애 모델링 (FMEA)

전제
- `open-in-view: true` (`application.yml`): 컨트롤러가 조회한 `Member`가 `place` 트랜잭션 안에서 dirty checking 대상이 된다. OSIV를 끄면 `applyToMember` 변경이 조용히 유실된다.
- `Cart`는 `@SessionScope`(`order/cart/Cart.java:15`)라서 DB 롤백과 상관없이 동작한다.
- 토큰은 `place` 호출 **전에** 폐기된다(`OrderController.java:43`, `Cart.java:61-64`).
- 분류되지 않은 예외는 `GlobalExceptionHandler.unknown`(`:51`)으로 가서 500 화면 + 요청 번호를 보여 준다.

A. 주문 `OrderService.place` (`order/service/OrderService.java:71-101`). S=심각도, D=탐지난이도(1~5, 높을수록 발견이 어려움)

| # | 단계 | 실패 모드 | DB | 장바구니(세션) | 사용자 화면 | S | D |
|---|---|---|---|---|---|---|---|
| 1 | 토큰 확인 `Controller:43` | 같은 세션에서 동시 요청 2건이 모두 통과 (`useToken`이 동기화되어 있지 않음) | 주문 2건 생성 가능 | 비워짐 | 정상 | 4 | 4 |
| 2 | 검증 `:73`, `Controller:47` | 빈 장바구니, 요청사항 200자 초과 | 변화 없음 | 유지, **토큰은 이미 소모됨** | 400 메시지. 다시 제출하면 "이미 처리한 주문서"가 뜨고 `/orders`로 이동 | 2 | 2 |
| 3 | 가격 재계산 `makeLine` `:83`(`:44-67`) | 담은 뒤 품절·숨김(`:46`), 옵션 삭제(`:57`), 할인 종료 | 롤백 | 유지, 토큰 소모 | 400. 어느 줄이 문제인지 표시되지 않음 | 2 | 2 |
| 4 | 번호 발급 `nextOrderNo` `:123-132` | 동시 주문에서 `count`→`exists` 경합으로 **같은 번호** 발급 | 아직 쓰기 전 | - | - (7번에서 터짐) | 4 | 5 |
| 5 | `save` `:97` | IDENTITY 키라서 즉시 INSERT. 이 시점에 unique 위반이 먼저 날 수도 있음 | 롤백 | 아직 유지 | 500 | 4 | 3 |
| 6 | `applyToMember` `:98`(`:113-121`) | `@Version`이 없어서 동시 주문·취소 시 **lost update** | 커밋은 됨, 포인트·누적금액 불일치 | 비워짐 | 정상처럼 보임 | 4 | **5** |
| 7 | `cart.clear()` `:99` | 커밋보다 **먼저** 실행됨 | - | **비워짐** | - | - | - |
| 8 | 커밋·flush (`:101` 이후 프록시) | 4·5번의 unique 위반이 `DataIntegrityViolationException`으로 터지고 서비스 바깥에서 발생 | 전체 롤백 (주문 0건) | **이미 비워짐, 복구 불가** | 500 "잠시 문제가 생겼어요". 장바구니는 비었고 주문 내역도 없음 | **5** | 4 |
| 9 | 리다이렉트 이후 | 8번 뒤에 뒤로가기로 다시 제출 | - | 토큰이 없어 거절 | "이미 처리한 주문서예요" 안내가 **사실과 다름** | 3 | 4 |

> 보정: 섹터 2·3 확인 결과 IDENTITY 때문에 unique 위반은 5번(`save`)에서 터진다. 따라서 현재 코드에서 8번의 "장바구니 소실"은 일어나지 않고, 2·9번의 토큰 문제가 실제 피해다. 8번은 커밋 시점 예외(예: `@Version` 도입 후)가 생기면 현실화되는 잠재 위험이다.

B. 분석 `AnalysisService`

| 대상 | 실패 모드 | 결과 | S | D |
|---|---|---|---|---|
| `apply` `AnalysisService.java:272-296` | 확인(`:274`)과 갱신(`:293`) 사이에 잠금이 없어 더블클릭·관리자 2명 동시 적용 | 할인 **2건** 생성(`:280`). 오늘의 메뉴·출시는 멱등이라 영향 적음 | 4 | 4 |
| `apply` 중간 예외 | `discountService.create` 이후 예외 | 같은 트랜잭션이라 함께 롤백. 정상 | 1 | 1 |
| `run` 고착 `:65`, `:72-115` | 실행 중 JVM 종료, 재시작 | RUNNING 행이 영구히 남아 화면에 계속 "실행 중". 사람이 정리해야 함 | 3 | 3 |
| `run` 다중 인스턴스 | `AtomicBoolean`이 JVM마다 따로 있음 | 동시에 2번 실행, `deleteAllInBulk`(`:176`)가 서로의 추천을 지움 | 3 | 5 |
| `run` 저장 실패 | `save` 예외 | 결과 롤백, FAILED 기록(`:100-109`). 정상 | 1 | 1 |
| Python 지연 | 타임아웃 `PythonAnalysisRunner.java:72` | 강제 종료 후 Java 대체 계산. 정상 | 1 | 1 |

우선순위(S×D): 토큰 문제·장바구니 연쇄 → 회원 집계 lost update → 추천 중복 할인 → 토큰 경합 → 분석 고착·다중 실행

### 섹터 2. 트랜잭션·롤백 의미론
- 런타임 예외는 롤백, 체크 예외는 커밋. `DataIntegrityViolationException`·`BusinessException`이면 `place()` 전체(주문, 주문상품, 회원 집계)가 롤백된다.
- IDENTITY 엔티티는 `persist` 순간 INSERT가 실행되고 JDBC 배치에서도 제외된다(`batch_size: 200`이 주문에는 효과 없음). 따라서 unique 위반은 `save()`에서 즉시 발생한다. 반면 dirty checking 변경(회원 집계)은 커밋 직전 flush에서 실행된다.
- `SimpleJpaRepository.save`도 `@Transactional`이라 예외가 그 경계를 지나면 공유 트랜잭션이 rollback-only가 된다. 같은 트랜잭션 안에서 잡고 계속 진행하면 `UnexpectedRollbackException`. Hibernate도 예외가 난 Session을 재사용하지 말라고 한다.
- 자기 호출은 프록시를 거치지 않는다. 재시도는 다른 빈에 둔다.
- 커밋 후 작업: `TransactionSynchronization.afterCommit` 또는 `@TransactionalEventListener(AFTER_COMMIT)`. 가장 단순한 방법은 컨트롤러에서 `place()`가 정상 반환된 뒤 처리하는 것이다(반환 = 커밋 완료).
- `REQUIRES_NEW`는 커넥션을 하나 더 잡는다. 주문 전체 재시도라면 "바깥 트랜잭션 없음 + 시도마다 `place()` 새로 호출"이 깔끔하다.
- 출처 (에이전트가 직접 열어 확인하지 않음):
  - https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html
  - https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
  - https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
  - https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
  - https://docs.spring.io/spring-framework/reference/core/resilience.html
  - https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html

### 섹터 3. 동시성·멱등성
- 주문번호 전략 비교

  | 전략 | H2 (`MODE=MySQL`) | MySQL InnoDB | 장점 | 단점 |
  |---|---|---|---|---|
  | 현재 (count + exists) | READ COMMITTED, 같은 번호 발생 | 스냅샷 읽기, 같은 번호 발생 | 단순 | 경쟁 상태 확정 |
  | DB 시퀀스 | 지원 | **SEQUENCE 없음** | 잠금 없음 | 이식 불가, 일자별 리셋 어려움 |
  | 카운터 테이블 + `PESSIMISTIC_WRITE` | 지원 | 행 잠금 직렬화 | 결정적, 연속 번호 | 그날 첫 행에서 gap lock 데드락 |
  | unique 위반 시 재시도 | 동작 | 동작 | 변경 최소 | 바깥 트랜잭션 필요, 부하 시 재시도 몰림 |
  | `날짜-{id}` | 동작 | 동작 | 충돌 0 | 연속 번호 아님 |

- MySQL 함정: REPEATABLE READ에서 없는 행에 `SELECT … FOR UPDATE`를 하면 gap lock끼리 서로 막지 않아 둘 다 INSERT하다 데드락. 원자적 upsert(`INSERT … ON DUPLICATE KEY UPDATE last_no = last_no + 1`)나 첫 행 별도 생성 + 중복 무시로 해결.
- 회원 집계: 카운터 성격은 원자적 UPDATE, 사람이 수정하는 엔티티는 `@Version`.
- H2의 `MODE=MySQL`은 문법 호환일 뿐 잠금 동작은 흉내 내지 않는다.
- 토큰: `useToken`을 동기화하거나 `AtomicReference.compareAndSet`. 세션을 넘는 중복은 `orders.idempotency_key` unique 검토.
- 출처:
  - https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html
  - https://h2database.github.io/html/advanced.html
  - https://h2database.github.io/javadoc/org/h2/engine/IsolationLevel.html
  - https://docs.spring.io/spring-framework/docs/6.1.0/javadoc-api/org/springframework/orm/jpa/JpaOptimisticLockingFailureException.html
  - https://docs.hibernate.org/orm/5.0/userguide/html_single/chapters/locking/Locking.html
  - https://www.baeldung.com/java-jpa-transaction-locks
  - https://vaadin.com/docs/v25/building-apps/forms-data/consistency/optimistic-locking

### 섹터 4. 방어 패턴
- Spring Framework 7 내장 resilience: `@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods`, `org.springframework.core.retry`의 `RetryTemplate`/`RetryPolicy`. 기본 `maxRetries=3`(재시도 횟수만 셈, spring-retry의 `maxAttempts`와 다름), 간격 1초. spring-retry는 아카이브. Resilience4j의 Boot 4 정식 아티팩트는 미확인.
- 재시도 대상: `order_no` unique 위반(제약명·SQLState로 판별), `OptimisticLockingFailureException`, `PessimisticLockingFailureException`, `CannotAcquireLockException`. 재시도하지 않음: `BusinessException`, `EntityNotFoundException`, 그 외 무결성 위반(버그).
- 분석 고착: `ApplicationReadyEvent`에서 `RUNNING && startedAt < now - (timeout + 여유)`인 행을 FAILED("서버 재시작으로 중단")로 바꾼다. `AnalysisLauncher.onReady`보다 먼저 실행되어야 한다. 다중 인스턴스는 ShedLock이나 조건부 INSERT/UPDATE.
- `PythonAnalysisRunner`: 강제 종료 시 `p.descendants().forEach(ProcessHandle::destroyForcibly)`도 호출하고, 종료 후 `waitFor`로 실제 종료를 확인한다(Windows 파일 잠금). 실행 ID별 작업 폴더가 더 안전하다.
- 출처:
  - https://docs.spring.io/spring-framework/reference/7.0/core/resilience.html
  - https://docs.spring.io/spring-framework/reference/7.1/core/resilience.html
  - https://docs.spring.io/spring-framework/docs/7.0.0/javadoc-api/org/springframework/resilience/annotation/EnableResilientMethods.html
  - https://spring.io/blog/2025/09/09/core-spring-resilience-features/
  - https://github.com/spring-attic/spring-retry
  - https://spring.io/blog/2025/07/17/spring-framework-7-0-0-M7-available-now
  - https://github.com/resilience4j/resilience4j/issues/2371

### 섹터 5. 예외 처리 아키텍처·사용자 경험
- 현재: 업무 오류는 400 + `e.getMessage()`, 나머지는 500 + 요청 번호 + ERROR 로그. 스택은 로그에만 남아 정보 노출 측면은 좋다. 다만 `DataAccessException` 계열 처리기가 없어 재시도 가능한 충돌과 버그를 구분할 수 없다. `IllegalArgumentException` 메시지를 그대로 노출하는 것도 위험하다.
- Spring 예외 계층: `TransientDataAccessException`(재시도 가능: `ConcurrencyFailureException` 이하, `QueryTimeoutException`), `NonTransientDataAccessException`(`DataIntegrityViolationException`, `DuplicateKeyException`). 단 주문번호 unique 충돌은 NonTransient지만 도메인상 재시도 대상이다.

  | 예외 | 사용자 메시지 | 이동 | HTTP | 로그 |
  |---|---|---|---|---|
  | `BusinessException` | 도메인 메시지 그대로 | 원래 화면 + flash | 303 (POST) | INFO |
  | `DataIntegrityViolationException` (주문번호, 재시도 소진 후) | "주문이 몰려 접수하지 못했어요. 장바구니는 그대로예요." | `/checkout` | 리다이렉트 / 409 | WARN + 요청번호 |
  | `OptimisticLockingFailureException` | "다른 곳에서 먼저 변경됐어요. 다시 확인해 주세요." | 원래 화면 | 리다이렉트 / 409 | WARN |
  | `CannotAcquireLockException`, `QueryTimeoutException` | "잠시 후 다시 시도해 주세요." | 원래 화면 | 503 | WARN |
  | `TransactionSystemException`, `UnexpectedRollbackException`, 기타 | 요청번호 안내 | `message.html` | 500 | ERROR + 스택 |
  | `IllegalArgumentException` | "요청 값이 올바르지 않아요." (고정) | `message.html` | 400 | INFO |

- 규칙: 기술 예외 → 업무 메시지 번역은 서비스 경계 한 곳에서. 화면에 보여도 되는 메시지는 `BusinessException`뿐. 요청번호는 MDC로.
- ProblemDetail(RFC 9457)은 JSON 엔드포인트가 생길 때 검토.

### 섹터 6. 관측성
- `build.gradle`에 Actuator가 없었다(이번 기준 커밋에서 추가). `/actuator/**`는 ADMIN으로 제한.
- 구조화 로그: `logging.structured.format.console=ecs`(Boot 3.4+), ECS/Logstash 형식은 MDC 값을 JSON 필드로 자동 포함.
- before/after 측정: 서비스 계층 동시성 테스트(100건 × 5회)에 `SimpleMeterRegistry`를 주입해 카운터를 직접 assert. HTTP 부하(k6/JMeter)는 보조. 결과표: 시나리오 × (attempt, conflict, retry, failed, p95, 정합성 위반) × before/after.
- 출처:
  - https://docs.spring.io/spring-boot/reference/features/logging.html
  - https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4/
  - https://docs.spring.io/spring-boot/api/java/org/springframework/boot/logging/structured/package-summary.html
  - https://docs.spring.io/spring-boot/appendix/application-properties/index.html
  - https://docs.spring.io/spring-boot/reference/actuator/observability.html
  - https://docs.micrometer.io/micrometer/reference/observation/introduction.html
  - https://docs.micrometer.io/micrometer/reference/observation/components.html

### 섹터 7. 롤백 검증 테스트
- `@Transactional`/`@DataJpaTest` 테스트는 끝나면 롤백해서 커밋 시점 실패가 재현되지 않는다. 테스트 레벨 전파는 `NOT_SUPPORTED`/`NEVER`만 가능. 별도 스레드 작업은 테스트 트랜잭션과 무관하게 커밋된다. `TestTransaction.end()`로 중간 커밋 경계를 만들 수 있다.
- `@MockitoBean`/`@MockitoSpyBean`이 Spring 6.2부터 `@MockBean`/`@SpyBean`을 대체. 스파이는 AOP 프록시 안쪽에 위치.
- Testcontainers: `spring-boot-testcontainers` + static `@Container` + `@ServiceConnection`. 이미지 버전 고정(`mysql:8.4`).
- 테스트 환경: 기본 DB가 파일 H2이고 `DataInitializer`가 고객을 시드하므로 테스트 전용 프로필(`jdbc:h2:mem:`)이 필요. `@Column(unique=true)`가 DDL에 반영됐는지 첫 테스트에서 확인.
- 출처:
  - https://docs.spring.io/spring/reference/7.1/testing/testcontext-framework/tx.html
  - https://docs.spring.io/spring/reference/testing/annotations/integration-spring/annotation-mockitobean.html
  - https://docs.spring.io/spring-framework/reference/7.0/testing/testcontext-framework/bean-overriding.html
  - https://www.baeldung.com/spring-mockitospybean
  - https://www.baeldung.com/spring-test-programmatic-transactions
  - https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html
  - https://www.issoh.co.jp/md/tech/4510 (제3자, 미검증)

### 섹터 8. 배포·운영 롤백
- 현재 `ddl-auto: update`만 있고 마이그레이션 도구가 없다. `update`는 컬럼 추가만 하고 기본값을 채우지 않아, `@Version`을 그대로 추가하면 기존 행 version이 NULL로 남는다.
- 기능 플래그: `@ConfigurationProperties` record로 서비스 안에서 분기. `@ConditionalOnProperty`는 기동 시점 선택이라 바꾸려면 재시작이 필요하다. 플래그로 되돌릴 수 있는 건 코드뿐이다.
- Flyway(후속): Boot 4는 `spring-boot-starter-flyway`, MySQL은 `flyway-mysql` 추가. 현재 스키마를 `V1__baseline.sql`로 덤프 → baseline 설정 → `ddl-auto: validate`. Community에는 undo가 없으므로(Teams 이상 유료) 롤 포워드, 배포 전 백업(H2 `SCRIPT TO`, `mysqldump`), expand-contract로 대체한다.
- Expand-contract: V2에서 컬럼·카운터 테이블 추가(구버전 호환) → 새 코드 배포 후 플래그 on → 안정화 뒤 V3에서 기존 로직·플래그 제거.
- 정합성 점검 쿼리 (원문 그대로):

```sql
-- ① 주문번호 중복 (unique 제약이 빠진 DB 대비)
SELECT order_no, COUNT(*) FROM orders GROUP BY order_no HAVING COUNT(*) > 1;
-- ② 번호 없는 주문
SELECT id FROM orders WHERE order_no IS NULL;
-- ③ 회원 집계 vs 실제 (취소 제외)
SELECT m.id, m.order_count, a.cnt, m.total_spent, a.spent, m.point, a.pt
FROM member m
LEFT JOIN (SELECT member_id, COUNT(*) cnt, SUM(pay_amount) spent, SUM(earned_point) pt
           FROM orders WHERE status <> 'CANCELED' GROUP BY member_id) a ON a.member_id = m.id
WHERE m.role = 'CUSTOMER'
  AND (m.order_count <> COALESCE(a.cnt,0) OR m.total_spent <> COALESCE(a.spent,0)
       OR m.point <> COALESCE(a.pt,0));
-- ④ 주문상품 없는 주문 (부분 저장 흔적)
SELECT o.id FROM orders o LEFT JOIN order_item i ON i.order_id = o.id WHERE i.id IS NULL;
```

  - 포인트 사용(2단계)이 생기면 ③은 맞지 않게 된다. 그 전에 포인트 원장 테이블을 둔다.
  - 시드가 `applyToMember`를 거쳤는지 확인해야 첫 실행 오탐을 피한다.
  - 보정은 조회 → 리포트 → 수동 승인 → 보정 순서. 자동 UPDATE 금지.
- 출처:
  - https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html
  - https://docs.spring.io/spring-boot/4.0/appendix/auto-configuration-classes/spring-boot-flyway.html
  - https://documentation.red-gate.com/fd/undo-migrations-273973334.html
  - https://documentation.red-gate.com/fd/reference/commands
  - https://productsupport.red-gate.com/hc/en-us/community/posts/24973588563101-Does-Flyway-Community-edition-supports-undo
  - https://www.red-gate.com/hub/product-learning/flyway/rollbacks-undos-and-undonts/
  - https://www.baeldung.com/flyway-roll-back

### 섹터 9. 성과 정리 (책임 → 판단 → 성과)
- 형식: Nygard ADR(제목·상태·맥락·결정·결과) + MADR의 Considered Options/Pros & Cons. 서술은 STAR, Result는 숫자로.
- ADR-0001 초안(`docs/decisions/0001-order-placement-consistency.md`, 후속 작성): 맥락(count+exists 경합, 토큰·장바구니 선소모, 회원 집계 lost update, 재현 수치 [__]), 결정 요인(주문 유실 0, 고객 상태 보존, 수동 보정 0, H2/MySQL 동일 동작, 복잡도), 대안 4개, 결정 = 2번 표, 결과(재시도 지연 p95 +[__]ms), 후속 ADR-0002(추천 적용 멱등성)·ADR-0003(고착 실행 정리).
- 측정 지표

  | 지표 | 측정 방법 |
  |---|---|
  | 동시 주문 실패율 | 100건 × 10회, 수정 전후 실패 건수 |
  | orderNo 중복·누락 | `GROUP BY order_no HAVING COUNT>1`, 연속성 확인 |
  | 롤백 정합성 4항목 | 장애 주입 후 통과율 |
  | 포인트 lost update | 주문·취소 동시 실행 후 기대값 대비 차이 |
  | 재시도 횟수·지연 | `order.place.retry`, Timer p95 |
  | H2 vs MySQL 차이 | 같은 테스트를 MySQL에서 실행 |
  | 운영 비용 대리 지표 | 수동 보정 필요 데이터 건수 → 0 |

- 포트폴리오 서술 뼈대
  1. 동시 주문 시 unique 위반 하나가 주문 실패·잘못된 안내·포인트 불일치로 번진다는 것을 코드 리뷰로 발견했다.
  2. 수정 전에 동시 [__]건 재현 테스트를 먼저 작성해 실패율 [__]%를 수치로 확인했다.
  3. 테스트 트랜잭션 자동 롤백이 커밋 시점 예외를 가린다는 점, H2와 MySQL의 잠금 동작 차이까지 파고들어 테스트 환경을 바로잡았다.
  4. 시퀀스·락·재시도·멱등성 키를 ADR로 비교하고 [__] 근거로 [__]를 선택했다.
  5. 실패율 [__]% → 0%, 롤백 정합성 4항목을 자동 테스트로 상시 보장해 수동 데이터 보정이 필요 없어졌다.
  6. 같은 원칙을 AI 추천 적용과 분석 실행 상태에도 확장했다.
- 원칙 한 줄: **"상태를 바꾸는 작업은 중복 실행해도 안전해야 하고, 실패하면 흔적 없이 되돌아가거나 스스로 정리돼야 한다."** 공통 체크리스트(멱등성, 트랜잭션 경계, 커밋 후 부수효과, 동시성 테스트, 측정 지표)를 `docs/`에 두고 PR 템플릿에 연결한다.
- 출처:
  - https://www.martinfowler.com/bliki/ArchitectureDecisionRecord.html
  - https://llm-coding.github.io/Semantic-Anchors/anchor/adr-according-to-nygard
  - https://adr.github.io/madr
  - https://github.com/adr/madr
  - https://ozimmer.ch/practices/2022/11/22/MADRTemplatePrimer.html
  - https://adr.github.io/adr-templates
  - https://techinterviewhandbook.org/star-format/
  - https://www.educative.io/answers/the-star-format
