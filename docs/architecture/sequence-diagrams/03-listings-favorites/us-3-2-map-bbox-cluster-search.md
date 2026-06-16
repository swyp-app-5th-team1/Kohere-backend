# US-3-2 — 지도(bbox/반경) 검색과 클러스터 집계

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 지도 이동/확대 (영역 변경)
    C->>LIST: GET /api/v1/listings/map<br/>swLat=37.49&swLng=126.95<br/>&neLat=37.57&neLng=127.05<br/>&zoom=13&cluster=true<br/>(Authorization 선택)
    Note over LIST: bbox/반경 모드 상호 배타 검증<br/>좌표 그리드로 마커 집계<br/>비클러스터는 결과 상한(예 500건) 검사
    alt 정상 클러스터 조회
        LIST->>DB: 지도 bbox 매물 조회·클러스터 집계
        DB-->>LIST: 격자별 마커 집계 결과
        LIST-->>C: 200 OK<br/>data.clusters[]( lat, lng, count,<br/>count==1일 때 listingId )<br/>data.total
        C-->>U: 지도에 클러스터 마커 표시
    else 좌표 불완전/모순/모드 혼용
        LIST-->>C: 400 Bad Request<br/>error.code=LISTING_INVALID_BBOX
        C-->>U: 영역 좌표 오류 안내
    else cluster=false인데 결과가 상한 초과
        LIST-->>C: 400 Bad Request<br/>error.code=LISTING_AREA_TOO_LARGE
        C-->>U: 클러스터 사용 유도 안내
    end
```

## 흐름 요약

- 지도 패닝 시 `GET /api/v1/listings/map`을 호출해 `listing` 모듈이 MongoDB에서 bbox(또는 center+radius) 영역의 매물을 조회·줌 레벨 기준 클러스터로 집계한다(`200 OK` + `data.clusters[]`/`total`).
- bbox 4좌표 불완전·모순(`swLat>neLat`) 또는 bbox·반경 모드 혼용은 `listing` 모듈이 `400 LISTING_INVALID_BBOX`로 거부한다(검증 실패 분기는 MongoDB 접근 없음).
- 비클러스터(`cluster=false`) 결과가 상한을 초과하면 `400 LISTING_AREA_TOO_LARGE`로 클러스터 전환을 유도한다.
