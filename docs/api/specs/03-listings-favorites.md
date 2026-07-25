# 매물 탐색 · 찜 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

매물 리스트/지도/키워드 검색, 매물 상세, 찜 토글·찜 목록, 최근 본 매물을 다룬다. 도메인 모듈은 `listing`이며 도메인 에러 코드 prefix는 `LISTING`이다. `listingId`는 MongoDB ObjectId의 24자리 hex 문자열이다. 좌표는 WGS84 십진수(소수 6자리 권장), 금액은 KRW 정수, 날짜·시각은 UTC ISO-8601, enum은 UPPER_SNAKE_CASE다. 목록은 모두 **오프셋 페이지네이션**(`page`·`size`)을 사용한다.

> **다국어 응답 규칙([ADR-0037](../../adr/0037-listing-localization-and-code-catalog.md))**: 매물명·주소·역명·방 이름·설명은 서버가 사용자 언어 문자열 하나를 선택해 반환한다. `type`·`rentalType`·`genderPolicy`·교통/건물/시설/조건처럼 UI에 표시하는 공통 코드는 `{ "code": "FEMALE_ONLY", "label": "Female Only" }` 형태다. 프론트는 **label을 표시**하고 **code를 필터 요청과 내부 비교에 사용**한다. 로그인 사용자는 계정에서 선택한 표시 언어(`users.lang`), 비로그인 사용자는 영어가 기본이며 미지원 언어도 영어로 폴백한다. 요청의 `type`·`conditions`는 계속 기존 UPPER_SNAKE code를 보낸다.

공통 enum:

- `ListingType`: `GOSHIWON`, `CO_LIVING`, `SHARE_HOUSE`, `OTHER`
- `ListingSort`(이름 기반 정렬 프리셋): `RECOMMENDED`(기본), `PRICE_ASC`, `DISTANCE`
- `ConditionTag`(매물 옵션 필터 9종): `MOVE_IN_NOW`(즉시 입주), `FEMALE_ONLY`(여성 전용), `MEALS_INCLUDED`(식사 제공), `DOUBLE_ROOM`(2인실), `PRIVATE_BATH`(개인 욕실), `ENGLISH_OK`(영어 소통 가능), `ADDRESS_REGISTRATION`(전입신고 가능), `NO_MAINT_FEE`(관리비 없음), `NO_ARC`(ARC 없이 가능)
- `ContractTerm`(계약기간, 개월): `ONE_MONTH`, `THREE_MONTHS`, `SIX_MONTHS`, `TWELVE_MONTHS`
- `MatchedPlaceType`(키워드 검색 매칭 분류): `UNIVERSITY`, `REGION`, `SUBWAY_STATION`

