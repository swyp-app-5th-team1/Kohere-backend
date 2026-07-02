# 매물 탐색 · 찜 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

매물 리스트/지도/키워드 검색, 매물 상세, 찜 토글·찜 목록, 최근 본 매물을 다룬다. 도메인 모듈은 `listing`이며 도메인 에러 코드 prefix는 `LISTING`이다. `listingId`는 MongoDB ObjectId의 24자리 hex 문자열이다. 좌표는 WGS84 십진수(소수 6자리 권장), 금액은 KRW 정수, 날짜·시각은 UTC ISO-8601, enum은 UPPER_SNAKE_CASE다. 목록은 모두 **오프셋 페이지네이션**(`page`·`size`)을 사용한다.

공통 enum:

- `ListingType`: `GOSIWON`, `CO_LIVING`, `SHARE_HOUSE`, `OTHER`
- `ListingSort`(이름 기반 정렬 프리셋): `RECOMMENDED`(기본), `PRICE_ASC`, `DISTANCE`
- `ConditionTag`(주거 환경 조건 9종, 필터 칩·편의시설 태그 공용): `IMMEDIATE_MOVE_IN`(즉시 입주), `FEMALE_ONLY`(여성 전용), `PRIVATE_TOILET`(개인 화장실), `PRIVATE_BATH`(개인 욕실), `ENGLISH_AVAILABLE`(영어 소통 가능), `RESIDENT_REGISTRATION`(전입신고 가능), `NO_MAINTENANCE_FEE`(관리비 없음), `MEALS_PROVIDED`(식사 제공), `DOUBLE_ROOM`(2인실)
- `ContractTerm`(계약기간, 개월): `ONE_MONTH`, `THREE_MONTHS`, `SIX_MONTHS`, `TWELVE_MONTHS`
- `MatchedPlaceType`(키워드 검색 매칭 분류): `UNIVERSITY`, `REGION`, `SUBWAY_STATION`

