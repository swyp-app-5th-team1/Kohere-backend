# API Design Guide

> Kohere 백엔드(REST API)의 설계 표준이다. 모든 신규 엔드포인트는 이 문서를 따른다.
> 관련 문서: [error-response-guide](./error-response-guide.md) · [system-overview](../architecture/system-overview.md) · [code-style](../convention/code-style.md)

## 목적

클라이언트(모바일 앱)와 서버가 합의한 **일관된 요청/응답 규약**을 정의해, 엔드포인트마다 형식이 달라지는 것을 막는다. 스택은 **Spring Boot 3.5 / Spring MVC / Spring Modulith**이며, 에러 응답의 정본은 [error-response-guide](./error-response-guide.md)다.

## 1. RESTful 설계 원칙

- **리소스는 명사, 복수형**으로 표현한다. 행위는 HTTP 메서드로 표현한다. (`GET /listings`, `POST /listings`)
- URL 경로는 **소문자**, 여러 단어는 **하이픈**(`/recent-listings`). 경로에 동사를 쓰지 않는다(`/getListings` ❌).
- 상태를 바꾸지 않는 조회는 **GET**, 생성은 **POST**, 전체 교체는 **PUT**, 부분 수정은 **PATCH**, 삭제는 **DELETE**.
- **상태 전이/액션**처럼 CRUD로 자연스럽지 않은 경우에만 하위 리소스 또는 동사형 서브경로를 쓴다. (`POST /listings/{id}/favorite`, `POST /auth/reissue`)
- HTTP 메서드는 **멱등성**을 지킨다. GET/PUT/DELETE는 멱등, POST는 비멱등.

### 메서드 ↔ 상태코드 기본값

| 메서드 | 용도 | 성공 status |
| --- | --- | --- |
| GET | 조회 | `200 OK` |
| POST | 생성 | `201 Created` (생성), `200 OK` (생성 아닌 액션) |
| PUT / PATCH | 수정 | `200 OK` (수정된 리소스 반환) / `204 No Content` |
| DELETE | 삭제 | `204 No Content` |

> 에러 status·코드는 전적으로 [error-response-guide](./error-response-guide.md)를 따른다.

> **`PUT`의 첫 사례는 매물 수정(`PUT /api/v2/listings/{listingId}`)이다.** 임대인이 등록 때 보낸 본문을 그대로 다시 보내 매물 본문 전체를 교체하므로 위 규칙대로 `PATCH`가 아니라 `PUT`이고, 성공은 수정된 리소스를 담은 `200 OK`다. `PATCH`를 함께 열지 않은 이유는 규칙 이전에 데이터에 있다 — 좌표·행정구역·인근 대학처럼 **주소에서 파생되는 값**이 있어 일부 필드만 받으면 파생값이 본문과 어긋난 채 저장된다. 상세는 [03-listings-favorites](./specs/03-listings-favorites.md)가 정본이다.

## 2. 엔드포인트 규약

- 모든 API는 **경로 프리픽스로 버전을 가진다.** 신규 엔드포인트의 기본은 `/api/v1`이고, 하위 호환이 깨지는 변경만 `/api/v2`로 올린다. 어느 리소스가 어느 버전에 있는지와 구 버전을 끝내는 방식은 **§2-1이 정본**이다.
- 리소스 식별자는 경로 변수로(`/listings/{listingId}`), 조회 조건은 쿼리 파라미터로 둔다.
- 컬렉션과 단건을 구분한다: `GET /listings`(목록) ↔ `GET /listings/{id}`(단건).
- 중첩은 **소유 관계가 분명할 때 1단계까지만** 허용한다. (`GET /posts/{postId}/comments`) 그 이상 깊어지면 쿼리 파라미터로 평탄화한다.

### 2-1. 버전 정책

