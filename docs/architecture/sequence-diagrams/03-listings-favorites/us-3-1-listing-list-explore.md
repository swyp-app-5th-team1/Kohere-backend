# US-3-1 — 매물 리스트 탐색(필터·정렬·페이지네이션)

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 예산·조건 칩 선택, 정렬/페이지 설정
    C->>LIST: GET /api/v1/listings<br/>minBudget=300000&maxBudget=700000<br/>&conditions=ENGLISH_AVAILABLE<br/>&sort=PRICE_ASC&page=0&size=20<br/>(Authorization 선택)
    Note over LIST: 필터를 MongoDB 질의 조건으로 변환<br/>가격·조건·재고는 같은 roomOffer에 $elemMatch<br/>sort=DISTANCE면 bbox 중심점 기준<br/>로그인 시 본인 찜 여부로 favorited 채움
    alt 정상 (필터/정렬 유효)
        LIST->>DB: status=PUBLISHED + roomOffers $elemMatch<br/>+ 2dsphere/정렬로 매물 목록 조회
        DB-->>LIST: 조건에 맞는 건물 매물 후보 + roomOffers[]
        LIST-->>C: 200 OK<br/>data.content[]( listingId, type,<br/>min/max monthlyRent·deposit·maintenanceFee,<br/>availableCount, min/max stayMonths,<br/>location, conditions, distanceMeters,<br/>favorited, favoriteCount )<br/>data.page( number/size/totalElements/totalPages/hasNext )
        C-->>U: 가격 범위가 표시된 매물 카드 목록 표시
    else 범위/enum 위반 (minBudget>maxBudget, 미정의 enum 등)
        LIST-->>C: 400 Bad Request<br/>error.code=INVALID_INPUT<br/>errors[]( field, reason )
        C-->>U: 필터 입력 오류 안내
    else sort=DISTANCE인데 bbox 네 좌표 누락
        LIST-->>C: 400 Bad Request<br/>error.code=LISTING_INVALID_SORT_PARAM
        C-->>U: 기준 좌표 필요 안내
    end
```

## 흐름 요약

- 비로그인/로그인 모두 `GET /api/v1/listings`로 `listing` 모듈에서 필터·정렬·오프셋 페이지 목록을 조회하며, 성공 시 `listing` 모듈이 MongoDB에서 `status=PUBLISHED` 건물 매물 중 조건에 맞는 `roomOffers[]`를 `$elemMatch`로 찾은 뒤 조건을 만족하는 active roomOffer들을 매물 단위로 묶어 `200 OK` + `data.content[]`·`data.page`를 받는다.
- 목록 항목 1개는 `Listing` 단위 매물 카드다. 필터가 없으면 조회 범위 안의 모든 active roomOffer가 집계 대상이고, 필터가 있으면 조건을 만족하는 active roomOffer만 집계 대상이다. 같은 매물 안에 조건을 만족하는 방 상품이 여러 개 있어도 같은 `listingId`는 한 번만 내려간다.
- 목록의 `minMonthlyRent`/`maxMonthlyRent`, `minDeposit`/`maxDeposit`, `minMaintenanceFee`/`maxMaintenanceFee`, `minStayMonths`/`maxStayMonths`, `availableCount`, `conditions`는 조건을 통과한 roomOffer 목록에서 집계한 값이다. 실제 방 상품 상세는 단건 상세의 `roomOffers[]`에서 확인한다.
- 로그인 시 `listing` 모듈이 각 항목의 `favorited`를 본인 찜 여부로 채우고(인증 선택), 비로그인은 `false`로 고정된다.
- 범위/enum 위반은 `400 INVALID_INPUT` + `errors[]`로, `sort=DISTANCE`인데 bbox 네 좌표가 없으면 `400 LISTING_INVALID_SORT_PARAM`으로 거부한다(검증 실패 분기는 MongoDB 접근 없음).