> `ListingSort`는 api-design-guide §6의 일반 `?sort=field,(asc|desc)` 형식이 아닌 **이름 기반 정렬 프리셋**이다(추천 정렬 등 단일 필드로 표현되지 않는 정렬이 있어 enum으로 둔다). 단순 시간 정렬을 쓰는 찜 목록은 일반 `field,dir` 형식을 사용한다.

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/listings` | 매물 리스트(필터·정렬·오프셋 페이지) | 선택 | 200 |
| GET | `/api/v1/listings/places` | 네이버 지역 검색 장소 후보(최대 5개) | 불필요 | 200 |
| GET | `/api/v1/listings/map` | 지도 마커 조회(bbox 내 개별 매물 좌표) | 선택 | 200 |
| GET | `/api/v1/listings/search` | 키워드 검색(학교명·지역명·지하철역명) | 선택 | 200 |
| GET | `/api/v1/listings/{listingId}` | 매물 상세 조회(정식 로그인 시 최근 본 기록) | 선택 | 200 |
| POST | `/api/v1/listings/{listingId}/favorite` | 찜 등록(토글) | 필수 | 201 (신규) / 200 (이미 찜) |
| DELETE | `/api/v1/listings/{listingId}/favorite` | 찜 해제(토글) | 필수 | 200 |
| GET | `/api/v1/users/me/favorites` | 내 찜한 매물 목록 | 필수 | 200 |
| GET | `/api/v1/users/me/recent-listings` | 최근 본 매물(최신순 최대 10건) | 필수 | 200 |

> 목록·지도·검색·장소 후보·상세는 가입 전부터 사용할 수 있는 공개 API다. 온보딩을 완료한 정식 사용자 토큰이 있으면 계정 언어와 상세 찜 상태를 적용하고 상세
> 조회를 최근 본 매물로 기록한다. 비로그인·온보딩 미완료·검증 실패 토큰은 공개 조회에서 익명으로 처리해 영어와 `favorited=false`를 사용하며 최근 본 기록을 남기지
> 않는다. 찜·찜 목록·최근 본 목록은 온보딩 완료 사용자(`ROLE_USER`) 전용이다. 토큰이 없거나 만료·위조되면 `401`, 온보딩 미완료 토큰이면 `403
> AUTH_ONBOARDING_REQUIRED`다.

## 상세

### GET /api/v1/listings — 매물 리스트

- 설명: 지도 바텀시트나 매물 리스트 화면에 표시할 매물 목록을 반환한다. 응답 항목 1개가 화면의 카드 1개가 된다.
- 인증: 선택 (로그인 시 각 항목 `favorited` 채움)

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `swLat` | number | 선택 | — | 현재 지도 화면의 남서쪽 위도. 지도 기준 목록을 갱신할 때 네 좌표를 모두 보낸다 |
| `swLng` | number | 선택 | — | 현재 지도 화면의 남서쪽 경도 |
| `neLat` | number | 선택 | — | 현재 지도 화면의 북동쪽 위도. `swLat`보다 커야 함 |
| `neLng` | number | 선택 | — | 현재 지도 화면의 북동쪽 경도. `swLng`보다 커야 함 |
| `minBudget` | integer(KRW) | 선택 | — | 월세 최소값. 조건에 맞는 방 타입이 있는 매물만 보여준다 |
| `maxBudget` | integer(KRW) | 선택 | — | 월세 최대값. 카드 가격은 응답의 `roomOffers[].pricing`으로 계산한다 |
| `minDeposit` | integer(KRW) | 선택 | — | 보증금 최소값 |
| `maxDeposit` | integer(KRW) | 선택 | — | 보증금 최대값 |
| `type` | `ListingType` | 선택 | — | 매물 유형 필터 칩. 다중 값 콤마 구분(`GOSHIWON,CO_LIVING`) |
| `conditions` | `ConditionTag[]` | 선택 | — | 옵션 필터 칩. `MOVE_IN_NOW`, `FEMALE_ONLY`, `PRIVATE_BATH`, `ADDRESS_REGISTRATION`, `NO_ARC` 등을 반복 파라미터 또는 콤마로 전송 |
| `sort` | `ListingSort` | 선택 | `RECOMMENDED` | 정렬 방식. `RECOMMENDED` 추천순, `PRICE_ASC` 낮은 월세순, `DISTANCE` 현재 지도 중심에서 가까운 순 |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호. 무한스크롤의 다음 페이지 요청에 사용 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 매물 수(최대 100) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Single-room goshiwon, 5 minutes from Sinchon",
        "type": { "code": "GOSHIWON", "label": "Goshiwon" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": {
          "code": "FULL_REFUND_BEFORE_7_DAYS",
          "description": "Full refund for cancellations made at least 7 days before move-in."
        },
        "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
        "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
        "location": { "lat": 37.555134, "lng": 126.936893 },
        "address": {
          "city": "SEOUL",
          "district": "SEODAEMUN_GU",
          "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Sinchon Station",
          "walkMinutes": 5,
          "nearbyPlacesDescription": "Convenience store, pharmacy"
        },
        "nearbyUniversityCodes": ["YONSEI"],
        "building": {
          "type": { "code": "VILLA", "label": "Villa" },
          "usedFloorMin": 1,
          "usedFloorMax": 2,
          "totalFloors": 4,
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "propertyPolicies": {
          "arcRequired": false,
          "residentRegistrationAvailable": true,
          "studySuitable": true,
          "mealsProvided": true,
          "englishAvailable": false
        },
        "facilities": {
          "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
          "kitchen": [{ "code": "MICROWAVE", "label": "Microwave" }],
          "laundry": [{ "code": "COIN_LAUNDRY", "label": "Coin Laundry" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "type": { "code": "SHARED_TOILET", "label": "Shared Toilet" }, "count": 6 }],
          "providedSupplies": [{ "code": "BEDDING", "label": "Bedding" }]
        },
        "conditions": [
          { "code": "ENGLISH_OK", "label": "English OK" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
          { "code": "NO_ARC", "label": "No ARC" }
        ],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000101",
            "name": "Standard Single Room",
            "status": "ACTIVE",
            "pricing": {
              "monthlyRent": 380000,
              "deposit": 200000,
              "maintenanceFee": 20000,
              "currency": "KRW"
            },
            "inventory": {
              "totalCount": 10,
              "availableCount": 2,
              "nextAvailableFrom": null
            },
            "filterTags": [
              { "code": "ENGLISH_OK", "label": "English OK" },
              { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
            ],
            "roomImageUrls": []
          }
        ],
        "descriptions": {
          "description": "A well-maintained goshiwon within a five-minute walk of the subway station.",
          "extraNotes": "외국인 환영"
        },
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "distanceMeters": 320,
        "favorited": true,
        "favoriteCount": 12,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z"
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

- 카드 제목은 `title`, 대표 이미지는 `imageUrls[0]`, 주소는 `address.fullAddress`, 교통 배지는 `nearestTransit.name`과 `nearestTransit.walkMinutes`를 사용한다.
- 가격/보증금/관리비는 `roomOffers[].pricing`에서 읽는다. 여러 방 타입이 있으면 프론트에서 최저~최고 범위를 계산해 카드에 표시한다.
- 계약기간은 방 타입이 아니라 매물 공통 값인 `contract.minStayMonths/maxStayMonths`를 사용한다.
- 조건 배지/Property Details features는 상위 `conditions`의 `label`을 표시한다. 이 값은 공개 가능한 ACTIVE 방 타입들의 `roomOffers[].filterTags` 합집합이며, `propertyPolicies.arcRequired=false`이면 `code=NO_ARC`가 함께 포함된다. 필터 요청에는 `code`를 보낸다.
- 필터가 있으면 `roomOffers[]`에는 조건을 통과한 방 타입만 들어온다. 필터가 없으면 노출 가능한 ACTIVE 방 타입 전체가 들어온다.
- 방 타입별 세부 조건 배지가 필요하면 각 `roomOffers[].filterTags`를 사용한다.
- 난방 방식은 `building.heatingSystem`이 아니라 `facilities.heatingSystem[]`에서 읽는다.
- `distanceMeters`가 있으면 거리 라벨로 표시하고, 없으면 숨긴다.
- `favorited`는 하트 상태, `favoriteCount`는 찜 수 표시값이다. 비로그인 목록에서는 `favorited=false`로 표시하면 된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 범위/enum 위반(`minBudget>maxBudget`, 미정의 `conditions`/`sort` 등), `size` 범위 초과 |
| 400 | `MALFORMED_REQUEST` | 타입 불일치(숫자 파라미터에 비숫자 등) |

### GET /api/v1/listings/places — 네이버 장소 후보 검색

- 설명: 지도 검색창의 키워드를 네이버 지역 검색 API로 조회하고, 사용자가 선택할 장소 후보를 정확도순 최대 5개 반환한다.
- 인증: 불필요
- 책임 범위: 장소 후보만 반환하며 MongoDB 매물은 조회하지 않는다. 프론트가 후보 좌표로 지도를 이동한 뒤 계산한 bounds를 기존 `/api/v1/listings`와 `/api/v1/listings/map`에 전달한다.
- 외부 연동: 장소 검색은 아웃바운드 포트 `PlaceSearchClient`(인프라 어댑터 `NaverPlaceSearchClient` — 네이버 지역 검색 API)로 **동기 호출**한다. 네이버 API 장애·타임아웃·인증정보 누락·응답/좌표 형식 이상 등 연동 실패는 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(공통 코드 — [error-response-guide](../error-response-guide.md)). 인증정보는 환경변수 `NAVER_SEARCH_CLIENT_ID`/`NAVER_SEARCH_CLIENT_SECRET`(SSM SecureString)로 주입한다.

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 필수 | — | 지도 검색창 입력값. 앞뒤 공백 제거 후 1~50자 |

서버가 네이버 호출에 고정하는 값:

| 이름 | 값 | 설명 |
| --- | --- | --- |
| `display` | `5` | 한 번에 받을 최대 장소 후보 수 |
| `start` | `1` | 지역 검색 API가 허용하는 검색 시작 위치 |
| `sort` | `random` | 네이버 문서상 정확도 내림차순 |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "items": [
      {
        "title": "<b>경희대학교</b> 서울캠퍼스",
        "address": "서울특별시 동대문구 회기동 1-5",
        "roadAddress": "서울특별시 동대문구 경희대로 26",
        "lat": 37.5964494,
        "lng": 127.0525009
      }
    ]
  },
  "error": null
}
```