버전은 **경로 프리픽스로만** 표현한다(헤더·쿼리 파라미터 버전을 두지 않는다). 버전을 올리는 기준은 하나다 — **이미 출시된 앱이 그대로는 파싱할 수 없는 응답 구조 변경.** 필드 추가처럼 하위 호환이 유지되는 변경은 버전을 올리지 않고 기존 경로에서 처리한다.

현재 두 버전이 병존한다.

| 리소스 | `/api/v1` | `/api/v2` |
| --- | --- | --- |
| 진단 | 클라이언트 주도 흐름 — 그대로 동작(회원 전용) | 서버 주도 흐름 `/api/v2/diagnoses/*` — 앱이 쓰는 흐름([ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md)) |
| 매물 등록·수정 | 없음 | `POST /api/v2/listings`([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)) · `PUT /api/v2/listings/{listingId}`(임대인 본인 매물 전체 교체) |
| 매물 조회·찜·내 스코프 | **deprecated 스텁** — 아래 참조. `GET /api/v1/listings/places`만 그대로 동작한다([ADR-0040](../adr/0040-listing-query-api-v2-and-v1-sunset.md)) | **정본** — 목록·지도·키워드 검색·상세·찜 토글 `/api/v2/listings*`, `/api/v2/users/me/favorites`·`/api/v2/users/me/recent-listings`, 임대인 전용 내 매물 조회 `/api/v2/users/me/listings`·`/api/v2/users/me/listings/{listingId}` |
| 그 외 전부 | 정본 | 없음 |

매물 조회 계열은 v4 스키마 개편([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md))으로 응답 구조가 바뀌어 버전을 올렸다([ADR-0040](../adr/0040-listing-query-api-v2-and-v1-sunset.md)). 엔드포인트별 상세는 [03-listings-favorites](./specs/03-listings-favorites.md)가 정본이다.

**대체할 v1 계약이 없는 신설 경로는 어느 버전에서 시작하나** — 답은 **어느 네임스페이스에 얹히는가**로 갈린다. 임대인 매물 수정(`PUT /api/v2/listings/{listingId}`)과 임대인 전용 내 매물 조회(`GET /api/v2/users/me/listings*`)는 v1에 대응 경로가 없지만, 이미 v2로 옮긴 매물 네임스페이스에 얹혀 **같은 v4 스키마**를 주고받으므로 v2에서 시작한다 — 한 네임스페이스가 두 버전으로 갈리면 클라이언트가 경로마다 버전을 외워야 한다. 반대로 관리자 매물 심사(`/api/v1/admin/listings*`)는 **네임스페이스 자체가 새것**이라 v1에서 시작한다. 버전을 올리는 기준이 「이미 출시된 앱이 깨지는가」인 이상, 깨질 앱이 없는 새 네임스페이스에 v2를 붙일 근거가 없기 때문이다.

#### 구 버전을 끝내는 방식 — 구조는 유지, 데이터는 0건

버전을 올린 리소스의 v1은 **개정 전 응답 구조를 그대로 유지하되 데이터를 반환하지 않는다.** 저장소를 아예 조회하지 않는다.

- 목록·검색 계열 → **빈 페이지**(`content: []`, `totalElements: 0`)
- 단건 조회·상태 변경 계열 → 해당 도메인의 `*_NOT_FOUND`(404)
- **그 데이터를 쓰지 않는 경로는 버전을 옮기지 않는다** — 매물에서는 `GET /api/v1/listings/places`(네이버 장소 검색)가 유일한 예외다

**새 데이터로 옛 모양을 조립하지 않는다.** 없어진 필드를 `deposit: 0`·빈 재고 같은 값으로 채우면 구버전 앱이 날조된 값을 정상으로 표시한다. 빈 결과를 주면 구버전 앱은 "매물 없음" 화면을 보고 업데이트로 유도된다.