> `ListingSort`는 api-design-guide §6의 일반 `?sort=field,(asc|desc)` 형식이 아닌 **이름 기반 정렬 프리셋**이다(추천 정렬 등 단일 필드로 표현되지 않는 정렬이 있어 enum으로 둔다). 단순 시간 정렬을 쓰는 찜 목록은 일반 `field,dir` 형식을 사용한다.

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/listings` | 매물 리스트(필터·정렬·오프셋 페이지) | 선택 | 200 |
| GET | `/api/v1/listings/map` | 지도 마커 조회(bbox 내 개별 매물 좌표) | 선택 | 200 |
| GET | `/api/v1/listings/search` | 키워드 검색(학교명·지역명·지하철역명) | 선택 | 200 |
| GET | `/api/v1/listings/{listingId}` | 매물 상세 조회(로그인 시 최근 본 매물 기록) | 선택 | 200 |
| POST | `/api/v1/listings/{listingId}/favorite` | 찜 등록(토글) | 필수 | 201 (신규) / 200 (이미 찜) |
| DELETE | `/api/v1/listings/{listingId}/favorite` | 찜 해제(토글) | 필수 | 200 |
| GET | `/api/v1/users/me/favorites` | 내 찜한 매물 목록 | 필수 | 200 |
| GET | `/api/v1/users/me/recent-listings` | 최근 본 매물(7일 이내, 최대 5건) | 필수 | 200 |

> 인증 "선택"은 토큰이 있으면 `favorited` 등 사용자 맞춤 필드를 채우고, 없으면 공개 데이터만 반환한다는 의미다. 찜·찜 목록·최근 본 매물은 모두 `me` 스코프라 타인 리소스 접근 경로가 없어 `403`이 발생하지 않는다(인증 실패는 `401`).

## 상세

### GET /api/v1/listings — 매물 리스트

- 설명: 필터·정렬을 적용한 방 상품 카드 목록을 오프셋 페이지로 반환한다. 카드 1개는 `Listing + roomOffer` 조합이다.
- 인증: 선택 (로그인 시 각 항목 `favorited` 채움)

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `swLat` | number | 선택 | — | 지도 화면 남서쪽 위도. bbox를 쓰려면 `swLat`·`swLng`·`neLat`·`neLng` 모두 필요 |
| `swLng` | number | 선택 | — | 지도 화면 남서쪽 경도 |
| `neLat` | number | 선택 | — | 지도 화면 북동쪽 위도. `swLat`보다 커야 함 |
| `neLng` | number | 선택 | — | 지도 화면 북동쪽 경도. `swLng`보다 커야 함 |
| `minBudget` | integer(KRW) | 선택 | — | roomOffer 월세 하한. 같은 roomOffer의 `pricing.monthlyRent`가 이 값 이상인 카드만 반환 |
| `maxBudget` | integer(KRW) | 선택 | — | roomOffer 월세 상한. 같은 roomOffer의 `pricing.monthlyRent`가 이 값 이하인 카드만 반환 |
| `minDeposit` | integer(KRW) | 선택 | — | roomOffer 보증금 하한. 같은 roomOffer의 `pricing.deposit`이 이 값 이상인 카드만 반환 |
| `maxDeposit` | integer(KRW) | 선택 | — | roomOffer 보증금 상한. 같은 roomOffer의 `pricing.deposit`이 이 값 이하인 카드만 반환 |
| `type` | `ListingType` | 선택 | — | Listing 기준 매물 유형. 다중 값 콤마 구분(`GOSIWON,CO_LIVING`) |
| `conditions` | `ConditionTag[]` | 선택 | — | roomOffer 기준 조건 칩. 콤마 구분 또는 같은 이름 반복 전송 가능. 같은 roomOffer가 모두 만족해야 하며, 전입신고 가능 필터는 `RESIDENT_REGISTRATION`을 포함해 요청 |
| `arcRequired` | boolean | 선택 | — | Listing 기준 ARC 필수 매물 필터. `true`면 ARC가 필수인 매물만 조회하고, `false` 또는 미전달이면 ARC 조건을 적용하지 않음 |
| `sort` | `ListingSort` | 선택 | `RECOMMENDED` | 정렬 프리셋. `RECOMMENDED`는 기본 추천순, `PRICE_ASC`는 roomOffer 월세 낮은 순, `DISTANCE`는 요청 bbox의 원본 중심점에서 가까운 Listing 순 |
| `page` | integer | 선택 | 0 | 0-base 페이지 번호 |
| `size` | integer | 선택 | 20 | 페이지 크기(최대 100) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "roomOfferId": "6858e2000000000000000101",
        "roomOfferName": "Green Zone 1",
        "title": "신촌 도보 5분 1인실 고시원",
        "type": "GOSIWON",
        "monthlyRent": 450000,
        "deposit": 0,
        "maintenanceFee": 0,
        "availableCount": 3,
        "thumbnailUrl": "https://cdn.kohere.app/listings/6858e2000000000000000001/thumb.jpg",
        "lat": 37.555134,
        "lng": 126.936893,
        "address": "서울 서대문구 ...",
        "conditions": ["ENGLISH_AVAILABLE", "RESIDENT_REGISTRATION"],
        "distanceMeters": 320,
        "favorited": true,
        "favoriteCount": 12
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 137,
      "totalPages": 7,
      "hasNext": true
    }
  },
  "error": null
}
```

- 목록의 각 항목은 매물 자체가 아니라 해당 매물 안의 활성 `roomOffer` 카드다. 같은 매물에 조건을 만족하는 `roomOffer`가 여러 개 있으면 같은 `listingId`가 여러 번 내려갈 수 있고, 각 항목은 서로 다른 `roomOfferId`를 가진다.
- 필터가 없으면 조회 범위 안의 공개 매물에 속한 모든 활성 `roomOffer`를 반환한다. 필터가 있으면 같은 `roomOffer`가 가격·보증금·재고·옵션 조건을 모두 만족하는 항목만 반환한다.
- `monthlyRent`·`deposit`·`maintenanceFee`·`availableCount`·`conditions`는 모두 해당 `roomOffer` 기준 값이다. 상세 화면에서는 `roomOffers[]`에서 같은 `roomOfferId`를 찾아 방 상품 상세를 표시할 수 있다.
- `availableCount`는 같은 조건의 실제 방 묶음 중 현재 계약 가능한 수량이다. `conditions=IMMEDIATE_MOVE_IN`이면 같은 roomOffer의 `availableCount`가 1 이상이어야 통과한다.
- `sort=PRICE_ASC`는 조건에 맞는 `roomOffer`들의 월세 오름차순으로 정렬한다.
- `sort=DISTANCE`는 프론트가 별도 중심 좌표를 보내지 않는다. 서버가 요청 bbox(`swLat`·`swLng`·`neLat`·`neLng`)의 원본 중심점을 계산해 가까운 Listing 순으로 정렬한다. 따라서 `sort=DISTANCE`를 쓰려면 bbox 네 좌표가 모두 필요하다.
- `distanceMeters`는 bbox가 제공된 경우 서버가 계산한 원본 bbox 중심점 기준 직선 거리다. bbox 없이 조회하면 `null`이다.
- 전입신고 가능 여부는 별도 boolean 파라미터가 아니라 `conditions=RESIDENT_REGISTRATION`으로 요청한다.
- `arcRequired=false`는 "ARC 불필요 매물만"이 아니라 "ARC 조건을 적용하지 않음"이다.
- 비로그인 시 `favorited`는 `false`로 고정한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 범위/enum 위반(`minBudget>maxBudget`, 미정의 `conditions`/`sort` 등), `size` 범위 초과 |
| 400 | `LISTING_INVALID_SORT_PARAM` | `sort=DISTANCE`인데 bbox 네 좌표 누락 |
| 400 | `MALFORMED_REQUEST` | 타입 불일치(숫자 파라미터에 비숫자 등) |

