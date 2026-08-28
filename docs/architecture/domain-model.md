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
| [`auth`](#1-auth--인증온보딩) | `SocialAccount`, `RefreshToken`, `EmailVerification`, `PhoneVerification`, `SignupPhoneVerification`, `SignupEmailVerification` (+ `BusinessVerification` — 무상태 검증, 영속 없음) | `SocialIdentity`, `TokenHash` | ✅ |
| [`user`](#2-user--회원-프로필계정-lifecycle) | `User`, `UserBlock` | `FullName`, `Consent` | ✅ |
| [`listing`](#3-listing--매물-등록탐색찜) | `Listing`, `Favorite`, `RecentListing` | `GeoPoint`, `Address`, `Contact`, `RoomOffer`, `MatchedPlace` | ✅ |
| [`diagnosis`](#4-diagnosis--6단계-맞춤-진단) | `Diagnosis`, `DiagnosisFlowSession`(v2) | `DiagnosisCriteria`, `RecommendationSuggestions` | ✅ |
| [`booking`](#5-booking--매물-신청예약) | `Booking`, `BookingReport` | — (`GreetingMessage`는 후속·이연) | ✅ |
| [`chat`](#6-chat--인앱-채팅) | `ChatRoom`(+`Message`·`ReadCursor`) | `BookingCard`, `ListingCard`, `ListingSnapshot` | 후속·이연 |
| [`community`](#7-community--커뮤니티) | `Post`(+`Comment`·`PostLike`) | `Hashtag` | 이후 |
| [`gamification`](#8-gamification--퀴즈) | `Quiz` | `QuizChoice` | 이후 |
| [`report`](#9-report--신고-처리) | `Report` | `ReportTarget`, `ReportDetail` | 이후 |
| [`lifetip`](#10-lifetip--생활-팁주제별-생활-정보) | `LifeTipTopic`, `LifeTip` | — | 이후 |

> `common`은 공유 커널(애그리거트 없음): 응답 래퍼·예외 표준만 제공.

---

## 1. `auth` — 인증·온보딩

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/auth`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

소셜 로그인(Apple/Google) 자격을 회원 식별자로 매핑하고(소셜 로그인 시점에 제공자 토큰으로 확정한 `email`과 요청으로 받은 `name`을 `user`의 `User`에 즉시 채운다 — 온보딩까지 미루지 않음), 서버 자체 세션 토큰(불투명 refresh)의 발급·회전·재사용 탐지·무효화와 **ACTIVE(온보딩 완료) 사용자 전용 이메일 인증(인증번호 발송·검증)**, 그리고 **임대인 온보딩 중 연락처(휴대폰) SMS 인증**을 책임지는 인증 경계다. 사업자등록번호 검증(`BusinessVerification`)은 **온보딩과 분리된 ACTIVE 임대인 전용 무상태(stateless) 검증**으로, 온보딩 게이트가 아니라 임대인이 필요할 때 정식 access 토큰으로 직접 호출한다(검증 결과를 서버에 저장하지 않고 응답 본문으로만 회신 — 아래 `BusinessVerification` 참조). **매물 등록 시점에는 이 검증을 호출하지 않는다** — 등록 API(`POST /api/v2/listings`)는 사업자등록번호를 **형식만 검증해 저장**하고, 진위는 **관리자가 승인 심사에서 수동으로 확인**한다. 회원 프로필·상태(`PENDING`/`TERMS_AGREED`/`ACTIVE`/`WITHDRAWN`)는 `user` 모듈 소관이므로 여기선 회원 식별자(`userId`)로만 참조한다. 온보딩은 역할(`userType`: 세입자 `TENANT` / 임대인 `LANDLORD`)에 따라 분기하며, **소셜 로그인·약관 동의까지는 두 역할 공통**이고 이후 임대인만 연락처 인증(`PhoneVerification`)을 추가로 거친다 — **세입자 온보딩은 약관 동의만으로 완료**(이메일 인증은 온보딩 게이트가 아니라 ACTIVE 전용 별도 API로 분리 — 온보딩 게이트로서의 `AUTH_EMAIL_NOT_VERIFIED`는 제거됐고, 같은 코드가 **임대인 웹 가입 게이트로 재도입**됐다(US-1-18)), **임대인 온보딩은 약관 동의 + 연락처 인증만으로 완료**(사업자번호 게이트 없음). 온보딩 제출 엔드포인트(세입자 `POST /auth/onboarding`, 임대인 `POST /auth/landlord/onboarding`)에서 `userType`이 확정된다.

**`SocialAccount`** — 소셜 제공자 자격을 한 명의 회원에 묶는 자격 매핑 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(provider, providerUserId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `provider` | enum `Provider` | 소셜 로그인 제공자(`APPLE`/`GOOGLE`) |
| `providerUserId` | String | 제공자가 발급한 사용자 고유 식별자(검증된 토큰의 `sub` 클레임 — Google은 `idToken`, Apple은 코드 교환으로 받은 `id_token`) |
| `email` | String | 제공자가 제공한 이메일(검증된 토큰의 `email` 클레임에서 추출) — **provider 스냅샷**으로 로그인마다 최신값 upsert. 소셜 로그인 요청의 `email`을 대조·확정하는 진본이자 심사계정 매칭 등에 쓴다. 민감정보 |
| `name` | String | 제공자가 준 표시 이름(소셜 로그인 요청의 `name`, 검증 안 함) — **provider 스냅샷**. 로그인마다 upsert(값 있을 때만 — Apple 재로그인 `null`은 기존값 보존). `User.name`(사용자 값)과 **별개로 이중 관리**하며(사용자 수정은 `User`만), 현재 소비처는 없고 향후 provider 변경 감지·이메일 변경 기능 대비 |
| `userId` | 식별자 | 이 자격이 연결된 회원 → `User` 식별자 참조 |
| `linkedAt` | Instant | 자격 연결(최초 발견) 시각(UTC) |
| `appleRefreshToken` | String | Apple 전용 — 코드 교환(`/auth/token`)으로 받은 refresh token. 탈퇴 시 `/auth/revoke` 폐기에 사용([ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)). Google은 `null`. 1급 민감정보 — 로그·응답 비노출, 평문 저장(저장소 암호화 의존, [ADR-0015](../adr/0015-sensitive-column-encryption.md)) |

**불변식:** `(provider, providerUserId)` 조합은 전역 유니크 — 동일 제공자 자격은 정확히 한 회원에만 매핑; 소셜 로그인 시 `(provider, providerUserId)`로 조회해 존재하면 기존 회원 로그인, 없으면 신규 자격 생성 + `user` 모듈에 새 `PENDING` 회원 생성을 요청하며 이때 소셜 로그인 요청의 `name`과 토큰으로 확정한 `email`을 즉시 채워 그 식별자로 연결(기존/신규 분기의 단일 진실원 — 이름·이메일을 온보딩까지 미루지 않는다); `providerUserId`는 검증 통과 토큰(서명·`iss`·`aud`·`exp` — Google `idToken` / Apple은 코드 교환으로 받은 `id_token`)에서만 채워짐 — 검증 실패 토큰으로는 자격을 만들지 않음(`401 AUTH_INVALID_SOCIAL_TOKEN`); **`email`은 검증된 토큰의 `email` 클레임을 진본으로 확정하고 소셜 로그인 요청의 `email`이 토큰 값과 일치해야 함** — 불일치는 `422 AUTH_EMAIL_MISMATCH`, 토큰·요청 어느 쪽에도 `email`이 없으면 `422 AUTH_EMAIL_REQUIRED`로 거부(반면 `name`은 검증 대상이 아니라 요청 값을 신뢰 — Apple은 이름을 최초 1회만 반환해 토큰에서 얻지 못한다); **`SocialAccount.email`·`name`은 provider 스냅샷으로 매 로그인 upsert**한다 — email은 검증 토큰 값, name은 요청 값(값 있을 때만 갱신, Apple 재로그인 `null`은 기존값 보존 — `appleRefreshToken`과 동일 방어 가드) — 이는 `User.name`·`User.email`(사용자 값, 최초 로그인에만 세팅·이후 사용자 편집)과 **별개로 이중 관리**된다(사용자 수정은 `User`만 건드리고 provider 최신값은 `SocialAccount` 스냅샷에 반영); 한 번 연결된 `userId`는 재할당 불가(자격 소유권 고정); `appleRefreshToken`은 코드 교환 응답에 refresh token이 있을 때만 갱신(없으면 기존 값 보존 — 보통 매번 반환되나 비어 올 경우를 대비한 방어 가드, [ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)); `email`·`appleRefreshToken`은 응답·로그 비노출.

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

**`EmailVerification`** — **ACTIVE(온보딩 완료) 사용자**가 이메일 소유를 인증번호로 확인하는 단명(ephemeral) 인증 시도 애그리거트 루트. **온보딩 단계 전용이 아니라 정식(ACTIVE) 토큰을 가진 사용자 전용**으로 반전됐다(#192) — 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로는 호출할 수 없다. 이번 범위에서는 **접근 제한만** 다루고 `VERIFIED` 전이가 `User.email`을 바꾸지 않는다(실제 이메일 변경 반영은 후속 이슈). 비즈니스 키 `userId`(사용자 단위로 1건). 영속 무관이나 단명 상태라 Redis로 물리화한다([database-design](../database/database-design.md) §4-1).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | 식별자 | 인증 대상(ACTIVE) 회원 → `User` 식별자 참조(시도 단위) |
| `email` | String | 인증 대상 이메일(사용자가 입력) — 민감정보 |
| `codeHash` | String | 발송한 인증번호의 단방향 해시(원문 미보관) |
| `status` | enum `EmailVerificationStatus` | 인증 시도 상태 |
| `attempts` | int | 검증 실패 누적 횟수(상한 초과 시 거부) |
| `expiresAt` | Instant | 인증번호 만료 시각(UTC) |
| `issuedAt` | Instant | 인증번호 발송 시각(UTC) |

**불변식:** 발송·검증은 **정식(ACTIVE) 토큰(`hasRole("USER")`)** 을 가진 본인에 한함 — 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`(#192로 온보딩 단계 전용 → ACTIVE 전용으로 반전); 사용자당 활성 시도 1건(재발송은 기존 시도 대체); 인증 챌린지는 **메일 발송이 성공한 뒤에만 확정**하고(동기 발송), provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 거부(재시도 유도); 검증 시 챌린지가 없으면(미발송·만료·이미 검증으로 키 부재) 올릴 `attempts` 레코드가 없어 즉시 `422 AUTH_EMAIL_VERIFICATION_FAILED`(재요청 유도); 챌린지가 있고 `attempts`가 상한 미만일 때 입력 인증번호 해시가 `codeHash`와 일치하면 `VERIFIED`로 전이하고, 불일치면 `attempts`를 올려 상한 초과 시 `429 TOO_MANY_REQUESTS`·아니면 `422 AUTH_EMAIL_VERIFICATION_FAILED`(과도 재발송도 `429`); **이번 범위에서는 접근 제한만 다루고 `VERIFIED` 전이가 `User.email`을 바꾸지 않는다**(실제 이메일 변경 반영은 후속 이슈 — 온보딩 완료 게이트로 쓰이던 `AUTH_EMAIL_NOT_VERIFIED`는 제거됐고, 같은 코드가 임대인 웹 가입 게이트로 재도입됐다(US-1-18)); 인증번호 원문은 보관·로그하지 않음(해시만), `email`은 응답·로그 마스킹; 만료/완료 시도는 TTL로 자동 소멸. (확인 필요: 인증번호 길이·만료 시간·검증 시도 상한·재발송 레이트리밋)

**`PhoneVerification`** — **임대인 온보딩** 중 입력한 **연락처(휴대폰)** 의 소유를 SMS 인증번호로 확인하는 단명(ephemeral) 인증 시도 애그리거트 루트. 이메일 인증(`EmailVerification`)과 **인증번호 발송·검증 메커니즘이 대칭**이며 임대인(`userType=LANDLORD` 분기) 전용이다 — **임대인 온보딩의 본인 확인 게이트**다(세입자 온보딩은 별도 인증 게이트 없이 약관 동의만으로 완료하고, 임대인만 이 연락처 인증을 거친다; [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). 비즈니스 키 `userId`(온보딩 중 회원 단위로 1건). 영속 무관이나 단명 상태라 Redis로 물리화한다([database-design](../database/database-design.md) §4-1).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | 식별자 | 인증 대상(임대인 온보딩 중) 회원 → `User` 식별자 참조(시도 단위) |
| `phoneNumber` | String | 인증 대상 연락처(임대인이 온보딩에서 입력) — 민감정보(마스킹) |
| `codeHash` | String | 발송한 인증번호의 단방향 해시(원문 미보관) |
| `status` | enum `PhoneVerificationStatus` | 인증 시도 상태 |
| `attempts` | int | 검증 실패 누적 횟수(상한 초과 시 거부) |
| `expiresAt` | Instant | 인증번호 만료 시각(UTC) |
| `issuedAt` | Instant | 인증번호 발송 시각(UTC) |

**불변식:** 발송은 온보딩 토큰(`PENDING`/`TERMS_AGREED` 모두 `onboardingCompleted=false`)을 가진 본인에 한함(임대인 분기) — 사용자당 활성 시도 1건(재발송은 기존 시도 대체); 인증 챌린지는 **SMS 발송이 성공한 뒤에만 확정**하고(동기 발송), provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 거부(재시도 유도 — 이메일 발송 실패와 대칭); 검증 시 챌린지가 없으면(미발송·만료·이미 검증으로 키 부재) 올릴 `attempts` 레코드가 없어 즉시 `422 AUTH_PHONE_VERIFICATION_FAILED`(재요청 유도); 챌린지가 있고 `attempts`가 상한 미만일 때 입력 인증번호 해시가 `codeHash`와 일치하면 `VERIFIED`로 전이하고, 불일치면 `attempts`를 올려 상한 초과 시 `429 TOO_MANY_REQUESTS`·아니면 `422 AUTH_PHONE_VERIFICATION_FAILED`(과도 재발송도 `429`); 임대인 온보딩 제출(`POST /auth/landlord/onboarding`)은 제출 `phoneNumber`가 `VERIFIED`된 번호와 일치해야 진행(미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`); 인증번호 원문은 보관·로그하지 않음(해시만), `phoneNumber`는 응답·로그 마스킹(예 `010-****-5678`); 만료/완료 시도는 TTL로 자동 소멸. **인증번호 정책은 이메일 인증(`EmailVerification`)과 통일** — 인증번호 6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 5회·재발송 간격 60초이며, SMS provider는 인프라 어댑터로 격리한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). 프로필 연락처 변경(US-1-5)도 같은 `PhoneVerification` 재인증을 거친다(미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`).

**`SignupPhoneVerification` · `SignupEmailVerification`** — 임대인 웹 회원가입(US-1-11) **제출 전에** 수행하는 비로그인 소유 인증의 단명 애그리거트 루트 둘. 위 `PhoneVerification`·`EmailVerification`과 **정책(6자리·코드 TTL 5분·검증 마커 30분·시도 5회·재발송 60초)·해시·발송 포트를 그대로 공유**하지만 **비즈니스 키가 다르다** — 계정이 없는 단계라 `userId`가 존재하지 않으므로 **정규화한 휴대폰 번호**(숫자만)와 **정규화한 이메일**(trim + 소문자)이 각각 곧 식별자다. 그래서 대상 값을 속성으로 들지 않는다(키에 있는 사실을 값에 또 넣으면 어긋날 여지만 생긴다). 영속 무관이나 단명 상태라 Redis로 물리화한다([database-design](../database/database-design.md) §4-1 A-5·A-8).

**불변식:** 두 경로 모두 **permitAll**이라 토큰으로 주체를 묶을 수 없어 **대상 값 단위 + 호출자 IP 단위 이중 레이트리밋**이 유일한 남용 방어다(시간당 한도는 채널별로 **예산을 나눠** 갖는다 — 공유하면 한쪽 화면의 남용이 다른 쪽 정상 흐름을 막는다); 챌린지는 **발송 성공 뒤에만 확정**하고 실패는 `502 UPSTREAM_ERROR`(챌린지 미저장); **챌린지 부재·불일치·만료·시도 상한 초과가 모두 `422`** 한 코드다(각각 `AUTH_PHONE_VERIFICATION_FAILED`·`AUTH_EMAIL_VERIFICATION_FAILED`) — 시도 초과를 `429`로 가르는 로그인 트랙과 달리, 비로그인 경로에서는 응답의 차이 자체가 챌린지 존재·시도 잔량을 알려 주는 신호다; 검증 성공 시 남기는 마커는 값이 상수라 **용도가 값에 없고 키스페이스가 곧 용도**이므로 서로의 게이트를 통과시키지 못한다; 가입 제출은 **두 마커를 모두** 요구하고(서로 순서 무관, 미인증은 각각 `422 AUTH_PHONE_NOT_VERIFIED`·`422 AUTH_EMAIL_NOT_VERIFIED`) 성공 시 **커밋 이후에** 둘을 함께 소비한다. **한 가지가 갈린다** — 이메일 쪽은 발송 단계에서 **로그인 ID 중복을 판정해 이미 가입된 주소면 발송하지 않고 `409 AUTH_EMAIL_ALREADY_REGISTERED`** 다(감추면 남의 메일함으로 인증번호가 실제 발송된다). 그 대가로 가입 여부 열거를 수용하며, 번호 쪽은 뒤에 이름 대조 게이트(US-1-16)가 남아 있어 종전대로 계정 존재를 노출하지 않는다. 앱 심사용 고정 인증번호 우회는 `userId`+소셜 계정을 전제로 하므로 **두 경로 모두에 적용되지 않는다** — 이메일 발급 포트를 `EmailVerification` 쪽과 **공유하지 않고 따로 두는 이유**가 이것이다(공유하면 dev·local에서 우회 래퍼가 존재하지 않는 `userId`로 판정하게 된다).

**`BusinessVerification`** — 임대인이 입력한 **사업자등록번호**의 진위·상태(정상 영업)를 외부 사업자등록정보 검증 API(국세청 사업자등록정보 기반, 구체 provider는 [ADR-0033](../adr/0033-business-registry-verification.md))로 확인하는 **무상태(stateless) 검증**이다. **온보딩과 분리된 ACTIVE 임대인 전용 검증**으로, 온보딩을 마친(`ACTIVE`·`userType=LANDLORD`) 임대인이 필요할 때 정식 access 토큰(ROLE_USER)으로 `POST /api/v1/auth/business/verify`를 직접 호출한다 — 온보딩 게이트가 아니다. **매물 등록 시점에는 이 검증을 호출하지 않는다** — 등록 API(`POST /api/v2/listings`)는 사업자등록번호를 **형식만 검증해 저장**하고, 진위는 **관리자가 승인 심사에서 수동으로 확인**한다([ADR-0033](../adr/0033-business-registry-verification.md)). **검증 결과는 서버에 저장하지 않고(Redis 마커·해시 컬럼 없음) 응답 본문에만 담는다** — 따라서 애그리거트로 영속되지 않으며(단명 상태 없음), 요청 `{ businessRegistrationNumber(숫자 10자리) }` → 응답 `{ businessRegistrationNumber(마스킹), verified:true }`의 요청·응답 계약만 갖는다. 연락처 인증(`PhoneVerification`)의 "온보딩 중 인증 → 마커 → 온보딩 대조" 패턴(이메일 인증 `EmailVerification`은 #192로 온보딩에서 분리돼 ACTIVE 전용이 됐다)과 달리, 검증 결과를 저장·대조하는 마커가 없다.

**인가·판정:**

| 항목 | 규칙 |
| --- | --- |
| 인가 | 정식 access 토큰(ACTIVE, ROLE_USER) 필수. 온보딩 토큰(PENDING/TERMS_AGREED, ROLE_ONBOARDING)으로 호출 시 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) ACTIVE 사용자면 `403 FORBIDDEN` |
| 판정 | 사업자등록정보 검증 API 조회로 정상(계속) 사업자면 `verified:true`; 미등록·휴폐업·진위 실패는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`, 외부 장애·타임아웃은 `502 UPSTREAM_ERROR` |
| 저장 | 없음 — 검증 결과를 서버에 남기지 않는다(무상태). `user.businessRegistrationNumberHash` 컬럼에도 쓰지 않으며, **매물 등록 시점에도 그 컬럼을 채우지 않는다** — 사업자등록번호 **원문**은 `listing`의 매물 문서(`Listing.businessRegistrationNumber`)가 보유한다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)) |

**불변식:** 검증은 정식 access 토큰(ACTIVE·ROLE_USER)을 가진 임대인 본인에 한함 — 온보딩 토큰은 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌 ACTIVE 사용자는 `403 FORBIDDEN`; 사업자등록정보 검증 API **동기 호출**로 국세청 사업자등록정보 진위·상태를 확인해 **정상(계속 사업자)만** `verified:true`로 회신하고, 미등록·휴업·폐업·진위 불일치는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`, 외부 장애·타임아웃은 `502 UPSTREAM_ERROR`로 거부; **검증 결과를 서버에 저장하지 않는다**(Redis 마커 없음, `businessRegistrationNumberHash` 컬럼에도 쓰지 않음) — 결과는 응답 본문에만 담기며 온보딩 제출과 대조하지 않는다; **`auth`는** 사업자번호 원문을 보관·로그하지 않고 응답·로그는 마스킹한다(예 `****567890`, 확인 필요) — 원문 보관은 `listing`의 매물 문서 한정으로 허용된다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)의 [ADR-0033](../adr/0033-business-registry-verification.md) 개정, `users` 테이블·온보딩에는 미채택). (확인 필요: 검증 서비스 회신 상호·대표자 활용 여부, 검증 레이트리밋 임계값)

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
| | `VERIFIED` | 인증번호 일치로 이메일 소유 확인 완료(ACTIVE 사용자 전용 — 실제 이메일 변경 반영은 후속 이슈) |
| `PhoneVerificationStatus` | `PENDING` | SMS 인증번호 발송 후 미검증(만료 전·시도 가능) |
| | `VERIFIED` | 인증번호 일치로 연락처(휴대폰) 소유 확인 완료(임대인 온보딩 제출에 사용 가능) |

> `BusinessVerification`은 무상태 검증이라 서버에 남는 상태 enum이 없다 — 판정 결과는 응답 본문 `verified:true`(또는 `422`/`502`)로만 표현한다.

**협력 / 이벤트:** 회원 프로필·상태는 `user`가 소유하고 `auth`는 `userId`로만 참조한다(ADR-0002). 신규 분기의 `PENDING` 회원 생성(이때 소셜 로그인 요청의 `name`과 토큰으로 확정한 `email`을 즉시 채운다 — 온보딩까지 미루지 않음), 약관 동의 시 `TERMS_AGREED` 전이, 온보딩 시 `ACTIVE` 전이, 탈퇴 시 `WITHDRAWN` 전이는 `user`의 공개 명령/쿼리로 협력하고 결과 식별자를 `SocialAccount.userId`로 보유한다. 세입자 온보딩 입력의 `gender`·`visaType`·`occupation`은 `user` 소유 enum이라 타입을 공유하지 않고 원시 값으로 전달한다. `userType`(세입자/임대인 역할)은 온보딩 제출 엔드포인트로 분기·확정돼 `user`로 전달된다(이후 불변). 온보딩 완료 명령 전에 `auth`가 **역할별 검증 게이트**를 통과시킨다 — **세입자는 약관 동의만으로 완료**하고(이메일 인증은 더 이상 온보딩 게이트가 아니라 ACTIVE 전용 별도 API로 분리됐다 — 온보딩 게이트로서의 `AUTH_EMAIL_NOT_VERIFIED`는 제거됐으나, 같은 코드가 **임대인 웹 가입 게이트로 재도입**됐다(US-1-18)), **임대인은 약관 동의 → 연락처 인증**(`PhoneVerification`의 `VERIFIED`, 미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`)을 확인한다 — **임대인 온보딩에는 사업자번호 게이트가 없다**(약관·연락처만으로 완료). 검증 상태는 `auth` 소유이며, `name`·`email`은 **소셜 로그인 시점에 확정돼 `user`가 보유하고**(세입자·임대인 공통 — 온보딩에서 재수집하지 않음), 임대인 `phoneNumber`는 온보딩 연락처 인증으로 확정돼 `user`가 보유한다(임대인 `businessRegistrationNumber`는 온보딩에서 수집하지 않아 온보딩 완료 시 `null`이다). 인증번호 메일 발송은 아웃바운드 포트 `VerificationEmailSender`(application)로 추상화하고 인프라 어댑터(SMTP)가 구현한다 — **동기 발송**이라 발송 성공 시에만 챌린지를 확정하고, 발송 실패(provider 장애·타임아웃)는 `502 UPSTREAM_ERROR`로 응답한다(메일 템플릿·다국어는 확인 필요). 연락처 SMS 발송은 아웃바운드 포트 `VerificationSmsSender`(application)로 추상화하고 인프라 어댑터(SMS API — 구체 provider는 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md))가 구현한다 — 이메일과 동일하게 **동기 발송**(발송 성공 시에만 챌린지 확정, 실패 시 `502 UPSTREAM_ERROR`)이다. 사업자등록번호 검증은 **온보딩과 분리된 무상태 API**(`POST /api/v1/auth/business/verify`)로, ACTIVE 임대인이 정식 access 토큰으로 호출하며 아웃바운드 포트 `BusinessRegistryVerifier`(application)로 추상화하고 인프라 어댑터(사업자등록정보 검증 API — 국세청 사업자등록정보 진위·상태 기반, 구체 provider는 [ADR-0033](../adr/0033-business-registry-verification.md))가 구현한다 — **동기 검증**이라 정상(계속) 사업자만 `verified:true`로 회신하고, 미등록·휴폐업·진위 불일치는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`, 외부 장애·타임아웃은 `502 UPSTREAM_ERROR`로 응답하며 **검증 결과는 서버에 저장하지 않는다**(응답 본문에만 회신). `user`의 탈퇴 이벤트(`UserWithdrawnEvent`)를 구독해 해당 `userId`의 refresh 토큰을 일괄 무효화한다.

---

## 2. `user` — 회원 프로필·계정 lifecycle

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/users/me`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

회원 프로필과 계정 생애주기(가입·약관 동의·온보딩·수정·탈퇴)를 소유한다. 사용자는 소셜 검증만 끝난 `PENDING` → 약관 동의 완료 `TERMS_AGREED` → 온보딩 완료 `ACTIVE` → 탈퇴 `WITHDRAWN`의 단방향 상태 모델을 가진다(약관 동의와 온보딩은 분리된 단계로 각각 상태를 전이한다). 회원은 역할(`userType`)에 따라 **세입자(`TENANT`, 외국인 거주 탐색자)** 와 **임대인(`LANDLORD`, 매물 등록자)** 으로 나뉘며 한 `User` 애그리거트로 통합 관리한다(별도 모듈 아님). 소셜 로그인·약관 동의까지는 공통이고 이후 온보딩에서 분기한다 — 세입자는 `gender`·`birthDate`·`country`·`visaType`를 온보딩에서 수집하고(단일 `name`·`email`은 소셜 로그인 시점에 확정돼 온보딩에서 재수집하지 않는다; `occupation`은 `lang`처럼 **선택** 수집 — 매물 추천·탐색에서 쓰지 않아 필수에서 제외(#187)), **임대인은 `phoneNumber`(SMS 인증 완료)·`birthDate` 두 필드로 온보딩을 완료**하며(단일 `name`은 세입자와 동일하게 소셜 로그인 시점에 확정돼 온보딩에서 재수집하지 않는다; 약관 동의 + 연락처 인증만 — 사업자번호는 온보딩에서 수집하지 않음) **`gender`·`occupation`·`visaType`을 미수집**한다(단일 `name`·`email`은 세입자와 동일하게 소셜 로그인 provider 값으로 보유 — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집"을 개정(#192): email 수집 폼이 아니라 소셜 로그인 provider email 보유; 생년월일은 세입자와 동일하게 수집; `country`=`KR`·`lang`=`ko`는 임대인에게 입력받지 않고 **서버가 온보딩 시 고정으로 심는다** — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 country 미수집" 결정을 개정(#141))(임대인 온보딩의 본인 확인은 `phoneNumber` SMS 인증이고, 세입자 `email`은 소셜 로그인으로 확정돼 온보딩 인증 게이트가 없다 — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). 임대인 사업자등록번호는 온보딩에서 수집하지 않고 **매물 등록 시점에도 `users` 컬럼을 채우지 않는다**(항상 `null` — 원문은 `listing`의 매물 문서가 보유한다, [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 검증은 별도 무상태 API로 다룬다(§1 `BusinessVerification`). `userType`은 온보딩 제출 시 확정되고 이후 불변이다.

**`User`** — 회원의 프로필·동의·계정 생애주기를 일관성 경계로 묶는 애그리거트 루트. 식별자 `id`(소셜 자격→회원 매핑·세션 토큰·이메일 인증은 `auth` 소관). [값 객체: `FullName`, `Consent`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(타 모듈은 이 값으로만 회원 참조) |
| `userType` | enum `UserType` | 회원 역할(`TENANT` 세입자 / `LANDLORD` 임대인). 온보딩 제출 엔드포인트로 분기·확정, 이후 불변(확인 필요: NOT NULL DEFAULT `TENANT`) |
| `name` | VO `FullName` | 회원 이름 — 세입자·임대인 공통 **단일 `name`**(역할 무관, 항상 존재). **소셜 로그인 시점에 확정**돼(요청 `name`을 신뢰 — Apple은 최초 1회만 이름을 준다) 온보딩에서 재수집하지 않으며, 수정은 `PATCH /users/me`. API는 단일 `name` 필드로 주고받고 저장도 단일 `name` 컬럼(`VARCHAR(200)` — 구 `first_name`+`last_name`을 통합, 마이그레이션 V19; [database-design](../database/database-design.md) §4-2) |
| `phoneNumber` | String | 임대인 **개인** 연락처(임대인 온보딩 필수, 세입자 미수집). 온보딩 전 `auth` SMS 인증(`PhoneVerification`의 `VERIFIED`)을 거친 값 — 응답·로그 마스킹(예 `010-****-5678`)이며 매물 응답에 노출하지 않는다. 매물별 담당 연락처(`Listing.contact`)는 **별개 값**이라 마스킹 대상이 아니다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 형식 확인 필요(예 VARCHAR(20)) |
| `businessRegistrationNumber` | String, nullable | 임대인 사업자등록번호(세입자 미수집). **온보딩에서 수집하지 않고 매물 등록 시점에도 채우지 않아 항상 `null`** — 이 컬럼(`business_registration_number_hash`)을 채우는 코드 경로가 없다. 등록 확인용 원문은 `listing`의 매물 문서(`Listing.businessRegistrationNumber`)가 보유한다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 값이 생긴다면 원문 비저장·해시로만 영속하고 응답·로그 마스킹(예 `****567890`)(확인 필요) |
| `nickname` | String | 시스템이 배정하는 표시용 닉네임(`형용사 + 사물`). 전역 유니크, 사용자 입력 아님 — 온보딩 완료 시 자동 배정(세입자·임대인 공통). 메인 화면 비노출(프로필·`더보기 탭`에서 확인) |
| `gender` | enum `Gender` | 성별. 세입자 온보딩 필수(임대인 미수집) |
| `birthDate` | LocalDate | 생년월일(과거 날짜만). 세입자·임대인 온보딩 필수 |
| `country` | String | 국적(ISO 3166-1 alpha-2 코드, 예 `VN`). 세입자 온보딩 필수 — 클라이언트는 국가만 전송, 표시명·국기(이미지 URL)는 `countries` 참조로 확보. **임대인은 서버가 `KR`로 고정**(입력받지 않음 — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 country 미수집"을 개정(#141)). 표시 언어는 별도 `lang` 속성으로 국적과 독립이다(국가가 표시 언어를 정하지 않는다) |
| `lang` | Language enum, nullable | 사용자가 선택한 표시 언어(ISO 639-1 **소문자** 코드 `en`·`ko`·`ja` — 그 외 값은 `400 INVALID_INPUT`). 세입자는 온보딩 **선택** 입력이자 프로필 수정 대상이고, **임대인은 서버가 `ko`로 고정하며 변경 불가**(임대인 프로필 수정은 `lang`을 읽지 않는다). 미설정(`null`)은 미선택을 뜻하며 저장하지 않고 표시 시 런타임에서 `en`으로 폴백한다. 닫힌 집합이라 `Gender`·`Occupation`·`VisaType`처럼 `Language` **enum**으로 두고 DB·API엔 소문자 코드를 쓴다(`LanguageConverter`) — 신규 언어는 카탈로그 콘텐츠 시드가 선행이라 배포를 수반한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) |
| `occupation` | enum `Occupation`, nullable | 직업 유형. 세입자 온보딩 **선택** 입력이자 프로필 수정 대상(임대인 미수집) — 매물 추천·탐색에서 쓰지 않아 필수에서 제외(#187). 미설정(`null`)은 미입력을 뜻하며 저장하지 않고 프로필 응답에서 생략한다(`lang`과 동일한 `NON_NULL` 생략 — RestDocs responseFields도 optional). 값이 오면 enum 7종 목록으로 검증(밖이면 `400 INVALID_INPUT`) |
| `email` | String | 연락 이메일 — **소셜 로그인 시점에 제공자 토큰의 `email` 클레임으로 확정**(요청 `email`이 토큰 값과 일치해야 하며 provider 진본으로 채운다). 민감정보. **세입자·임대인 공통** — 소셜 로그인이 역할 미정 상태에서 provider(Google/Apple) email을 캡처·저장하므로 임대인도 이 값을 보유한다(더 이상 `NULL` 아님; [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집"을 개정(#192) — email 수집 폼이 아니라 소셜 로그인 provider 값 보유). #192가 이메일 인증을 없애 email은 인증 대상이 아닌 "미검증 연락처"이며 임대인 프로필 응답(`GET /users/me`)에도 포함된다. 사용자의 email 수정은 #192 범위 밖(후속 이슈) — 당분간 소셜 로그인 값으로 고정 |
| `visaType` | enum `VisaType` | 비자 유형 — 민감정보. 세입자 온보딩 필수(임대인 미수집) |
| `status` | enum `UserStatus` | 계정 상태(생성 시 `PENDING`) |
| `consent` | VO `Consent` | 이용약관·개인정보처리방침·마케팅 동의 3종(동의 여부·시각·약관 버전). **약관 동의 단계**(`PENDING`→`TERMS_AGREED`)에서 확정 |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 최종 수정 시각(UTC) |
| `withdrawnAt` | Instant(UTC), nullable | 탈퇴 시각(탈퇴 시 기록) |

**불변식:** 상태 전이는 `PENDING → TERMS_AGREED → ACTIVE → WITHDRAWN`만 허용(역전이·건너뛰기 금지); **약관 동의** 상태 전이는 `PENDING`에서만 일어나며(→`TERMS_AGREED`) 이때 `consent`(이용약관·개인정보처리방침·마케팅 동의 + `agreedAt` + `termsVersion`)를 확정 — 이용약관·개인정보처리방침 동의가 모두 필요(미동의 `422 AUTH_REQUIRED_AGREEMENT_MISSING`)하고 마케팅 동의는 선택(기본 미동의); 이미 `TERMS_AGREED`면 약관 재호출은 상태·동의를 바꾸지 않는 멱등 성공(`200`, 의도적 재동의 아님 — 마케팅 동의 변경은 프로필 수정으로), 이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`; **온보딩 제출**은 `TERMS_AGREED`에서만 가능하며(약관 미동의 `PENDING` 상태에서 시도하면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`) **역할별로 분기**한다 — 세입자는 `POST /auth/onboarding`으로 `gender`·`birthDate`·`country`·`visaType`와 **선택 필드** `occupation`(미전송이면 저장하지 않고(NULL) 응답에서 생략 — 매물 추천·탐색에서 쓰지 않아 필수에서 제외(#187), 값이 오면 enum 검증)·`lang`(미전송이면 저장하지 않고(NULL) 표시 시 `en`으로 폴백)을 제출하고(**단일 `name`·`email`은 소셜 로그인 시점에 확정돼 온보딩 요청 필드가 아니다** — #192), 임대인은 `POST /auth/landlord/onboarding`으로 **`phoneNumber`·`birthDate` 두 필드**를 제출하고(단일 `name`·`email`은 세입자와 동일하게 소셜 로그인 시점에 확정돼 온보딩 요청 필드가 아니다 — #192; 임대인은 `businessRegistrationNumber` 미수집; 생년월일은 세입자와 동일하게 수집; **`country`=`KR`·`lang`=`ko`는 임대인이 전송하지 않고 서버가 고정으로 심는다** — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141)), 성공 시 `ACTIVE`로 전이하면서 해당 필드를 한 번에 확정하고 `userType`(세입자 `TENANT` / 임대인 `LANDLORD`)을 확정(이후 불변)하며 `nickname`을 자동 배정한다(이미 `ACTIVE` 재요청은 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`); 온보딩 완료 게이트는 **역할별로 분기**한다 — **세입자**는 약관 동의(`TERMS_AGREED`)만으로 완료하고(이메일 인증은 더 이상 온보딩 게이트가 아니라 ACTIVE 전용 별도 API로 분리 — 온보딩 게이트로서의 `AUTH_EMAIL_NOT_VERIFIED`는 제거됐으나, 같은 코드가 **임대인 웹 가입 게이트로 재도입**됐다(US-1-18)), **임대인**은 제출 `phoneNumber`가 `auth` SMS 인증(`VERIFIED`)된 번호와 일치해야 한다(미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`; 우선순위 약관 동의 → 연락처 인증 — **사업자번호 게이트 없음**); `nickname`은 `NicknameGenerator`(도메인 서비스)가 형용사 풀·사물 풀의 active 단어에서 골라 `형용사 + 사물`로 무작위 배정하되 전역 유니크가 보장될 때까지 재조합 재시도(상한 초과 시 fallback; 사용자 입력·수정 대상 아님); 필수 약관 동의는 프로필 수정으로 철회 불가(탈퇴 경로로만); 프로필 부분 수정은 `ACTIVE`에서만, 전송 필드만 변경(미전송 ≠ 비움) — **역할별로 수정 가능 필드가 갈린다**: 세입자는 `name`·`gender`·`birthDate`(과거만)·`country`·`occupation`·`visaType`·`lang`·`marketingAgreed`를, 임대인은 `name`·`phoneNumber`·`marketingAgreed`를 수정 가능하다(**`lang` 변경 주체는 세입자뿐** — 임대인 프로필 수정은 `lang`을 읽지 않고 `ko` 고정을 유지한다; `country`와 `lang`은 독립이라 `country`만 보내도 `lang`은 바뀌지 않고 `lang`만 보내면 `country`는 그대로이며, 각 필드는 전송한 값만 저장한다 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); 세입자·임대인 `email`은 소셜 로그인 값으로 고정돼 이 경로로 수정 불가(email 수정은 #192 범위 밖·후속 이슈); `nickname`·`userType`은 공통 불변; 임대인 `businessRegistrationNumber`는 온보딩에서 수집하지 않으므로 이 프로필 수정 경로 대상이 아니다 — 매물 등록에서 받는 사업자등록번호는 `users`가 아니라 매물 문서에 저장된다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)); `phoneNumber`는 변경 시 **SMS 재인증(`PhoneVerification` VERIFIED) 필요** — 새 번호 재인증 후에만 반영(미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`), [API 스펙](../api/specs/01-auth-onboarding.md) §9); 탈퇴는 `PENDING`/`TERMS_AGREED`/`ACTIVE`에서 `WITHDRAWN`으로(이미 `WITHDRAWN` 재요청 `409 USER_ALREADY_WITHDRAWN`); `WITHDRAWN`·부재 사용자 조회·수정은 `404 USER_NOT_FOUND`; 모든 변경은 `updatedAt`을 갱신; 탈퇴(`WITHDRAWN`) 시 `withdrawnAt`을 기록하고 식별 PII(이름·생년월일·국적·표시 언어·직업·이메일·비자·닉네임)를 즉시 익명화한다(`lang`은 국적과 함께 `NULL`로 — 표시 언어도 사용자 식별정보이므로 익명화 범위에 포함, [ADR-0014](../adr/0014-withdrawal-pii-anonymization.md) · [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) — 임대인 PII(`name`·`email`·`phoneNumber`·`birthDate`) 익명화 범위는 확인 필요이며, 매물 문서에 남는 임대인 PII(`Listing.contact`·`Listing.businessRegistrationNumber`)는 이 익명화 범위 밖이라 임대인 탈퇴 기능 구현 시 함께 설계한다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)); `email`·`visaType`(및 임대인 개인 `phoneNumber`)은 민감정보로 로그·타 사용자 노출 시 마스킹하되 **매물별 담당 연락처(`Listing.contact`)는 임대인 개인 연락처와 별개 값이라 마스킹하지 않고 세입자 응답에 공개한다**(본인 `GET /users/me`는 평문 노출 — 단 임대인 프로필 응답 형태는 추후 확인 필요).

**`UserBlock`** — 한 회원이 다른 회원을 차단한 사실 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(blockerId, blockedUserId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `blockerId` | 식별자 | 차단한 회원(차단 주체) → `User` 식별자 참조(소유권·조회 스코프) |
| `blockedUserId` | 식별자 | 차단당한 회원 → `User` 식별자 참조 |
| `createdAt` | Instant | 차단 시각(UTC, 차단 목록 정렬 기준 — 응답에는 `blockedAt`으로 노출) |

**불변식:** `(blockerId, blockedUserId)`는 유일 → 중복 차단 불가(동시 차단도 1건만); `blockerId != blockedUserId` — **자기 차단은 구조적으로 성립하지 않는다**: 생성 경로가 `POST /api/v1/bookings/{bookingId}/block` 하나뿐이고 상대를 예약에서 도출하는데(`요청자 == tenantId ? landlordId : tenantId` — 클라이언트가 상대 식별자를 보내지 않는다), 예약 생성이 `userType=TENANT` 전용이고 `userType`은 온보딩 확정 후 불변이라 `tenantId != landlordId`가 항상 참이다(그래서 자기 차단 전용 검증·에러코드를 두지 않는다 — §5 `BookingReport`의 자기 신고와 같은 구조); **행의 존재가 곧 차단**이라 활성 플래그(`is_active` 등)를 두지 않는다 — 해제는 상태 전이가 아니라 **기록 제거**이고 재차단은 새 기록이라 이 애그리거트에는 상태 enum이 없다; 차단·해제는 **멱등** — 이미 차단한 상대를 다시 차단해도 신규 생성 없이 성공(`204`)이고 차단하지 않은 상대의 해제도 에러 없이 성공(`204`); 차단 목록 조회·해제는 본인(`blockerId`=요청자) 것만(`me` 스코프); 차단은 **비대칭 사실**이다 — 한 기록은 `blockerId`가 `blockedUserId`를 차단했다는 것만 말하고 역방향은 별개 기록이라, 양방향 여부가 필요한 소비 측(예: `booking`의 신규 신청 가드)은 두 방향을 함께 질의한다.

> **왜 예약 단위가 아니라 회원 단위 차단인가:** 차단의 대상은 예약이 아니라 **사람**이고, 그 근거는 매물 구조에 있다 — `Listing.landlordId`(§3)라 **한 임대인이 매물을 여러 개** 소유하고, `Listing.roomOffers`가 `List<RoomOffer>`(§3)라 **한 매물이 방 상품을 여러 개** 갖는다. 그래서 예약 #1에서 임대인 A를 차단해도 A의 **다른 방 상품**에 신청하면 새 예약이 생겨(뒤이어 새 채팅방까지) 차단이 그대로 우회된다. 즉 차단 대상이 예약이면 **상대가 방을 하나 더 가진 순간 무력해진다** — 회원 단위 차단만이 "그 사람과의 모든 예약"을 한 번에 가릴 수 있다.
>
> **보조 근거(중복 방지 반영):** 예전엔 `booking`이 중복 신청을 허용해 **같은 방으로 재신청하는 것만으로도** 예약 단위 차단이 뚫렸지만, 이제 `(tenantId, roomOfferId)` UNIQUE(`uq_bookings_tenant_room_offer`)로 **같은 방 재신청은 막힌다**(§5 — 재신청 시 `409 BOOKING_ALREADY_EXISTS`). 그래도 임대인은 방 상품·매물을 여러 개 가지므로 **다른 방으로는 여전히 새 예약을 만들어 우회할 수 있다** — 같은 방 경로가 닫혀도 위 구조적 근거는 그대로 살아남아, 예약/방 단위가 아니라 회원 단위 차단이어야 한다는 결론은 변하지 않는다.
>
> **왜 `user`가 소유하나:** 차단은 회원 대 회원 관계라 예약 없이도 다뤄야 한다 — 차단하면 그 예약이 내 목록에서 사라져 `bookingId`를 다시 얻을 수 없으므로 **해제 경로가 예약에 매여선 안 된다**(`GET`·`DELETE /api/v1/users/me/blocks`가 예약과 무관하게 존재해야 하는 이유). 그래서 회원 관계의 정본인 `user`가 소유한다. 다만 차단 **생성**만은 경로가 예약 상세(`POST /api/v1/bookings/{bookingId}/block`)라 상대를 예약에서 도출해야 해서 컨트롤러가 `booking`에 있고 `user`는 공개 명령으로 저장만 받는다 — `user`가 생성까지 소유하면 상대를 도출하려 `user → booking` 의존이 생기고 기존 `booking → user :: api`와 맞물려 **순환이 나 `ApplicationModules.verify()`가 깨진다**(§5 협력 참조). 이 배치로 `user`의 `allowedDependencies`는 `{common}` 그대로다.

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `FullName` | `name` | String | 회원 이름(세입자·임대인 공통 단일 값, 역할 무관). 빈 문자열 불가. 소셜 로그인 시점에 확정, 수정은 `PATCH /users/me` |
| `Consent` | `termsOfServiceAgreed` | boolean | 이용약관 동의 |
| | `privacyPolicyAgreed` | boolean | 개인정보처리방침 동의 |
| | `marketingAgreed` | boolean | 마케팅 수신 동의(선택, 기본 `false`) |
| | `agreedAt` | Instant | 동의 시각(UTC) |
| | `termsVersion` | String | 동의한 약관 버전 — **약관 동의 단계**에서 서버가 기록([ADR-0012](../adr/0012-terms-version-management.md)). 세분화된 마케팅 동의 항목 추가는 고도화(확인 필요) |

**닉네임 생성(도메인 서비스):** `NicknameGenerator`가 **형용사 풀·사물 풀**(reference 데이터, 각각 단어 목록)의 **active 단어**에서 무작위로 각 1개를 골라(비활성 단어 제외) `형용사 + 사물`(앞=형용사, 뒤=사물)로 조합한다. 조합값은 `nickname` 전역 유니크를 만족해야 하며, 충돌 시 재조합으로 재시도하고(재시도 상한 도달 시 fallback — 예: 숫자 접미사) 동시 생성 경합은 유니크 제약으로 최종 차단한다. 온보딩 완료 시점에 `user`가 호출한다. 두 풀은 운영 중 가변인 reference 데이터로 물리 테이블·시딩·무작위 선택 전략은 [database-design](../database/database-design.md) §4-2.

**국가 참조:** `country`는 ISO 국가 코드만 보유하고(애그리거트가 직접 품는 VO가 아님), 표시명·국기는 `CountryRepository`(도메인 포트, 구현은 infrastructure)로 `countries`(code→표시명→국기) reference 데이터를 조회해 `Country`(`code`·`name`·`flag`=국기 이미지 URL) 참조 값으로 resolve한다 — 클라이언트는 국가(코드)만 전송하고 국기는 서버가 `countries`에서 채운다(국가+국기 수집). 임대인은 서버가 `country`=`KR`을 심으므로 임대인 응답에도 국적·국기가 나온다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141)). 온보딩/수정 입력은 `CountryRepository.existsByCode`로 코드 유효성을 검증한다. `Country`는 응답 조립용 reference 값(닉네임 풀과 같은 부류)이라 애그리거트/구성 VO 카탈로그에는 오르지 않는다. 물리 테이블은 [database-design](../database/database-design.md) §4-2.

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `UserType` | `TENANT` | 세입자(외국인 거주 탐색자) — 온보딩에서 `gender`·`birthDate`·`country`·`visaType` 수집(+`occupation`(#187)·`lang` 선택) |
| | `LANDLORD` | 임대인(매물 등록자) — 온보딩에서 `phoneNumber`·`birthDate` 수집(단일 `name`·`email`은 세입자와 동일하게 소셜 로그인 시 확정·보유; 사업자등록번호는 `users`에 저장하지 않고 매물 문서가 보유; `country`=`KR`·`lang`=`ko`는 서버 고정) |
| `UserStatus` | `PENDING` | 소셜 검증만 완료, 약관 미동의·온보딩 미완료 |
| | `TERMS_AGREED` | 약관 동의 완료, 온보딩 정보 미입력 |
| | `ACTIVE` | 온보딩 완료, 정상 이용 |
| | `WITHDRAWN` | 탈퇴 |
| `Gender` | `MALE` | 남성 |
| | `FEMALE` | 여성 |
| `Occupation` | `UNDERGRADUATE_STUDENT` | 학부생 |
| | `GRADUATE_STUDENT` | 대학원생 |
| | `EXCHANGE_STUDENT` | 교환학생 |
| | `LANGUAGE_TEACHING` | 어학·교육 |
| | `MANUFACTURING_PRODUCTION` | 제조·생산 |
| | `BUSINESS_TRADE` | 사업·무역 |
| | `ETC` | 기타 |
| `VisaType`(API=상수명·DB=라벨) | `SHORT_TERM_VISIT` | 단기방문 · 저장값 `Short Term Visit(C-1~4, B)` |
| | `STUDENTS_TRAINEES` | 유학·연수 · 저장값 `Students & Trainees(D-2, D-3, D-4)` |
| | `NON_PROFESSIONAL_WORKERS` | 비전문취업 · 저장값 `Non-Professional Workers(E-8, E-9, E-10, H-2)` |
| | `WORKING_HOLIDAY_WORK_AND_VISIT` | 워킹홀리데이·방문취업 · 저장값 `Working Holiday/Work and Visit(H-1, H-2)` |
| | `OVERSEAS_KOREANS` | 재외동포 · 저장값 `Overseas Koreans(F-4)` |
| | `FAMILY_MARRIAGE_MIGRANTS` | 방문동거·거주·결혼이민 · 저장값 `Family/Marriage Migrants(F-1, F-2, F-3, F-6)` |
| | `PERMANENT_RESIDENTS` | 영주 · 저장값 `Permanent Residents(F-5)` |
| | `PROFESSIONALS` | 전문인력 · 저장값 `Professionals(C-4, D-1, D-7~10, E-1~7)` |
| | `DIPLOMATIC_OFFICIAL_AND_OTHERS` | 외교·공무·기타 · 저장값 `Diplomatic/Official & Others(A-1, A-2, G-1)` |
| | `ETC` | 기타 · 저장값 `etc` |

> `Occupation`·`VisaType` 값은 확정 분류값이다(#93, #138 개편). **`VisaType`은 API(요청·응답)에서 다른 enum과 동일하게 상수명(예: `SHORT_TERM_VISIT`)으로 주고받되, DB에는 사람이 읽는 표시용 라벨(예: `Short Term Visit(C-1~4, B)`)을 저장한다** — 영속은 `VisaTypeConverter`가 `getValue()`/`fromValue()`로 처리한다(#138). `country`는 ISO 국가 코드를 보유하고, 표시명·국기(이미지 URL)는 `countries` reference로 확보한다(국가+국기 수집 — 클라이언트는 국가만 전송).

**협력 / 이벤트:** 모든 타 모듈은 사용자를 `User` 식별자(`id`)로만 참조한다(엔티티 비공유). 소셜 자격→회원 매핑·이메일 인증·사업자번호 검증은 `auth`가 소유하며, `user`는 **회원 생성(`PENDING`)·약관 동의(`TERMS_AGREED` 전이)·온보딩 완료(`ACTIVE` 전이 + `userType` 확정)·탈퇴(`WITHDRAWN` 전이)** 를 공개 명령으로, 프로필을 공개 쿼리로 제공한다(`auth`가 소셜 로그인 분기·약관 동의·온보딩 완료에서 호출). 온보딩 완료 명령은 사용자가 `TERMS_AGREED`이고, **역할별 게이트**를 통과한 뒤에만 수행된다 — 세입자는 **약관 동의만으로** 완료하고(이메일 인증은 더 이상 온보딩 게이트가 아니라 ACTIVE 전용 별도 API로 분리 — #192), 임대인은 **약관 동의 → 연락처 인증**(연락처가 `auth`에서 `VERIFIED`)된 뒤에만 완료된다(역할은 호출 엔드포인트로 분기, `userType`으로 확정 — 임대인 온보딩에는 사업자번호 게이트가 없다). 임대인 사업자등록번호는 온보딩과 분리된 무상태 검증(§1 `BusinessVerification`)으로 다루며 온보딩 완료 시 저장되지 않는다. 탈퇴 시 도메인 이벤트(예: `UserWithdrawnEvent`)를 발행해 `auth`가 refresh 토큰을 일괄 무효화하게 한다(ADR-0002). 닉네임·국적 등 표시정보가 필요한 타 모듈(예: `community`)에는 식별자 기반 공개 쿼리를 제공한다(탈퇴 회원은 닉네임 `(탈퇴한 사용자)`·국적 비움으로 마스킹). 다국어 콘텐츠를 내려주는 모듈(`diagnosis`·`gamification`·`lifetip`·`booking`)에는 **표시 언어 공개 쿼리 `getLanguage(userId)`** 를 제공한다 — `user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`을 언어 코드 문자열 하나로 회신하므로, **소비 모듈은 폴백 규칙을 알지 못한다**(도출 규칙 변경은 `user` 안에서 끝난다; 엔티티 비공유·원시 값 전달 — [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5 · [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)). 임대인은 `lang`이 `ko` 고정이라 이 쿼리도 `ko`를 회신한다. **차단(`UserBlock`)은 `user`가 소유하고 공개 쿼리·명령으로 협력한다** — `booking`에 (1) 예약 목록·상세에서 가릴 상대를 고르는 `findBlockedUserIds(blockerId)`(차단 상대 식별자 집합), (2) 신규 예약 신청 가드의 양방향 판정 `isBlockedBetween(a, b)`를 공개 쿼리로 제공하고, 예약 상세에서의 차단 생성은 `booking`이 예약에서 도출한 상대 식별자를 받는 공개 명령으로 처리한다(식별자·원시 값만 오가고 엔티티·테이블은 공유하지 않는다 — `booking`이 차단 기록을 직접 조인하지 않고 받은 식별자 집합을 자기 질의의 술어로 쓴다, ADR-0002 Decision 5). 차단 목록 조회·해제 엔드포인트(`/api/v1/users/me/blocks`)는 `user`가 직접 제공한다. 에러 메시지 i18n은 이 쿼리와 무관하다 — `getLanguage`는 **본문 콘텐츠 번역에만** 쓰이고, 에러 메시지는 `Accept-Language`/`LocaleContextHolder` 경로를 그대로 쓴다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)).

---

## 3. `listing` — 매물 등록·탐색·찜

> [API 스펙](../api/specs/03-listings-favorites.md) · [시퀀스](sequence-diagrams/03-listings-favorites/README.md) · `allowedDependencies = {common, user :: api}`

외국인 대상 주거 매물의 **등록**(임대인)과 탐색(리스트·지도·키워드)·상세·찜·최근 본 매물을 소유한다. 좌표는 WGS84 십진수, 금액은 KRW 정수, 시각은 UTC. 매물은 **건물/주소 단위 Listing**으로 관리하고, 동일한 가격·계약기간·검색 태그를 가진 실제 방 묶음은 Listing 내부의 **방 상품(`RoomOffer`)** 으로 관리한다. 임대 방식·환불 정책·성별 정책·ARC 요구 여부처럼 매물 전체에 공통인 값은 Listing 루트가 소유하고, **가격·계약기간은 방 상품이 소유한다**. 매물 스키마는 임대인 등록 폼을 기준으로 정의한 **v4**(`schemaVersion=4`)다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)).

**등록 경로**는 임대인이 호출하는 `POST /api/v2/listings` 하나이며, **매물 v2의 첫 엔드포인트**였다. 이어서 **조회 계열 6종(목록·지도·키워드 검색·상세·찜 토글·내 찜/최근 본)도 `/api/v2`로 옮겨 이쪽이 정본**이 됐고, 같은 경로의 `/api/v1` 조회는 개정 전(v3) 응답 구조를 복원한 **`deprecated` 스텁**으로 DB에 닿지 않고 빈 결과 또는 `404 LISTING_NOT_FOUND`만 반환한다(제거 시점 미정) — 출시된 구버전 앱이 v4 구조를 받아 깨지는 대신 "매물 없음" 화면을 보고 업데이트로 유도되게 하려는 것이며, 새 데이터로 옛 모양을 조립하지 않으므로 하위 호환용 값 날조도 없다. 장소 후보 검색(`GET /api/v1/listings/places`)과 도로명 주소 검색(`GET /api/v1/listings/addresses`, 임대인 전용)만 매물 데이터를 쓰지 않아 `/api/v1`에 그대로 남는다([ADR-0040](../adr/0040-listing-query-api-v2-and-v1-sunset.md)). 공개 조회는 `SecurityConfig`에 **`GET /api/v2/listings`·`GET /api/v2/listings/*` `permitAll` 명시 매처**가 필요하다 — 없으면 `anyRequest().authenticated()`로 떨어져 비회원 매물 탐색이 `401`이 되며, 결국 같은 `/api/v2/listings` 네임스페이스가 **GET은 공개, POST(등록)는 회원 전용**으로 갈린다. 등록 인가는 **2단**이다 — 보안 필터가 `hasRole("USER")` 명시 매처로 온보딩 스코프(`ROLE_ONBOARDING`) 토큰을 걸러내고([ADR-0010](../adr/0010-jwt-authentication-filter.md)), 임대인 여부(`userType=LANDLORD`)는 토큰 클레임에 없으므로 서비스가 `user::api getUserType(userId)` 공개 쿼리로 재검사해 `403 FORBIDDEN`으로 거른다. `landlordId`는 요청 본문이 아니라 **토큰에서** 얻는다. **사진은 이 요청에 파일로 실리지 않는다** — 먼저 `POST /api/v2/listings/images`로 한 장씩 올려 받은 저장 키를 `imageKeys`(1~5개)·`roomOffers[].roomImageKeys`(방마다 2~5개)에 담는다. 요청을 파일마다 가르는 이유는 브라우저가 요청 단위로만 진행률을 주기 때문이고, 어느 사진이 어느 방 것인지는 **이 JSON 구조**가 정한다. 임시 사진은 `uploads/{landlordId}/{uuid}.{ext}`에 놓였다가 등록 확정 시 `listings/{listingId}/cover/…`·`listings/{listingId}/rooms/{roomOfferId}/…`로 복사되며, 확정 키가 식별자를 포함하므로 `listingId`·`roomOfferId`를 저장 전에 발급한다. 순서는 **키 검사 → 복사 → 저장 → 임시본 삭제**다 — 거절될 요청은 확정 위치에 흔적을 남기지 않고, 복사나 저장이 실패하면 복사본만 걷어내고 임시본은 남겨 다시 제출할 수 있게 한다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)). 등록 결과는 `201 Created` + 생성된 매물(상세 응답 구조)이며, 상태는 항상 `PENDING`으로 시작해 관리자 승인 전까지 목록·지도·상세·찜·신청 어디에도 노출되지 않는다. 사업자등록번호는 **형식만 검증해 저장**하고 진위는 **관리자가 승인 심사에서 수동으로 확인**한다 — 등록 시점에 `POST /api/v1/auth/business/verify`를 호출하지 않는다([ADR-0033](../adr/0033-business-registry-verification.md)). **주소는 `GET /api/v1/listings/addresses`로 먼저 검색해** 고른 후보의 `roadAddress`·`lat`·`lng`를 요청에 담고, 서버는 그 좌표를 `location`에 채운다(등록 시점 재지오코딩 없음 — [ADR-0042](../adr/0042-road-address-search-with-ncp-geocoding.md)). 주변 대학(`nearbyUniversityCodes`)은 그 좌표로 원장을 훑어 서버가 채운다([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)). **관리자 승인(`PENDING → PUBLISHED`)·반려(`→ REJECTED`)에 이어 임대인이 자기 매물을 다시 손보는 경로도 들어왔다.** 수정은 `PUT /api/v2/listings/{listingId}` 하나이며 **등록 때 보낸 속성을 그대로 다시 보내 본문을 통째로 교체한다** — 부분 전송을 받지 않는 것은 `location`·`address.city`/`district`·`nearbyUniversityCodes`가 모두 주소에서 파생되는 값이라 일부만 오면 파생값이 서로 모순되기 때문이다. 요청 모양은 등록과 거의 같고 다른 곳은 둘뿐이다 — `roomOffers[]`가 `roomOfferId`(없으면 신규 방)와 `status`를 함께 싣고, `imageKeys`·`roomOffers[].roomImageKeys`에 **임시 키와 이미 확정된 키를 섞어** 보낼 수 있다(확정 키는 **원래 있던 그 자리에서 온 것만** 허용해 커버↔방·방↔방 교차를 막고, 교체돼 참조를 잃은 사진은 저장이 성공한 뒤에 지운다 — [ADR-0041](../adr/0041-listing-image-upload-to-s3.md) Amended). 상태 전이는 두 갈래다 — **반려된 매물은 `PENDING`으로 되돌아가 재심사를 받고, 이미 공개 중인 매물은 `UPDATE_PENDING`으로 내려가 심사가 끝날 때까지 세입자 노출에서 빠진다.** 후자는 심사를 거치지 않은 내용이 세입자에게 도달하지 않게 하려는 **의도된 동작**이며, 승인되면 찜 수·찜 문서·최근 본 기록까지 그대로 복구된다. 심사를 기다리는 동안(`PENDING`·`UPDATE_PENDING`)에는 수정할 수 없고(`422 LISTING_NOT_EDITABLE`), 읽고 저장하는 사이에 관리자가 상태를 바꾸면 그 저장을 거절해(`409 LISTING_STATE_CHANGED`) 심사 결과와 수정 본문이 서로를 소리 없이 덮어쓰지 않게 한다. **임대인 전용 조회**는 `GET /api/v2/users/me/listings`(목록 — `status` 다중 필터, 정렬은 `updatedAt` 최신순 고정)와 `GET /api/v2/users/me/listings/{listingId}`(상세)이며 **상태를 가리지 않는다** — 세입자 조회는 `PUBLISHED`만 보여주고 관리자 조회는 ADMIN 전용이라, 임대인이 심사 중·반려된 자기 매물을 볼 유일한 경로다. 경로를 `/api/v2/listings/mine`이 아니라 `/api/v2/users/me` 아래에 둔 것은 앞의 `GET /api/v2/listings/*` `permitAll` 매처에 먼저 걸려 **비로그인에 열리기** 때문이고, 수정도 같은 이유로 `PUT /api/v2/listings/*` 명시 매처가 따로 필요하다(`GET`만 공개다). 상세 응답은 수정 폼 프리필이 목적이라 **수정 요청에 실을 수 있는 전 필드**(`imageKeys`·`roomImageKeys` 포함)에 읽기 전용 `status`·`rejectionReason`을 더해 내려주고, 목록 응답은 세입자 목록 카드와 같은 무게에 `rejectionReason`만 더한다. 수정·조회 모두 남의 매물을 지목하면 존재를 감춰 `404 LISTING_NOT_FOUND`다(`403`을 쓰면 그 차이가 매물의 실재를 누설한다). **재고는 여전히 후속**이다.

**`Listing`** — 주소와 공용시설을 공유하는 건물 매물 애그리거트 루트. 식별자 `id`는 MongoDB ObjectId의 24자리 hex 문자열이며 API에서는 `listingId`로 노출한다. [값 객체: `GeoPoint`, `Address`, `NearestTransit`, `Building`, `Contact`, `Facilities`, `RoomOffer`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `schemaVersion` | int | 문서 구조 버전(v4 = `4`) |
| `landlordId` | 식별자 | 매물을 등록한 임대인 → `userType=LANDLORD`인 `User` 식별자 참조 |
| `contact` | VO `Contact` | 매물 담당 연락처(담당자명·지점 대표 전화). 세입자 응답에 공개. 임대인 개인 연락처는 담지 않는다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended) |
| `businessRegistrationNumber` | String | 등록 확인용 사업자등록번호 **원문**(숫자 10자리). 민감정보 — 응답 비노출 |
| `blogUrl` | String, nullable | 지점 블로그 링크(있을 때만) |
| `title` | VO `LocalizedText` | 매물 제목 `{ko,en}` |
| `type` | enum `ListingType` | 매물 유형 |
| `status` | enum `ListingStatus` | 심사 대기/승인·공개/반려/수정 심사 대기 상태(4종). 등록 직후는 `PENDING`이고, 공개 중인 매물을 임대인이 수정하면 `UPDATE_PENDING`으로 내려가 다시 승인될 때까지 세입자 노출에서 빠진다 — 세입자 경로는 예외 없이 "정확히 `PUBLISHED`인가"만 묻기 때문에 이 값 하나로 노출 전체가 닫히고 열린다 |
| `rejectionReason` | String, nullable | 반려 사유(관리자 입력·한국어 단일 문자열). `REJECTED`와, 고쳐서 재심사 중인 `PENDING`에 값이 있다 — 임대인만 읽는 값이라 번역 대상이 아니다. **임대인이 직접 바꿀 수 없다**(수정 요청에 칸이 없다). **수정은 이 값을 건드리지 않고 승인이 지운다** — 상태가 「지금 고쳐야 한다」와 「고쳐서 재심사 중」을 구분하므로 남아 있어도 혼동되지 않고, 재심사하는 관리자에게 이전 반려 맥락이 된다 |
| `rentalType` | enum `RentalType` | 매물 공통 임대 방식 |
| `refundPolicy` | VO `LocalizedText` | 매물 공통 환불 정책 안내 문구 `{ko,en}` |
| `genderPolicy` | enum `GenderPolicy` | 매물 공통 성별 정책 |
| `arcRequired` | enum `ArcRequirement` | 외국인등록증(ARC) 필수 요구 여부. 진단 `ArcStatus`와 값이 1:1로 대응하는 매물 쪽 정본 |
| `ageMin` | int | 이용 가능 최소 연령 |
| `ageMax` | int | 이용 가능 최대 연령 |
| `languagesSupported` | `Set<SupportedLanguage>` | 외국어 응대 가능 언어(복수) |
| `location` | VO `GeoPoint` | NOT NULL · 지도 검색용 좌표. 도메인/GeoJSON 순서는 `longitude, latitude`, API는 `{lat,lng}`. 등록이 주소 검색으로 받은 `address.lat`·`lng`로 채운다(ADR-0042 · changeUnit `0116`에서 필수로 전환) |
| `address` | VO `Address` | 표시 주소·행정구역 |
| `nearestTransit` | VO `NearestTransit` | 가까운 지하철역·도보 시간 |
| `nearbyFacilities` | `Set<NearbyFacility>` | 주변 편의시설(복수) — 교통과 무관한 값이라 루트가 소유 |
| `nearbyUniversityCodes` | `Set<String>` | 학교 검색·추천에 사용할 **개별 대학(member) 코드**(`SNU`·`CAU` 등) — 진단의 `UniversityGroup` 그룹 코드가 아니라 개별 대학 코드를 저장한다(저장 형식 불변). 진단은 선택 그룹을 member 코드로 펼쳐 `$in`으로 매칭([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). **등록이 `location` 반경 2km 안의 대학을 원장(`universities`)에서 찾아 채운다**([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)) |
| `building` | VO `Building` | 건물 유형·층수·주차·엘리베이터 |
| `facilities` | VO `Facilities` | 난방·주방·세탁·생활 편의·보안·공용 공간·제공 물품 |
| `roomOffers` | `List<RoomOffer>` | 가격·계약기간·검색 태그가 같은 실제 방 묶음. 등록 시 최소 1개 필수이며 각 `roomOfferId`는 서버가 발급한다 |
| `description` | VO `LocalizedText` | 매물 소개 문구 `{ko,en}` |
| `extraNotes` | VO `LocalizedText` | 입주 시 유의사항·생활 규칙 `{ko,en}` |
| `imageUrls` | `List<String>` | 건물 공용 이미지 URL 목록(순서 보존, 첫 번째가 썸네일). **등록 요청은 URL이 아니라 저장 키(`imageKeys`, 1~5개)를 보낸다** — 서버가 임시 위치의 사진을 확정 위치로 복사하고 그 CDN URL을 보낸 순서 그대로 채운다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)) |
| `preferredNationalities` | `Set<Nationality>` | 임대인이 선호하는 입주자 국적(등록 폼 설문, 복수). 요청에서는 **선택**이며 값이 없으면 빈 집합으로 저장한다. 세입자 응답 비노출 |
| `contractDifficulties` | `Set<ContractDifficulty>` | 외국인 임차인과 계약할 때 겪은 어려움(등록 폼 설문, 복수). 요청에서는 **선택**이며 값이 없으면 빈 집합으로 저장한다. 세입자 응답 비노출 |
| `serviceFeedback` | String | Kohere에 바라는 점(등록 폼 설문, 자유 입력). 응답 비노출 |
| `favoriteCount` | int | 찜 수 집계(≥0) |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 수정 시각(UTC) |

**불변식:** `Listing.status=PUBLISHED`이고 `RoomOffer.status=ACTIVE`인 방 상품이 하나 이상 있는 매물만 목록·지도·상세·찜·신청 대상이며 그 외 상태는 조회 시 부재처럼 처리한다(`404 LISTING_NOT_FOUND`); 등록 직후 상태는 `PENDING`이고 공개는 관리자 승인(`PENDING → PUBLISHED`)으로만 이뤄지며 반려(`PENDING → REJECTED`)는 `rejectionReason`을 함께 기록한다. **승인·반려 모두 상태를 가리지 않는다** — 재승인(잘못 반려한 매물을 되살린다)·사후 반려(공개 매물을 내린다)·사유 정정이 모두 정상 경로이며, 관리자의 오판을 되돌릴 수단이 서버에 있어야 하기 때문이다. 승인은 이전 `rejectionReason`을 비우고, **이미 공개 중이면 아무 일도 하지 않는다**(`updatedAt`을 바꾸면 목록 정렬이 흔들린다); **`location`이 없는 매물은 승인을 통과하지 못한다**(좌표 없이는 지도·거리 조회에 실릴 수 없다); Listing은 저장 시 최소 1개 이상의 `roomOffers`를 가져야 하고 각 `RoomOffer.roomImageUrls`는 최소 2장이다; **등록 요청이 싣지 않고 서버가 채우는 값**은 `id`·`roomOffers[].roomOfferId`·`schemaVersion`(4)·`status`(`PENDING`)·`favoriteCount`(0)·`createdAt`/`updatedAt`·`rentalType`(`MONTHLY_RENT` 고정)·`pricing.currency`(`KRW` 고정)·`roomOffers[].status`(`ACTIVE`)·`imageUrls`·`roomOffers[].roomImageUrls`다 — 사진은 URL이 아니라 **저장 키**로 실려 오므로 서버가 확정 위치로 복사한 뒤 그 URL을 보낸 순서 그대로 채운다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)); 등록 폼의 **한 칸이 두 필드로 갈리는 입력**(지점 운영층 `1~2` → `building.usedFloorMin`/`usedFloorMax`, 이용 연령대 `20~35` → `ageMin`/`ageMax`)은 서버가 파싱하며 형식 위반은 `400 INVALID_INPUT`이고 `usedFloorMin <= usedFloorMax <= building.totalFloors`를 함께 검증한다; 코드 값을 받는 필드는 `listingCatalog`의 `(category, code)`에 실재해야 한다(위반 `400 LISTING_UNKNOWN_CATALOG_CODE`, 문자열 길이 제한은 두지 않는다); `address.fullAddress`는 입력값을 **정규화 없이 그대로** 저장하고 `address.city`·`district`만 도로명 주소 파싱으로 `City`·`District` enum을 도출한다(파싱 실패 `400 LISTING_INVALID_ADDRESS`); `ageMin <= ageMax`; 금액은 `RoomOffer.pricing`에 집주인이 정한 단일값으로 저장하고 사용자 필터의 최소·최대 예산은 조회 조건일 뿐 애그리거트 속성이 아니다; 상세 화면의 조건 요약은 DB에 저장하지 않고 활성 `RoomOffer.filterTags` 합집합으로 응답 시 계산한다 — **파생 태그는 없어 저장값과 응답 태그가 1:1**이고, ARC 요구 여부는 태그가 아니라 루트 `arcRequired` 필드 하나로 표현한다; 필터 매칭의 정본은 반드시 같은 `RoomOffer`가 가격·옵션을 동시에 만족하는지로 판정한다; `imageUrls` 첫 항목이 건물 썸네일; **매물 담당 연락처(`contact`)는 매물 애그리거트가 저장하고 세입자 응답에 공개한다** — 임대인 개인 연락처(`users.phone_number`, 마스킹 대상 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md))와는 **별개 값**이라 마스킹하지 않으며, 임대인 개인 연락처는 매물에 저장하지도 노출하지도 않는다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)); `businessRegistrationNumber`(원문)와 등록 폼 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 저장하되 **응답에서 제외**한다; `favoriteCount`는 0 미만 불가이며 멱등 토글에서 중복 증감하지 않는다; `favorited`(사용자별 찜 여부)·`distanceMeters`(기준 좌표 대비 거리)는 조회 시점 산출 표현값이며 영속 속성이 아니다. 현재 일반 목록·기존 키워드 검색의 `favorited`는 실제 찜 조회 없이 항상 `false`이고, 찜 목록 저장소는 예외적으로 `PUBLISHED`만 검사해 ACTIVE 방 상품이 없는 매물이 포함될 수 있다.

**임대인 수정에도 위 불변식이 그대로 적용된다** — 등록과 같은 검증을 다시 통과해야 한다. 수정에서 **요청이 건드리지 못하고 승계되는 값**은 `id`·`landlordId`·`schemaVersion`·`createdAt`·`favoriteCount`·`rentalType`이고, **요청에서 서버가 다시 파생하는 값**은 `location`(요청 좌표)·`address.city`/`district`(도로명 재파싱, 미발견 `ETC`)·`nearbyUniversityCodes`(좌표 반경 재탐색)·`imageUrls`/`roomOffers[].roomImageUrls`(확정 키 → URL)·`pricing.currency`(`KRW`)·다국어 `LocalizedText` 8종이며, 나머지 `status`·`rejectionReason`(항상 `null`)·`updatedAt`은 **전이가 정한다**. `roomOffers`는 **순서 있는 전량 제출**이다 — `roomOfferId`가 없으면 신규 방이고 있으면 그 방을 갱신하며, 문서에 없는 id를 제시하면 `400 INVALID_INPUT`이다. **방을 내리는 것은 요청에서 빼는 것이 아니라 `status=INACTIVE`로 보내는 것**이고(그래야 나중에 다시 `ACTIVE`로 되살릴 수 있다), 어느 경우에도 하드 삭제하지 않는다 — 예약·채팅이 `roomOfferId`로 그 방을 영구 참조하기 때문이다. 요청에서 id가 통째로 빠진 방은 안전망으로 `INACTIVE`로 돌려 배열 맨 뒤에 원래 상대순서로 남기고, **저장 후 `ACTIVE` 방이 하나도 없으면 `400 INVALID_INPUT`으로 거절한다**(상태만 `PUBLISHED`이고 목록·상세에는 잡히지 않는 유령 매물이 생긴다). 저장 자체는 **읽을 때의 상태를 기대값으로 건 조건부 교체**라, 그 사이 관리자 심사가 상태를 바꿔 놓았으면 아무것도 쓰지 않고 `409 LISTING_STATE_CHANGED`로 되돌린다.

**`RoomOffer`** — 동일한 가격·계약기간·검색 태그를 가진 실제 방 묶음. Listing 내부 구성 요소이며 독립 애그리거트가 아니다. 식별자 `roomOfferId`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `roomOfferId` | 식별자 | Listing 내부 방 상품 식별자 |
| `name` | VO `LocalizedText` | 사용자에게 노출되는 방 상품명 `{ko,en}` |
| `status` | enum `RoomOfferStatus` | 판매/노출 상태 |
| `contract` | VO `Contract` | 방 상품별 최소/최대 계약기간 |
| `pricing` | VO `Pricing` | 월세·주세·보증금·관리비·통화 |
| `filterTags` | `Set<ConditionTag>` | 방 상품별 검색 필터 태그(등록 폼의 방 옵션 선택값). 응답 태그는 이 저장값과 1:1 — 파생 태그를 더하지 않는다 |
| `roomImageUrls` | `List<String>` | 방 상품 전용 이미지 URL 목록(순서 보존). **등록 요청은 `roomOffers[].roomImageKeys`(방마다 2~5개)로 저장 키를 보낸다** — 서버가 확정 위치로 복사하고 그 URL(배포 환경은 CDN 도메인)을 채운다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)) |

