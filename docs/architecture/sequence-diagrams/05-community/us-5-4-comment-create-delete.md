# US-5-4 — 댓글 작성 · 삭제

> 모듈: 커뮤니티 (게시판 · 동네친구) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/05-community.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant COMM as community 모듈
    participant DB as MySQL

    U->>C: 댓글 작성
    C->>SEC: POST /api/v1/community/posts/{postId}/comments<br/>Authorization Bearer accessToken<br/>content (1~1000자)
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: 게시글 부재면 POST_NOT_FOUND 404<br/>content 검증 후 댓글 저장<br/>게시글 commentCount 1 증가
    COMM->>DB: 댓글 저장 후 게시글 commentCount 1 증가
    DB-->>COMM: commentId, createdAt
    COMM-->>C: 201 Created<br/>Location .../comments/{commentId}<br/>data commentId, content, authorNickname, createdAt
    C-->>U: 댓글 노출, 댓글 수 갱신

    U->>C: 내 댓글 삭제
    C->>SEC: DELETE /api/v1/community/posts/{postId}/comments/{commentId}<br/>Authorization Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>COMM: 인증된 요청 전달 (userId)
    Note over COMM: 게시글·댓글 부재 우선 판정<br/>(POST_NOT_FOUND / COMMENT_NOT_FOUND 404)<br/>작성자 검증(타인이면 FORBIDDEN 403)<br/>소프트 삭제, commentCount 1 감소(음수 방지)
    COMM->>DB: 댓글 소프트 delete 후 commentCount 1 감소(음수 방지)
    DB-->>COMM: 삭제 반영
    COMM-->>C: 204 No Content
    C-->>U: 댓글 사라짐, 댓글 수 갱신
```

## 흐름 요약

- 댓글 작성·삭제는 모두 인증 필수이며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT(서명·만료·클레임)를 검증한 뒤 인증된 요청(userId)을 community 모듈로 전달한다. 토큰이 없거나 만료·위조면 필터가 401(UNAUTHENTICATED / TOKEN_EXPIRED)로 막는다.
- 댓글 작성은 community 모듈의 POST /api/v1/community/posts/{postId}/comments로 content를 보내 MySQL에 댓글을 저장하고 게시글 commentCount를 1 증가시킨 뒤 201(commentId)과 Location 헤더를 받는다.
- 삭제는 DELETE .../comments/{commentId}로 community 모듈이 게시글·댓글 부재(POST_NOT_FOUND / COMMENT_NOT_FOUND 404)를 먼저 판정한 뒤 작성자 소유권을 강제하며, 타인이면 FORBIDDEN 403이다.
- 삭제 성공 시 community 모듈이 MySQL에 댓글 소프트 삭제를 반영하고 204를 반환하며 commentCount를 1 감소(음수 방지)시킨다.
