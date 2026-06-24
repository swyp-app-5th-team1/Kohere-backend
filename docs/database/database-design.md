# 데이터베이스 설계 (Database Design)

> Kohere 백엔드의 **영속(물리 스키마) 정본**이다. 폴리글랏 영속([ADR-0005](../adr/0005-polyglot-persistence.md)·[ADR-0006](../adr/0006-refresh-token-store-redis.md))이라 단일 ERD로 묶지 않고 **스토어별로 표기**한다.
>
> - **관계형(MySQL)** → 테이블(컬럼·키·인덱스; 관계는 FK 컬럼·비고로)
> - **문서(MongoDB)** → 컬렉션 스키마(필드·임베드·인덱스, `2dsphere` 포함)
> - **키-값(Redis)** → 키스페이스(키 패턴·자료구조·TTL)
> - **저장소(추후 결정)** → 논리 스키마(논리 타입; store 확정 시 물리화)
>
> **설계-우선(design-first)**: 도메인 설계가 먼저 정의되고 코드가 그것을 구현한다. 이 문서는 [domain-model](../architecture/domain-model.md)의 애그리거트·속성·불변식을 **물리 스키마로 매핑**한 것이다(현재 코드 상태가 아니라 구현 목표). 필드의 **의미·도메인 타입·enum 값**은 domain-model이 정본이고, 여기선 **물리 타입·키·인덱스·제약**만 둔다(중복 방지).

## 1. 스토어 배치 (요약)

