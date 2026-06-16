# US-1-3 — 만료된 access 토큰을 refresh로 재발급받기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant DB as Redis

    U->>C: 보호 기능 사용
    C->>SEC: 보호 API 호출<br/>Authorization: Bearer 만료된 accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC-->>C: 401 TOKEN_EXPIRED
    Note over C: 저장된 refreshToken으로 재발급 시도
    C->>AUTH: POST /api/v1/auth/reissue<br/>{ refreshToken }
    Note over AUTH: refreshToken 만료·위조·무효화·재사용 검증
    AUTH->>DB: refreshToken 해시 조회·상태 확인
    DB-->>AUTH: 유효·무효
    alt 유효한 refresh
        Note over AUTH: 새 accessToken 발급<br/>회전 정책 시 새 refreshToken 발급
        AUTH->>DB: 기존 무효화 + 새 refreshToken 저장
        DB-->>AUTH: 갱신 완료
        AUTH-->>C: 200 OK<br/>{ tokenType: Bearer, accessToken,<br/>refreshToken, expiresIn: 3600 }
        C->>SEC: 새 accessToken으로 보호 API 재호출<br/>Authorization: Bearer accessToken
        Note over SEC: JWT 검증 (서명·만료·클레임)
        SEC->>AUTH: 인증된 요청 전달 (userId)
        AUTH-->>C: 200 OK (보호 리소스)
        C-->>U: 끊김 없이 기능 제공
    else 재사용 탐지 (회전된 옛 refresh 재등장)
        Note over AUTH: 탈취 의심 → 전 세션 차단
        AUTH->>DB: 사용자 refreshToken 일괄 무효화<br/>(revoked=true WHERE userId)
        DB-->>AUTH: 무효화 완료
        AUTH-->>C: 401 AUTH_INVALID_REFRESH_TOKEN
        C-->>U: 재로그인 유도
    else 만료·위조·이미 무효화
        AUTH-->>C: 401 AUTH_INVALID_REFRESH_TOKEN
        C-->>U: 재로그인 유도
    end
```

## 흐름 요약

- 보호 API 호출 시 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증하다 만료를 감지해 `401 TOKEN_EXPIRED`를 반환하면, 앱이 저장된 `refreshToken`으로 `auth 모듈`의 `POST /api/v1/auth/reissue`를 호출한다(재발급 엔드포인트는 인증 불필요 — SEC를 거치지 않고 본문 refresh로 처리).
- 서버는 Redis에서 **refreshToken 해시를 조회·상태 확인**해 유효하면 `200 OK`로 새 access(회전 시 새 refresh) 토큰을 발급하면서 **기존을 무효화하고 새 refreshToken을 저장**한다. 이후 새 accessToken으로 보호 API를 재호출하면 다시 SEC가 JWT를 검증한 뒤 모듈로 전달한다.
- 검증 실패는 두 갈래다. **재사용 탐지**(회전돼 무효화된 옛 refresh가 다시 제출됨)는 Redis에 **사용자 refresh 일괄 무효화** 쿼리를 실행한 뒤 `401 AUTH_INVALID_REFRESH_TOKEN`을 반환하고, **만료·위조·이미 무효화**는 추가 쓰기 없이 `401`만 반환한다. 어느 쪽이든 클라이언트는 재로그인으로 유도된다.

## refresh 검증 실패 4가지

서버는 저장된 **refresh 토큰 해시**를 조회해 [`RefreshToken`](../../../../src/main/java/com/kohere/auth/domain/RefreshToken.java)의 `expiresAt`·`revoked` 상태로 다음을 판별한다.

| 경우 | 무엇 | 판별 근거 | 처리 |
| --- | --- | --- | --- |
| **만료** | refresh 토큰의 수명이 다 됨(access보다 길지만 유한) | `expiresAt < now` | `401`, 해당 토큰만 거부 |
| **위조** | 서버가 발급한 진짜 토큰이 아님(서명 검증 실패 또는 해시 조회 시 Redis에 없음) | 해시 매칭 레코드 없음 | `401`, 거부 |
| **무효화** | 발급했으나 이미 죽은 토큰(로그아웃·탈퇴·회전·재사용 탐지의 결과) | `revoked = true` | `401`, 거부 |
| **재사용 탐지** | 회전으로 죽은 옛 refresh가 다시 제출됨 → 탈취 정황 | `revoked = true`(회전된 세대)인 토큰의 재제출 | `401` + **사용자 refresh 일괄 무효화** |

- 앞 세 가지(**만료·위조·무효화**)는 제출된 그 토큰 **한 건만** 거부하고 끝난다(추가 Redis 쓰기 없음).
- **재사용 탐지**만 부수효과가 다르다. 회전 정책에서는 재발급마다 옛 refresh를 무효화하고 새 것을 발급하므로 정상 클라이언트는 항상 최신 토큰만 보유한다. 그런데 이미 회전돼 죽은 옛 토큰이 다시 등장하면, 정상 클라이언트와 탈취본이 동시에 토큰 체인을 들고 있다는 신호여서 서버가 둘을 구분할 수 없다. 그래서 그 토큰만 막지 않고 **해당 사용자의 모든 refresh를 일괄 무효화**해 전 세션을 끊고 재로그인을 강제한다(OAuth 2.0 refresh token rotation). 이 일괄 무효화는 회원 탈퇴([US-1-4](us-1-4-logout-withdraw.md))와 동일한 "사용자별 refresh 일괄 무효화" 연산이다.
