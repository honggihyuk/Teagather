# Teagather 기능 인벤토리

> 대상 : Spring Boot 4.1 / Thymeleaf / JPA — `com.teagather` · 분석 : Python 3.9 + PyTorch (`analytics/`)
> 목적 : 카페 온라인 스토어(고객 사이트 + 관리자)의 **화면·버튼·폼 필드·쿼리 파라미터·상태 전이·엔드포인트**를 한곳에 정리한다.
> 참고 : InhaWorks `docs/feature-inventory.md` 의 표기·구성을 따른다. 관리자 화면 구성은 POS 관리자 화면(고객·쿠폰·적립, 리뷰, 메뉴 연동)을 참고했다.
> 표기 : `GET`/`POST` = HTTP 메서드, `{id}` = 경로 변수, `?x=` = 쿼리 파라미터, **flash** = `RedirectAttributes.addFlashAttribute`.
> 모든 POST 폼은 `th:action` 으로 만들어 **CSRF hidden 토큰이 자동 삽입**된다.
> 상태 : ✅ 1차 구현 · 🕐 2차(설계만)

---

## 1. 역할 · 인증

### 1-1. 역할 (`MemberRole`)

| enum | 권한 문자열 | 라벨 | 의미 |
|---|---|---|---|
| `ADMIN` | `ROLE_ADMIN` | 관리자 | `/admin/**` 전체 (상품·주문·고객·분석·매출) |
| `CUSTOMER` | `ROLE_CUSTOMER` | 고객 | 장바구니·주문·내 주문·내 정보 |

- 고객은 **회원가입** 가능(`/member/signup`). 관리자 계정은 시드로만 만든다 (`app.admin.username/password`, 기본 admin / admin1234).
- 메뉴 보기(`/`, `/menu/**`)는 로그인 없이 가능. 장바구니에 담기부터 로그인 필요.
- 탈퇴·정지(`active=false`) 고객은 로그인 불가.

### 1-2. SecurityFilterChain 2개 (`config/SecurityConfig.java`)

| 체인 | 매칭 | 접근 규칙 | 로그인 | 로그아웃 |
|---|---|---|---|---|
| `@Order(1)` 관리자 | `/admin/**` | `/admin/login` permitAll, 나머지 `hasRole("ADMIN")` | `GET/POST /admin/login` → `/admin`, 실패 `?error`, 계정 종류 다르면 `?wrongPage` | `POST /admin/logout` → `/admin/login?logout` |
| `@Order(2)` 고객 | 그 외 | `/`, `/menu/**`, `/member/**`, 정적 파일 permitAll, 나머지 `authenticated()` | `GET/POST /member/login` → 이전 화면 또는 `/`, 실패 `?error` | `POST /member/logout` → `/` |

- 체인마다 로그인할 수 있는 계정 종류를 나눈다 (관리자 계정은 고객 로그인 불가, 반대도 마찬가지) — InhaWorks 와 같은 `loginManager(adminOnly)` 방식.

### 1-3. 공통 모델 / 오류 처리

| 구성요소 | 내용 |
|---|---|
| `GlobalModelAdvice` | 모든 화면에 `loginMember`, `currentUri`, `cartCount`(장바구니 수량) |
| `GlobalExceptionHandler` | `BusinessException`/`IllegalArgumentException` → 400 `message.html`, `EntityNotFoundException` → 404 |
| 레이아웃 | 고객 `layouts/shop.html`(상단 바), 관리자 `layouts/admin.html`(사이드바 + 반투명 상단바) |
| 토스트 | flash `shopMessage`, `adminMessage`, `errorMessage`(빨강) |

---

## 2. 디자인 규칙 (요약, 자세한 값은 `docs/design.md`)

