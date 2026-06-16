# US-3-3 — 키워드 검색(학교명·지역명·지하철역명)

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 키워드 입력 (예 연세대학교)
    C->>LIST: GET /api/v1/listings/search<br/>keyword=연세대학교&sort=DISTANCE<br/>&page=0&size=20<br/>(Authorization 선택)
    Note over LIST: 키워드를 POI 사전(학교/지역/역)에 매칭<br/>매칭 위치 좌표 기준으로 주변 매물 조회<br/>비로그인은 favorited=false
    alt 키워드 길이 위반 (누락/공백/1~50자 초과)
        LIST-->>C: 400 Bad Request<br/>error.code=INVALID_INPUT
        C-->>U: 검색어 입력 오류 안내
    else POI 매칭 (주변 매물 조회)
        LIST->>DB: 매칭 위치 좌표 기준 키워드 매물 조회
        DB-->>LIST: 주변 매물 페이지
        LIST-->>C: 200 OK<br/>data.matchedPlace( type, name, lat, lng )<br/>data.content[]( 주변 매물 ), data.page
        C-->>U: 매칭 위치 + 주변 매물 표시
    end
    Note over LIST: POI 사전에 없으면 404 아닌 200 OK<br/>matchedPlace=null, content=[]
```

## 흐름 요약

- `GET /api/v1/listings/search?keyword=...`로 `listing` 모듈이 학교/지역/역 키워드를 POI 사전에 매칭해 매칭 위치 기준으로 MongoDB에서 주변 매물을 조회하고 `data.matchedPlace`와 `data.content[]`(오프셋 페이지)를 반환한다.
- 키워드 누락/공백/길이(1~50자) 위반은 `listing` 모듈이 `400 INVALID_INPUT`으로 거부한다(검증 실패 분기는 MongoDB 접근 없음).
- POI 매칭이 없으면 에러가 아니라 `200 OK` + `matchedPlace=null`·`content=[]`로 응답한다.