| 모듈 | 스토어 | 테이블/컬렉션(키스페이스) | MVP |
| --- | --- | --- | --- |
| [`auth`](#4-1-auth) | **Redis** + **MySQL** | `refresh:{tokenHash}`·`refresh:user:{userId}`·`email-verify:code:{userId}`·`email-verify:verified:{userId}` / `social_accounts` | ✅ |
| [`user`](#4-2-user) | **MySQL** | `users`·`countries`·`nickname_adjectives`·`nickname_nouns` | ✅ |
| [`listing`](#4-3-listing) | **MongoDB** | `listings`·`favorites`·`recentListings` | ✅ |
| [`diagnosis`](#4-4-diagnosis) | **MongoDB** | `diagnoses`(제출 결과)·`diagnosisQuestions`(문항·선택지 카탈로그)·`diagnosisSuggestions`(추천 조정 제안) — 인라인 언어-키 맵 번역, US-2-5·US-2-6 | ✅ |
| [`booking`](#4-5-booking) | **저장소(추후 결정)** | `bookings` | ✅ |
| [`chat`](#4-6-chat) | **저장소(추후 결정)** | `chat_rooms`·`messages`·`chat_room_members` | ✅ |
| [`community`](#4-7-community) | **MySQL** | `posts`·`comments`·`post_likes`·`post_hashtags` | 이후 |
| [`gamification`](#4-8-gamification) | **저장소(추후 결정)** | `quizzes`(+`quiz_choices`)·`quiz_submissions`·`point_histories` | 이후 |
| [`report`](#4-9-report) | **저장소(추후 결정)** | `reports` | 이후 |

> `access` 토큰은 무상태 JWT라 저장소에 없다. `common`은 공유 커널(스키마 없음).

## 2. 설계 규약

### 2-1. 명명 규칙

| 스토어 | 컨테이너 | 필드/키 |
| --- | --- | --- |
| MySQL | 테이블 `snake_case` 복수형(`users`, `post_likes`) | 컬럼 `snake_case`(`user_id`, `created_at`) |
| MongoDB | 컬렉션 lowerCamel 복수형(`listings`, `recentListings`) | 필드 `camelCase`(`userId`, `monthlyBudgetMax`) |
| Redis | 키 colon 네임스페이스(`refresh:{tokenHash}`, `refresh:user:{userId}`) | — |

- URL/JSON은 [api-design-guide](../api/api-design-guide.md)대로 `camelCase`/`kebab-case`. DB 컬럼명과 API 필드명이 다르면(`move_in_date`↔`moveInDate`) 어댑터에서 매핑한다.
- domain-model의 값 객체(VO)는 임베드형 스토어(MongoDB)에선 **임베드 객체**로, 관계형(MySQL)에선 **루트 테이블의 컬럼 묶음**으로 평탄화해 매핑한다(예: `FullName`→`first_name`/`last_name`).

### 2-2. 공통 컬럼 표준

| 컬럼 | 적용 | 비고 |
| --- | --- | --- |
| 식별자 | MySQL `id BIGINT PK AUTO_INCREMENT` · Mongo `_id ObjectId` · Redis 키 자체 | 외부 노출 식별자는 store별 네이티브 타입을 따른다 |
| 생성시각 | 전 애그리거트 `created_at`/`createdAt`(또는 의미상 `submitted_at` 등) (UTC) | MySQL `DATETIME(6)` / Mongo ISODate |
| 수정시각 | **가변** 애그리거트 `updated_at`/`updatedAt` | `user`·`listings`·`posts` 등 |
| 소프트삭제 | **`community`만** `deleted`+`deleted_at` | 그 외는 상태 enum으로 표현(`user.status=WITHDRAWN`, `listing.published=false`, `booking.status=CANCELED`) |

### 2-3. 데이터 타입 가이드

| 용도 | MySQL | MongoDB | 논리(추후결정) |
| --- | --- | --- | --- |
| 식별자 | `BIGINT` | `ObjectId` | `long` |
| 짧은 문자열/enum | `VARCHAR(n)` | `string` | `string`/`enum` |
| 장문 | `TEXT` | `string` | `text` |
| 날짜(시각 없음) | `DATE` | ISODate(date) | `date` |
| 시각(UTC) | `DATETIME(6)` | ISODate | `datetime` |
| 불리언 | `BOOLEAN`(`TINYINT(1)`) | `bool` | `bool` |
| 금액(KRW)·카운트·포인트 | `INT`/`BIGINT` | `int` | `int` |
| 값 객체(VO) | 컬럼 묶음으로 평탄화 | 임베드 객체 | 임베드/평탄화 |
| 좌표 | — | GeoJSON `Point`(`[lng,lat]`) + `2dsphere` | — |

- **enum**: 문자열 **UPPER_SNAKE** 저장. MySQL 네이티브 `ENUM` 미사용(값 추가 진화 용이). 값 카탈로그는 [domain-model](../architecture/domain-model.md) 각 모듈 "상태(enum)".
- **금액**: 원(KRW) 정수, 소수점 없음. **포인트**(`gamification`)는 정수이되 KRW 금액이 아니다.
- **시각**: UTC ISO-8601, 저장도 UTC([api-design-guide §6](../api/api-design-guide.md)).

### 2-4. 제약·무결성 (공통)

- **FK는 같은 모듈 안에서만.** 교차 모듈 참조는 **store가 같아도 FK 금지** — 식별자 값만 보유한다(Modulith 독립성·[ADR-0002](../adr/0002-inter-module-communication-via-events.md)). 교차 스토어 조인·FK·분산 트랜잭션 금지([ADR-0005](../adr/0005-polyglot-persistence.md) D5·D6) → 애플리케이션 레벨 조인/이벤트.
- **유니크 제약은 도메인 불변식대로**: `social_accounts(provider,provider_user_id)` · `users(nickname)` · `nickname_adjectives(word)` · `nickname_nouns(word)` · `favorites(userId,listingId)` · `post_likes(post_id,user_id)` · `quiz_submissions(user_id,quiz_date)` · `reports(reporter_id,target_type,target_id)` · `chat_rooms(listing_id,tenant_id,landlord_id)`.
- **카운트 정합**(`community` like/comment/share, `listings.favoriteCount`)은 단일 store 트랜잭션 또는 원자적 증감 + 배치 재계산으로 유지(음수 방지).
- **민감정보**(비자·이메일·토큰 원문·인증번호 원문)는 응답·로그 마스킹([error-response-guide §6](../api/error-response-guide.md)). 컬럼 암호화 여부는 [§6](#6-결정-필요-open-questions).

## 3. 마이그레이션

- **MySQL**: Flyway(`flyway-core`,`flyway-mysql`) 후보 — DDL 버전 관리. 정본 [migration-policy](./migration-policy.md).
- **MongoDB**: 스키마리스 + 애플리케이션 레벨 버전 필드([ADR-0005](../adr/0005-polyglot-persistence.md) D7). 인덱스(`2dsphere`·TTL 등)는 부트스트랩/마이그레이션 스크립트로 보장.
- **Redis**: 스키마 없음. 키 설계·TTL은 코드 상수로 관리([ADR-0006](../adr/0006-refresh-token-store-redis.md)).

## 4. 모듈별 스키마 (Data Model by Module)

> 표기: 각 스토어 스키마를 **표**(필드·타입·키/제약) + 인덱스 + 비고로 적는다. 필드 의미·도메인 타입은 [domain-model](../architecture/domain-model.md)이 정본이고 여기선 물리 타입·키·제약을 둔다. 관계는 FK 컬럼·비고로 표기하며, **교차 모듈/스토어 참조는 FK 없이 식별자 값 참조**다.

### 4-1. `auth`

> 스토어: **Redis**(refresh, [ADR-0006](../adr/0006-refresh-token-store-redis.md)) + **MySQL**(소셜 연동). domain-model: `RefreshToken`·`SocialAccount`.

#### (A) Redis — refresh 토큰

[ADR-0006](../adr/0006-refresh-token-store-redis.md) Decision대로. 불투명 토큰을 **SHA-256(+pepper)** 해시로 키에 담고, 값은 토큰 메타, TTL=만료 시각으로 자동 소멸. 사용자 인덱스 Set으로 일괄 무효화. domain-model `RefreshTokenStatus`(`ACTIVE`/`ROTATED`/`REVOKED`)는 **값의 `status` 필드로 저장**하고, 회전·무효화는 키 삭제가 아니라 **`status` 전이**로 처리한다 — ROTATED/REVOKED 레코드를 만료(TTL)까지 보존해야 **재사용 탐지**(폐기된 토큰 재제출 감지)가 가능하다. 토큰 식별은 키(`tokenHash`)가 곧 애그리거트 식별이다(domain `RefreshToken.id` ≡ `tokenHash`).

**키스페이스** (Redis · AWS ElastiCache, AOF+복제)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `refresh:{tokenHash}` | Hash | `userId`(→ users.id) · `status`(enum `ACTIVE`/`ROTATED`/`REVOKED`) · `issuedAt` · `expiresAt` | refresh 만료까지 | reissue 시 해시 조회 → `status`로 유효/회전/무효 판정. 회전 시 기존 `status=ROTATED` 전이 + 신규 `ACTIVE` 발급 |
| `refresh:user:{userId}` | Set | member = 사용자 보유 `{tokenHash}`들 | refresh 만료(±여유) | 사용자별 일괄 무효화(탈퇴·재사용 탐지) 대상 추적 |

> Redis 자료구조(Hash 또는 String(JSON))는 동등한 구현 선택이다. 원문 토큰은 보관·로그하지 않는다(해시만). **TTL=만료**라 폐기 레코드도 자동 정리된다.
>
> - **회전(reissue)**: 제출 토큰을 조회해 `status=ACTIVE`면 `ROTATED`로 전이(보존) + 새 `ACTIVE` 발급.
> - **재사용 탐지**: 조회된 토큰의 `status`가 `ROTATED`/`REVOKED`(이미 폐기)면 탈취 정황 → `refresh:user:{userId}`의 전 토큰을 `REVOKED`로 전이(일괄 무효화) 후 거부(`401 AUTH_INVALID_REFRESH_TOKEN`).
> - **로그아웃/탈퇴**: 해당 토큰(들)을 `REVOKED`로 전이(멱등). 보존은 TTL로 자동 만료.
> - 이는 [ADR-0006](../adr/0006-refresh-token-store-redis.md)의 **재사용 탐지 목표**를 실현한다(ADR의 `revoked` 플래그를 3-상태 `status`로 일반화 — ADR의 "회전 시 기존 키 삭제"는 폐기 토큰을 못 남겨 재사용 탐지가 불가하므로 status 전이로 대체).

#### (A-2) Redis — 이메일 인증(`EmailVerification`)

온보딩 중 이메일 소유 확인. 인증번호는 **SHA-256(+pepper)** 해시로만 보관(원문 미보관)하고, TTL로 자동 소멸한다. 사용자(온보딩 중 PENDING) 단위로 1건이다. domain-model `EmailVerification`.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `email-verify:code:{userId}` | Hash | `email` · `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` · `status`(enum `PENDING`/`VERIFIED`) | 인증번호 만료(예: 5분 — 확인 필요) | 인증번호 발송·검증. 검증 시 `codeHash` 대조 + `attempts` 증가, 일치 시 `VERIFIED` 전이 |
| `email-verify:verified:{userId}` | String/Hash | 검증 완료 `email` | 온보딩 토큰 만료(예: 30분 — 확인 필요) | 온보딩 제출 시 제출 `email`과 대조해 미인증 거름(`AUTH_EMAIL_NOT_VERIFIED`) |

- **발송**: 인증번호 메일을 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SES/SMTP — 확인 필요)로 **동기 발송**하고, **발송 성공 시에만** `email-verify:code:{userId}`를 (재)설정한다(발송 실패 시 챌린지 미저장 + `502 UPSTREAM_ERROR`로 응답·재시도 유도). 재발송·검증 시도는 레이트리밋·`attempts` 상한(확인 필요)으로 보호, 초과 시 `429 TOO_MANY_REQUESTS`.
- **검증**: 입력 인증번호 해시가 `codeHash`와 일치하고 미만료·시도 미초과면 `email-verify:verified:{userId}`에 이메일 기록(코드 키는 만료/삭제). 불일치·만료는 `422 AUTH_EMAIL_VERIFICATION_FAILED`.
- **온보딩 제출**: `auth`가 `email-verify:verified:{userId}`의 이메일과 제출 `email`을 대조 → 일치해야 `user` 온보딩 완료 명령 진행. 확정 `email`은 `users.email`로 영속(아래 §4-2), 인증 흔적은 TTL로 소멸한다(영속 안 함).
- **민감정보**: `email`은 응답·로그 마스킹, 인증번호 원문은 보관·로그하지 않는다(해시만).

#### (B) MySQL — 소셜 연동(`social_accounts`)

소셜 제공자 자격을 회원에 묶는다(domain-model `SocialAccount`).

`social_accounts`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `user_id` | BIGINT | NOT NULL, INDEX · → user `users.id`(FK 아님, 값 참조) |
| `provider` | VARCHAR(20) (enum `Provider`) | NOT NULL |
| `provider_user_id` | VARCHAR(255) | NOT NULL · provider OIDC `sub` |
| `email` | VARCHAR(255) | NULL · 제공자 이메일 — 민감정보 |
| `linked_at` | DATETIME(6) | NOT NULL · 자격 연결 시각 |

**인덱스**: PK `id` / UNIQUE `(provider, provider_user_id)`(중복 연동 방지·로그인 분기 등치 조회) / INDEX `user_id`(회원 연동 목록·탈퇴 정리).

- **교차 모듈 no-FK**: `user_id`는 user 소유 → FK 미설정(값 참조). 회원 상태/프로필은 user 공개 쿼리/애플리케이션 조인.
- 연동 매핑은 불변(생성/삭제만) → `updated_at`/소프트삭제 미적용.

### 4-2. `user`

> 스토어: **MySQL** (회원 프로필·계정 lifecycle, [ADR-0005](../adr/0005-polyglot-persistence.md)). domain-model `User`(VO `FullName`·`Consent`를 컬럼으로 평탄화, `nickname`·`country`·`occupation`·`email`은 단일 컬럼).

`users`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `first_name` | VARCHAR(100) | NULL · VO `FullName`(PII — 아래 註) |
| `last_name` | VARCHAR(100) | NULL · VO `FullName`(PII) |
| `nickname` | VARCHAR(50) | NULL · **UNIQUE** · 시스템 배정(`형용사 + 사물`) · 탈퇴 시 익명화 |
| `gender` | VARCHAR(16) (enum `Gender`) | NULL(PII) |
| `birth_date` | DATE | NULL · 과거만(앱 검증)(PII) |
| `country` | CHAR(2) | NULL · 국적 ISO 3166-1 alpha-2 코드 · → `countries.code`(같은 모듈) · 표시명·국기는 `countries`에서 확보(PII) |
| `occupation` | VARCHAR(32) (enum `Occupation`) | NULL · 임시 분류값(확인 필요)(PII) |
| `email` | VARCHAR(255) | NULL · 인증 완료 연락 이메일 · 민감정보(PII). 소셜 제공자 이메일(`social_accounts.email`)과 별개 |
| `visa_type` | VARCHAR(32) (enum `VisaType`) | NULL · 민감정보(PII) |
| `status` | VARCHAR(16) (enum `UserStatus`) | NOT NULL · 신규 `PENDING` |
| `terms_of_service_agreed` | BOOLEAN | NOT NULL · VO `Consent` |
| `privacy_policy_agreed` | BOOLEAN | NOT NULL · VO `Consent` |
| `marketing_agreed` | BOOLEAN | NOT NULL DEFAULT FALSE · VO `Consent` |
| `agreed_at` | DATETIME(6) | NULL · VO `Consent` 동의 시각 |
| `terms_version` | VARCHAR(20) | NULL · VO `Consent` 동의 약관 버전 |
| `withdrawn_at` | DATETIME(6) | NULL · 탈퇴 시각 — 탈퇴 시 PII 즉시 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)) |
| `created_at` | DATETIME(6) | NOT NULL |
| `updated_at` | DATETIME(6) | NOT NULL |

**인덱스**: PK `id`(`findById`) / **UNIQUE `nickname`**(닉네임 전역 유일·중복 배정 차단; NULL은 다중 허용이라 온보딩 전 `PENDING` 다건 무방) / (선택) INDEX `status`(상태별 배치 — MVP 조회는 PK 단건뿐이라 보류 가능).

- **이메일 두 종류**: 소셜 제공자 이메일은 **auth `social_accounts.email`** 소관이고, `users.email`은 **온보딩 중 사용자가 입력·인증한 연락 이메일**이다(둘은 별개, 같을 수도 다를 수도 있음). 이메일 *인증 흔적*은 Redis(§4-1 A-2)에만 단명 보관하고 users엔 확정 이메일만 영속한다.
- **닉네임**: 시스템이 `형용사 + 사물`로 무작위 배정하며 `UNIQUE`로 중복을 막는다(충돌 시 재시도). 사용자 입력·수정 대상이 아니다.
- **상태 흐름·컬럼 채움 시점**: `status`는 `PENDING`(소셜 검증) → `TERMS_AGREED`(약관 동의) → `ACTIVE`(온보딩 완료) → `WITHDRAWN`. **동의 컬럼**(`terms_of_service_agreed`·`privacy_policy_agreed`·`marketing_agreed`·`agreed_at`·`terms_version`)은 **약관 동의 단계**(`PENDING`→`TERMS_AGREED`)에 채워지고, **프로필 컬럼**(이름·`nickname`·성별·생년월일·`country`·`occupation`·`email`·`visa_type`)은 **온보딩 단계**(`TERMS_AGREED`→`ACTIVE`)에 채워진다(enum 값 정본은 [domain-model](../architecture/domain-model.md)).
- **교차 모듈 no-FK**: auth(소셜연동·refresh·이메일인증)와 `userId` 값만 공유.
- **소프트삭제 대신 상태**: 탈퇴=`status=WITHDRAWN`+`withdrawn_at` 기록, PII 즉시 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md))(+토큰 일괄 무효화). 탈퇴 시 `nickname`도 익명화(NULL)해 유니크 슬롯을 회수한다. WITHDRAWN/없음 조회는 `USER_NOT_FOUND`(404).
- **PII 컬럼은 NULL 허용**(`first_name`·`last_name`·`nickname`·`gender`·`birth_date`·`country`·`occupation`·`email`·`visa_type`): 회원은 온보딩 *전*(`PENDING`)에 프로필 없이 생성되고, 탈퇴 시 즉시 **익명화(NULL)**되기 때문이다([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)). "온보딩 완료(`ACTIVE`) 시 채워져야 한다"는 **상태 불변식**(앱·서버 검증)이지 컬럼 NOT NULL 제약이 아니다.
- **민감정보**: `email`·`visa_type`은 로그·타 사용자 노출 시 마스킹(본인 `GET /users/me`는 평문). 컬럼 암호화 도입 시 길이 재산정([§6](#6-결정-필요-open-questions)).
- **마이그레이션 후속**: 이 스키마(닉네임·국적·직업·이메일 추가, 전화번호 컬럼 제거)는 baseline([V1](../../src/main/resources/db/migration/V1__baseline_users_social_accounts.sql)) 이후 변경이므로 **전진 마이그레이션(V2 등)** 으로 반영해야 한다([migration-policy](./migration-policy.md), 확인 필요).

#### 국가 참조 — `countries`

국적은 **국가 코드(ISO 3166-1 alpha-2)** 로 식별하고, 표시명·국기는 이 reference 테이블에서 확보한다. 클라이언트는 온보딩 시 **국가(코드)만 전송**하고 국기는 서버가 여기서 채운다(수집). 시드/마이그레이션으로 적재.

`countries`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `code` | CHAR(2) | PK · ISO 3166-1 alpha-2(예: `VN`) |
| `name` | VARCHAR(64) | NOT NULL · 표시명(다국어 단일 vs `name_en`·`name_ko`는 확인 필요) |
| `flag` | VARCHAR(512) | NOT NULL · 국기 이미지 URL(flagcdn.com SVG, 코드 소문자 기반 · 예 `https://flagcdn.com/vn.svg`) |
| `lang` | VARCHAR(8) | NOT NULL DEFAULT `en` · 표시 언어(ISO 639-1) · 다국어 표시(진단 문항·추천 제안)의 사용자 언어 결정 출처 · 미매핑은 `en` 폴백 |

**인덱스**: PK `code`.

- **국기 확보**: `users.country`(코드)로 `countries`를 조회해 `name`·`flag`를 얻는다(API 응답의 `countryName`·`countryFlag`). `flag`는 **국기 이미지 URL**(flagcdn.com SVG, 코드 소문자 기반 — 예 `VN`→`https://flagcdn.com/vn.svg`)이며, 표기 일관성·교체 용이를 위해 이 테이블을 단일 출처로 둔다.
- **표시 언어 도출**: `users.country`(코드)로 `countries.lang`을 조회해 사용자 표시 언어를 정한다 — `diagnosis` 등 다국어 모듈이 `user` 공개 query `getLanguage(userId)`로 동기 취득한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정, 국가→언어 매핑을 diagnosis에서 user `countries`로 이전). 미설정·미매핑이면 `en` 폴백.
- **검증**: 온보딩/수정의 `country`는 `countries.code`에 존재해야 함(없으면 `400 INVALID_INPUT`). `users.country`→`countries.code`는 같은 모듈이라 FK 가능(값 참조도 허용).
- **reference 데이터**: ISO 3166-1 기준 시드, 운영 중 갱신 가능. 전화 국가코드(dial code)는 전화번호 제거로 불필요.

#### 닉네임 풀 — `nickname_adjectives`·`nickname_nouns`

닉네임은 **형용사(앞 단어) + 사물(뒤 단어)** 조합이라 두 단어 풀을 reference 테이블로 둔다(시드/마이그레이션으로 적재, 운영 중 가변). 조합·유니크 검증 로직은 `user` 도메인 서비스 `NicknameGenerator`가 담당(domain-model `user`).

`nickname_adjectives` (형용사 풀)

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `word` | VARCHAR(50) | NOT NULL · **UNIQUE** · 형용사(앞 단어) |
| `active` | BOOLEAN | NOT NULL DEFAULT TRUE · 비활성 단어는 선택 풀에서 제외 |

`nickname_nouns` (사물 풀)

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `word` | VARCHAR(50) | NOT NULL · **UNIQUE** · 사물(뒤 단어) |
| `active` | BOOLEAN | NOT NULL DEFAULT TRUE · 비활성 단어는 선택 풀에서 제외 |

**인덱스**: 각 PK `id` / UNIQUE `word`(중복 단어 방지).

- **생성·유니크 로직**: `NicknameGenerator`가 두 풀의 `active=TRUE`에서 무작위로 각 1개를 뽑아 `형용사 + 사물`로 조합 → `users.nickname` 유니크를 검사해 **충돌 시 재조합 재시도**(상한 N회 — 확인 필요), 상한 초과 시 **fallback**(예: 숫자 접미사)으로 종료를 보장한다. 동시 생성 경합은 `users.nickname` UNIQUE 제약이 최종 차단하고(위반 시 재조합), 그래서 닉네임은 `INSERT`/`UPDATE` 시점에 확정된다.
- **무작위 선택**: 풀 규모가 작아 앱에서 풀 로드 후 선택 또는 `ORDER BY RAND() LIMIT 1` 모두 허용(전략·캐싱은 확인 필요).
- **reference 데이터**: 단어 목록은 시드/마이그레이션으로 적재하고 운영 중 추가·비활성(`active`) 가능. 단어·로케일(언어)·조합 포맷(연결/구분자)은 시드 시 확정([§6](#6-결정-필요-open-questions)).
- **교차 모듈 no-FK**: `user` 모듈 내부 reference 테이블이라 타 모듈 FK 없음.

### 4-3. `listing`

> 스토어: **MongoDB** (지오·대량 읽기, `2dsphere`. [ADR-0005](../adr/0005-polyglot-persistence.md)). domain-model `Listing`·`Favorite`·`RecentListing`(VO `Location`·`Landlord` 임베드).
>
> Numbers 첫 번째 임시 데이터를 반영한 필드별 주석·예시·인덱스 검토안은 [Listing MongoDB 상세 스키마 초안](./listing-mongodb-schema.md)을 참고한다.

`listings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `type` | string (enum `ListingType`) | NOT NULL |
| `title` | string | NOT NULL |
| `location` | object(VO `Location`) | `lat`/`lng`(GeoJSON Point `[lng,lat]`, 2dsphere)·`address`·`addressDetail` |
| `monthlyRent` | int | NOT NULL, ≥0 |
| `deposit` | int | NOT NULL, ≥0 |
| `conditions` | string[] (enum `ConditionTag`) | 중복 불가(집합) · AND 필터 |
| `contractTermOptions` | string[] (enum `ContractTerm`) | 최소 1, 중복 불가(집합) |
| `imageUrls` | string[] | `[0]`=썸네일 |
| `arcRequired` | bool | NOT NULL |
| `availableFrom` | date | 입주 가능일 |
| `landlord` | object(VO `Landlord`) | `landlordId`(→ user 값 참조)·`name`·`contactChannel`(enum `ContactChannel`, 항상 `CHAT`) |
| `published` | bool | NOT NULL, default true |
| `favoriteCount` | int | default 0, ≥0 · 비정규화 캐시 |
| `createdAt` | ISODate | NOT NULL |
| `updatedAt` | ISODate | NOT NULL |

`favorites`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | NOT NULL · UNIQUE(userId,listingId) · → user(값 참조) |
| `listingId` | long | NOT NULL · UNIQUE(userId,listingId) · → listings(값 참조) |
| `favoritedAt` | ISODate | NOT NULL · 목록 정렬키(desc) |

`recentListings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | NOT NULL · UNIQUE(userId,listingId) · → user(값 참조) |
| `listingId` | long | NOT NULL · UNIQUE(userId,listingId) · → listings(값 참조). 재조회 upsert |
| `viewedAt` | ISODate | NOT NULL · TTL(7일) |

**인덱스:**

| 인덱스 | 대상 | 종류 | 목적 |
| --- | --- | --- | --- |
| `listings.location` | `location` | **2dsphere** | bbox/반경·거리순(`$near`/`$geoWithin`, [ADR-0005](../adr/0005-polyglot-persistence.md) D3) |
| `listings_filter` | `published, type, monthlyRent` | 복합 | 공개·유형·월세 필터 |
| `listings_conditions` | `conditions` | multikey | `ConditionTag` AND 필터 |
| `favorites_user_listing` | `userId, listingId` | UNIQUE | 중복 찜 불가·토글 멱등 |
| `favorites_user_favoritedAt` | `userId, favoritedAt` | 복합(desc) | 내 찜 목록 |
| `recentListings_user_listing` | `userId, listingId` | UNIQUE | 재조회 upsert |
| `recentListings_viewedAt_ttl` | `viewedAt` | TTL(604800s) | 7일 자동 만료 |

- **교차 스토어/모듈 no-FK**: `favorites`/`recentListings`→user(다른 store)·listing(같은 store)이라도 FK 없이 값 참조 + 애플리케이션 조인.
- **유니크/멱등**: 찜 토글 멱등(신규 201/기존 200, 해제 항상 200). 최근본 재조회는 upsert.
- **카운트 정합**: `favoriteCount`는 `favorites` 집계의 비정규화 캐시 — 토글 시 동일 store 갱신 + 배치 재계산([§6](#6-결정-필요-open-questions)).
- **최근 본**: "7일·최대 5건" — 7일은 TTL, 5건은 조회 `viewedAt desc limit 5`(표시 상한).
- **좌표**: 저장 `[lng,lat]` ↔ API `{lat,lng}` 변환. `landlord.contactChannel`은 상수 `CHAT`(직접 연락처 미보유).
- `favorited`·`distanceMeters`는 조회 시점 산출 표현값으로 영속하지 않는다(domain-model).

### 4-4. `diagnosis`

> 스토어: **MongoDB** (문서형 애그리거트·임베드 배열·단일 도큐먼트 원자 쓰기). domain-model `Diagnosis`(VO `DiagnosisCriteria` 임베드).

`diagnoses` — `criteria`(VO `DiagnosisCriteria`)는 임베드로 평탄화(`region`·`purpose`·`university`/`district`·`conditions`·`monthlyBudgetMax`·`arcStatus`). 6단계 진단(① 지역 `region` / ② 입국 목적·유학 여부 `purpose` / ③ 대학·지역 선택 `university`/`district` / ④ 주거 환경 조건 `conditions` / ⑤ 월 예산 `monthlyBudgetMax` / ⑥ ARC `arcStatus`)을 단일 도큐먼트로 들고, **진행 중 답을 서버가 단계별로 저장**한다 — 사용자당 진행 중(`status=IN_PROGRESS`) 진단 1건을 draft로 두고 답을 채워가다가, 제출 시 저장된 답을 재검증해 `COMPLETED`로 확정한다(canon: user-stories §2).

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | 필수, 인덱스 · → user(값 참조) |
| `region` | string (enum `Region`) | 필수 · ① 지역 |
| `purpose` | string (enum `Purpose`) | 필수 · ② 입국 목적·유학 여부 — 단일 enum `STUDY`\|`NON_STUDY`. ③ 대학/지역 조건부 필수의 분기 키(`STUDY`→`university` / `NON_STUDY`→`district`) |
| `university` | string (enum `University`) | nullable · ③ 대학(입국 목적 `purpose=STUDY`일 때 **필수**·`NON_STUDY`이면 없음 — 앱 레벨 조건부 필수 불변식) · UPPER_SNAKE 저장 · 값: `SNU`·`CAU`·`SOONGSIL`·`HUFS`·`KHU`·`KOREA`·`SKKU`·`SUNGSHIN`·`KONKUK`·`SEJONG`·`HYU`·`HONGIK`·`YONSEI`·`EWHA`·`ETC` |
| `district` | string (enum `District`) | nullable · ③ 지역구(입국 목적 `purpose=NON_STUDY`일 때 **필수**·`STUDY`이면 없음 — 앱 레벨 조건부 필수 불변식) · UPPER_SNAKE 저장 · 값: `GURO_GU`·`YEONGDEUNGPO_GU`·`GEUMCHEON_GU`·`GWANAK_GU`·`DONGDAEMUN_GU`·`ETC` |
| `conditions` | string[] (enum `ConditionTag`) | 선택, ≤3, 중복 제거 · ④ 주거 환경 조건 — listing `ConditionTag` 이름으로 통일. 값: `IMMEDIATE_MOVE_IN`·`FEMALE_ONLY`·`PRIVATE_TOILET`·`PRIVATE_BATH`·`ENGLISH_AVAILABLE`·`RESIDENT_REGISTRATION`·`NO_MAINTENANCE_FEE`·`MEALS_PROVIDED`·`DOUBLE_ROOM` |
| `monthlyBudgetMax` | int(KRW) | 필수, ≥0 · ⑤ 월 예산 |
| `arcStatus` | string (enum `ArcStatus`) | 필수 · ⑥ ARC |
| `status` | string (enum `DiagnosisStatus`) | 필수 · `IN_PROGRESS`→`COMPLETED`. 진행 중 답은 `IN_PROGRESS` draft에 저장하고, 제출 확정 시 `COMPLETED`로 전이 |
| `idempotencyKey` | string | nullable · 제시 시 동일 소유자 범위 유니크 |
| `submittedAt` | ISODate | nullable · `COMPLETED` 확정 시각(IN_PROGRESS 단계에선 미설정) |

**인덱스**: PK `_id` / 복합 `(userId, submittedAt desc)`(이력 목록·최신 단건) / UNIQUE 부분 `(userId, idempotencyKey)`(키 제시 시 — 중복 제출 차단).

- **진행 중 저장(server-stateful)**: 진단 답은 서버가 단계별로 저장한다 — 사용자당 진행 중(`status=IN_PROGRESS`) 진단 1건을 draft로 두고 `POST /api/v1/diagnoses/answers`(body `{ field, code }`, `conditions`처럼 다중은 `codes` 배열)가 현재 step 답을 그 draft에 채운다(누적 답 재전송 없음). 문항은 `GET /api/v1/diagnoses/questions/{step}`로 step별 1개씩 받는다(다음 step 번호는 클라가 정한다). 모든 단계 답이 채워지면 `POST /api/v1/diagnoses`가 저장된 답을 재검증해 `COMPLETED`로 확정(`submittedAt` 설정, `201` + `Location` 헤더)한다. **이력·목록·최근 단건 조회는 `COMPLETED`만 노출**하고 `IN_PROGRESS`는 제외한다.
- **재진단=새 진행 중 진단 시작**(수정 아님) → 새 `IN_PROGRESS` draft를 시작해 채운 뒤 `COMPLETED`로 확정. 기존 진단을 덮어쓰지 않으므로 `updatedAt`/소프트삭제 없이 단계별 답 채움과 확정 전이만 둔다.
- **소유권**: 조회는 `userId` 일치 필수, 타인 `403`, 없으면 `DIAGNOSIS_NOT_FOUND`(404).
- **멱등성**: `idempotencyKey` 제시 시 (키+정규화 criteria) 동일 재시도는 1건, 같은 키·다른 criteria는 `409 DIAGNOSIS_IDEMPOTENCY_CONFLICT`.
- **교차 모듈 no-FK**: `userId` 값만. 추천은 `listing` 공개 쿼리(매물 데이터 비영속·런타임 계산) — diagnosis가 `RecommendationCriteria`(지역·예산·조건·대학/지역) 값객체로 `listing` 공개 query를 동기 호출해 `ListingSummaryResponse`+좌표를 수신([ADR-0002](../adr/0002-inter-module-communication-via-events.md) D5). listing 컬렉션 스키마(§4-3)는 변경하지 않는다.
- **3단계 대학/지역 조건부 필수**: `university`/`district`는 **두 필드로 분리**하며 NOT NULL 제약이 아니라 **앱 레벨 조건부 필수 불변식**으로 강제한다 — 입국 목적(`purpose`)에 맞는 하나만 채워진다: `purpose=STUDY`면 `university` 필수·`district` 없음, `purpose=NON_STUDY`면 `district` 필수·`university` 없음. 위반은 공통 `400 INVALID_INPUT`+`errors[]`(신규 도메인 코드 없음). enum 값 목록은 위 필드 표(`University`: `SNU`·…·`ETC` / `District`: `GURO_GU`·…·`ETC`)대로.
- **문항·선택지 출처(US-2-5)**: 문항 제공은 **단계별 server-stateful 질의응답**이다 — 클라이언트가 받을 step(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`(인증 필수, 200)를 호출하면 서버가 (카탈로그 + 진행 중 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정해 `{ step, field, question(사용자 언어 라벨 문자열), select{type,max}, options[{code,label}] }`로 내려준다(한 번에 다 주지 않음, 다음 step 번호는 클라가 정한다). 현재 step 답은 별도로 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; `conditions`처럼 다중은 `codes` 배열)로 보내면 서버가 진행 중(`IN_PROGRESS`) 진단에 저장한다(누적 답 묶음 전송 없음). 흐름은 `GET questions/1 → POST answers → GET questions/2 → … → GET questions/6 → POST answers → POST /diagnoses`이며, 모든 단계 답이 저장되면 `POST /api/v1/diagnoses`가 진행 중 진단을 `COMPLETED`로 확정한다. 반환 선택지 `code`는 **확정 검증 enum과 1:1 동일 출처**다(언어 무관 단일 키). 문항·선택지 카탈로그는 **MongoDB `diagnosisQuestions` 컬렉션**(아래)에 데이터로만 영속한다. **분기는 서버 비즈니스 로직(diagnosis 서비스 코드)이 결정한다(클라 로컬 분기·카탈로그 분기 메타 아님)** — ③(step 3)은 저장된 `purpose`에 따라 서비스가 알맞은 질문만 담는다(`STUDY`→대학 질문 `university`, `NON_STUDY`→지역구 질문 `district`; 한 응답에 두 목록을 함께 주지 않음). 잘못된 현재 step 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `400 INVALID_INPUT`+`errors[]`.
- **라벨 번역(US-2-6)**: 표시 `question`·`label`만 사용자 표시 언어로 번역하고 `code`는 언어 무관 동일이다. 번역 표시 문자열은 `diagnosisQuestions` 도큐먼트 내부 `question`·`label`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드하고, 추천 사유/액션 텍스트는 `diagnosisSuggestions` 컬렉션의 **동일한 인라인 언어-키 맵**에 둔다(같은 방식 재사용). **해당 언어 키가 없으면 영어(`en`) 폴백**(에러 아님, `Accept-Language` 비의존). 표시 언어는 **user 모듈 공개 query(`getLanguage`)로 동기 취득**하고 `user`가 등록 국가→언어(`countries.lang`)로 도출한다(토큰 클레임 분기 아님, [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5; 교차 모듈 no-FK 값 참조) → 모듈 의존 `diagnosis`→`user`.
- **검증 불변식**(앱 레벨): `purpose` 단일 enum(`STUDY`\|`NON_STUDY`)·`conditions≤3`·`budget≥0`·필수 필드·3단계 대학/지역 조건부 필수(`400 INVALID_INPUT`).

#### 문항·선택지 카탈로그 — `diagnosisQuestions`

진단 6단계의 문항·선택지·제약을 **데이터로만** 영속하는 카탈로그 컬렉션이다(US-2-5). 분기 메타(`branchOn` 등)는 두지 않는다 — 어느 질문을 낼지(③ 대학/지역)는 diagnosis 서비스 비즈니스 로직이 결정한다(D4). 표시 문자열(번역)은 별도 컬렉션 없이 도큐먼트 내부 `question`·`label`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드한다(US-2-6). `GET /api/v1/diagnoses/questions/{step}`(클라가 받을 step을 path로 지정)이 (이 카탈로그 + 진행 중 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정·조립해 내려준다(한 번에 다 주지 않음, 다음 step 번호는 클라가 정한다). 선택지 `code`는 **확정 검증 enum과 동일 출처**(언어 무관 단일 키)다. 시드/마이그레이션으로 적재, 운영 중 `active`로 가변.

`diagnosisQuestions`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `step` | int | NOT NULL · 진단 단계(①~⑥) · `GET /questions/{step}` path로 지정해 조회하는 단계 키 |
| `field` | string | NOT NULL · 제출 필드명(`region`·`purpose`·`university`/`district`·`conditions`·`monthlyBudgetMax`·`arcStatus`) |
| `question` | object | NOT NULL · 문항 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `select` | object | NOT NULL · `type`(enum `SINGLE`/`MULTI`)·`max`(int, MULTI 상한) — 선택 제약(③ 대학/지역·④ 조건 등) |
| `options` | object[] | 선택지 배열 · 각 항목은 `code`(제출 검증 enum과 동일·언어 무관 불변)와 `label`(표시 문자열의 **인라인 언어-키 맵**, 예 `{ "en": "Seoul", "ja": "ソウル" }`)을 보유 |
| `active` | bool | NOT NULL DEFAULT true · 비활성 문항/선택지는 응답에서 제외 |

**인덱스**: PK `_id` / INDEX `(active, step)`(활성 문항 단계순 조회).

- **출처 일치**: `options[].code`는 `diagnoses`의 제출 검증 enum(`Region`·`Purpose`·`University`/`District`·`ConditionTag`·`ArcStatus`)과 **1:1 동일 키**라 `GET /questions/{step}` 응답·`POST /answers` 답 저장·`POST /diagnoses` 확정 검증이 모두 같은 카탈로그를 본다(언어와 무관).
- **번역**: 표시 문자열은 도큐먼트 내부 `question`·`options[].label`의 **인라인 언어-키 맵**(`{ lang → message }`)에 임베드한다 — 서버가 사용자 언어 키를 골라 조립한다. 표시 언어 키는 **user 공개 query(`getLanguage`)로 취득한 표시 언어**로 선택하고 해당 키가 없으면 `en` 폴백(에러 아님, `Accept-Language` 비의존). 등록 국가→언어 매핑은 `user`의 `countries.lang`이 보유하며 교차 모듈 **값 참조**(no-FK).
- **③ 분기(서버 결정)**: 대학/지역 단계는 **분기 메타 없이** 두 질문(`university`·`district`)이 데이터로 각각 존재하고, `GET /api/v1/diagnoses/questions/{step}`이 호출되면 **diagnosis 서비스 비즈니스 로직**이 진행 중 진단에 저장된 `purpose`를 보고 어느 질문을 낼지 결정해 하나만 골라 내려준다 — `STUDY`면 대학 목록으로 `university` 질문, `NON_STUDY`면 지역구 목록으로 `district` 질문(한 응답에 두 목록을 함께 주지 않음, 클라 로컬 분기 아님).
- **추천 사유/액션 번역**: 추천 0건 `suggestions`의 `reason`/`actions[].type`(언어 무관 enum 키)의 표시 `message`/`detail`은 **`diagnosisSuggestions` 전용 컬렉션**(아래)의 `reason`별 인라인 언어-키 맵에서 서버가 사용자 언어 키로 골라 제공한다(문항 카탈로그와 동일 방식, 없으면 `en` 폴백).

#### 추천 조정 제안 — `diagnosisSuggestions`

추천 0건일 때의 조정 제안(`suggestions`) 표시 문자열을 사유(`reason`)별로 영속하는 카탈로그 컬렉션이다(US-2-2·US-2-6). 표시 문자열은 문항 카탈로그와 동일하게 **인라인 언어-키 맵**으로 두고, `reason`·`actions[].type`은 언어 무관 enum 식별 키다. 서버가 사용자 언어 키로 `message`/`detail`을 골라(없으면 `en` 폴백) 응답을 조립한다. 시드/마이그레이션으로 적재, 운영 중 갱신 가능.

`diagnosisSuggestions`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | string | PK · 사유 식별 키(언어 무관, 예 `NO_MATCH`) |
| `message` | object | NOT NULL · 사유 안내 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ko": "..." }`) |
| `actions` | object[] | 조정 액션 배열 · 각 항목은 `type`(언어 무관 식별 키, 예 `RELAX_REGION`)와 `detail`(표시 문자열 **인라인 언어-키 맵**)을 보유 |

### 4-5. `booking`

> 스토어: **저장소(추후 결정)** — **논리 스키마**(store 확정 시 물리화). domain-model `Booking`(VO `GreetingMessage`).

`bookings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | long | PK |
| `tenant_id` | long | NOT NULL · → user(값 참조) |
| `listing_id` | long | NOT NULL · → listing(값 참조) |
| `landlord_id` | long | NOT NULL · → user(값 참조) |
| `move_in_date` | date | NOT NULL |
| `contract_period` | enum `ContractPeriod` | NOT NULL |
| `greeting_message` | text | NULL · VO `GreetingMessage`(≤500) |
| `status` | enum `BookingStatus` | NOT NULL, default `REQUESTED` |
| `created_at` | datetime | NOT NULL |

**인덱스**: PK `id` / **부분 UNIQUE** `(tenant_id, listing_id) WHERE status IN ('REQUESTED','ACCEPTED')`(활성 예약 1건·중복 신청 방지, `BOOKING_ALREADY_EXISTS` 409) / INDEX `(tenant_id, created_at)`·`(listing_id, status)`.

- **활성 유니크**: REJECTED/CANCELED 후 재신청 허용을 위해 **부분(조건부) 유니크**. 미지원 스토어면 앱 레벨 검증(조회+락/멱등키).
- **교차 모듈 no-FK**: `tenant_id`·`listing_id`·`landlord_id` 값 참조. 본인 매물 금지(`tenant_id==landlord_id`, `422`)·입주일 검증은 listing 공개 쿼리로.
- **chatRoomId 비영속**: 신청 응답의 `chatRoomId`는 `BookingCreatedEvent` 후 `chat`이 보장한 방을 이벤트/공개 쿼리로 받아 구성하며 `bookings`에 저장하지 않는다.
- **소프트삭제 불요**: 취소는 `status=CANCELED`. 상태 전이(수락/거절/취소)는 현 스펙 범위 밖 — 도입 시 `updated_at` 추가.

### 4-6. `chat`

> 스토어: **저장소(추후 결정)** — **논리 스키마**. domain-model `ChatRoom`(+`Message`·`ReadCursor`, VO `BookingCard`·`ListingCard`·`ListingSnapshot`).

`chat_rooms`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | long | PK |
| `category` | enum `ChatCategory` | NOT NULL |
| `listing_id` | long | NULL · → listing(값 참조). NEIGHBOR이면 null |
| `tenant_id` | long | NOT NULL · → user(값 참조) |
| `landlord_id` | long | NOT NULL · → user(값 참조) |
| `listing_snapshot` | object(VO `ListingSnapshot`) | NULL · `listingId`·`title`·`thumbnailUrl`·`monthlyRent`(비정규화) |
| `active` | bool | NOT NULL, default true |
| `last_message_at` | datetime | NULL · 목록 정렬키(desc) |
| `created_at` | datetime | NOT NULL |

`messages`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | long | PK |
| `chat_room_id` | long | NOT NULL, FK→`chat_rooms.id`(같은 모듈) |
| `sender_id` | long | NULL · → user(값 참조). 시스템/카드는 null |
| `type` | enum `MessageType` | NOT NULL |
| `content` | text | NULL · `TEXT`에만, 1~1000자 |
| `booking_card` | object(VO `BookingCard`) | NULL · `BOOKING_CARD`에만(`moveInDate`·`contractPeriod`·`monthlyRent`·`listingTitle`·`bookingId`) |
| `listing_card` | object(VO `ListingCard`) | NULL · `LISTING_CARD`에만(`listingId`·`title`·`monthlyRent`·`thumbnailUrl`) |
| `pinned` | bool | NOT NULL, default false |
| `sent_at` | datetime | NOT NULL · 전송 시각 |

`chat_room_members` (읽음 커서)

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | long | PK |
| `chat_room_id` | long | NOT NULL, FK→`chat_rooms.id`(같은 모듈) |
| `user_id` | long | NOT NULL · → user(값 참조) |
| `last_read_message_id` | long | NULL · → `messages.id`(같은 모듈). 전진만 |
| `updated_at` | datetime | NOT NULL · 읽음 갱신 시각 |

**인덱스**: PK 각 / UNIQUE `chat_rooms(listing_id,tenant_id,landlord_id)`(방 유일·문의 멱등) / INDEX `chat_rooms(tenant_id,last_message_at desc)`·`(landlord_id,last_message_at desc)`(참여자별 목록) / INDEX `messages(chat_room_id, id desc)`(커서 페이지) / UNIQUE `chat_room_members(chat_room_id,user_id)`.

- **교차 모듈 no-FK**: `listing_id`·`tenant_id`·`landlord_id`·`sender_id`·`user_id` 값 참조. 같은 모듈 `messages.chat_room_id`·`chat_room_members.*`만 FK.
- **읽음 커서**: `unreadCount`는 저장하지 않고 `messages` 중 `id > last_read_message_id`인 본인 미발신 수로 계산. 읽음은 전진만·멱등.
- **카드/스냅샷 비정규화**: `booking_card`/`listing_card`/`listing_snapshot`은 생성 시점 정보를 굳힌 임베드 VO(매물 변경과 독립). `content`는 `TEXT`에만·카드는 카드 타입에만(앱 레벨 배타; DB CHECK는 store 확정 후).
- **NEIGHBOR 유니크**: `listing_id=null`의 유니크 시맨틱이 스토어별 상이(MySQL NULL 비충돌 vs Mongo partial index) → store 확정 시 결정([§6](#6-결정-필요-open-questions)).

### 4-7. `community`

> 스토어: **MySQL** (`PostLike` 유니크·다엔티티 카운트 정합). **1차 MVP 이후**. domain-model `Post`(+`Comment`·`PostLike`, VO `Hashtag`).

`posts`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `author_id` | BIGINT | NOT NULL · → user(값 참조) |
| `board_type` | VARCHAR(20) (enum `BoardType`) | NOT NULL |
| `title` | VARCHAR(100) | NOT NULL |
| `content` | TEXT | NOT NULL |
| `like_count` | INT | NOT NULL DEFAULT 0, CHECK ≥0 |
| `comment_count` | INT | NOT NULL DEFAULT 0, CHECK ≥0 |
| `share_count` | INT | NOT NULL DEFAULT 0, CHECK ≥0 |
| `deleted` | BOOLEAN | NOT NULL DEFAULT FALSE |
| `deleted_at` | DATETIME(6) | NULL |
| `created_at` | DATETIME(6) | NOT NULL |
| `updated_at` | DATETIME(6) | NOT NULL |

`comments`: `id` PK / `post_id` FK→posts / `author_id`(값 참조) / `content` TEXT(1~1000) / `deleted`+`deleted_at` / `created_at`.
`post_likes`: `id` PK / `post_id` FK→posts / `user_id`(값 참조) / `created_at`. **UNIQUE `(post_id,user_id)`**.
`post_hashtags`: `id` PK / `post_id` FK→posts / `tag` VARCHAR(50)(VO `Hashtag`, 게시글당 ≤10).

**인덱스**: INDEX `posts(board_type,deleted,created_at desc)`(최신순)·`posts(board_type,deleted,like_count desc,created_at desc)`(인기순)·`posts(author_id,deleted,created_at desc)`(내 글) / FULLTEXT(ngram) `posts(title,content)`(**MVP 이후**) / INDEX `comments(post_id,deleted,created_at asc)` / UNIQUE `post_likes(post_id,user_id)` / INDEX `post_hashtags(tag,post_id)`.

- **소프트삭제**: `posts`/`comments` `deleted=false`만 노출. 목록 인덱스에 `deleted` 포함.
- **교차 모듈 no-FK**(`author_id`/`user_id`→user) / **같은 모듈 FK**(`post_id`→posts).
- **좋아요 멱등·정합**: `(post_id,user_id)` UNIQUE + `UPDATE posts SET like_count = like_count ± 1`(원자 증감) 단일 트랜잭션. 카운트 `CHECK ≥0`(MySQL 8.0.16+; 미만이면 앱 가드).
- **해시태그**: 권장 `post_hashtags` 정규화 테이블(검색·인덱스 유리). 대안 `posts.hashtags JSON`.

### 4-8. `gamification`

> 스토어: **저장소(추후 결정)** — **논리 스키마**. **1차 MVP 이후**. domain-model `Quiz`(+VO `QuizChoice`)·`QuizSubmission`·`PointHistory`.

`quizzes`: `id` PK / `quiz_date` **UNIQUE**(날짜별 1개·조회키) / `question` text / `correct_choice`(enum `ChoiceKey`, 제출자 공개) / `explanation` text(제출자 공개). 보기 `choices`(VO `QuizChoice`)는 **임베드**(`[{key,text}]`) 또는 별도 테이블 `quiz_choices`로 매핑(store 확정 시).
`quiz_submissions`: `id` PK / `user_id`(값 참조) / `quiz_id` FK→quizzes / `quiz_date` / `selected_choice`(enum `ChoiceKey`) / `correct` / `earned_point`(≥0, 오답 0) / `submitted_at`. **UNIQUE `(user_id,quiz_date)`**.
`point_histories`: `id` PK / `user_id`(값 참조) / `amount`(>0, 포인트≠KRW) / `reason`(enum `PointReason`) / `created_at`(append-only).

**인덱스**: UNIQUE `quizzes(quiz_date)` / (별도 테이블 시) INDEX `quiz_choices(quiz_id)` / UNIQUE `quiz_submissions(user_id,quiz_date)`(하루 1회·멱등) + INDEX `(user_id,quiz_id)`(제출 조회) / INDEX `point_histories(user_id,created_at)`(내역·합계).

- **하루 1회 멱등**: `(user_id,quiz_date)` 유니크 + 채점·적립 단일 트랜잭션(`correct=true`만 `point_histories` 1행 append). Mongo로 결정 시 단일 트랜잭션 보장 재검토.
- **교차 모듈 no-FK**(`user_id`→user) / 같은 모듈 `quiz_id`→quizzes FK.
- **합계**: `totalPoint`는 `SUM(amount)` 집계(별도 잔액 컬럼 없음). 차감은 범위 밖.

### 4-9. `report`

> 스토어: **저장소(추후 결정)** — **논리 스키마**. **1차 MVP 이후**. domain-model `Report`(VO `ReportTarget`·`ReportDetail`).

`reports` — 불변(immutable). `target`(VO `ReportTarget`)·`detail`(VO `ReportDetail`)은 컬럼으로 평탄화.

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | long | PK |
| `reporter_id` | long | NOT NULL · UNIQUE(복합) · → user(값 참조). 응답 비노출 |
| `target_type` | enum `ReportTargetType` | NOT NULL · UNIQUE(복합) |
| `target_id` | long | NOT NULL · UNIQUE(복합) · 다형 참조(community/chat). FK 불가 |
| `reason` | enum `ReportReason` | NOT NULL |
| `detail` | text | NULL, ≤500 · 응답 비노출 |
| `status` | enum `ReportStatus` | NOT NULL, default `RECEIVED` |
| `created_at` | datetime | NOT NULL |

**인덱스**: PK `id` / **UNIQUE `(reporter_id, target_type, target_id)`**(중복 신고 차단, `REPORT_ALREADY_EXISTS` 409) / (선택) INDEX `(target_type, target_id)`(운영자 검토 — 흐름 확정 후).

- **교차 모듈 no-FK**: `reporter_id`(→user)·`target_id`(→community/chat 다형) 값 참조. 다형이라 단일 FK 불가.
- **프라이버시**: `reporter_id`·`detail`은 저장하되 응답 비노출([error-response-guide §6](../api/error-response-guide.md)).
- **불변**: 전이 없음 → `updated_at`/소프트삭제 불요. 자기 신고 차단(`422`)·대상 존재 검증(`404`)·MESSAGE 참여 권한(`403`)은 대상 모듈 공개 쿼리로.

## 5. 관련 문서

- [domain-model](../architecture/domain-model.md) — 모듈별 애그리거트·속성·불변식(설계 정본) · [system-overview](../architecture/system-overview.md) — 스토어 토폴로지
- [ADR-0005](../adr/0005-polyglot-persistence.md)(폴리글랏) · [ADR-0006](../adr/0006-refresh-token-store-redis.md)(refresh=Redis) · [ADR-0002](../adr/0002-inter-module-communication-via-events.md)(교차 모듈 통신)
- [migration-policy](./migration-policy.md) · [api-design-guide](../api/api-design-guide.md) · [error-response-guide](../api/error-response-guide.md) · [api/specs](../api/specs/README.md)

## 6. 결정 필요 (Open Questions)

영속 물리화 전 닫아야 할 **저장소·인프라 결정**(도메인 설계는 [domain-model](../architecture/domain-model.md)에서 확정됨).

1. **저장소 ADR 4건**: `booking`·`chat`·`gamification`·`report`의 스토어(MySQL vs MongoDB) 미결정 → 식별자(BIGINT vs ObjectId)·임베드 VO(`booking_card`/`listing_snapshot`/`choices`) 표현·단일 트랜잭션 보장이 이에 종속.
2. **store별 물리 식별자**: Mongo 모듈(`listing`·`diagnosis`)은 `_id ObjectId`, 추후결정 모듈은 store 확정 시. 외부 노출 식별자(API path)를 ObjectId(string)로 노출할지 별도 숫자키를 둘지 결정.
3. **카운트 정합 전략**: `listings.favoriteCount`·community 카운트의 갱신/배치 재계산 주기, MySQL `CHECK` 가능 버전 확인.
4. **검색/레이트리밋**: community FULLTEXT(ngram) 도입 시점(MVP 이후), 공유·신고 레이트리밋 카운터 저장소(Redis 등 — DB 외).
5. **NEIGHBOR 채팅방 유일성**: `chat_rooms(listing_id=null)`의 복합 유니크 처리(MySQL NULL 비충돌 vs Mongo partial unique) — store 확정 시.
6. **문자열 길이**: 스펙 미명시 항목(`title`·이름·`nickname`·`email`·`country`·`provider_user_id`·`terms_version` 등) 실제 검증 규칙 확정.
7. **이메일 인증 정책**: 인증번호 길이·만료(TTL)·검증 시도 상한·재발송 레이트리밋, 메일 발송 인프라(SES/SMTP 등) 미확정(§4-1 A-2).
8. **직업(`Occupation`) 분류값**: 요구사항 정의서 드롭다운 항목 잘림 → 현재 임시값(`STUDENT`/`EMPLOYEE`/`SELF_EMPLOYED`/`JOB_SEEKER`/`ETC`), 실제 선택지 확정 필요.
9. **닉네임 풀**: `nickname_adjectives`·`nickname_nouns` 단어 시딩·로케일(언어)·조합 포맷(연결/구분자), 재조합 재시도 상한·fallback 규칙, 무작위 선택 전략(앱 로드 vs `RAND()`) 미확정.
10. **국가(`countries`)**: 표시명 다국어(단일 vs `name_en`/`name_ko`), 시드 출처(ISO 3166-1·전체 국가 확장), `users.country`→`countries.code` FK 적용 여부. (`flag`는 국기 이미지 URL(flagcdn.com SVG)로 확정 — 외부 CDN 의존, 자체 호스팅 전환은 후속 검토.)

> refresh 토큰 저장(Redis)·회전·재사용 탐지·TTL(=만료)은 [ADR-0006](../adr/0006-refresh-token-store-redis.md)으로 **확정**돼 결정 필요 항목이 아니다(§4-1 참조).

## 체크리스트

- [ ] 새 테이블/컬렉션이 §2 규약(명명·공통컬럼·타입·enum 문자열·UTC·KRW 정수)을 따른다
- [ ] domain-model의 애그리거트·속성·VO·enum과 필드가 일치한다(필드 의미는 domain-model이 정본)
- [ ] 교차 모듈 참조에 FK를 걸지 않았다(값 참조) — 같은 모듈 안에서만 FK
- [ ] 도메인 불변식의 유니크/인덱스를 반영했다(찜·좋아요·예약 활성·신고 중복·소셜연동)
- [ ] MongoDB 지오 컬렉션에 `2dsphere`, 만료성 데이터에 TTL 인덱스를 두었다
- [ ] 저장소 미정 모듈은 논리 스키마로만 두고 store를 단정하지 않았다
- [ ] MySQL 변경은 [migration-policy](./migration-policy.md) 마이그레이션으로 관리한다