구 버전 경로는 `deprecated`로 표기하되 **제거 시점은 정하지 않는다.** 스펙 문서·Swagger에는 「폐지됐다」 같은 변경 이력이 아니라 **현재 동작**("이 경로는 항상 빈 목록을 반환한다. 매물 데이터는 `/api/v2/listings`에서 조회한다")으로 적는다([ADR-0017](../adr/0017-openapi-swagger-ui-from-restdocs.md) description 작성 규약).

#### 버전을 올릴 때 함께 하는 것

- **`SecurityConfig` 매처를 새 경로에 다시 깐다.** 매처는 경로 문자열 기준이라 v1 매처가 v2를 덮지 않는다. 빠뜨리면 `anyRequest().authenticated()`로 떨어져 **공개여야 할 조회가 401**이 된다 — `GET /api/v2/listings`·`/api/v2/listings/*`는 `permitAll`이다. 같은 네임스페이스라도 메서드로 갈린다: `POST /api/v2/listings`(등록)·`PUT /api/v2/listings/*`(수정)는 `hasRole("USER")`다 — `permitAll` 매처가 `GET`만 덮으므로 **같은 경로에 다른 메서드를 얹을 때마다 매처를 새로 깔아야** `anyRequest().authenticated()`로 떨어지는 것을 막는다. 뒤집으면 **회원 전용 조회를 `GET /api/v2/listings/…` 아래에 두어서는 안 된다** — 그 `permitAll` 매처에 먼저 걸려 비로그인에 열린다(임대인 전용 내 매물 조회를 `/api/v2/users/me/listings`에 둔 이유다). 찜 토글·내 스코프처럼 회원 전용인 경로도 v2에 **다시 명시**한다(§6 인가 매처).
- **공통 응답 래퍼는 버전과 무관하다.** `ApiResponseWrapper`는 `com.kohere` 전체를 대상으로 반환 타입 기준으로 감싸므로 새 버전 컨트롤러도 그대로 래핑된다([ADR-0013](../adr/0013-response-auto-wrapping.md)).
- **문서 테스트의 스니펫 identifier와 오퍼레이션 상수를 버전별로 나눈다.** 안 나누면 `operationId` 중복으로 빌드가 깨지거나 한쪽 설명이 반대쪽에 붙는다([test-strategy](../convention/test-strategy.md) §3-4).