- 네이버 원본의 `mapx/mapy`는 서버가 WGS84 십진수 `lng/lat`으로 변환한다.
- `title`은 검색어 강조를 위한 네이버의 `<b>` 태그를 그대로 유지한다.
- 정상적으로 검색 결과가 없으면 `200 OK`와 `data.items=[]`를 반환한다.
- 네이버 응답의 `lastBuildDate`, `total`, `start`, `display`, `link`, `category`, `description`, `telephone`은 공개하지 않는다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 키워드 누락·공백·길이(1~50자) 위반 |
| 502 | `UPSTREAM_ERROR` | 네이버 HTTP 오류·타임아웃·인증정보 누락·응답 또는 좌표 형식 이상 |

### GET /api/v1/listings/map — 지도 마커 조회

- 설명: 지도에 찍을 마커 좌표만 반환한다. 지도 SDK 마커/클러스터 렌더링에 사용하고, 상세한 카드 정보는 `/api/v1/listings`로 가져온다.
- 인증: 선택

Query 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `swLat` | number | bbox 모드 필수 | 남서 위도 |
| `swLng` | number | bbox 모드 필수 | 남서 경도 |
| `neLat` | number | bbox 모드 필수 | 북동 위도(`swLat` 이상) |
| `neLng` | number | bbox 모드 필수 | 북동 경도(`swLng` 이상) |
| `minBudget`/`maxBudget`/`minDeposit`/`maxDeposit`/`type`/`conditions` | (리스트와 동일) | 선택 | 목록과 같은 필터를 보내면 지도 마커와 바텀시트 목록을 같은 조건으로 맞출 수 있음 |

