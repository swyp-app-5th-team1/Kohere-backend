# US-1-9 — 임대인 온보딩 정보 제출하기

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)
>
> 임대인(`LANDLORD`) 전용 온보딩 제출이다. 세입자용 `POST /api/v1/auth/onboarding`(US-1-2)과 **분리된 엔드포인트**이며, 성공 시 `TERMS_AGREED`→`ACTIVE` 전이와 함께 `userType=LANDLORD`를 확정하고(이후 불변) 정식 토큰을 발급한다. 약관 동의까지는 세입자와 공통이고, 임대인은 **연락처 SMS 인증(US-1-10)** 을 선행한다(임대인은 이메일을 수집하지 않는다 — [ADR-0034](../../../adr/0034-landlord-phone-sms-verification.md)).

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant USER as user 모듈
    participant SQL as MySQL
    participant RDS as Redis

    Note over U,C: 약관 동의(US-1-7)·연락처 인증(US-1-10) 사전 완료
    U->>C: 이름·생년월일·휴대전화번호 입력
    C->>SEC: POST /api/v1/auth/landlord/onboarding<br/>Authorization: Bearer 온보딩토큰<br/>{ name, phoneNumber, birthDate }
    Note over SEC: JWT 검증 (서명·만료·클레임)<br/>온보딩 스코프(ROLE_ONBOARDING) 주입<br/>landlord/onboarding 경로 인가
    SEC->>AUTH: 인증된 요청 전달 (userId + 온보딩 스코프)
    Note over AUTH: 필드 검증<br/>연락처는 응답·로그에서 마스킹
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
        AUTH->>RDS: phone-verify:verified:{userId} 조회(제출 phoneNumber 대조)
        RDS-->>AUTH: VERIFIED 연락처(있음/없음)
        alt 연락처 미인증·불일치
            AUTH-->>C: 422 AUTH_PHONE_NOT_VERIFIED
            C-->>U: 연락처 인증 안내(US-1-10)
        else 연락처 인증 확인됨
            AUTH->>USER: 임대인 온보딩 완료 공개명령<br/>(name·검증 phoneNumber·birthDate 전달)
            Note over USER: 약관·termsVersion은 US-1-7에서 기록 완료(이 요청에 약관 필드 없음)
            loop 닉네임 생성(NicknameGenerator) — UNIQUE 충돌 시 재조합, 상한 N
                USER->>SQL: 형용사 풀·사물 풀에서 active 단어 무작위 각 1개 조회
                SQL-->>USER: 형용사·사물 → "형용사 + 사물" 후보
                USER->>SQL: users.nickname 중복 확인(UNIQUE)
                SQL-->>USER: 사용 가능 / 중복(→ 재조합)
            end
            Note over USER,SQL: 동시 온보딩 경합은 users.nickname UNIQUE 제약이 최종 차단(위반 시 재조합)<br/>재시도 상한 초과 시 fallback(예: 숫자 접미사)
            Note over USER: 임대인은 country='KR'·lang='ko'를 서버가 고정 부여<br/>(클라이언트는 전송하지 않는다 — 요청 본문은 name·phoneNumber·birthDate뿐)<br/>임대인은 진단·프로필을 ko로 본다
            USER->>SQL: TERMS_AGREED→ACTIVE 전이 + userType=LANDLORD 확정(이후 불변)<br/>name·phoneNumber·birthDate·nickname 확정 + country='KR'·lang='ko' 고정, businessRegistrationNumberHash는 null로 설정<br/>(임대인은 gender/occupation/visaType·email 미수집)
            SQL-->>USER: 갱신 완료
            USER-->>AUTH: 온보딩 완료 (user{ userType: LANDLORD, status: ACTIVE, nickname })
            Note over AUTH: 정식 accessToken+refreshToken 발급
            AUTH->>RDS: refreshToken 해시 저장
            RDS-->>AUTH: 저장 완료
            AUTH-->>C: 200 OK<br/>{ user{ userType: LANDLORD, status: ACTIVE, nickname },<br/>tokenType: Bearer, accessToken, refreshToken, expiresIn: 3600 }
            C-->>U: 임대인 가입 완료, 서비스 진입
        end
    end
