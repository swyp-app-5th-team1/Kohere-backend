# US-3-4 — 매물 상세 조회 + 최근 본 매물 기록

> 모듈: 매물 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant LIST as listing 모듈
    participant DB as MongoDB

    U->>C: 매물 카드 탭 (상세 보기)
    C->>LIST: GET /api/v1/listings/{listingId}<br/>(Authorization 선택)
    alt 존재하는 매물
        Note over LIST: 상세 구성 (임대인 연락처 미노출, CHAT만)<br/>로그인 시 (userId, listingId) 유니크로<br/>최근 본 매물 upsert, viewedAt 갱신<br/>비로그인은 favorited=false, 기록 안 함
        LIST->>DB: 매물 상세 조회
        DB-->>LIST: 매물 상세 + favoriteCount
        opt 로그인 사용자
            LIST->>DB: 최근 본 매물 기록 저장<br/>(userId, listingId) upsert, viewedAt 갱신
            DB-->>LIST: 저장 완료
        end
        LIST-->>C: 200 OK<br/>data( imageUrls[], type, monthlyRent, deposit,<br/>contractTermOptions[], location, conditions[],<br/>landlord(contactChannel=CHAT), favorited, favoriteCount )
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
        Note over LIST: 7일 이내·최신순 최대 5건 반환<br/>페이지 없이 content 배열만
        LIST->>DB: 최근 본 매물 조회 (7일 이내·최신순 5건)
        DB-->>LIST: 최근 본 매물 목록
        LIST-->>C: 200 OK<br/>data.content[]( listingId, viewedAt ... )
        C-->>U: 최근 본 매물 표시
    end
```

## 흐름 요약

- `GET /api/v1/listings/{listingId}`로 `listing` 모듈이 MongoDB에서 상세(`imageUrls[]`·`type`·`monthlyRent`·`deposit`·`contractTermOptions[]`·`location`·`conditions[]`·`landlord`·`favorited`)를 조회하며, 임대인 연락처는 `contactChannel=CHAT`로만 노출한다(인증 선택이라 SEC 없이 `listing` 모듈로 직접 요청).
- 로그인 상태면 `listing` 모듈이 MongoDB에 `(userId, listingId)` 유니크 upsert로 최근 본 매물을 기록(`viewedAt` 갱신)하고, 비로그인은 기록하지 않으며 `favorited=false`다.
- 없음/비공개/삭제 매물은 `404 LISTING_NOT_FOUND`(MongoDB 조회로 부재 확인), 최근 본 매물은 인증 필수 `GET /api/v1/users/me/recent-listings`로 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 `listing` 모듈로 전달하며(토큰 없음/만료/위조면 SEC가 `401`로 차단해 MongoDB 접근 없음), 검증 통과 후 MongoDB에서 7일 이내·최신순 최대 5건만 조회한다.
