# US-1-5 — 내 프로필 조회·수정하기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant DB as MySQL

    U->>C: 내 정보 화면 진입
    C->>SEC: GET /api/v1/users/me<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>AUTH: 인증된 요청 전달 (userId)
    alt PENDING 토큰으로 접근
        AUTH-->>C: 403 AUTH_ONBOARDING_REQUIRED
        C-->>U: 온보딩 완료 안내
    else ACTIVE 본인 조회
        Note over AUTH: 본인 프로필 조회 (민감정보 마스킹 정책)
        AUTH->>DB: 프로필 조회
        DB-->>AUTH: 프로필 데이터
        AUTH-->>C: 200 OK<br/>{ id, firstName, lastName, gender, birthDate,<br/>countryCode, phoneNumber, visaType, status,<br/>termsOfServiceAgreed, privacyPolicyAgreed, marketingAgreed }
        C-->>U: 프로필 표시
    end

    U->>C: 비자정보·연락처 등 일부 수정
    C->>SEC: PATCH /api/v1/users/me<br/>Authorization: Bearer accessToken<br/>{ phoneNumber, visaType, marketingAgreed }
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>AUTH: 인증된 요청 전달 (userId)
    Note over AUTH: 전송 필드만 변경 (미전송 필드 유지)
    AUTH->>DB: 프로필 부분 수정
    DB-->>AUTH: 수정 완료
    AUTH-->>C: 200 OK<br/>{ 수정된 프로필 전체 }
    C-->>U: 변경 내용 반영
```

## 흐름 요약

- ACTIVE 사용자가 `auth 모듈`의 `GET /api/v1/users/me`로 본인 프로필(이름·성별·생년월일·연락처·`visaType`·약관 동의 상태)을 MySQL에서 **프로필 조회**해 `200 OK`로 반환하며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다.
- 온보딩 미완료(PENDING) 토큰으로 접근하면 `403 AUTH_ONBOARDING_REQUIRED`를 반환한다.
- `auth 모듈`의 `PATCH /api/v1/users/me`에 변경 필드만 담아 보내면 미전송 필드는 유지한 채 MySQL에서 **프로필을 부분 수정**한 뒤 `200 OK`로 수정된 프로필을 반환한다.
