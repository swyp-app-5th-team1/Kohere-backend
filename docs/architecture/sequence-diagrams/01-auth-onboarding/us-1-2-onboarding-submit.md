# US-1-2 — 필수 온보딩 정보 제출하기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant USER as user 모듈
    participant SQL as MySQL
    participant RDS as Redis

    Note over U,C: 약관 동의(US-1-7)·이메일 인증(US-1-6) 사전 완료
    U->>C: 이름·성별·생년월일·국적·직업·이메일·비자정보 입력
    C->>SEC: POST /api/v1/auth/onboarding<br/>Authorization: Bearer 온보딩토큰<br/>{ firstName, lastName, gender, birthDate,<br/>country, occupation, email, visaType,<br/>lang(선택 — 미전송 시 표시 시 en) }
    Note over SEC: JWT 검증 (서명·만료·클레임)<br/>온보딩 스코프(ROLE_ONBOARDING) 주입<br/>onboarding 경로 인가
    SEC->>AUTH: 인증된 요청 전달 (userId + 온보딩 스코프)
    Note over AUTH: 필드 검증<br/>민감정보(이메일·비자)는 응답·로그에서만 마스킹(저장은 원문)
    AUTH->>USER: 계정 상태 조회(공개 API)
    USER->>SQL: 회원 조회 (상태 확인)
    SQL-->>USER: 현재 상태
    USER-->>AUTH: status(PENDING|TERMS_AGREED|ACTIVE)
    alt 이미 ACTIVE
        AUTH-->>C: 409 AUTH_ONBOARDING_ALREADY_COMPLETED
        C-->>U: 이미 온보딩 완료 안내
    else PENDING (약관 미동의)
        AUTH-->>C: 422 AUTH_TERMS_AGREEMENT_REQUIRED
        C-->>U: 약관 동의 안내(US-1-7)
    else TERMS_AGREED
        AUTH->>RDS: email-verify:verified:{userId} 조회(제출 email 대조)
        RDS-->>AUTH: VERIFIED 이메일(있음/없음)
        alt 이메일 미인증·불일치
            AUTH-->>C: 422 AUTH_EMAIL_NOT_VERIFIED
            C-->>U: 이메일 인증 안내(US-1-6)
        else 이메일 인증 확인됨
            AUTH->>USER: 온보딩 완료 공개명령<br/>(프로필·검증 email 전달)
            Note over USER: 약관·termsVersion은 US-1-7에서 기록 완료(이 요청에 약관 필드 없음)
            loop 닉네임 생성(NicknameGenerator) — UNIQUE 충돌 시 재조합, 상한 N
                USER->>SQL: 형용사 풀·사물 풀에서 active 단어 무작위 각 1개 조회
                SQL-->>USER: 형용사·사물 → "형용사 + 사물" 후보
                USER->>SQL: users.nickname 중복 확인(UNIQUE)
                SQL-->>USER: 사용 가능 / 중복(→ 재조합)
            end
            Note over USER,SQL: 동시 온보딩 경합은 users.nickname UNIQUE 제약이 최종 차단(위반 시 재조합)<br/>재시도 상한 초과 시 fallback(예: 숫자 접미사)
            USER->>SQL: TERMS_AGREED→ACTIVE 전이<br/>프로필·email·nickname 확정(country=ISO 코드, countries.code 검증)
            SQL-->>USER: 갱신 완료
            Note over USER,SQL: country(코드)로 countries 조회 → countryName·countryFlag resolve(응답용)<br/>countryFlag=국기 이미지 URL(flagcdn.com)
            USER-->>AUTH: 온보딩 완료 (user{ status: ACTIVE, nickname })
            Note over AUTH: 정식 accessToken+refreshToken 발급
            AUTH->>RDS: refreshToken 해시 저장
            RDS-->>AUTH: 저장 완료
            AUTH-->>C: 200 OK<br/>{ user{ status: ACTIVE, nickname, country, countryName, countryFlag, occupation, email, lang(설정 시), ... },<br/>tokenType: Bearer, accessToken, refreshToken, expiresIn: 3600 }
            C-->>U: 가입 완료, 서비스 진입
        end
    end
```

## 흐름 요약

- **선행 단계**: 약관 동의(US-1-7, `PENDING`→`TERMS_AGREED`)와 이메일 인증(US-1-6)이 끝난 상태에서 진행한다. `TERMS_AGREED` 사용자가 온보딩 토큰으로 필수 프로필만 담아(약관 필드 없음) `POST /api/v1/auth/onboarding`을 호출하며, 공통 보안 필터(SEC)가 JWT 검증·**온보딩 스코프(`ROLE_ONBOARDING`) 인가**를 마친 뒤 `userId`를 `auth 모듈`로 전달한다.
- `auth 모듈`이 요청을 수신해 필드를 검증하고, **온보딩 흐름 순서(약관 동의 → 이메일 인증)를 강제**한다. 먼저 `user 모듈`의 공개 API로 **계정 상태를 조회**해 이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`, **약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`**(약관 동의 US-1-7 선행 필요)로 거절한다 — 약관을 마치지 않은 사용자에게는 이메일 인증 안내보다 **약관 동의 안내가 먼저** 나간다. 민감정보(이메일·비자)는 **저장은 원문**이며 **응답·로그에서만 마스킹**한다.
- 약관까지 마친(`TERMS_AGREED`) 경우에만 **제출 `email`이 사전 인증(US-1-6, Redis `email-verify:verified:{userId}`)된 값과 일치**하는지 확인한다. 미인증·불일치면 `422 AUTH_EMAIL_NOT_VERIFIED`로 거절하고, 일치하면 **온보딩 완료 공개명령으로 `user 모듈`을 호출**한다. 약관 동의·`termsVersion`은 이 단계가 아니라 **약관 동의 단계(US-1-7)에서 이미 기록**된다.
- 정상(`TERMS_AGREED` + 이메일 인증 확인)이면 `user 모듈`이 **MySQL에서 상태를 `TERMS_AGREED`→`ACTIVE`로 전이하며 프로필·`email`을 확정**한다. `nickname`은 **`NicknameGenerator`가 형용사 풀·사물 풀의 active 단어에서 무작위로 각 1개를 골라 `형용사 + 사물`로 조합하고, `users.nickname` 유니크 충돌 시 재조합·재시도(상한 초과 시 fallback; 동시 경합은 UNIQUE 제약이 최종 차단)** 해 자동 배정한다. 이어 `auth 모듈`이 정식 access/refresh 토큰을 발급하여 **Redis에 refreshToken 해시를 저장**한 뒤 `200 OK`(`expiresIn: 3600`)로 완성 프로필과 토큰을 반환한다.
