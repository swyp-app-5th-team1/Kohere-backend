# ADR-0040. 매물 조회를 /api/v2로 옮기고 v1은 빈 결과로 종료한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0040 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-08-13 |
| 기준 코드 | `feature/220-listing-registration-api` @ `21fdc7e`. 본 ADR의 파일·경로 참조는 전부 이 시점 기준이며, 재검증 없이 인용하지 않는다 |
| 관련 문서 | [ADR-0004](./0004-api-response-envelope.md), [ADR-0010](./0010-jwt-authentication-filter.md), [ADR-0013](./0013-response-auto-wrapping.md), [ADR-0017](./0017-openapi-swagger-ui-from-restdocs.md), [ADR-0036](./0036-diagnosis-v2-server-driven-flow.md), [ADR-0037](./0037-listing-localization-and-code-catalog.md), [ADR-0039](./0039-listing-schema-v4-registration-form.md), [api-design-guide](../api/api-design-guide.md), [listing API](../api/specs/03-listings-favorites.md) |

## Status

Proposed · **Amended(2026-08-15)** · **Amended(추천 카드에 `nearestTransit` 추가, 2026-09-10)**

> **Amended — 키워드 검색이 대상에서 빠졌다.** [ADR-0043](./0043-remove-seeded-poi-keyword-search.md)이 `GET /listings/search`를 **v1·v2 양쪽에서 제거**했다. 클라이언트가 호출하지 않는 것이 확인됐고, US-3-3은 이미 네이버 장소 검색(`GET /api/v1/listings/places`)으로 갈아탔기 때문이다. 아래 본문의 **"6종"은 이제 5종**이며, v1 스텁도 키워드 검색을 뺀 나머지만 남는다. 이력을 남기기 위해 본문은 작성 시점 그대로 두고 해당 줄에만 표시한다.
>
> **범위는 매물 조회 계열뿐이다.** 진단 추천(`GET /diagnoses/{id}/recommendations`)은 이 결정의 대상이 **아니다** — 추천 응답 `RecommendedListingView`는 v4 개편 전후로 **구조가 바뀌지 않았고**(`git diff 04913ea..HEAD`에 변경 없음), 바뀐 것은 값뿐이다(`conditions`에서 `NO_ARC` 배지가 빠졌다 — [ADR-0039](./0039-listing-schema-v4-registration-form.md)). 추천은 이미 v1·v2 양쪽에 있고([ADR-0036](./0036-diagnosis-v2-server-driven-flow.md) 결정 10) 앱은 v2를 쓴다. 진단 컨트롤러·진단 문서는 이 ADR로 바뀌지 않는다.

> **Amended — 추천 카드가 `nearestTransit`을 싣는다.** 위 「범위는 매물 조회 계열뿐이다」가 근거로 든 「`RecommendedListingView`의 구조가 바뀌지 않았다」는 **작성 시점의 사실**이다. 지금 추천 카드는 가까운 교통수단(`nearestTransit` — 종류·이름·도보 분)을 함께 싣고, 가격 범위·조건 태그는 진단 조건을 통과한 ACTIVE 방 상품만을 기준으로 집계한다 — 정본은 [진단·추천 스펙](../api/specs/02-diagnosis-recommendation.md)이다. **이 ADR의 범위는 그대로다** — 매물 조회 v1 종료·v2 이관과 무관한 진단 쪽 변경이며, 필드가 늘어나는 것은 하위 호환을 깨지 않아 추천은 계속 v2 한 벌로 간다. 이력을 남기기 위해 본문과 위 Amended는 작성 시점 그대로 둔다.

## Context

