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

## 2. 엔드포인트 규약

- 모든 API는 **`/api/v1`** 프리픽스를 가진다. 하위 호환이 깨지는 변경은 `/api/v2`로 올린다.
- 리소스 식별자는 경로 변수로(`/listings/{listingId}`), 조회 조건은 쿼리 파라미터로 둔다.
- 컬렉션과 단건을 구분한다: `GET /listings`(목록) ↔ `GET /listings/{id}`(단건).
- 중첩은 **소유 관계가 분명할 때 1단계까지만** 허용한다. (`GET /posts/{postId}/comments`) 그 이상 깊어지면 쿼리 파라미터로 평탄화한다.

### 엔드포인트 표 형식 (각 API 스펙 문서가 따르는 형식)

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/listings/{listingId}` | 매물 상세 조회 | 선택 | 200 |
| POST | `/api/v1/listings/{listingId}/favorite` | 찜 등록 | 필수 | 201 |

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
    "errors": [ /* 입력 검증 실패 시 필드별 상세 (선택) */ ]
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
| 버전 | 경로 프리픽스 `/api/v1` |
| 인증 헤더 | `Authorization: Bearer <accessToken>` (JWT). 갱신은 `POST /api/v1/auth/reissue` |
| 인증 필요 표기 | 각 엔드포인트에 인증 **필수 / 선택 / 불필요**를 명시 |
| 날짜·시각 | **ISO-8601 UTC** (`2026-06-15T08:30:00Z`). 서버 저장은 UTC, 표시 변환은 클라이언트 책임. 날짜만은 `YYYY-MM-DD` |
| 식별자 | 리소스 ID는 서버 생성. 본문/경로에서 숫자(Long) 또는 문자열로 일관되게 노출 |
| 금액 | 원(KRW) 정수, 소수점 없음 (`budget: 500000`) |
| 좌표 | `lat`/`lng` 십진수(WGS84), 소수 6자리 권장 |
| enum | 응답·요청 모두 **UPPER_SNAKE_CASE 문자열**(`VISA_STUDENT`, `ARC_ISSUED`). 숫자 코드로 노출하지 않는다 |
| 불리언 | `is`/`has` 없이 명사형 필드(`favorited`, `read`) 또는 의미가 분명한 이름 |
| 네이밍 | JSON 필드·쿼리 파라미터 `lowerCamelCase`, URL 경로 `kebab-case` |
| 정렬 | `?sort=field,(asc\|desc)` (다중 정렬은 `sort` 반복) |
| 멱등성 키 | 결제·예약 등 중복 위험 POST는 `Idempotency-Key` 헤더 지원을 검토 |

## 체크리스트

- [ ] 경로가 `/api/v1` + 복수 명사 + kebab-case이고 동사를 쓰지 않는다
- [ ] 메서드·성공 status가 §1 표를 따른다 (생성 201 + Location, 삭제 204)
- [ ] 응답을 공통 래퍼(`success`/`data`/`error`)로 감쌌고 엔티티를 직접 노출하지 않는다
- [ ] 목록은 오프셋/커서 중 하나를 명시하고 §4 구조를 따른다
- [ ] 입력은 Bean Validation으로 검증하고, 실패 시 [error-response-guide](./error-response-guide.md)의 `INVALID_INPUT`을 반환한다
- [ ] 날짜(UTC ISO-8601)·금액(KRW 정수)·enum(UPPER_SNAKE)·인증 표기 규약을 지켰다
- [ ] 에러 응답은 [error-response-guide](./error-response-guide.md)를 따른다