**불변식:** `pricing.monthlyRent`·`pricing.deposit`·`pricing.maintenanceFee`는 KRW 정수 ≥0; `contract.minStayMonths <= contract.maxStayMonths`(계약기간은 매물 공통이 아니라 **방 상품마다** 다를 수 있다); 가격·계약기간·개인 욕실/2인실 여부 등 필터 결과가 달라지는 조건이 다르면 별도의 `RoomOffer`로 분리한다.

**다국어 표시:** 매물마다 달라지는 제목·주소·역명·방 이름·환불 정책 문구·소개 문구(`description`)·유의사항(`extraNotes`)은 `LocalizedText(ko,en)`로 Listing에 저장한다. 검색·검증용 enum/시설/조건 code는 번역하지 않고, 별도 `listingCatalog`의 `{category,code,label:{ko,en}}`와 응답 시 결합한다 — 행정구역(`City`·`District`)·`ArcRequirement`·`SupportedLanguage`·`NearbyFacility`·개별 대학 코드도 같은 방식으로 라벨을 얻는다. 반면 `status`(`ListingStatus`)와 `rejectionReason`은 임대인·관리자만 읽는 값이라 카탈로그·번역 대상이 아니다(카탈로그에 상태 카테고리가 없다). 프론트 응답은 `{code,label}`이며 label은 표시, code는 필터 요청에 사용한다. 로그인 사용자의 언어는 `listing → user::api getLanguage` 동기 조회로 결정하고 비로그인·미지원 언어는 영어로 폴백한다([ADR-0037](../adr/0037-listing-localization-and-code-catalog.md)).