- [ADR-0039](./0039-listing-schema-v4-registration-form.md)가 매물 스키마를 v4로 재정의하면서 **응답 구조가 하위 호환을 깼다**. 그 ADR의 Consequences는 "구버전 앱 대응은 별도 ADR(API 버전 분리)에서 다룬다"로 이 결정을 남겨 두었다.
- **앱은 이미 출시됐고 `/api/v1/listings`를 호출한다.** 그런데 조회 컨트롤러(`ListingController`·`MyListingController`)는 경로를 그대로 둔 채 응답만 v4로 바뀌었다 — 즉 **지금 v1이 v4 구조를 반환하고 있다.** 구버전 앱은 없는 필드를 읽으므로 목록·상세가 깨진 상태다.
- 깨진 지점은 세 종류다. **삭제**(`propertyPolicies` · `roomOffers[].inventory` · `descriptions` · `RefundPolicy.code` · `commonSpaces[].count` · `ConditionTag.NO_ARC` 태그), **이동**(`arcRequired`가 루트로 승격), **형태 변경**(`commonSpaces`가 객체 배열 → 코드 집합, `refundPolicy`가 객체 → 문장 하나).
- **옛 모양을 되살릴 원본이 없다.** v3 데이터는 baseline 리셋으로 폐기됐고([ADR-0039](./0039-listing-schema-v4-registration-form.md) §4), `inventory`·`propertyPolicies`는 **v4 스키마에 대응 필드 자체가 없다** — 등록 폼이 수집하지 않는 값이라 삭제한 것이다.
- 배선 사실(코드 확인): `/api/v1`은 전역 설정이 아니라 **각 컨트롤러의 `@RequestMapping("/api/v1/...")` 리터럴**이다([ADR-0036](./0036-diagnosis-v2-server-driven-flow.md) Context와 같다). `SecurityConfig`는 `GET /api/v1/listings`·`/api/v1/listings/*`만 permitAll로 열고, 찜·내 스코프는 `hasRole("USER")`로 명시하며, 그 밖은 `.anyRequest().authenticated()`로 떨어진다([ADR-0010](./0010-jwt-authentication-filter.md)).
- **v2 네임스페이스는 이미 열려 있다.** `POST /api/v2/listings`(등록, `hasRole("USER")`)가 v4 스키마 기준으로 들어와 있다([ADR-0039](./0039-listing-schema-v4-registration-form.md)).
- [api-design-guide](../api/api-design-guide.md)는 "하위 호환이 깨지는 변경은 `/api/v2`로 올린다"고 정해 두었다. **v4 개편은 이 규약을 지키지 않았다.** 같은 일이 처음도 아니다 — [ADR-0037](./0037-listing-localization-and-code-catalog.md)이 표시 코드 필드를 문자열에서 `{code,label}`로 바꾸면서 버전을 올리지 않은 선례가 있다.

## Decision

**매물 조회 6종을 `/api/v2`로 이관하고, `/api/v1`은 개정 전(v3) 응답 구조를 복원한 채 *항상 빈 결과*를 돌려주는 스텁으로 종료한다. v1은 `deprecated`로 표기하되 제거 시점은 정하지 않는다.**

| | v1 | v2 |
|---|---|---|
| 응답 구조 | **개정 전(v3) 그대로 복원** | 개정 후(v4) |
| 데이터 | **0건 · DB 미접근** | 실데이터 |
| 상태 | `deprecated` · 제거 시점 **미정** | 정본 |

구버전 앱은 "매물 없음" 화면을 보고 업데이트로 유도된다. **새 데이터로 옛 모양을 조립하지 않으므로 하위 호환용 값 날조(`deposit: 0`, 빈 재고)가 없다.**

### 1. 이관 대상 6종

| 용도 | v1(스텁) | v2(정본) |
|---|---|---|
| 목록 | `GET /api/v1/listings` | `GET /api/v2/listings` |
| 지도 | `GET /api/v1/listings/map` | `GET /api/v2/listings/map` |
| ~~키워드 검색~~ | ~~`GET /api/v1/listings/search`~~ | ~~`GET /api/v2/listings/search`~~ — **제거됨([ADR-0043](./0043-remove-seeded-poi-keyword-search.md))** |
| 상세 | `GET /api/v1/listings/{id}` | `GET /api/v2/listings/{id}` |
| 찜 토글 | `POST`·`DELETE /api/v1/listings/{id}/favorite` | `POST`·`DELETE /api/v2/listings/{id}/favorite` |
| 내 스코프 | `GET /api/v1/users/me/favorites`·`/recent-listings` | `GET /api/v2/users/me/favorites`·`/recent-listings` |