> 지도 마커 조회는 bbox 4좌표가 모두 필요하다. 마커와 바텀시트 목록을 같이 갱신할 때는 목록 API에도 같은 필터 값을 보내면 된다.

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

- `markers[].lat/lng`는 지도 SDK에 넘길 좌표다.
- `markers[].listingId`는 마커 선택 상태, 목록 카드 선택 상태, 상세 진입을 연결하는 키다.
- `title`, 가격, 이미지 등 카드 정보는 포함하지 않는다. 마커를 눌렀을 때 카드가 필요하면 같은 `listingId`로 목록 결과에서 찾거나 상세 API를 호출한다.
- `LISTING_AREA_TOO_LARGE`가 오면 지도를 더 확대하거나 bbox를 좁혀 다시 호출한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `LISTING_INVALID_BBOX` | bbox 좌표 불완전/모순(`swLat>neLat` 등) |
| 400 | `LISTING_AREA_TOO_LARGE` | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |
| 400 | `INVALID_INPUT` | 필터 enum/범위 위반 등 |

### GET /api/v1/listings/search — 키워드 검색

- 설명: 검색창에서 학교명·지역명·지하철역명을 입력했을 때, 매칭된 장소와 주변 매물을 함께 반환한다.
- 인증: 선택
- 매칭된 장소가 있으면 `matchedPlace.lat/lng`로 지도를 이동하고, `content[]`를 검색 결과 리스트로 표시한다.

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 필수 | — | 검색창 입력값(학교/지역/역). 1~50자 |
| `sort` | `ListingSort` | 선택 | `DISTANCE` | 검색 결과 정렬. 기본은 검색된 장소에서 가까운 순 |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 매물 수(최대 100) |

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
    "content": [ /* /api/v1/listings content[]와 같은 매물 카드 구조 */ ],
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