```

## 흐름 요약

- **선행 단계**: 약관 동의(US-1-7, `PENDING`→`TERMS_AGREED`)·연락처 인증(US-1-10)이 끝난 상태에서 진행한다. `TERMS_AGREED` 사용자가 온보딩 토큰으로 임대인 필수 정보(`name`·`phoneNumber`·`birthDate`)를 담아(약관 필드·이메일·사업자번호 없음) **세입자용과 분리된 `POST /api/v1/auth/landlord/onboarding`** 을 호출하며, 공통 보안 필터(SEC)가 JWT 검증·**온보딩 스코프(`ROLE_ONBOARDING`) 인가**를 마친 뒤 `userId`를 `auth 모듈`로 전달한다.
- `auth 모듈`이 요청을 수신해 필드를 검증하고, **온보딩 흐름 순서(약관 동의 → 연락처 인증)를 강제**한다. 먼저 `user 모듈` 공개 API로 **계정 상태를 조회**해 이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`, **약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`**(약관 동의 안내가 먼저)로 거절한다. 연락처는 **응답·로그에서 마스킹**한다.
- 약관까지 마친(`TERMS_AGREED`) 경우에만 **연락처 검증 게이트**를 확인한다. 제출 `phoneNumber`가 사전 인증(US-1-10, Redis `phone-verify:verified:{userId}`)된 값과 일치하는지 보고 미인증·불일치면 `422 AUTH_PHONE_NOT_VERIFIED`로 거절한다 — **검증 게이트 우선순위는 약관 → 연락처**다(온보딩에는 사업자번호 게이트가 없다).
- 게이트를 통과하면 `user 모듈`이 **MySQL에서 상태를 `TERMS_AGREED`→`ACTIVE`로 전이하며 `userType=LANDLORD`를 확정(이후 불변)** 하고 `name`·`phoneNumber`·`birthDate`를 확정한다. **사업자등록번호는 온보딩에서 수집하지 않으며 `businessRegistrationNumberHash` 컬럼은 `null`로 남는다**(온보딩 이후 매물 등록 시점에 채운다). 임대인은 **`gender`/`occupation`/`visaType`·`email`을 수집하지 않는다**(생년월일 `birthDate`은 세입자와 동일하게 수집한다). 다만 **국적 `country`='KR'·표시 언어 `lang`='ko'는 서버가 온보딩 완료 시점에 고정 부여**한다 — **클라이언트는 둘 다 전송하지 않으며 요청 본문은 `name`·`phoneNumber`·`birthDate` 그대로**다. 임대인은 진단을 `ko`로 본다([ADR-0034](../../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 `country` 미수집" 결정을 개정한다). `nickname`은 세입자와 동일하게 **`NicknameGenerator`가 형용사 풀·사물 풀의 active 단어에서 각 1개를 골라 `형용사 + 사물`로 조합하고, `users.nickname` 유니크 충돌 시 재조합·재시도(상한 초과 시 fallback; 동시 경합은 UNIQUE 제약이 최종 차단)** 해 자동 배정한다.
- 이어 `auth 모듈`이 정식 access/refresh 토큰을 발급하여 **Redis에 refreshToken 해시를 저장**한 뒤 `200 OK`(`expiresIn: 3600`)로 `user{ userType: LANDLORD, status: ACTIVE, nickname }`과 토큰을 반환한다. 임대인 프로필 조회(`GET /users/me`, 스펙 §8)는 `birthDate`와 함께 고정 부여된 `country`(`KR`)·`countryName`·`countryFlag`·`lang`(`ko`)도 포함해 반환하며, 수정(`PATCH /users/me`, §9)의 임대인 수정 대상은 `name`·`phoneNumber`·`marketingAgreed`다(`birthDate`는 온보딩 확정·조회 전용, `country`·`lang`은 고정 불변이라 수정 대상이 아니다).