### 2. v1 스텁 동작

- 목록 · 찜 목록 → **빈 페이지**(`content: []`, `page.totalElements: 0`). (키워드 검색은 [ADR-0043](./0043-remove-seeded-poi-keyword-search.md)으로 제거)
- 지도 → **빈 마커**(`markers: []`, `total: 0`) — 이 응답에는 `content`도 `page`도 없다.
- 최근 본 → **빈 목록**(`content: []`) — 페이지네이션이 없어 `page` 객체가 없다.
- 상세 → **`404 LISTING_NOT_FOUND`**.
- 찜 토글(`POST`·`DELETE`) → **`404 LISTING_NOT_FOUND`**.
- **`GET /api/v1/listings/places`(네이버 장소 검색)만 그대로 동작한다** — 매물 데이터를 쓰지 않고 외부 장소 API만 호출하므로 v4 개편의 영향을 받지 않았다. 스텁으로 만들 이유가 없다.

**스텁은 저장소를 호출하지 않는다.** 빈 결과를 "조회했더니 0건"으로 만들지 않고 **DB에 가지 않는 것**이 결정의 핵심이다 — v1이 v4 문서를 읽는 순간 옛 DTO로의 매핑이 필요해지고, 그 매핑이 곧 대안 A(값 날조)다.

v1 DTO는 개정 전 구조로 되돌려 유지한다. 실제로 직렬화되는 것은 응답 봉투(페이지 정보·`markers`·`total`)뿐이고 **항목 DTO는 `content`가 늘 비어 있어 한 번도 직렬화되지 않는다** — 스펙에도 Swagger에도 그 필드 목록은 나오지 않는다. 그럼에도 남기는 이유는 **컴파일러가 v1 응답 타입을 v4 도메인에서 떼어 놓게 하기 위해서**다. 항목 타입을 지우고 `List<Object>` 따위로 두면 다음 개편 때 누군가 v4 DTO를 그 자리에 꽂아도 빌드가 통과한다. 옛 계약을 코드로 붙들어 두는 쪽이 주석보다 강하다.

### 3. 보안 경로 — v1 매처를 v2로 복제한다

`GET /api/v2/listings`·`/api/v2/listings/*`를 **permitAll**에 넣는다. 넣지 않으면 `.anyRequest().authenticated()`로 떨어져 **비회원 매물 탐색이 401**이 된다(매물 탐색은 가입 전에도 쓰는 공개 기능이다). v1과 같은 이유로 **HTTP method를 GET으로 한정하고 한 단계 하위 경로만** 연다 — `/{id}/favorite`은 두 세그먼트라 이 매처에 걸리지 않는다.

같은 네임스페이스 안에서 **GET은 공개, POST는 회원 전용**으로 갈린다: `POST /api/v2/listings`(등록)는 이미 `hasRole("USER")`로 명시돼 있다([ADR-0039](./0039-listing-schema-v4-registration-form.md)). 찜 토글(`POST`·`DELETE /api/v2/listings/*/favorite`)과 내 스코프(`GET /api/v2/users/me/favorites`·`/recent-listings`)도 v1과 같이 **`hasRole("USER")`로 명시**한다 — 명시하지 않고 `anyRequest().authenticated()`에 맡기면 온보딩(`ROLE_ONBOARDING`) 토큰이 통과한다.

### 4. 공통 응답 래퍼는 버전과 무관하다