### GET /api/v1/listings/map — 지도 마커 조회

- 설명: bbox 영역 내 매물의 개별 마커 좌표를 반환한다. 클러스터링은 프론트 지도 SDK가 화면 기준으로 처리한다. 필터 기준은 목록과 같지만, 마커는 roomOffer가 아니라 Listing 기준으로 반환한다.
- 인증: 선택

Query 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `swLat` | number | bbox 모드 필수 | 남서 위도 |
| `swLng` | number | bbox 모드 필수 | 남서 경도 |
| `neLat` | number | bbox 모드 필수 | 북동 위도(`swLat` 이상) |
| `neLng` | number | bbox 모드 필수 | 북동 경도(`swLng` 이상) |
| `minBudget`/`maxBudget`/`minDeposit`/`maxDeposit`/`type`/`conditions`/`arcRequired` | (리스트와 동일) | 선택 | 리스트와 동일한 필터 적용. 단 응답은 조건에 맞는 roomOffer 카드 수가 아니라 매물 마커 수 기준 |

> 지도 마커 조회는 bbox 4좌표가 모두 필요하다. 서버는 `/listings` 목록 조회와 동일하게 요청 bbox를 20% 확장해 조회한다.
> 한 Listing 안에 조건을 만족하는 roomOffer가 여러 개 있어도 지도 마커는 해당 Listing 위치에 1개만 반환한다.
> 전입신고 가능 여부는 `conditions=RESIDENT_REGISTRATION`으로 필터링하며, `arcRequired=true`일 때만 ARC 필수 매물로 좁힌다.

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "markers": [
      { "listingId": "6858e2000000000000000001", "lat": 37.5489, "lng": 126.9412 }
    ],
    "total": 1
  },
  "error": null
}
```

- `markers[]`는 프론트 지도 SDK가 마커와 클러스터를 만들 때 사용하는 최소 데이터다.
- `title`, 가격, 썸네일 등 카드 정보는 포함하지 않는다. 마커 선택 후 카드 목록이나 상세 정보가 필요하면 `/api/v1/listings` 또는 `/api/v1/listings/{listingId}`를 호출한다.
- 결과 수가 서버 상한(예: 500건)을 초과하면 `LISTING_AREA_TOO_LARGE`를 반환한다. 클라이언트는 지도를 더 확대하거나 bbox를 좁혀 다시 호출한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `LISTING_INVALID_BBOX` | bbox 좌표 불완전/모순(`swLat>neLat` 등) |
| 400 | `LISTING_AREA_TOO_LARGE` | 지도 마커 결과 수가 서버 상한 초과 |
| 400 | `INVALID_INPUT` | 필터 enum/범위 위반 등 |

### GET /api/v1/listings/search — 키워드 검색

- 설명: 학교명·지역명·지하철역명을 키워드로 매칭해 해당 위치와 주변 매물을 오프셋 페이지로 반환한다.
- 인증: 선택
- MVP 구현: 검색 가능한 장소는 MongoDB `searchPlaces` POI 사전으로 관리한다. 서버는 `name`/`aliases`를 비교해
  `정확히 일치 > 별칭 일치 > 앞부분 일치 > 포함 일치` 순으로 가장 적절한 장소 1개를 고른다.
- MVP 구현: 매칭된 장소 좌표 기준 **3km 이내** 공개 방 상품 카드를 반환하며, 기본 정렬은 거리순이다.

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 필수 | — | 검색어(학교/지역/역). 1~50자 |
| `sort` | `ListingSort` | 선택 | `DISTANCE` | 매칭 위치 기준 정렬 프리셋 |
| `page` | integer | 선택 | 0 | 0-base 페이지 번호 |
| `size` | integer | 선택 | 20 | 페이지 크기(최대 100) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "matchedPlace": {
      "type": "UNIVERSITY",
      "name": "연세대학교",
      "lat": 37.565784,
      "lng": 126.938572
    },
    "content": [ /* 리스트 항목과 동일 스키마 */ ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 42,
      "totalPages": 3,
      "hasNext": true
    }
  },
  "error": null
}
```

