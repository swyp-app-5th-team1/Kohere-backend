# US-1-2 — 필수 온보딩 정보·약관 동의 제출하기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant SQL as MySQL
    participant RDS as Redis

    U->>C: 이름·성별·생년월일·연락처·비자정보 입력 및 약관 동의
    C->>SEC: POST /api/v1/auth/onboarding<br/>Authorization: Bearer 온보딩토큰<br/>{ firstName, lastName, gender, birthDate,<br/>countryCode, phoneNumber, visaType,<br/>termsOfServiceAgreed, privacyPolicyAgreed, marketingAgreed }
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>AUTH: 인증된 요청 전달 (userId)
    Note over AUTH: PENDING 토큰 검증·필드 검증<br/>(민감정보 마스킹 저장)
    alt 필수 약관 동의 누락
        AUTH-->>C: 422 AUTH_REQUIRED_AGREEMENT_MISSING
        C-->>U: 약관 동의 안내
    else 정상 제출
        Note over AUTH: 사용자 단위 멱등 처리
        AUTH->>SQL: 사용자 PENDING→ACTIVE 갱신
        SQL-->>AUTH: 갱신 완료
        AUTH->>RDS: refreshToken 해시 저장
        RDS-->>AUTH: 저장 완료
        AUTH-->>C: 200 OK<br/>{ user{ status: ACTIVE, ... }, tokenType: Bearer,<br/>accessToken, refreshToken, expiresIn: 3600 }
        C-->>U: 가입 완료, 서비스 진입
    end
```

## 흐름 요약

- PENDING 사용자가 온보딩 토큰으로 필수 프로필과 약관 동의를 담아 `auth 모듈`의 `POST /api/v1/auth/onboarding`을 호출하며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다.
- 필수 약관(`termsOfServiceAgreed`/`privacyPolicyAgreed`) 미동의면 `422 AUTH_REQUIRED_AGREEMENT_MISSING`으로 거절한다.
- 정상이면 **MySQL에서 사용자 상태를 PENDING→ACTIVE로 갱신하고 Redis에 refreshToken 해시를 저장**한 뒤 `200 OK`로 완성 프로필과 정식 access/refresh 토큰을 발급한다.