- 흰 바탕 · 검은 글자(#0B0F19). InhaWorks 의 파랑 자리를 **로고 갈색 `#625139`** 가, 연한 파랑 자리를 **크림 `#F8F4EA`** 가 맡는다.
- 갈색은 **핵심 숫자**와 **주 버튼**에만. 크림은 선택된 메뉴·안내 박스·칩·진행 막대 같은 보조 요소에.
- 빨강은 취소·품절·이탈 위험처럼 확인이 필요한 것에만.
- 로고 : `static/images/logo-*.png` (가로형 `logo-horizontal.png`, 심볼 `logo-mark.png`). 파일이 없으면 `logo-mark.svg` 임시 로고.

---

## 3. 화면 목록

### 3-1. 고객 사이트

| # | URL (GET) | 템플릿 | 컨트롤러#메서드 | 모델 속성 | 목적 | 상태 |
|---|---|---|---|---|---|---|
| C1 | `/` | shop/home | ShopController#home | todayMenus, discountMenus, newMenus, recommended, recommendTitle, categories | 첫 화면 (4개 큐레이션) | ✅ |
| C2 | `/menu?category=` | shop/menu-list | ShopController#menu | categories, category, products | 전체 메뉴 (카테고리 탭) | ✅ |
| C3 | `/menu/{id}` | shop/menu-detail | ShopController#detail | p (ProductView), groups | 메뉴 상세 + 옵션 선택 + 담기 | ✅ |
| C4 | `/cart` | shop/cart | CartController#cart | cart | 장바구니 | ✅ |
| C5 | `/order` | shop/checkout | OrderController#checkout | cart, member | 주문서 (매장/포장, 요청사항) | ✅ |
| C6 | `/orders` | shop/orders | OrderController#myOrders | orders | 내 주문 내역 | ✅ |
| C7 | `/orders/{id}` | shop/order-detail | OrderController#detail | order | 주문 상세 / 주문 완료 | ✅ |
| C8 | `/member/login` | member/login | MemberController#login | (param error/logout/wrongPage) | 고객 로그인 | ✅ |
| C9 | `/member/signup` | member/signup | MemberController#signupForm | signupForm | 회원가입 | ✅ |
| C10 | `/me` | member/me | MeController#me | loginMember, recommended | 내 정보 · 등급 · 포인트 · 추천 | ✅ |

### 3-2. 관리자

| # | URL (GET) | 템플릿 | 컨트롤러#메서드 | 모델 속성 | 목적 | 상태 |
|---|---|---|---|---|---|---|
| A1 | `/admin/login` | admin/login | AdminController#login | (param) | 관리자 로그인 | ✅ |
| A2 | `/admin` | admin/dashboard | AdminDashboardController#dashboard | dash | 오늘 매출·주문·이번 주 매출·인기 메뉴·AI 추천 요약 | ✅ |
| A3 | `/admin/orders?status=` | admin/orders | AdminOrderController#list | orders, status, counts | 주문 관리 (상태 진행) | ✅ |
| A4 | `/admin/customers?q=&segment=` | admin/customers | AdminCustomerController#list | customers, segments, q, segment | 고객 정보 | ✅ |
| A5 | `/admin/customers/{id}` | admin/customer-detail | AdminCustomerController#detail | c, orders, recommendations, favorites | 고객 카드 | ✅ |
| A6 | `/admin/analysis/customers` | admin/analysis-customers | AdminAnalysisController#customers | run, segments, churnTop | 고객 분석 (PyTorch) | ✅ |
| A7 | `/admin/analysis/menus` | admin/analysis-menus | AdminAnalysisController#menus | run, picks(DISCOUNT/TODAY/NEW), metrics, runs | 메뉴 판매 분석 → 할인·오늘의 메뉴·신메뉴 후보 | ✅ |
| A8 | `/admin/products?category=&status=` | admin/products | AdminProductController#list | products, categories, statuses | 상품 목록 | ✅ |
| A9 | `/admin/products/new`, `/admin/products/{id}/edit` | admin/product-form | AdminProductController#form | productForm, categories, optionGroups, statuses | 상품 등록·수정 | ✅ |
| A10 | `/admin/categories` | admin/categories | AdminCategoryController#list | categories(+상품 수) | 카테고리 | ✅ |
| A11 | `/admin/options` | admin/options | AdminOptionController#list | groups | 옵션 그룹·옵션 | ✅ |
| A12 | `/admin/discounts?state=` | admin/discounts | AdminDiscountController#list | discounts, products, state | 할인 | ✅ |
| A13 | `/admin/sales?days=` | admin/sales | AdminSalesController#report | report (일별·카테고리별·메뉴별·시간대별) | 매출 리포트 | ✅ |
| A14 | `/admin/coupons` | – | – | – | 쿠폰 직접 발송 (홍보·시즌·날씨 템플릿, 발송 내역) | 🕐 |
| A15 | `/admin/points` | – | – | – | 포인트·스탬프 적립 규칙 | 🕐 (주문 시 1% 적립만 1차 구현) |
| A16 | `/admin/reviews` | – | – | – | 리뷰 모으기 (결제 시 리뷰 요청, 리뷰 보상) | 🕐 |

---

## 4. 화면별 상세

### 고객

#### C1 첫 화면 `/`
- 히어로 : 로고 · 한 줄 소개 · **메뉴 보러 가기** `/menu`.
- 섹션 4개 (가로 카드 줄, 비면 섹션 숨김) :
  1. **오늘의 메뉴** — `Product.todayMenu = true` (관리자가 AI 추천을 적용하거나 직접 지정). 카드에 "오늘의 메뉴" 칩.
  2. **오늘의 할인** — 오늘 날짜에 걸친 사용 중 `Discount`. 원래 가격 취소선 + 할인 가격(갈색) + "-15%" 칩.
  3. **신메뉴** — `Product.newMenu = true` 이고 출시 30일 안. "NEW" 칩.
  4. **추천 메뉴** — 로그인 고객 : 최근 분석의 `Recommendation`(PyTorch 행렬 분해) 상위 4개, 제목 "OO님께 추천해요". 비로그인·추천 없음 : 최근 30일 판매량 상위 4개 "많이 찾는 메뉴".
- 카테고리 바로가기 칩 → `/menu?category={id}`.

#### C2 메뉴 `/menu`
- 카테고리 밑줄 탭 `.tabs-line` (전체 + 카테고리). 판매중·품절만 보인다 (숨김·후보 제외).
- 카드 : 이미지 타일(카테고리 색) · 이름 · 가격(할인 시 할인가) · 칩(오늘의 메뉴 / NEW / 할인 / 품절). 품절이면 흐리게 + "품절".

#### C3 메뉴 상세 `/menu/{id}`
- 이름 · 설명 · 태그 칩 · 가격(할인가).
- 담기 폼 `POST /cart/add` : `productId`, `opt_{그룹 id}`(그룹마다 이름이 달라야 하나씩 고를 수 있다 — 하나만 고르는 그룹은 radio, 필수 그룹은 첫 옵션 기본 선택, 여러 개 고르는 그룹은 checkbox), `go=order`(바로 주문 → `/order`), `quantity`(1–20, − + 버튼). 품절이면 버튼 비활성.
- 비로그인으로 담으면 로그인 화면 → 로그인 뒤 상세로 돌아온다.

#### C4 장바구니 `/cart` (세션 보관)
- 줄 : 이름 · 옵션 글자 · 단가 · 수량 폼 `POST /cart/{index}/qty` (`quantity`) · 줄 금액 · **삭제** `POST /cart/{index}/delete`.
- 합계 : 상품 금액 · 할인 · 결제 금액(갈색 큰 숫자). **주문하기** `/order`. 비면 빈 상태 + "메뉴 보러 가기".

#### C5 주문서 `/order`
- 폼 `POST /order` (`data-once`) : `pickupType`(STORE 매장 / TAKEOUT 포장, 세그먼트), `request`(≤200), hidden `orderToken`(중복 방지).
- 결제는 데모 : "현장 결제" 안내. 성공 → `/orders/{id}` + flash shopMessage "주문을 받았어요". 장바구니 비움, 포인트 1% 적립.

#### C6/C7 내 주문 `/orders`, `/orders/{id}`
- 목록 : 주문번호 · 시각 · 대표 메뉴 외 N건 · 금액 · 상태 칩.
- 상세 : 상태 진행 막대(접수 → 준비 중 → 픽업 대기 → 완료) · 품목 · 금액. `PLACED` 일 때만 **주문 취소** `POST /orders/{id}/cancel` (확인 모달).

#### C8/C9 로그인 · 회원가입
- 로그인 `POST /member/login` : `username`, `password`. 링크 : 회원가입, 관리자 로그인.
- 회원가입 `POST /member/signup` (`SignupForm`) : `username`(3–30, 중복), `password`(≥4), `password2`(같아야 함), `name`(≤20), `phone`(선택), `email`(선택·중복), `marketingAgree`(체크). 성공 → 로그인 화면 `?signup`.

#### C10 내 정보 `/me`
- 이름 · 등급 칩(일반/실버/골드/VIP) · 포인트 · 주문 수 · 누적 금액 · 최근 주문 3 · 추천 메뉴 4.

### 관리자

#### A2 대시보드 `/admin`
- 히어로 카드 : 오늘 매출(64px 갈색 숫자) + 어제 대비 % + 오늘 주문 수.
- KPI 2개 : 처리할 주문(PLACED+PREPARING) → `/admin/orders`, 이번 달 신규 고객.
- 이번 주 매출 막대 `.week-chart` (오늘만 갈색).
- 인기 메뉴 TOP 5 (최근 7일 수량 막대).
- **AI 추천 요약** : 최근 분석 시각·엔진 + 할인/오늘의 메뉴/신메뉴 후보 각 1위 → `/admin/analysis/menus`.

#### A3 주문 관리 `/admin/orders`
- 상태 세그먼트 `?status=` (전체 / 접수 / 준비 중 / 픽업 대기 / 완료 / 취소, 건수).
- 표 : 주문번호 · 시각 · 고객 · 품목 요약 · 매장/포장 · 금액 · 상태 칩 · 동작.
- 동작 : **다음 단계** `POST /admin/orders/{id}/advance` (PLACED→PREPARING→READY→COMPLETED), **취소** `POST /admin/orders/{id}/cancel` (확인 모달, COMPLETED 전만).

#### A4 고객 정보 `/admin/customers`
- 검색 `q`(이름·아이디·연락처) + 고객군 칩 `segment=`(분석 결과).
- 표 : 고객(이름·아이디) · 연락처 · 등급 · 고객군 칩 · 이탈 위험(%; 60% 이상 빨강) · 주문 수 · 누적 금액 · 마지막 주문 · 가입일.
- 페이지 20명씩 `?page=`.

#### A5 고객 카드 `/admin/customers/{id}`
- 머리 : 이름 · 등급 · 고객군 · 이탈 위험 · 마케팅 동의.
- 카드 : 기본 정보 · 구매 요약(주문 수·누적·평균 객단가·자주 사는 메뉴 TOP 3·선호 시간대) · AI 추천 메뉴 · 최근 주문 20.
- **정지 / 다시 사용** `POST /admin/customers/{id}/active` (확인 모달).

#### A6 고객 분석 `/admin/analysis/customers`
- 머리 : 최근 분석(시각 · 엔진 `PyTorch 2.x` 또는 `Java 대체 계산` · 고객 수 · 학습 손실). **지금 분석하기** `POST /admin/analysis/run` (`data-once`, 확인 모달 없음 — 되돌릴 필요 없는 계산).
- 고객군 카드 (VIP / 단골 / 신규 / 가끔 방문 / 이탈 위험) : 인원 · 비율 막대 · 평균 주문 수 · 평균 누적 금액 · 평균 마지막 주문 경과일 · 선호 카테고리 · **추천 액션**(예: "이탈 위험 : 재방문 쿠폰"). 카드 → `/admin/customers?segment=`.
- 이탈 위험 상위 10명 표 (위험도 막대 · 마지막 주문 · 누적 금액).
- 방법 설명 박스(`.card.soft`) : 특징값 → 오토인코더 임베딩 → K-평균 군집, 이탈 위험 = 로지스틱 회귀(21일 뒤 재방문 여부 학습).

#### A7 메뉴 판매 분석 `/admin/analysis/menus`
- 세 칸 카드 : **할인 추천** · **오늘의 메뉴 추천** · **신메뉴 후보**. 각 3개 (순위 · 메뉴 · 점수 막대 · 이유 한 줄 · 예상 판매).
  - 할인 : 제안 할인율 · 기간(오늘) → **적용** `POST /admin/analysis/insights/{id}/apply` → `Discount`(source=AI) 생성.
  - 오늘의 메뉴 : **적용** → `todayMenu = true`. **오늘의 메뉴 비우기** `POST /admin/analysis/today/clear` (확인 모달).
  - 신메뉴 후보 : `CANDIDATE` 상품 → **출시** → `ON_SALE`, `newMenu = true`, `launchedAt = 오늘` (확인 모달).
  - 적용한 항목은 "적용됨" 칩 + 버튼 숨김.
- 전체 메뉴 지표 표 : 메뉴 · 카테고리 · 30일 판매 수 · 30일 매출 · 2주 추세(± %, 감소 빨강) · 오늘 예상(PyTorch MLP) · 마진율 · 고객 선호도.
- 분석 이력 10건 : 시각 · 엔진 · 상태 칩 · 메시지.

#### A8/A9 상품
- 목록 : 카테고리 칩 + 상태 select. 표 : 메뉴 · 카테고리 · 가격 · 원가 · 상태 칩 · 표시(오늘의 메뉴/NEW) · 옵션 그룹 수 · ⋯(수정, 품절↔판매중 `POST /admin/products/{id}/status`, 삭제 `POST /admin/products/{id}/delete` 확인 모달 — 주문 이력이 있으면 숨김 처리만).
- 폼 `POST /admin/products/new` | `/{id}/edit` (`ProductForm`) : `name`(≤40 필수), `categoryId`(필수), `price`(100–100000), `cost`(0–price), `description`(≤300), `tags`(쉼표 : 과일·우유·디카페인·시즌·달콤·상큼·고소·쌉쌀·초코·차), `status`(ON_SALE/SOLD_OUT/HIDDEN/CANDIDATE), `todayMenu`, `newMenu`, `optionGroupIds`(체크 그리드), `sortOrder`.

#### A10 카테고리 `/admin/categories`
- 추가 `POST /admin/categories` : `name`(≤20 필수, 중복), `sortOrder`. 이름·순서 수정 `POST /admin/categories/{id}`. 삭제 `POST /admin/categories/{id}/delete` (확인, 상품 있으면 막음).

#### A11 옵션 `/admin/options`
- 그룹 카드 : 이름 · 필수/선택 · 하나만/여러 개 · 옵션 칩(이름 +가격 · × 지우기).
- 그룹 추가 `POST /admin/options` : `name`, `required`, `multiple`. 그룹 지우기 `POST /admin/options/{id}/delete` (확인, 상품 연결은 해제).
- 옵션 추가 `POST /admin/options/{id}/items` : `name`, `extraPrice`(0–5000). 옵션 지우기 `POST /admin/options/items/{itemId}/delete`.
- 기본 : 온도(HOT/ICE, 필수) · 사이즈(레귤러/라지 +500, 필수) · 샷 추가(+500, 여러 개) · 시럽(바닐라·헤이즐넛 +300) · 당도(30/50/100%).

#### A12 할인 `/admin/discounts`
- 상태 세그먼트 `?state=active|scheduled|ended|all`.
- 추가 `POST /admin/discounts` : `productId`, `name`, `type`(RATE 정률 / AMOUNT 정액), `value`(정률 1–90, 정액 ≤ 가격), `startDate`, `endDate`(≥ 시작).
- 표 : 할인 이름 · 메뉴 · 할인(15% / 500원) · 할인가 · 기간 · 출처 칩(직접/AI) · 상태 칩 · **사용 중지/다시 사용** `POST /admin/discounts/{id}/toggle` · 삭제(확인).
- 같은 메뉴에 겹치는 할인이 있으면 할인 금액이 큰 하나만 적용.

#### A13 매출 리포트 `/admin/sales`
- 기간 세그먼트 `?days=7|30|90` 또는 `?from=&to=`.
- KPI : 매출 합계(갈색) · 주문 수 · 객단가 · 할인 금액.
- 일별 매출 막대 · 시간대별(7–22시) 막대 · 카테고리별 비율 막대 · 메뉴별 표(수량 · 매출 · 비중 · 할인 판매 비율). 취소 주문은 빠진다.
- **CSV 내려받기** `GET /admin/sales.csv?days=` (UTF-8 BOM).

---

## 5. 워크플로우 · 상태 전이

### 5-1. 주문 (`OrderStatus`)

| enum | 라벨 | 칩 |
|---|---|---|
| PLACED | 주문 접수 | 크림 |
| PREPARING | 준비 중 | 크림 |
| READY | 픽업 대기 | 갈색 채움 |
| COMPLETED | 완료 | 회색 |
| CANCELED | 취소 | 빨강 |

```
PLACED ─▶ PREPARING ─▶ READY ─▶ COMPLETED
  └─(고객·관리자 취소)─▶ CANCELED   (PREPARING·READY 는 관리자만 취소)
```

- 주문 생성 시 : 단가 = 상품 가격 + 옵션 추가금, 할인 = 그 시각 적용 할인 × 수량, `orderNo` = `yyMMdd-####`. 고객 `orderCount`, `totalSpent`, `lastOrderAt`, `point`(+1%) 갱신, 등급 재계산.
- 취소 시 : 고객 집계·포인트 되돌림.
- 중복 주문 방지 : `orderToken` (세션 발급, 한 번 쓰면 폐기).

### 5-2. 상품 (`ProductStatus`)

| enum | 라벨 | 고객 화면 |
|---|---|---|
| ON_SALE | 판매 중 | 보임, 주문 가능 |
| SOLD_OUT | 품절 | 보임(흐리게), 주문 불가 |
| HIDDEN | 숨김 | 안 보임 |
| CANDIDATE | 신메뉴 후보 | 안 보임 — 분석에서 출시 후보로 평가 |

### 5-3. 고객 등급 (`CustomerGrade`, 누적 금액 기준)

BASIC 일반(0~) · SILVER 실버(5만~) · GOLD 골드(15만~) · VIP(30만~).

### 5-4. 할인

- 상태(계산) : 예정(시작 전) · 진행 중 · 종료 · 중지(`active=false`).
- 출처 `DiscountSource` : MANUAL 직접 · AI (분석 적용).

---

## 6. 분석 (PyTorch)

### 6-1. 실행 흐름

```
관리자 "지금 분석하기" / 서버 시작(app.analysis.run-on-startup) / 매일 05:00(app.analysis.cron)
  → AnalysisService : 주문·상품·고객을 JSON 으로 내보냄 (analytics/work/input.json)
  → ProcessBuilder : python analytics/teagather_analysis.py --input … --output …  (제한 시간 app.analysis.timeout-seconds)
  → output.json 을 읽어 AnalysisRun · SegmentSummary · MenuMetric · MenuInsight · Recommendation 저장, 고객 고객군·이탈 위험 갱신
  → Python 또는 PyTorch 가 없거나 실패하면 Java 대체 계산(JavaFallbackAnalyzer)으로 같은 결과 모양을 만든다 (엔진 이름으로 구분)
```

### 6-2. 모델

| 목적 | 입력 | 모델 | 결과 |
|---|---|---|---|
| 고객 군집 | 최근성·빈도·금액·객단가·가입 경과·카테고리 다양성·오전 비율 (표준화) | 오토인코더(7→16→3) 임베딩 + K-평균(k=5, torch) | VIP·단골·신규·가끔 방문·이탈 위험 |
| 이탈 위험 | 21일 전 시점의 특징값 → 그 뒤 21일 안 재주문 여부 | 로지스틱 회귀 (torch) | 고객별 위험 확률 |
| 개인 추천 | 고객×메뉴 구매 행렬(log1p) + 메뉴 속성(카테고리·태그) | 행렬 분해 (id 임베딩 + 속성 선형층) | 고객별 추천 4개 |
| 수요 예측 | 요일·전날·7일 전·7일 평균·28일 평균·할인 여부·카테고리 | MLP (전 메뉴 공유) | 메뉴별 오늘 예상 판매 |
| 신메뉴 후보 | 후보 메뉴의 속성 | 위 추천 모델의 속성 경로(콜드 스타트) | 고객 선호도 → 후보 순위 |

### 6-3. 뽑는 규칙

- **오늘의 메뉴** : 판매 중 메뉴 · 점수 = 예상 판매(정규화) 0.6 + 2주 추세 0.2 + 마진율 0.2. 상위 3.
- **할인 추천** : 마진율 55% 이상이고 (추세 감소 또는 예상 판매가 카테고리 평균 아래). 점수 = 마진 0.4 + 감소 폭 0.4 + 낮은 예상 0.2. 제안 할인율 10/15/20% (마진에 따라). 과거 할인일 판매 증가율이 있으면 이유에 표시.
- **신메뉴 후보** : `CANDIDATE` 메뉴 · 점수 = 고객 상위 25% 예상 선호도 평균. 이유 = 잘 맞는 고객 수 · 가까운 기존 인기 메뉴.

### 6-4. 엔티티

`AnalysisRun`(startedAt, finishedAt, status RUNNING/SUCCESS/FAILED, engine, message, customerCount, orderCount, lossText) → `SegmentSummary`, `MenuMetric`, `MenuInsight`(kind DISCOUNT/TODAY/NEW, rank, score, forecast, suggestedRate, reason, applied), `Recommendation`(member, product, rank, score).

---

## 7. 시드 데이터 (`config/DataInitializer`, `app.seed.enabled`)

- 카테고리 6 (커피 · 티 · 라떼·논커피 · 에이드·주스 · 디저트 · 베이커리), 메뉴 26 (+ 신메뉴 후보 4 : 흑임자 라떼 · 딸기 말차 라떼 · 레몬 마들렌 · 얼그레이 크림 콜드브루), 옵션 그룹 5.
- 고객 160명 (비밀번호 `app.seed.customer-password`, 예 `customer1` / 1234) — 성향(아침 커피파·디저트파·차 애호가·주말 방문 등)에 따라 주문 패턴이 다르다.
- 최근 90일 주문 (약 2,000~3,000건) : 요일·시간대 패턴, 메뉴별 상승·하락 추세, 지난 할인 기간의 판매 증가가 들어 있어 분석 결과가 의미 있게 나온다. 난수 시드 고정(같은 결과 재현).

---

## 8. 설정 (`application.yml` 의 `app.*`)

| 키 | 기본 | 뜻 |
|---|---|---|
| `app.admin.username/password` | admin / admin1234 | 관리자 계정 |
| `app.seed.enabled`, `app.seed.customer-password` | true, 1234 | 예시 데이터 |
| `app.analysis.python` | `python` | 파이썬 실행 파일 |
| `app.analysis.script` | `analytics/teagather_analysis.py` | 분석 스크립트 |
| `app.analysis.timeout-seconds` | 180 | 제한 시간 |
| `app.analysis.run-on-startup` | true | 시작 시 분석이 없으면 한 번 실행 |
| `app.analysis.cron` | `0 0 5 * * *` | 매일 자동 분석 |