- `matchedPlace.type`은 `MatchedPlaceType`(`UNIVERSITY`/`REGION`/`SUBWAY_STATION`) 중 하나다.
- 매칭 결과가 없으면 `matchedPlace=null`, `content=[]`로 `200 OK`(404 아님).
- `matchedPlace=null`이면 프론트는 "검색된 장소가 없어요" 상태를 표시할 수 있다. `matchedPlace`가 있고 `content=[]`이면
  장소는 찾았지만 3km 이내 매물이 없는 상태다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 키워드 누락/공백/길이(1~50자) 위반, `size` 범위 초과 |

### GET /api/v1/listings/{listingId} — 매물 상세

- 설명: 단건 매물 상세를 반환한다. 인증 사용자면 최근 본 매물에 upsert한다.
- 인증: 선택 (로그인 시 `favorited` 채움 + 최근 본 매물 기록)

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 매물 식별자(ObjectId hex 문자열) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "listingId": "6858e2000000000000000001",
    "title": "신촌 도보 5분 1인실 고시원",
    "type": "GOSIWON",
    "imageUrls": [
      "https://cdn.kohere.app/listings/6858e2000000000000000001/1.jpg",
      "https://cdn.kohere.app/listings/6858e2000000000000000001/2.jpg"
    ],
    "location": {
      "lat": 37.555134,
      "lng": 126.936893,
      "address": "서울 서대문구 신촌로 ...",
      "addressDetail": "3층 305호"
    },
    "nearestTransit": { "type": "SUBWAY", "name": "Seoul Nat'l Univ.", "walkMinutes": 5 },
    "nearbyPlacesDescription": "CU, 스타벅스, 약국, 헬스장",
    "featureSummary": ["FEMALE_ONLY", "RESIDENT_REGISTRATION", "NO_MAINTENANCE_FEE"],
    "propertyPolicies": {
      "arcRequired": false,
      "residentRegistrationAvailable": true,
      "englishAvailable": false,
      "mealsProvided": true
    },
    "roomOffers": [
      {
        "roomOfferId": "6858e2000000000000000101",
        "name": "스탠다드 1인실",
        "status": "ACTIVE",
        "pricing": {
          "monthlyRent": 300000,
          "deposit": 300000,
          "maintenanceFee": 0,
          "currency": "KRW"
        },
        "contract": {
          "minStayMonths": 2,
          "maxStayMonths": 6
        },
        "inventory": {
          "totalCount": 10,
          "availableCount": 0,
          "nextAvailableFrom": null
        },
        "genderPolicy": "FEMALE_ONLY",
        "filterTags": ["FEMALE_ONLY", "RESIDENT_REGISTRATION", "NO_MAINTENANCE_FEE"]
      }
    ],
    "landlordId": 77,
    "favorited": true,
    "favoriteCount": 12,
    "createdAt": "2026-05-30T02:11:00Z"
  },
  "error": null
}
```

- 전화번호 등 직접 연락처는 노출하지 않는다(신청·문의는 채팅으로만 연결).
- `roomOffers[]`는 같은 가격·조건의 실제 방 묶음이다. 필터 조건은 같은 `roomOffer`가 가격·재고·옵션을 모두 만족하는지 기준으로 판단한다.
- 비로그인 시 `favorited=false`, 최근 본 매물 기록은 생성하지 않는다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 404 | `LISTING_NOT_FOUND` | 없음/비공개/삭제된 매물 |

### POST /api/v1/listings/{listingId}/favorite — 찜 등록(토글)

- 설명: 매물을 찜한다. (userId, listingId) 유니크로 멱등 보장.
- 인증: 필수

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 매물 식별자(ObjectId hex 문자열) |

Request Body: 없음

성공 Response — 201 Created (신규 찜) / 200 OK (이미 찜):

```jsonc
{
  "success": true,
  "data": {
    "favorited": true,
    "favoriteCount": 13
  },
  "error": null
}
```

- 신규로 찜이 **생성**되면 `201 Created`로 반환한다(생성=201, api-design-guide §1).
- 이미 찜한 상태에서 다시 호출하면 생성이 일어나지 않으므로 `200 OK`로 현재 상태(`favorited: true`)를 멱등하게 반환한다(중복 행 미생성, 별도 충돌 에러 아님). 이는 04-booking-inquiry-chat 스펙의 문의 생성(신규 201 / 기존 200) 패턴과 일관된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제된 매물 |

### DELETE /api/v1/listings/{listingId}/favorite — 찜 해제(토글)

- 설명: 찜을 해제한다. 찜하지 않은 매물 해제도 멱등하게 처리한다.
- 인증: 필수

Path 파라미터: `listingId` (위와 동일)

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "favorited": false,
    "favoriteCount": 12
  },
  "error": null
}
```

