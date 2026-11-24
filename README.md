# Teagather — 카페 온라인 스토어

Spring Boot 4.1 · Thymeleaf · JPA · Spring Security + Python/PyTorch 분석.
구조와 규칙은 InhaWorks(sbb-InhaWorks)를 따른다. 화면·엔드포인트 목록은 [`docs/feature-inventory.md`](docs/feature-inventory.md).

## 실행

```bash
./gradlew bootRun            # Windows : .\gradlew.bat bootRun (JDK 21)
```

- 기본 DB 는 H2 파일(`data/teagather.mv.db`). 지우면 예시 데이터부터 다시 만든다. MySQL : `--spring.profiles.active=mysql`
- 고객 사이트 http://localhost:8080 · 고객 `customer1` ~ `customer160` / `1234`
- 관리자 http://localhost:8080/admin · `admin` / `admin1234`

## PyTorch 분석

```bash
pip install torch          # Python 3.9+
```

- 서버가 처음 뜨면 분석을 한 번 돌리고, 매일 05:00 다시 돌린다. 관리자 > 메뉴 판매 분석 > **지금 분석하기** 로도 실행.
- Spring 이 `analytics/work/input.json` 을 쓰고 `python analytics/teagather_analysis.py` 를 실행해 `output.json` 을 읽는다.
- 파이썬 경로는 `app.analysis.python` (예: `C:/Python39/python.exe`). 파이썬·PyTorch 가 없으면 Java 대체 계산으로 같은 화면을 채우고 엔진 칩에 표시한다.

| 모델 | 쓰임 |
|---|---|
| 오토인코더 + K-평균 | 고객군 (VIP · 단골 · 신규 · 가끔 방문 · 이탈 위험) |
| 로지스틱 회귀 | 이탈 위험 (21일 안 재주문 여부) |
| 행렬 분해 (+ 메뉴 속성) | 고객별 추천 메뉴, 신메뉴 후보 선호도 (콜드 스타트) |
| MLP | 메뉴별 오늘 예상 판매 → 오늘의 메뉴 · 할인 추천 |

## 로고

`src/main/resources/static/images/logo-mark.svg`, `logo-horizontal.svg` 는 임시 로고다. 실제 로고 파일로 바꾸면 된다.
