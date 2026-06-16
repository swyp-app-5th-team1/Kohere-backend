# US-1-4 — 로그아웃·회원 탈퇴로 세션과 계정 정리하기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant SQL as MySQL
    participant RDS as Redis

    alt 로그아웃
        U->>C: 로그아웃 선택
        C->>SEC: POST /api/v1/auth/logout<br/>Authorization: Bearer accessToken<br/>{ refreshToken }
        Note over SEC: JWT 검증 (서명·만료·클레임)
        SEC->>AUTH: 인증된 요청 전달 (userId)
        Note over AUTH: 전달된 refreshToken 무효화<br/>(이미 무효화면 멱등 처리)
        AUTH->>RDS: refreshToken 무효화
        RDS-->>AUTH: 무효화 완료
        AUTH-->>C: 204 No Content
        C-->>U: 세션 종료, 로그인 화면
    else 회원 탈퇴
        U->>C: 회원 탈퇴 선택
        C->>SEC: DELETE /api/v1/users/me<br/>Authorization: Bearer accessToken
        Note over SEC: JWT 검증 (서명·만료·클레임)
        SEC->>AUTH: 인증된 요청 전달 (userId)
        Note over AUTH: 상태 WITHDRAWN 전이<br/>(개인정보 파기/익명화는 정책)
        AUTH->>SQL: 사용자 WITHDRAWN 갱신
        SQL-->>AUTH: 갱신 완료
        AUTH->>RDS: refresh 토큰 일괄 무효화
        RDS-->>AUTH: 무효화 완료
        AUTH-->>C: 204 No Content
        C-->>U: 계정 정리 완료
    end
```

## 흐름 요약

- 로그아웃은 access 토큰으로 `auth 모듈`의 `POST /api/v1/auth/logout`에 `refreshToken`을 담아 호출하면 Redis에서 해당 **refreshToken을 무효화**하고 `204 No Content`를 반환한다(이미 무효화면 멱등).
- 회원 탈퇴는 `auth 모듈`의 `DELETE /api/v1/users/me` 호출 시 MySQL에서 **사용자 상태를 WITHDRAWN으로 갱신**하고 Redis에서 **모든 refresh 토큰을 일괄 무효화**한 뒤 `204 No Content`를 반환한다.
- 두 동작 모두 인증 필수이며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다. PENDING 사용자도 탈퇴는 허용된다.