### 엔드포인트 표 형식 (각 API 스펙 문서가 따르는 형식)

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v2/listings/{listingId}` | 매물 상세 조회 | 선택 | 200 |
| POST | `/api/v2/listings/{listingId}/favorite` | 찜 등록 | 필수 | 201 |
| POST | `/api/v2/listings` | 매물 등록(임대인) | 필수(`ROLE_USER` · `userType=LANDLORD`) | 201 |
| PUT | `/api/v2/listings/{listingId}` | 매물 수정(임대인) — 등록 본문 전체 교체 | 필수(`ROLE_USER` · `userType=LANDLORD` · 본인 매물) | 200 |
| GET | `/api/v1/listings/{listingId}` | 매물 상세 조회 — **deprecated**(항상 404) | 선택 | — |

deprecated된 경로도 **표에서 지우지 않는다.** 설명에 `deprecated`와 현재 동작을 함께 적는다 — 구버전 앱을 들고 있는 클라이언트 개발자가 계약을 확인할 곳이 여기뿐이다.

상세 스펙은 [docs/api/specs/](./specs/)에 도메인별로 둔다.

## 3. 요청 / 응답 구조

### 3-1. 공통 응답 래퍼

**성공·에러 모두** 동일한 봉투(envelope)로 감싼다. 클라이언트는 `success` 플래그 하나로 분기한다.

```jsonc
// 성공
{
  "success": true,
  "data": { /* 객체 | 배열 | null */ },
  "error": null
}
```

```jsonc
// 에러 (정본: error-response-guide)
{
  "success": false,
  "data": null,
  "error": {
    "code": "LISTING_NOT_FOUND",
    "message": "해당 매물을 찾을 수 없습니다.",
    "errors": [ /* 입력 검증 실패 시 필드별 상세 (선택) */ ],
    "details": { /* 코드별 부가 데이터 (선택). 값이 없으면 키 자체가 없다 */ }
  }
}
```

- 서버는 `ApiResponse<T>` 공통 타입으로 응답을 감싼다. 컨트롤러는 **DTO만** 반환하고 엔티티를 직접 노출하지 않는다([code-style](../convention/code-style.md) §3-3).
- `data`는 단일 리소스면 객체, 목록이면 페이지 객체(§4)를 담는다. 본문이 없으면(예: 204) 래퍼 없이 빈 본문을 반환할 수 있다.

### 3-2. 생성 / 수정 / 삭제 응답

- **생성(201)**: `data`에 생성된 리소스(최소 `id` 포함)를 담고, 가능하면 `Location` 헤더에 리소스 URI를 준다.
- **수정(200)**: 수정 후 리소스 전체 또는 변경 필드를 `data`에 담는다.
- **삭제(204)**: 본문 없음.
- **토글성 액션**(찜/좋아요): 현재 상태를 `data`에 담아 반환한다. (`{ "favorited": true, "favoriteCount": 12 }`)

### 3-3. 요청 본문

- `Content-Type: application/json; charset=UTF-8`.
- 요청 DTO는 **Bean Validation**(`@NotNull`, `@Size`, `@Email` 등)으로 표현 계층에서 검증한다. 위반 시 `400` + `INVALID_INPUT` + `errors[]`([error-response-guide](./error-response-guide.md) §4).
- 부분 수정(PATCH)에서 "값을 비움"과 "필드 미전송"을 구분해야 하면 문서에 명시한다.

## 4. 페이지네이션 규약

목록은 두 방식 중 하나를 쓰고, 각 스펙에 명시한다.

### 4-1. 오프셋 기반 (기본 — 게시판·검색 결과 등)

요청: `?page=0&size=20&sort=createdAt,desc` (page는 0-base, size 기본 20·최대 100)

```jsonc
"data": {
  "content": [ /* ... */ ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 137,
    "totalPages": 7,
    "hasNext": true
  }
}
```

### 4-2. 커서 기반 (채팅 메시지·무한 스크롤 피드)

요청: `?cursor=<lastId>&size=20` (첫 페이지는 `cursor` 생략)

```jsonc
"data": {
  "content": [ /* ... */ ],
  "nextCursor": "1024",
  "hasNext": true
}
```

- 정렬 키가 시간이면 `cursor`는 `(정렬값,id)` 조합을 불투명 토큰으로 인코딩할 수 있다. 클라이언트는 `nextCursor`를 그대로 다시 보낸다.

## 5. 필터링 / 검색 규약

- 필터는 **쿼리 파라미터**로 받는다. 파라미터명은 `lowerCamelCase`.
- 다중 값은 콤마로 구분한다. (`?conditions=FEMALE_ONLY,PRIVATE_BATH`)
- 범위는 `min`/`max` 접미사. (`?minBudget=300000&maxBudget=700000`, 단위 원)
- 키워드 검색은 `?keyword=` 하나로 받고, 검색 대상(제목/본문 등)은 스펙에 명시한다.
- **지도/위치 검색**(매물 탐색)은 좌표를 WGS84 십진수로 받는다.
  - 뷰포트(bounding box): `?swLat=&swLng=&neLat=&neLng=` — 지도 영역 내 매물.
  - 반경: `?centerLat=&centerLng=&radius=`(m) — 특정 지점 주변.
  - 클러스터링은 API별 스펙에서 서버/클라이언트 책임을 명시한다. 예: 매물 지도 마커는 서버가 개별 좌표를 반환하고 프론트 지도 SDK가 묶는다.
- 정의되지 않은/허용되지 않은 필터 값은 무시하지 말고 `400 INVALID_INPUT`으로 알린다.

## 6. 공통 규약

| 항목 | 규약 |
| --- | --- |
| 버전 | 경로 프리픽스 `/api/v1`·`/api/v2`. 신규는 `/api/v1`이 기본이고 하위 호환이 깨지는 변경만 버전을 올린다. 구 버전은 구조를 유지한 채 **빈 결과**로 끝낸다(§2-1) |
| 인증 헤더 | `Authorization: Bearer <accessToken>` (JWT). 갱신은 `POST /api/v1/auth/reissue` |
| 인증 필요 표기 | 각 엔드포인트에 인증 **필수 / 선택 / 불필요**를 명시 |
| 인가 매처 | 인증이 필요한 신규 경로는 `SecurityConfig`에 **명시 매처**(예: `hasRole("USER")`)를 둔다. `anyRequest().authenticated()`에 맡기면 온보딩 스코프(`ROLE_ONBOARDING`) 토큰도 컨트롤러에 도달한다. 역할 조건(예: `userType=LANDLORD`)은 서비스에서 재검사해 `403` |
| 날짜·시각 | **ISO-8601 UTC** (`2026-06-15T08:30:00Z`). 서버 저장은 UTC, 표시 변환은 클라이언트 책임. 날짜만은 `YYYY-MM-DD` |
| 식별자 | 리소스 ID는 서버 생성. 본문/경로에서 숫자(Long) 또는 문자열로 일관되게 노출 |
| 금액 | 원(KRW) 정수, 소수점 없음 (`budget: 500000`) |
| 좌표 | `lat`/`lng` 십진수(WGS84), 소수 6자리 권장 |
| enum | 응답·요청 모두 **UPPER_SNAKE_CASE 문자열**(`MALE`, `ARC_ISSUED`). 숫자 코드로 노출하지 않는다. (`visaType`도 API는 상수명(`SHORT_TERM_VISIT`)이며, 표시용 라벨은 DB 저장 형식일 뿐 API 규약과 무관 — #138) |
| 불리언 | `is`/`has` 없이 명사형 필드(`favorited`, `read`) 또는 의미가 분명한 이름 |
| 네이밍 | JSON 필드·쿼리 파라미터 `lowerCamelCase`, URL 경로 `kebab-case` |
| 정렬 | `?sort=field,(asc\|desc)` (다중 정렬은 `sort` 반복) |
| 멱등성 키 | 결제·예약 등 중복 위험 POST는 `Idempotency-Key` 헤더 지원을 검토 |

## 체크리스트

- [ ] 경로가 `/api/v1`(하위 호환이 깨져 신설했다면 `/api/v2`) + 복수 명사 + kebab-case이고 동사를 쓰지 않는다
- [ ] 버전을 올렸다면 §2-1을 따랐다 — 구 버전은 **구조 유지 + 빈 결과**(목록은 빈 페이지, 단건은 404)이고, 새 경로에 `SecurityConfig` 매처를 다시 깔았고, 문서 테스트 identifier·오퍼레이션 상수를 버전별로 나눴다
- [ ] 메서드·성공 status가 §1 표를 따른다 (생성 201 + Location, 삭제 204)
- [ ] 인증이 필요한 경로는 `SecurityConfig`에 명시 매처(`hasRole("USER")` 등)를 두고, 역할·소유권 조건은 서비스에서 재검사한다(§6)
- [ ] 응답을 공통 래퍼(`success`/`data`/`error`)로 감쌌고 엔티티를 직접 노출하지 않는다
- [ ] 목록은 오프셋/커서 중 하나를 명시하고 §4 구조를 따른다
- [ ] 입력은 Bean Validation으로 검증하고, 실패 시 [error-response-guide](./error-response-guide.md)의 `INVALID_INPUT`을 반환한다
- [ ] 날짜(UTC ISO-8601)·금액(KRW 정수)·enum(UPPER_SNAKE)·인증 표기 규약을 지켰다
- [ ] 에러 응답은 [error-response-guide](./error-response-guide.md)를 따른다