`ApiResponseWrapper`는 `@ControllerAdvice(basePackages = "com.kohere")` + 반환 타입 기준이라 **v2 컨트롤러에도 그대로 적용된다** — 새 경로를 위해 손댈 것이 없다([ADR-0004](./0004-api-response-envelope.md)). [ADR-0013](./0013-response-auto-wrapping.md) 결정 3이 "`supports()`에서 `/api/v1` 컨트롤러로 한정한다"고 적은 것은 **구현과 다른 서술**이므로 문서를 구현대로 바로잡는다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A′. v2 이관 + v1 빈 결과 스텁(채택)** | 구버전 앱이 정상 화면("매물 없음")을 받고 업데이트로 유도된다. v1이 DB에 가지 않아 옛↔새 매핑이 아예 없다. v2는 v4를 그대로 반환하고 등록·조회가 한 네임스페이스에 모인다 | 구버전 앱 사용자는 업데이트 전까지 매물을 볼 수 없다. 쓰지 않는 v1 DTO 한 벌이 코드·문서에 남는다 | **채택** |
| A. v1이 v4 데이터를 옛 모양으로 조립 | 구버전 앱이 계속 동작한다 | `roomOffers[].inventory`·`propertyPolicies`는 **v4에 원본이 없어** 채울 수 없다 — `deposit: 0`·빈 재고 같은 값을 **날조**해야 하고, 그 거짓을 구버전 앱이 화면에 그린다. 옛 DTO ↔ v4 도메인 매핑을 v1이 살아 있는 내내 유지해야 한다 | 미채택 — 서버가 없는 사실을 지어내는 쪽이 빈 화면보다 나쁘다 |
| B. v1 즉시 제거 | 코드·문서가 가장 깨끗하다 | 구버전 앱이 `404`를 받아 원인 불명 오류 화면이 뜬다 — 사용자에게 "업데이트하라"는 신호가 전달되지 않는다 | 미채택 — 종료 자체는 맞지만 착지가 거칠다 |
| C. 버전을 올리지 않고 v1이 v4를 반환(현상 유지) | 작업이 없다 | 출시된 앱이 깨진 채로 방치된다. [api-design-guide](../api/api-design-guide.md)의 "하위 호환이 깨지면 `/api/v2`" 규약 위반이고, [ADR-0037](./0037-listing-localization-and-code-catalog.md)이 같은 방식으로 v1을 깬 **선례를 한 번 더 반복**해 관행으로 굳힌다 | 미채택 — 이 ADR이 바로 그 선례를 닫는다 |
| D. v1이 전용 상태코드(`410 Gone`·`426 Upgrade Required`)로 응답 | "종료됐다"는 사실이 프로토콜에 드러난다 | 구버전 앱은 그 코드를 **모른 채 출시됐다** — 처리 분기가 없어 결국 알 수 없는 에러 화면이 된다(대안 B와 같은 착지) | 미채택 — 앱이 이미 아는 표현(빈 목록)으로 알리는 편이 낫다 |
| E. v1 조회를 회원 전용으로 막아 업데이트를 강제 | 강한 유도 | 공개 기능을 인증 뒤로 숨겨 비회원 탐색 계약을 깨고, 401은 앱에서 로그아웃 흐름을 트리거할 수 있다 | 미채택 — 인증은 유도 수단이 아니다 |

## Consequences

- **긍정**: 구버전 앱이 **에러가 아니라 정상 화면**("매물 없음")을 받는다. 하위 호환용 날조 값이 한 곳도 생기지 않는다. v2는 v4 스키마를 있는 그대로 반환하고, 매물 등록·조회가 `/api/v2` 한 네임스페이스에 모인다. 규약("하위 호환이 깨지면 버전을 올린다")이 실제로 지켜진 첫 사례가 된다.
- **부정/트레이드오프**
  - **구버전 앱 사용자는 업데이트 전까지 매물을 볼 수 없다.** 이 결정은 그것을 장애가 아니라 **의도된 종료 상태**로 받아들인다.
  - **v1 DTO를 개정 전(v3) 구조로 복원해 유지해야 한다.** 값을 채우지 않는 DTO 한 벌(항목 타입 6종 + 동결한 도메인 사본)이 코드에만 남는다. 스펙·Swagger에는 나타나지 않으므로 **읽는 사람 없이 유지 비용만 드는 자산**이고, v1을 제거할 때 함께 지운다.
  - **Swagger에 v1·v2가 공존한다** — REST Docs 기반 OpenAPI([ADR-0017](./0017-openapi-swagger-ui-from-restdocs.md))에서 `operationId`는 스니펫 identifier들의 공통 접두사로 만들어진다. v1·v2가 같은 접두사를 쓰면 `verifyOpenApiSpec`이 **중복으로 빌드를 깬다**(조용히 덮이지 않는다). 스니펫 식별자를 버전별로 분리해야 한다.
  - **Swagger에 `deprecated` 배지가 뜬다.** 스니펫에 `resourceDetails().deprecated(true)`를 주면 `openapi3.yaml`의 오퍼레이션에 `deprecated: true`가 실리고 Swagger UI가 취소선과 배지를 붙인다. 성공 응답이 없는 오퍼레이션(v1 상세·찜 토글은 항상 404)은 에러 스니펫이 유일한 모델이므로 거기에 플래그를 세운다. summary에는 버전 표기를 넣지 않는다 — 경로가 이미 `/api/v1`을 보여주고 배지가 상태를 말한다. 코드의 `@Deprecated(forRemoval = true)`도 그대로 붙인다.
  - **제거 시점이 미정이라 v1이 방치될 수 있다.** `deprecated` 표기만으로는 아무것도 사라지지 않는다.
  - 조회 계열 컨트롤러가 두 벌이 된다(v2 정본 + v1 스텁). 스텁은 서비스에 위임하지 않으므로 로직 중복은 아니지만 표면적은 늘어난다.