- `matchedPlace`가 있으면 검색 결과 화면의 지도 중심과 장소 제목으로 사용한다.
- `content[]`는 목록 API와 같은 카드 구조다. `distanceMeters`는 검색된 장소에서 매물까지의 거리 라벨로 표시한다.
- `matchedPlace=null`이면 "검색된 장소가 없어요" 상태를 표시한다.
- `matchedPlace`가 있고 `content=[]`이면 "이 주변에 매물이 없어요" 상태를 표시한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 키워드 누락/공백/길이(1~50자) 위반, `size` 범위 초과 |

### GET /api/v1/listings/{listingId} — 매물 상세

- 설명: 목록 카드나 지도 마커를 눌렀을 때 상세 화면을 그리기 위한 매물 정보를 반환한다.
- 인증: 선택. 비로그인·온보딩 미완료 사용자는 공개 상세만 받고, 온보딩 완료 사용자는 찜 상태·계정 언어·최근 본 기록이 적용된다.

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 목록/검색/마커 응답에서 받은 `listingId` |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "listingId": "6858e2000000000000000001",
    "title": "Single-room goshiwon, 5 minutes from Sinchon",
    "type": { "code": "GOSHIWON", "label": "Goshiwon" },
    "status": "PUBLISHED",
    "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
    "refundPolicy": {
      "code": "FULL_REFUND_BEFORE_7_DAYS",
      "description": "Full refund for cancellations made at least 7 days before move-in."
    },
    "contract": {
      "minStayMonths": 2,
      "maxStayMonths": 6
    },
    "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
    "location": { "lat": 37.555134, "lng": 126.936893 },
    "address": {
      "city": "SEOUL",
      "district": "SEODAEMUN_GU",
      "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
      "detail": "Room 305, 3rd floor"
    },
    "nearestTransit": {
      "type": { "code": "SUBWAY", "label": "Subway" },
      "name": "Sinchon Station",
      "walkMinutes": 5,
      "nearbyPlacesDescription": "CU, Starbucks, pharmacy, gym"
    },
    "nearbyUniversityCodes": ["YONSEI", "EWHA"],
    "building": {
      "type": { "code": "VILLA", "label": "Villa" },
      "usedFloorMin": 2,
      "usedFloorMax": 3,
      "totalFloors": 5,
      "parkingAvailable": false,
      "elevatorAvailable": false
    },
    "propertyPolicies": {
      "arcRequired": false,
      "residentRegistrationAvailable": true,
      "studySuitable": true,
      "mealsProvided": false,
      "englishAvailable": true
    },
    "facilities": {
      "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
      "kitchen": [{ "code": "SHARED_REFRIGERATOR", "label": "Shared Refrigerator" }],
      "laundry": [{ "code": "SHARED_WASHER", "label": "Shared Washer" }],
      "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
      "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
      "commonSpaces": [{ "type": { "code": "STUDY_ROOM", "label": "Study Room" }, "count": null }],
      "providedSupplies": [{ "code": "SLIPPERS", "label": "Slippers" }]
    },
    "conditions": [
      { "code": "FEMALE_ONLY", "label": "Female Only" },
      { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
      { "code": "NO_MAINT_FEE", "label": "No Maint. Fee" },
      { "code": "NO_ARC", "label": "No ARC" }
    ],
    "roomOffers": [
      {
        "roomOfferId": "6858e2000000000000000101",
        "name": "Standard Single Room",
        "status": "ACTIVE",
        "pricing": {
          "monthlyRent": 300000,
          "deposit": 300000,
          "maintenanceFee": 0,
          "currency": "KRW"
        },
        "inventory": {
          "totalCount": 10,
          "availableCount": 2,
          "nextAvailableFrom": null
        },
        "filterTags": [
          { "code": "FEMALE_ONLY", "label": "Female Only" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
          { "code": "NO_MAINT_FEE", "label": "No Maint. Fee" }
        ],
        "roomImageUrls": [
          "https://cdn.kohere.app/listings/6858e2000000000000000001/rooms/101.jpg"
        ]
      }
    ],
    "descriptions": {
      "description": "A quiet goshiwon within walking distance of Sinchon Station.",
      "extraNotes": "직접 연락처는 노출하지 않으며 신청은 앱에서 진행합니다."
    },
    "imageUrls": [
      "https://cdn.kohere.app/listings/6858e2000000000000000001/1.jpg",
      "https://cdn.kohere.app/listings/6858e2000000000000000001/2.jpg"
    ],
    "favorited": true,
    "favoriteCount": 12,
    "createdAt": "2026-05-30T02:11:00Z",
    "updatedAt": "2026-06-01T02:11:00Z"
  },
  "error": null
}
```

- 상단 제목/하트는 `title`, `favorited`, `favoriteCount`를 사용한다.
- 사진 갤러리는 `imageUrls`와 `roomOffers[].roomImageUrls`를 사용한다. 카드 대표 이미지는 `imageUrls[0]`를 우선 사용한다.
- 가격 영역은 `roomOffers[].pricing`, 계약기간은 `contract`, 주소/지도는 `address`와 `location`, 교통 정보는 `nearestTransit`으로 표시한다.
- `{code,label}` 형태는 `label`을 화면에 표시하고 `code`를 필터 요청·아이콘/분기 비교에 사용한다. `title`·주소·역명·방 이름·`descriptions.description`은 이미 사용자 언어 문자열 하나로 선택되어 온다.
- Property Details의 features/조건 배지는 상위 `conditions`를 사용한다. 방 타입별 조건은 `roomOffers[].filterTags`를 사용한다.
- 시설/정책 섹션은 `building`, `propertyPolicies`, `facilities`를 사용한다. 난방 방식은 `building.heatingSystem`이 아니라 `facilities.heatingSystem[]`에서 읽는다.
- `roomOffers[]`는 상세 화면의 Room Types 목록에 그대로 렌더링할 수 있는 ACTIVE 방 타입이다.
- 온보딩 완료 사용자의 상세 조회가 성공하면 최근 본 목록이 자동 갱신된다. 프론트에서 최근 본 저장 API를 따로 호출할 필요는 없다.
- 비로그인·온보딩 미완료 사용자는 `favorited=false`와 영어 기본 문구를 받으며 최근 본 기록을 남기지 않는다.
- 로그인 전에 본 매물은 온보딩 완료 후 최근 본 목록으로 소급 이전하지 않는다. 정식 로그인 시점 이후의 상세 조회부터 기록한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 404 | `LISTING_NOT_FOUND` | 없음/비공개/삭제된 매물 |

### POST /api/v1/listings/{listingId}/favorite — 찜 등록(토글)

- 설명: 사용자가 하트를 눌러 매물을 찜 상태로 만든다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 목록/상세 응답에서 받은 `listingId` |

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

- 응답의 `favorited=true`로 하트를 채우고, `favoriteCount`로 카드/상세의 찜 수를 갱신한다.
- 이미 찜한 매물에 다시 호출해도 에러가 아니며 현재 상태를 그대로 반환한다. 프론트는 status code와 무관하게 body 값으로 UI를 맞추면 된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제된 매물 |

### DELETE /api/v1/listings/{listingId}/favorite — 찜 해제(토글)

- 설명: 사용자가 하트를 다시 눌러 찜을 해제한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

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

> 응답의 `favorited=false`로 하트를 비우고, `favoriteCount`로 카드/상세의 찜 수를 갱신한다. 이미 해제된 매물이어도 에러가 아니라 현재 상태를 반환한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제된 매물 |

### GET /api/v1/users/me/favorites — 내 찜한 매물 목록

- 설명: 마이페이지의 찜한 매물 목록을 반환한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 찜 매물 수(최대 100) |
| `sort` | string | 선택 | `favoritedAt,desc` | 찜한 시각 최신순. 현재 화면에서는 기본값 그대로 사용하면 됨 |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Single-room goshiwon, 5 minutes from Sinchon",
        "type": { "code": "GOSHIWON", "label": "Goshiwon" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": {
          "code": "FULL_REFUND_BEFORE_7_DAYS",
          "description": "Full refund for cancellations made at least 7 days before move-in."
        },
        "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
        "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
        "location": { "lat": 37.555134, "lng": 126.936893 },
        "address": {
          "city": "SEOUL",
          "district": "SEODAEMUN_GU",
          "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Sinchon Station",
          "walkMinutes": 5,
          "nearbyPlacesDescription": "Convenience store, pharmacy"
        },
        "nearbyUniversityCodes": ["YONSEI"],
        "building": {
          "type": { "code": "VILLA", "label": "Villa" },
          "usedFloorMin": 1,
          "usedFloorMax": 2,
          "totalFloors": 4,
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "propertyPolicies": {
          "arcRequired": false,
          "residentRegistrationAvailable": true,
          "studySuitable": true,
          "mealsProvided": true,
          "englishAvailable": false
        },
        "facilities": {
          "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
          "kitchen": [{ "code": "MICROWAVE", "label": "Microwave" }],
          "laundry": [{ "code": "COIN_LAUNDRY", "label": "Coin Laundry" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "type": { "code": "SHARED_TOILET", "label": "Shared Toilet" }, "count": 6 }],
          "providedSupplies": [{ "code": "BEDDING", "label": "Bedding" }]
        },
        "conditions": [
          { "code": "ENGLISH_OK", "label": "English OK" },
          { "code": "NO_ARC", "label": "No ARC" }
        ],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000101",
            "name": "Standard Single Room",
            "status": "ACTIVE",
            "pricing": {
              "monthlyRent": 450000,
              "deposit": 0,
              "maintenanceFee": 0,
              "currency": "KRW"
            },
            "inventory": {
              "totalCount": 10,
              "availableCount": 2,
              "nextAvailableFrom": null
            },
            "filterTags": [{ "code": "ENGLISH_OK", "label": "English OK" }],
            "roomImageUrls": []
          }
        ],
        "descriptions": {
          "description": "A well-maintained goshiwon within a five-minute walk of the subway station.",
          "extraNotes": "외국인 환영"
        },
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "favorited": true,
        "favoriteCount": 13,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z",
        "favoritedAt": "2026-06-10T11:20:00Z"
      }
    ],
    "page": { "number": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false }
  },
  "error": null
}
```

