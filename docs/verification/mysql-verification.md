# MySQL 실DB 검증 (리뷰 지적 #2) — 상태: **환경 문제로 미실행**

작성일 2026-11-25 · 브랜치 `feature/od-mysql-verify`

## 1. 목적
H2(`MODE=MySQL`)는 문법만 흉내 내고 InnoDB 잠금(gap lock·next-key lock)은 재현하지 않는다.
그래서 아래 상황을 실제 MySQL 8.4에서 확인하려 한다.
- 그날 첫 주문이 동시에 몰릴 때, 일자별 카운터(`INSERT … ON DUPLICATE KEY UPDATE`)가 교착이나 PK 중복 없이 도는지
- 교착이나 중복이 나면 재시도(`OrderConflicts`)로 흡수되는지

## 2. 준비한 것 (커밋됨)
| 항목 | 내용 |
|---|---|
| 의존성 | `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-mysql`. Boot 4.1.1 BOM은 Testcontainers **2.0.5**를 관리하며, 클래스는 `org.testcontainers.mysql.MySQLContainer`이다. |
| Gradle | 기본 `test`는 `@Tag("mysql")`을 제외해 빠르게 끝낸다. MySQL 검증은 `./gradlew mysqlTest`로 따로 돌린다. |
| 프로필 | `src/test/resources/application-mysqltest.yml`: 드라이버를 MySQL로 바꾸고, 접속 정보는 `@ServiceConnection`으로 넣는다. 예시 데이터와 분석은 끈다. |
| 테스트 | `src/test/java/com/teagather/mysql/MySqlOrderDefenseIT.java`. `@Testcontainers(disabledWithoutDocker = true)`를 붙였고, 이미지는 `mysql:8.4`로 고정했다. |

### 시나리오 (모두 트랜잭션 없이 실제 커밋, 각 5회 반복)
- **(a) 그날 첫 주문:** 카운터 줄이 없는 상태에서 `OrderPlacementFacade`로 50건을 **50개 스레드로 한꺼번에** 넣는다. 기대값은 사용자에게 보이는 실패 0, 번호 1..50 연속이다. 재시도와 충돌(order_no / lock) 건수는 지표로 기록한다.
- **(b) 시드 경로:** 오늘 주문 3건을 넣고 카운터를 지운 뒤 동시에 50건을 넣는다. 기대값은 번호 1..53 연속이다.
- **(c) 회원 집계:** 같은 고객이 동시에 주문 10건과 취소 10건을 한다. 기대값은 실패 0이고, 집계가 취소되지 않은 주문의 합계와 같은 것이다.
- **(d)** 기존 `order.verify` / `order.web` 테스트를 MySQL로 다시 돌리는 것은 이번에 하지 못했다.

## 3. 실행 결과: Docker 저장소 쓰기 실패로 중단
- Docker 엔진 자체는 응답한다(29.3.1).
- `mysql:8.4` 이미지를 받는 단계에서 아래 오류가 반복됐다.
  ```
  Status 500: write /var/lib/desktop-containerd/daemon/io.containerd.metadata.v1.bolt/meta.db: input/output error
  ```
- `docker-desktop` 커널 로그에도 `Buffer I/O error on dev sdf … lost async page write`가 남아 있었다.
- **원인:** 호스트 **C: 드라이브의 남은 공간이 약 1.1 GB**뿐이다. Docker Desktop의 가상 디스크(VHDX)가 커지지 못해 쓰기가 실패한다.
- 테스트 프로세스는 이미지 받기 재시도를 기다리다 멈춰서 중단시켰다. 사용자에게 보이는 결과(수치)는 **아직 없다.**
- 기본 테스트(`./gradlew test`, H2)는 MySQL 태그를 제외한 상태로 통과했다. 테스트 결과 파일 8개, 실패 0.

## 4. 다시 실행하는 방법
1. C: 공간을 최소 수 GB 확보한다. MySQL 이미지만 약 0.8 GB이고, Docker 디스크에도 여유가 필요하다.
2. Docker Desktop을 재시작한다. 오류가 계속되면 Troubleshoot → Clean / Purge data를 실행한다. **이 작업은 기존 이미지와 컨테이너를 지운다.**
3. `./gradlew mysqlTest`를 실행하고, 출력의 `[MYSQL] …` 줄을 이 문서 5절에 기록한다.

## 5. 결과 기록 (실행 후 채움)
| 시나리오 | 실패 | 재시도 | 충돌(order_no) | 충돌(lock) | 번호 연속 | 비고 |
|---|---|---|---|---|---|---|
| (a) 그날 첫 주문 ×5 | | | | | | |
| (b) 시드 경로 ×5 | | | | | | |
| (c) 회원 집계 ×5 | | | | | | |

### 결과가 나오면 판단할 것
- (a)·(b)에서 실패 유형이 `order_day_seq` PK 중복(`Duplicate entry '…' for key 'order_day_seq.PRIMARY'`)이면 `OrderConflicts`가 재시도 대상으로 잡지 못한 것이다. order_no 판별 옆에 카운터 키 중복도 재시도 대상으로 추가한다.
- 교착(`DeadlockLoserDataAccessException`)은 `PessimisticLockingFailureException` 계열이라 이미 재시도 대상이다. 다만 `conflict(lock)` 수치가 크면 카운터 행 생성을 별도 트랜잭션으로 분리하는 방안을 검토한다.
