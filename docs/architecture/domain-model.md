# 도메인 모델 — 모듈별 애그리거트 카탈로그

> 모듈(Bounded Context)별 **도메인 모델의 정본**이다. **설계-우선(design-first)**: 이 문서가 먼저 정의되고 **코드가 이 모델을 구현**한다(문서 → 코드). 현재 코드 상태가 아니라 **구현해야 할 목표 모델**을 기술한다.
>
> - **전략적 경계**(모듈 분해): [ADR-0001](../adr/0001-bounded-context-module-decomposition.md)
> - **전술적 도메인**(애그리거트·구성요소·VO·불변식·관계): **이 문서**
> - **영속 매핑**(저장소·물리 타입·키·인덱스·DDL): [database-design](../database/database-design.md) — 이 문서는 **영속 무관**(저장소·물리 타입을 다루지 않는다)
> - **모듈 간 통신**: [ADR-0002](../adr/0002-inter-module-communication-via-events.md) — 엔티티 비공유, **식별자 참조** + 공개 쿼리/도메인 이벤트
> - **API 계약**: [api/specs](../api/specs/README.md) · [api-design-guide](../api/api-design-guide.md) · [error-response-guide](../api/error-response-guide.md)

## 읽는 법 · 규약

- **애그리거트 루트** = 일관성·트랜잭션 경계. 외부는 루트를 통해서만 접근하고, 구성 엔티티/값 객체는 루트에 종속한다.
- **타 애그리거트·타 모듈은 객체가 아니라 식별자(`id`)로 참조**한다(엔티티 비공유, [ADR-0002](../adr/0002-inter-module-communication-via-events.md)). 협력은 **공개 쿼리/도메인 이벤트**로 한다.
- 식별자는 개념적 `id`(+ 비즈니스/자연키)로만 표기한다. **물리 타입·저장소·인덱스는 [database-design](../database/database-design.md) 소관**이라 여기서 다루지 않는다.
- 타입 표기는 **도메인 타입**: `String`·`int`·`boolean`·`Instant`·`LocalDate`·`enum X`·`Set<X>`·`List<X>`·`VO X`·`식별자`.
- enum 값은 UPPER_SNAKE, 의미는 한국어. **enum 값 카탈로그의 정본은 이 문서**다.
- 불변식은 도메인 규칙(상태 전이·유니크·멱등·소유권·검증)이다. 관련 에러코드는 [error-response-guide](../api/error-response-guide.md).

## 한눈에 — 모듈 ↔ 애그리거트