- 항목 모두 `favorited=true`라 하트는 채운 상태로 표시한다.
- 카드 렌더링은 일반 목록과 같은 방식으로 `title`, `imageUrls[0]`, `address.fullAddress`, `roomOffers[].pricing`, `contract`를 사용한다.
- `favoritedAt`은 찜한 시각 표시나 최신순 정렬 확인에 사용할 수 있다.
- 찜 해제 후에는 목록을 다시 조회하거나, 클라이언트에서 해당 `listingId` 항목을 제거하면 된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `size` 범위 초과, `sort` 형식 오류 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |

### GET /api/v1/users/me/recent-listings — 최근 본 매물

- 설명: 마이페이지나 홈의 최근 본 매물 영역에 표시할 매물을 최신 조회순으로 최대 10건 반환한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Query 파라미터: 없음. 온보딩 완료 사용자가 상세 조회를 호출하면 최근 본 기록이 자동으로 갱신된다. 로그인 전 조회 기록은 저장하거나 소급 이전하지 않는다.

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Hongdae Co-living Double Room",
        "type": { "code": "CO_LIVING", "label": "Co-living" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": {
          "code": "FULL_REFUND_BEFORE_7_DAYS",
          "description": "Full refund for cancellations made at least 7 days before move-in."
        },
        "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
        "genderPolicy": { "code": "ANY", "label": "Any Gender" },
        "location": { "lat": 37.5571, "lng": 126.9245 },
        "address": {
          "city": "SEOUL",
          "district": "MAPO_GU",
          "fullAddress": "Mapo-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Hongik Univ. Station",
          "walkMinutes": 8,
          "nearbyPlacesDescription": "Convenience store, cafe"
        },
        "nearbyUniversityCodes": ["HONGIK"],
        "building": {
          "type": { "code": "OFFICETEL", "label": "Officetel" },
          "usedFloorMin": 5,
          "usedFloorMax": 7,
          "totalFloors": 12,
          "parkingAvailable": false,
          "elevatorAvailable": true
        },
        "propertyPolicies": {
          "arcRequired": false,
          "residentRegistrationAvailable": true,
          "studySuitable": true,
          "mealsProvided": false,
          "englishAvailable": true
        },
        "facilities": {
          "heatingSystem": [{ "code": "INDIVIDUAL", "label": "Individual Heating" }],
          "kitchen": [{ "code": "SHARED_REFRIGERATOR", "label": "Shared Refrigerator" }],
          "laundry": [{ "code": "SHARED_WASHER", "label": "Shared Washer" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "type": { "code": "LOUNGE", "label": "Lounge" }, "count": 1 }],
          "providedSupplies": [{ "code": "TISSUE", "label": "Toilet Paper" }]
        },
        "conditions": [
          { "code": "ENGLISH_OK", "label": "English OK" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
          { "code": "NO_ARC", "label": "No ARC" }
        ],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000201",
            "name": "Co-living Double Room",
            "status": "ACTIVE",
            "pricing": {
              "monthlyRent": 580000,
              "deposit": 1000000,
              "maintenanceFee": 30000,
              "currency": "KRW"
            },
            "inventory": {
              "totalCount": 8,
              "availableCount": 1,
              "nextAvailableFrom": null
            },
            "filterTags": [
              { "code": "ENGLISH_OK", "label": "English OK" },
              { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
            ],
            "roomImageUrls": []
          }
        ],
        "descriptions": {
          "description": "Co-living near Hongdae...",
          "extraNotes": "공용 라운지 이용 가능"
        },
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "favorited": false,
        "favoriteCount": 13,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z",
        "viewedAt": "2026-06-15T01:30:00Z"
      }
    ]
  },
  "error": null
}
```

- 카드 렌더링은 일반 목록과 같은 방식으로 `title`, `imageUrls[0]`, `address.fullAddress`, `roomOffers[].pricing`, `contract`를 사용한다.
- `viewedAt`은 마지막으로 상세 화면을 본 시각이다. 필요하면 "최근 본 시간" 보조 문구에 사용한다.
- `favorited`로 현재 하트 상태를 바로 표시한다.
- 오래되었거나 더 이상 공개되지 않는 매물은 응답에 포함되지 않는다. 빈 배열이면 최근 본 매물 없음 상태를 표시한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |

## 도메인 에러 코드

> prefix는 `LISTING`. 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN` 등)는 [error-response-guide](../error-response-guide.md) §4를 그대로 쓰며 여기서 재정의하지 않는다.

| code | status | 의미 |
| --- | --- | --- |
| `LISTING_NOT_FOUND` | 404 | 존재하지 않거나 비공개/삭제된 매물 |
| `LISTING_INVALID_SORT_PARAM` | 400 | `sort=DISTANCE`인데 bbox 네 좌표가 누락됨 |
| `LISTING_INVALID_BBOX` | 400 | bbox 좌표 불완전/모순(`swLat>neLat` 등) |
| `LISTING_AREA_TOO_LARGE` | 400 | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |

> `LISTING_NOT_FOUND`는 04-booking-inquiry-chat 스펙에서도 참조한다. 카탈로그 중복 등록을 피하기 위해 해당 코드의 정본 정의는 본 listing 스펙에 둔다.
> 하트 토글은 이미 찜/미찜 상태여도 에러로 보지 않고 현재 하트 상태와 찜 수를 반환한다. 프론트는 응답 body의 `favorited`, `favoriteCount`만 보고 UI를 맞추면 된다.