**`Favorite`** — 사용자가 매물을 찜한 사실 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(userId, listingId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 찜한 사용자 → `User` 식별자 참조(소유권) |
| `listingId` | 식별자 | 찜 대상 매물 → `Listing` 식별자 참조 |
| `favoritedAt` | Instant | 찜한 시각(UTC, 찜 목록 정렬 기준) |

**불변식:** `(userId, listingId)`는 유일 → 중복 찜 불가; 찜 등록은 멱등(이미 찜이면 신규 생성 없이 현재 상태 반환 — 신규 201 / 기존 200); 찜 해제도 멱등(미찜 해제는 에러 없이 `favorited=false`이고 카운트도 감소시키지 않음); 등록·해제 시 대상 매물은 `PUBLISHED`이면서 ACTIVE 방 상품이 하나 이상 있어야 함(그 외 `404 LISTING_NOT_FOUND`). 찜 문서와 `Listing.favoriteCount`는 현재 같은 MongoDB에서 순차적으로 별도 갱신하며 단일 트랜잭션·배치 재계산은 없다.

**`RecentListing`** — 사용자가 최근 조회한 매물 기록 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(userId, listingId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 조회한 사용자 → `User` 식별자 참조(소유권) |
| `listingId` | 식별자 | 조회한 매물 → `Listing` 식별자 참조 |
| `viewedAt` | Instant | 마지막 조회 시각(UTC, 최신순 정렬 기준) |

**불변식:** `(userId, listingId)`는 유일 → 재조회는 새 기록 없이 `viewedAt`만 갱신(upsert·멱등); 온보딩 완료 사용자의 상세 조회 성공 시 기록; 사용자별 최신순 최대 30개까지 보관하고 초과분은 오래된 기록부터 삭제; 최근 본 목록 API는 저장된 기록 중 `PUBLISHED`이면서 ACTIVE 방 상품이 있는 매물만 최신순 최대 10건 노출; 본인 기록만 조회(`me` 스코프).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `GeoPoint` | `longitude` | double | 경도(WGS84). MongoDB GeoJSON 좌표 배열의 첫 번째 값 |
| | `latitude` | double | 위도(WGS84). API 응답에서는 `lat`으로 변환 |
| `Address` | `city` | string | 도시/광역 단위 · 정본은 `listingCatalog`의 `CITY`(주소 토큰에서 파생, 못 찾으면 `ETC`) |
| | `district` | string | 구/군 단위 · 정본은 `listingCatalog`의 `DISTRICT`([ADR-0046](../adr/0046-administrative-region-as-catalog-data.md)) |
| | `fullAddress` | VO `LocalizedText` | 표시 주소 `{ko,en}` |
| | `detail` | VO `LocalizedText` | 상세주소 `{ko,en}`, nullable |
| `NearestTransit` | `type` | enum `TransitType` | 가까운 교통수단 유형 |
| | `name` | VO `LocalizedText` | 역 이름 `{ko,en}` |
| | `walkMinutes` | int | 도보 시간(분) |
| `Contact` | `managerName` | String | 매물 담당자(지점 운영자)명 |
| | `phone` | String | 지점 대표 전화(문의 수신). 매물마다 다른 값이라 임대인 개인 연락처(`users.phone_number`)와 별개다 |
| `Building` | `type` | enum `BuildingType` | 건물 형태 |
| | `usedFloorMin` | int | 사용 층 최소값 |
| | `usedFloorMax` | int | 사용 층 최대값 |
| | `totalFloors` | int | 건물 전체 층수 |
| | `parkingAvailable` | boolean | 주차 가능 여부 |
| | `elevatorAvailable` | boolean | 엘리베이터 여부 |
| `Facilities` | `heatingSystem` | `Set<HeatingSystem>` | 난방 방식(없으면 `NONE` 하나) |
| | `kitchen` | `Set<KitchenFacility>` | 주방 시설 |
| | `laundry` | `Set<LaundryFacility>` | 세탁 시설 |
| | `livingAmenities` | `Set<LivingAmenity>` | 생활 편의시설 |
| | `securityFeatures` | `Set<SecurityFeature>` | 보안 시설 |
| | `commonSpaces` | `Set<CommonSpaceType>` | 공용 공간(원소가 곧 코드 — 수량은 받지 않는다) |
| | `providedSupplies` | `Set<ProvidedSupply>` | 제공 물품 |
| `Pricing` | `monthlyRent` | int | 월세(KRW 정수) |
| | `deposit` | int | 보증금(KRW 정수) — booking의 `totalAmount = deposit + monthlyRent × 계약 개월수` 계산 근거 |
| | `maintenanceFee` | int | 관리비(KRW 정수) |
| | `currency` | enum `Currency` | 통화(현재 `KRW`) |
| `Contract` | `minStayMonths` | int | 최소 계약 개월(`RoomOffer` 소유) |
| | `maxStayMonths` | int | 최대 계약 개월(`RoomOffer` 소유) |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ListingType` | `GOSHIWON` | 고시원 |
| | `CO_LIVING` | 코리빙 |
| | `SHARE_HOUSE` | 쉐어하우스 |
| `ListingStatus` | `PENDING` | 등록 직후 관리자 승인 대기(등록 시 기본값) |
| | `PUBLISHED` | 승인돼 공개 중 |
| | `REJECTED` | 관리자 반려(`rejectionReason` 보유) |
| | `UPDATE_PENDING` | 공개 중이던 매물을 임대인이 수정해 **재심사 대기** — 승인 전까지 세입자 노출에서 빠지고, 승인되면 `PUBLISHED`로 복귀한다 |
| `RoomOfferStatus` | `ACTIVE` | 노출·계약 가능 |
| | `INACTIVE` | 노출 중지 |
| `RentalType` | `MONTHLY_RENT` | 월세 |
| `Currency` | `KRW` | 원화 |
| `City` | `SEOUL` | 서울특별시 |
| | `BUSAN` | 부산광역시 |
| | `GYEONGGI` | 경기도 |
| `District` | `JONGNO_GU` | 종로구 |
| | `SEODAEMUN_GU` | 서대문구 |
| | `MAPO_GU` | 마포구 |
| | `GWANGJIN_GU` | 광진구 |
| | `DONGDAEMUN_GU` | 동대문구 |
| | `GWANAK_GU` | 관악구 |
| | `GURO_GU` | 구로구 |
| | `YEONGDEUNGPO_GU` | 영등포구 |
| | `GEUMCHEON_GU` | 금천구 |
| `TransitType` | `SUBWAY` | 지하철 |
| `BuildingType` | `COMMERCIAL_BUILDING` | 상가건물 |
| | `STANDALONE_BUILDING` | 단독건물 |
| | `VILLA` | 빌라/연립 |
| | `DETACHED_HOUSE` | 단독주택 |
| | `OFFICETEL` | 오피스텔 |
| | `APARTMENT` | 아파트 |
| | `MIXED_USE` | 주상복합 |
| `HeatingSystem` | `CENTRAL` | 중앙난방 |
| | `INDIVIDUAL` | 개별난방 |
| | `NONE` | 해당 없음 |
| `GenderPolicy` | `ANY` | 성별 무관 |
| | `FEMALE_ONLY` | 여성 전용 |
| | `MALE_ONLY` | 남성 전용 |
| | `GENDER_SEPARATED` | 층/공간 분리 |
| `ArcRequirement` | `REQUIRED` | 외국인등록증(ARC) 필수 |
| | `NOT_REQUIRED` | ARC 불필요(여권 등으로 신분 확인) |
| `SupportedLanguage` | `ENGLISH` | 영어 |
| | `CHINESE` | 중국어 |
| | `JAPANESE` | 일본어 |
| `NearbyFacility` | `CONVENIENCE_STORE` | 편의점 |
| | `MART` | 마트/슈퍼마켓 |
| | `HOSPITAL_PHARMACY` | 병원/약국 |
| | `PARK` | 공원 |
| | `LAUNDROMAT` | 세탁소 |
| | `NONE` | 해당 없음 |
| `ConditionTag` | `MOVE_IN_NOW` | 즉시 입주 |
| | `FEMALE_ONLY` | 여성 전용 |
| | `MEALS_INCLUDED` | 식사 제공 |
| | `DOUBLE_ROOM` | 2인실 |
| | `PRIVATE_BATH` | 개인 욕실 |
| | `ENGLISH_OK` | 영어 소통 가능 |
| | `ADDRESS_REGISTRATION` | 전입신고 가능 |
| | `NO_MAINT_FEE` | 관리비 없음 |
| `RoomFeature` | `SINGLE_ROOM` | 1인실 |
| | `DOUBLE_ROOM` | 2인실 |
| | `PRIVATE_BATH` | 개인 욕실 |
| | `PRIVATE_REFRIGERATOR` | 개인 냉장고 |
| | `MICROWAVE` | 전자레인지 |
| | `ELECTRIC_KETTLE` | 전기포트 |
| `KitchenFacility` | `SHARED_REFRIGERATOR` | 공용 냉장고 |
| | `INDUCTION` | 인덕션 |
| | `GAS_STOVE` | 가스레인지 |
| | `MICROWAVE` | 전자레인지 |
| | `ELECTRIC_KETTLE` | 전기포트 |
| | `RICE_COOKER` | 전기밥솥 |
| | `TOASTER` | 토스트기 |
| | `COFFEE_MACHINE` | 커피머신 |
| | `WATER_PURIFIER` | 정수기 |
| | `NONE` | 해당 없음 |
| `LaundryFacility` | `WASHER` | 세탁기 |
| | `DRYER` | 건조기 |
| | `DRYING_RACK` | 건조대 |
| | `IRON` | 다리미 |
| | `NONE` | 해당 없음 |
| `LivingAmenity` | `WIFI` | 와이파이 |
| | `TV` | TV |
| | `SOFA` | 소파 |
| | `GYM_EQUIPMENT` | 운동기구 |
| | `AIR_CONDITIONER` | 공용 에어컨 |
| | `PROJECTOR` | 프로젝터 |
| | `AIR_PURIFIER` | 공기청정기 |
| | `SHARED_PC` | 공용 PC |
| | `NONE` | 해당 없음 |
| `SecurityFeature` | `CCTV` | CCTV |
| | `ENTRANCE_DOOR_LOCK` | 공동현관 도어락 |
| | `DOOR_LOCK` | 방별 도어락 |
| | `FIRE_EXTINGUISHER` | 소화기 |
| | `FIRE_ALARM` | 화재경보기 |
| | `SECURITY_GUARD` | 경비원 |
| | `NONE` | 해당 없음 |
| `ProvidedSupply` | `BEDDING` | 침구류 |
| | `LAUNDRY_DETERGENT` | 세탁세제 |
| | `SEASONING` | 조미료 |
| | `SLIPPERS` | 실내화 |
| | `TISSUE` | 휴지 |
| | `TOWEL` | 수건 |
| | `NONE` | 해당 없음 |
| `CommonSpaceType` | `SHARED_KITCHEN` | 공용 주방 |
| | `SHARED_TOILET` | 공용 화장실 |
| | `SHARED_BATH` | 공용 욕실 |
| | `LOUNGE` | 라운지 |
| | `STUDY_ROOM` | 스터디룸 |
| | `MEETING_ROOM` | 회의실 |
| | `ROOFTOP` | 옥상 |
| | `NONE` | 해당 없음 |
| `Nationality` | `JAPAN` | 일본(등록 폼 설문 — 선호 국적) |
| | `USA` | 미국 |
| | `CHINA` | 중국 |
| | `SOUTHEAST_ASIA` | 동남아 |
| | `EUROPE` | 유럽 |
| `ContractDifficulty` | `LANGUAGE` | 의사소통 문제(언어) — 등록 폼 설문 |
| | `CULTURE` | 외국인 생활 관련 문제(문화) |
| | `IDENTITY` | 낯선 외국인에 대한 두려움(신원) |
| | `PAYMENT` | 대금 지급·환율(결제) |
| | `CONTRACT_FULFILLMENT` | 손해배상·위약금(계약 이행) |
| | `COMMUNICATION_CHANNEL` | 외국인과의 소통 채널 부족 |

> 정렬 프리셋(`ListingSort`: `RECOMMENDED`·`PRICE_ASC`·`DISTANCE`)과 지도 검색의 bbox·마커 결과 상한은 **조회 파라미터**이지 애그리거트 영속 속성이 아니다(거리순은 요청 bbox의 중심 좌표를 기준으로 하며, bbox 누락 시 `400 LISTING_INVALID_SORT_PARAM`; 과대 영역 `400 LISTING_AREA_TOO_LARGE`; bbox 모순 `400 LISTING_INVALID_BBOX`).

> `RoomFeature` enum은 코드에 남아 있지만 현재 `RoomOffer` 속성이나 MongoDB 저장 스키마에서는 사용하지 않는다. 방 검색 조건의 현재 정본은 `RoomOffer.filterTags`의 `ConditionTag`다.
>
> 시설 8종(`HeatingSystem`·`KitchenFacility`·`LaundryFacility`·`LivingAmenity`·`SecurityFeature`·`CommonSpaceType`·`ProvidedSupply`·`NearbyFacility`)은 전부 `NONE`(해당 없음)을 갖는다 — 등록 폼이 각 칸에 최소 하나를 강제하므로 「없음」을 보낼 코드가 필요하기 때문이다. **`NONE`은 단독으로만 보낼 수 있고**(다른 코드와 섮이면 `400 INVALID_INPUT`), 응답에는 다른 코드와 똑같이 `{code, label}`로 나간다. 행정구역의 `ETC`·설문의 `OTHER`가 「목록 밖의 값」인 것과 뜻이 다르다.
>
> `Nationality`·`ContractDifficulty`는 등록 폼 설문 응답 전용이라 `listingCatalog`에 카테고리가 없다 — 세입자 응답에 나가지 않아 라벨을 번역할 소비처가 없다. `ListingStatus`도 같은 이유로 카탈로그 대상이 아니다(임대인·관리자만 읽는다).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다(ADR-0002). `Favorite`·`RecentListing`·`Listing.landlordId`는 `user`를 식별자로만 보유하고 표시정보는 `user` 공개 쿼리로 협력한다. **매물 등록의 임대인 인가 재검사**도 같은 경로다 — 보안 필터가 넘긴 `userId`로 `user::api getUserType(userId)`를 동기 조회해 `LANDLORD`가 아니면 `403 FORBIDDEN`으로 거절하고(`auth`의 사업자번호 검증이 같은 쿼리를 쓰는 것과 동일 패턴), 매물 문서에는 그 `userId`만 `landlordId`로 남긴다. 진단 기반 추천은 `diagnosis`가 본 모듈의 **공개 추천 쿼리**(조건·월세 범위·지역·대학 그룹으로 매물 조회)를 호출해 충족하며 매물 엔티티를 공유하지 않고 식별자·요약만 넘긴다 — `diagnosis`가 선택 대학 그룹을 펼친 member 코드 집합을 넘기면 `nearbyUniversityCodes`를 `$in`(ANY member)으로 매칭하고(`ETC`면 그 대신 목록 전체를 제외 조건으로 받아 `$nin`으로 거른다), 월세 범위는 같은 ACTIVE `RoomOffer`의 `pricing.monthlyRent`에 각 경계(존재 시)를 적용한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). **진단과 매물은 값 집합을 공유하지 않고 매핑으로 잇는다** — 진단 `Region`은 `address.city`(`City`)와 등가 비교하고, 진단 `District`의 5구는 `address.district`(`District`)와 등가 비교하되 **`ETC`는 그 5구의 여집합(`$nin`)** 으로 질의하며, 진단 `ArcStatus`는 `NO_ARC`면 `arcRequired=NOT_REQUIRED` 필터로 옮기고 `ARC_ISSUED`면 필터를 걸지 않는다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 신청·문의(`booking`·`chat`)는 매물 존재·공개·임대인 식별자·대표 가격·썸네일이 필요할 때 공개 쿼리로 검증한다. 지도 검색창 키워드 장소 검색은 아웃바운드 포트 `PlaceSearchClient`(domain)로 추상화하고 인프라 어댑터 `NaverPlaceSearchClient`(네이버 지역 검색 API)가 구현한다 — **무상태**(매물 미조회·미저장)이며 최대 5개 후보(`title`·`address`·`roadAddress`·`lng`·`lat`, 네이버 `mapx/mapy`→WGS84 변환)를 반환하고, 외부 장애·타임아웃·인증정보 누락·응답/좌표 형식 이상은 `502 UPSTREAM_ERROR`로 응답한다. 매물 사진 저장도 같은 구조다 — 아웃바운드 포트 `ListingImageStorage`(domain)를 인프라 어댑터 `S3ListingImageStorage`(AWS S3, 로컬은 MinIO로 endpoint만 바꾼다)가 구현하고, 업로드(`upload`)·확정 복사(`copy`)·삭제(`deleteQuietly`) 셋을 맡는다. 사진은 업로드 API가 `uploads/{landlordId}/{uuid}.{ext}`에 올려 두고, 등록이 확정될 때 `listings/{listingId}/cover/{uuid}.{ext}`·`listings/{listingId}/rooms/{roomOfferId}/{uuid}.{ext}`로 복사되며 매물 문서에는 URL만 남는다. 확정 키가 식별자를 포함하므로 저장 전에 `ListingRepository.nextIdentity()`로 `listingId`·`roomOfferId`를 선발급한다. 복사·저장이 실패하면 복사본을 보상 삭제하고(삭제 계약은 실패를 처리하는 중에 불리므로 예외를 던지지 않는다) 임시본은 남긴다. 없는 원본은 복사가 `400 LISTING_IMAGE_KEY_NOT_FOUND`로, 그 밖의 저장소 실패는 `502 UPSTREAM_ERROR`로 갈린다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)).

---

## 4. `diagnosis` — 6단계 맞춤 진단

> [API 스펙](../api/specs/02-diagnosis-recommendation.md) · [시퀀스](sequence-diagrams/02-diagnosis-recommendation/README.md) · `allowedDependencies = {common, listing :: api, user :: api}`(추천·지역 매칭 `listing::api recommendByCriteria`, 번역용 표시 언어 조회 `user::api getLanguage`)

6단계 맞춤 진단(지역·입국 목적(유학 여부)·대학 그룹/지역(구) 선택·주거 환경 조건·월세 최소-최대 범위·ARC 발급 여부)을 본인 소유 레코드로 영속하고, 진단 조건으로 `listing` 공개 쿼리와 협력해 추천 매물을 제공한다. **진행 중 답은 서버가 DB에 저장**한다 — 사용자당 진행 중(`IN_PROGRESS`) 진단 1건을 in-progress draft로 들고 단계별 답을 채워가다가, 제출 시 `COMPLETED`로 확정한다(누적 답 재전송 없음). 재진단은 기존을 수정하지 않고 새 in-progress 진단을 시작해 항상 새 레코드로 이력을 보존한다.

**`Diagnosis`** — 한 사용자의 6단계 진단(애그리거트 루트). **진행 중(`IN_PROGRESS`)에는 서버가 단계별 답을 채워가는 in-progress draft**이고, **제출 시 `COMPLETED`로 확정**된다. 식별자 `id`(별도 비즈니스 키 없음 — 재진단은 새 레코드다).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자, nullable | 진단 소유자 → `User` 식별자 참조. **회원 진단만 채운다** — 게스트(비회원) 진단은 부재이고 `guestSessionId`가 대신 채워진다(아래 "게스트 신원") |
| `guestSessionId` | String, nullable | **게스트 진단만 채운다**(회원 진단은 부재) — 게스트 세션 키. `userId`와 **정확히 하나만** 채워진다(#181) |
| `criteria` | VO `DiagnosisCriteria` | 6단계 입력(지역·입국 목적·대학 그룹/지역(구) 선택·조건·월세 범위·ARC). `IN_PROGRESS`에는 서버가 단계별로 채워가는 부분 값, `COMPLETED` 확정 시 불변 |
| `status` | enum `DiagnosisStatus` | 진단 상태(`IN_PROGRESS` → `COMPLETED` \| `DISCARDED`) |
| `submittedAt` | Instant, nullable | **종료 시각**(UTC) — `COMPLETED`는 제출 확정 시각, `DISCARDED`는 폐기 시각(`IN_PROGRESS`에는 부재). 상태가 어느 종료인지 말해주므로 타임스탬프는 하나로 통일 |

**불변식:** `status` 전이는 `IN_PROGRESS → COMPLETED`(제출 확정, 완결성 검증 통과) 또는 `IN_PROGRESS → DISCARDED`(v2 미완주 시도 폐기, **검증 없음** — 부분 답이 정상)만 허용(역전이·건너뛰기 없음); `DISCARDED`는 사용자 입력을 버리지 않기 위한 수요 분석 기록이며 어떤 조회에도 노출되지 않는다(ADR-0036); 사용자당 진행 중(`IN_PROGRESS`) 진단은 1건만 — 단계별 답을 보낼 때마다 서버가 그 in-progress draft의 `criteria`에 해당 필드를 채운다(서버가 DB에 저장; 누적 답 재전송 없음); **제출은 in-progress 진단 확정 요청**으로, 서버가 저장된 답을 재검증해 `COMPLETED`로 확정하고 `submittedAt`을 기록한다(이 시점이 진단 생성=완료); `criteria`는 `COMPLETED` 확정 후 불변(재진단은 수정이 아니라 **새 in-progress 진단 시작** → 확정 시 새 `Diagnosis`); **이력/목록 조회는 `COMPLETED`만 노출**(`IN_PROGRESS` draft 제외); **입국 목적별 대학/지역 선택 정합** — ③ 대학·지역은 **두 필드로 분리**(`university`·`district`)하며, `purpose`가 `STUDY`이면 `university`가 필수이고 `district`는 비어야 하며(유학 분기), `NON_STUDY`(비유학) 분기면 `district`가 필수이고 `university`는 비어야 한다(입국 목적에 맞는 하나만 채워짐; 위반은 공통 `400 INVALID_INPUT` + `errors[]` 필드별 사유, 진단 도메인 전용 코드 없음); 조회·추천은 `userId`가 요청자와 일치하는 본인 소유에 한함(타인 `403 FORBIDDEN`); 부재 진단 조회 `404 DIAGNOSIS_NOT_FOUND`; **신원은 `userId`(회원)와 `guestSessionId`(게스트) 중 정확히 하나만 채워진다** — 게스트 진단의 소유권은 `guestSessionId` 일치로 판정하며, **신원 종류가 같고 값이 같을 때만** 통과하고 한쪽이 부재면 거절한다(게스트가 회원 진단을, 회원이 게스트 진단을 열지 못한다 — 타인 접근은 동일하게 `403 FORBIDDEN`).

> **게스트(비회원) 신원 — 부재로 표현한다(#181)**: 애플 심사 대응으로 **v2 서버 주도 흐름(`/api/v2/diagnoses/**`)에 한해** 진단(및 진단이 부르는 매물 추천)이 **로그인 없이** 열리면서, 이 모듈은 요청자를 두 종류로 본다(**v1 진단 7개는 회원 전용으로 유지**되므로 게스트 문서를 만드는 경로는 v2뿐이다) — **회원**은 `userId`, **게스트**는 `userId` **부재(null)** 다. 게스트에게 합성 식별자(`anonymous`+uuid 같은 임시 `userId`)를 **발급하지 않는다**: 신원의 부재를 값으로 위장하면 회원 식별자와 같은 자리에 섞여 소유권 판정이 "값이 같은가"로 뭉개진다. 대신 게스트에게 필요한 것은 신원이 아니라 **대화 연속성**이므로, 여러 요청에 걸친 진단 흐름을 잇기 위한 **게스트 세션 키**를 별도 개념으로 둔다(아래 `DiagnosisFlowSession`). 그 키는 진단 문서에 `guestSessionId`로 남아 게스트 진단의 소유권 판정 기준이 되며, **요청자마다 다른 값**이어야 한다 — `Diagnosis.id`는 순차 식별자라 열거가 쉬워서 게스트 경로에서는 이 판정이 유일한 접근 통제다. 게스트 진단은 회원 진단으로 **이관되지 않는다**(게스트가 나중에 로그인해도 결과는 옮겨가지 않는다 — 후속 과제). **회원 경로의 소유권·조회·상태 전이 규칙은 위와 동일하게 유지된다.**
>
> **게스트의 표시 언어는 `en` 고정**이다 — `user` 공개 쿼리(`getLanguage`)는 **회원(신원이 있는 요청)에 대해서만 호출**하고, 게스트에 대해서는 호출하지 않는다(`getLanguage`는 회원 레코드를 전제하므로 게스트에겐 부재로 귀결된다). 즉 라벨 번역의 언어 결정이 게스트에서는 `user` 모듈을 경유하지 않으며, 이 경우에도 폴백 기본 언어(`en`)와 결과가 같다(`Accept-Language` 기반 게스트 언어는 후속 과제).

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

> 진단 결과 화면은 `Diagnosis.criteria`를 입력으로 `listing`의 공개 추천 쿼리를 호출해 추천 전용 요약(`RecommendedListingView`)·좌표를 조립한다. 매물은 본 모듈 애그리거트가 아니므로 식별자(`listingId`)로만 참조하며, 추천 결과는 진단에 종속된 읽기 결과로 영속하지 않는다. **대학 그룹 매칭**: 진단은 선택된 `UniversityGroup`을 소속 개별 대학 코드 `includedUniversityCodes`로 확장해 넘기고, `listing`은 `nearbyUniversityCodes`를 그 코드 집합으로 `$in` 매칭한다 — `ETC`면 목록 전체를 제외 조건으로 넘겨 여집합(`$nin`)으로 매칭한다. **월세 범위 매칭**: `monthlyRentMin`/`monthlyRentMax`는 같은 ACTIVE `roomOffers[]` 원소의 `pricing.monthlyRent`에 적용한다. 교차 모듈 계약은 `RecommendationCriteria`로 전달한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). 0건 추천 제안의 `message`/`detail`은 `diagnosisSuggestions` 컬렉션의 인라인 언어-키 맵에서 서버가 사용자 언어로 골라 제공한다.

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
| | `ETC` | 기타 — "선택지에 없는 그 외"라는 뜻이라 추천 시 매물 `address.district`의 **위 5구 여집합**으로 매칭한다(매물 9종에 맞춰 선택지를 늘리지 않는다) |
| `DiagnosisCondition` | `MOVE_IN_NOW` | 즉시입주(④, listing `ConditionTag` 이름 통일) |
| | `FEMALE_ONLY` | 여성전용 |
| | `PRIVATE_BATH` | 개인욕실 |
| | `ENGLISH_OK` | 영어가능 |
| | `ADDRESS_REGISTRATION` | 전입신고가능 |
| | `NO_MAINT_FEE` | 관리비없음 |
| | `MEALS_INCLUDED` | 식사제공 |
| | `DOUBLE_ROOM` | 2인실 |
| `ArcStatus` | `ARC_ISSUED` | ARC(외국인등록증) 발급 완료(추천 시 ARC 필터 미적용) |
| | `NO_ARC` | ARC 미발급(추천 시 `arcStatus` 값을 그대로 넘겨 매물 `arcRequired=NOT_REQUIRED`로 매칭 — 파생 조건으로 바꾸지 않는다) |
| `DiagnosisStatus` | `IN_PROGRESS` | 진행 중(v1이 단계별 답을 채워가는 in-progress draft, 이력·목록 비노출) |
| | `COMPLETED` | 제출 확정 완료(`IN_PROGRESS`에서 전이, 이력·목록 노출) |
| | `DISCARDED` | 6단계를 못 채우고 **끝난** v2 시도(`Diagnosis.discard`) — 수요 분석 전용 기록이라 **사용자 노출 경로를 두지 않는다**: 목록(이력·최근)은 `COMPLETED`만 보므로 자동으로 빠지고, **id로 직접 오는 상세·추천은 명시적으로 404로 거절**한다(소유권만으론 못 막는다 — 본인 기록이고 id가 순차 발급이라 추측 가능). ADR-0036 결정 12 |
| `NoMatchReason` | `NO_MATCH` | 조건에 맞는 매물 없음 |
| `SuggestionActionType` | `RELAX_REGION` | 지역 조건 완화 |
| | `RELAX_CONDITIONS` | 주거 조건 일부 해제 |
| | `INCREASE_BUDGET` | 월세 범위 확대(최소를 낮추거나 최대를 높임 — enum 코드는 유지) |
| | `ADJUST_KEYWORD` | 키워드 조정 |

> `DiagnosisCondition`은 `listing`의 `ConditionTag`와 동일 이름을 쓴다(각 모듈이 자기 enum을 소유·공유 금지) — 두 enum 모두 8종으로 1:1이며 **ARC 관련 값은 어느 쪽에도 없다**(진단은 `arcStatus` 답으로, 매물은 `arcRequired` 필드로 표현한다). ④ 주거 환경 조건은 예외 없이 최대 3개다.
>
> `UniversityGroup`·`District`는 ③ 대학·지역 선택 단계의 입력 enum으로, **enum 값 카탈로그의 정본은 이 문서**다(위 표에 등재). **고정 enum(코드 1:1 검증용)은 MongoDB `diagnosisQuestions` 카탈로그**(`step`·`field`·`options[{code, label}]`·`select{type,max}` — **데이터만**, 분기 메타 없음)로 두고, **표시 문자열(번역)은 같은 `diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — `code`(UPPER_SNAKE)는 제출 검증 enum과 단일 출처(1:1)로 고정·언어 무관이고, 질문은 `question: { "en": .., "ja": .., "ko": .. }`, 옵션 라벨은 `options[].label: { "en": .., "ja": .. }`처럼 **언어 코드를 키로 하는 맵**으로 둔다(서버가 사용자 언어 키로 선택, 부재 시 영어(`en`) 폴백; 닉네임 풀·`countries`와 다른 부류로, reference로 분리하지 않음). 대학 그룹 질문(`university`)·지역 질문(`district`)은 각각 별도 step 데이터로 카탈로그에 존재하고, **어느 질문을 낼지는 서비스가 저장된 `purpose`로 결정**한다(데이터에 분기 메타 없음). 대학 그룹 질문의 `options[].code`는 `UniversityGroup`과 1:1(6개 그룹), 사용자는 그중 1개를 단일 선택한다.
>
> **대학 그룹 → 소속 개별 대학 코드(member) 매핑** — `UniversityGroup`은 ③ 단계의 **선택 입력**이고, member 개별 코드(`SNU`·`CAU` 등 기존 개별 University 코드)는 **추천 질의 시 그룹을 펼친(query-time expansion) 결과**다. 개별 코드는 여전히 `listing`의 `nearbyUniversityCodes`에 저장되는 값으로(**listing 저장 형식은 변경하지 않음** — 그룹 코드가 아니라 member 개별 코드를 저장), 진단은 선택 그룹을 아래 member 집합으로 확장해 listing 공개 추천 쿼리에 넘기고 listing은 `nearbyUniversityCodes`를 그 집합으로 `$in`(ANY member) 매칭한다. `ETC`는 고른 학교가 목록 밖이라는 뜻이므로, 멤버를 펼치는 대신 **목록 전체를 제외 조건**으로 넘겨 여집합(`$nin`)으로 매칭한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)).
>
> | `UniversityGroup` | ko 라벨 | en 라벨 | member 개별 대학 코드 |
> | --- | --- | --- | --- |
> | `HUFS_KHU_KOREA` | 한국외대·경희대·고려대 | HUFS · Kyung Hee · Korea Univ. | `HUFS`·`KHU`·`KOREA` |
> | `SKKU_SUNGSHIN` | 성균관대·성신여대 | Sungkyunkwan · Sungshin Women's | `SKKU`·`SUNGSHIN` |
> | `SNU_CAU_SOONGSIL` | 서울대·중앙대·숭실대 | Seoul National · Chung-Ang · Soongsil | `SNU`·`CAU`·`SOONGSIL` |
> | `HONGIK_YONSEI_EWHA` | 홍익대·연세대·이화여대 | Hongik · Yonsei · Ewha Womans | `HONGIK`·`YONSEI`·`EWHA` |
> | `KONKUK_SEJONG_HYU` | 건국대·세종대·한양대 | Konkuk · Sejong · Hanyang | `KONKUK`·`SEJONG`·`HYU` |
> | `ETC` | 기타 | Other | (멤버 없음) — 목록 14곳의 **여집합**(`$nin`)으로 매칭 |
>
> **⑤ 월세 범위(`monthlyRent`) — `NUMBER_RANGE` 카브아웃** — 5단계 월세 입력은 enum 옵션이 아니라 **숫자 범위 자유 입력**이라 `diagnosisQuestions`의 step-5 `select.type`을 `"NUMBER_RANGE"`(두 숫자 입력 필드, `options`는 빈 배열)로 둔다 — "모든 step은 코드가 enum과 1:1인 고정 옵션 목록"이라는 전제에서 의도적으로 갈라진 예외다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다 — `userId`(→ `user`), 추천 결과의 `listingId`(→ `listing`). 추천은 `Diagnosis.criteria`로 `listing` 공개 추천 쿼리 `recommendByCriteria`를 동기 호출해 `RecommendedListingView` 페이지를 받아 조립한다(엔티티 비공유, ADR-0002 Decision 5). 교차 모듈 계약 `RecommendationCriteria`의 `includedUniversityCodes`는 개별 대학 코드 `Set<String>`(`ETC`면 비고 대신 `excludedUniversityCodes`가 목록 전체로 채워진다)이고, `monthlyRentMin`/`monthlyRentMax`는 nullable이며, `arcStatus`도 원시 값으로 함께 넘긴다. listing은 같은 ACTIVE 방 상품의 월세에 각 경계를 적용한다([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)). **두 모듈은 값 집합을 일치시키지 않고 매핑으로 잇는다** — `Region`↔`address.city`는 등가 비교, `District`의 5구는 등가 비교하되 `ETC`는 매물 쪽 그 5구의 **여집합(`$nin`)**, `ArcStatus`는 `NO_ARC`면 매물 `arcRequired=NOT_REQUIRED` 필터·`ARC_ISSUED`면 필터 미적용으로 옮긴다(진단 `District`는 6종 그대로 두고 매물 9종에 맞춰 늘리지 않는다 — [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 라벨 번역에 쓸 표시 언어는 `user` 공개 쿼리 `getLanguage`로 동기 취득한다. 진단 제출·재진단은 본 모듈 내부에서 완결되며 외부 발행 이벤트는 없다.

- **문항·선택지 카탈로그(US-2-5)** — 6단계별 {질문(`question`), 선택지[`code`], 선택 제약(`select{type, max}`)}는 `Diagnosis` 애그리거트가 아니라 **MongoDB `diagnosisQuestions` 컬렉션(도메인 포트로 조회)**로 제공한다 — **데이터만 보유**하고 분기 메타(`branchOn` 등)는 두지 않는다(분기는 서비스 비즈니스 로직 소관). 번역(표시 문자열)은 분리 컬렉션 없이 **같은 `diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — 질문은 `question: { "en": .., "ja": .., "ko": .. }`, 옵션 라벨은 `options[].label: { "en": .., "ja": .. }`로 두고 선택지 `code`(UPPER_SNAKE)는 언어 무관 불변이다. 문항 제공은 **단계별 server-stateful 질의응답**이다 — 클라이언트가 받을 step(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`(인증 필수, 200)을 호출하면, 서버가 (카탈로그 + 본인 진행 중(`IN_PROGRESS`) 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정해 `{ step, field, question, select{type, max}, options[{code, label}] }`(`question`·`label`은 서버가 인라인 언어-키 맵에서 사용자 언어 키로 고른 표시 문자열, `code`는 언어 무관)로 내려준다(한 번에 다 주지 않음; 다음 step 번호는 클라가 정한다). 현재 step 답은 별도로 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; `conditions`처럼 다중은 `codes` 배열; **⑤ 월세 범위는 enum 코드가 아니라 두 숫자 필드** `{ "field": "monthlyRent", "min": 300000, "max": 600000 }` — 순서 없는 `codes[]` 배열을 재사용하지 않는다)로 보내면 서버가 **본인 진행 중(`IN_PROGRESS`) 진단에 저장**한다(누적 답 묶음 전송 없음). 흐름은 `GET questions/1 → POST answers → GET questions/2 → … → GET questions/6 → POST answers → POST /diagnoses`이며, 모든 단계 답이 저장되면 `POST /api/v1/diagnoses`(제출)가 진행 중 진단의 저장된 답을 재검증해 `COMPLETED`로 확정한다. **분기는 서비스 비즈니스 로직이 결정한다(클라 로컬 분기·데이터 분기 메타 아님)** — ③ 대학·지역 단계(step 3)는 저장된 `purpose`를 보고 서비스가 알맞은 질문만 낸다: `STUDY`면 대학 그룹 질문(`university`, 목록 `UniversityGroup` 6개 그룹)을, `NON_STUDY`면 지역 질문(`district`, 목록 `District`)을 내려준다(두 질문 데이터는 카탈로그에 각각 존재하고, 노출은 서비스가 결정; 한 응답에 두 목록을 함께 주지 않는다). 선택지 `code`는 제출 검증 enum과 **동일 출처(1:1)** 라 코드로 제출하면 `INVALID_INPUT` 없이 수용된다(카탈로그·번역 모두 `diagnosisQuestions` 도큐먼트에 함께 보유). 잘못된 현재 step 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `400 INVALID_INPUT`+`errors[]`로 거른다.
- **라벨 번역(US-2-6)** — 표시 `label`·`question`은 **사용자 표시 언어**의 값으로 채운다. `code`는 언어 무관 동일(UPPER_SNAKE)이며 인라인 언어-키 맵의 값(표시 문자열)만 언어별이고, 해당 언어 키가 없으면 영어(`en`)로 폴백한다(에러 아님; `Accept-Language` 비의존). 표시 언어는 **`user` 공개 쿼리(`getLanguage`)로 동기 취득**한다(`user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 회신 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); 토큰 클레임 분기 제거; ADR-0002 Decision 5) — `user`는 식별자/원시 값으로만 참조하고 엔티티를 공유하지 않는다. 표시 문자열은 **`diagnosisQuestions` 도큐먼트의 `question`/`options[].label`에 인라인 언어-키 맵으로 임베드**한다: `question: { "en": "Select a region", "ja": "エリアを選択", "ko": "지역 선택" }`, `options: [ { "code": "SEOUL", "label": { "en": "Seoul", "ja": "ソウル" } }, ... ]`처럼 **언어 코드를 키로 하는 맵**이다(문항·옵션은 `diagnosisQuestions`, 추천 사유/액션은 `diagnosisSuggestions` 컬렉션에 같은 인라인 언어-키 맵 방식 재사용). **표시 언어 결정은 전적으로 `user` 소관**이며(`users.lang`이 있으면 그 값, 없으면 `en`), `diagnosis`는 폴백 규칙을 알지 못한 채 언어 코드만 받는다. 미지원 언어의 **폴백 기본 언어는 영어**다. 서버 동작: 표시 언어(`user` `getLanguage`) → 도큐먼트의 언어-키 맵에서 그 언어 키 값을 골라(부재 시 `en`) 응답 조립.
- **`allowedDependencies`** — 추천·지역 매칭은 `listing` 공개 쿼리(`recommendByCriteria`)를, 라벨 번역 표시 언어는 `user` 공개 쿼리(`getLanguage`)를 동기 호출하므로 `diagnosis`의 `allowedDependencies`는 **`listing :: api`·`user :: api`를 포함**한다(즉 `{common, listing :: api, user :: api}`; 토큰 클레임 분기 제거로 `{common}` 유지 안 함). 이는 `package-info.java`/`@ApplicationModule`에 반영된다(v2 서버 주도 흐름의 ① 지역 조기 게이트·확정 시 매칭 존재 확인도 동일 `listing::api`를 재사용하므로 의존은 그대로다).

**v2 — 서버 주도 진단 흐름 (issue #157):**

기존 v1(클라이언트가 `step`·확정을 주도) 위에, 클라이언트가 `POST /api/v2/diagnoses/start`로 진단을 열고 `POST /api/v2/diagnoses/next`로 대화하면 서버가 다음 질문·분기·확정 시점을 판단하는 **서버 주도 흐름**을 `/api/v2`에 신설한다([ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md)). **서버가 주도하는 것은 질문과 분기뿐이다** — 진단을 시작할 시점도, 확정된 진단의 매물을 조회할 시점도 클라이언트가 정한다: 확정 응답에는 추천 매물을 인라인으로 싣지 않고 `diagnosisId`만 주며, 매물은 클라가 v1 `GET /api/v1/diagnoses/{id}/recommendations`로 별도 조회한다(서버의 매칭 계산은 결과코드 판정에 필요한 **존재 확인**까지만). v2는 위 `Diagnosis`·`DiagnosisCriteria`·상태 enum(`Region`…`ArcStatus`)을 그대로 재사용하고, **진행 상태만 별도 애그리거트**에 담는다. v1 계약(`GET /questions/{step}` → `POST /answers` → `POST /diagnoses`)은 변경하지 않는다.

**`DiagnosisFlowSession`** — v2 진행 세션 애그리거트 루트. 사용자당 최대 1건이며, 완료 시 `draft`를 `Diagnosis.complete()`로 확정해 정본 `Diagnosis`로 만들고(기존 `diagnoses`에 저장) 세션은 삭제한다. 세션은 **클라이언트가 `POST /start`로 시작할 때만 생기고 터미널(자동 확정·재시도·종료)에서 삭제된다** — 진행 중 세션이 있어도 `/start`는 그것을 버리고 새로 만들며(진단을 중단했다 다시 시작하면 언제나 처음부터), 세션이 없는데 `/next`가 오면 서버가 흐름을 되살리지 않고 `DiagnosisFlowSessionNotFoundException`(→ `400 DIAGNOSIS_SESSION_NOT_FOUND`)으로 거절한다. v1의 `IN_PROGRESS` 초안(`Diagnosis`)을 공유하지 않는 이유: `pendingField` 같은 절차 필드가 `Diagnosis`에 없고, "사용자당 `IN_PROGRESS` 1건" 제약과 충돌하기 때문이다.

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자, nullable | 세션 소유자 → `User` 식별자 참조(사용자당 1 세션, UNIQUE). **회원 세션만 채운다** — 게스트 세션은 부재이고 `guestSessionId`가 대신 채워진다 |
| `guestSessionId` | String, nullable | **게스트 세션만 채운다**(회원 세션은 부재) — 게스트 세션 키당 1 세션. `userId`와 **정확히 하나만** 채워진다(#181) |
| `draft` | VO `DiagnosisCriteria`(부분) | 누적 답 스냅샷(단계별로 채워지는 부분 값) |
| `pendingField` | String | 서버가 **직전에 낸 문항의 `field`** — 다음 `/next`가 받을 수 있는 유일한 답이자 **진행의 단일 정본**(다음 문항은 `DiagnosisFlowStep.ofField(답한 field).next()`) |

**불변식:** 세션은 `POST /start`로만 생성되며 사용자당 1건 — 진행 중 세션이 있어도 **버리고** 새 세션(빈 `draft`·`pendingField = "region"`)으로 대체한다(서버가 기존 진행 정보를 보고 이어가지 않음); 세션 없이 `/next`가 오면 `DiagnosisFlowSessionNotFoundException`(`400 DIAGNOSIS_SESSION_NOT_FOUND`)으로 거절하고 서버가 임의로 흐름을 되살리지 않는다(클라가 `/start`로 복구); **`/next`가 받을 수 있는 답은 `pendingField`(서버가 직전에 낸 문항) 하나뿐**이며 없거나 다르면 `400 INVALID_INPUT` — 기대 답을 정본 순서로 역산하지 않는 이유는 6슬롯에 없는 문항(`regionRetry`)이 흐름에 끼어들 수 있어서다(그때 순서가 가리키는 슬롯과 실제로 낸 문항이 어긋난다); 선형 순서는 `DiagnosisFlowStep`의 **선언 순서**(`REGION → PURPOSE → UNIVERSITY_OR_DISTRICT → CONDITIONS → MONTHLY_RENT → ARC_STATUS`)가 정본이며 `ofField(답한 field).next()`로 따라간다 — `Diagnosis.validateComplete`가 `conditions`를 필수로 보지 않아(비어도 통과) 답 필드 null로 진행을 추론하지 않는다; 마지막 슬롯(`arcStatus`)을 답하면 서버가 `draft`를 `COMPLETED`로 자동 확정하고(확정 시점을 클라가 주지 않음) 세션을 삭제한다; ① 지역 답 직후 지역-only 매칭이 0건이면 `pendingField = "regionRetry"`로 예외질문을 내고(서버가 미리 필터링하는 유일한 지점; 정본 슬롯은 전진하지 않는다), 그 예/아니오는 진단 답이 아니라 흐름 제어 응답이라 `draft`에 저장되지 않고 둘 다 터미널이라 세션을 삭제한다 — "예"=`RESTART`(클라가 `/start`로 새 세션을 열어 재시도)·"아니오"=`TERMINATED`(진단 종료); **진행 중 세션을 되돌리는 전이는 없다**(재시작은 언제나 `/start`의 새 세션으로); 조회·매칭은 본인 소유(`userId`)에 한한다; **게스트 세션은 `guestSessionId`로 식별**하며 세션 키당 1건이고, 신원 종류가 같고 값이 같을 때만 그 세션에 닿는다(아래 "게스트 세션").

> **게스트 세션 — 클라이언트가 에코하는 세션 키로 잇는다(#181)**: 회원 세션은 `userId`로 이어지지만 게스트에게는 그 식별자가 없으므로, `POST /start`가 게스트 요청에 대해 **세션 키를 발급해 응답에 싣고** 클라이언트가 이후 `/next`·추천 조회에 그 키를 **되돌려보낸다**(전송 수단은 API 계약 소관 — [02-diagnosis-recommendation](../api/specs/02-diagnosis-recommendation.md)). 이 키는 인증 자격이 아니라 **대화 연속성의 키**다: 신원(권한)을 부여하지 않고, 그 키로 시작한 흐름과 그 흐름이 확정한 진단에만 닿는다. 요청자마다 다른 값이어야 하며(공용·상수 키를 쓰면 게스트끼리 서로의 진단을 열 수 있다), 키가 없거나 서버가 모르는 값이면 **세션이 없는 것과 같아** `DiagnosisFlowSessionNotFoundException`(`400 DIAGNOSIS_SESSION_NOT_FOUND`)이다 — 회원의 세션 부재와 코드도 복구법(`/start`로 재시작)도 같다. **클라이언트가 키를 잃으면 진단은 처음부터**이며, 서버는 흐름을 되살리지 않는다(위 불변식의 "서버가 임의로 흐름을 되살리지 않는다"가 게스트에도 그대로 적용된다). 회원 세션의 규칙(사용자당 1 세션·`/start`가 기존 세션을 버림·터미널에서 삭제)은 게스트 세션에도 동일하게 적용된다.

**상태(enum, v2 흐름):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `DiagnosisFlowStep` | `REGION`·`PURPOSE`·`UNIVERSITY_OR_DISTRICT`·`CONDITIONS`·`MONTHLY_RENT`·`ARC_STATUS` | 진단 6단계의 **정본 선형 순서**(선언 순서가 정본) + 슬롯↔`field` 매핑(`UNIVERSITY_OR_DISTRICT`만 `field()`가 없음 — 저장된 `purpose`로 `university`/`district` 택일하며, `ofField`는 그 둘을 모두 `UNIVERSITY_OR_DISTRICT`로 되돌린다). `step()`(1..6)은 위치에서 **파생**하므로 순서와 어긋날 수 없고, v1 경로 파라미터↔`field` 변환과 응답 표시에만 쓴다. v2 전용이 아니다: v2는 선언 순서(`ofField(...).next()`)로 진행을 강제하고, **v1도 `ofStep(step)`으로 클라가 지정한 단계에서 낼 문항 `field`를 이 매핑으로 지목**한다 |
| `FlowResultCode` | `NEXT_QUESTION` | 다음 질문이 남음(마지막 슬롯 전). ① 지역 0건 예외질문(`field=regionRetry`)도 **이 코드**로 내려간다(`question` 채움) |
| | `RESTART` | 지역 예외질문 "예" → 클라이언트가 `POST /start`로 처음부터 재시도(코드만, 세션 삭제) |
| | `COMPLETED` | 자동 확정 — `diagnosisId`만 채움(매칭 유무 미확인·추천 매물 비포함) |
| | `TERMINATED` | 지역 예외질문 "아니오" → 진단 종료(코드만, 세션 삭제) |

> `FlowResultCode`는 `POST /api/v2/diagnoses/start`·`/next`의 정상 `200` 응답 `data`에 실리는 결과코드(태그드 유니온)이며 에러가 아니다 — `DiagnosisStatus`(도메인 전이 enum)와 분리한다. payload는 결과코드별로 `question`(NEXT_QUESTION) 또는 `diagnosisId`(COMPLETED)뿐이고 **매물 요약은 실리지 않는다** — 조회 시점은 클라이언트가 정해 `GET /api/v2/diagnoses/{id}/recommendations`(`V2RecommendationResponse { content, markers, page }`)를 호출한다. **매칭 0건에 해당하는 결과코드는 없다** — 0건인지는 추천을 실제로 조회해야 알 수 있어(그러려면 클라가 요청하지 않은 쿼리를 서버가 돌려야 한다) 그 응답의 빈 `content`로 드러낸다. v2는 v1의 `RecommendationSuggestions`(조정 제안)를 쓰지 않으므로 v2 추천 응답에는 그 필드가 없다(v1의 `diagnosisSuggestions` 자산은 v1 전용으로 유지·미참조). 상세: [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md), 시퀀스 [US-2-7](sequence-diagrams/02-diagnosis-recommendation/us-2-7-v2-server-driven-flow.md).
>
> **① 지역 0건 예외질문(`regionRetry`)은 진단 답 필드가 아니다** — 그 답(`YES`/`NO`)은 `DiagnosisCriteria`에 없어 `Diagnosis`의 어떤 필드로도 저장되지 않고 정본 슬롯도 전진시키지 않는 **흐름 제어 응답**이다(진단 답과 달리 `applyAnswer`를 타지 않는다). 단, **그 응답이 폐기 기록의 방아쇠**다 — 예/아니오 어느 쪽이든 세션을 지우기 전에 그때까지의 `draft`(=`region`만 채워진 부분 답)를 `DISCARDED` 진단으로 저장한다(지역 수요 신호 — 위 `DiagnosisStatus` 참조). 즉 저장되지 않는 건 **`YES`/`NO` 값**이지 그 시점의 진단 초안이 아니다. 동시에 서버 코드에 하드코딩한 합성 문구가 아니라 **`diagnosisQuestions` 카탈로그의 일반 문항**(`field: "regionRetry"`, `select {type: "SINGLE", max: 1}`, `options[].code`=`YES`/`NO`)이라 문항·번역 정본이 다른 6단계와 같은 곳에 있다(인라인 언어-키 맵도 동일). 카탈로그는 **순서를 담지 않고 `field`로 문항을 식별**하므로 이 문항도 다른 문항과 동등하게 들어가고, 정본 6슬롯 밖이라는 사실은 코드(`DiagnosisFlowStep`에 없음)에만 있다. v1 `GET /questions/1`은 `DiagnosisFlowStep.ofStep(1).field()`=`region`으로 조회해 계약이 불변이다. 시드는 다른 카탈로그 문항과 같이 Mongock `@ChangeUnit`으로 적재하며(기배포 환경용 멱등 추가 ChangeUnit 포함), 적재 순서·컬렉션 등 물리 사항은 [database-design](../database/database-design.md) 소관이라 여기서 다루지 않는다.

---

## 5. `booking` — 매물 신청(예약)

> [API 스펙](../api/specs/04-booking-inquiry-chat.md) · [시퀀스](sequence-diagrams/04-booking-inquiry-chat/README.md) · `allowedDependencies = {common, listing, user}`(조회 시점 매물 요약·가격 조회 `listing::api`, 예약자 성명·차단 조회·차단 생성 `user::api`, 신고 사유 라벨 번역용 표시 언어 조회 `user::api getLanguage`)

세입자가 매물에 입주를 신청(예약)하는 컨텍스트다. **1차 MVP에서 매물 예약은 인앱 채팅과 분리된 독립 기능**으로, 예약을 저장(생성)하고, 조회 엔드포인트는 요청자 `userType`으로 분기해 **세입자는 내 예약**을, **임대인은 자기 소유 매물(`listing.landlordId`=본인)에 신청된 예약**을 목록·단건 상세로 본다(별도 임대인 전용 API 없이 `userType` 분기; 역할 `403` 없음). 조회는 **스냅샷 없이 조회 시점 실시간 조인**으로 매물 요약·가격은 `listing` 공개 쿼리(`listing::api`)로, 예약자 성명은 `user` 공개 쿼리(`user::api`)로 조합한다(cross-store 조인 금지, [ADR-0005](../adr/0005-polyglot-persistence.md) → 애플리케이션 레벨 조합). **후속·이연**: 신청 성공 시 도메인 이벤트(`BookingCreatedEvent`)를 발행해 임대인과의 채팅·예약 카드(`BOOKING_CARD`) 고정을 `chat`에 위임하는 설계는 1차 MVP 범위 밖으로 이연한다(설계 보존, 아래 협력/이벤트 참조).

**`Booking`** — 세입자가 특정 매물(방 상품)에 제출한 신청(예약) 애그리거트 루트. 식별자 `id`(API에서 `bookingId`로 노출), 비즈니스 키 `(tenantId, roomOfferId)`(활성 예약 유일 — 동일 세입자·동일 방 상품에 활성 예약은 1건만 존재). 이 유일성은 `uq_bookings_tenant_room_offer`(`(tenant_id, room_offer_id)` UNIQUE)로 강제하며 재신청은 `409 BOOKING_ALREADY_EXISTS`로 거부된다 — 상태 전이(수락/거절/취소)가 미구현이라 모든 예약이 `REQUESTED`(활성)이므로 "활성 1건"이 곧 "전체 1건"이 되어 조건 없는 UNIQUE로 규칙이 정확히 표현된다(전이 도입 시 활성 상태만 거는 부분 유니크로 교체 필요 — 아래 불변식 참조). 차단이 예약 단위가 아니라 **사용자 단위**인 근거는 이 유일성이 아니라 **임대인이 매물·방 상품을 여러 개 갖는 구조**에 있다(§2 `UserBlock` 참조).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(숫자 PK, API에서 `bookingId`로 노출) |
| `tenantId` | 식별자 | 신청한 세입자 → `User` 식별자 참조(소유권·조회 스코프) |
| `listingId` | 식별자 | 신청 대상 매물 → `Listing` 식별자 참조(문자열 ObjectId hex) |
| `roomOfferId` | 식별자 | 신청 대상 방 상품 → `Listing` 내부 `RoomOffer` 식별자 참조(문자열 ObjectId hex) |
| `landlordId` | 식별자 | 신청 대상 매물의 소유자 → `User` 식별자 참조. **생성 시 `listing::api`로 조회한 `listing.landlordId` 스냅샷**(숫자) — 임대인 받은 신청 조회(US-4-6)의 소유권 스코프 키 |
| `moveInDate` | LocalDate | 타겟 입주일(날짜만) |
| `contractPeriod` | int | 신청 시 입력한 계약 기간(개월수, 양의 정수) |
| `status` | enum `BookingStatus` | 예약 상태(신청 직후 `REQUESTED` 고정). **두 참여자가 공유하는 필드** — 참여자별 표시 여부를 담지 않는다(아래 `*DeletedAt` 참조) |
| `createdAt` | Instant | 신청 시각(UTC, 목록 정렬 기준) |
| `tenantDeletedAt` | Instant, nullable | **표시 상태(참여자별)** — 세입자가 이 예약을 자기 목록에서 숨긴 시각(UTC). `null`이면 미삭제. 세입자 분기 목록·상세에서만 제외 기준이 되고 임대인의 가시성에는 영향이 없다 |
| `landlordDeletedAt` | Instant, nullable | **표시 상태(참여자별)** — 임대인이 이 예약을 자기 목록에서 숨긴 시각(UTC). `null`이면 미삭제. 임대인 분기에만 적용되고 세입자의 가시성에는 영향이 없다 |

> **저장하지 않는 값(조회 시점 계산·조인):** 예약자 성명(`tenantName`)·매물 요약(`title`·`thumbnailUrl`·주소·`RoomOffer` name)·가격(`deposit`·`monthlyRent`)·총 금액(`totalAmount`)은 **애그리거트에 스냅샷으로 저장하지 않는다** — 상세/목록 조회 시점에 `listing`(`listingId`·`roomOfferId`로)·`user`(`tenantId`로) 공개 쿼리로 실시간 조인해 조립한다(가격 변경 시 현재가 기준). 총 금액 `totalAmount = deposit + monthlyRent × contractPeriod`(계약 개월수 정수)이며 **관리비(`maintenanceFee`)는 총액에서 제외**한다 — 저장 필드가 아니라 조회 계산값이다.
>
> **소유자 식별자(`landlordId`) — 생성 시 비정규화 저장:** 예약 **생성 시** 매물 소유자(`listing.landlordId`)를 `Booking.landlordId`로 함께 저장한다(생성이 이미 `listing::api`로 매물을 조회하므로 소유자 스냅샷 캡처 비용이 거의 없다). 임대인 **조회 분기**(`userType=LANDLORD`)의 "내 매물에 신청됨"은 이 `landlordId`로 booking 저장소에서 직접 스코핑하며(목록 `landlord_id=요청자`, 상세 `booking.landlordId==요청자` 행 단위 확인), **cross-store 조인이 불필요**하다([ADR-0005](../adr/0005-polyglot-persistence.md)) — `chat_rooms`가 `landlord_id`를 비정규화하는 선례와 일치한다. 생성 시점 스냅샷이라 소유권 이전 시 stale하나 이전은 MVP 범위 밖이다. 예약 **생성** 자체는 세입자 전용이라 본인 매물 차단(소유자 대조)은 여전히 불필요하다. 첫 인사 메시지(`GreetingMessage`)는 채팅 연동 설계에 속하므로 **후속·이연**으로 애그리거트 저장 필드에 두지 않는다.

**불변식:** 예약은 `ACTIVE` 세입자(`userType=TENANT`) 전용 — 임대인/비세입자는 거부(`403 FORBIDDEN`); **동일 세입자–동일 방 상품 예약은 중복 방지한다** — `(tenantId, roomOfferId)`에 UNIQUE(`uq_bookings_tenant_room_offer`)를 두어 활성 예약을 1건으로 제한하고, 이미 신청한 방 상품 재신청은 `409 BOOKING_ALREADY_EXISTS`로 거부한다(상태 전이가 미구현이라 모든 예약이 `REQUESTED`=활성이므로 조건 없는 UNIQUE로 "활성 1건"이 정확히 표현된다 — ⚠️ 향후 상태 전이가 도입되면 `REJECTED`·`CANCELED` 건이 같은 방 재신청을 영구 차단하므로 활성 상태만 거는 **부분 유니크**로 교체해야 하며, MySQL은 부분 유니크 인덱스를 지원하지 않아 표현 방식(`active_room_offer_id` 같은 nullable 컬럼 + UNIQUE 트릭, 또는 앱 레벨 검사)을 그때 정한다); 예약은 세입자 전용이라 자기 소유 매물 예약 상황이 성립하지 않아 본인 매물 차단(소유자 조회)도 없다; `moveInDate`는 과거 불가(`422 BOOKING_INVALID_MOVE_IN_DATE` — 매물이 입주 가능일을 보유하지 않으므로 과거 날짜 검증만 한다); `contractPeriod`는 양의 정수 개월수(`400 INVALID_INPUT`); 대상 매물·방 상품이 없거나 비공개면 부재 처리(`404 LISTING_NOT_FOUND`); **신청 생성 시 `status`는 항상 `REQUESTED` 고정 — 수락/거절/취소 등 상태전이는 이 범위 밖**(1차 MVP 미구현); 목록·상세 조회는 `userType`으로 분기한다 — **세입자 분기**는 요청자 본인(`tenantId`) 예약만 반환하며 없거나 타인 예약이면 `404 BOOKING_NOT_FOUND`, **임대인 분기**(`userType=LANDLORD`)는 요청자 소유 매물(`listing.landlordId`=본인)에 신청된 예약만 반환하며 없거나 내 소유 매물 신청이 아니면 `404 BOOKING_NOT_FOUND`로 통일한다(두 역할 모두 유효 요청이라 역할 `403` 없음); **삭제(내 목록에서 숨김)는 요청자 쪽 필드만 세팅한다** — 세입자는 `tenantDeletedAt`, 임대인은 `landlordDeletedAt`에 삭제 시각(UTC)을 기록하며 **상대의 목록·상세 가시성은 그대로다**(두 참여자가 예약 1건을 공유하므로 단일 삭제 플래그를 두면 한쪽의 삭제가 상대의 기록까지 지우는 데이터 손실이 된다); 삭제는 **멱등** — 이미 삭제한 예약을 다시 삭제해도 성공(`204`)이며, 이를 위해 변이 경로는 삭제·차단으로 **필터되지 않은** 조회로 대상을 찾는다(필터된 조회를 쓰면 두 번째 요청이 `404`가 되어 멱등이 깨진다); 참여자가 아니거나 없는 예약의 삭제는 `404 BOOKING_NOT_FOUND`(존재 비노출 — 조회 규약과 통일); **차단 관계인 상대의 예약은 목록·상세에서 제외한다** — 차단자 기준 **단방향**이라 내가 상대를 차단하면 그 상대와의 모든 예약이 **내 목록에서만** 사라지고 상대의 목록은 그대로다(차단은 `*DeletedAt`을 세팅하지 않는다 — 숨김은 차단 필터가 수행한다); **차단 관계면 신규 예약 신청을 거부한다** — 요청자와 매물 소유자 사이에 **어느 방향으로든**(양방향 판정) 차단이 있으면 `POST /listings/{listingId}/bookings`는 `403 FORBIDDEN`으로 막는다(막지 않으면 생성은 성공하는데 차단 필터가 그 예약을 양쪽 목록에서 영구히 가리는 **블랙홀 예약**이 된다).

> **왜 삭제·차단이 `BookingStatus` 값이 아닌가:** 표시 상태는 `status`로 표현하지 않는다 — (1) `status`는 `tenantId`·`landlordId`가 **공유하는 하나의 필드**라 한쪽의 숨김이 상대가 보는 상태까지 바꿔 버리고, (2) **취소(`CANCELED`)와 숨김은 다른 개념**이며(취소는 예약 자체의 종료, 삭제는 내 목록의 표시 여부 — 상대에겐 예약이 그대로 살아 있다), (3) 1차 MVP는 애초에 **상태 전이가 미구현**(`REQUESTED` 고정)이라 `status`에 실을 전이가 없다. 그래서 참여자별 표시 상태는 `tenantDeletedAt`·`landlordDeletedAt` 두 독립 필드로 둔다(물리 컬럼·인덱스·소프트삭제 규약과의 관계는 [database-design](../database/database-design.md) 소관).
>
> **차단 목록 조회는 애플리케이션 레벨 조인이다:** 목록·상세에서 가릴 차단 상대는 `user` 공개 쿼리(`user :: api`의 `findBlockedUserIds(요청자)`)로 식별자 집합을 받아 booking 저장소 질의의 술어로 넘기고, 신규 신청 가드의 양방향 판정은 `isBlockedBetween(요청자, 임대인)`으로 받는다 — `booking`이 차단 기록을 **직접 조인하지 않는다**(엔티티·테이블 비공유, [ADR-0002](../adr/0002-inter-module-communication-via-events.md)). `user :: api`는 이미 `allowedDependencies`에 있으므로 **새 모듈 의존 엣지는 생기지 않는다**.

**`BookingReport`** — 예약 상대의 부적절한 행위를 신고한 **접수 기록**(불변) 애그리거트 루트. 식별자 `id`(API에서 `reportId`로 노출). **별도 비즈니스 키가 없다** — 동일 신고자가 동일 예약을 여러 번 신고할 수 있다(다건 허용, 유일성 제약 없음).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(숫자 PK, API에서 `reportId`로 노출) |
| `reporterId` | 식별자 | 신고자 → `User` 식별자 참조(대상 예약의 참여자 본인). **응답 비노출** |
| `bookingId` | 식별자 | 신고 대상 예약 → `Booking` 식별자 참조(같은 모듈) |
| `reason` | String, nullable | 신고 사유 — 사유 카탈로그(`booking_report_reasons`)의 **`code` 문자열을 값 참조**로 저장(**선택** — 전송하면 활성 `code` 검증 후 저장하고 미전송이면 `null`). enum·FK 아님 |
| `detail` | String, nullable | 신고에 덧붙이는 자유 텍스트(선택, 최대 500자). **원문 응답 비노출** |
| `createdAt` | Instant | 접수 시각(UTC) |

**불변식:** **동일 예약 다건 신고를 허용한다** — 같은 신고자가 같은 예약을 여러 번 신고할 수 있다(새 사유·지속되는 문제를 다시 접수할 수 있어야 하므로 유일성 제약을 두지 않는다 — `(reporterId, bookingId)` 유니크 없음, 재접수 `409` 없음). 신고 도배 방지는 별도 유일성이 아니라 **레이트리밋(`429`)** 으로 다루며 이는 **후속·이연**이다(현재 미구현); **접수 후 상태 전이가 없다** — 생성 시점에 확정되는 불변 기록이라 어떤 필드도 갱신되지 않는다(수정·철회 경로 없음); 신고자는 대상 예약의 참여자(`tenantId` 또는 `landlordId`)여야 하며, 참여자가 아니거나 없는 예약이면 `404 BOOKING_NOT_FOUND`(존재 비노출 — 조회·삭제와 동일 규약); **자기 신고는 구조적으로 성립하지 않는다** — 예약 생성이 `userType=TENANT` 전용이고 `userType`은 온보딩 확정 후 불변이라 `tenantId != landlordId`가 항상 참이다(그래서 자기 신고 전용 에러코드를 두지 않는다); **신고 가능 여부는 삭제·차단 상태와 무관하다** — 내가 삭제했거나 상대를 차단한 예약도 신고할 수 있다(증거 보존). 따라서 같은 예약이 `GET /bookings/{id}`에선 `404`인데 신고는 `201`이 되는 **의도된 비대칭**이 생기며, 이를 위해 신고 경로는 삭제·차단으로 **필터되지 않은** 조회로 대상을 찾는다; `reason`은 전송 시 **활성 사유 카탈로그 `code`인지 검증**하고(비활성·미등록 code면 `400 INVALID_INPUT`) 그 `code` 문자열을 저장하거나 미전송(`null`), `detail`은 선택·500자 초과 불가(`400 INVALID_INPUT`); `reporterId`·`detail` 원문은 응답 비노출(프라이버시 — 응답은 `reportId`·`bookingId`·`reason`·`createdAt`).

> **왜 예약 신고를 `booking`이 소유하나(§9 `report`가 아니라):** 신고 **접수**는 "대상 예약이 실재하는가 / 신고자가 그 예약의 참여자인가"를 검증해야 하는데 그건 **예약만 아는 정보**이고, 불변식(참여자만 신고 가능)이 예약 상태에 의존하므로 소유자도 예약이다. `report`가 접수하면 `report → booking :: api` 포트를 새로 뚫어야 하지만, `booking`이 접수하면 모듈 내부 호출이라 **새 의존 엣지가 0개**다. §9 `report`의 `Report`는 **게시글(`POST`)·댓글(`COMMENT`)·채팅 메시지(`MESSAGE`)** 를 대상으로 하고 `BookingReport`는 **예약**을 대상으로 해 **신고 대상이 겹치지 않는 별개 애그리거트**다 — `ReportTargetType`에 `BOOKING`을 두지 않는 것이 이 무충돌의 전제다.
>
> **왜 `status`가 없나:** 이 애그리거트의 범위는 **접수(capture)까지**다 — 운영자 검토·제재·처리 상태 전이는 범위 밖이라 실을 전이가 없다. 그래서 §9 `Report`의 `ReportStatus`(`RECEIVED` 단일 값)에 해당하는 필드를 아예 두지 않는다(접수됐다는 사실은 기록의 존재가 말한다).
>
> **왜 사유 카탈로그가 별개인가:** 예약 신고 사유는 `booking`이 소유하는 **DB 카탈로그**(`booking_report_reasons` — 아래 참조)이고 §9 `report`의 `ReportReason`과 **값이 겹치지만 별개 저장소**다 — 사유 카탈로그를 공유하면 `booking → report` 모듈 의존이 생긴다. 각 모듈이 자기 사유 목록을 소유하는 선례(`diagnosis`의 `DiagnosisCondition` ↔ `listing`의 `ConditionTag`)를 따라 예약 맥락 전용 카탈로그로 둔다.

**값 객체(VO):** 1차 MVP의 예약·예약 신고 애그리거트에는 값 객체가 없다(`BookingReport.detail`은 제약이 "선택·최대 500자"뿐이라 §9 `report`의 `ReportDetail`과 달리 VO로 감싸지 않고 nullable 문자열 속성으로 둔다).

> **후속·이연(1차 MVP 제외) — `GreetingMessage`:** `text`(String, 공백 제외 1~500자). 신청과 함께 보내는 첫 인사로, 존재할 때만 채팅 첫 텍스트 메시지로 전달하는 설계다 — 채팅 연동과 함께 이연한다(설계 보존).

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `BookingStatus` | `REQUESTED` | 신청 직후 기본 상태(1차 MVP는 생성 시 항상 이 값) |
| | `ACCEPTED` | 임대인 수락(**후속·이연** — 상태전이 미구현) |
| | `REJECTED` | 임대인 거절(**후속·이연** — 상태전이 미구현) |
| | `CANCELED` | 세입자 취소(**후속·이연** — 상태전이 미구현) |

**신고 사유 카탈로그(`booking_report_reasons`):** 신고 사유는 JVM enum이 아니라 **`booking`이 소유하는 MySQL 카탈로그 테이블**의 행이다 — 사유든 언어든 **행 INSERT만으로**(코드 배포·스키마 변경 없이) 동적으로 늘릴 수 있게 enum·리소스 번들 대신 카탈로그로 둔다. **`(code, lang)` 한 쌍이 한 라벨**이며, `code`는 언어 무관 불변이고 라벨은 언어별 행이다(사유 6종 × 언어 3종(en/ko/ja) = 시드 행). `BookingReport.reason`은 이 카탈로그의 **`code` 문자열을 값 참조**로 저장한다(선택 · nullable · FK 없음). 물리 스키마·시드 마이그레이션은 [database-design](../database/database-design.md) 소관.

| `code` | 의미 |
| --- | --- |
| `SPAM` | 스팸/광고(§9 `report`의 `ReportReason`과 값이 겹치지만 별개 카탈로그: 예약 맥락 전용) |
| `ABUSE` | 욕설/괴롭힘 |
| `SEXUAL_CONTENT` | 성적 콘텐츠 |
| `EXTERNAL_CONTACT` | 외부 연락처 유도 |
| `FALSE_INFO` | 허위 정보 |
| `ETC` | 기타 |

> 사유는 **선택 입력**이라 `BookingReport.reason`이 nullable이다(미전송이면 `null`) — 카탈로그 자체에 "미선택"을 뜻하는 `code`를 두지 않는다(`ETC`는 사용자가 고른 "기타"이지 미선택이 아니다). 소규모 집합이라 사유 목록 조회는 페이지네이션 없이 활성 사유 전체를 한 번에 반환한다.
>
> **사유 라벨 번역** — 사유 목록 조회(`GET /api/v1/bookings/report-reasons`)의 표시 `label`은 **서버가 사용자 표시 언어의 카탈로그 라벨 행을 골라** 내려준다. 표시 언어는 `user` 공개 쿼리(`user :: api`의 `getLanguage(userId)`)로 동기 취득하며(`user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`을 언어 코드 문자열로 회신 — `booking`은 폴백 규칙을 알지 못한다), `diagnosis`·`gamification`·`lifetip`이 이미 쓰는 것과 **같은 경로**다. `user :: api`는 이미 `allowedDependencies`에 있으므로 **새 모듈 의존 엣지가 생기지 않는다**. 시드 언어는 `en`·`ko`·`ja` 3종이고, 사용자 표시 언어의 `(code, lang)` 행이 없으면 **`en` 행으로 폴백**한다. **`code`는 언어 무관 불변이고 `label`(행)만 언어별**이다(§4 `diagnosis` 문항 카탈로그가 `code`(UPPER_SNAKE)를 언어 무관으로 고정하고 표시 문자열만 언어별로 두는 것과 같은 원칙 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) Decision 6). 클라이언트는 언제나 `code`로 신고를 제출하고 `label`은 표시에만 쓴다. 계약은 그대로 `{ code, label }`이다.
>
> **왜 DB 카탈로그인가(enum·리소스 번들이 아니라):** 사유와 그 라벨은 `booking`이 소유하는 **MySQL 카탈로그 테이블 `booking_report_reasons`**(행: `code`·`lang`·`label`·표시순서·활성 플래그, `(code, lang)` 유니크)에 둔다. JVM enum·리소스 번들이 아니라 카탈로그에 두는 이유는 **코드 배포 없이 행 추가만으로** 사유든 언어든 동적으로 늘리기 위해서다 — 사유 6종이 늘거나 새 언어가 필요해도 스키마 변경 없이 행을 INSERT하면 된다(§4 `diagnosis`의 문항·선택지, §10 `lifetip`의 주제가 운영 중 콘텐츠를 저장소에 두는 것과 같은 결). `booking`이 이미 MySQL 저장소라(polyglot, [ADR-0005](../adr/0005-polyglot-persistence.md)) 별도 store를 추가하지 않고 같은 DB의 카탈로그 테이블로 처리한다.
>
> **에러 메시지 번들(`messages`)과 섞지 않는다:** 사유 라벨은 리소스 번들(`messages`)에 넣지 않는다 — `messages` 번들은 [ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)이 **에러 메시지 전용**으로 규정한다("키는 `ErrorCode` 이름", 검증 불변식은 "`messages.properties`의 키 집합 == `ErrorCode` 전체 상수", `Locale`은 `Accept-Language`/`LocaleContextHolder`로 결정). 신고 사유 라벨은 **본문 콘텐츠**라 표시 언어를 `getLanguage(userId)`에서 받으므로 두 경로가 섞여선 안 되고, `messages`에 넣으면 `ErrorCode`가 아닌 키가 섞여 **ADR-0030의 키 범위·커버리지 불변식이 깨진다**. 라벨을 카탈로그 테이블에 두는 것은 §2 `user` 협력이 이미 요구하는 **"에러 i18n과 콘텐츠 i18n 분리"** 를 저장 위치로 그대로 인코딩한다.
>
> ⚠️ **표시 언어는 `getLanguage(userId)`가 회신한 코드로 고른다** — `LocaleContextHolder`/`Accept-Language`가 아니다. §2 `user` 협력이 두 경로를 명시적으로 분리해 뒀다: `getLanguage`는 **본문 콘텐츠 번역에만** 쓰이고, **에러 메시지**는 `Accept-Language`/`LocaleContextHolder` 경로를 그대로 쓴다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)). 사유 `label` 행 선택은 본문 콘텐츠라 전자를 따르며(그 언어 행이 없으면 `en` 행 폴백), 에러 경로와 섞지 않는다.
>
> **일본어 라벨은 #169 구현 범위다:** 사유 6종 × `en`·`ko`·`ja` 3언어 = 시드 행을 카탈로그에 채운다(마이그레이션). 지원 언어 3종 전부에 라벨 행이 있어야 US-4-9의 정상 AC(사용자 표시 언어로 번역된 `label`)를 만족한다 — **`ja` 사용자에게 `en` 라벨이 나가는 것은 허용 상태가 아니라 `ja` 행 미시드 시의 실패 양상**이다. 물리 스키마·시드 마이그레이션은 [database-design](../database/database-design.md) 소관.
>
> `contractPeriod`는 enum이 아니라 **정수(개월수, 양의 정수)** 다 — `1`·`3`·`6`·`12`·`24` 등 자유 입력.

**협력 / 이벤트:** 세입자는 `user`, 매물·방 상품은 `listing`을 식별자로만 참조한다(ADR-0002, 엔티티 비공유). **생성 시점**에는 매물 존재·공개 검증을 `listing` 공개 쿼리(`listing::api`)로 받는다(부재/비공개 `404 LISTING_NOT_FOUND`; 예약은 세입자 전용이라 소유자 조회는 불요). **조회 시점**(목록·상세)에는 **스냅샷 없이 실시간 조인**한다 — 매물 요약·가격(`title`·`thumbnailUrl`·주소·`RoomOffer` name·`deposit`·`monthlyRent`)은 `listing::api`로 `(listingId, roomOfferId)`를 조회하고, 예약자 성명(`tenantName`)은 `user::api`(`getUserName(tenantId)`)로 조회해 애플리케이션 레벨에서 조합한다(cross-store 조인 금지, [ADR-0005](../adr/0005-polyglot-persistence.md); 가격 변경 시 상세는 현재가 기준). 총 금액은 조회 시점 계산값(`deposit + monthlyRent × contractPeriod`, 관리비 제외)이다. 두 조회 메서드(`listing::api`의 가격·매물요약 조회, `user::api`의 성명 조회)는 신규 공개 조회로 노출되며 이에 따라 `booking`의 `allowedDependencies`는 `{common, listing, user}`다. **임대인 조회 분기**(`userType=LANDLORD`)는 소유권을 `Booking.landlordId`로 판정하므로 `listing::api` 소유권 조회 메서드가 **불필요**하다 — 대신 예약 **생성** 시 소유자 캡처를 위해 `listing::api`의 매물 조회 뷰(`RoomOfferBookingView`)에 `landlordId`를 추가 노출하고, 임대인 **상세** 분기의 신청자 프로필 조회를 위해 `user::api`에 `getApplicantProfile`(성명·성별·국적·이메일)를 신규 공개 조회로 추가한다. 임대인에게 신청자 PII(이메일·성별·국적)는 **마스킹 없이 평문으로 노출**한다(제품 결정). **차단**은 `user`가 소유하는 `UserBlock`(§2)에 위임한다 — 목록·상세 필터가 쓸 차단 상대 식별자 집합은 `user::api`의 `findBlockedUserIds(요청자)`로, 신규 신청 가드의 양방향 판정은 `isBlockedBetween(요청자, 임대인)`으로 동기 조회해 **애플리케이션 레벨에서** 적용한다(차단 기록을 직접 조인하지 않는다, [ADR-0002](../adr/0002-inter-module-communication-via-events.md)). 차단 **생성**만은 경로가 `POST /bookings/{bookingId}/block`이라 상대를 예약에서 도출해야 하므로(`요청자 == tenantId ? landlordId : tenantId` — 클라이언트가 상대 식별자를 보내지 않는다) 엔드포인트는 `booking`에 두되 저장은 `user::api` 공개 명령으로 `user`에 위임한다 — `user`가 생성을 소유하면 상대를 도출하려 `user → booking`이 생기고 `booking → user :: api`가 이미 있어 **순환이 나 `ApplicationModules.verify()`가 깨진다**. 차단 목록·해제(`/api/v1/users/me/blocks`)는 예약과 무관하게 `user`가 제공한다(차단하면 그 예약이 목록에서 사라져 `bookingId`를 다시 얻을 수 없으므로 해제 경로가 예약에 매일 수 없다). 세 협력 모두 기존 `user :: api`를 쓰므로 `allowedDependencies`는 **`{common, listing, user}` 그대로**이고 새 의존 엣지가 없다. **예약 신고 접수**(`BookingReport`)는 대상 존재·참여자 검증이 예약 내부 정보라 모듈 안에서 완결되며 `report`(§9)를 호출하지 않는다(대상이 겹치지 않는다 — 위 `BookingReport` 참조).

**후속·이연(1차 MVP 제외):** 신청 성공 시 **`BookingCreatedEvent`** 를 발행해(페이로드: `bookingId`·`listingId`·`tenantId`·`landlordId`·`moveInDate`·`contractPeriod`·첫 인사 메시지) `chat`이 구독하고 임대인 채팅방 보장·예약 카드(`BOOKING_CARD`) 고정·첫 인사 전송을 처리하는 채팅 연동 설계는 **1차 MVP 범위 밖으로 이연**한다(설계 보존, 삭제 아님). `booking`은 `chat`을 알지 못한다(단방향).

---

## 6. `chat` — 인앱 채팅

> [API 스펙](../api/specs/04-booking-inquiry-chat.md) · [시퀀스](sequence-diagrams/04-booking-inquiry-chat/README.md) · `allowedDependencies = {common, booking}`(이벤트 구독 목적)
>
> **[후속·이연 · 1차 MVP 제외]** `chat`(문의·인앱 채팅: 채팅방·메시지·읽음·예약/매물 카드) 전체는 매물 예약(신청, US-4-1·US-4-2)과 분리되어 후속으로 이연된다(설계 보존, 삭제 아님). `booking`은 예약 저장·조회만 담당하며 `chat`을 알지 못한다(단방향).

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
| | `contractPeriod` | int | 계약 기간(개월수) |
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

**협력 / 이벤트:** 타 애그리거트는 식별자(`authorId`·`userId`)로만 참조한다(ADR-0002). 작성자 표시정보(닉네임·국적)는 `user` 공개 쿼리로 조립하며, 탈퇴 작성자는 닉네임 `(탈퇴한 사용자)`·국적 비움으로 마스킹. 동네친구 1:1 채팅 시작은 `chat`의 `NEIGHBOR` 방 보장에 위임(게시글 작성자를 상대로 전달, 기존 방이면 멱등 반환). 본인 글 채팅 불가(`422 POST_CHAT_SELF_NOT_ALLOWED`), 작성자 불가(`422 POST_CHAT_AUTHOR_UNAVAILABLE`), 차단 관계(`403 POST_CHAT_BLOCKED`)는 차단한다 — 이때 볼 차단 모델은 `user`가 소유하는 `user_blocks`(**사용자 단위 전역 차단**, §2 `UserBlock`)다. 다만 **이 채팅 시작 가드는 아직 배선되지 않았다(후속·이연)** — `community`의 `allowedDependencies`는 `{common}`이라 차단을 조회할 수 없고, §2 `UserBlock` 협력이 지금 열어 둔 소비자는 `booking` 하나뿐이다. 구현하려면 **`community → user :: api` 의존 신설(미도입)** 이 선행한다(어떤 공개 쿼리를 쓸지 — `isBlockedBetween(요청자, 작성자)` 양방향 판정 여부 — 는 확인 필요).

---

## 8. `gamification` — 퀴즈

> [API 스펙](../api/specs/06-gamification.md) · [시퀀스](sequence-diagrams/06-gamification/README.md) · `allowedDependencies = {common, user}`(번역용 표시 언어 조회 `getLanguage`) · **1차 MVP 이후**

외국인 임차인을 **주 독자**로 삼는 학습형 퀴즈를 제공한다(다만 접근에 역할·상태 게이트는 없다 — 아래 게스트 노트) — 요청마다 활성 풀에서 4지선다 퀴즈 1개를 **랜덤 선정**해 사용자 언어로 번역해 내려주고, 사용자가 보기를 선택해 답하면 서버가 저장된 정답과 대조해 채점한다. **무제한 재응시·무상태**로, 제출·적립·이력·이벤트를 남기지 않으며(멱등·재응시 가능), 하루 1회 제한이나 오늘의 퀴즈·포인트 개념은 없다.

**`Quiz`** — 활성 풀에서 랜덤 노출되는 4지선다 퀴즈 콘텐츠(콘텐츠 카탈로그 애그리거트 루트). 식별자 `id`만 가지며(날짜·비즈니스 키 없음). [값 객체: `QuizChoice`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(`quizId`) |
| `question` | 인라인 언어-키 맵 | 문제 본문 — `{ "en": .., "ja": .., "ko": .. }` 언어 코드를 키로 하는 맵. 서버가 `getLanguage`로 얻은 표시 언어 키로 선택(부재 시 영어(`en`) 폴백) |
| `choices` | `List<QuizChoice>` | 4지선다 보기(키 A~D, 보기 텍스트). 키 A~D는 언어 무관 |
| `correctChoice` | enum `ChoiceKey` | 정답 보기 키. `GET random`에는 절대 포함하지 않고 **오답 응답에만** 반환 |
| `explanation` | 인라인 언어-키 맵 | 해설(정답 근거·오답 사유) — `{ "en": .., "ja": .. }` 언어-키 맵. 서버가 표시 언어 키로 선택(부재 시 `en` 폴백), **채점 응답에 반환(정답·오답 공통)** |
| `active` | boolean | 랜덤 풀 게이팅(`true`인 퀴즈만 랜덤 선정 대상) |

**불변식:** `choices`는 정확히 4개·키 `A`·`B`·`C`·`D` 각 1개(중복·누락 없음); `correctChoice`는 `choices` 키 집합에 포함; 보기 키 A~D는 **언어 무관**(채점은 키로 판정); `question`·`choices[].text`·`explanation`은 **인라인 언어-키 맵**으로 저장하고 서버가 `getLanguage` 표시 언어 키로 골라 응답(해당 언어 키 부재 시 영어(`en`) 폴백; diagnosis와 동일 i18n 경로); **채점은 무상태** — 서버가 `selectedChoice`를 `Quiz.correctChoice`와 대조해 정답이면 `{ quizId, selectedChoice, correct:true, explanation }`, 오답이면 `{ quizId, selectedChoice, correct:false, correctChoice, explanation }`을 반환하며 제출·적립·이력·이벤트를 남기지 않는다(멱등·재응시 가능); `selectedChoice`는 A~D 중 하나(그 외 `400 INVALID_INPUT`); `GET random`에는 `correctChoice`·`explanation`을 포함하지 않는다(채점 응답에서만 노출 — `explanation`은 정답·오답 모두 반환, `correctChoice`는 오답 응답에만); `quizId`가 없거나 활성 풀이 공백이면 `404 QUIZ_NOT_FOUND`; **접근에 역할·상태 게이트가 없다** — `SecurityConfig`가 `/api/v1/quizzes/**`를 `permitAll`로 열고 응용 계층에도 세입자 전용 검사가 없어(#181로 `userType=TENANT` 게이트와 그 `403 FORBIDDEN`을 제거) 비로그인 게스트·세입자·임대인·온보딩 미완료 사용자가 모두 호출할 수 있다. "랜덤"은 활성 풀에서의 **랜덤 선정**을 뜻하고 동적 생성이 아니다 **(확인 필요)**.

> **게스트(비회원) 조회 허용 — 이 컨텍스트는 신원을 쓰지 않는다(#181)**: 퀴즈는 **로그인 없이** 조회·채점할 수 있다. `Quiz`는 애초에 사용자 상태를 갖지 않고(무상태 채점 — 제출·적립·이력 없음) 게스트 경로에서 신원의 소비자가 **표시 언어 결정 하나뿐**이라, 표시 언어는 `getLanguage`를 **호출하지 않고 `en` 고정**으로 조립한다(통과시키는 것이 아니라 아예 묻지 않는다 — 게스트에겐 조회할 회원 레코드가 없다). **세입자 전용 게이트(`assertTenant` → `TenantOnlyException`)는 제거했다** — `permitAll`로 게스트에게 열린 콘텐츠를 로그인한 임대인만 `403 FORBIDDEN`으로 막는 것은 앞뒤가 맞지 않고 실효도 없기 때문이다(임대인이 로그아웃하면 그대로 볼 수 있다). **비-`ACTIVE` 차단도 사라진다** — 온보딩 미완료(`PENDING`/`TERMS_AGREED`) 토큰은 `403 AUTH_ONBOARDING_REQUIRED`에 도달하지 않는다(그 차단은 `hasRole("USER")`가 하던 것인데 `permitAll`로 열리며 없어진다 — 의도적 수용). 즉 **이 두 엔드포인트에는 어떤 역할·상태 게이트도 남지 않는다.**
>
> | 호출자 | 결과 | 표시 언어 |
> | --- | --- | --- |
> | 비로그인 게스트 | `200` | `en` 고정(`getLanguage` 미호출) |
> | 세입자(`TENANT`·`ACTIVE`) | `200` | `users.lang`(온보딩에서 `lang`이 선택이라 미설정이면 `en`) |
> | 임대인(`LANDLORD`) | `200` — **종전 `403 FORBIDDEN`에서 변경** | `users.lang` = **`ko` 고정** → 퀴즈를 **한국어로 본다**. 임대인은 온보딩 시 서버가 `lang`=`ko`를 심고 프로필 수정도 `lang`을 읽지 않아 값이 바뀌지 않는다(§2) — 진단과 동일 |
> | 온보딩 미완료(`PENDING`/`TERMS_AGREED`) | `200` | `users.lang`(온보딩 전이라 미설정 → `en`) |
>
> 토큰을 **보냈는데 만료된** 요청만 게스트로 강등하지 않고 `401 TOKEN_EXPIRED`를 유지한다([error-response-guide](../api/error-response-guide.md) §4).

**값 객체(VO):**

| 이름 | 속성 | 타입 | 설명 |
| --- | --- | --- | --- |
| `QuizChoice` | `key` | enum `ChoiceKey` | 4지선다 보기 키(A~D). 동일 `Quiz` 내 유일, **언어 무관**(채점 기준) |
| | `text` | 인라인 언어-키 맵 | 보기 텍스트 — `{ "en": .., "ja": .. }` 언어-키 맵(각 언어 값 비어 있지 않음). 서버가 `getLanguage` 표시 언어 키로 선택(부재 시 `en` 폴백) |

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ChoiceKey` | `A` | 4지선다 첫 번째 보기(코드는 언어 무관) |
| | `B` | 두 번째 보기 |
| | `C` | 세 번째 보기 |
| | `D` | 네 번째 보기 |

> 퀴즈 콘텐츠는 `Quiz` 애그리거트를 **MongoDB `quizzes` 컬렉션**(`diagnosisQuestions`와 동종의 문서 카탈로그, ADR-0005 폴리글랏 — 퀴즈=콘텐츠/문서 → MongoDB)에 보유한다. 표시 문자열(`question`·`choices[].text`·`explanation`)은 분리 컬렉션 없이 **같은 도큐먼트 안에 인라인 언어-키 맵으로 임베드**하고(언어 코드 키), 보기 키 `A`~`D`(`ChoiceKey`)는 언어 무관 불변으로 채점 기준이다. `active` 불리언이 랜덤 풀을 게이트하며, 시드는 정본 시드 JSON 주입으로 적재한다. 제출 테이블·포인트 테이블은 없다(무상태 채점).

**협력 / 이벤트:** 채점 귀속 주체는 `user`를 식별자(`userId`)로만 참조한다(ADR-0002). 번역에 쓸 **표시 언어는 `user` 공개 쿼리(`getLanguage`)로 동기 취득**한다(`user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 회신 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) — `user`를 식별자/원시 값으로만 참조하고 엔티티를 공유하지 않는다(ADR-0002 Decision 5). 이로써 **모듈 의존 `gamification → user`를 추가**한다(위 `allowedDependencies` 항목). 퀴즈 콘텐츠는 **MongoDB 문서 카탈로그**(`diagnosisQuestions`와 동종, ADR-0005)로 제공하며, 채점은 **무상태**라 제출·적립·이벤트를 남기지 않는다.

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

## 10. `lifetip` — 생활 팁(주제별 생활 정보)

> [API 스펙](../api/specs/08-life-tips.md) · [시퀀스](sequence-diagrams/08-life-tips/README.md) · `allowedDependencies = {common, user}`(번역용 표시 언어 조회 `getLanguage`) · **1차 MVP 이후**

외국인 세입자를 **주 독자**로 삼아 한국 생활에 필요한 정보를 **주제(topic)** 별로 묶어 조회하는 **읽기 전용** 큐레이션 컨텍스트다(홈 부가 기능; 접근에 역할·상태 게이트는 없다 — 아래 게스트 노트). 사용자는 먼저 주제 목록을 보고(US-8-1), 특정 주제를 고르면 그 주제에 속한 생활 팁(**제목 · 내용 · 사진**) 전체 리스트를 받는다(US-8-2). 콘텐츠는 운영이 시드로 적재하는 큐레이션 콘텐츠이며 사용자 작성·수정·좋아요·신고가 없다(UGC인 `community`(7절)와 구분). 주제·팁의 표시 텍스트(주제명·주제 설명·제목·내용)는 **사용자 표시 언어**로 번역해 내려주며(US-8-3), 진단 i18n과 **완전히 동일한 전략**을 재사용한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md), US-2-6) — 표시 문자열을 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드하고, 서버가 `user` 공개 query `getLanguage(userId)`로 취득한 언어 키(사용자가 고른 표시 언어 `users.lang`이 있으면 그 값, 없으면 `en`)로 문자열을 골라 조립하며 해당 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). 식별자(`code`/`id`)와 이미지 URL(주제의 `LifeTipTopic.imageUrl`·`backgroundImageUrl`, 팁의 `LifeTip.imageUrl` 사진)은 언어 무관 불변이고 표시 텍스트만 언어별이다. 문서형·언어-키 맵 임베드 특성상 **MongoDB**에 둔다([ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏; 진단 카탈로그 저장 방식([ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md))과 정합).

**`LifeTipTopic`** — 생활 팁을 묶는 주제(애그리거트 루트). 운영이 적재한 큐레이션 카탈로그로, 언어 무관 식별 `code`(UPPER_SNAKE)와 노출 순서(`order`)를 가지며 표시 텍스트(표시명 `name`·짧은 설명 `shortDescription`·긴 설명 `longDescription`)는 언어-키 맵으로 임베드되고, 화면에 그릴 이미지(홈 카드용 `imageUrl`·상세 상단 배경용 `backgroundImageUrl`)는 언어 무관 절대 CDN URL이다. 식별자 `code`(주제 코드, 언어 무관 불변).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `code` | 식별자(String) | 주제 코드(UPPER_SNAKE, 언어 무관 불변 식별자). 예: `MOVING_IN`·`ADMINISTRATION`·`TRANSPORT`·`FINANCE`·`HOUSING`. US-8-2에서 특정 주제의 팁을 지정하는 path 키로 쓰인다 |
| `name` | 언어-키 맵 | 표시명(번역 대상). `{ "en": …, "ja": …, "ko": … }` 인라인 언어-키 맵 — 서버가 사용자 언어 키로 선택(부재 시 `en` 폴백) |
| `shortDescription` | 언어-키 맵 | 짧은 설명(번역 대상). `{ "en": …, "ja": …, "ko": … }` 인라인 언어-키 맵 — 서버가 사용자 언어 키로 선택(부재 시 `en` 폴백). 홈 화면 주제 카드 문구, **필수(NOT NULL)** |
| `longDescription` | 언어-키 맵 | 긴 설명(번역 대상). 인라인 언어-키 맵(서버가 사용자 언어 키로 선택, 부재 시 `en` 폴백). 주제 상세 상단 문구, **필수(NOT NULL)** |
| `imageUrl` | String | 주제 카드 이미지 URL(언어 무관 절대 CDN URL, 불변). 홈 화면 카드에 그린다. 팁 사진(`LifeTip.imageUrl`, nullable)과 **다른 리소스**이며 **필수(NOT NULL)** |
| `backgroundImageUrl` | String | 주제 상세 상단 배경 이미지 URL(언어 무관 절대 CDN URL, 불변). 상세 화면 상단에 그린다. **필수(NOT NULL)** |
| `order` | int | 노출 순서(오름차순, 내부 정렬값 — 응답에 노출하지 않음) |

**불변식:** `code`는 전 주제에 걸쳐 유일(UPPER_SNAKE, 언어 무관 불변); 목록은 `order` 오름차순으로 노출하고 고정·소규모 카탈로그라 페이지네이션 없이 전체 배열을 한 번에 반환한다(비페이지 — api-design-guide §4 목록 규약 미적용, US-7-3 신고 사유 카탈로그와 동일 성격); `name`·`shortDescription`·`longDescription`은 표시 문자열만 언어별이고 `code`·`order`·`imageUrl`·`backgroundImageUrl`는 언어 무관; 표시 텍스트 3종(`name`·`shortDescription`·`longDescription`)과 이미지 2종(`imageUrl`·`backgroundImageUrl`)은 모두 **필수(NOT NULL)** — 홈 카드는 `imageUrl`+`shortDescription`, 상세 상단은 `backgroundImageUrl`+`longDescription`을 항상 그리므로 팁의 `LifeTip.imageUrl`(nullable, "사진 없는 팁" 허용)과 달리 "이미지·설명 없는 주제" 경계 케이스를 두지 않는다; 존재하지 않는 주제 `code`로 팁을 조회하면 `404 LIFE_TIP_TOPIC_NOT_FOUND`(신규 도메인 에러코드 — `ErrorCode` 등록 필요, `*_NOT_FOUND` 규약).

**`LifeTip`** — 하나의 주제에 속한 생활 팁 항목(애그리거트 루트). 주제 : 팁 = **1 : N**. `title`·`content`는 언어-키 맵으로 임베드되고 `imageUrl`은 언어 무관(사진)이다. 식별자 `id`, 소속 주제 참조 `topicCode`(→ `LifeTipTopic.code`, 애플리케이션 레벨 조인·DB 조인 없음).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 팁 식별자(언어 무관 불변) |
| `topicCode` | String | 소속 주제 코드 → `LifeTipTopic` 식별자(`code`) 참조(애플리케이션 레벨 조인, DB 조인 없음) |
| `order` | int | 주제 내 노출 순서(오름차순) |
| `title` | 언어-키 맵 | 제목(번역 대상). `{ "en": …, "ja": …, "ko": … }` — 서버가 사용자 언어 키로 선택(부재 시 `en` 폴백) |
| `content` | 언어-키 맵 | 내용(번역 대상). 인라인 언어-키 맵(서버가 사용자 언어 키로 선택, 부재 시 `en` 폴백) |
| `imageUrl` | String, nullable | 사진 URL(언어 무관). 사진이 없으면 `null`(또는 응답에서 생략) |

**불변식:** `topicCode`는 실재하는 `LifeTipTopic.code`를 가리켜야 한다(참조 무결성 — 없는 주제 코드로 조회 시 `404 LIFE_TIP_TOPIC_NOT_FOUND`); 한 주제의 팁 목록은 `(topicCode, order)`로 노출 순서를 정하며, 주제당 팁 수가 제한적이라 페이지네이션 없이 전체 리스트를 한 번에 반환한다(비페이지 — "해당 주제에 맞는 제목-내용-사진의 모든 리스트"); `title`·`content`는 표시 문자열만 언어별이고 `id`·`imageUrl`은 언어 무관 불변; `imageUrl`이 없는 팁은 `null`로 두되 나머지 필드(`title`·`content`)는 정상 노출한다; 응답 스키마는 언어와 무관하게 동일하고 서버가 언어 문자열만 채운다.

> `LifeTipTopic`·`LifeTip` 모두 운영이 시드로 적재하는 큐레이션 카탈로그다(사용자 생성 콘텐츠 아님). 시드는 진단 카탈로그와 동일하게 운영자가 정본 JSON을 `lifeTipTopics`/`lifeTips` 컬렉션에 주입한다([ADR-0032](../adr/0032-mongodb-migration-runner.md) §4). 컬렉션·인덱스 등 영속 매핑(`lifeTipTopics { order: 1 }`, `lifeTips { topicCode: 1, order: 1 }` 복합)은 [database-design](../database/database-design.md) 소관이라 여기서 다루지 않는다.

**i18n(진단과 동일 전략):** 번역 기준은 **사용자 표시 언어**이고, `user` 공개 query `getLanguage(userId)`를 **동기 호출**해 취득한다(`user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 회신 — `lifetip`은 그 규칙을 알지 못한다; `Accept-Language`·토큰 클레임 미사용; [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141), US-2-6 일관). 표시 문자열(`name`/`title`/`content`)은 별도 메시지 컬렉션·키 없이 주제·팁 도큐먼트 안 **인라인 언어-키 맵**으로 임베드하고, 서버가 사용자 언어 키로 문자열을 고르되 그 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). 식별자(`code`/`id`)·`imageUrl`은 언어 무관 불변이라 응답 스키마는 언어와 무관하게 동일하다(서버가 언어 문자열만 채운다). 진단 문항 라벨(`diagnosisQuestions`의 인라인 언어-키 맵)과 같은 부류로, 새 i18n 메커니즘을 만들지 않는다.

**협력 / 이벤트:** 타 애그리거트·타 모듈은 식별자/원시 값으로만 참조한다(엔티티 비공유, ADR-0002) — 표시 언어 결정에 쓸 `user` 공개 쿼리(`getLanguage`)를 **동기 취득**하고(`user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 회신 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), `user`를 식별자/원시 값으로만 참조한다. 주제-팁 참조는 `LifeTip.topicCode → LifeTipTopic.code`의 **애플리케이션 레벨 조인**(DB 조인 없음)으로 처리한다. **읽기 전용 컨텍스트**라 상태 전이·불변식 위반 외 부작용이 없고, 발행하거나 구독하는 도메인 이벤트가 없다. 주 독자는 외국인 세입자지만 **접근에 역할·상태 게이트는 없다** — 조회는 `permitAll`이라 비로그인 게스트도 부를 수 있고(#181), 임대인(`userType=LANDLORD`)·온보딩 미완료(PENDING/TERMS_AGREED) 사용자도 `200`이다. 한때 계획했던 `TENANT` 게이트는 **신설하지 않고 폐기**했으며(`gamification`(퀴즈)의 세입자 게이트도 같은 이유로 제거 — 근거·호출자별 표는 §8 게스트 노트), 토큰을 보냈는데 만료된 요청만 `401 TOKEN_EXPIRED`로 남는다. 이는 세입자/임대인 게이트를 두지 않는 `diagnosis`(진단)와 같은 결론이며, 임대인은 `lang`=`ko` 고정이라 진단과 마찬가지로 **생활 팁도 한국어로 본다**.

> **게스트(비회원) 조회 허용 — 이 컨텍스트도 신원을 쓰지 않는다(#181)**: 주제·팁 조회는 **로그인 없이** 가능하다. 읽기 전용 큐레이션이라 애그리거트에 사용자 필드가 없고 게스트 경로에서 신원의 소비자가 **표시 언어 결정 하나뿐**이라, 퀴즈(§8)와 같은 방식으로 표시 언어는 `getLanguage`를 **호출하지 않고 `en` 고정**이다. 따라서 `401 UNAUTHENTICATED`·`403 AUTH_ONBOARDING_REQUIRED`는 **이 엔드포인트들에서 도달 불가**가 된다(코드 목록에서 삭제되는 것이 아니라 여기서 나오지 않는다 — [error-response-guide](../api/error-response-guide.md) §4). **토큰을 보냈는데 만료된 요청만 게스트로 강등하지 않고 `401 TOKEN_EXPIRED`를 유지**한다. 세입자 전용 게이트는 제거돼 **임대인도 `200`**이며(종전 `403 FORBIDDEN`에서 변경), 호출자별 결과·표시 언어는 퀴즈와 동일하므로 §8의 표를 그대로 따른다.

- **`allowedDependencies`** — 표시 텍스트 번역이 표시 언어를 `user` 공개 쿼리(`getLanguage`)로 동기 취득하므로 `lifetip`의 `allowedDependencies`는 **`user`를 포함**한다(즉 `{common, user}` — 진단과 동일 근거: [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5, [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md)). 이는 `package-info.java`/`@ApplicationModule`에 반영된다. **1차 MVP 이후**(홈 부가 기능)이며 읽기 전용이라 발행·구독 도메인 이벤트는 없다.

---

## 관련 문서

- [system-overview](system-overview.md) · [sequence-diagrams](sequence-diagrams/README.md) — 구성/흐름 뷰
- [ADR-0001](../adr/0001-bounded-context-module-decomposition.md)(모듈 분해) · [ADR-0002](../adr/0002-inter-module-communication-via-events.md)(이벤트·식별자 참조)
- [database-design](../database/database-design.md)(영속 매핑 — 저장소·물리 스키마) · [code-style §3](../convention/code-style.md)(계층·포트/어댑터)
- [api/specs](../api/specs/README.md) · [api-design-guide](../api/api-design-guide.md) · [error-response-guide](../api/error-response-guide.md) · [requirements/user-stories](../requirements/user-stories.md)
