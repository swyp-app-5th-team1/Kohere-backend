# US-5-2 — 게시글 목록 · 상세 · 검색 · 내 게시글

> 모듈: 커뮤니티 (게시판 · 동네친구) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/05-community.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant COMM as community 모듈
    participant DB as MySQL

    U->>C: 게시판 열기 / 키워드·해시태그 검색
    C->>COMM: GET /api/v1/community/posts<br/>boardType=FREE, sort=createdAt,desc<br/>keyword, hashtag, page=0, size=20 (인증 선택)
    Note over COMM: sort·boardType·page·size 검증<br/>미정의 값은 INVALID_INPUT 400<br/>인증 시 각 항목 liked 채움
    COMM->>DB: 게시글 목록 조회(boardType·정렬·검색·페이지)
    DB-->>COMM: content[] + page 메타
    COMM-->>C: 200 OK<br/>data content[](postId, title, authorNickname,<br/>likeCount, commentCount) + page 메타
    C-->>U: 목록·검색 결과 표시(0건이면 빈 목록)

    U->>C: 게시글 카드 탭
    C->>COMM: GET /api/v1/community/posts/{postId} (인증 선택)
    Note over COMM: 없거나 삭제된 글이면 POST_NOT_FOUND 404<br/>탈퇴 작성자는 닉네임·국적 마스킹
    COMM->>DB: 게시글 상세 조회(postId)
    DB-->>COMM: 게시글 상세(없으면 미존재)
    COMM-->>C: 200 OK<br/>data 상세(content, hashtags, shareCount,<br/>liked, editable)
    C-->>U: 상세 화면 표시

    U->>C: 내 게시글 보기
    C->>SEC: GET /api/v1/community/posts/me<br/>Authorization Bearer accessToken (인증 필수)
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: 본인 작성·미삭제 글만 최신순
    COMM->>DB: 내 게시글 조회(작성자=userId, 미삭제, 최신순)
    DB-->>COMM: content[] + page 메타
    COMM-->>C: 200 OK<br/>data content[] + page 메타
    C-->>U: 내가 쓴 글 목록 표시
```

## 흐름 요약

- 목록·검색은 community 모듈의 GET /api/v1/community/posts에 boardType·sort(createdAt,desc / likeCount,desc)·keyword·hashtag를 보내 MySQL에서 목록을 조회해 200 페이지 객체를 받으며, 잘못된 정렬·페이지 값은 INVALID_INPUT 400으로 거부한다. 인증 선택이므로 공통 보안 필터(SEC)를 거치지 않고 C->>COMM으로 직접 호출한다.
- 상세 GET /api/v1/community/posts/{postId}는 인증 선택으로 SEC 없이 직접 호출되며, community 모듈이 MySQL에서 게시글을 조회해 없거나 삭제된 글이면 POST_NOT_FOUND 404, 정상이면 200 상세를 반환한다.
- 내 게시글 GET /api/v1/community/posts/me는 인증 필수로, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 인증된 요청(userId)을 community 모듈로 전달하며, 토큰이 없거나 만료·위조면 필터가 401(UNAUTHENTICATED / TOKEN_EXPIRED)로 막고, 통과하면 community 모듈이 MySQL에서 본인 글만 조회해 200으로 반환한다.