| 모듈 | 애그리거트 루트 | 핵심 값 객체(VO) | MVP |
| --- | --- | --- | --- |
| [`auth`](#1-auth--인증온보딩) | `SocialAccount`, `RefreshToken`, `EmailVerification` | `SocialIdentity`, `TokenHash` | ✅ |
| [`user`](#2-user--회원-프로필계정-lifecycle) | `User` | `FullName`, `Consent` | ✅ |
| [`listing`](#3-listing--매물-탐색찜) | `Listing`, `Favorite`, `RecentListing` | `Location`, `Address`, `RoomOffer`, `MatchedPlace` | ✅ |
| [`diagnosis`](#4-diagnosis--6단계-맞춤-진단) | `Diagnosis` | `DiagnosisCriteria`, `RecommendationSuggestions` | ✅ |
| [`booking`](#5-booking--매물-신청예약) | `Booking` | `GreetingMessage` | ✅ |
| [`chat`](#6-chat--인앱-채팅) | `ChatRoom`(+`Message`·`ReadCursor`) | `BookingCard`, `ListingCard`, `ListingSnapshot` | ✅ |
| [`community`](#7-community--커뮤니티) | `Post`(+`Comment`·`PostLike`) | `Hashtag` | 이후 |
| [`gamification`](#8-gamification--퀴즈포인트) | `Quiz`, `QuizSubmission`, `PointHistory` | `QuizChoice` | 이후 |
| [`report`](#9-report--신고-처리) | `Report` | `ReportTarget`, `ReportDetail` | 이후 |

> `common`은 공유 커널(애그리거트 없음): 응답 래퍼·예외 표준만 제공.

---

## 1. `auth` — 인증·온보딩

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/auth`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

소셜 로그인(Apple/Google) 자격을 회원 식별자로 매핑하고, 서버 자체 세션 토큰(불투명 refresh)의 발급·회전·재사용 탐지·무효화와 **온보딩 중 이메일 인증(인증번호 발송·검증)** 을 책임지는 인증 경계다. 회원 프로필·상태(`PENDING`/`ACTIVE`/`WITHDRAWN`)는 `user` 모듈 소관이므로 여기선 회원 식별자(`userId`)로만 참조한다.

**`SocialAccount`** — 소셜 제공자 자격을 한 명의 회원에 묶는 자격 매핑 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(provider, providerUserId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `provider` | enum `Provider` | 소셜 로그인 제공자(`APPLE`/`GOOGLE`) |
| `providerUserId` | String | 제공자가 발급한 사용자 고유 식별자(검증된 토큰의 `sub` 클레임 — Google은 `idToken`, Apple은 코드 교환으로 받은 `id_token`) |
| `email` | String | 제공자가 제공한 이메일(검증된 토큰에서 추출) — 민감정보 |
| `userId` | 식별자 | 이 자격이 연결된 회원 → `User` 식별자 참조 |
| `linkedAt` | Instant | 자격 연결(최초 발견) 시각(UTC) |
| `appleRefreshToken` | String | Apple 전용 — 코드 교환(`/auth/token`)으로 받은 refresh token. 탈퇴 시 `/auth/revoke` 폐기에 사용([ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)). Google은 `null`. 1급 민감정보 — 로그·응답 비노출, 평문 저장(저장소 암호화 의존, [ADR-0015](../adr/0015-sensitive-column-encryption.md)) |

**불변식:** `(provider, providerUserId)` 조합은 전역 유니크 — 동일 제공자 자격은 정확히 한 회원에만 매핑; 소셜 로그인 시 `(provider, providerUserId)`로 조회해 존재하면 기존 회원 로그인, 없으면 신규 자격 생성 + `user` 모듈에 새 `PENDING` 회원 생성을 요청한 뒤 그 식별자로 연결(기존/신규 분기의 단일 진실원); `providerUserId`는 검증 통과 토큰(서명·`iss`·`aud`·`exp` — Google `idToken` / Apple은 코드 교환으로 받은 `id_token`)에서만 채워짐 — 검증 실패 토큰으로는 자격을 만들지 않음(`401 AUTH_INVALID_SOCIAL_TOKEN`); 한 번 연결된 `userId`는 재할당 불가(자격 소유권 고정); `appleRefreshToken`은 코드 교환 응답에 refresh token이 있을 때만 갱신(없으면 기존 값 보존 — 보통 매번 반환되나 비어 올 경우를 대비한 방어 가드, [ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)); `email`·`appleRefreshToken`은 응답·로그 비노출.

**`RefreshToken`** — 한 로그인 세션의 refresh 자격을 표현하는 애그리거트 루트(발급·만료·무효화·회전 세대 관리). 식별자 `id`, 비즈니스 키 `tokenHash`(불투명 토큰의 해시 — 조회·검증 키).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 토큰 소유 회원 → `User` 식별자 참조(일괄 무효화 단위) |
| `tokenHash` | VO `TokenHash` | 발급된 불투명 토큰의 단방향 해시(원문 미보관) |
| `status` | enum `RefreshTokenStatus` | 토큰 생애 상태 |
| `expiresAt` | Instant | 만료 시각(UTC) |
| `issuedAt` | Instant | 발급 시각(UTC) |

**불변식:** `status=ACTIVE`이고 `expiresAt > now`인 토큰만 유효 — 그 외(만료·위조(해시 매칭 없음)·무효화)는 거부(`401 AUTH_INVALID_REFRESH_TOKEN`); 재발급은 **회전** — 제출된 유효 토큰을 `ROTATED`로 전이해 무효화하고 같은 세션 계보로 새 `ACTIVE` 발급; **재사용 탐지** — 이미 `ROTATED`/`REVOKED`인 토큰 재제출은 탈취 정황으로 보고 해당 `userId`의 모든 refresh 토큰을 일괄 무효화 후 거부(`401`); 로그아웃은 제출 토큰을 `REVOKED`로 전이하며 이미 무효화돼도 멱등 성공(`204`); 회원 탈퇴(`WITHDRAWN` 전이) 시 해당 `userId`의 모든 refresh 토큰 일괄 무효화; 신규 회원의 소셜 로그인 단계(온보딩 미완료)에서는 refresh 미발급(온보딩 완료/기존 회원 로그인 시에만 발급); 원문 토큰은 보관·로그하지 않음(해시만).

**`EmailVerification`** — 온보딩 중 사용자가 입력한 이메일의 소유를 인증번호로 확인하는 단명(ephemeral) 인증 시도 애그리거트 루트. 비즈니스 키 `userId`(온보딩 중인 PENDING 회원 단위로 1건). 영속 무관이나 단명 상태라 Redis로 물리화한다([database-design](../database/database-design.md) §4-1).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | 식별자 | 인증 대상(온보딩 중) 회원 → `User` 식별자 참조(시도 단위) |
| `email` | String | 인증 대상 이메일(사용자가 온보딩에서 입력) — 민감정보 |
| `codeHash` | String | 발송한 인증번호의 단방향 해시(원문 미보관) |
| `status` | enum `EmailVerificationStatus` | 인증 시도 상태 |
| `attempts` | int | 검증 실패 누적 횟수(상한 초과 시 거부) |
| `expiresAt` | Instant | 인증번호 만료 시각(UTC) |
| `issuedAt` | Instant | 인증번호 발송 시각(UTC) |

**불변식:** 발송은 온보딩 토큰(`PENDING`/`TERMS_AGREED` 모두 `onboardingCompleted=false`)을 가진 본인에 한함 — 사용자당 활성 시도 1건(재발송은 기존 시도 대체); 인증 챌린지는 **메일 발송이 성공한 뒤에만 확정**하고(동기 발송), provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 거부(재시도 유도); 검증 시 챌린지가 없으면(미발송·만료·이미 검증으로 키 부재) 올릴 `attempts` 레코드가 없어 즉시 `422 AUTH_EMAIL_VERIFICATION_FAILED`(재요청 유도); 챌린지가 있고 `attempts`가 상한 미만일 때 입력 인증번호 해시가 `codeHash`와 일치하면 `VERIFIED`로 전이하고, 불일치면 `attempts`를 올려 상한 초과 시 `429 TOO_MANY_REQUESTS`·아니면 `422 AUTH_EMAIL_VERIFICATION_FAILED`(과도 재발송도 `429`); 온보딩 완료(`POST /auth/onboarding`)는 제출 `email`이 `VERIFIED`된 이메일과 일치해야 진행(미인증·불일치 `422 AUTH_EMAIL_NOT_VERIFIED`); 인증번호 원문은 보관·로그하지 않음(해시만), `email`은 응답·로그 마스킹; 만료/완료 시도는 TTL로 자동 소멸. (확인 필요: 인증번호 길이·만료 시간·검증 시도 상한·재발송 레이트리밋)

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `SocialIdentity` | `provider` | enum `Provider` | 소셜 로그인 제공자. `(provider, providerUserId)`가 소셜 자격의 자연키 — 동일 값이면 같은 자격 |
| | `providerUserId` | String | 제공자가 발급한 사용자 고유 식별자 |
| `TokenHash` | `value` | String | 불투명 refresh 토큰 원문의 단방향 해시. 동치성은 해시 값으로 판정하며 원문 재구성 불가 |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `Provider` | `APPLE` | 애플 소셜 로그인 제공자 |
| | `GOOGLE` | 구글 소셜 로그인 제공자 |
| `RefreshTokenStatus` | `ACTIVE` | 발급 후 유효(재발급에 사용 가능) |
| | `ROTATED` | 회전으로 새 세대에 자리를 넘겨 무효화됨(재제출 시 재사용 탐지 대상) |
| | `REVOKED` | 로그아웃·탈퇴·재사용 탐지로 강제 무효화됨 |
| `EmailVerificationStatus` | `PENDING` | 인증번호 발송 후 미검증(만료 전·시도 가능) |
| | `VERIFIED` | 인증번호 일치로 이메일 소유 확인 완료(온보딩 제출에 사용 가능) |

**협력 / 이벤트:** 회원 프로필·상태는 `user`가 소유하고 `auth`는 `userId`로만 참조한다(ADR-0002). 신규 분기의 `PENDING` 회원 생성, 약관 동의 시 `TERMS_AGREED` 전이, 온보딩 시 `ACTIVE` 전이, 탈퇴 시 `WITHDRAWN` 전이는 `user`의 공개 명령/쿼리로 협력하고 결과 식별자를 `SocialAccount.userId`로 보유한다. 온보딩 입력의 `gender`·`visaType`·`occupation`은 `user` 소유 enum이라 타입을 공유하지 않고 원시 값으로 전달한다. 온보딩 완료 명령 전에 `auth`가 `EmailVerification`의 `VERIFIED` 여부를 확인해 미인증 이메일을 거른다(인증 상태는 `auth` 소유, 확정된 `email` 값은 `user`가 보유). 인증번호 메일 발송은 아웃바운드 포트 `VerificationEmailSender`(application)로 추상화하고 인프라 어댑터(SES/SMTP — 확인 필요)가 구현한다 — **동기 발송**이라 발송 성공 시에만 챌린지를 확정하고, 발송 실패(provider 장애·타임아웃)는 `502 UPSTREAM_ERROR`로 응답한다(메일 템플릿·다국어는 확인 필요). `user`의 탈퇴 이벤트(`UserWithdrawnEvent`)를 구독해 해당 `userId`의 refresh 토큰을 일괄 무효화한다.

---

## 2. `user` — 회원 프로필·계정 lifecycle

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/users/me`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

회원 프로필과 계정 생애주기(가입·약관 동의·온보딩·수정·탈퇴)를 소유한다. 사용자는 소셜 검증만 끝난 `PENDING` → 약관 동의 완료 `TERMS_AGREED` → 온보딩 완료 `ACTIVE` → 탈퇴 `WITHDRAWN`의 단방향 상태 모델을 가진다(약관 동의와 온보딩은 분리된 단계로 각각 상태를 전이한다).

**`User`** — 회원의 프로필·동의·계정 생애주기를 일관성 경계로 묶는 애그리거트 루트. 식별자 `id`(소셜 자격→회원 매핑·세션 토큰·이메일 인증은 `auth` 소관). [값 객체: `FullName`, `Consent`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(타 모듈은 이 값으로만 회원 참조) |
| `name` | VO `FullName` | 이름(`firstName`)·성(`lastName`). 온보딩 시 확정 |
| `nickname` | String | 시스템이 배정하는 표시용 닉네임(`형용사 + 사물`). 전역 유니크, 사용자 입력 아님 — 온보딩 완료 시 자동 배정. 메인 화면 비노출(프로필·`더보기 탭`에서 확인) |
| `gender` | enum `Gender` | 성별. 온보딩 필수 |
| `birthDate` | LocalDate | 생년월일(과거 날짜만). 온보딩 필수 |
| `country` | String | 국적(ISO 3166-1 alpha-2 코드, 예 `VN`). 온보딩 필수 — 클라이언트는 국가만 전송, 표시명·국기(이미지 URL)·표시 언어(`lang`)는 `countries` 참조로 확보 |
| `occupation` | enum `Occupation` | 직업 유형. 온보딩 필수 |
| `email` | String | 인증 완료된 연락 이메일(온보딩 중 인증번호로 검증) — 민감정보. 소셜 제공자 이메일(`auth.SocialAccount.email`)과 별개 |
| `visaType` | enum `VisaType` | 비자 유형 — 민감정보. 온보딩 필수 |
| `status` | enum `UserStatus` | 계정 상태(생성 시 `PENDING`) |
| `consent` | VO `Consent` | 이용약관·개인정보처리방침·마케팅 동의 3종(동의 여부·시각·약관 버전). **약관 동의 단계**(`PENDING`→`TERMS_AGREED`)에서 확정 |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 최종 수정 시각(UTC) |
| `withdrawnAt` | Instant(UTC), nullable | 탈퇴 시각(탈퇴 시 기록) |

**불변식:** 상태 전이는 `PENDING → TERMS_AGREED → ACTIVE → WITHDRAWN`만 허용(역전이·건너뛰기 금지); **약관 동의** 상태 전이는 `PENDING`에서만 일어나며(→`TERMS_AGREED`) 이때 `consent`(이용약관·개인정보처리방침·마케팅 동의 + `agreedAt` + `termsVersion`)를 확정 — 이용약관·개인정보처리방침 동의가 모두 필요(미동의 `422 AUTH_REQUIRED_AGREEMENT_MISSING`)하고 마케팅 동의는 선택(기본 미동의); 이미 `TERMS_AGREED`면 약관 재호출은 상태·동의를 바꾸지 않는 멱등 성공(`200`, 의도적 재동의 아님 — 마케팅 동의 변경은 프로필 수정으로), 이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`; **온보딩 제출**은 `TERMS_AGREED`에서만 가능하며(약관 미동의 `PENDING` 상태에서 시도하면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`) 성공 시 `ACTIVE`로 전이하면서 `name`·`gender`·`birthDate`·`country`·`occupation`·`email`·`visaType`를 한 번에 확정하고 `nickname`을 자동 배정(이미 `ACTIVE` 재요청은 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`); 온보딩 완료에는 제출 `email`이 `auth`에서 인증 완료(`VERIFIED`)된 이메일과 일치해야 함(미인증·불일치 `422 AUTH_EMAIL_NOT_VERIFIED`); `nickname`은 `NicknameGenerator`(도메인 서비스)가 형용사 풀·사물 풀의 active 단어에서 골라 `형용사 + 사물`로 무작위 배정하되 전역 유니크가 보장될 때까지 재조합 재시도(상한 초과 시 fallback; 사용자 입력·수정 대상 아님); 필수 약관 동의는 프로필 수정으로 철회 불가(탈퇴 경로로만); 프로필 부분 수정은 `ACTIVE`에서만, 전송 필드만 변경(미전송 ≠ 비움), `birthDate`는 과거만(수정 대상은 `email`·`nickname` 제외 — [API 스펙](../api/specs/01-auth-onboarding.md) §9); 탈퇴는 `PENDING`/`TERMS_AGREED`/`ACTIVE`에서 `WITHDRAWN`으로(이미 `WITHDRAWN` 재요청 `409 USER_ALREADY_WITHDRAWN`); `WITHDRAWN`·부재 사용자 조회·수정은 `404 USER_NOT_FOUND`; 모든 변경은 `updatedAt`을 갱신; 탈퇴(`WITHDRAWN`) 시 `withdrawnAt`을 기록하고 식별 PII(이름·생년월일·국적·직업·이메일·비자·닉네임)를 즉시 익명화한다([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)); `email`·`visaType`은 민감정보로 로그·타 사용자 노출 시 마스킹(본인 `GET /users/me`는 평문 노출).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `FullName` | `firstName` | String | 이름. 빈 문자열 불가 |
| | `lastName` | String | 성. 빈 문자열 불가 |
| `Consent` | `termsOfServiceAgreed` | boolean | 이용약관 동의 |
| | `privacyPolicyAgreed` | boolean | 개인정보처리방침 동의 |
| | `marketingAgreed` | boolean | 마케팅 수신 동의(선택, 기본 `false`) |
| | `agreedAt` | Instant | 동의 시각(UTC) |
| | `termsVersion` | String | 동의한 약관 버전 — **약관 동의 단계**에서 서버가 기록([ADR-0012](../adr/0012-terms-version-management.md)). 세분화된 마케팅 동의 항목 추가는 고도화(확인 필요) |

**닉네임 생성(도메인 서비스):** `NicknameGenerator`가 **형용사 풀·사물 풀**(reference 데이터, 각각 단어 목록)의 **active 단어**에서 무작위로 각 1개를 골라(비활성 단어 제외) `형용사 + 사물`(앞=형용사, 뒤=사물)로 조합한다. 조합값은 `nickname` 전역 유니크를 만족해야 하며, 충돌 시 재조합으로 재시도하고(재시도 상한 도달 시 fallback — 예: 숫자 접미사) 동시 생성 경합은 유니크 제약으로 최종 차단한다. 온보딩 완료 시점에 `user`가 호출한다. 두 풀은 운영 중 가변인 reference 데이터로 물리 테이블·시딩·무작위 선택 전략은 [database-design](../database/database-design.md) §4-2.

**국가 참조:** `country`는 ISO 국가 코드만 보유하고(애그리거트가 직접 품는 VO가 아님), 표시명·국기는 `CountryRepository`(도메인 포트, 구현은 infrastructure)로 `countries`(code→표시명→국기) reference 데이터를 조회해 `Country`(`code`·`name`·`flag`=국기 이미지 URL) 참조 값으로 resolve한다 — 클라이언트는 국가(코드)만 전송하고 국기는 서버가 `countries`에서 채운다(국가+국기 수집). 온보딩/수정 입력은 `CountryRepository.existsByCode`로 코드 유효성을 검증한다. `Country`는 응답 조립용 reference 값(닉네임 풀과 같은 부류)이라 애그리거트/구성 VO 카탈로그에는 오르지 않는다. 물리 테이블은 [database-design](../database/database-design.md) §4-2.

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `UserStatus` | `PENDING` | 소셜 검증만 완료, 약관 미동의·온보딩 미완료 |
| | `TERMS_AGREED` | 약관 동의 완료, 온보딩 정보 미입력 |
| | `ACTIVE` | 온보딩 완료, 정상 이용 |
| | `WITHDRAWN` | 탈퇴 |
| `Gender` | `MALE` | 남성 |
| | `FEMALE` | 여성 |
| `Occupation` (임시) | `STUDENT` | 학생 |
| | `EMPLOYEE` | 직장인 |
| | `SELF_EMPLOYED` | 자영업 |
| | `JOB_SEEKER` | 구직 중 |
| | `ETC` | 기타 |
| `VisaType` | `VISA_STUDENT` | 유학·연수 |
| | `VISA_WORK` | 취업 |
| | `VISA_RESIDENCE` | 거주·가족동반 |
| | `VISA_WORKING_HOLIDAY` | 워킹홀리데이 |
| | `VISA_TOURISM` | 관광 |
| | `VISA_ETC` | 기타 |

> `Occupation` 값은 요구사항 정의서의 직업 드롭다운 항목이 미확정(잘림)이라 **임시 분류값**이다 — 실제 선택지 확정 시 갱신한다(확인 필요). `country`는 ISO 국가 코드를 보유하고, 표시명·국기(이미지 URL)는 `countries` reference로 확보한다(국가+국기 수집 — 클라이언트는 국가만 전송).

**협력 / 이벤트:** 모든 타 모듈은 사용자를 `User` 식별자(`id`)로만 참조한다(엔티티 비공유). 소셜 자격→회원 매핑·이메일 인증은 `auth`가 소유하며, `user`는 **회원 생성(`PENDING`)·약관 동의(`TERMS_AGREED` 전이)·온보딩 완료(`ACTIVE` 전이)·탈퇴(`WITHDRAWN` 전이)** 를 공개 명령으로, 프로필을 공개 쿼리로 제공한다(`auth`가 소셜 로그인 분기·약관 동의·온보딩 완료에서 호출). 온보딩 완료 명령은 사용자가 `TERMS_AGREED`이고 이메일이 `auth`에서 `VERIFIED`된 뒤에만 수행된다. 탈퇴 시 도메인 이벤트(예: `UserWithdrawnEvent`)를 발행해 `auth`가 refresh 토큰을 일괄 무효화하게 한다(ADR-0002). 닉네임·국적 등 표시정보가 필요한 타 모듈(예: `community`)에는 식별자 기반 공개 쿼리를 제공한다(탈퇴 회원은 닉네임 `(탈퇴한 사용자)`·국적 비움으로 마스킹).

---

## 3. `listing` — 매물 탐색·찜

> [API 스펙](../api/specs/03-listings-favorites.md) · [시퀀스](sequence-diagrams/03-listings-favorites/README.md) · `allowedDependencies = {common}`

외국인 대상 주거 매물의 탐색(리스트·지도·키워드)·상세·찜·최근 본 매물을 소유한다. 좌표는 WGS84 십진수, 금액은 KRW 정수, 시각은 UTC. 매물은 **건물/주소 단위 Listing**으로 관리하고, 동일한 가격·계약·성별·옵션을 가진 실제 방 묶음은 Listing 내부의 **방 상품(`RoomOffer`)** 으로 관리한다.

**`Listing`** — 주소와 공용시설을 공유하는 건물 매물 애그리거트 루트. 식별자 `id`는 MongoDB ObjectId의 24자리 hex 문자열이며 API에서는 `listingId`로 노출한다. [값 객체: `Location`, `Address`, `NearestTransit`, `Building`, `PropertyPolicies`, `Facilities`, `RoomOffer`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `schemaVersion` | int | 문서 구조 버전 |
| `landlordId` | 식별자 | 매물을 등록한 임대인 → `User` 식별자 참조 |
| `title` | String | 매물 제목 |
| `type` | enum `ListingType` | 매물 유형 |
| `status` | enum `ListingStatus` | 공개/임시저장/중지/삭제 상태 |
| `location` | VO `Location` | 지도 검색용 좌표 |
| `address` | VO `Address` | 표시 주소·행정구역 |
| `nearestTransit` | VO `NearestTransit` | 가까운 교통수단과 도보 시간 |
| `nearbyPlacesDescription` | String | 집주인이 자유 입력한 주변 시설 안내 |
| `nearbyUniversityCodes` | `Set<String>` | 학교 검색·추천에 사용할 **개별 대학(member) 코드**(`SNU`·`CAU` 등) — 진단의 `UniversityGroup` 그룹 코드가 아니라 개별 대학 코드를 저장한다(저장 형식 불변). 진단은 선택 그룹을 member 코드로 펼쳐 `$in`으로 매칭([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)) |
| `building` | VO `Building` | 건물 유형·층수·주차·엘리베이터·난방 |
| `propertyPolicies` | VO `PropertyPolicies` | 건물/전체 방 공통 정책(ARC·전입신고·식사·영어 안내 등) |
| `facilities` | VO `Facilities` | 공용 편의·보안·공간·제공 물품 |
| `roomOffers` | `List<RoomOffer>` | 가격·계약·방 특징·재고가 같은 실제 방 묶음 |
| `featureSummary` | `Set<ConditionTag>` | 활성 방 상품들의 필터 태그 합집합(상세 표시용) |
| `descriptions` | VO `Descriptions` | 다국어 상세 설명 |
| `extraNotes` | String | 자유 입력 주의사항 |
| `imageUrls` | `List<String>` | 건물 공용 이미지 URL 목록(순서 보존, 첫 번째가 썸네일) |
| `favoriteCount` | int | 찜 수 집계(≥0) |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 수정 시각(UTC) |

**불변식:** `status=PUBLISHED`인 매물만 목록·지도·상세·찜·신청·문의 대상이며 그 외 상태는 조회 시 부재처럼 처리한다(`404 LISTING_NOT_FOUND`); Listing은 최소 1개 이상의 `roomOffers`를 가져야 한다; 금액은 `RoomOffer.pricing`에 집주인이 정한 단일값으로 저장하고 사용자 필터의 최소·최대 예산은 조회 조건일 뿐 애그리거트 속성이 아니다; `featureSummary`는 상세 화면 표시용 합집합이며 필터 매칭의 정본은 반드시 같은 `RoomOffer`가 가격·재고·옵션을 동시에 만족하는지로 판정한다; `imageUrls` 첫 항목이 건물 썸네일; 직접 연락처는 매물 애그리거트에 저장하지 않고 신청·문의는 인앱 채팅으로만 연결한다; `favoriteCount`는 0 미만 불가이며 찜 등록/해제와 정합(멱등 토글에서 중복 증감 없음); `favorited`(사용자별 찜 여부)·`distanceMeters`(기준 좌표 대비 거리)는 조회 시점에 요청 주체·파라미터로 산출되는 표현값이며 애그리거트 영속 속성이 아니다.

**`RoomOffer`** — 동일한 가격·계약조건·성별 정책·방 특징을 가진 실제 방 묶음. Listing 내부 구성 요소이며 독립 애그리거트가 아니다. 식별자 `roomOfferId`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `roomOfferId` | 식별자 | Listing 내부 방 상품 식별자 |
| `name` | String | 사용자에게 노출되는 방 상품명 |
| `status` | enum `RoomOfferStatus` | 판매/노출 상태 |
| `rentalType` | enum `RentalType` | 임대 방식 |
| `pricing` | VO `Pricing` | 월세·보증금·관리비·통화 |
| `contract` | VO `Contract` | 최소/최대 계약기간·환불 안내 |
| `inventory` | VO `Inventory` | 전체 수량·계약 가능 수량·다음 입주 가능일 |
| `genderPolicy` | enum `GenderPolicy` | 성별 정책 |
| `features` | `Set<RoomFeature>` | 방 상품 자체 시설·형태 |
| `filterTags` | `Set<ConditionTag>` | 검색 최적화를 위해 정본 필드에서 파생한 필터 태그 |
| `roomImageUrls` | `List<String>` | 방 상품 전용 이미지 |

**불변식:** `pricing.monthlyRent`·`pricing.deposit`·`pricing.maintenanceFee`는 KRW 정수 ≥0; `contract.minStayMonths <= contract.maxStayMonths`; `inventory.availableCount <= inventory.totalCount`이고 둘 다 0 이상; 계약 확정은 `availableCount > 0`인 방 상품에서만 가능하며 확정 시 수량을 원자적으로 1 감소시킨다; 가격·성별 정책·개인 욕실/2인실 여부 등 필터 결과가 달라지는 조건이 다르면 별도의 `RoomOffer`로 분리한다.

**`Favorite`** — 사용자가 매물을 찜한 사실 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(userId, listingId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 찜한 사용자 → `User` 식별자 참조(소유권) |
| `listingId` | 식별자 | 찜 대상 매물 → `Listing` 식별자 참조 |
| `favoritedAt` | Instant | 찜한 시각(UTC, 찜 목록 정렬 기준) |

**불변식:** `(userId, listingId)`는 유일 → 중복 찜 불가; 찜 등록은 멱등(이미 찜이면 신규 생성 없이 현재 상태 반환 — 신규 201 / 기존 200); 찜 해제도 멱등(미찜 해제는 에러 없이 `favorited=false`); 등록 시 대상 매물은 공개 상태여야 함(없음·비공개 `404 LISTING_NOT_FOUND`).

**`RecentListing`** — 사용자가 최근 조회한 매물 기록 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(userId, listingId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 조회한 사용자 → `User` 식별자 참조(소유권) |
| `listingId` | 식별자 | 조회한 매물 → `Listing` 식별자 참조 |
| `viewedAt` | Instant | 마지막 조회 시각(UTC, 최신순 정렬 기준) |

**불변식:** `(userId, listingId)`는 유일 → 재조회는 새 기록 없이 `viewedAt`만 갱신(upsert·멱등); 인증 사용자의 상세 조회 시에만 기록(비로그인 미기록); 최근 7일 이내 기록만 유효(7일 경과분 노출 제외); 사용자당 최신순 최대 5건 노출; 본인 기록만 조회(`me` 스코프).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `Location` | `lat` | double | 위도(WGS84) |
| | `lng` | double | 경도(WGS84) |
| `Address` | `city` | String | 도시/광역 단위 코드 |
| | `district` | String | 구/군 단위 코드 |
| | `fullAddress` | String | 표시 주소 |
| | `detail` | String | 상세주소 |
| `NearestTransit` | `type` | enum `TransitType` | 가까운 교통수단 유형 |
| | `name` | String | 역/정류장 이름 |
| | `walkMinutes` | int | 도보 시간(분) |
| `Building` | `type` | enum `BuildingType` | 건물 형태 |
| | `usedFloorMin` | int | 사용 층 최소값 |
| | `usedFloorMax` | int | 사용 층 최대값 |
| | `totalFloors` | int | 건물 전체 층수 |
| | `parkingAvailable` | boolean | 주차 가능 여부 |
| | `elevatorAvailable` | boolean | 엘리베이터 여부 |
| | `heatingSystem` | enum `HeatingSystem` | 난방 방식 |
| `PropertyPolicies` | `arcRequired` | boolean | ARC 필요 여부 |
| | `residentRegistrationAvailable` | boolean | 전입신고 가능 여부 |
| | `studySuitable` | boolean | 학업 목적 거주 적합 여부 |
| | `mealsProvided` | boolean | 식사 제공 여부 |
| | `englishAvailable` | boolean | 영어 소통 가능 여부 |
| `Facilities` | `laundry` | Set<String> | 세탁 시설 |
| | `livingAmenities` | Set<String> | 생활 편의시설 |
| | `securityFeatures` | Set<String> | 보안 시설 |
| | `commonSpaces` | List<CommonSpace> | 공용 공간 |
| | `providedSupplies` | Set<String> | 제공 물품 |
| `CommonSpace` | `type` | enum `CommonSpaceType` | 공용 공간 유형 |
| | `count` | Integer | 수량. 미확정이면 null |
| `Pricing` | `monthlyRent` | int | 월세(KRW 정수) |
| | `deposit` | int | 보증금(KRW 정수) |
| | `maintenanceFee` | int | 관리비(KRW 정수) |
| | `currency` | enum `Currency` | 통화(현재 `KRW`) |
| `Contract` | `minStayMonths` | int | 최소 계약 개월 |
| | `maxStayMonths` | int | 최대 계약 개월 |
| | `refundPolicy` | VO `RefundPolicy` | 환불 정책 코드·설명 |
| `RefundPolicy` | `code` | enum `RefundPolicyCode` | 환불 정책 코드 |
| | `description` | String | 환불 정책 설명 |
| `Inventory` | `totalCount` | int | 동일 조건 실제 방 전체 수 |
| | `availableCount` | int | 현재 계약 가능한 수 |
| | `nextAvailableFrom` | LocalDate | 현재 가능 수량이 없을 때 가장 빠른 예상 입주 가능일 |
| `Descriptions` | `ko` | String | 한국어 상세 설명 |
| | `en` | String | 영어 상세 설명 |
| `MatchedPlace` | `type` | enum `MatchedPlaceType` | 매칭된 POI 유형(학교·지역·역). 키워드 검색 입력어가 매칭된 위치를 표현하는 조회 결과 값(매칭 없으면 부재) |
| | `name` | String | POI 이름 |
| | `lat` | double | 위도 |
| | `lng` | double | 경도 |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ListingType` | `GOSIWON` | 고시원 |
| | `CO_LIVING` | 코리빙 |
| | `SHARE_HOUSE` | 셰어하우스 |
| | `OTHER` | 기타 |
| `ListingStatus` | `DRAFT` | 임시저장 |
| | `PUBLISHED` | 공개 중 |
| | `PAUSED` | 임대인/운영자에 의해 노출 중지 |
| | `DELETED` | 삭제 처리 |
| `RoomOfferStatus` | `ACTIVE` | 노출·계약 가능 |
| | `INACTIVE` | 노출 중지 |
| `RentalType` | `MONTHLY_RENT` | 월세 |
| `Currency` | `KRW` | 원화 |
| `TransitType` | `SUBWAY` | 지하철 |
| | `BUS` | 버스 |
| `BuildingType` | `GOSIWON` | 고시원 |
| | `VILLA` | 빌라 |
| | `APARTMENT` | 아파트 |
| | `OFFICETEL` | 오피스텔 |
| | `OTHER` | 기타 |
| `HeatingSystem` | `CENTRAL` | 중앙난방 |
| | `INDIVIDUAL` | 개별난방 |
| | `DISTRICT` | 지역난방 |
| | `OTHER` | 기타 |
| `GenderPolicy` | `ANY` | 성별 무관 |
| | `FEMALE_ONLY` | 여성 전용 |
| | `MALE_ONLY` | 남성 전용 |
| | `GENDER_SEPARATED` | 층/공간 분리 |
| `ConditionTag` | `IMMEDIATE_MOVE_IN` | 즉시 입주 |
| | `FEMALE_ONLY` | 여성 전용 |
| | `PRIVATE_TOILET` | 개인 화장실 |
| | `PRIVATE_BATH` | 개인 욕실 |
| | `ENGLISH_AVAILABLE` | 영어 소통 가능 |
| | `RESIDENT_REGISTRATION` | 전입신고 가능 |
| | `NO_MAINTENANCE_FEE` | 관리비 없음 |
| | `MEALS_PROVIDED` | 식사 제공 |
| | `DOUBLE_ROOM` | 2인실 |
| `RoomFeature` | `SINGLE_ROOM` | 1인실 |
| | `DOUBLE_ROOM` | 2인실 |
| | `PRIVATE_TOILET` | 개인 화장실 |
| | `PRIVATE_BATH` | 개인 욕실 |
| | `PRIVATE_REFRIGERATOR` | 개인 냉장고 |
| | `MICROWAVE` | 전자레인지 |
| | `ELECTRIC_KETTLE` | 전기포트 |
| `CommonSpaceType` | `SHARED_KITCHEN` | 공용 주방 |
| | `SHARED_TOILET` | 공용 화장실 |
| | `SHARED_BATH` | 공용 욕실 |
| | `LOUNGE` | 라운지 |
| | `STUDY_ROOM` | 스터디룸 |
| `RefundPolicyCode` | `FULL_REFUND_BEFORE_7_DAYS` | 입주 7일 전 전액 환불 |
| | `PARTIAL_REFUND` | 부분 환불 |
| | `NON_REFUNDABLE` | 환불 불가 |
| | `CUSTOM` | 직접 입력 |
| `MatchedPlaceType` | `UNIVERSITY` | 학교 |
| | `REGION` | 지역 |
| | `SUBWAY_STATION` | 지하철역 |

> 정렬 프리셋(`ListingSort`: `RECOMMENDED`·`PRICE_ASC`·`DISTANCE`)과 지도 검색의 bbox·마커 결과 상한은 **조회 파라미터**이지 애그리거트 영속 속성이 아니다(거리순은 기준 좌표 필요, 누락 시 `400 LISTING_INVALID_SORT_PARAM`; 과대 영역 `400 LISTING_AREA_TOO_LARGE`; bbox 모순 `400 LISTING_INVALID_BBOX`).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다(ADR-0002). `Favorite`·`RecentListing`·`Listing.landlordId`는 `user`를 식별자로만 보유하고 표시정보는 `user` 공개 쿼리로 협력한다. 진단 기반 추천은 `diagnosis`가 본 모듈의 **공개 추천 쿼리**(조건·월세 범위·지역·대학 그룹으로 매물 조회)를 호출해 충족하며 매물 엔티티를 공유하지 않고 식별자·요약만 넘긴다 — `diagnosis`가 선택 대학 그룹을 펼친 member 코드 집합을 넘기면 `nearbyUniversityCodes`를 `$in`(ANY member)으로 매칭하고(빈 집합이면 대학 필터 생략), 월세는 `pricing.monthlyRent`를 `[monthlyRentMin, monthlyRentMax]`의 각 경계(존재 시)로 거른다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). 신청·문의(`booking`·`chat`)는 매물 존재·공개·임대인 식별자·대표 가격·썸네일이 필요할 때 공개 쿼리로 검증한다.

---

## 4. `diagnosis` — 6단계 맞춤 진단

> [API 스펙](../api/specs/02-diagnosis-recommendation.md) · [시퀀스](sequence-diagrams/02-diagnosis-recommendation/README.md) · `allowedDependencies = {common, user}`(번역용 표시 언어 조회 `getLanguage`)

6단계 맞춤 진단(지역·입국 목적(유학 여부)·대학 그룹/지역(구) 선택·주거 환경 조건·월세 최소-최대 범위·ARC 발급 여부)을 본인 소유 레코드로 영속하고, 진단 조건으로 `listing` 공개 쿼리와 협력해 추천 매물을 제공한다. **진행 중 답은 서버가 DB에 저장**한다 — 사용자당 진행 중(`IN_PROGRESS`) 진단 1건을 in-progress draft로 들고 단계별 답을 채워가다가, 제출 시 `COMPLETED`로 확정한다(누적 답 재전송 없음). 재진단은 기존을 수정하지 않고 새 in-progress 진단을 시작해 항상 새 레코드로 이력을 보존한다.

**`Diagnosis`** — 한 사용자의 6단계 진단(애그리거트 루트). **진행 중(`IN_PROGRESS`)에는 서버가 단계별 답을 채워가는 in-progress draft**이고, **제출 시 `COMPLETED`로 확정**된다. 식별자 `id`, 비즈니스 키 `(userId, idempotencyKey)`(멱등성 키가 제시된 경우에 한해 유일).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 진단 소유자 → `User` 식별자 참조 |
| `criteria` | VO `DiagnosisCriteria` | 6단계 입력(지역·입국 목적·대학 그룹/지역(구) 선택·조건·월세 범위·ARC). `IN_PROGRESS`에는 서버가 단계별로 채워가는 부분 값, `COMPLETED` 확정 시 불변 |
| `status` | enum `DiagnosisStatus` | 진단 상태(`IN_PROGRESS` → `COMPLETED`) |
| `idempotencyKey` | String | 중복 제출 방지용 멱등성 키(선택) |
| `submittedAt` | Instant, nullable | 제출 확정 시각(UTC). `COMPLETED` 확정 시 기록(`IN_PROGRESS`에는 부재) |

**불변식:** `status` 전이는 `IN_PROGRESS → COMPLETED`만 허용(역전이·건너뛰기 없음); 사용자당 진행 중(`IN_PROGRESS`) 진단은 1건만 — 단계별 답을 보낼 때마다 서버가 그 in-progress draft의 `criteria`에 해당 필드를 채운다(서버가 DB에 저장; 누적 답 재전송 없음); **제출은 in-progress 진단 확정 요청**으로, 서버가 저장된 답을 재검증해 `COMPLETED`로 확정하고 `submittedAt`을 기록한다(이 시점이 진단 생성=완료); `criteria`는 `COMPLETED` 확정 후 불변(재진단은 수정이 아니라 **새 in-progress 진단 시작** → 확정 시 새 `Diagnosis`); **이력/목록 조회는 `COMPLETED`만 노출**(`IN_PROGRESS` draft 제외); **입국 목적별 대학/지역 선택 정합** — ③ 대학·지역은 **두 필드로 분리**(`university`·`district`)하며, `purpose`가 `STUDY`이면 `university`가 필수이고 `district`는 비어야 하며(유학 분기), `NON_STUDY`(비유학) 분기면 `district`가 필수이고 `university`는 비어야 한다(입국 목적에 맞는 하나만 채워짐; 위반은 공통 `400 INVALID_INPUT` + `errors[]` 필드별 사유, 진단 도메인 전용 코드 없음); 조회·추천은 `userId`가 요청자와 일치하는 본인 소유에 한함(타인 `403 FORBIDDEN`); 부재 진단 조회 `404 DIAGNOSIS_NOT_FOUND`; `idempotencyKey`가 제시되면 동일 소유자 범위에서 (키 + 정규화 `criteria`)가 같은 재시도는 1건만 확정·같은 진단 반환(멱등; 정규화 `criteria`에는 `university`·`district` 등 신규 필드도 포함), 같은 키에 다른 `criteria` 재제출은 `409 DIAGNOSIS_IDEMPOTENCY_CONFLICT`.

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `DiagnosisCriteria` | `region` | enum `Region` | 지역. 필수 1택 |
| | `purpose` | enum `Purpose` | 입국 목적(유학 여부). 필수 1택(`STUDY`/`NON_STUDY`) |
| | `university` | enum `UniversityGroup`, nullable | 대학 그룹(③ 대학·지역 선택의 유학 분기). 6개 그룹 중 단일 선택(1택). `purpose`가 `STUDY`일 때 필수, 그 외엔 비움(두 필드 분리·조건부 필수). 필드 키는 `university`로 유지(`district`와 대칭). 추천 시 선택 그룹을 소속 개별 대학 코드(member)로 확장해 전달([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)) |
| | `district` | enum `District`, nullable | 지역(구)(③ 대학·지역 선택의 비유학 분기). `NON_STUDY`일 때 필수, 그 외엔 비움(UPPER_SNAKE; 두 필드 분리·조건부 필수) |
| | `conditions` | `Set<DiagnosisCondition>` | 주거 환경 조건. 0~3개·중복 제거 |
| | `monthlyRentMin` | int(KRW) | 월세 최솟값. 0 이상 정수, `monthlyRentMin <= monthlyRentMax`(위반은 `400 INVALID_INPUT`, 사유 `"0 이상이어야 합니다."`·`"monthlyRentMin은 monthlyRentMax 이하여야 합니다."`) |
| | `monthlyRentMax` | int(KRW) | 월세 최댓값. 0 이상 정수, `monthlyRentMin <= monthlyRentMax` |
| | `arcStatus` | enum `ArcStatus` | ARC 발급 여부. 필수 1택(위반은 `400 INVALID_INPUT`, 필드별 사유) |
| `RecommendationSuggestions` | `reason` | enum `NoMatchReason` | 매칭 0건 사유 |
| | `message` | String | 안내 메시지 — `diagnosisSuggestions` 컬렉션의 `reason`별 인라인 언어-키 맵(`{ "en": .., "ja": .. }`)에서 **사용자 언어 키로 서버가 선택**(해당 언어 키 부재 시 영어(`en`) 폴백, US-2-6 일관) |
| | `actions` | `List<SuggestionAction>` | 완화 제안 목록(1건 이상이면 비어 있음) |
| `SuggestionAction` | `type` | enum `SuggestionActionType` | 조정 유형 |
| | `detail` | String | 결과를 늘리기 위한 단일 조정 제안 — `diagnosisSuggestions` 컬렉션의 `type`별 인라인 언어-키 맵(`{ "en": .., "ja": .. }`)에서 **사용자 언어 키로 서버가 선택**(해당 언어 키 부재 시 영어(`en`) 폴백) |

> 진단 결과 화면은 `Diagnosis.criteria`를 입력으로 `listing`의 공개 추천 쿼리를 호출해 매물 요약(`ListingSummaryResponse`)·좌표를 조립한다. 매물은 본 모듈 애그리거트가 아니므로 식별자(`listingId`)로만 참조하며, 추천 결과는 진단에 종속된 읽기 결과로 영속하지 않는다. **대학 그룹 매칭**: 진단은 선택된 `UniversityGroup`을 소속 개별 대학 코드(member)로 확장해 넘기고, `listing`은 `nearbyUniversityCodes`를 그 member 코드 집합으로 `$in`(ANY member) 매칭한다 — `ETC`(member 빈 집합)면 대학 필터를 생략하고 지역(`region`) 기반 매칭만 적용한다. **월세 범위 매칭**: `monthlyRentMin`/`monthlyRentMax`는 listing에서 `pricing.monthlyRent >= min` AND `<= max`를 각각 별개 조건으로 적용한다(각 경계가 존재할 때만). 교차 모듈 계약은 `RecommendationCriteria`로 전달한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). 0건 추천 제안의 `message`/`detail`은 **MongoDB `diagnosisSuggestions` 컬렉션**(사유 `reason`을 `_id`로)의 `reason`/`type`별 **인라인 언어-키 맵**(`{ "en": .., "ja": .. }`)에서 서버가 사용자 언어 키로 골라 제공한다 — `user` 공개 query(`getLanguage`)로 취득한 표시 언어로 message·detail을 고른다(해당 언어 키 부재 시 영어(`en`) 폴백, US-2-6과 동일 i18n 경로 — 문항 라벨과 같은 인라인 언어-키 맵 방식 재사용).

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `Region` | `SEOUL` | 서울 |
| | `BUSAN` | 부산 |
| | `GYEONGGI` | 경기 |
| `Purpose` | `STUDY` | 학업(유학·연수) |
| | `NON_STUDY` | 비학업(취업 등) |
| `UniversityGroup` | `HUFS_KHU_KOREA` | 한국외대·경희대·고려대(③ 대학 그룹 선택, 유학 분기) |
| | `SKKU_SUNGSHIN` | 성균관대·성신여대 |
| | `SNU_CAU_SOONGSIL` | 서울대·중앙대·숭실대 |
| | `HONGIK_YONSEI_EWHA` | 홍익대·연세대·이화여대 |
| | `KONKUK_SEJONG_HYU` | 건국대·세종대·한양대 |
| | `ETC` | 기타 |
| `District` | `GURO_GU` | 구로구(③ 지역 선택, 비유학 분기, UPPER_SNAKE) |
| | `YEONGDEUNGPO_GU` | 영등포구 |
| | `GEUMCHEON_GU` | 금천구 |
| | `GWANAK_GU` | 관악구 |
| | `DONGDAEMUN_GU` | 동대문구 |
| | `ETC` | 기타 |
| `DiagnosisCondition` | `IMMEDIATE_MOVE_IN` | 즉시입주 |
| | `FEMALE_ONLY` | 여성전용 |
| | `PRIVATE_TOILET` | 개인화장실 |
| | `PRIVATE_BATH` | 개인욕실 |
| | `ENGLISH_AVAILABLE` | 영어가능 |
| | `RESIDENT_REGISTRATION` | 전입신고가능 |
| | `NO_MAINTENANCE_FEE` | 관리비없음 |
| | `MEALS_PROVIDED` | 식사제공 |
| | `DOUBLE_ROOM` | 2인실 |
| `ArcStatus` | `ARC_ISSUED` | ARC(외국인등록증) 발급 완료 |
| | `ARC_PENDING` | ARC 미발급·발급 예정 |
| `DiagnosisStatus` | `IN_PROGRESS` | 진행 중(서버가 단계별 답을 채워가는 in-progress draft, 이력·목록 비노출) |
| | `COMPLETED` | 제출 확정 완료(`IN_PROGRESS`에서 전이, 이력·목록 노출) |
| `NoMatchReason` | `NO_MATCH` | 조건에 맞는 매물 없음 |
| `SuggestionActionType` | `RELAX_REGION` | 지역 조건 완화 |
| | `RELAX_CONDITIONS` | 주거 조건 일부 해제 |
| | `INCREASE_BUDGET` | 월세 범위 확대(최소를 낮추거나 최대를 높임 — enum 코드는 유지) |
| | `ADJUST_KEYWORD` | 키워드 조정 |

> `DiagnosisCondition`은 `listing`의 `ConditionTag`와 동일 이름을 쓴다(각 모듈이 자기 enum을 소유·공유 금지).
>
> `UniversityGroup`·`District`는 ③ 대학·지역 선택 단계의 입력 enum으로, **enum 값 카탈로그의 정본은 이 문서**다(위 표에 등재). **고정 enum(코드 1:1 검증용)은 MongoDB `diagnosisQuestions` 카탈로그**(`step`·`field`·`options[{code, label}]`·`select{type,max}` — **데이터만**, 분기 메타 없음)로 두고, **표시 문자열(번역)은 같은 `diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — `code`(UPPER_SNAKE)는 제출 검증 enum과 단일 출처(1:1)로 고정·언어 무관이고, 질문은 `question: { "en": .., "ja": .., "ko": .. }`, 옵션 라벨은 `options[].label: { "en": .., "ja": .. }`처럼 **언어 코드를 키로 하는 맵**으로 둔다(서버가 사용자 언어 키로 선택, 부재 시 영어(`en`) 폴백; 닉네임 풀·`countries`와 다른 부류로, reference로 분리하지 않음). 대학 그룹 질문(`university`)·지역 질문(`district`)은 각각 별도 step 데이터로 카탈로그에 존재하고, **어느 질문을 낼지는 서비스가 저장된 `purpose`로 결정**한다(데이터에 분기 메타 없음). 대학 그룹 질문의 `options[].code`는 `UniversityGroup`과 1:1(6개 그룹), 사용자는 그중 1개를 단일 선택한다.
>
> **대학 그룹 → 소속 개별 대학 코드(member) 매핑** — `UniversityGroup`은 ③ 단계의 **선택 입력**이고, member 개별 코드(`SNU`·`CAU` 등 기존 개별 University 코드)는 **추천 질의 시 그룹을 펼친(query-time expansion) 결과**다. 개별 코드는 여전히 `listing`의 `nearbyUniversityCodes`에 저장되는 값으로(**listing 저장 형식은 변경하지 않음** — 그룹 코드가 아니라 member 개별 코드를 저장), 진단은 선택 그룹을 아래 member 집합으로 확장해 listing 공개 추천 쿼리에 넘기고 listing은 `nearbyUniversityCodes`를 그 집합으로 `$in`(ANY member) 매칭한다. `ETC`는 member가 빈 집합이라 대학 필터를 생략한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)).
>
> | `UniversityGroup` | ko 라벨 | en 라벨 | member 개별 대학 코드 |
> | --- | --- | --- | --- |
> | `HUFS_KHU_KOREA` | 한국외대·경희대·고려대 | HUFS · Kyung Hee · Korea Univ. | `HUFS`·`KHU`·`KOREA` |
> | `SKKU_SUNGSHIN` | 성균관대·성신여대 | Sungkyunkwan · Sungshin Women's | `SKKU`·`SUNGSHIN` |
> | `SNU_CAU_SOONGSIL` | 서울대·중앙대·숭실대 | Seoul National · Chung-Ang · Soongsil | `SNU`·`CAU`·`SOONGSIL` |
> | `HONGIK_YONSEI_EWHA` | 홍익대·연세대·이화여대 | Hongik · Yonsei · Ewha Womans | `HONGIK`·`YONSEI`·`EWHA` |
> | `KONKUK_SEJONG_HYU` | 건국대·세종대·한양대 | Konkuk · Sejong · Hanyang | `KONKUK`·`SEJONG`·`HYU` |
> | `ETC` | 기타 | Other | (빈 집합) — 대학 필터 생략, 지역 기반 매칭만 |
>
> **⑤ 월세 범위(`monthlyRent`) — `NUMBER_RANGE` 카브아웃** — 5단계 월세 입력은 enum 옵션이 아니라 **숫자 범위 자유 입력**이라 `diagnosisQuestions`의 step-5 `select.type`을 `"NUMBER_RANGE"`(두 숫자 입력 필드, `options`는 빈 배열)로 둔다 — "모든 step은 코드가 enum과 1:1인 고정 옵션 목록"이라는 전제에서 의도적으로 갈라진 예외다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다 — `userId`(→ `user`), 추천 결과의 `listingId`(→ `listing`). 추천은 `Diagnosis.criteria`로 **조건·월세 범위·지역(+대학 그룹/지역구)으로 매물 요약을 조회하는 `listing` 공개 추천 쿼리**(예: `recommendByCriteria`)를 동기 호출해 매물 요약(`ListingSummaryResponse`)·좌표를 받아 조립한다(엔티티 비공유, listing 내부 스키마는 변경하지 않고 호출 인터페이스만 참조; ADR-0002 Decision 5). 교차 모듈 계약(`RecommendationCriteria`)에서 `university`는 단일 `String`이 아니라 **member 개별 대학 코드의 `Set<String>`**(`ETC`면 빈 집합)이고, `monthlyRentMin`/`monthlyRentMax`는 nullable(null/부재 = 해당 경계 무제한)로 전달해 listing이 `pricing.monthlyRent >= min` AND `<= max`를 각 경계가 존재할 때만 별개 조건으로 적용한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). 라벨 번역에 쓸 **표시 언어는 `user` 공개 쿼리(`getLanguage`)로 동기 취득**한다(`user`가 등록 국가 `countries.lang`으로 도출) — `user`를 식별자/원시 값으로만 참조하고 엔티티를 공유하지 않는다(토큰 클레임 분기 제거; ADR-0002 Decision 5). 이로써 **모듈 의존 `diagnosis → user`를 추가**한다(아래 `allowedDependencies` 항목). 진단 제출·재진단은 본 모듈 내부에서 완결되며 외부 발행 이벤트는 없다.

- **문항·선택지 카탈로그(US-2-5)** — 6단계별 {질문(`question`), 선택지[`code`], 선택 제약(`select{type, max}`)}는 `Diagnosis` 애그리거트가 아니라 **MongoDB `diagnosisQuestions` 컬렉션(도메인 포트로 조회)**로 제공한다 — **데이터만 보유**하고 분기 메타(`branchOn` 등)는 두지 않는다(분기는 서비스 비즈니스 로직 소관). 번역(표시 문자열)은 분리 컬렉션 없이 **같은 `diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — 질문은 `question: { "en": .., "ja": .., "ko": .. }`, 옵션 라벨은 `options[].label: { "en": .., "ja": .. }`로 두고 선택지 `code`(UPPER_SNAKE)는 언어 무관 불변이다. 문항 제공은 **단계별 server-stateful 질의응답**이다 — 클라이언트가 받을 step(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`(인증 필수, 200)을 호출하면, 서버가 (카탈로그 + 본인 진행 중(`IN_PROGRESS`) 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정해 `{ step, field, question, select{type, max}, options[{code, label}] }`(`question`·`label`은 서버가 인라인 언어-키 맵에서 사용자 언어 키로 고른 표시 문자열, `code`는 언어 무관)로 내려준다(한 번에 다 주지 않음; 다음 step 번호는 클라가 정한다). 현재 step 답은 별도로 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; `conditions`처럼 다중은 `codes` 배열; **⑤ 월세 범위는 enum 코드가 아니라 두 숫자 필드** `{ "field": "monthlyRent", "min": 300000, "max": 600000 }` — 순서 없는 `codes[]` 배열을 재사용하지 않는다)로 보내면 서버가 **본인 진행 중(`IN_PROGRESS`) 진단에 저장**한다(누적 답 묶음 전송 없음). 흐름은 `GET questions/1 → POST answers → GET questions/2 → … → GET questions/6 → POST answers → POST /diagnoses`이며, 모든 단계 답이 저장되면 `POST /api/v1/diagnoses`(제출)가 진행 중 진단의 저장된 답을 재검증해 `COMPLETED`로 확정한다. **분기는 서비스 비즈니스 로직이 결정한다(클라 로컬 분기·데이터 분기 메타 아님)** — ③ 대학·지역 단계(step 3)는 저장된 `purpose`를 보고 서비스가 알맞은 질문만 낸다: `STUDY`면 대학 그룹 질문(`university`, 목록 `UniversityGroup` 6개 그룹)을, `NON_STUDY`면 지역 질문(`district`, 목록 `District`)을 내려준다(두 질문 데이터는 카탈로그에 각각 존재하고, 노출은 서비스가 결정; 한 응답에 두 목록을 함께 주지 않는다). 선택지 `code`는 제출 검증 enum과 **동일 출처(1:1)** 라 코드로 제출하면 `INVALID_INPUT` 없이 수용된다(카탈로그·번역 모두 `diagnosisQuestions` 도큐먼트에 함께 보유). 잘못된 현재 step 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `400 INVALID_INPUT`+`errors[]`로 거른다.
- **라벨 번역(US-2-6)** — 표시 `label`·`question`은 **사용자 표시 언어**의 값으로 채운다. `code`는 언어 무관 동일(UPPER_SNAKE)이며 인라인 언어-키 맵의 값(표시 문자열)만 언어별이고, 해당 언어 키가 없으면 영어(`en`)로 폴백한다(에러 아님; `Accept-Language` 비의존). 표시 언어는 **`user` 공개 쿼리(`getLanguage`)로 동기 취득**한다(`user`가 등록 국가 `countries.lang`으로 도출; 토큰 클레임 분기 제거; ADR-0002 Decision 5) — `user`는 식별자/원시 값으로만 참조하고 엔티티를 공유하지 않는다. 표시 문자열은 **`diagnosisQuestions` 도큐먼트의 `question`/`options[].label`에 인라인 언어-키 맵으로 임베드**한다: `question: { "en": "Select a region", "ja": "エリアを選択", "ko": "지역 선택" }`, `options: [ { "code": "SEOUL", "label": { "en": "Seoul", "ja": "ソウル" } }, ... ]`처럼 **언어 코드를 키로 하는 맵**이다(문항·옵션은 `diagnosisQuestions`, 추천 사유/액션은 `diagnosisSuggestions` 컬렉션에 같은 인라인 언어-키 맵 방식 재사용). **국가→언어 매핑은 `user`의 `countries.lang`이 보유**하며, 미지원 언어의 **폴백 기본 언어는 영어**다. 서버 동작: 표시 언어(`user` `getLanguage`) → 도큐먼트의 언어-키 맵에서 그 언어 키 값을 골라(부재 시 `en`) 응답 조립.
- **`allowedDependencies`** — 라벨 번역이 표시 언어를 `user` 공개 쿼리(`getLanguage`)로 동기 취득하므로 `diagnosis`의 `allowedDependencies`는 **`user`를 포함**한다(즉 `{common, user}`; 토큰 클레임 분기 제거로 `{common}` 유지 안 함). 이는 `package-info.java`/`@ApplicationModule`에 반영된다.

---

## 5. `booking` — 매물 신청(예약)

> [API 스펙](../api/specs/04-booking-inquiry-chat.md) · [시퀀스](sequence-diagrams/04-booking-inquiry-chat/README.md) · `allowedDependencies = {common}`

세입자가 매물에 입주를 신청(예약)하는 컨텍스트다. 신청 성공 시 도메인 이벤트를 발행해 임대인과의 채팅·예약 카드 고정을 `chat`에 위임한다.

**`Booking`** — 세입자가 특정 매물에 제출한 신청(예약) 애그리거트 루트. 식별자 `id`, 활성 예약 비즈니스 키 `(tenantId, listingId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `tenantId` | 식별자 | 신청한 세입자 → `User` 식별자 참조(소유권) |
| `listingId` | 식별자 | 신청 대상 매물 → `Listing` 식별자 참조 |
| `landlordId` | 식별자 | 매물 소유자(임대인) → `User` 식별자 참조(본인 매물 차단·이벤트 페이로드용) |
| `moveInDate` | LocalDate | 입주 희망일(날짜만) |
| `contractPeriod` | enum `ContractPeriod` | 신청 시 선택한 계약 기간 |
| `greetingMessage` | VO `GreetingMessage` | 첫 인사 메시지(선택). 존재 시 이벤트 페이로드로 `chat`에 전달 |
| `status` | enum `BookingStatus` | 예약 상태(신청 직후 `REQUESTED`) |
| `createdAt` | Instant | 신청 시각(UTC) |

**불변식:** 동일 `tenantId`–`listingId`의 활성 예약(`REQUESTED`/`ACCEPTED`)은 1건만(`409 BOOKING_ALREADY_EXISTS`); 본인 소유 매물 신청 금지 — `tenantId == landlordId`면 거부(`422 BOOKING_SELF_NOT_ALLOWED`); `moveInDate`는 과거 불가·매물 입주 가능일 이전 불가(`422 BOOKING_INVALID_MOVE_IN_DATE`); `contractPeriod`는 정의된 값(`400 INVALID_INPUT`); 신청 생성 시 `status`는 항상 `REQUESTED`; 동시 도착에도 정확히 1건만 성립(활성 비즈니스 키 유일성으로 멱등).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `GreetingMessage` | `text` | String | 공백 제외 1~500자. 신청과 함께 보내는 첫 인사 — 미제공 시 부재, 길이 초과는 `400 INVALID_INPUT`, 존재할 때만 채팅 첫 텍스트 메시지로 전달 |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `BookingStatus` | `REQUESTED` | 신청 직후 기본 상태 |
| | `ACCEPTED` | 임대인 수락 |
| | `REJECTED` | 임대인 거절 |
| | `CANCELED` | 세입자 취소 |
| `ContractPeriod` | `ONE_MONTH` | 1개월 |
| | `THREE_MONTHS` | 3개월 |
| | `SIX_MONTHS` | 6개월 |
| | `TWELVE_MONTHS` | 12개월 |

**협력 / 이벤트:** 세입자·임대인은 `user`, 매물은 `listing`을 식별자로만 참조한다(ADR-0002). 매물 존재·공개 검증, 임대인(`landlordId`) 식별, 입주 가능일 조회는 `listing` 공개 쿼리로 받는다(부재/비공개 `404 LISTING_NOT_FOUND`). 신청 성공 시 **`BookingCreatedEvent`** 발행 — 페이로드는 원시/공유 타입(`bookingId`·`listingId`·`tenantId`·`landlordId`·`moveInDate`·`contractPeriod`·첫 인사 메시지). `chat`이 구독해 임대인 채팅방 보장·예약 카드 고정·첫 인사 전송을 처리한다. `booking`은 `chat`을 알지 못한다(단방향).

---

## 6. `chat` — 인앱 채팅

> [API 스펙](../api/specs/04-booking-inquiry-chat.md) · [시퀀스](sequence-diagrams/04-booking-inquiry-chat/README.md) · `allowedDependencies = {common, booking}`(이벤트 구독 목적)

세입자(요청자)와 임대인/이웃 사이의 1:1 인앱 채팅을 소유한다. 신청·문의로 임대인 채팅방을 보장하고, 카드/시스템/텍스트 메시지와 참여자별 읽음 위치를 일관성 경계 안에서 관리한다.

**`ChatRoom`** — 두 참여자 간의 1:1 채팅방 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(listingId, tenantId, landlordId)`. 구성 엔티티: `Message`, `ReadCursor`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `category` | enum `ChatCategory` | 채팅방 종류(임대인 `LANDLORD` / 이웃 `NEIGHBOR`) |
| `listingId` | 식별자 | 대상 매물 → `Listing` 식별자 참조. `LANDLORD`는 필수, `NEIGHBOR`는 없을 수 있음 |
| `tenantId` | 식별자 | 세입자/요청자 → `User` 식별자 참조 |
| `landlordId` | 식별자 | 상대(임대인/이웃) → `User` 식별자 참조 |
| `listingSnapshot` | VO `ListingSnapshot` | 목록 표시용 매물 비정규화 스냅샷. `NEIGHBOR`는 없을 수 있음 |
| `active` | boolean | 활성 여부(차단/나간 방은 비활성) |
| `lastMessageAt` | Instant | 마지막 메시지 시각(목록 정렬키) |
| `createdAt` | Instant | 생성 시각(UTC) |
| `messages` | `List<Message>` | 방에 속한 메시지(구성 엔티티) |
| `readCursors` | `List<ReadCursor>` | 참여자별 읽음 위치(구성 엔티티) |

**불변식:** `(listingId, tenantId, landlordId)`로 채팅방 유일 — 문의 시 존재하면 기존 방 반환(`created=false`), 없으면 신규(`created=true`)(멱등); 본인 소유 매물엔 방 생성 불가(`422 CHAT_SELF_INQUIRY_NOT_ALLOWED`); 참여자는 정확히 `tenantId`·`landlordId` 둘이며 그 외 접근 금지(`403 FORBIDDEN`); `LANDLORD` 방은 `listingId`와 매물 스냅샷을 반드시 가짐; 미존재 방 `404 CHAT_ROOM_NOT_FOUND`; 신규 `LANDLORD` 방 생성 시 카드 메시지(`BOOKING_CARD`/`LISTING_CARD`)를 정확히 1개 고정(`pinned=true`)·중복 추가 없음; 메시지 추가 시 `lastMessageAt`은 전진만(후퇴 금지); 비활성 방엔 전송 불가(`422 CHAT_ROOM_INACTIVE`).

**`Message`** — 채팅방에 속한 한 건의 메시지(구성 엔티티). 식별자 `id`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 메시지 식별자(읽음 커서·정렬·커서 페이지네이션 기준) |
| `roomId` | 식별자 | 소속 채팅방 → `ChatRoom`(같은 모듈) |
| `type` | enum `MessageType` | 메시지 유형 |
| `senderId` | 식별자 | 발신자 → `User` 식별자 참조. 시스템·카드는 발신자 없음 |
| `content` | String | 본문. `TEXT`에만 존재 |
| `bookingCard` | VO `BookingCard` | 예약 카드 페이로드. `BOOKING_CARD`에만 존재 |
| `listingCard` | VO `ListingCard` | 매물 카드 페이로드. `LISTING_CARD`에만 존재 |
| `pinned` | boolean | 상단 고정 여부 |
| `sentAt` | Instant | 전송 시각(UTC) |

**불변식:** 사용자가 전송 가능한 것은 `TEXT`뿐(`BOOKING_CARD`·`LISTING_CARD`·`SYSTEM`은 서버 생성, 타입을 본문으로 받지 않음); `TEXT`는 발신자 필수·공백 제외 1~1000자(빈/공백만/초과 `400 INVALID_INPUT`); `SYSTEM`·카드는 발신자 없음; `BOOKING_CARD`는 `bookingCard`, `LISTING_CARD`는 `listingCard`를 정확히 가지며 `content` 없음; `pinned=true`는 카드 메시지에만; 메시지는 자기 방 안에서만 의미를 가짐; 생성 후 불변.

**`ReadCursor`** — 한 참여자의 "마지막으로 읽은 메시지" 위치(구성 엔티티). 비즈니스 키 `(roomId, userId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `roomId` | 식별자 | 소속 채팅방 → `ChatRoom`(같은 모듈) |
| `userId` | 식별자 | 읽음 위치의 주체(참여자) → `User` 식별자 참조 |
| `lastReadMessageId` | 식별자 | 마지막으로 읽은 메시지 → `Message`(같은 모듈) |
| `updatedAt` | Instant | 읽음 위치 갱신 시각(UTC) |

**불변식:** `(roomId, userId)`로 참여자별 단일 커서; 방 참여자만 커서 보유; 읽음 위치는 **전진만**(과거로의 갱신은 무시·멱등); `lastReadMessageId`는 해당 방 메시지여야 함(`422 CHAT_MESSAGE_NOT_IN_ROOM`); 미지정 시 방 최신 메시지까지 읽음 처리; 안읽음 수(`unreadCount`)는 커서 이후 도착한 본인 미발신 메시지 수로 산출(갱신 직후 0).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `BookingCard` | `moveInDate` | LocalDate | 입주 희망일 |
| | `contractPeriod` | enum `ContractPeriod` | 계약 기간 |
| | `monthlyRent` | int(KRW) | 월세 |
| | `listingTitle` | String | 매물 제목 |
| | `bookingId` | 식별자 | → `Booking` 식별자 참조(예약 생성 시점 정보를 굳혀 상단 고정하는 불변 카드) |
| `ListingCard` | `listingId` | 식별자 | → `Listing` 식별자 참조 |
| | `title` | String | 매물 제목 |
| | `monthlyRent` | int(KRW) | 월세 |
| | `thumbnailUrl` | String | 썸네일 URL(문의 시점 매물 정보를 굳혀 고정하는 불변 카드) |
| `ListingSnapshot` | `listingId` | 식별자 | → `Listing` 식별자 참조 |
| | `title` | String | 매물 제목 |
| | `thumbnailUrl` | String | 썸네일 URL |
| | `monthlyRent` | int(KRW) | 월세(채팅방 목록을 매물 조회 없이 표시하는 비정규화 뷰) |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ChatCategory` | `LANDLORD` | 임대인 문의·신청으로 생성되는 채팅 |
| | `NEIGHBOR` | 커뮤니티에서 시작되는 이웃 채팅 |
| `MessageType` | `TEXT` | 사용자가 전송하는 텍스트 메시지 |
| | `BOOKING_CARD` | 예약 정보 카드(서버 생성·고정) |
| | `LISTING_CARD` | 매물 정보 카드(서버 생성·고정) |
| | `SYSTEM` | 시스템 안내 메시지(서버 생성) |

**협력 / 이벤트:**

- **구독** — `booking`의 `BookingCreatedEvent`(원시/공유 타입 페이로드)를 받아 임대인 채팅방 보장·`BOOKING_CARD` 고정. `booking`은 `chat`을 모르는 단방향(ADR-0002).
- **발행** — 새 메시지 발생 시 상대 참여자 푸시 알림용 도메인 이벤트(roomId·수신자 식별자·메시지 요약 등 원시/공유 타입) 발행.
- **공개 쿼리(소비)** — 문의 방 생성 시 `listing` 공개 쿼리로 매물 존재·공개·소유자·제목·월세·썸네일 확인(본인 매물 차단·카드/스냅샷 구성). 이웃 채팅은 `community` 게시글 작성자 식별자를 상대로 받아 방 보장.
- **공개 쿼리(제공)** — `report`의 메시지 신고 권한 검증 등을 위해 메시지의 소속 방·참여자 여부를 식별자 기반으로 제공.
- 타 애그리거트(`Listing`·`User`·`Booking`)는 식별자로만 참조(엔티티 비공유).

---

## 7. `community` — 커뮤니티

> [API 스펙](../api/specs/05-community.md) · [시퀀스](sequence-diagrams/05-community/README.md) · `allowedDependencies = {common}` · **1차 MVP 이후**

자유게시판(`FREE`)·동네생활(`NEIGHBORHOOD`)의 텍스트 게시글 작성·조회·검색, 좋아요 토글·공유 카운트, 댓글, 동네친구 1:1 채팅 시작을 담당한다. 게시글은 제목+본문 텍스트만 다루며, 작성자 표시정보는 `user` 공개 쿼리로, 채팅 시작은 `chat` 협력으로 해결한다.

**`Post`** — 자유게시판/동네생활의 텍스트 게시글 애그리거트 루트. 식별자 `id`. [구성 엔티티: `Comment`, `PostLike`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `authorId` | 식별자 | 작성자 → `User` 식별자 참조 |
| `boardType` | enum `BoardType` | 게시판 종류(`FREE`/`NEIGHBORHOOD`) |
| `title` | String | 제목(1~100자) |
| `content` | String | 본문(1~5000자) |
| `hashtags` | `List<Hashtag>` | 해시태그 목록(`#` 제외, 최대 10개) |
| `likeCount` | int | 좋아요 수 집계(≥0) |
| `commentCount` | int | 미삭제 댓글 수 집계(≥0) |
| `shareCount` | int | 공유 수 집계(≥0) |
| `comments` | `List<Comment>` | 구성 엔티티: 댓글 |
| `likes` | `Set<PostLike>` | 구성 엔티티: 좋아요(작성자별 최대 1개) |
| `deleted` | boolean | 소프트 삭제 플래그 |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 수정 시각(UTC) |

**불변식:** `title` 1~100자·`content` 1~5000자·`boardType` 정의된 값(`400 INVALID_INPUT`); `hashtags`는 최대 10개·`#` 없이 정규화·중복 제거; 작성 시 `authorId`는 인증 주체로 고정, 카운트 3종은 0에서 시작; 수정은 작성자 본인만·전송 필드만 부분 반영(변경 없으면 거부, 소유권 위반 `403 FORBIDDEN`); 삭제는 작성자 본인만 소프트 삭제·멱등, 부재/이미 삭제는 소유권보다 먼저 판정(`404 POST_NOT_FOUND`); 소프트 삭제 글은 목록·상세·검색 제외; 수정 시 `updatedAt` 갱신; 모든 카운트는 동시 변경에도 음수 불가.

> **좋아요 토글:** 사용자의 좋아요 존재 여부를 멱등하게 뒤집는다. 없으면 `PostLike` 추가 + `likeCount` +1, 있으면 제거 + −1. 사용자당 최대 1개, 동시 토글에도 `likeCount`는 실제 좋아요 수와 정확히 일치.
> **공유 카운트 증가:** 호출마다 `shareCount` +1(비멱등). 사용자 레이트리밋 초과 시 거부(`429 TOO_MANY_REQUESTS`).

**`Comment`** — 게시글에 종속된 댓글(구성 엔티티). 식별자 `id`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 댓글 식별자 |
| `postId` | 식별자 | 소속 게시글 → `Post` 식별자 참조(같은 모듈) |
| `authorId` | 식별자 | 작성자 → `User` 식별자 참조 |
| `content` | String | 댓글 내용(1~1000자) |
| `deleted` | boolean | 소프트 삭제 플래그 |
| `createdAt` | Instant | 생성 시각(UTC) |

**불변식:** `content`는 공백 아닌 1~1000자(`400 INVALID_INPUT`); 작성 시 소속 게시글 `commentCount` +1; 삭제는 작성자 본인만 소프트 삭제·`commentCount` −1(동시 삭제에도 음수 불가, 소유권 위반 `403 FORBIDDEN`); 게시글 부재(`404 POST_NOT_FOUND`)·댓글 부재/이미 삭제(`404 COMMENT_NOT_FOUND`)는 소유권보다 먼저 판정; 소프트 삭제 댓글은 목록 제외·카운트 미포함; 댓글은 작성순 정렬.

**`PostLike`** — 게시글에 대한 사용자의 좋아요(구성 엔티티). 식별자 `id`, 비즈니스 키 `(postId, userId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 좋아요 식별자 |
| `postId` | 식별자 | 대상 게시글 → `Post` 식별자 참조(같은 모듈) |
| `userId` | 식별자 | 좋아요한 사용자 → `User` 식별자 참조 |
| `createdAt` | Instant | 좋아요 시각(UTC) |

**불변식:** `(postId, userId)`는 유니크 → 사용자당 게시글에 최대 1개; 생성·제거는 멱등(동시 토글에도 중복 없음)이며 결과가 소속 게시글 `likeCount`와 항상 정합.

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `Hashtag` | `name` | String | `#` 없이 정규화된 태그명. 동일 게시글 내 중복 불가, 게시글당 최대 10개 |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `BoardType` | `FREE` | 자유게시판 |
| | `NEIGHBORHOOD` | 동네생활 |

**협력 / 이벤트:** 타 애그리거트는 식별자(`authorId`·`userId`)로만 참조한다(ADR-0002). 작성자 표시정보(닉네임·국적)는 `user` 공개 쿼리로 조립하며, 탈퇴 작성자는 닉네임 `(탈퇴한 사용자)`·국적 비움으로 마스킹. 동네친구 1:1 채팅 시작은 `chat`의 `NEIGHBOR` 방 보장에 위임(게시글 작성자를 상대로 전달, 기존 방이면 멱등 반환). 본인 글 채팅 불가(`422 POST_CHAT_SELF_NOT_ALLOWED`), 작성자 불가(`422 POST_CHAT_AUTHOR_UNAVAILABLE`), 차단 관계(`403 POST_CHAT_BLOCKED`, `report` 차단 모델 의존)는 차단한다.

---

## 8. `gamification` — 퀴즈·포인트

> [API 스펙](../api/specs/06-gamification.md) · [시퀀스](sequence-diagrams/06-gamification/README.md) · `allowedDependencies = {common}` · **1차 MVP 이후**

오늘의 퀴즈(하루 1문제, 4지선다)를 제공하고, 제출을 서버가 채점해 정답이면 포인트를 적립하며, 사용자별 포인트 합계·적립 내역을 제공한다.

**`Quiz`** — 특정 날짜에 노출되는 4지선다 오늘의 퀴즈(애그리거트 루트). 식별자 `id`, 비즈니스 키 `quizDate`. [값 객체: `QuizChoice`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `quizDate` | LocalDate | 퀴즈가 노출되는 날짜(날짜당 1개) |
| `question` | String | 문제 본문 |
| `choices` | `List<QuizChoice>` | 4지선다 보기(키 A~D, 보기 텍스트) |
| `correctChoice` | enum `ChoiceKey` | 정답 보기 키(제출을 마친 사용자에게만 공개) |
| `explanation` | String | 정답 해설(제출을 마친 사용자에게만 공개) |

**불변식:** `quizDate`는 전 퀴즈에 걸쳐 유일(날짜당 정확히 1개); `choices`는 정확히 4개·키 `A`·`B`·`C`·`D` 각 1개(중복·누락 없음); `correctChoice`는 `choices` 키 집합에 포함; `correctChoice`·`explanation`은 제출을 마친 주체에게만 노출(미제출자에겐 가림); 서버 기준 오늘 퀴즈가 없으면 조회·제출 실패(`404 QUIZ_NOT_FOUND`); 제출은 `quizDate`가 오늘인 퀴즈에만(`422 QUIZ_NOT_TODAY`).

**`QuizSubmission`** — 한 사용자의 하루 1회 제출 결과 스냅샷(채점·적립 확정값) 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(userId, quizDate)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 제출 주체 → `User` 식별자 참조 |
| `quizId` | 식별자 | 제출 대상 → `Quiz` 식별자 참조 |
| `quizDate` | LocalDate | 제출 대상 퀴즈의 날짜(하루 1회 판정 기준값) |
| `selectedChoice` | enum `ChoiceKey` | 사용자가 선택한 보기 키 |
| `correct` | boolean | 서버 채점 결과(정답 여부) |
| `earnedPoint` | int | 이 제출로 적립된 포인트(오답이면 0) |
| `submittedAt` | Instant | 제출·채점 확정 시각(UTC) |

**불변식:** `(userId, quizDate)`는 유일 → 사용자당 같은 날 제출 1건만(동시·재시도 `409 QUIZ_ALREADY_SUBMITTED`, 멱등); `selectedChoice`는 A~D 중 하나(그 외 `400 INVALID_INPUT`); `correct`는 클라이언트 입력이 아니라 `Quiz.correctChoice`와 대조한 서버 판정; `correct=true`면 `earnedPoint`는 정답 적립 단위, `false`면 0; 채점·제출 기록·정답 적립은 하나의 원자적 단위로 처리(정답은 정확히 1건 적립, 중복 없음); 제출은 오늘 퀴즈에만(`422 QUIZ_NOT_TODAY`); 결과는 제출을 마친 본인에게만 노출.

**`PointHistory`** — 포인트 적립 1건을 기록하는 추가 전용(append-only) 애그리거트 루트. 식별자 `id`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 적립 귀속 주체 → `User` 식별자 참조 |
| `amount` | int | 적립 포인트(양수, **포인트 정수이며 KRW 금액 아님**) |
| `reason` | enum `PointReason` | 적립 사유 |
| `createdAt` | Instant | 적립 시각(UTC) |

**불변식:** 추가 전용(기록 후 수정·삭제 없음); `amount`는 양수(현재 범위에 차감·음수 없음); 정답 제출당 정확히 1건의 `PointHistory`(`QUIZ_CORRECT` 사유), 오답은 미생성; 사용자 포인트 합계는 별도 잔액이 아니라 해당 `userId`의 `amount` 합으로 도출(집계가 유일한 진실 원천); 조회는 인증 주체 `userId`로만 필터링.

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `QuizChoice` | `key` | enum `ChoiceKey` | 4지선다 보기 키(A~D). 동일 `Quiz` 내 유일 |
| | `text` | String | 보기 텍스트(비어 있지 않음) |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ChoiceKey` | `A` | 4지선다 첫 번째 보기 |
| | `B` | 두 번째 보기 |
| | `C` | 세 번째 보기 |
| | `D` | 네 번째 보기 |
| `PointReason` | `QUIZ_CORRECT` | 퀴즈 정답 적립(현재 범위의 유일 사유) |

**협력 / 이벤트:** 제출·적립 귀속 주체는 `user`를 식별자(`userId`)로만 참조한다(ADR-0002). 포인트 합계·내역은 인증 주체 `userId`로 필터링한 본 모듈 내부 집계로 제공한다. 외부 모듈이 포인트 적립을 알아야 하면 공개 쿼리 또는 적립 도메인 이벤트로 협력한다.

---

## 9. `report` — 신고 처리

> [API 스펙](../api/specs/07-reports.md) · [시퀀스](sequence-diagrams/07-reports/README.md) · `allowedDependencies = {common}` · **1차 MVP 이후**

콘텐츠(게시글·댓글·채팅 메시지)에 대한 신고를 접수·보존하고, 신고 사유 카탈로그를 단일 출처로 제공한다. 신고는 접수 즉시 확정되는 불변 기록이며 동일 신고자의 동일 대상 중복 접수를 막는다.

**`Report`** — 한 건의 콘텐츠 신고 접수 기록 애그리거트 루트(불변). 식별자 `id`, 비즈니스 키 `(reporterId, targetType, targetId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `reporterId` | 식별자 | 신고자 → `User` 식별자 참조. 응답 비노출 |
| `target` | VO `ReportTarget` | 신고 대상(유형 + 대상 식별자)을 묶은 다형 참조 |
| `reason` | enum `ReportReason` | 신고 사유 |
| `detail` | VO `ReportDetail` | 신고 상세(선택, 자유 텍스트). 응답 비노출 |
| `status` | enum `ReportStatus` | 처리 상태(접수 시 `RECEIVED` 고정) |
| `createdAt` | Instant | 접수 시각(UTC) |

**불변식:** 접수 시 `status`는 항상 `RECEIVED` 고정·상태 전이 없음(불변 기록); `(reporterId, targetType, targetId)`는 유일 → 동일 신고자의 동일 대상 재접수는 거부(`409 REPORT_ALREADY_EXISTS`), 동시 접수도 1건만(멱등); 신고 대상은 접수 시점에 실재해야 함(`404 REPORT_TARGET_NOT_FOUND`); 자기 신고 차단(`422 REPORT_SELF_TARGET`); `targetType=MESSAGE`면 신고자는 해당 채팅방 참여자여야 함(`403 FORBIDDEN`); `reason`은 정의된 카탈로그 중 하나; `detail`은 선택·최대 길이(예: 500자) 초과 불가; `reporterId`·`detail`은 응답 비노출(프라이버시).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `ReportTarget` | `targetType` | enum `ReportTargetType` | 신고 대상 유형 |
| | `targetId` | 식별자 | → 대상 콘텐츠 식별자 참조. `POST`/`COMMENT`면 `community`, `MESSAGE`면 `chat`. 동일 `targetId`라도 `targetType`이 다르면 다른 대상 |
| `ReportDetail` | `text` | String | 신고에 덧붙이는 선택적 자유 텍스트(비어 있을 수 있음, 최대 길이 초과 불가). `reason=ETC`일 때 입력 권장 |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ReportTargetType` | `POST` | 커뮤니티 게시글 |
| | `COMMENT` | 커뮤니티 댓글 |
| | `MESSAGE` | 채팅 메시지 |
| `ReportReason` | `SPAM` | 스팸/광고 |
| | `ABUSE` | 욕설/괴롭힘 |
| | `SEXUAL_CONTENT` | 성적 콘텐츠 |
| | `EXTERNAL_CONTACT` | 외부 연락처 유도 |
| | `FALSE_INFO` | 허위 정보 |
| | `ETC` | 기타 |
| `ReportStatus` | `RECEIVED` | 접수됨(단일 값, 상태 전이 없음) |

**협력 / 이벤트:** 타 애그리거트는 식별자(`reporterId`·`targetId`)로만 참조한다(ADR-0002). 대상 존재 검증·작성자 동일성(자기 신고 판별)·채팅방 참여 권한 평가는 대상 보유 모듈의 공개 쿼리에 위임한다 — `POST`/`COMMENT`는 `community`, `MESSAGE`는 `chat`. 신고 사유 카탈로그(`ReportReason`)는 고정·소규모 정적 메타로 외부에 노출되어 클라이언트가 사유 선택지를 서버와 동일하게 구성하는 단일 출처가 된다.

---

## 관련 문서

- [system-overview](system-overview.md) · [sequence-diagrams](sequence-diagrams/README.md) — 구성/흐름 뷰
- [ADR-0001](../adr/0001-bounded-context-module-decomposition.md)(모듈 분해) · [ADR-0002](../adr/0002-inter-module-communication-via-events.md)(이벤트·식별자 참조)
- [database-design](../database/database-design.md)(영속 매핑 — 저장소·물리 스키마) · [code-style §3](../convention/code-style.md)(계층·포트/어댑터)
- [api/specs](../api/specs/README.md) · [api-design-guide](../api/api-design-guide.md) · [error-response-guide](../api/error-response-guide.md) · [requirements/user-stories](../requirements/user-stories.md)
