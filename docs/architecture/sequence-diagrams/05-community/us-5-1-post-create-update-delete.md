# US-5-1 — 게시글 작성 · 수정 · 삭제

> 모듈: 커뮤니티 (게시판 · 동네친구) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/05-community.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant COMM as community 모듈
    participant DB as MySQL

    U->>C: 게시판 선택 후 제목·본문 입력, 작성
    C->>SEC: POST /api/v1/community/posts<br/>Authorization Bearer accessToken<br/>boardType=FREE, title, content, hashtags[]
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: title 1~100자 / content 1~5000자 검증<br/>작성자 = 인증 사용자로 게시글 영속화
    COMM->>DB: 게시글 insert(작성자=userId, boardType, title, content, hashtags)
    DB-->>COMM: postId, createdAt
    COMM-->>C: 201 Created<br/>Location /api/v1/community/posts/{postId}<br/>data postId, boardType, createdAt
    C-->>U: 작성 완료, 내 게시글 목록에 즉시 노출

    U->>C: 내 글 제목·본문 수정
    C->>SEC: PATCH /api/v1/community/posts/{postId}<br/>Authorization Bearer accessToken<br/>title, content, hashtags[] (변경 필드만)
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: 대상 부재 우선 판정(POST_NOT_FOUND 404)<br/>작성자 본인 여부 검증(타인이면 FORBIDDEN 403)
    COMM->>DB: 게시글 update(변경 필드, postId, 작성자=userId 조건)
    DB-->>COMM: 수정된 게시글
    COMM-->>C: 200 OK<br/>수정된 게시글 상세
    C-->>U: 수정 반영 표시

    U->>C: 내 글 삭제
    C->>SEC: DELETE /api/v1/community/posts/{postId}<br/>Authorization Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: 작성자 검증 후 소프트 삭제<br/>댓글·좋아요 집계 정리(음수 방지)
    COMM->>DB: 게시글 소프트 delete(postId, 집계 정리)
    DB-->>COMM: 삭제 반영
    COMM-->>C: 204 No Content
    C-->>U: 목록·상세에서 제외됨
```

## 흐름 요약

- 작성·수정·삭제는 모두 인증 필수이며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT(서명·만료·클레임)를 검증한 뒤 인증된 요청(userId)을 community 모듈로 전달한다. 토큰이 없거나 만료·위조면 필터가 401(UNAUTHENTICATED / TOKEN_EXPIRED)로 막는다.
- 작성은 community 모듈의 POST /api/v1/community/posts로 boardType·title·content를 보내 MySQL에 게시글을 insert한 뒤 201(postId)과 Location 헤더를 받는다.
- 수정(PATCH)·삭제(DELETE)는 community 모듈이 대상 부재(POST_NOT_FOUND 404)를 먼저 판정한 뒤 작성자 소유권을 강제하며, 타인이면 FORBIDDEN 403을 반환한다. 통과하면 MySQL에 게시글 update/소프트 delete를 반영한다.
- 삭제는 community 모듈에서 소프트 삭제(204)로 처리되어 목록·상세에서 제외되고 집계가 음수가 되지 않는다.
