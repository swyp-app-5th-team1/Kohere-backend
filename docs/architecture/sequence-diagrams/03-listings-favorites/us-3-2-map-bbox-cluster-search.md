# US-3-2 — 지도 bbox 마커 조회

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 지도 이동/확대 (영역 변경)
    C->>LIST: GET /api/v1/listings/map<br/>swLat=37.49&swLng=126.95<br/>&neLat=37.57&neLng=127.05<br/>(Authorization 선택)
    Note over LIST: bbox 4좌표 검증<br/>요청 bbox 20% 확장<br/>location 2dsphere + roomOffers 필터 적용<br/>결과 상한(예 500건) 검사
    alt 정상 마커 조회
        LIST->>DB: 지도 bbox 매물 조회<br/>(location 2dsphere, status=PUBLISHED)
        DB-->>LIST: 개별 매물 좌표 목록
        LIST-->>C: 200 OK<br/>data.markers[]( listingId, lat, lng )<br/>data.total
        C-->>U: 지도 SDK가 마커 표시·클러스터링 처리
    else 좌표 불완전/모순
        LIST-->>C: 400 Bad Request<br/>error.code=LISTING_INVALID_BBOX
        C-->>U: 영역 좌표 오류 안내
    else 결과가 상한 초과
        LIST-->>C: 400 Bad Request<br/>error.code=LISTING_AREA_TOO_LARGE
        C-->>U: 지도 확대 또는 범위 축소 유도
    end
```

## 흐름 요약

- 지도 패닝 시 `GET /api/v1/listings/map`을 호출해 `listing` 모듈이 MongoDB `location`의 `2dsphere` 인덱스로 bbox 영역의 공개 건물 매물을 조회한다. 가격·조건 필터는 `roomOffers[]` 기준으로 적용하고, 성공 시 프론트 지도 SDK가 사용할 `data.markers[]`(`listingId`, `lat`, `lng`)와 `total`을 반환한다.
- 서버는 클러스터링을 하지 않는다. 프론트 지도 SDK가 화면상 가까운 마커를 자체 기준으로 묶는다.
- bbox 4좌표 불완전·모순(`swLat>neLat`)은 `listing` 모듈이 `400 LISTING_INVALID_BBOX`로 거부한다(검증 실패 분기는 MongoDB 접근 없음).
- 결과가 서버 상한을 초과하면 `400 LISTING_AREA_TOO_LARGE`로 범위 축소 또는 확대 조회를 유도한다.