- **후속 작업**
  - v1 제거 시점의 판단 기준(구버전 앱 트래픽 비중)과 앱 강제 업데이트 안내 방식을 정한다.
  - 스펙([03-listings-favorites](../api/specs/03-listings-favorites.md))·[api-design-guide](../api/api-design-guide.md)·시퀀스 다이어그램의 경로를 v2로 갱신하고 v1 절에 `deprecated`를 표기한다.
  - 이후 매물 계열의 하위 호환 불가 변경은 **v2를 깨지 말고 v3를 신설**한다 — 이 ADR이 만든 규칙이 자기 자신에게도 적용된다.

## Validation

- **v1 스텁이 저장소를 건드리지 않는다는 것을 테스트로 강제한다.** 목(mock) 상호작용이 아니라 **실데이터 대조**로 본다 — v1 컨트롤러에 주입 대상이 아예 없어 목을 걸 자리가 없고, 목보다 다음 쪽이 강한 증거다: 시드 매물 2건과 v2로 만든 찜 1건·최근 본 1건이 있는 상태에서 v1을 호출해 빈 결과·404를 확인하고, 찜 토글 404 뒤 두 컬렉션 건수가 그대로인지 다시 센다(읽기·쓰기 모두 없음). 이 가드가 없으면 "빈 결과인데 왜 DB를 읽지"가 나중에 값 조립으로 되돌아간다.
- v1 목록·지도·키워드 검색·찜 목록·최근 본이 **빈 페이지**(`content: []`, `totalElements: 0`)를, 상세와 찜 토글(`POST`·`DELETE`)이 **`404 LISTING_NOT_FOUND`** 를 반환한다 — 매물이 실제로 저장돼 있어도 그렇다.
- **인가 판정이 스텁보다 먼저다** — 토큰 없이 v1 찜 토글·내 스코프를 부르면 `404`가 아니라 `401 UNAUTHENTICATED`다.
- `GET /api/v1/listings/places`는 종전대로 장소 검색 결과를 반환한다(스텁 대상이 아님).
- v2 6종이 **실데이터**를 v4 구조로 반환하고, v1과 응답 구조가 서로 다르다.
- **보안 경로**: 토큰 없이 `GET /api/v2/listings`·`/api/v2/listings/{id}`가 `200`이고(401이 아니다), `POST`·`DELETE /api/v2/listings/{id}/favorite`와 `GET /api/v2/users/me/*`는 온보딩 토큰으로 `403 AUTH_ONBOARDING_REQUIRED`다.
- 공통 래퍼가 v2 응답에도 `{ success, data, error }`로 적용된다.
- REST Docs가 v1·v2 스니펫을 **둘 다** 생성하고 `operationId`가 충돌하지 않는다.
- **재검토 시점**: 구버전 앱 트래픽이 무시할 수준으로 떨어지면 v1 스텁·DTO·문서 절을 일괄 제거한다.
