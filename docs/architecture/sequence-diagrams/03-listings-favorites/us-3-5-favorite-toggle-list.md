# US-3-5 — 찜 토글·찜 목록(인증 필수)

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 찜 버튼 탭 (등록)
    C->>SEC: POST /api/v1/listings/{listingId}/favorite<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 유도
    else 검증 통과
        SEC->>LIST: 인증된 요청 전달 (userId)
        alt 신규 찜 생성
            Note over LIST: listingId는 ObjectId 문자열<br/>(userId, listingId) 유니크로 멱등 보장<br/>favoriteCount 원자적 증가
            LIST->>DB: 찜 insert ( (userId, ObjectId listingId) 유니크 )<br/>favoriteCount 원자적 증가
            DB-->>LIST: 생성 완료, favoriteCount 증가값
            LIST-->>C: 201 Created<br/>data( favorited=true, favoriteCount=증가값 )
            C-->>U: 찜 등록 상태 표시
        else 이미 찜한 상태 (재호출)
            LIST-->>C: 200 OK<br/>data( favorited=true, favoriteCount=현재값 )
            C-->>U: 찜 등록 상태 유지
        end
    end
    U->>C: 찜 버튼 다시 탭 (해제)
    C->>SEC: DELETE /api/v1/listings/{listingId}/favorite<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>LIST: 인증된 요청 전달 (userId)
    Note over LIST: 미찜 매물 해제도 멱등 처리<br/>삭제 성공한 경우에만 favoriteCount 감소
    LIST->>DB: 찜 delete (멱등)
    alt 찜 문서 삭제됨
        LIST->>DB: favoriteCount 원자적 감소
        DB-->>LIST: favoriteCount 감소값
    else 원래 미찜 상태
        Note over LIST: 카운트 변경 없이 조회한 현재값 사용
    end
    LIST-->>C: 200 OK<br/>data( favorited=false, favoriteCount=현재값 )
    C-->>U: 찜 해제 상태 표시
    U->>C: 찜 목록 열기
    C->>SEC: GET /api/v1/users/me/favorites?page=0&size=20<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>LIST: 인증된 요청 전달 (userId)
    LIST->>DB: 본인 찜 목록 조회 (오프셋 페이지)
    DB-->>LIST: 찜한 매물 페이지
    LIST-->>C: 200 OK<br/>data.content[]( favorited=true ... ), data.page
    C-->>U: 찜한 매물 목록 표시
```

## 흐름 요약

- 찜 등록은 `POST /api/v1/listings/{listingId}/favorite`로 `listing` 모듈이 처리하며, `listingId`는 ObjectId 문자열이다. 신규 생성 시 MongoDB에 찜 insert(`(userId, listingId)` 유니크)·`favoriteCount` 원자적 증가 후 `201 Created`(이미 찜이면 멱등하게 `200 OK`) + `data( favorited, favoriteCount )`를 반환한다.
- 찜 해제는 `listing` 모듈의 `DELETE .../favorite`로 MongoDB에서 찜 문서를 삭제하고, 실제 삭제가 성공한 경우에만 `favoriteCount`를 감소시킨 뒤 항상 `200 OK`를 반환한다. 원래 미찜 상태면 카운트를 변경하지 않고 현재값을 반환한다.
- 찜·찜 목록은 모두 인증 필수(`me` 스코프)라 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 `listing` 모듈로 전달하며, 토큰 없음/만료/위조는 SEC가 `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`로 차단한다(검증 실패 분기는 저장소 접근 없음). 목록은 `GET /api/v1/users/me/favorites`로 별도 sort 파라미터 없이 `favoritedAt desc` 고정 조회한다. 현재 구현은 대상 매물의 `PUBLISHED`만 검사하므로 ACTIVE roomOffer가 없는 공개 매물이 빈 `roomOffers[]`로 포함될 수 있다.
