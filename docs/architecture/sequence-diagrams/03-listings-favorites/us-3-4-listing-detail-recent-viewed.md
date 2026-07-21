# US-3-4 — 매물 상세 조회 + 최근 본 매물 기록

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant LIST as listing 모듈
    participant USER as user 공개 API
    participant DB as MongoDB

    U->>C: 매물 카드 탭 (상세 보기)
    C->>SEC: GET /api/v1/listings/{listingId}<br/>Authorization 선택
    Note over SEC: 공개 API로 통과<br/>정식 토큰이면 userId 주입,<br/>그 외에는 익명 처리
    SEC->>LIST: 요청 전달 (정식 userId 또는 null)
    alt 존재하는 매물
        opt 온보딩 완료 사용자
            LIST->>USER: getLanguage(userId)
            USER-->>LIST: 사용자가 선택한 표시 언어<br/>(미지원은 en)
        end
        Note over LIST: 상세 구성 (직접 연락처 미노출)<br/>건물 공통정보 + roomOffers[] 방 상품<br/>비로그인·온보딩 미완료는 영어·favorited=false
        LIST->>DB: ObjectId listingId로 매물 상세 조회
        DB-->>LIST: 건물 매물 상세 + roomOffers[] + favoriteCount<br/>+ listingCatalog 공통 코드 번역
        opt 온보딩 완료 사용자
            LIST->>DB: 찜 상태 조회 + 최근 본 기록 저장<br/>(userId, listingId) upsert, viewedAt 갱신<br/>사용자별 최신 30개 초과분 정리
            DB-->>LIST: 조회/저장/정리 완료
        end
        LIST-->>C: 200 OK<br/>공통 표시 코드={code,label}<br/>roomOffers[], favorited, favoriteCount
        C-->>U: 사진 갤러리 + 상세 정보 표시
    else 없음/비공개/삭제
        LIST-->>C: 404 Not Found<br/>error.code=LISTING_NOT_FOUND
        C-->>U: 매물을 찾을 수 없음 안내
    end
    U->>C: 최근 본 매물 목록 열기 (로그인)
    C->>SEC: GET /api/v1/users/me/recent-listings<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 유도
    else 검증 통과
        SEC->>LIST: 인증된 요청 전달 (userId)
        Note over LIST: 저장 기록 중 공개 매물만<br/>최신순 최대 10건 반환<br/>페이지 없이 content 배열만
        LIST->>DB: 최근 본 매물 조회 (PUBLISHED·최신순 10건)
        DB-->>LIST: 최근 본 매물 목록
        LIST-->>C: 200 OK<br/>data.content[]( listingId, viewedAt ... )
        C-->>U: 최근 본 매물 표시
    end
```

## 흐름 요약

- `GET /api/v1/listings/{listingId}`는 공개 API다. 공통 보안 필터는 온보딩 완료 토큰이면 `userId`를 전달하고, 비로그인·온보딩 미완료·검증 실패 토큰은 익명으로 통과시킨다. `listing` 모듈은 MongoDB에서 건물 공통 상세(`imageUrls[]`·`type`·`location`·`address`·`propertyPolicies`·`facilities`)와 방 상품 목록(`roomOffers[]`: 가격·재고·필터 태그)을 조회한다.
- 온보딩 완료 사용자의 상세 조회만 `(userId, listingId)` 유니크 upsert로 최근 본 매물을 기록하고 사용자별 최신 30개 초과분을 정리한다. 비로그인·온보딩 미완료 조회는 기록하지 않으며 로그인 후 소급 이전하지 않는다. 저장/정리 실패는 상세 조회를 실패시키지 않고 로그로 남긴다.
- 정식 사용자는 `user::api getLanguage(userId)`로 표시 언어를 얻고 실제 찜 상태를 조회한다. 그 외 공개 조회는 영어와 `favorited=false`를 사용한다. 시설·조건 등 공통 코드는 `listingCatalog`의 번역과 결합해 `{code,label}`로 반환한다.
- 없음/비공개/삭제 매물은 `404 LISTING_NOT_FOUND`(MongoDB 조회로 부재 확인), 최근 본 매물은 인증 필수 `GET /api/v1/users/me/recent-listings`로 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 `listing` 모듈로 전달하며(토큰 없음/만료/위조면 SEC가 `401`로 차단해 MongoDB 접근 없음), 검증 통과 후 MongoDB에서 공개 매물만 최신순 최대 10건 조회한다.