> 토글 액션은 변경 후 현재 상태(`favorited`, `favoriteCount`)를 `data`에 담아야 하므로 본문 없는 `204`가 아닌 `200`을 사용한다(api-design-guide §3-2 토글성 액션 — 일반 삭제 `204` 규칙의 명시적 예외). 찜하지 않은 매물 해제도 에러 없이 멱등하게 `favorited: false`를 반환한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제된 매물 |

### GET /api/v1/users/me/favorites — 내 찜한 매물 목록

- 설명: 로그인 사용자가 찜한 매물 목록을 오프셋 페이지로 반환한다.
- 인증: 필수

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | integer | 선택 | 0 | 0-base 페이지 번호 |
| `size` | integer | 선택 | 20 | 페이지 크기(최대 100) |
| `sort` | string | 선택 | `favoritedAt,desc` | 찜한 시각 기준 정렬(api-design-guide §6 `field,dir` 형식) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "신촌 도보 5분 1인실 고시원",
        "type": "GOSIWON",
        "monthlyRent": 450000,
        "deposit": 0,
        "thumbnailUrl": "https://cdn.kohere.app/listings/6858e2000000000000000001/thumb.jpg",
        "location": { "lat": 37.555134, "lng": 126.936893, "address": "서울 서대문구 ..." },
        "conditions": ["ENGLISH_AVAILABLE"],
        "favorited": true,
        "favoriteCount": 13,
        "favoritedAt": "2026-06-10T11:20:00Z"
      }
    ],
    "page": { "number": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false }
  },
  "error": null
}
```

- 항목 모두 `favorited=true`다. 비어 있으면 `content=[]`, `200 OK`.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `size` 범위 초과, `sort` 형식 오류 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |

### GET /api/v1/users/me/recent-listings — 최근 본 매물

- 설명: 7일 이내에 조회한 매물을 최신순 최대 5건 반환한다(요구사항 정의서 기준). 페이지네이션 없이 고정 상한이므로 `content` 배열만 반환하고 `page` 객체는 두지 않는다.
- 인증: 필수

Query 파라미터: 없음 (상한 5건 고정)

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "홍대입구 코리빙 2인실",
        "type": "CO_LIVING",
        "monthlyRent": 600000,
        "deposit": 1000000,
        "thumbnailUrl": "https://cdn.kohere.app/listings/6858e2000000000000000001/thumb.jpg",
        "location": { "lat": 37.5571, "lng": 126.9245, "address": "서울 마포구 ..." },
        "favorited": false,
        "viewedAt": "2026-06-15T01:30:00Z"
      }
    ]
  },
  "error": null
}
```

- 7일이 지난 기록은 응답에서 제외한다(만료 즉시 숨김 + 배치 삭제, 정리 주기는 운영 설정값).
- 같은 매물 재조회는 새 항목을 만들지 않고 `viewedAt`만 갱신한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |

## 도메인 에러 코드

> prefix는 `LISTING`. 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN` 등)는 [error-response-guide](../error-response-guide.md) §4를 그대로 쓰며 여기서 재정의하지 않는다.

| code | status | 의미 |
| --- | --- | --- |
| `LISTING_NOT_FOUND` | 404 | 존재하지 않거나 비공개/삭제된 매물 |
| `LISTING_INVALID_SORT_PARAM` | 400 | `sort=DISTANCE`인데 bbox 네 좌표가 누락됨 |
| `LISTING_INVALID_BBOX` | 400 | bbox 좌표 불완전/모순(`swLat>neLat` 등) |
| `LISTING_AREA_TOO_LARGE` | 400 | 지도 마커 결과가 서버 상한을 초과 |

> `LISTING_NOT_FOUND`는 04-booking-inquiry-chat 스펙에서도 참조한다. 카탈로그 중복 등록을 피하기 위해 해당 코드의 정본 정의는 본 listing 스펙에 둔다.
> 이미 찜/미찜 상태에서의 토글은 별도 충돌 에러(`LISTING_ALREADY_FAVORITED` 등)로 보지 않고 멱등하게 현재 상태를 반환한다(등록은 신규 201 / 기존 200, 해제는 항상 200). 만약 "이미 찜" 충돌을 명시적으로 알리는 정책이 정해지면 `409`로 별도 코드를 추가한다.
