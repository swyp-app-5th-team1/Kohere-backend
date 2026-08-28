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
| [`auth`](#4-1-auth) | **Redis** + **MySQL** | `refresh:{tokenHash}`·`refresh:user:{userId}`·`email-verify:code:{userId}`·`email-verify:verified:{userId}`·`phone-verify:code:{userId}`·`phone-verify:verified:{userId}`·`signup-phone:code:{정규화번호}`·`signup-phone:verified:{정규화번호}`·`signup-phone:rate:phone:{정규화번호}`·`signup-phone:rate:ip:{IP}`(가입용 SMS 인증·레이트리밋)·`find-email:code:{정규화번호}`·`find-email:verified:{정규화번호}`·`find-email:rate:phone:{정규화번호}`·`find-email:rate:ip:{IP}`(이메일 찾기, US-1-16)·`pwd-reset:{tokenHash}`·`pwd-reset:rate:email:{소문자이메일}`·`pwd-reset:rate:ip:{IP}`(비밀번호 재설정=잠금 해제, US-1-17) / `social_accounts`·`local_accounts`(웹 로컬 자격증명) | ✅ |
| [`user`](#4-2-user) | **MySQL** | `users`·`user_blocks`(사용자 차단, US-4-8)·`countries`·`nickname_adjectives`·`nickname_nouns` | ✅ |
| [`listing`](#4-3-listing) | **MongoDB** | `listings`·`favorites`·`recentListings` | ✅ |
| [`diagnosis`](#4-4-diagnosis) | **MongoDB** | `diagnoses`(제출 결과)·`diagnosisQuestions`(문항·선택지 카탈로그)·`diagnosisSuggestions`(추천 조정 제안)·`diagnosisFlowSessions`(v2 서버 주도 진행 세션) — 인라인 언어-키 맵 번역, US-2-5·US-2-6·US-2-7 | ✅ |
| [`booking`](#4-5-booking) | **MySQL** — `V9`/`V11`로 이미 배포됨([ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏 배치 표에는 아직 미반영 — 확인 필요) | `bookings`·`booking_reports`(예약 신고 접수, US-4-9)·`booking_report_reasons`(신고 사유 카탈로그) | ✅ |
| [`chat`](#4-6-chat) | **MySQL** | `chat_rooms`·`chat_room_members`·`chat_messages` (`V24`) | ✅ |
| [`community`](#4-7-community) | **MySQL** | `posts`·`comments`·`post_likes`·`post_hashtags` | 이후 |
| [`gamification`](#4-8-gamification) | **MongoDB** | `quizzes`(문항·선택지 카탈로그 — 인라인 언어-키 맵·무상태 채점) | 이후 |
| [`report`](#4-9-report) | **MySQL** | `chat_reports`·`chat_report_evidence` (`V26`) | ✅ |
| [`lifetip`](#4-10-lifetip) | **MongoDB** | `lifeTipTopics`·`lifeTips` — 인라인 언어-키 맵 번역, US-8-1·US-8-2 | 이후 |

> `access` 토큰은 무상태 JWT라 저장소에 없다. `common`은 공유 커널(스키마 없음).

## 2. 설계 규약

### 2-1. 명명 규칙

| 스토어 | 컨테이너 | 필드/키 |
| --- | --- | --- |
| MySQL | 테이블 `snake_case` 복수형(`users`, `post_likes`) | 컬럼 `snake_case`(`user_id`, `created_at`) |
| MongoDB | 컬렉션 lowerCamel 복수형(`listings`, `recentListings`) | 필드 `camelCase`(`userId`, `monthlyRentMax`) |
| Redis | 키 colon 네임스페이스(`refresh:{tokenHash}`, `refresh:user:{userId}`) | — |

- URL/JSON은 [api-design-guide](../api/api-design-guide.md)대로 `camelCase`/`kebab-case`. DB 컬럼명과 API 필드명이 다르면(`move_in_date`↔`moveInDate`) 어댑터에서 매핑한다.
- domain-model의 값 객체(VO)는 임베드형 스토어(MongoDB)에선 **임베드 객체**로, 관계형(MySQL)에선 **루트 테이블의 컬럼 묶음**으로 평탄화해 매핑한다(예: `FullName`→단일 `name` 컬럼).

### 2-2. 공통 컬럼 표준

| 컬럼 | 적용 | 비고 |
| --- | --- | --- |
| 식별자 | MySQL `id BIGINT PK AUTO_INCREMENT` · Mongo `_id ObjectId` · Redis 키 자체 | 외부 노출 식별자는 store별 네이티브 타입을 따른다. **예외: `diagnoses._id`는 ObjectId가 아니라 Long 순번**(`diagnosisSequences` 카운터 채번 — §4-4) |
| 생성시각 | 전 애그리거트 `created_at`/`createdAt`(또는 의미상 `submitted_at` 등) (UTC) | MySQL `DATETIME(6)` / Mongo ISODate |
| 수정시각 | **가변** 애그리거트 `updated_at`/`updatedAt` | `user`·`listings`·`posts` 등 |
| 소프트삭제 | **`community`만** `deleted`+`deleted_at` · **예외: `booking`은 참여자별** `tenant_deleted_at`+`landlord_deleted_at`(아래 註) | 그 외는 상태 enum으로 표현(`user.status=WITHDRAWN`, `listing.status`=심사 상태 4종(`PENDING`/`PUBLISHED`/`REJECTED`/`UPDATE_PENDING`)뿐이라 **삭제·중지를 뜻하는 값이 없다**, `booking.status=CANCELED`=취소 — 단 취소는 **삭제가 아니다**, 아래 註) |

- **`booking` 참여자별 소프트삭제 예외(#169 · US-4-7)**: 위 규약("소프트삭제는 `community`만, 그 외는 상태 enum")의 **유일한 예외**다. `bookings`는 세입자(`tenant_id`)와 임대인(`landlord_id`)이 **공유하는 1행**이라 단일 `deleted` flag로는 "누구의 목록에서 숨겼는가"를 표현할 수 없다 — 한쪽이 지우면 상대의 예약 기록까지 함께 사라지는 **데이터 손실**이 된다. 공유 필드인 `status`도 같은 이유로 못 쓴다(`status=CANCELED`는 **예약 취소**라는 별개 사실이지 "내 목록에서 숨김"이 아니고, 취소를 포함한 상태 전이 자체가 현재 미구현이라 생성 시 `REQUESTED` 고정이다 — §4-5). 그래서 `booking`만 **참여자별 삭제 시각 2컬럼**(`tenant_deleted_at`·`landlord_deleted_at`)을 두고 요청자 역할에 해당하는 컬럼만 채우며, 상대에겐 그 예약이 그대로 보인다. 행 전체를 감추는 `community`의 `deleted`+`deleted_at`와는 목적이 다르다.

### 2-3. 데이터 타입 가이드

| 용도 | MySQL | MongoDB | 논리(추후결정) |
| --- | --- | --- | --- |
| 식별자 | `BIGINT` | `ObjectId` | `long` |
| 짧은 문자열/enum | `VARCHAR(n)` | `string` | `string`/`enum` |
| 장문 | `TEXT` | `string` | `text` |
| 날짜(시각 없음) | `DATE` | ISODate(date) | `date` |
| 시각(UTC) | `DATETIME(6)` | ISODate | `datetime` |
| 불리언 | `BOOLEAN`(`TINYINT(1)`) | `bool` | `bool` |
| 금액(KRW)·카운트 | `INT`/`BIGINT` | `int` | `int` |
| 값 객체(VO) | 컬럼 묶음으로 평탄화 | 임베드 객체 | 임베드/평탄화 |
| 좌표 | — | GeoJSON `Point`(`[lng,lat]`) + `2dsphere` | — |

- **enum**: 문자열 **UPPER_SNAKE** 저장. MySQL 네이티브 `ENUM` 미사용(값 추가 진화 용이). 값 카탈로그는 [domain-model](../architecture/domain-model.md) 각 모듈 "상태(enum)". 회원 역할 `UserType`은 `TENANT`(세입자/외국인)·`LANDLORD`(임대인)·`ADMIN`(관리자) 세 값이며 `users.user_type`에 `VARCHAR(16)`로 저장한다(DEFAULT `TENANT`). `TENANT`·`LANDLORD`는 온보딩 제출로 확정·불변이고, **`ADMIN`만 예외로 온보딩 이후 운영자의 수동 `UPDATE`로 부여**한다 — 가입 경로가 없다.
- **금액**: 원(KRW) 정수, 소수점 없음.
- **시각**: UTC ISO-8601, 저장도 UTC([api-design-guide §6](../api/api-design-guide.md)).

### 2-4. 제약·무결성 (공통)

- **FK는 같은 모듈 안에서만.** 교차 모듈 참조는 **store가 같아도 FK 금지** — 식별자 값만 보유한다(Modulith 독립성·[ADR-0002](../adr/0002-inter-module-communication-via-events.md)). 교차 스토어 조인·FK·분산 트랜잭션 금지([ADR-0005](../adr/0005-polyglot-persistence.md) D5·D6) → 애플리케이션 레벨 조인/이벤트.
- **유니크 제약은 도메인 불변식대로**: `social_accounts(provider,provider_user_id)` · `local_accounts(email)` · `local_accounts(user_id)` · `users(nickname)` · `users(phone_number)` · `user_blocks(blocker_id,blocked_user_id)` · `nickname_adjectives(word)` · `nickname_nouns(word)` · `favorites(userId,listingId)` · `bookings(tenant_id,room_offer_id)` · `booking_report_reasons(code,lang)` · `post_likes(post_id,user_id)` · `chat_reports(reporter_id,chat_room_id)` · `chat_rooms(listing_id,tenant_id,landlord_id)`.
- **카운트 정합**(`community` like/comment/share, `listings.favoriteCount`)은 단일 store 트랜잭션 또는 원자적 증감 + 배치 재계산으로 유지(음수 방지).
- **민감정보**(비자·이메일·토큰 원문·인증번호 원문)는 응답·로그 마스킹([error-response-guide §6](../api/error-response-guide.md)). 컬럼 암호화 여부는 [§6](#6-결정-필요-open-questions).

## 3. 마이그레이션

- **MySQL**: Flyway(`flyway-core`,`flyway-mysql`) 후보 — DDL 버전 관리. 정본 [migration-policy](./migration-policy.md).
- **MongoDB**: 스키마리스 + 애플리케이션 레벨 `schemaVersion` 필드([ADR-0005](../adr/0005-polyglot-persistence.md) D7). 인덱스(`2dsphere`·TTL 등)는 부트스트랩이 기동 시 멱등 생성. **스키마·문서 이행(validator 전이·옛 인덱스 삭제·문서 필드 이행)은 모듈별 Mongock `@ChangeUnit`으로 환경당 1회 적용**하고, **레퍼런스 데이터 적재는 마이그레이션이 아니라 운영자가 정본 JSON으로 주입**한다([ADR-0032](../adr/0032-mongodb-migration-runner.md), [migration-policy §8](./migration-policy.md#8-mongodb-변경-관리)·[§8-1](./migration-policy.md#8-1-시드-주입-절차)).
- **Redis**: 스키마 없음. 키 설계·TTL은 코드 상수로 관리([ADR-0006](../adr/0006-refresh-token-store-redis.md)).

## 4. 모듈별 스키마 (Data Model by Module)

> 표기: 각 스토어 스키마를 **표**(필드·타입·키/제약) + 인덱스 + 비고로 적는다. 필드 의미·도메인 타입은 [domain-model](../architecture/domain-model.md)이 정본이고 여기선 물리 타입·키·제약을 둔다. 관계는 FK 컬럼·비고로 표기하며, **교차 모듈/스토어 참조는 FK 없이 식별자 값 참조**다.

### 4-1. `auth`

> 스토어: **Redis**(refresh, [ADR-0006](../adr/0006-refresh-token-store-redis.md)) + **MySQL**(소셜 연동·웹 로컬 자격증명). domain-model: `RefreshToken`·`SocialAccount`·`LocalAccount`.

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

**온보딩 완료(`ACTIVE`) 사용자**의 이메일 소유 확인(#192로 온보딩 단계 전용 → `ACTIVE` 전용 접근으로 반전 — 온보딩 토큰으로는 호출 불가, `hasRole("USER")` 정식 토큰 필요). 인증번호는 **SHA-256(+pepper)** 해시로만 보관(원문 미보관)하고, TTL로 자동 소멸한다. 사용자(`ACTIVE`) 단위로 1건이다. domain-model `EmailVerification`.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `email-verify:code:{userId}` | Hash | `email` · `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` · `status`(enum `PENDING`/`VERIFIED`) | 인증번호 만료(예: 5분 — 확인 필요) | 인증번호 발송·검증. 검증 시 `codeHash` 대조 + `attempts` 증가, 일치 시 `VERIFIED` 전이 |
| `email-verify:verified:{userId}` | String/Hash | 검증 완료 `email` | 인증 마커 만료(예: 30분 — 확인 필요) | 이메일 인증 완료 표시. **실제 `users.email` 변경 반영은 후속 이슈**(#192 범위 밖 — 이번엔 접근을 `ACTIVE` 전용으로 제한만) |

- **발송**: 인증번호 메일을 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SMTP)로 **동기 발송**하고, **발송 성공 시에만** `email-verify:code:{userId}`를 (재)설정한다(발송 실패 시 챌린지 미저장 + `502 UPSTREAM_ERROR`로 응답·재시도 유도). 재발송·검증 시도는 레이트리밋·`attempts` 상한(확인 필요)으로 보호, 초과 시 `429 TOO_MANY_REQUESTS`.
- **검증**: 입력 인증번호 해시가 `codeHash`와 일치하고 미만료·시도 미초과면 `email-verify:verified:{userId}`에 이메일 기록(코드 키는 만료/삭제). 불일치·만료는 `422 AUTH_EMAIL_VERIFICATION_FAILED`.
- **접근 제한(`ACTIVE` 전용)**: 이메일 인증 API(`POST /api/v1/auth/email/verification-code`·`/verify`)는 **온보딩 완료(`ACTIVE`) 사용자 전용**이다(SecurityConfig tier3 `hasRole("USER")`) — 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`, `ACTIVE` 정식 토큰만 허용한다. **온보딩 제출은 이메일 인증을 게이트하지 않는다**(#192로 이메일 인증 선행·온보딩 제출 대조 폐지 — 온보딩 선행조건은 약관 동의(`TERMS_AGREED`)뿐, `AUTH_EMAIL_NOT_VERIFIED` 제거). 이번엔 `verify` 성공이 `users.email`을 바꾸지 않는다(실제 이메일 변경 반영은 후속 이슈).
- **민감정보**: `email`은 응답·로그 마스킹, 인증번호 원문은 보관·로그하지 않는다(해시만).
- **가입 전(비로그인) 이메일 인증은 별도 키스페이스다**: 임대인 웹 회원가입의 선행 인증은 `userId`가 없어 이 키를 쓸 수 없다 — 아래 **(A-8)** `signup-email:*` 참조. 정책값·발송 포트·해시는 공유하지만 키와 접근 통제가 다르다.

#### (A-3) Redis — 연락처 인증(`PhoneVerification`)

임대인 온보딩 중 연락처(휴대폰) 소유 확인(**임대인 전용**, 세입자 이메일 인증 A-2와 대칭 — 세입자 트랙의 이메일 인증을 임대인 트랙에서 대체, [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). 인증번호는 **SHA-256(+pepper)** 해시로만 보관(원문 미보관)하고, TTL로 자동 소멸한다. 사용자(온보딩 중 PENDING) 단위로 1건이다. domain-model `PhoneVerification`.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `phone-verify:code:{userId}` | Hash | `phoneNumber` · `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` · `status`(enum `PENDING`/`VERIFIED`) | 인증번호 만료(이메일과 동일 — 5분) | 인증번호 발송·검증. 검증 시 `codeHash` 대조 + `attempts` 증가, 일치 시 `VERIFIED` 전이 |
| `phone-verify:verified:{userId}` | String/Hash | 검증 완료 `phoneNumber` | 온보딩 토큰 만료(이메일과 동일 — 30분) | 임대인 온보딩 제출·프로필 연락처 변경 시 제출 `phoneNumber`와 대조해 미인증 거름(`AUTH_PHONE_NOT_VERIFIED`) |

- **발송**: 인증번호 SMS를 아웃바운드 포트 `VerificationSmsSender`(인프라 어댑터: SMS API — 구체 provider는 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md))로 **동기 발송**하고, **발송 성공 시에만** `phone-verify:code:{userId}`를 (재)설정한다(발송 실패 시 챌린지 미저장 + `502 UPSTREAM_ERROR`로 응답·재시도 유도). 재발송·검증 시도는 레이트리밋·`attempts` 상한(이메일과 동일 — 재발송 간격 60초·검증 시도 5회·인증번호 6자리)으로 보호, 초과 시 `429 TOO_MANY_REQUESTS`.
- **검증**: 입력 인증번호 해시가 `codeHash`와 일치하고 미만료·시도 미초과면 `phone-verify:verified:{userId}`에 연락처 기록(코드 키는 만료/삭제). 불일치·만료는 `422 AUTH_PHONE_VERIFICATION_FAILED`.
- **임대인 온보딩 제출**: `auth`가 `phone-verify:verified:{userId}`의 연락처와 제출 `phoneNumber`를 대조 → 일치해야 `user` 온보딩 완료 명령 진행. 확정 `phoneNumber`는 `users.phone_number`로 영속(아래 §4-2), 인증 흔적은 TTL로 소멸한다(영속 안 함).
- **민감정보**: `phoneNumber`는 응답·로그 마스킹(예 `010-****-5678`), 인증번호 원문은 보관·로그하지 않는다(해시만).
- **가입 전(비로그인) 인증은 별도 키스페이스다**: 임대인 웹 회원가입의 선행 인증은 `userId`가 없어 이 키를 쓸 수 없다 — 아래 **(A-5)** `signup-phone:*` 참조.

#### (A-4) 사업자번호 검증(`POST /api/v1/auth/business/verify`) — 무상태, Redis 마커 없음

임대인 사업자등록번호 유효 확인은 **온보딩과 분리된 무상태(stateless) 검증 API**다. 온보딩을 마친(`ACTIVE`) 임대인이 정식 access 토큰(`ROLE_USER`)으로 `POST /api/v1/auth/business/verify`를 직접 호출한다. 외부 사업자등록정보 검증 API(국세청 사업자등록정보 진위·상태 기반)로 동기 검증해 정상(계속) 사업자면 `verified:true`를 응답한다. 아웃바운드 포트는 `BusinessRegistryVerifier`(인프라 어댑터=사업자등록정보 검증 API, 구체 provider는 [ADR-0033](../adr/0033-business-registry-verification.md)). 정책 골격은 ADR-0033(Proposed — 확인 필요).

- **매물 등록은 이 API를 호출하지 않는다**: 매물 등록(`POST /api/v2/listings`, §4-3)은 요청 본문의 사업자등록번호를 **형식(숫자 10자리)만 검증하고 `listings.businessRegistrationNumber`에 원문으로 저장**한다 — 등록 시점에 외부 검증을 자동 수행하지 않으며, **진위·영업 상태는 관리자가 승인 심사(`PENDING` → `PUBLISHED`/`REJECTED`, 후속)에서 수동으로 확인**한다. 이 검증 API는 임대인이 필요할 때 스스로 호출하는 별도 경로로 남는다(엔드포인트 자체는 유지).
- **무상태(저장 없음)**: 검증 결과를 서버에 저장하지 않는다 — **Redis 마커(`business-verify:verified:{userId}`)는 존재하지 않고**, `users.business_registration_number_hash` 컬럼에도 이 경로에서 쓰지 않는다. 검증 결과는 응답(HTTP body)에만 담긴다. 따라서 이 절엔 Redis 키스페이스가 없다.
- **검증**: `POST /api/v1/auth/business/verify`가 `BusinessRegistryVerifier`로 동기 검증한다. 정상(계속) 사업자면 `verified:true`. 미등록·휴폐업·진위 실패는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`, 외부 장애·타임아웃은 공통 `502 UPSTREAM_ERROR`(재시도 유도). 검증 서비스 회신 상호·대표자는 검증 응답 표시용으로만 쓰며 저장하지 않는다. 레이트리밋 임계값 미정(확인 필요).
- **인가**: 정식 토큰(`ACTIVE`, `ROLE_USER`) 필수. 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출 시 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) `ACTIVE` 사용자면 `403 FORBIDDEN`. 온보딩 제출과는 무관하다(온보딩 게이트에 사업자번호 항목 없음 — §4-2·§4-1 A-3).
- **민감정보**: 사업자번호 원문은 보관·로그하지 않고, 응답엔 마스킹값(예 `****567890`)만 노출한다.

#### (A-5) Redis — 가입용 연락처 인증(`SignupPhoneVerification`, 임대인 웹·비로그인)

임대인 웹 회원가입(§4-1 C `local_accounts` · [ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md)) **제출 전에** 수행하는 번호 소유 인증(US-1-13). 인증번호 정책(6자리·코드 TTL 5분·검증 마커 30분·검증 시도 5회·재발송 간격 60초 — `app.phone.*`)과 발송 포트 `VerificationSmsSender`, 해시(SHA-256+pepper)는 **(A-3)과 그대로 공유**한다. 원본 차이는 **키와 접근 통제 둘**이다 — 가입 전 단계라 `userId`가 없어 **정규화(숫자만)한 휴대폰 번호가 곧 키**이고, 두 엔드포인트가 **permitAll**이다. 나머지 차이(번호·IP 이중 레이트리밋, 검증 실패를 `422` 한 코드로 통일)는 그 둘에서 따라 나온다 — 토큰으로 주체를 묶을 수 없으니 남는 식별자로 한도를 걸어야 하고, 응답 차이가 곧 챌린지 상태 노출이 되니 구분하지 않는다. domain-model `SignupPhoneVerification`.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `signup-phone:code:{정규화번호}` | Hash | `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` | 인증번호 만료(A-3과 동일 — 5분) | 인증번호 발송·검증. 대조 대상 번호는 **값이 아니라 키**다(A-3은 키가 `userId`라 번호를 값으로 들지만 여기선 중복이 된다) |
| `signup-phone:verified:{정규화번호}` | String | 상수 `"1"`(존재 자체가 의미) | 검증 마커 만료(A-3과 동일 — 30분) | 웹 회원가입 제출이 대조하고 **성공 시 소비(삭제)** 한다. 소비처가 하나뿐이라 용도 구분 필드가 없다 |
| `signup-phone:rate:phone:{정규화번호}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 같은 번호로의 발송 남용 차단 — 초과 시 `429 TOO_MANY_REQUESTS` |
| `signup-phone:rate:ip:{IP}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 번호를 바꿔가며 발송비를 태우는 남용 차단 — 초과 시 `429` |

- **발송**: (A-3)과 같이 **동기 발송**하고 **발송에 성공한 뒤에만** `signup-phone:code:{정규화번호}`를 (재)설정한다(발송 실패 시 챌린지 미저장 + `502 UPSTREAM_ERROR`). 저장 전에 **재발송 쿨다운 60초 → 번호·IP 시간당 한도** 순으로 판정하며, 셋 중 무엇을 어겨도 응답은 `429 TOO_MANY_REQUESTS` 하나다.
- **레이트리밋 한도**(`app.auth.signup-phone.*`): **번호 5회/1시간 · IP 20회/1시간**(#229 D6). 카운터는 `INCR` + 첫 증가에서만 `EXPIRE`를 거는 **고정 창**이고, **발송 성공이 아니라 시도**를 센다 — 성공분만 세면 provider 장애를 반복시키는 것만으로 한도를 무력화할 수 있다. IP는 리버스 프록시(Caddy) 뒤라 `X-Forwarded-For` 최좌측 값을 쓰며, 그 값은 호출자가 위조할 수 있으므로 **IP 한도는 비용 가드이지 인가가 아니다**(우회 불가한 방어는 번호 한도·쿨다운).
- **검증**: 입력 인증번호 해시가 `codeHash`와 일치하고 미만료·시도 미초과면 `signup-phone:verified:{정규화번호}`에 마커를 남기고 코드 키를 삭제한다. **챌린지 부재·불일치·만료·시도 상한 초과가 모두 `422 AUTH_PHONE_VERIFICATION_FAILED`** 로, 시도 초과를 `429`로 구분하는 (A-3)과 다르다 — 비로그인 경로에서는 응답의 차이 자체가 챌린지 존재·시도 잔량을 알려 주는 신호가 된다.
- **계정 존재 여부 비노출**: 발송·확인 어느 쪽도 `users`를 조회하지 않는다. 가입 이력이 있는 번호든 없는 번호든 같은 응답이며, 연동 판정은 가입 제출 시점에만 이뤄진다.
- **이 마커는 다른 용도로 재사용하지 않는다**: 값이 상수 `"1"`이라 **무슨 용도로 인증했는지가 값에 없고**, 그 전제는 소비처가 웹 회원가입 하나뿐이라서 성립한다. 이메일 찾기(US-1-16)가 같은 마커를 세우면 전제가 깨져 **이메일을 찾으려고 받은 인증번호 하나로 회원가입까지 통과**하게 된다 — 인증의 인가 범위가 조용히 넓어지는 것이다. 그래서 값에 용도 필드를 더하는 대신 **키스페이스를 갈랐다**(아래 **(A-6)** `find-email:*`, **(A-8)** `signup-email:*`) — 필드로 갈라도 소비처 한쪽이 그 필드를 안 보면 같은 사고가 나지만, 키가 다르면 애초에 잘못 쓸 수 없다. **가입 제출은 (A-5)와 (A-8) 두 마커를 함께 요구**하며, 둘은 서로의 게이트를 통과시키지 못한다.
- **앱 심사용 고정 인증번호 우회(`FixedVerificationPolicy`) 미적용**: 그 우회는 `userId` + Google 소셜 계정으로 판정하는데 가입 전 단계에는 둘 다 없다. 이 경로는 프로파일과 무관하게 항상 실제 발급·발송을 타며, 로컬은 `LoggingVerificationSmsSender`가 인증번호를 콘솔에 찍는다.
- **민감정보**: 번호·IP가 **키에 실리지만** 전부 TTL(코드 5분·마커 30분·카운터 1시간)로 소멸해 영속하지 않는다. 응답·로그의 번호는 마스킹(예 `010-****-5678`), 인증번호 원문은 보관·로그하지 않는다(해시만).

#### (A-6) Redis — 이메일 찾기용 연락처 인증(임대인 웹·비로그인)

가입한 이메일을 잊은 임대인이 **번호 소유를 먼저 증명하고** 자기 이메일을 마스킹 형태로 돌려받는 경로(US-1-16)다. 인증번호 정책(6자리·코드 TTL 5분·검증 마커 30분·검증 시도 5회·재발송 간격 60초 — `app.phone.*`)·발송 포트 `VerificationSmsSender`·해시(SHA-256+pepper)·permitAll·`422` 한 코드 통일까지 **(A-5)와 전부 같다**. 다른 것은 **키스페이스 하나**이고, 그 하나가 이 절이 따로 있는 이유다 — (A-5)의 `signup-phone:verified:*`는 용도 구분 필드가 없어(그쪽 소비처가 하나뿐이라 필요가 없었다) **재사용하면 이메일 찾기용 인증 하나로 회원가입까지 통과**한다. 인가 범위가 조용히 넓어지는 종류의 사고라 **키를 갈라 잘못 쓸 수 없게** 만든다.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `find-email:code:{정규화번호}` | Hash | `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` | 인증번호 만료((A-5)와 동일 — 5분) | 이메일 찾기용 인증번호 발송·검증. 대조 대상 번호는 (A-5)와 같이 **값이 아니라 키**다 |
| `find-email:verified:{정규화번호}` | String | 상수 `"1"`(존재 자체가 의미) | 검증 마커 만료((A-5)와 동일 — 30분, `app.phone.verified-ttl-seconds`) | `POST /api/v1/auth/email/find`가 대조하고 **성공 시 소비(삭제)** 한다. 이 마커도 소비처가 하나뿐이라 용도 필드가 없다 — **키스페이스가 곧 용도**다 |
| `find-email:rate:phone:{정규화번호}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 같은 번호로의 발송 남용 차단 — 초과 시 `429 TOO_MANY_REQUESTS` |
| `find-email:rate:ip:{IP}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 번호를 바꿔가며 발송비를 태우는 남용 차단 — 초과 시 `429` |

- **레이트리밋 버킷도 (A-5)와 나눠 쓴다**(`app.auth.find-email.*` — 번호 5회/1시간 · IP 20회/1시간): 버킷을 공유하면 이메일을 찾느라 한도를 태운 사람이 **회원가입 인증번호를 못 받는**, 즉 한쪽 남용이 다른 쪽 정상 흐름을 막는 결합이 생긴다. 카운터 방식(고정 창 `INCR` + 첫 증가에서만 `EXPIRE`, 성공이 아니라 **시도**를 셈)과 판정 순서(재발송 쿨다운 — 번호·IP 한도)는 (A-5)와 같다.
- **가입 여부를 노출하지 않는다**: 발송·확인 어느 쪽도 `users`·`local_accounts`를 조회하지 않는다 — 가입 이력이 있는 번호든 없는 번호든 같은 응답이다. 계정 존재 판정은 마커를 소비하는 `email/find` **한 곳에서만** 일어나고, 거기서는 호출자가 **소유를 증명한 자기 번호**로만 조회할 수 있어 `404 AUTH_WEB_ACCOUNT_NOT_FOUND`로 존재를 드러내도 열거 표면이 열리지 않는다.
- **이름 대조는 `local_accounts.name` 단독**([§4-1 C](#4-1-auth)): `users.name`으로 폴백하지 않는다. 폼 스냅샷이라 정본과 다를 수 있다는 것을 알면서도 그렇게 두는 이유는, 대조 대상을 둘로 늘리면 **"둘 중 하나만 맞아도 통과"** 가 되어 대조가 느슨해지기 때문이다. **이름 불일치와 계정 미존재는 같은 `404`로 수렴**시킨다 — 가르면 번호 소유자에게 "이 번호의 이름은 무엇인가"를 맞혀 보게 해 주는 오라클이 된다.
- **성공하면 마커를 소비한다**: 조회에 성공한 순간 `find-email:verified:*`를 지운다. 남겨 두면 마커 하나로 30분 동안 무제한 반복 조회가 되어, 이름을 바꿔 가며 대조를 시도할 수 있다.
- **민감정보**: 번호·IP가 **키에 실리지만** 전부 TTL(코드 5분·마커 30분·카운터 1시간)로 소멸해 영속하지 않는다. 응답의 이메일은 마스킹(예 `ki***@work.com`), 번호도 마스킹(예 `010-****-5678`)해 내보내고 인증번호 원문은 보관·로그하지 않는다(해시만).

#### (A-7) Redis — 비밀번호 재설정 토큰(임대인 웹·비로그인 · 잠금 해제 겸용)

비밀번호를 잊었거나 10회 연속 실패로 **잠긴**([§4-1 C](#4-1-auth) `locked_at`) 임대인이 메일로 받은 일회용 링크로 자격증명을 되찾는 경로(US-1-17)다. **"비밀번호 찾기"와 "계정 잠금 해제"는 같은 API**이고 화면만 둘이다 — 잠금을 풀 자격이 "새 비밀번호를 세울 수 있는 메일함 소유자"와 정확히 같아서, 해제 전용 API를 따로 두면 **같은 증명으로 열리는 문이 둘**이 되고 둘 다 영원히 같이 관리해야 한다. **`app.auth.web.password-reset.enabled` 토글 뒤에 있으며 local·dev만 켠다**(prod 미배포).

토큰은 **불투명 난수**(`"pr_" + Base64Url(SecureRandom 32바이트)` — refresh의 `rt_`와 같은 모양)이고, 서버는 **`SHA-256(토큰 + pepper)`만** 키에 담는다(원문 비저장). pepper는 refresh·인증번호가 쓰는 `app.auth.email-pepper`를 그대로 재사용해 **새로 배선할 시크릿이 없다**. MySQL 컬럼이 아니라 Redis에 두는 이유는 **만료가 곧 삭제**여야 하기 때문이다 — 30분짜리 일회용 토큰을 컬럼으로 두면 만료 레코드 청소가 별도 배치가 된다. 잠금(`locked_at`)이 반대로 컬럼인 것과 짝을 이룬다: **잠금은 저절로 풀리면 안 되고, 토큰은 저절로 죽어야 한다.**

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `pwd-reset:{tokenHash}` | String(JSON) | `userId`(→ users.id) · `email` · `issuedAt` · `expiresAt` | 토큰 만료(`app.auth.web.password-reset.token-ttl-seconds` — 30분) | 링크 사전 확인이 **읽기만** 하고, 재설정 확정이 **원자 소비**한다 |
| `pwd-reset:rate:email:{소문자이메일}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 한 사람 메일함에 링크를 퍼붓는 남용 차단 — 초과 시 `429 TOO_MANY_REQUESTS` |
| `pwd-reset:rate:ip:{IP}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 이메일을 바꿔가며 발송비를 태우는 남용 차단 — 초과 시 `429` |

- **Hash가 아니라 값 하나(JSON)다 — 원자 소비 때문이다.** 필드를 Hash로 쪼개면 소비가 `HGETALL` + `DEL` 두 명령이 되어 원자적일 수 없고, 같은 링크를 동시에 두 번 눌렀을 때 둘 다 통과해 **일회용이 아니게 된다**(메일 클라이언트 prefetch·더블클릭이 흔하다). 값 하나로 두면 `GETDEL` 한 명령이 읽기와 삭제를 함께 끝낸다 — Lua 스크립트를 들이지 않고 같은 보장을 얻는다.
- **소비는 원자적이어야 한다**: 확정 시 `GETDEL`(또는 Lua) **한 번**으로 읽고 지운다. `find` → 검증 → `delete`로 쪼개면 동시에 들어온 두 요청이 같은 토큰으로 통과해 **한 링크가 두 번 쓰인다**.
- **사전 확인은 소비하지 않는다**: 메일 클라이언트·보안 스캐너가 링크를 미리 여는 일이 흔해, 확인 단계에서 지우면 **사용자가 클릭하기도 전에 링크가 죽는다**. 사전 확인이 돌려주는 `expiresIn`은 고정값이 아니라 그 키의 **남은 TTL**이다.
- **확정 순서가 계약이다**: 토큰 원자 소비 → `local_accounts.password_hash` 교체(MySQL) → 그 사용자 refresh 전량 무효화(Redis, (A) `refresh:user:{userId}`) → 로그인 시도 레이트리밋 카운터 삭제(Redis). MySQL과 Redis에 걸친 원자성은 불가능하므로([§2-4](#2-4-제약무결성-공통) 교차 스토어 트랜잭션 금지), 중간에 끊겼을 때 **남는 상태가 "토큰만 소비됨"**(사용자가 링크를 다시 요청하면 그만)이 되도록 이 순서를 지킨다. 뒤집으면 비밀번호는 이미 바뀌었는데 토큰이 살아 있는 **재사용 창**이 남는다.
- **잠금 해제는 컬럼 갱신으로 일어난다**: 비밀번호를 교체하는 같은 트랜잭션에서 `failed_login_attempts=0`·`locked_at=NULL`로 되돌린다([§4-1 C](#4-1-auth)) — 잠긴 채로 새 비밀번호만 세워지면 재설정을 마치고도 `423`으로 막혀 사용자가 같은 링크를 다시 요구하게 된다.
- **새 세션은 발급하지 않는다**: 재설정 응답에 refresh 쿠키를 싣지 않고 로그인 화면으로 보낸다. 메일함이 털린 경우 "비밀번호가 바뀐다"와 "공격자가 즉시 로그인된 세션을 쥔다"는 피해 크기가 다르고, 로그인 한 번을 더 요구하는 비용이 그보다 싸다.
- **발송은 계정 존재를 가르지 않는다**: 가입되지 않은 이메일에도 **같은 200**을 준다(키를 만들지 않고 메일만 보내지 않는다). 이 엔드포인트는 선행 게이트가 없어 임의 이메일로 부를 수 있어서, 응답을 가르는 순간 **완전한 열거 오라클**이 된다. 다만 발송이 동기라 **가입 계정은 SMTP 왕복 시간이 들고 `502`가 날 수 있는 반면 미가입은 즉시 200**이라 응답 시간·status 분포로는 존재가 드러난다 — 레이트리밋은 이 누출의 완화책이 아니라 발송비·남용 방어다. 이 한계는 **받아들인다**.
- **민감정보**: 토큰 원문은 저장·로그하지 않고(해시만), 값의 `email`은 사전 확인 응답에서 마스킹해 내보낸다. 키·값·카운터 전부 TTL로 소멸해 영속하지 않는다.

#### (A-8) Redis — 가입용 이메일 인증(임대인 웹·비로그인)

임대인 웹 회원가입([§4-1 C](#4-1-auth) `local_accounts`) **제출 전에** 수행하는 이메일 소유 인증(US-1-18)이다. 인증번호 정책(6자리·코드 TTL 5분·검증 마커 30분·검증 시도 5회·재발송 간격 60초 — `app.email.*`)과 발송 포트 `VerificationEmailSender`, 해시(SHA-256+pepper)는 **(A-2)와 그대로 공유**한다. 원본 차이는 **키와 접근 통제 둘**이다 — 가입 전 단계라 `userId`가 없어 **정규화(trim + 소문자)한 이메일이 곧 키**이고, 두 엔드포인트가 **permitAll**이다. 나머지 차이(이메일·IP 이중 레이트리밋, 검증 실패를 `422` 한 코드로 통일)는 그 둘에서 따라 나온다 — (A-5)가 SMS 채널에서 같은 이유로 갈라진 것과 대칭이다. domain-model `SignupEmailVerification`.

**왜 이 인증이 필요한가** — 웹 이메일은 **로그인 ID이자 비밀번호 재설정 메일이 도착할 유일한 주소**이고, 그 재설정이 계정 잠금 해제를 겸한다((A-7)). 검증 없이 가입하면 오타로 등록한 계정은 **잠기는 순간 자력 복구 경로가 사라진다**.

**키스페이스** (Redis · AWS ElastiCache)

| 키 패턴 | 자료구조 | 값(필드) | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `signup-email:code:{정규화이메일}` | Hash | `codeHash` · `attempts`(int) · `issuedAt` · `expiresAt` | 인증번호 만료((A-2)와 동일 — 5분) | 인증번호 발송·검증. 대상 이메일은 **값이 아니라 키**다((A-5)와 같은 이유 — (A-2)는 키가 `userId`라 이메일을 값으로 들지만 여기선 중복이 된다) |
| `signup-email:verified:{정규화이메일}` | String | 상수 `"1"`(존재 자체가 의미) | 검증 마커 만료((A-2)와 동일 — 30분) | 웹 회원가입 제출이 대조하고 **성공 시 소비(삭제)** 한다. 소비처가 하나뿐이라 용도 구분 필드가 없다 |
| `signup-email:rate:email:{정규화이메일}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 한 주소에 인증번호를 퍼붓는 남용 차단 — 초과 시 `429 TOO_MANY_REQUESTS` |
| `signup-email:rate:ip:{IP}` | String(counter) | 1시간 창의 발송 **시도** 수 | 1시간(첫 `INCR`에서 `EXPIRE`) | 주소를 바꿔가며 태우는 남용 차단 — 초과 시 `429` |

- **키스페이스를 (A-2)·(A-5)·(A-6)과 모두 나눈다.** 마커 값이 상수 `"1"`이라 **무슨 용도로 인증했는지가 값에 없고**, 그 전제는 소비처가 하나뿐이라야 성립한다((A-5)가 같은 이유로 (A-6)과 갈렸다). 필드로 용도를 표현하는 대신 키를 나누면 **읽는 쪽이 검사를 잊어도 조회 자체가 실패**한다.
- **판정 순서가 계약이다 — ① 재발송 쿨다운 → ② 이메일·IP 시간당 한도 → ③ 로그인 ID 중복 → ④ 발급·발송 → ⑤ 챌린지 저장.** ③이 ②보다 뒤인 것이 핵심이다: 앞에 두면 카운터를 하나도 올리지 않고 주소를 무한히 물어볼 수 있고, 그 판정은 **익명 호출자가 유발하는 MySQL 읽기**다. 한도는 그래도 **비용 상한이지 열거 방어가 아니다**(이메일 축은 한 주소당 한 번만 묻는 관찰에 무력하고 IP 축은 위조 가능).
- **③은 `local_accounts.email`만 본다.** 이미 가입된 주소면 **메일을 보내지 않고** `409 AUTH_EMAIL_ALREADY_REGISTERED`다 — 감추면 남의 메일함으로 인증번호가 실제 발송된다. 대신 **가입 여부 열거를 수용**하며, 그 결과 (A-7)의 발송이 `200`으로 감추려던 사실이 이 경로로 드러난다.
- **발송**: (A-2)와 같이 **동기 발송**하고 **발송에 성공한 뒤에만** `signup-email:code:{정규화이메일}`을 (재)설정한다(발송 실패 시 챌린지 미저장 + `502 UPSTREAM_ERROR`).
- **검증**: 입력 인증번호 해시가 `codeHash`와 일치하고 미만료·시도 미초과면 `signup-email:verified:{정규화이메일}`에 마커를 남기고 코드 키를 삭제한다. **챌린지 부재·불일치·만료·시도 상한 초과가 모두 `422 AUTH_EMAIL_VERIFICATION_FAILED`** 로, 시도 초과를 `429`로 구분하는 (A-2)와 다르다 — 비로그인 경로에서는 응답의 차이 자체가 챌린지 존재·시도 잔량을 알려 주는 신호가 된다.
- **소비**: 가입 제출이 마커를 확인하고 **커밋 이후에** (A-5)의 연락처 마커와 **함께** 지운다. 트랜잭션 안에서 지우면 커밋 시점 실패가 MySQL만 되돌리고 마커 둘은 사라져, 사용자가 인증을 처음부터 두 번 다시 해야 한다.
- **레이트리밋 버킷도 (A-5)·(A-6)·(A-7)과 나눠 쓴다**(`app.auth.signup-email.*` — 이메일 5회/1시간 · IP 20회/1시간): 공유하면 한쪽 화면의 남용이 다른 쪽 정상 흐름을 막는 결합이 생긴다.
- **민감정보**: 인증번호 원문은 보관·로그하지 않고(해시만), 이메일은 응답·로그에서 마스킹한다. 키·값·카운터 전부 TTL로 소멸해 영속하지 않으며 **이 기능에는 Flyway 마이그레이션이 없다**.

#### (B) MySQL — 소셜 연동(`social_accounts`)

소셜 제공자 자격을 회원에 묶는다(domain-model `SocialAccount`).

`social_accounts`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `user_id` | BIGINT | NOT NULL, INDEX · → user `users.id`(FK 아님, 값 참조) |
| `provider` | VARCHAR(20) (enum `Provider`) | NOT NULL |
| `provider_user_id` | VARCHAR(255) | NOT NULL · provider OIDC `sub` |
| `email` | VARCHAR(255) | NULL · 제공자 이메일(**provider 스냅샷**) — 로그인마다 최신값 upsert. 심사계정 매칭 등에 사용. 민감정보 |
| `name` | VARCHAR(200) | NULL · 제공자가 준 표시 이름(**provider 스냅샷**) — 로그인마다 요청 `name`으로 upsert(값 있을 때만, Apple 재로그인 `null`은 기존값 보존). `users.name`(사용자 값)과 별개 · 전진 마이그레이션 V20 · 민감정보 |
| `apple_refresh_token` | VARCHAR(512) | NULL · Apple 전용 — 코드 교환(`/auth/token`)으로 받은 refresh token. 탈퇴 시 `/auth/revoke` 폐기용([ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)). Google·교환 전 행은 NULL — **1급 민감정보(로그·응답 비노출)** |
| `linked_at` | DATETIME(6) | NOT NULL · 자격 연결 시각 |

**인덱스**: PK `id` / UNIQUE `(provider, provider_user_id)`(중복 연동 방지·로그인 분기 등치 조회) / INDEX `user_id`(회원 연동 목록·탈퇴 정리).

- **교차 모듈 no-FK**: `user_id`는 user 소유 → FK 미설정(값 참조). 회원 상태/프로필은 user 공개 쿼리/애플리케이션 조인.
- 연동 매핑의 **식별키**(`provider`·`provider_user_id`·`user_id`)는 불변(생성/삭제만) → `updated_at`/소프트삭제 미적용. 단 **provider 스냅샷 컬럼 `email`·`name`·`apple_refresh_token`은 로그인마다 최신 provider 값으로 upsert**된다(email=검증 토큰 값, name=요청 값·값 있을 때만, apple_refresh_token=응답에 있을 때만; 비어 오면 기존 값 보존 — [ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)). 이 스냅샷은 `users`(사용자 값)와 별개로, 재로그인 시 provider 변경은 여기 반영하되 `users`(사용자 편집분)는 덮지 않는다.
- **Apple refresh token**: 1급 민감정보 — 로그·응답·`toString` 비노출. MVP는 평문(RDS 저장소 암호화 의존, [ADR-0015](../adr/0015-sensitive-column-encryption.md))이며 컬럼 암호화 도입 시 후보. 전진 마이그레이션(Flyway)으로 추가하며, 탈퇴 시 매핑 삭제로 함께 제거된다([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)).
- **이름 스냅샷 컬럼 — 전진 마이그레이션 V20**: `social_accounts`에 `name VARCHAR(200) NULL` 추가(#192, provider 스냅샷). 기존 행은 NULL(과거 로그인의 provider name 미보유)이며 다음 로그인 시 upsert로 채워진다(소비처가 없어 NULL이어도 무방; 필요 시 `users.name`에서 1회 백필 가능). Apple 기존 계정은 재로그인 때 이름을 주지 않아 NULL로 남을 수 있다.

#### (C) MySQL — 웹 로컬 자격증명(`local_accounts`)

임대인 웹(앱과 별개 클라이언트)의 **이메일 + 비밀번호** 자격증명을 회원에 묶는다(domain-model `LocalAccount`, [ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md)). `social_accounts`(앱 소셜 로그인)와 **대칭**인 두 번째 자격증명 채널이며, 둘은 같은 `users` 행에 매달린다 — 한 사람은 `users` 한 행이고 로그인 수단만 둘이다. 자격증명은 `auth` 소관이라 `users`에 `password_hash`·`failed_login_attempts`·`locked_at`을 붙이지 않는다([ADR-0001](../adr/0001-bounded-context-module-decomposition.md) 모듈 경계 · 부수 효과로 세입자 다수의 `users` 행에 영원히 NULL인 컬럼 넷이 생기지 않는다).

`local_accounts`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `user_id` | BIGINT | NOT NULL · **UNIQUE**(`uq_local_accounts_user_id`) · → user `users.id`(FK 아님, 값 참조) |
| `email` | VARCHAR(255) | NOT NULL · **UNIQUE**(`uq_local_accounts_email`) · 웹 로그인 ID · 민감정보(PII) · `users.email`과 별개 값(아래 註) |
| `password_hash` | VARCHAR(100) | NOT NULL · **BCrypt 해시**(60자 + 여유) · 원문은 저장·로그 어디에도 남기지 않는다 |
| `name` | VARCHAR(200) | NULL · 가입 폼이 준 이름(**폼 스냅샷**) — `users.name`(정본)과 별개 · 민감정보(PII) |
| `birth_date` | DATE | NULL · 가입 폼이 준 생년월일(**폼 스냅샷**) · 민감정보(PII) |
| `failed_login_attempts` | INT | NOT NULL DEFAULT 0 · 연속 로그인 실패 횟수 · 로그인 성공·비밀번호 재설정 확정 시 0으로 초기화 |
| `locked_at` | DATETIME(6) | NULL · 잠금 시각 — 10회 연속 실패 시 기록. 채워져 있으면 **비밀번호가 맞아도** `423 AUTH_ACCOUNT_LOCKED`. 비밀번호 재설정 확정이 `NULL`로 되돌린다(US-1-17 — 이것이 유일한 애플리케이션 해제다) |
| `created_at` | DATETIME(6) | NOT NULL |
| `updated_at` | DATETIME(6) | NOT NULL |

**인덱스**: PK `id` / **UNIQUE `email`**(`uq_local_accounts_email` — 웹 로그인 ID 유일성 겸 로그인 등치 조회) / **UNIQUE `user_id`**(`uq_local_accounts_user_id` — "한 계정에 웹 자격증명 하나"를 DB 레벨로 강제. 이 유니크가 `user_id` 인덱스를 겸하므로 별도 보조 인덱스를 두지 않는다).

- **교차 모듈 no-FK**: `user_id`는 user 소유 → **FK 미설정**(값 참조). `social_accounts`가 [V1](../../src/main/resources/db/migration/V1__baseline_users_social_accounts.sql)에서 의도적으로 생략한 선례를 그대로 따른다([§2-4](#2-4-제약무결성-공통)). 회원 상태·프로필은 user 공개 쿼리/애플리케이션 조인으로 얻는다.
- **`uq_local_accounts_user_id`가 지키는 불변식**: 번호로 찾은 기존 계정에 자격증명을 덧붙이는 **연동 경로**(US-1-11)는 "조회 후 INSERT"라 동시 요청에 check-then-act가 깨지는데, 두 번째 INSERT가 이 제약에서 막힌다. 선검사에서 걸리는 경우가 `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS`다.
- **정본은 `users`, 이 테이블은 스냅샷**: `name`·`birth_date`는 가입 폼 값의 사본이라 NULL 허용이며, **연동 시 `users`의 프로필을 갱신하지 않으므로 두 값이 다를 수 있다**. 모든 응답의 `name`·`email`은 `users`에서 나가고 이 표의 사본은 어떤 응답에도 싣지 않는다([ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md) §6). `social_accounts.email`·`name`(provider 스냅샷)과 같은 취급이다.
- **이메일 중복 검사는 이 테이블에만**: 웹 가입의 이메일 중복(`409 AUTH_EMAIL_ALREADY_REGISTERED`)은 `local_accounts.email`만 본다. **`users.email`에는 UNIQUE를 걸지 않는다** — 걸면 "본인이 본인 소셜 이메일로 웹 가입"이라는 가장 흔한 정상 경로가 막힌다([§4-2](#4-2-user)). 신규 가입일 때만 폼 이메일을 `users.email`에도 기록하고, 연동일 때는 소셜 진본을 유지한다.
- **잠금은 컬럼이지 Redis TTL이 아니다**: `failed_login_attempts`·`locked_at`을 TTL 키로 두면 **만료와 함께 잠금이 저절로 풀린다** — 시간 경과 자동 해제는 지금도 없는 정책이므로 이 근거는 그대로 유효하다. 해제 경로가 생긴(US-1-17 비밀번호 재설정) 뒤로는 근거가 하나 더 정확해진다: **해제가 언제 일어났는지가 행에 남아야 한다.** 컬럼이면 `locked_at`이 `NULL`로 되돌아간 시각이 `updated_at`에 찍혀 "잠겼다가 본인 재설정으로 풀렸다"를 사후에 확인할 수 있지만, TTL 키는 **아무도 아무것도 하지 않아도 사라지므로** 해제와 방치를 구분할 수 없다. 잠금은 사고 조사 대상이라 그 차이가 곧 감사 가능성이다. 운영자가 `locked_at`을 직접 비우는 경로는 재설정 토글(`app.auth.web.password-reset.enabled`)이 꺼진 구간에서 여전히 유일한 해제로 남는다.
- **V22 주석은 옛 근거인 채로 남는다**: [`V22__create_local_accounts.sql`](../../src/main/resources/db/migration/V22__create_local_accounts.sql)의 주석은 잠금을 컬럼으로 둔 이유를 "해제 기능 없음 정책"으로 적고 있지만, **이미 적용된 마이그레이션은 체크섬이 고정이라 주석 한 줄도 고칠 수 없다**(고치면 Flyway `validate`가 기동을 막는다 — [migration-policy](./migration-policy.md)). 근거의 정본은 이 문서이고 그 주석은 **낡은 사본으로 남는다** — 파일을 읽고 정책을 되짚지 않도록 여기 적어 둔다.
- **가입용 이메일 인증도 스키마를 바꾸지 않는다**(US-1-18): 읽는 것은 이미 있는 `local_accounts.email`(중복 판정)뿐이고, 챌린지·마커·레이트리밋 카운터는 전부 **TTL이 있는 Redis 키**([§4-1](#4-1-auth) A-8)다. Flyway 마이그레이션이 없다.
- **계정 복구는 스키마를 바꾸지 않는다**: 이메일 찾기(US-1-16)·비밀번호 재설정(US-1-17)은 **새 테이블도 새 컬럼도 만들지 않는다** — 읽는 것은 이미 있는 `email`·`name`이고 쓰는 것은 `password_hash`·`failed_login_attempts`·`locked_at`이며, 새로 생기는 상태(인증 마커·재설정 토큰·레이트리밋 카운터)는 전부 **TTL이 있는 Redis 키**([§4-1](#4-1-auth) A-6·A-7)라 이 테이블에 남지 않는다. 그래서 이번 기능에는 Flyway 마이그레이션이 없다.
- **연동 매핑은 불변, 자격증명은 가변**: `user_id`·`email`은 생성 이후 바뀌지 않지만 `password_hash`·실패 카운터·`locked_at`은 갱신되므로 `social_accounts`와 달리 `updated_at`을 둔다([§2-2](#2-2-공통-컬럼-표준)).
- **탈퇴 시 이 행도 함께 지운다**: 탈퇴는 `users` PII 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)) + `social_accounts` 삭제 + **`local_accounts` 삭제** + refresh 무효화다(`UserWithdrawnEventListener` — 자격증명 두 채널을 같은 자리에서 지운다). 남기면 두 가지가 동시에 무너진다 — ① 탈퇴는 `users` 행을 지우지 않고 `user_type`도 `LANDLORD` 그대로라, 남은 웹 자격증명으로 **권한이 온전한 세션을 다시 받는다**(앱 소셜이 그렇지 않은 것은 `social_accounts`를 지우기 때문이다) ② `uq_local_accounts_email`이 살아남아 **본인이 같은 이메일로 재가입할 수 없다**(409 `AUTH_EMAIL_ALREADY_REGISTERED`). 웹 계정이 없는 세입자·앱 전용 임대인은 0행 삭제라 무해하다.
- **민감정보**: `email`·`name`·`birth_date`는 로그·응답 마스킹 대상이고(응답에는 애초에 `users` 값만 나간다), 비밀번호는 **해시만** 보관한다. 컬럼 암호화 도입 시 길이 재산정([§6](#6-결정-필요-open-questions)).
- **신설 — 전진 마이그레이션 V22**([`V22__create_local_accounts.sql`](../../src/main/resources/db/migration/V22__create_local_accounts.sql), [migration-policy](./migration-policy.md)): 신규 테이블이라 백필이 없는 순수 확장 변경이다.

    ```sql
    -- 웹 로컬 자격증명(local_accounts). 자격증명은 auth 소관이라 users에 붙이지 않는다(ADR-0001·ADR-0047).
    -- user_id에 FK는 걸지 않는다 — social_accounts가 V1에서 의도적으로 생략한 선례(값 참조).
    -- uq_local_accounts_user_id가 user_id 인덱스를 겸하므로 별도 보조 인덱스도 두지 않는다.
    CREATE TABLE local_accounts (
        id                    BIGINT       NOT NULL AUTO_INCREMENT,
        user_id               BIGINT       NOT NULL,
        email                 VARCHAR(255) NOT NULL,
        password_hash         VARCHAR(100) NOT NULL,
        name                  VARCHAR(200) NULL,
        birth_date            DATE         NULL,
        failed_login_attempts INT          NOT NULL DEFAULT 0,
        locked_at             DATETIME(6)  NULL,
        created_at            DATETIME(6)  NOT NULL,
        updated_at            DATETIME(6)  NOT NULL,
        PRIMARY KEY (id),
        CONSTRAINT uq_local_accounts_email   UNIQUE (email),
        CONSTRAINT uq_local_accounts_user_id UNIQUE (user_id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    ```

### 4-2. `user`

> 스토어: **MySQL** (회원 프로필·계정 lifecycle, [ADR-0005](../adr/0005-polyglot-persistence.md)). domain-model `User`(VO `FullName`(→단일 `name`)·`Consent`를 컬럼으로 평탄화, `name`·`nickname`·`country`·`occupation`·`email`은 단일 컬럼).

`users`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `user_type` | VARCHAR(16) (enum `UserType`) | NOT NULL DEFAULT `TENANT` · 온보딩 제출 엔드포인트로 확정·이후 불변(**`ADMIN` 수동 승격만 예외** — 아래 註) · INDEX(아래 註) |
| `name` | VARCHAR(200) | NULL · VO `FullName`→단일 컬럼 · **세입자·임대인 공통** 전체 이름(PII — 아래 註) · **소셜 로그인 시점에 요청 `name`으로 채움**(검증 대상 아님 — 아래 註) · 세입자 `first_name`+`last_name` 분리·임대인 `first_name` 재사용 편법은 #192에서 폐지 |
| `phone_number` | VARCHAR(20) | NULL · **UNIQUE**(`uq_users_phone_number` — `(user_type, phone_number)` 복합키, V28 · 아래 註) · **임대인**(PII — 로그·타 사용자 노출 시 마스킹, 예 `010-****-5678`). 온보딩 전 `auth` SMS 인증(§4-1 A-3 `VERIFIED`)을 거친 값이며 입력 경로에서 **숫자만 남겨 정규화**한다(기존 행 백필 없음 — 아래 註). 세입자는 NULL · 길이/형식 확인 필요 |
| `business_registration_number_hash` | VARCHAR(64) | NULL · **임대인** 사업자등록번호 SHA-256 해시(원문 비저장·로그 비저장·마스킹, 예 `****567890`) · 세입자는 NULL · **온보딩에서도 매물 등록에서도 채우지 않아 항상 NULL** — 임대인이 입력한 사업자등록번호는 매물 문서(`listings.businessRegistrationNumber`)가 원문으로 보유한다(아래 註) · 컬럼명·유니크 제약·저장 방식 확인 필요 |
| `nickname` | VARCHAR(50) | NULL · **UNIQUE** · 시스템 배정(`형용사 + 사물`) · 탈퇴 시 익명화 |
| `gender` | VARCHAR(16) (enum `Gender`) | NULL(PII) |
| `birth_date` | DATE | NULL · 과거만(앱 검증) · 세입자·임대인 온보딩에서 채움(PII) |
| `country` | CHAR(2) | NULL · 국적 ISO 3166-1 alpha-2 코드 · → `countries.code`(같은 모듈) · 표시명·국기는 `countries`에서 확보(PII) · **세입자**는 온보딩에서 사용자가 선택하고, **임대인**은 서버가 `KR` 고정으로 채운다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정 — 아래 註) |
| `lang` | VARCHAR(8) | NULL · 사용자가 선택한 표시 언어(ISO 639-1 **소문자**, 예 `ko`) · 지원 목록 **`en`·`ko`·`ja`** 로 서버 검증(위반 시 `400 INVALID_INPUT`) · **세입자만 변경 가능**하고 **임대인은 서버가 `ko` 고정**(아래 註) · NULL·공백이면 `en` 폴백 · 다국어 표시(진단 문항·퀴즈·생활 팁)의 **1순위 출처**([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) · 탈퇴 시 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)) |
| `occupation` | VARCHAR(32) (enum `Occupation`) | NULL · 세입자 온보딩 **선택**(#187 — 미전송이면 `ACTIVE` 후에도 NULL 유지, 임대인 미수집) · 확정 분류값 7종(#93, #138 개편): `UNDERGRADUATE_STUDENT`·`GRADUATE_STUDENT`·`EXCHANGE_STUDENT`·`LANGUAGE_TEACHING`·`MANUFACTURING_PRODUCTION`·`BUSINESS_TRADE`·`ETC`(PII) · V3부터 NULL 허용 컬럼이라 **필수→선택 전환(#187)에 DDL 변경·마이그레이션 없음** |
| `email` | VARCHAR(255) | NULL · 연락 이메일(세입자·임대인 공통) · 민감정보(PII) · **소셜 로그인 시 provider email(요청 `email`↔토큰 `email` 클레임 대조로 확정)로 세팅**(온보딩 입력·인증이 아님, #192) · **역할 무관 보유**(세입자·임대인 모두 소셜 로그인 provider email 보유 — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 '임대인 이메일 미수집'은 #192로 개정: 수집 폼이 아니라 소셜 로그인 provider 값 보유이며 email은 이제 미검증 연락처) · 계정 생성 시 소셜 제공자 이메일(`social_accounts.email`)과 같은 provider 값에서 확정 |
| `visa_type` | VARCHAR(80) (enum `VisaType`) | NULL · 민감정보(PII) · **저장값=표시용 라벨**(예: `Short Term Visit(C-1~4, B)`), API 노출은 상수명(`SHORT_TERM_VISIT`) · 라벨에 공백·괄호가 있어 `@Enumerated` 대신 `VisaTypeConverter`로 저장(#138) |
| `status` | VARCHAR(16) (enum `UserStatus`) | NOT NULL · 신규 `PENDING` |
| `terms_of_service_agreed` | BOOLEAN | NOT NULL · VO `Consent` |
| `privacy_policy_agreed` | BOOLEAN | NOT NULL · VO `Consent` |
| `marketing_agreed` | BOOLEAN | NOT NULL DEFAULT FALSE · VO `Consent` |
| `agreed_at` | DATETIME(6) | NULL · VO `Consent` 동의 시각 |
| `terms_version` | VARCHAR(20) | NULL · VO `Consent` 동의 약관 버전 |
| `withdrawn_at` | DATETIME(6) | NULL · 탈퇴 시각 — 탈퇴 시 PII 즉시 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)) |
| `created_at` | DATETIME(6) | NOT NULL |
| `updated_at` | DATETIME(6) | NOT NULL |

**인덱스**: PK `id`(`findById`) / **UNIQUE `nickname`**(닉네임 전역 유일·중복 배정 차단; NULL은 다중 허용이라 온보딩 전 `PENDING` 다건 무방) / **UNIQUE `(user_type, phone_number)`**(`uq_users_phone_number` — 「같은 번호면 **유형별로** 같은 계정」을 DB가 지킨다; NULL 다중 허용이라 세입자·탈퇴자는 무영향. 번호로 기존 임대인 계정을 찾는 연동·병합 조회의 인덱스도 겸한다 — 아래 註) / INDEX `user_type`(역할별 조회·집계) / (선택) INDEX `status`(상태별 배치 — MVP 조회는 PK 단건뿐이라 보류 가능).

- **이메일 두 종류**: 소셜 제공자 이메일은 **auth `social_accounts.email`** 소관(역할 무관·소셜 연동 메타데이터)이고, `users.email`은 **소셜 로그인 시 provider email(요청 `email`↔토큰 `email` 클레임 대조로 확정)로 세팅**되는 연락 이메일이다(#192 — 온보딩 입력·인증이 아니며, 계정 생성 시 둘은 같은 provider 값에서 나온다). **역할과 무관하게** 세입자·임대인 모두 소셜 로그인에서 캡처된 provider email을 `users.email`에 보유한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 '임대인 이메일 미수집' 결정은 #192로 개정 — 수집 폼이 아니라 소셜 로그인 provider 값 보유이고, 이메일 인증(신원 확인)이 없어진 지금 email은 '미검증 연락처'일 뿐이다). 이메일 인증 API(§4-1 A-2)는 **온보딩 완료(`ACTIVE`) 이후 접근 전용**이고, 그 *인증 흔적*은 Redis에만 단명 보관하며 **실제 `users.email` 변경 반영은 후속 이슈**(#192 범위 밖 — 이번엔 접근 제한만). **이름도 같은 이중 관리**다 — `social_accounts.name`(provider 스냅샷)과 `users.name`(사용자 값). `social_accounts`의 `email`·`name`은 로그인마다 provider 값으로 upsert되지만 `users`는 최초 로그인에만 세팅되고 이후 사용자 편집(name=`PATCH /users/me`)만 반영한다 — 재로그인 시 provider 변경은 `social_accounts`에만 반영하고 `users`는 덮지 않는다.
- **닉네임**: 시스템이 `형용사 + 사물`로 무작위 배정하며 `UNIQUE`로 중복을 막는다(충돌 시 재시도). 사용자 입력·수정 대상이 아니다.
- **상태 흐름·컬럼 채움 시점**: `status`는 `PENDING`(소셜 검증) → `TERMS_AGREED`(약관 동의) → `ACTIVE`(온보딩 완료) → `WITHDRAWN`. **`name`과 `email`은 소셜 로그인 시점**(User 생성, `PENDING`)에 요청 `name`·provider `email`로 채워지고(#192 — 온보딩이 아니며 세입자·임대인 역할 무관), **동의 컬럼**(`terms_of_service_agreed`·`privacy_policy_agreed`·`marketing_agreed`·`agreed_at`·`terms_version`)은 **약관 동의 단계**(`PENDING`→`TERMS_AGREED`)에 채워지며, **프로필 컬럼**(세입자: `nickname`·성별·생년월일·`country`·`lang`(선택 — 미전송이면 저장하지 않고 NULL, 표시 시 `en` 폴백)·`occupation`(선택 — 미전송이면 저장하지 않고 NULL, #187)·`visa_type` / 임대인: `nickname`·`phone_number`·`birth_date`·`country`=`KR`·`lang`=`ko`(둘 다 서버 고정, 사용자 미입력))은 **온보딩 단계**(`TERMS_AGREED`→`ACTIVE`)에 채워진다(임대인 `business_registration_number_hash`는 온보딩에서 수집하지 않아 이 단계엔 채우지 않고, **매물 등록 시점에도 채우지 않는다** — 사업자등록번호 원문은 매물 문서가 보유한다; enum 값 정본은 [domain-model](../architecture/domain-model.md)).
- **관리자(`ADMIN`) 승격**: 관리자는 **가입 경로가 없다.** 운영자가 관리자 전용 계정을 임대인 웹 가입 흐름으로 만든 뒤 아래로 승격한다. 로그인·계정 연동 방식은 임대인과 완전히 동일하며 관리자 전용 인증 경로를 두지 않는다.

  ```sql
  -- ACTIVE 계정에만 적용한다. 온보딩 미완료 계정을 승격하면 ROLE_ONBOARDING 토큰만 받아
  -- 보안 매처를 통과하지 못한 채 권한만 갖는 계정이 된다. 코드 경로가 없으므로 이 조건이 유일한 방어선이다.
  UPDATE users SET user_type = 'ADMIN' WHERE id = ? AND status = 'ACTIVE';
  ```

  **활동 중인 임대인·세입자 계정을 승격하지 않는다** — `ADMIN`은 병존하지 않는 제3의 유형이라 승격하면 이전 역할을 잃고(매물 등록 불가·임대인 연동 조회에서 제외), 매물을 보유한 임대인을 승격하면 자기 매물을 자기가 심사할 수 있게 된다.
- **번호 UNIQUE가 유형별인 이유**: `V23`은 `phone_number` 단독 UNIQUE였다. 관리자 승격이 생기면서 **승격된 계정이 번호를 계속 점유해** 같은 사람이 그 번호로 임대인 계정을 따로 만들 수 없게 되므로, `V28`에서 `(user_type, phone_number)` 복합키로 **완화**했다. **제약 이름은 `uq_users_phone_number` 그대로 둔다** — `GlobalExceptionHandler`가 제약 이름 화이트리스트로 이 위반을 `409 RESOURCE_CONFLICT`로 번역하므로, 이름을 바꾸면 그 경합이 `500 INTERNAL_ERROR`로 떨어진다. `V23`이 막으려던 경쟁 — 웹 임대인 가입과 앱 임대인 온보딩이 동시에 들어와 각자 `ACTIVE`·`LANDLORD` 행을 만드는 것 — 은 **두 INSERT가 모두 `LANDLORD`** 라 복합키로도 그대로 막힌다. 애플리케이션 조회는 이미 `userType`으로 필터하므로 코드 변경이 없다([ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md) Amended).
- **역할(`user_type`) 분기**: `user_type`은 **온보딩 제출 엔드포인트**(세입자 `POST /api/v1/auth/onboarding` / 임대인 `POST /api/v1/auth/landlord/onboarding`)로 확정되며 **이후 불변**이다. 소셜 로그인·약관 동의까지 두 역할 공통이고 이후 온보딩에서 분기한다(임대인 온보딩엔 연락처 SMS 인증이 있고, 세입자 이메일 인증은 온보딩 단계가 아니라 `ACTIVE` 이후 전용 접근이다 — §4-1 A-2). 임대인은 user 별도 모듈이 아니라 **같은 `users` 애그리거트**다 — 본인 프로필 조회·수정(`GET`/`PATCH /users/me`)은 세입자와 동일 경로로 제공하되 `user_type`에 따라 응답·수정 가능 컬럼이 갈린다(임대인 응답은 `name`·`nickname`·`birth_date`·`phone_number`·`email`·`country`(=`KR`, 표시명·국기 포함)·`status`·동의 컬럼·`created_at`만 — 세입자 전용 컬럼 `gender`·`occupation`·`visa_type`는 제외(`birth_date`·`country`는 임대인도 보유·반환 — `country`는 서버 고정값이라 수집하지 않지만 응답엔 나오고, `email`은 소셜 로그인 provider 값이라 임대인도 보유·반환한다); 수정은 `name`·`phone_number`·`marketing_agreed`만(임대인 `birth_date`는 온보딩 확정·조회 전용이고 `country`·`lang`은 서버 고정이라 변경 불가 — `lang` 변경은 세입자 전용), `business_registration_number_hash`·`user_type`·`nickname`은 불변).
- **이름 저장(세입자·임대인 통일)**: 세입자·임대인 모두 단일 `name` 컬럼에 전체 이름을 저장한다 — 과거 세입자 `first_name`(이름)+`last_name`(성) 분리·임대인 `first_name` 재사용(단일 name을 `first_name`에, `last_name`은 NULL) 편법은 **#192에서 폐지**했다(API는 예전부터 단일 `name` 필드였고 이제 DB 컬럼도 `name` 하나라 `name`↔`first_name` 매핑이 사라진다). `name`은 온보딩이 아니라 **소셜 로그인 시점에 요청 `name`으로 채운다**(네이티브 SDK가 준 값 신뢰 — 토큰 검증 대상 아님, Apple은 이름을 최초 1회만 주므로 백엔드가 토큰에서 못 얻는다). 임대인은 추가로 `phone_number`·`birth_date`를 온보딩에서 채우고, 세입자는 `gender`·`country`·`lang`(선택)·`occupation`(선택, #187)·`visa_type`·`birth_date`를 온보딩에서 채운다(임대인은 `gender`·`occupation`·`visa_type`는 미수집·NULL, `birth_date`는 세입자·임대인 공통 수집, `country`·`lang`은 사용자 입력이 아니라 **서버가 `KR`·`ko`로 심는다**). NOT NULL 제약이 아니라 역할별 채움은 **상태·역할 불변식**(앱·서버 검증)이다.
- **표시 언어(`lang`)**: 사용자가 고른 표시 언어를 영속하는 컬럼이며 다국어 표시의 **1순위 출처**다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)). 값은 ISO 639-1 **소문자 코드**이고(DB 컬럼은 이 코드를 저장), 서버는 닫힌 집합 `Language` enum으로 모델링·검증해 **지원 목록 `en`·`ko`·`ja`** 밖이면 `400 INVALID_INPUT`이다(세 언어 밖은 어느 카탈로그에도 콘텐츠가 시드되지 않아 빈 선택지를 노출하지 않는다). 채움·변경 규칙은 역할별로 갈린다 — **세입자**만 온보딩·`PATCH /users/me`로 보낼 수 있고(둘 다 **선택 필드**라 미전송이면 저장하지 않고 NULL로 두고 표시 시 `en`으로 폴백한다), **임대인**은 온보딩 시 서버가 `ko`를 심고 이후 변경 경로가 없다(임대인 프로필 수정은 `lang`을 읽지 않는다). `PATCH /users/me`에 `country`만 오고 `lang`이 없으면 `lang`은 그대로 두고(국적을 바꿔도 표시 언어는 바뀌지 않는다), `lang`을 명시 전송하면 그 값을 저장한다. `lang`만 보내면 `country`는 그대로다.
- **`lang`의 NULL은 "미선택"**: NOT NULL·DEFAULT `en`을 두지 않는다 — NULL(과 공백)은 "언어를 고른 적 없음"(미선택)을 뜻하며 런타임에서 `en`으로 매핑한다.
- **사업자번호 해시(어느 경로에서도 미채움)**: `business_registration_number_hash`는 임대인 **온보딩에서 수집·저장하지 않는다** — 온보딩(약관 동의 + 연락처 SMS 인증)은 사업자번호 게이트가 없어 온보딩 완료(`ACTIVE`) 후에도 이 컬럼은 **NULL로 남는다**. 사업자번호 검증(`POST /api/v1/auth/business/verify`, §4-1 A-4)은 무상태라 결과를 이 컬럼에 쓰지 않는다. **매물 등록 시점에도 채우지 않는다** — 임대인이 등록 폼에 입력한 사업자등록번호는 **원문 그대로 `listings.businessRegistrationNumber`에 저장**되므로(§4-3) `users`에 해시를 따로 둘 이유가 없다. 즉 현재 이 컬럼을 채우는 코드 경로는 없다. [ADR-0033](../adr/0033-business-registry-verification.md)의 "원문 비저장·해시로만 영속"은 **매물 문서 한정으로 개정**됐고([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)) `users`에는 여전히 미채택이다. 유니크 제약은 앱 레벨(컬럼 유니크 미적용 — 확인 필요).
- **연락처 인증값 영속**: `phone_number`(임대인)는 온보딩 제출 시 Redis 연락처 인증 마커(§4-1 A-3 `phone-verify:verified:{userId}`)의 번호와 대조해 일치할 때만 확정·영속한다(미인증/불일치 `AUTH_PHONE_NOT_VERIFIED`). 인증 흔적은 TTL로 소멸하고 확정 번호만 `users.phone_number`로 남는다.
- **교차 모듈 no-FK**: auth(소셜연동·웹 로컬 자격증명·refresh·이메일인증·연락처인증·사업자번호 검증)와 `userId` 값만 공유.
- **소프트삭제 대신 상태**: 탈퇴=`status=WITHDRAWN`+`withdrawn_at` 기록, PII 즉시 익명화([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md))(+토큰 일괄 무효화). 탈퇴 시 `nickname`도 익명화(NULL)해 유니크 슬롯을 회수하며, 임대인 PII(`name`·`phone_number`·`birth_date`·`business_registration_number_hash`)도 함께 익명화(NULL)한다. `lang`은 `country`와 함께 익명화(NULL) 대상이다 — 사용자가 고른 표시 언어도 PII라 탈퇴 시 남길 이유가 없다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)). WITHDRAWN/없음 조회는 `USER_NOT_FOUND`(404).
- **PII 컬럼은 NULL 허용**(`name`·`phone_number`·`business_registration_number_hash`·`nickname`·`gender`·`birth_date`·`country`·`lang`·`occupation`·`email`·`visa_type`): 회원은 소셜 로그인 시 `name`·`email`(둘 다 역할 무관)만 채운 채 `PENDING`으로 생성되고 나머지 프로필은 온보딩에서 채우며, 역할별로 컬럼 채움이 갈리고(임대인만 `phone_number`+`business_registration_number_hash`), 탈퇴 시 즉시 **익명화(NULL)**되기 때문이다([ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)). "온보딩 완료(`ACTIVE`) 시 (역할에 맞게) 채워져야 한다"는 **상태·역할 불변식**(앱·서버 검증)이지 컬럼 NOT NULL 제약이 아니다. 단 `lang`·`occupation`은 이 불변식에서도 **제외**다 — 세입자 온보딩 **선택** 필드라 `ACTIVE`여도 미전송이면 NULL로 남는다(`lang` #141 · `occupation` #187 — `occupation`은 V3부터 NULL 허용이라 필수→선택 전환에 마이그레이션이 없다).
- **민감정보**: `email`·`visa_type`·`phone_number`는 로그·타 사용자 노출 시 마스킹(본인 `GET /users/me`는 평문). `business_registration_number_hash`는 해시 컬럼이며 응답엔 마스킹값(예 `****567890`)만 노출한다. 이 테이블에는 원문을 저장하지 않고 로그에도 남기지 않는다(사업자등록번호 원문을 보유하는 곳은 매물 문서뿐이다 — [§4-3](#4-3-listing)). 컬럼 암호화 도입 시 길이 재산정([§6](#6-결정-필요-open-questions)).
- **마이그레이션 후속**: 이 스키마(닉네임·국적·직업·이메일 추가, 전화번호 컬럼 제거)는 baseline([V1](../../src/main/resources/db/migration/V1__baseline_users_social_accounts.sql)) 이후 변경이므로 **전진 마이그레이션(V2 등)** 으로 반영해야 한다([migration-policy](./migration-policy.md), 확인 필요). 임대인 역할 컬럼(`user_type`·`phone_number`·`business_registration_number_hash` 추가, `user_type` 인덱스)은 **전진 마이그레이션 V8 예정**이다(실제 DDL은 후속 구현 PR — 컬럼명·phone 길이/형식·저장 방식 확인 필요. `phone_number` 유니크 제약은 V8에서 미결로 남겼다가 **V23에서 확정**했다 — 아래 註). 이름은 세입자/임대인 통일로 `first_name`+`last_name`을 단일 `name`으로 병합하며 별도 **전진 마이그레이션 V19**로 반영한다(#192 — 아래 註).
- **`lang` 추가 — 전진 마이그레이션 V13 예정**(최신이 V12이므로 다음 번호는 `V13__users_lang.sql`, [migration-policy](./migration-policy.md)): 컬럼 추가 + 백필로 **배포 t=0 항등**(기존 사용자가 받던 언어가 그대로 유지)을 맞춘다. 인덱스는 추가하지 않는다 — `lang`은 PK 단건 조회(`findById`)로만 읽고 언어별 검색이 없다.

    ```sql
    ALTER TABLE users ADD COLUMN lang VARCHAR(8) NULL;
    -- 세입자: 미선택은 그대로 NULL(표시 시 런타임 en 폴백) — 백필 없음
    -- 임대인: 한국어·한국 고정
    UPDATE users SET lang = 'ko', country = 'KR' WHERE user_type = 'LANDLORD';
    -- 국가 폴백 제거 — V6에서 추가한 countries.lang 컬럼 제거
    ALTER TABLE countries DROP COLUMN lang;
    ```

    **`NOT NULL DEFAULT 'en'`로 채우지 않는 이유**: 전 행을 `en`으로 메우면 "언어를 고른 적 없음"(미선택)과 "`en`을 골랐음"이 구분 불가해진다. NULL은 "미선택"이라는 의미를 갖는 값이므로 유지하고 표시 시 런타임에서 `en`으로 매핑한다. 임대인 `UPDATE`는 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 country 미수집" 결정을 개정한 결과로 기존 임대인 행의 `country`도 함께 `KR`로 백필한다(#141). `ALTER TABLE countries DROP COLUMN lang`은 국가 경유 폴백을 없애며 V6에서 추가했던 컬럼을 제거한다(#141).

- **이름 통합 `first_name`+`last_name`→단일 `name` — 전진 마이그레이션 V19 예정**(최신이 `V18`이므로 다음 번호는 `V19__users_merge_name.sql`, [migration-policy](./migration-policy.md)): `name`(`VARCHAR(200)`) 추가 → 두 컬럼을 공백으로 합쳐 백필(트림 후 빈 문자열은 NULL) → 기존 `first_name`·`last_name` DROP. 세입자 `first_name`(이름)+`last_name`(성)과 임대인 `first_name`(단일 name)이 하나의 `name`으로 통일된다(#192).

    ```sql
    ALTER TABLE users ADD COLUMN name VARCHAR(200) NULL;
    -- 세입자 first_name+last_name / 임대인 first_name(단일 name)을 공백으로 병합. 트림 후 빈 문자열이면 NULL.
    UPDATE users SET name = NULLIF(TRIM(CONCAT_WS(' ', first_name, last_name)), '');
    ALTER TABLE users DROP COLUMN first_name;
    ALTER TABLE users DROP COLUMN last_name;
    ```

- **배포 클린업(온보딩 미완료 계정 삭제) — #192**: `name`·`email` 캡처 지점이 온보딩에서 **소셜 로그인**으로 옮겨져, 배포 전 온보딩 미완료 계정(`status` `PENDING`·`TERMS_AGREED`)은 `name`/`email`이 비어 있다. 이들 계정과 해당 `social_accounts` 행을 삭제한다(재로그인 시 새 플로우로 재가입해 `name`/`email`을 다시 캡처).

    ```sql
    -- 온보딩 미완료(PENDING·TERMS_AGREED) 계정의 소셜 연동 먼저 삭제(값 참조라 FK 없음) 후 계정 삭제.
    DELETE sa FROM social_accounts sa
      JOIN users u ON u.id = sa.user_id
      WHERE u.status IN ('PENDING', 'TERMS_AGREED');
    DELETE FROM users WHERE status IN ('PENDING', 'TERMS_AGREED');
    ```

- **번호 유일성 `uq_users_phone_number` — 전진 마이그레이션 V23**([`V23__users_phone_number_unique.sql`](../../src/main/resources/db/migration/V23__users_phone_number_unique.sql), [migration-policy §3](./migration-policy.md#3-되돌릴-수-있는-변경-호환성-분류)): 임대인 웹 가입(US-1-11)과 앱 임대인 온보딩(US-1-15)은 「같은 번호면 같은 계정」으로 이어붙는데, 두 흐름이 거의 동시에 도착하면 **양쪽 다 「기존 계정 없음」을 읽고** 각자 `ACTIVE`·`LANDLORD` 행을 만들어 한 사람의 계정이 갈라진다. 애플리케이션 조회(`SELECT … FOR UPDATE`)는 **아직 없는 행을 잠글 수 없으므로** 그 경쟁을 실제로 막는 것은 이 UNIQUE뿐이다 — 두 번째 INSERT가 제약 위반으로 실패하고 그 트랜잭션이 통째로 롤백되며, 실패한 쪽은 재시도하면 상대가 만든 계정을 발견해 정상 연동·병합된다([ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md) §5). MySQL UNIQUE는 NULL 중복을 허용하므로 세입자(정의상 NULL)와 탈퇴자(익명화로 NULL — [ADR-0014](../adr/0014-withdrawal-pii-anonymization.md))는 영향이 없다(`nickname` UNIQUE와 같은 성질이라 온보딩 전 다건도 무방). 이 제약이 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)가 미결로 남긴 "연락처 유니크 제약(동일 번호 다계정 허용 여부)"을 닫는다.

    ```sql
    ALTER TABLE users ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);
    ```

    **정규화는 입력 경로만 · 백필 없음(수용된 제약)**: 번호 정규화(숫자만 남김)는 새 입력 경로에만 넣고 **기존 행은 `UPDATE`하지 않는다** — 이 마이그레이션에 백필 SQL이 없는 이유다. 따라서 하이픈으로 저장돼 있던 기존 임대인 번호는 정규화된 값과 등치 매칭되지 않아 **연동·병합에서 누락될 수 있고**, 그 임대인은 웹 가입 시 별개 계정을 갖게 된다(재인증으로 번호를 다시 확정하면 그 시점에 표준형으로 접힌다). 알고 수용한 한계이며 [user-stories](../requirements/user-stories.md)·[ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md) Consequences에도 같은 제약으로 기록돼 있다. [migration-policy §3](./migration-policy.md#3-되돌릴-수-있는-변경-호환성-분류)상 **제약 강화는 비호환**이라 원래는 중복 행 정리가 선행돼야 하지만, 임대인 계정에 중복 번호를 만들 경로가 아직 없어 대상이 0건이다(적용 전 중복 점검 절차는 [migration-policy §3](./migration-policy.md#3-되돌릴-수-있는-변경-호환성-분류)).

#### 사용자 차단 — `user_blocks`

한 사용자가 다른 사용자를 차단한 사실을 담는다(#169 · US-4-8). **행의 존재 = 차단**이고 **해제 = 행 삭제**다. domain-model `UserBlock`.

**왜 예약 단위가 아니라 사용자 단위인가**: 차단 대상을 `bookings` 행에 매다는 방식(예약 단위)은 **매물 구조상 반드시 우회된다**. 한 임대인은 **매물을 여러 개** 소유하고(`Listing.landlordId` — 매물이 임대인을 가리키는 N:1), 한 매물은 **방 상품을 여러 개** 갖는다(`Listing.roomOffers`가 `List<RoomOffer>`). 그래서 임대인 A를 예약 #1에서 차단해도 A의 **다른 방 상품**(같은 매물의 다른 방이든, A의 다른 매물이든)에 신청이 들어오면 새 예약 행 = 새 대화가 생기고, 차단은 그 예약에만 걸려 있어 아무것도 막지 못한다. 차단은 본질적으로 **사람**에 대한 것이라 대상이 예약이면 상대가 방을 하나 더 가진 순간 무력해진다 — 그래서 `user` 모듈이 `(blocker_id, blocked_user_id)` **쌍**으로 소유한다.

- **보조 근거(같은 방 재신청은 이제 막히지만 우회는 남는다)**: `bookings`에는 **중복 방지 유니크 제약**(`uq_bookings_tenant_room_offer (tenant_id, room_offer_id)` — 동일 세입자–동일 방 상품 활성 1건)이 있어 **같은 방 상품 재신청은 이제 `409 BOOKING_ALREADY_EXISTS`로 막힌다**(§4-5). 그래서 "같은 방 재신청"이라는 손쉬운 우회로는 닫혔지만, 임대인은 **방 상품·매물을 여러 개** 가지므로 **다른 방·다른 매물로는 여전히 우회된다** — 예약 단위 차단은 상대가 방을 하나 더 가진 순간 그 새 예약엔 걸리지 않기 때문이다. 즉 중복 방지가 손쉬운 우회 하나를 없앴어도 **구조적 우회는 그대로 남아 결론(사용자 단위)은 바뀌지 않으며**, 위 구조적 근거가 유일하게 살아남는 근거가 된다.

**소유는 `user`, 생성 트리거는 `booking`**: 차단 생성 경로는 `POST /api/v1/bookings/{bookingId}/block`이라 예약에서 상대를 도출해야 하지만, `user`가 생성을 소유하면 상대를 도출하려 `user → booking` 의존이 생기고 `booking → user :: api`가 이미 있어 **모듈 순환이 생겨 `ApplicationModules.verify()`가 깨진다**. 그래서 컨트롤러는 `booking`에 두되 저장은 `user :: api` 공개 명령으로 이 테이블에 쓰고, 차단 목록·해제(`GET`/`DELETE /api/v1/users/me/blocks`)는 `user`가 직접 제공한다 — 차단하면 그 예약이 목록에서 사라져 `bookingId`를 다시 얻을 수 없으므로 **예약과 무관한 해제 경로가 반드시 있어야 한다**.

`user_blocks`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `blocker_id` | BIGINT | NOT NULL · UNIQUE(복합) · 차단한 사용자 → `users.id`(같은 모듈이지만 값 참조 — 아래 註) |
| `blocked_user_id` | BIGINT | NOT NULL · UNIQUE(복합) · 차단당한 사용자 → `users.id`(값 참조) |
| `created_at` | DATETIME(6) | NOT NULL · 차단 시각(UTC) · API `blockedAt` |

**인덱스**: PK `id` / **UNIQUE `(blocker_id, blocked_user_id)`**(같은 상대 중복 차단 방지 — 차단은 멱등(`204`)이라 재요청이 예외가 아니라 no-op이 된다. 차단자 기준 목록·`blockedIds` 조회의 선행 컬럼 인덱스도 겸한다) / INDEX `(blocked_user_id)`(**역방향** 조회 — 신규 예약 신청 가드가 "상대가 나를 차단했는가"를 양방향으로 확인해야 하는데 유니크 인덱스의 선행 컬럼이 `blocker_id`라 이 방향을 태우지 못한다).

- **`is_active` 컬럼 없음**: 차단 상태를 불리언 컬럼으로 들지 않는다 — **행 존재 자체가 차단**이고 해제는 행 삭제다. 해제 이력을 남길 요구가 없고(`docs/`에 `is_active` 전례 0건), 상태 컬럼을 두면 UNIQUE `(blocker_id, blocked_user_id)`가 "해제 후 재차단"을 막아버려 오히려 부활 로직이 필요해진다.
- **소프트삭제·`updated_at` 없음**: 생성·삭제만 있는 매핑이라 `social_accounts`(§4-1 B)와 같은 취급이다.
- **no-FK**: `blocker_id`·`blocked_user_id`는 같은 `user` 모듈의 `users.id`를 가리켜 §2-4상 FK가 허용되지만, 레포의 마이그레이션 13개 전체에 `FOREIGN KEY`/`REFERENCES`가 **0건**이라 전례를 따라 걸지 않는다(값 참조). 대상 사용자 존재·탈퇴 여부는 `user` 모듈 내부 조회로 판정한다.
- **단방향 저장·양방향 판정**: 행은 `blocker_id → blocked_user_id` **단방향**으로만 저장한다(A가 B를 차단해도 B의 차단 행은 생기지 않는다). 목록 숨김은 차단자 기준 단방향이고(내 목록에서만 사라진다), 신규 예약 신청 가드만 **양쪽 방향의 행을 모두** 조회해 어느 한쪽이라도 있으면 거절한다(§4-5).
- **공개 쿼리**: `booking` 등 타 모듈은 이 테이블을 직접 조인하지 않고 `user :: api`의 공개 쿼리(`findBlockedUserIds(blockerId)`·`isBlockedBetween(a, b)`)로 식별자만 받아 **애플리케이션 레벨 조인**한다([ADR-0002](../adr/0002-inter-module-communication-via-events.md)).
- **탈퇴**: 차단 행은 PII를 담지 않는 식별자 쌍이라 [ADR-0014](../adr/0014-withdrawal-pii-anonymization.md) 익명화 대상 컬럼이 없다(탈퇴 시 정리 여부는 확인 필요).
- **신설 — 전진 마이그레이션 V15 예정**(최신이 `V13__users_lang.sql`이고 `bookings` 컬럼 추가가 V14라 다음 번호는 `V15__create_user_blocks.sql`, [migration-policy](./migration-policy.md)): 신규 테이블이라 백필이 없는 순수 확장 변경이다.

    ```sql
    -- 사용자 차단(user_blocks) 테이블. 행 존재 = 차단, 해제 = 행 삭제(is_active 컬럼 없음).
    -- 예약 단위가 아니라 사용자 단위다 — 임대인은 매물을 여러 개, 매물은 방 상품을 여러 개 가져(Listing.landlordId · Listing.roomOffers)
    -- 예약 단위 차단은 상대의 다른 방 상품으로 새 예약을 만들면 그대로 우회된다. 차단은 사람에 건다(근거 상세는 §4-2 user_blocks).
    -- blocker_id·blocked_user_id는 users.id 값 참조(FK 없음 · 레포 전체 FK 0건 전례).
    -- #169 · US-4-8 · docs/database/database-design.md §4-2 · docs/api/specs/01-auth-onboarding.md.
    CREATE TABLE user_blocks (
        id              BIGINT      NOT NULL AUTO_INCREMENT,
        blocker_id      BIGINT      NOT NULL,
        blocked_user_id BIGINT      NOT NULL,
        created_at      DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        CONSTRAINT uq_user_blocks_blocker_blocked UNIQUE (blocker_id, blocked_user_id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

    -- 역방향(누가 나를 차단했나) 조회 — 신규 예약 신청 가드가 양방향으로 확인한다. 유니크 인덱스는 blocker_id 선행이라 이 방향을 못 태운다.
    CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_user_id);
    ```

#### 국가 참조 — `countries`

국적은 **국가 코드(ISO 3166-1 alpha-2)** 로 식별하고, 표시명·국기는 이 reference 테이블에서 확보한다. 클라이언트는 온보딩 시 **국가(코드)만 전송**하고 국기는 서버가 여기서 채운다(수집). 시드/마이그레이션으로 적재.

`countries`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `code` | CHAR(2) | PK · ISO 3166-1 alpha-2(예: `VN`) |
| `name` | VARCHAR(64) | NOT NULL · 표시명(다국어 단일 vs `name_en`·`name_ko`는 확인 필요) |
| `flag` | VARCHAR(512) | NOT NULL · 국기 이미지 URL(flagcdn.com SVG, 코드 소문자 기반 · 예 `https://flagcdn.com/vn.svg`) |

**인덱스**: PK `code`.

- **국기 확보**: `users.country`(코드)로 `countries`를 조회해 `name`·`flag`를 얻는다(API 응답의 `countryName`·`countryFlag`). `flag`는 **국기 이미지 URL**(flagcdn.com SVG, 코드 소문자 기반 — 예 `VN`→`https://flagcdn.com/vn.svg`)이며, 표기 일관성·교체 용이를 위해 이 테이블을 단일 출처로 둔다.
- **표시 언어 도출**: `user` 공개 query `getLanguage(userId)`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 정한다. `diagnosis` 등 다국어 모듈이 이 query로 동기 취득한다(시그니처 `getLanguage(long)`은 그대로라 소비자 계약은 바뀌지 않는다). `users.lang` 우선은 [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)이며, 이때 국가 경유 폴백(`countries.lang`)을 제거했다.
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

> 스토어: **MongoDB** (지오·대량 읽기, `2dsphere`. [ADR-0005](../adr/0005-polyglot-persistence.md)). domain-model `Listing`·`RoomOffer`·`Favorite`·`RecentListing`.
>
> 매물 문서는 **건물/주소 단위 Listing 1건**이고, 동일한 가격·검색 태그를 가진 실제 방 묶음은 `roomOffers[]`의 **방 상품**으로 임베드한다. 계약기간(`contract`)은 방 상품마다 다르므로 `roomOffers[]`가 소유한다. 임대 방식·환불 정책·성별 정책·ARC 요구(`arcRequired`)처럼 매물 전체에 공통인 값은 Listing 루트에 둔다. API의 `listingId`는 MongoDB `_id ObjectId`의 24자리 hex 문자열이다. 이 절을 `listings` 컬렉션의 정본 스키마로 둔다. 현재 구조는 임대인 등록 폼 기준으로 재정의한 **v4**(`schemaVersion=4`, 루트 34필드)다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)).

`listings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `schemaVersion` | int | NOT NULL · 문서 구조 버전(현재 `4`) |
| `landlordId` | long | NOT NULL · → user `users.id` 값 참조(FK 없음) |
| `contact` | object | NOT NULL · 매물별 담당 연락처(`managerName`·`phone`) · `phone`은 지점 대표 전화이며 임대인 개인 연락처와 별개 값(아래 註) |
| `businessRegistrationNumber` | string | 사업자등록번호 **원문** · 응답 비노출(아래 註) |
| `blogUrl` | string | nullable · 임대인 소개 페이지 URL |
| `ageMin` | int | NOT NULL · 이용 가능 연령 하한 · 등록 폼의 `ageRange`(`min~max`)를 서버가 나눈 값 |
| `ageMax` | int | NOT NULL · 이용 가능 연령 상한 |
| `title` | object | NOT NULL · 매물 고유 표시명 `{ko,en}` |
| `type` | string (enum `ListingType`) | NOT NULL · `GOSHIWON`/`CO_LIVING`/`SHARE_HOUSE` |
| `rentalType` | string (enum `RentalType`) | 매물 공통 임대 방식 · `MONTHLY_RENT` 단일값 |
| `status` | string (enum `ListingStatus`) | NOT NULL · `PENDING`/`PUBLISHED`/`REJECTED`/`UPDATE_PENDING` · 값 집합의 정본은 validator다(changeUnit `0121`이 3종으로 조이고 `0122`가 `UPDATE_PENDING`을 더한다 — [migration-policy §8-2](./migration-policy.md#8-2-listing-마이그레이션-체인)). `UPDATE_PENDING`은 공개 중이던 매물을 임대인이 수정해 재심사를 기다리는 상태이며, 세입자 조회는 전부 `PUBLISHED` equality라 이 값이면 자동으로 빠진다 |
| `rejectionReason` | string | nullable · 관리자 반려 사유 · `REJECTED`와 고쳐서 재심사 중인 `PENDING`에 값이 있고 승인 시 지운다 · 임대인 수정 요청은 이 필드를 싣지 않으며 수정이 성공하면 서버가 무조건 비운다 |
| `genderPolicy` | string (enum `GenderPolicy`) | 매물 공통 성별 정책 |
| `languagesSupported` | string[] (enum `SupportedLanguage`) | 임대인이 응대 가능한 언어 코드 |
| `arcRequired` | string (enum `ArcRequirement`) | NOT NULL · `REQUIRED`/`NOT_REQUIRED` · 매물 공통 ARC 요구 여부(아래 註) |
| `favoriteCount` | int | default 0, ≥0 · 비정규화 캐시 |
| `imageUrls` | string[] | 1~5개 · `[0]`=썸네일 · 미리 올려 둔 사진을 등록 확정 시 복사한 CDN URL(`listings/{listingId}/cover/{uuid}.{ext}` — 임시 위치는 `uploads/{landlordId}/…`, [ADR-0041](../adr/0041-listing-image-upload-to-s3.md)) |
| `nearbyUniversityCodes` | string[] | 학교 검색·진단 추천용 코드 |
| `createdAt` | ISODate | NOT NULL |
| `updatedAt` | ISODate | NOT NULL |
| `address` | object | `city`·`district` 코드(문자열 — 정본은 `listingCatalog`의 `CITY`·`DISTRICT`, 주소 토큰에서 파생하며 못 찾으면 `ETC`, [ADR-0046](../adr/0046-administrative-region-as-catalog-data.md)) + 표시 주소 `fullAddress/detail:{ko,en}` |
| `building` | object | 건물 유형·층수·주차·엘리베이터 |
| `description` | object | 매물 상세 설명 `{ko,en}` |
| `extraNotes` | object | 자유 입력 주의사항 `{ko,en}` |
| `facilities` | object | 난방·주방·세탁·생활 편의·보안·공용 공간·제공 물품 · `commonSpaces`는 `CommonSpaceType` 코드 배열 |
| `location` | GeoJSON `Point` | NOT NULL · `[lng,lat]`, `2dsphere`(아래 註) |
| `nearestTransit` | object | `type` 코드(`SUBWAY`)·`name:{ko,en}`·`walkMinutes` |
| `nearbyFacilities` | string[] (enum `NearbyFacility`) | 주변 시설 코드 |
| `refundPolicy` | object | 매물 공통 환불 정책 문장 `{ko,en}` |
| `roomOffers` | object[] | 동일 가격·검색 태그를 가진 실제 방 묶음. 최소 1개 |
| `roomOffers[].roomOfferId` | string | 문서 내부 방 상품 식별자(ObjectId hex 문자열) |
| `roomOffers[].name` | object | 방 상품 고유 표시명 `{ko,en}` |
| `roomOffers[].status` | string (enum `RoomOfferStatus`) | `ACTIVE`/`INACTIVE` |
| `roomOffers[].contract` | object | 방 상품별 계약기간(`minStayMonths`·`maxStayMonths`) |
| `roomOffers[].pricing` | object | `monthlyRent`·`deposit`·`maintenanceFee`·`currency`(KRW 정수, 단일값) |
| `roomOffers[].filterTags` | string[] (enum `ConditionTag`) | 등록 폼의 방 옵션 선택값 · 응답 태그와 1:1(파생 태그 없음) |
| `roomOffers[].roomImageUrls` | string[] | 방 상품 전용 이미지 · 2~5개 · 등록 확정 시 복사한 CDN URL(`listings/{listingId}/rooms/{roomOfferId}/{uuid}.{ext}`) |
| `preferredNationalities` | string[] | 임대인 설문 — 선호 국적 · 응답 비노출(아래 註). **요청에서는 선택**이지만 저장은 항상 배열이다(값이 없으면 `[]`) |
| `contractDifficulties` | string[] | 임대인 설문 — 계약 시 겪은 어려움 · 응답 비노출(아래 註). **요청에서는 선택**이지만 저장은 항상 배열이다(값이 없으면 `[]`) |
| `serviceFeedback` | string | nullable · 임대인 설문 — 서비스 개선 의견 · 응답 비노출(아래 註) |

> `monthlyRent`·`deposit`은 Listing 루트가 아니라 `roomOffers[].pricing`의 단일값이다. 앱의 `minBudget`/`maxBudget`은 조회 조건일 뿐 DB에 범위로 저장하지 않는다. `featureSummary`는 DB에 저장하지 않고, 상세 응답을 만들 때 활성 `roomOffers[].filterTags`의 합집합으로 계산한다. 필터는 반드시 같은 `roomOffers[]` 원소가 가격·옵션을 동시에 만족하는지 `$elemMatch`로 검사한다 — `MOVE_IN_NOW`도 `filterTags`의 태그 하나일 뿐이라 별도 재고 조건을 보지 않는다.

`listingCatalog`

사용자 UI에 표시하는 Listing 공통 코드의 번역 사전이다. 특정 매물 한 건의 코드만이 아니라 현재 Listing UI가 사용할 수 있는 전체 허용 코드를 담는다([ADR-0037](../adr/0037-listing-localization-and-code-catalog.md)).

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | string | PK · `CATEGORY:CODE` |
| `category` | string | 코드가 쓰이는 문맥. 예: `CONDITION_TAG`, `TRANSIT_TYPE`, `KITCHEN` |
| `code` | string | 언어 무관 UPPER_SNAKE 코드. 필터 요청·검증에 사용 |
| `label` | object | 코드 하나의 표시 번역 `{ko,en}`. 팀의 진단 카탈로그와 같은 필드명이며 MVP 기본은 영어 |

**인덱스**: UNIQUE `(category, code)`. `displayOrder`·`active`는 현재 MVP 요구가 없어 저장하지 않는다.

저장 예시는 [`listing-catalog-example.json`](examples/listing-catalog-example.json)을 참고한다. 실제 시드는 예시 몇 건이 아니라 Listing UI가 사용하는 전체 공통 코드 **19종 카테고리·112건**이다 — `ARC_REQUIREMENT`(2)·`BUILDING_TYPE`(7)·`CITY`(4 — `ETC` 포함)·`COMMON_SPACE`(8 — `NONE` 포함)·`CONDITION_TAG`(8)·`DISTRICT`(10 — `ETC` 포함)·`GENDER_POLICY`(4)·`HEATING_SYSTEM`(3 — `NONE` 포함)·`KITCHEN`(10 — `NONE` 포함)·`LAUNDRY`(5 — `NONE` 포함)·`LISTING_TYPE`(3)·`LIVING_AMENITY`(9 — `NONE` 포함)·`NEARBY_FACILITY`(6 — `NONE` 포함)·`PROVIDED_SUPPLY`(7 — `NONE` 포함)·`RENTAL_TYPE`(1)·`SECURITY_FEATURE`(7 — `NONE` 포함)·`SUPPORTED_LANGUAGE`(3)·`TRANSIT_TYPE`(1)·`UNIVERSITY`(14). `status`(`ListingStatus`)는 임대인에게만 보이는 관리 상태라 번역 대상이 아니고, 대응하는 카탈로그 카테고리도 없다.

`universities`

매물 등록이 좌표에서 인근 대학을 파생할 때 대조하는 **대학 좌표 원장**이다([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)). `listingCatalog`가 코드→라벨을 맡고 이 컬렉션이 코드→좌표를 맡으며, 둘은 `code`로 조인한다 — 같은 라벨을 두 곳에 두지 않기 위해 여기에는 표시 이름이 없다.

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | string | PK · 코드값 그대로(`SNU`). 재시드가 같은 문서를 덮어쓴다 |
| `code` | string | NOT NULL · `listingCatalog`의 `UNIVERSITY` code·매물 `nearbyUniversityCodes`와 같은 값(조인 키) |
| `location` | GeoJSON `Point` | **NOT NULL** · `[lng,lat]` · `2dsphere` · 캠퍼스 대표 좌표 |

**인덱스**: `universities_location_2dsphere`(부트스트랩이 멱등 생성). 좌표 없는 문서는 반경 조회에서 영영 잡히지 않으므로 `location`을 validator가 `required`로 막는다.

**시드는 14건**이며 `listingCatalog`의 `UNIVERSITY` 카테고리와 코드가 1:1이다. 좌표 출처는 교육부 학교개황(20241007 기준)의 도로명 주소를 주소 검색 API(NCP Geocoding)로 변환한 값이고, 조인은 학교명이 아니라 학교개황의 **학교코드**로 했다(이름 매칭은 부속고교를 오탐한다). 정본은 [`universities.json`](../../src/test/resources/fixtures/universities.json)이며 운영자가 주입한다([migration-policy §8-1](./migration-policy.md#8-1-시드-주입-절차)). 스키마는 changeUnit `0118 listing-university-collection`이 세운다.

**MVP 구현 메모**

- 주변 시설은 자유 텍스트가 아니라 `nearbyFacilities`의 `NearbyFacility` 코드 배열이다. API 응답에서도 `nearbyFacilities`로 내려주며 다른 공통 코드와 같이 카탈로그 label과 조합한다.
- 고유 문구는 `listings` 안의 `{ko,en}`에서 사용자 언어 하나를 선택한다. `type`·시설·`filterTags` 같은 공통 코드는 원문 code를 유지하고 `listingCatalog`의 label과 조합해 `{code,label}`로 응답한다. 필터 요청은 계속 code를 보낸다.
- **listing 마이그레이션 체인은 v4 baseline으로 리셋됐다.** `0099`~`0114`를 삭제하고 `0115 listing-v4-baseline` 하나가 **스키마만**(v4 validator + 옛 인덱스 2건 삭제) 담당한다. 이어 `0116 listing-location-required`가 `location`을 필수로 조인다([ADR-0042](../adr/0042-road-address-search-with-ncp-geocoding.md)). `0100`(`searchPlaces` 시드)은 [ADR-0043](../adr/0043-remove-seeded-poi-keyword-search.md)으로 삭제됐고, 그 컬렉션은 `0117 listing-search-place-drop`이 드롭한다. `0118 listing-university-collection`이 `universities` validator를 세우고([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)), `0119 listing-contact-sms-drop`이 담당자 연락처에서 `sms`를 뺀다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended — `contact.required`에서 `sms`를 지우고 `properties.contact.sms`를 삭제) — **다섯 다 스키마만 다루고 문서를 넣지 않는다**. 이어 `0120 listing-consents`가 매물 이용약관 동의를 넣고, `0121 listing-status-enum-shrink`·`0122 listing-status-enum-expand`가 `status` enum을 3종으로 조였다가 `UPDATE_PENDING`을 더해 4종으로 넓힌다. `0123 listing-consents-drop`은 validator에서 `consents`를 빼고 이미 저장된 문서의 값을 `$unset`으로 지운다 — 제거 방향이라 `collMod`가 `$unset`보다 먼저다(`required`가 남은 채로는 strict validator가 update를 거부한다). 절차와 근거는 [migration-policy §8-2](./migration-policy.md#8-2-listing-마이그레이션-체인) · [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md).
- **시드(`listings` 2건 · `listingCatalog` 112건 · `universities` 14건)는 운영자가 정본 JSON으로 주입**한다([migration-policy §8-1](./migration-policy.md#8-1-시드-주입-절차)). **`--drop`을 쓰지 않는다** — 컬렉션을 지우면 validator가 함께 사라지고 `0115`·`0118`은 1회성이라 복구되지 않는다. `deleteMany({})` 후 `mongoimport`한다. 신규 환경은 시드 전까지 카탈로그가 비어 라벨 자리에 코드값이 노출되고 **등록되는 매물의 `nearbyUniversityCodes`가 빈 배열로 남아 진단 추천에서 빠지므로**, 배포 절차에 시드 단계를 포함한다.
- seed의 고정 ObjectId는 반복 적재 시 중복 생성을 막기 위한 값이며 운영 ID 생성 규칙이 아니다. MongoDB 저장 예시는 [`listing-seed-example.json`](examples/listing-seed-example.json)에 둔다.

> **장소 후보 검색(`GET /api/v1/listings/places`) — 무상태, 컬렉션 없음**: 지도 검색창 키워드는 네이버 지역 검색 API로 조회한다(아웃바운드 포트 `PlaceSearchClient`, 인프라 어댑터 `NaverPlaceSearchClient`, 설정 `NaverSearchProperties`(prefix `app.naver.search`)). 결과(최대 5개 장소 후보)를 서버에 저장하지 않으므로 이 절엔 관련 스키마가 없다. **경로가 `/api/v1`인 이유**: 매물 조회 계열은 `/api/v2`로 이관되고 `/api/v1` 조회는 DB에 닿지 않는 `deprecated` 스텁이 됐지만, 이 엔드포인트만은 매물 데이터를 쓰지 않아 영향을 받지 않으므로 `/api/v1`에 그대로 둔다([ADR-0040](../adr/0040-listing-query-api-v2-and-v1-sunset.md) · [03-listings-favorites](../api/specs/03-listings-favorites.md)).
>
> **`searchPlaces` 컬렉션은 제거됐다.** 시드 POI 사전으로 키워드 검색(`GET /listings/search`)을 지원하던 컬렉션인데, 그 API가 아무도 쓰지 않는 채로 남아 있어 API와 함께 지웠다. 드롭은 changeUnit `0117 listing-search-place-drop`이 수행한다([ADR-0043](../adr/0043-remove-seeded-poi-keyword-search.md)).

`favorites`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | NOT NULL · UNIQUE(userId,listingId) · → user(값 참조) |
| `listingId` | ObjectId | NOT NULL · UNIQUE(userId,listingId) · → listings.\_id 값 참조 |
| `favoritedAt` | ISODate | NOT NULL · 목록 정렬키(desc) |

`recentListings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | NOT NULL · UNIQUE(userId,listingId) · → user(값 참조) |
| `listingId` | ObjectId | NOT NULL · UNIQUE(userId,listingId) · → listings.\_id 값 참조. 재조회 upsert |
| `viewedAt` | ISODate | NOT NULL · 최신순 정렬키(desc) |

**인덱스:**

| 인덱스 | 대상 | 종류 | 목적 |
| --- | --- | --- | --- |
| `listings.location` | `location` | **2dsphere** | bbox 지도 마커 조회·거리순(`$geoWithin`/`$near`, 반경 검색은 별도 API 확정 시 사용, [ADR-0005](../adr/0005-polyglot-persistence.md) D3) |
| `listings_status_type_rent` | `status, type, roomOffers.pricing.monthlyRent` | 복합/multikey | 공개 매물·유형·방 상품 월세 필터 |
| `listings_landlord_status_updated` | `landlordId, status, updatedAt desc` | 복합 | 임대인 전용 매물 조회(`GET /api/v2/users/me/listings`) — `landlordId` equality + `status` `$in` + `updatedAt` 최신순 |
| `listings_status_room_filter_tags` | `status, roomOffers.filterTags` | 복합/multikey | 여성전용·개인욕실·영어 가능 등 방 상품 옵션 필터 |
| `listings_status_arc_requirement` | `status, arcRequired` | 복합 | ARC 미보유 사용자 추천/필터 |
| `listings_status_created_at` | `status, createdAt desc` | 복합 | 관리자 심사 목록 — 상태로 좁힌 뒤 등록 최신순 |
| `search_places_active_priority_name` | `active, priority desc, name` | 복합 | 활성 POI 후보 목록 조회 |
| `favorites_user_listing` | `userId, listingId` | UNIQUE | 중복 찜 불가·토글 멱등 |
| `favorites_user_favoritedAt` | `userId, favoritedAt` | 복합(desc) | 내 찜 목록 |
| `recentListings_user_listing` | `userId, listingId` | UNIQUE | 재조회 upsert |
| `recentListings_user_viewedAt` | `userId, viewedAt desc` | 복합 | 최근 본 목록 조회·사용자별 오래된 기록 정리 |

> 인덱스는 부트스트랩(`ListingMongoIndexInitializer`)이 소유해 기동 시 멱등 생성한다. `listings_status_arc_requirement`는 옛 `listings_status_arc_required`(키 `status, propertyPolicies.arcRequired`)와 **키가 달라 새 이름으로 만든다** — 같은 이름·다른 키는 멱등 생성으로 갱신되지 않고 `IndexOptionsConflict`가 난다. 옛 인덱스 2건(`listings_status_arc_required`·`listings_status_room_available_count`)의 삭제만 changeUnit `0115`가 1회 수행한다([migration-policy §8](./migration-policy.md#8-mongodb-변경-관리)).

- **임대인 전용 조회는 인덱스를 추가하지 않는다**: `listings_landlord_status_updated`가 그 조회가 요구하는 키(`landlordId, status, updatedAt desc`)를 이미 그대로 갖고 있다. 다만 **`status`가 equality·`$in`으로 묶였을 때만** 접두가 이어져 정렬까지 인덱스로 처리된다 — 상태 필터 없는 기본 호출은 중간 키가 열려 blocking SORT로 떨어진다(`listings_status_created_at`의 상태 없는 전체 조회도 같은 성질이다). 임대인 1인당 매물이 수십 건 규모라는 가정에서 감수하며, 그 가정이 깨지면 기존 인덱스의 키를 고치지 말고 `listings_landlord_updated`(`landlordId, updatedAt desc`)를 **새 이름으로** 추가한다(같은 이름·다른 키는 `IndexOptionsConflict`).
- **교차 스토어/모듈 no-FK**: `landlordId`·`favorites.userId`·`recentListings.userId`는 user(MySQL)를 값으로만 참조한다. `listingId`는 Mongo `_id ObjectId` 값 참조이며 API에서는 문자열로 노출한다.
- **유니크/멱등**: 찜 토글 멱등(신규 201/기존 200, 해제 항상 200). 최근본 재조회는 upsert.
- **카운트 정합**: `favoriteCount`는 `favorites` 집계의 비정규화 캐시다. 현재 찜 문서 insert/delete와 카운터 증감은 같은 MongoDB에서 순차적으로 별도 실행하며 트랜잭션·배치 재계산은 구현되어 있지 않다. 두 번째 쓰기 실패 시 차이가 생길 수 있는 현재 운영 제약이다.
- **찜 목록 노출 조건**: 현재 aggregation은 대상 매물의 `status=PUBLISHED`만 검사하고 ACTIVE `roomOffers` 존재 여부는 검사하지 않는다. 따라서 다른 사용자 조회와 달리 빈 `roomOffers[]` 카드가 포함될 수 있다.
- **최근 본**: 사용자별 최신 30개까지만 보관하고, 조회 API는 그중 `PUBLISHED`이면서 ACTIVE `roomOffers`가 있는 매물만 `viewedAt desc limit 10`으로 반환한다.
- **좌표**: 저장 `[lng,lat]` ↔ API `{lat,lng}` 변환. `location`은 **v4 validator의 `required`**다 — 도로명 주소 검색(`GET /api/v1/listings/addresses`)이 준 좌표를 등록 요청이 되돌려 보내므로 좌표 없는 매물이 생길 경로가 없다([ADR-0042](../adr/0042-road-address-search-with-ncp-geocoding.md)). 최초 v4 baseline(`0115`)은 지오코딩이 없어 이 필드를 선택으로 뒀고, `0116 listing-location-required`가 필수로 조였다(시드 주입 전이라 백필 대상 0건).
- **ARC 요구**: 매물 루트 `arcRequired`(`REQUIRED`/`NOT_REQUIRED`) 한 필드가 정본이다. 파생 태그(`ConditionTag.NO_ARC`)는 없어졌고 `roomOffers[].filterTags` 저장값과 응답 태그가 1:1이다. 진단 `arcStatus`와의 매핑은 §4-4([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)).
- **매물 문서의 임대인 PII**: `businessRegistrationNumber`는 **원문**을, `contact`(`managerName`·`phone`)는 평문을 이 컬렉션에 저장한다 — [ADR-0033](../adr/0033-business-registry-verification.md)의 "원문 비저장·해시로만 영속"을 **매물 문서 한정으로 개정**한 결과다(온보딩·`users` 테이블에는 미채택, `auth`의 검증은 무상태 유지 — §4-1 A-4). `contact.phone`은 지점 대표 전화라 임대인 개인 연락처(`users.phone_number`, 마스킹 대상)와 **별개 값**이므로 마스킹하지 않고 **세입자 응답에 공개**한다. 반대로 **개인 번호는 이 문서에 복사하지 않는다** — 그 값을 그대로 받게 되던 `contact.sms`는 뺐고(changeUnit `0119`, [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended), 소비자가 생기면 저장이 아니라 조회 시점에 `user` 모듈에서 가져와 마스킹해 내보낸다. 응답에서 제외하는 것은 `businessRegistrationNumber`와 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)이다. 임대인 탈퇴 시 이 문서의 PII 처리는 [ADR-0014](../adr/0014-withdrawal-pii-anonymization.md)의 익명화 대상(MySQL `users` 컬럼)에 없어 후속 설계 대상이다.
- `favorited`·`distanceMeters`는 조회 시점 산출 표현값으로 영속하지 않는다(domain-model).

### 4-4. `diagnosis`

> 스토어: **MongoDB** (문서형 애그리거트·임베드 배열·단일 도큐먼트 원자 쓰기). domain-model `Diagnosis`(VO `DiagnosisCriteria` 임베드).

`diagnoses` — `criteria`(VO `DiagnosisCriteria`)는 임베드로 평탄화(`region`·`purpose`·`university`/`district`·`conditions`·`monthlyRentMin`·`monthlyRentMax`·`arcStatus`). 6단계 진단(① 지역 `region` / ② 입국 목적·유학 여부 `purpose` / ③ 대학·지역 선택 `university`(대학 그룹)/`district` / ④ 주거 환경 조건 `conditions` / ⑤ 월세 최소-최대 `monthlyRentMin`/`monthlyRentMax` / ⑥ ARC `arcStatus`)을 단일 도큐먼트로 들고, **진행 중 답을 서버가 단계별로 저장**한다 — 사용자당 진행 중(`status=IN_PROGRESS`) 진단 1건을 draft로 두고 답을 채워가다가, 제출 시 저장된 답을 재검증해 `COMPLETED`로 확정한다(canon: user-stories §2). ③ 대학과 ⑤ 월세 범위 결정 경위는 [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md).

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | long | PK · **ObjectId가 아니라 Long 순번**이다 — 별도 카운터 컬렉션 `diagnosisSequences`(`_id`=`"diagnoses"`·`seq`)를 원자적 `$inc`로 올려 채번한다(스펙의 숫자 `diagnosisId` 계약 유지, §2-2 식별자 규약의 예외) |
| `userId` | long | nullable, 인덱스 · → user(값 참조) · **회원 진단만 채운다** — 게스트 진단은 null이고 `guestSessionId`가 대신 채워진다(아래 註) |
| `guestSessionId` | string | nullable · **게스트 진단만 채운다**(회원 진단은 null) · 값 형식 `anonymous<uuid>` · 클라이언트가 `X-Guest-Session-Id` 헤더로 에코하는 게스트 세션 키 · **채워지는 경로는 v2 흐름뿐**(아래 註) |
| `region` | string (enum `Region`) | 필수 · ① 지역 |
| `purpose` | string (enum `Purpose`) | 필수 · ② 입국 목적·유학 여부 — 단일 enum `STUDY`\|`NON_STUDY`. ③ 대학/지역 조건부 필수의 분기 키(`STUDY`→`university` / `NON_STUDY`→`district`) |
| `university` | string (enum `UniversityGroup`) | nullable · ③ 대학 그룹(입국 목적 `purpose=STUDY`일 때 **필수**·`NON_STUDY`이면 없음 — 앱 레벨 조건부 필수 불변식) · 단일 그룹 코드를 UPPER_SNAKE로 저장 · 값(6개 그룹): `HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC` · 추천 시 그룹은 멤버 대학 코드 집합으로 전개(`SNU_CAU_SOONGSIL`→`{SNU,CAU,SOONGSIL}` 등, `ETC`→멤버 없음: 목록 14곳 전체를 제외 조건으로 넘겨 `$nin` 여집합 매칭), 멤버 코드는 listing `nearbyUniversityCodes`(개별 코드 저장 불변)와 1:1 — [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md) |
| `district` | string (enum `District`) | nullable · ③ 지역구(입국 목적 `purpose=NON_STUDY`일 때 **필수**·`STUDY`이면 없음 — 앱 레벨 조건부 필수 불변식) · UPPER_SNAKE 저장 · 값: `GURO_GU`·`YEONGDEUNGPO_GU`·`GEUMCHEON_GU`·`GWANAK_GU`·`DONGDAEMUN_GU`·`ETC` |
| `conditions` | string[] (enum `DiagnosisCondition`) | 선택, ④는 ≤3, 중복 제거 · ④ 주거 환경 조건 — listing `ConditionTag` 이름으로 통일. 값: `MOVE_IN_NOW`·`FEMALE_ONLY`·`PRIVATE_BATH`·`ENGLISH_OK`·`ADDRESS_REGISTRATION`·`NO_MAINT_FEE`·`MEALS_INCLUDED`·`DOUBLE_ROOM`(8종). 사용자가 ④에서 고른 값만 저장하며 **파생 주입이 없어** `≤3` 제한에 예외가 없다(아래 註) |
| `monthlyRentMin` | int(KRW) | 필수, ≥0 · ⑤ 월세 최소 · `monthlyRentMin ≤ monthlyRentMax`(앱 레벨 불변식) |
| `monthlyRentMax` | int(KRW) | 필수, ≥0 · ⑤ 월세 최대 · `monthlyRentMin ≤ monthlyRentMax`(앱 레벨 불변식) |
| `arcStatus` | string (enum `ArcStatus`) | 필수 · ⑥ ARC |
| `status` | string (enum `DiagnosisStatus`) | 필수 · `IN_PROGRESS`→`COMPLETED`(제출 확정) 또는 `IN_PROGRESS`→`DISCARDED`(v2 미완주 시도 폐기). 진행 중 답은 `IN_PROGRESS` draft에 저장 |
| `submittedAt` | ISODate | nullable · **종료 시각** — `COMPLETED`는 제출 확정 시각, `DISCARDED`는 폐기 시각(`IN_PROGRESS`에선 미설정). 상태가 어느 종료인지 말해주므로 타임스탬프 필드는 하나로 통일 |

> 위 답 필드의 **필수는 `COMPLETED` 기준**이다 — `DISCARDED` 문서는 6단계를 못 채우고 끝난 시도라 `region`을 뺀 나머지가 비어 있을 수 있다(부분 답 그대로 보존). 아래 "미완주 시도 보존" 참조.

**인덱스**: PK `_id` / 복합 `(userId, submittedAt desc)`(이력 목록·최신 단건 — 부트스트랩 initializer `DiagnosisIndexInitializer`가 `userId_submittedAt_idx`로 멱등 생성). **TTL 인덱스는 없다** — 이 컬렉션은 회원 진단도 만료 없이 영구 보존하며, 게스트 문서 때문에 TTL을 **새로 도입할지 자체가 미확정**이다(아래 註·§6-11).

- **진행 중 저장(server-stateful)**: 진단 답은 서버가 단계별로 저장한다 — 사용자당 진행 중(`status=IN_PROGRESS`) 진단 1건을 draft로 두고 `POST /api/v1/diagnoses/answers`(body `{ field, code }`, `conditions`처럼 다중은 `codes` 배열)가 현재 step 답을 그 draft에 채운다(누적 답 재전송 없음). 문항은 `GET /api/v1/diagnoses/questions/{step}`로 step별 1개씩 받는다(다음 step 번호는 클라가 정한다). 모든 단계 답이 채워지면 `POST /api/v1/diagnoses`가 저장된 답을 재검증해 `COMPLETED`로 확정(`submittedAt` 설정, `201` + `Location` 헤더)한다. **이력·목록·최근 단건 조회는 `COMPLETED`만 노출**하고 `IN_PROGRESS`는 제외한다.
- **재진단=새 진행 중 진단 시작**(수정 아님) → 새 `IN_PROGRESS` draft를 시작해 채운 뒤 `COMPLETED`로 확정. 기존 진단을 덮어쓰지 않으므로 `updatedAt`/소프트삭제 없이 단계별 답 채움과 확정 전이만 둔다.
- **지역 수요 보존(`DISCARDED`, v2)**: v2 ① 지역 0건 예외질문에 답이 오면 그때까지의 부분 답(`region`만)을 `status=DISCARDED`로 이 컬렉션에 남긴다 — **"어느 지역을 원했는데 매물이 없었나"를 수요 신호로 쓰기 위해서**다. **"예"(재시도)·"아니오"(종료) 양쪽 모두** 남긴다(재시도를 빼면 사용자가 다른 지역으로 완주했을 때 원래 원했던 지역이 증발한다). **그 외 이탈(답하다 앱을 닫음)은 남기지 않는다** — 이탈은 요청으로 오지 않아 다음 `POST /start` 때에야 알 수 있고, 그러면 영영 안 돌아온 사용자가 누락되고 시각도 재시작 시각이라 집계가 편향된다. **사용자 노출 경로를 두지 않는다** — 목록(이력·최근)은 `COMPLETED`만, v1 진행 중 초안 조회는 `IN_PROGRESS`만 보므로 자동으로 빠져 v1 흐름을 오염시키지 않는다. 다만 **id로 직접 오는 상세·추천은 소유권만 보므로 응용 계층이 명시적으로 404로 거절**한다(폐기 기록은 본인 것이고 진단 id가 순차 발급이라 추측 가능하다 — 이 가드가 없으면 내부 기록이 API로 샌다). 확정과 달리 **완결성 검증을 하지 않는다**(부분 답이 정상). 집계는 `status=DISCARDED` + `region`으로 지역별 미충족 수요를 센다(포기/재시도 구분은 없다 — `reason`을 담지 않는다). 상세 [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md).
- **소유권**: 조회는 `userId` 일치 필수, 타인 `403`, 없으면 `DIAGNOSIS_NOT_FOUND`(404).
- **신원 표현(회원 `userId` | 게스트 `guestSessionId`) — 정확히 하나만 채워진다**: 비회원(게스트) 진단이 열리면서(#181) 이 컬렉션은 신원 필드를 둘 갖는다 — **회원 문서는 `userId` 채움·`guestSessionId` null**, **게스트 문서는 `userId` null·`guestSessionId` 채움**이며 둘 다 채워지거나 둘 다 비는 문서는 없다. 게스트 신원을 `userId`에 합성 문자열(`anonymous`+uuid)로 담지 않는 이유는 이 컬렉션의 기존 `userId`가 **BSON Int64**라 String으로 넓히면 기존 회원 문서가 조용히 매치되지 않기 때문이다 — 그래서 `userId`는 `long` 그대로 두고 **데이터 마이그레이션 없이** 필드만 하나 추가한다. 이 "정확히 하나" 불변식은 `$jsonSchema` validator가 아니라 **앱 레벨**로만 강제된다 — Mongo 컬렉션은 스키마리스가 기본이고(§3) `$jsonSchema` validator를 두는 것은 `listings`뿐이라 이 컬렉션에는 없다([ADR-0005](../adr/0005-polyglot-persistence.md) D7). 조회는 신원 종류에 따라 키를 고르고, 소유권 검사는 **신원 종류가 같고 값이 같을 때만** 통과한다(한쪽이 null이면 무조건 거절 — 진단 `_id`가 전역 순차 채번이라 열거가 쉬워 이 검사가 게스트 경로의 유일한 IDOR 방어선이다).
- **게스트 문서를 만드는 경로는 v2 흐름뿐**: **v1 진단(`/api/v1/diagnoses/**`)은 회원 전용으로 유지한다** — SecurityConfig에 v1 진단용 permitAll 매처를 두지 않아 v1 7개 엔드포인트는 현행대로 `.anyRequest().authenticated()`에 남고(토큰 없으면 401), 따라서 **v1 경로로는 `guestSessionId`가 채워진 문서가 생기지 않는다**. 게스트 진단의 정본 경로는 v2뿐이고(`POST /api/v2/diagnoses/start` → `POST /api/v2/diagnoses/next` → `GET /api/v2/diagnoses/{id}/recommendations`), 신규 permitAll 매처의 대상도 `/api/v2/diagnoses/**`뿐이다. 귀결로 **게스트용 v1 이력(`GET /api/v1/diagnoses`)·최근 단건(`GET /api/v1/diagnoses/latest`) 시맨틱을 정의할 필요가 없고**(게스트는 401이라 도달하지 못한다), v1 답 저장(`POST /api/v1/diagnoses/answers`) 응답으로 세션 키를 발급하는 계약 변경도 없다. 이 컬렉션의 게스트 문서는 v2가 **종료 상태로만** 쓴다 — 확정(`COMPLETED`)과 지역 0건 폐기(`DISCARDED`)뿐이라 게스트 문서에는 `IN_PROGRESS`가 없다(v2 진행 중 상태는 `diagnosisFlowSessions`에 있다, 아래).
- **게스트 문서 만료(TTL) — 도입 여부부터 결정 필요**: 게스트 진단은 `userId` 키로 덮어써지지 않아 요청마다 새 문서로 누적되고 전역 순차 채번(`diagnosisSequences`)도 함께 소모하므로 만료를 검토한다. 다만 **현재 진단 컬렉션에는 TTL 인덱스가 하나도 없다** — 부트스트랩 `DiagnosisIndexInitializer`가 만드는 것은 `userId_submittedAt_idx`와 `diagnosisQuestions`의 `active_step_idx`뿐이고, `DiagnosisFlowSessionIndexInitializer`가 만드는 것은 `userId` UNIQUE뿐이다(회원 진단도 만료 없이 영구 보존한다). 따라서 **게스트 때문에 TTL을 새로 도입할지가 첫 결정**이며(도입하지 않고 회원과 동일하게 영구 보존하는 것도 선택지다), 도입한다면 다음이 따라온다.
  - **범위는 partial이어야 한다**: 회원 문서는 이력이라 만료 대상이 아니므로 TTL은 `guestSessionId`가 존재하는 문서로 좁힌 **partial TTL**(`expireAfterSeconds` + `partialFilterExpression: { guestSessionId: { $exists: true } }`)이어야 한다 — 전역 TTL은 회원 이력을 지운다. 인덱스는 Mongock이 아니라 부트스트랩 소관이라 `DiagnosisIndexInitializer`에 신규 작성이 필요하다([migration-policy §8](./migration-policy.md) — 인덱스=부트스트랩).
  - **보존 기간 후보**: 설계 검토에서 제안된 후보는 **30일**(`diagnosisFlowSessions` 게스트 세션 후보는 **24시간** — 아래)이나 확정값은 제품 결정이다.
  - **부수 결정 ①(이 컬렉션의 기준 필드)**: TTL 기준 시각 필드 후보는 `submittedAt`인데, 이 값은 **`IN_PROGRESS` 문서엔 없어 그런 문서는 만료되지 않는다**(Mongo TTL은 기준 필드가 없는 문서를 건너뛴다). 게스트 문서는 v2가 종료 상태(`COMPLETED`·`DISCARDED`)로만 써서 항상 값이 있지만, v1 회원 draft가 영구히 남는 셈이므로 기준 필드를 `submittedAt`으로 둘지 별도 생성 시각을 둘지 명시해야 한다.
  - **부수 결정 ②(`diagnosisFlowSessions`의 기준 필드)**: 그 컬렉션엔 **시각 필드가 아예 없어** TTL을 걸려면 기준 필드부터 신설해야 한다(아래 §`diagnosisFlowSessions`).
- **ARC 조건 파생 제거**: ⑥ `arcStatus=NO_ARC`(미발급)일 때 서버가 `DiagnosisCondition.NO_ARC`를 `conditions`에 주입하던 규칙과, 그 값을 **"최대 3개 제한에서 제외"하던 예외 조항**은 없앴다 — ARC 요구 여부는 매물 루트 `arcRequired` 한 필드로만 표현하고 추천은 그 필드를 직접 필터한다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). `DiagnosisCondition` enum에서도 `NO_ARC` 상수를 제거했다(진단 답 `ArcStatus.NO_ARC`는 이름만 같을 뿐 그대로 유지된다 — 혼동 주의). 기배포 환경의 `diagnoses` 문서에 남은 `conditions` 원소 `NO_ARC`는 Mongock changeUnit이 1회 제거한다(컬렉션 drop 없이 영향 문서만 이행 — [migration-policy §8](./migration-policy.md#8-mongodb-변경-관리)).
- **교차 모듈 no-FK**: `userId` 값만. 추천은 diagnosis가 `RecommendationCriteria`(지역·조건·`includedUniversityCodes` 멤버 코드 집합·월세 min-max·지역구·`arcStatus`)로 `listing` 공개 query를 동기 호출해 `RecommendedListingView` 페이지를 수신한다([ADR-0002](../adr/0002-inter-module-communication-via-events.md) D5). `includedUniversityCodes`는 선택 그룹을 전개한 `Set<String>`(`ETC`는 멤버 대신 `excludedUniversityCodes`가 목록 전체, [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md))이고, 월세는 nullable `monthlyRentMin`/`monthlyRentMax`다. listing은 `nearbyUniversityCodes`를 `$in`으로, 같은 ACTIVE `roomOffers[]` 원소의 `pricing.monthlyRent`를 각 경계로 매칭하며, `arcStatus=NO_ARC`는 매물 루트 `arcRequired=NOT_REQUIRED`로 옮겨 **직접 필터**한다(`ARC_ISSUED`는 필터 미적용).
- **진단↔매물 값 매핑**: 두 모듈은 각자의 입력 어휘를 보유하므로 값 집합을 일치시키지 않고 **매핑**으로 잇는다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 진단 `Region` 3종(`SEOUL`·`BUSAN`·`GYEONGGI`)은 매물 `address.city`(`City`, 카탈로그 3종)와 등가 비교하고, 진단 `District`의 5구는 매물 `address.district`(`District`, 카탈로그 9종)와 등가 비교하되 **`ETC`는 그 5구의 여집합(`$nin`)**으로 질의하며, 진단 `ArcStatus`는 `NO_ARC → arcRequired=NOT_REQUIRED`·`ARC_ISSUED → 필터 미적용`으로 옮긴다. 진단 `District`는 "선택지에 없는 그 외"라는 `ETC`의 의미를 지키려고 **6종 그대로 두고** 매물 9종에 맞춰 넓히지 않는다.
- **3단계 대학/지역 조건부 필수**: `university`/`district`는 **두 필드로 분리**하며 NOT NULL 제약이 아니라 **앱 레벨 조건부 필수 불변식**으로 강제한다 — 입국 목적(`purpose`)에 맞는 하나만 채워진다: `purpose=STUDY`면 `university` 필수·`district` 없음, `purpose=NON_STUDY`면 `district` 필수·`university` 없음. 위반은 공통 `400 INVALID_INPUT`+`errors[]`(신규 도메인 코드 없음). enum 값 목록은 위 필드 표(`UniversityGroup`: `HUFS_KHU_KOREA`·…·`ETC` 6개 그룹 / `District`: `GURO_GU`·…·`ETC`)대로.
- **문항·선택지 출처(US-2-5)**: 문항 제공은 **단계별 server-stateful 질의응답**이다 — 클라이언트가 받을 step(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`(인증 필수, 200)를 호출하면 서버가 (카탈로그 + 진행 중 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정해 `{ step, field, question(사용자 언어 라벨 문자열), select{type,max}, options[{code,label}] }`로 내려준다(한 번에 다 주지 않음, 다음 step 번호는 클라가 정한다). 현재 step 답은 별도로 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; `conditions`처럼 다중은 `codes` 배열)로 보내면 서버가 진행 중(`IN_PROGRESS`) 진단에 저장한다(누적 답 묶음 전송 없음). 흐름은 `GET questions/1 → POST answers → GET questions/2 → … → GET questions/6 → POST answers → POST /diagnoses`이며, 모든 단계 답이 저장되면 `POST /api/v1/diagnoses`가 진행 중 진단을 `COMPLETED`로 확정한다. 반환 선택지 `code`는 **확정 검증 enum과 1:1 동일 출처**다(언어 무관 단일 키). 문항·선택지 카탈로그는 **MongoDB `diagnosisQuestions` 컬렉션**(아래)에 데이터로만 영속한다. **분기는 서버 비즈니스 로직(diagnosis 서비스 코드)이 결정한다(클라 로컬 분기·카탈로그 분기 메타 아님)** — ③(step 3)은 저장된 `purpose`에 따라 서비스가 알맞은 질문만 담는다(`STUDY`→대학 질문 `university`, `NON_STUDY`→지역구 질문 `district`; 한 응답에 두 목록을 함께 주지 않음). 잘못된 현재 step 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `400 INVALID_INPUT`+`errors[]`.
- **라벨 번역(US-2-6)**: 표시 `question`·`label`만 사용자 표시 언어로 번역하고 `code`는 언어 무관 동일이다. 번역 표시 문자열은 `diagnosisQuestions` 도큐먼트 내부 `question`·`label`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드하고, 추천 사유/액션 텍스트는 `diagnosisSuggestions` 컬렉션의 **동일한 인라인 언어-키 맵**에 둔다(같은 방식 재사용). **해당 언어 키가 없으면 영어(`en`) 폴백**(에러 아님, `Accept-Language` 비의존). 표시 언어는 **user 모듈 공개 query(`getLanguage`)로 동기 취득**하고 `user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 폴백한다(토큰 클레임 분기 아님, [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5 · [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); 교차 모듈 no-FK 값 참조) → 모듈 의존 `diagnosis`→`user`.
- **검증 불변식**(앱 레벨): `purpose` 단일 enum(`STUDY`\|`NON_STUDY`)·`conditions≤3`·`monthlyRentMin`/`monthlyRentMax` 각 정수 `≥0` 및 `monthlyRentMin ≤ monthlyRentMax`(위반 시 `INVALID_INPUT` 사유 `"0 이상이어야 합니다."`·`"monthlyRentMin은 monthlyRentMax 이하여야 합니다."`)·필수 필드·3단계 대학/지역 조건부 필수(`400 INVALID_INPUT`).

#### 문항·선택지 카탈로그 — `diagnosisQuestions`

진단 6단계의 문항·선택지·제약을 **데이터로만** 영속하는 카탈로그 컬렉션이다(US-2-5). 분기 메타(`branchOn` 등)는 두지 않는다 — 어느 질문을 낼지(③ 대학/지역)는 diagnosis 서비스 비즈니스 로직이 결정한다(D4). v2 서버 주도 흐름(#157 · [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md))이 ① 지역 0건일 때 끼워 넣는 **예외질문도 서버 코드에 하드코딩한 합성 문구가 아니라 이 카탈로그의 일반 문항**(step 1·`field=regionRetry`)이다 — 예외질문을 따로 관리하지 않고 6단계 문항과 같은 곳에 둔다(아래 註). 표시 문자열(번역)은 별도 컬렉션 없이 도큐먼트 내부 `question`·`label`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드한다(US-2-6). `GET /api/v1/diagnoses/questions/{step}`(클라가 받을 step을 path로 지정)이 (이 카탈로그 + 진행 중 진단에 저장된 답 + 사용자 언어 키)으로 **그 step 질문 1개만** 선정·조립해 내려준다(한 번에 다 주지 않음, 다음 step 번호는 클라가 정한다). 선택지 `code`는 **확정 검증 enum과 동일 출처**(언어 무관 단일 키)다. 시드(정본 시드 JSON 주입)로 적재, 운영 중 `active`로 가변.

`diagnosisQuestions`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| ~~`step`~~ | — | **없다** · 진단 단계 번호와 순서는 **코드**(`DiagnosisFlowStep`)가 갖는다 — 카탈로그는 문항의 표현만 담는다(ADR-0036 결정 6). 기배포 환경의 잔재는 정본 시드에도 없다. 옛 컬럼 설명: 진단 단계(①~⑥) · `GET /questions/{step}` path로 지정해 조회하는 단계 키 · **한 step에 문항이 둘일 수 있다**(③=`university`/`district` 분기) → 응용 계층이 `field`로 택일(목록 순서에 기대지 않음) |
| `field` | string | NOT NULL · 제출 필드명(`region`·`regionRetry`(① 지역 0건 예외질문 — 그 답 `YES`/`NO`는 `diagnoses`의 필드가 아니다, 아래 註)·`purpose`·`university`/`district`·`conditions`·`monthlyRent`(⑤ min/max)·`arcStatus`) · step 내 문항 식별 키 |
| `question` | object | NOT NULL · 문항 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `select` | object | NOT NULL · `type`(enum `SINGLE`/`MULTI`/`NUMBER_RANGE`)·`max`(int, MULTI 상한) — 선택 제약(③ 대학 그룹=`SINGLE`·④ 조건=`MULTI`·⑤ 월세 범위=`NUMBER_RANGE`(두 숫자 입력 min/max) 등) |
| `options` | object[] | 선택지 배열 · 각 항목은 `code`(제출 검증 enum과 동일·언어 무관 불변)와 `label`(표시 문자열의 **인라인 언어-키 맵**, 예 `{ "en": "Seoul", "ja": "ソウル" }`)을 보유 · ③ 대학 그룹 step은 6개 그룹(`HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC`)이 `code`로 1:1(`UniversityGroup` enum과 동일) · ⑤ 월세 범위(`NUMBER_RANGE`)는 자유 수치 입력이라 `options` 비움 |
| `active` | bool | NOT NULL DEFAULT true · 비활성 문항/선택지는 응답에서 제외 |

**인덱스**: PK `_id` / **UNIQUE `field`**(문항 조회 키의 유일성 — 카탈로그가 순서를 담지 않고 `field`로 식별하므로, 중복이 생기면 어느 문항이 나갈지가 시드 순서에 좌우된다. 인덱스가 시드 시점에 막는다). 부트스트랩 initializer(`DiagnosisQuestionIndexInitializer`)가 멱등 생성한다 — 옛 `(active, step)` 인덱스는 order 0006 changeUnit이 제거한다.

- **출처 일치**: `options[].code`는 `diagnoses`의 제출 검증 enum(`Region`·`Purpose`·`UniversityGroup`/`District`·`ConditionTag`·`ArcStatus`)과 **1:1 동일 키**라 `GET /questions/{step}` 응답·`POST /answers` 답 저장·`POST /diagnoses` 확정 검증이 모두 같은 카탈로그를 본다(언어와 무관). ③ 대학 그룹은 `UniversityGroup`의 6개 그룹 코드와 1:1이다. 예외는 둘 — ⑤ 월세 범위(자유 수치 입력·`options` 비움)와 ① 예외질문 `regionRetry`(`YES`/`NO`는 진단 답이 아닌 흐름 제어 응답)다(아래).
- **⑤ 월세 범위 carve-out(`NUMBER_RANGE`)**: ⑤ 월세 범위 step은 고정 선택지가 아니라 **자유 수치 입력(min/max 두 숫자)**이라 `select.type=NUMBER_RANGE`·`options` 비움으로, **"모든 step은 코드가 enum과 1:1인 고정 선택지 목록"이라는 전제에서 의도적으로 제외**된다(수치 범위는 enum 옵션이 아닌 자유 입력). 답 제출은 `POST /api/v1/diagnoses/answers`가 `code`/`codes`가 아닌 `{ "field": "monthlyRent", "min": 300000, "max": 600000 }` 형태의 두 수치 필드로 보낸다 — [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md).
- **번역**: 표시 문자열은 도큐먼트 내부 `question`·`options[].label`의 **인라인 언어-키 맵**(`{ lang → message }`)에 임베드한다 — 서버가 사용자 언어 키를 골라 조립한다. 표시 언어 키는 **user 공개 query(`getLanguage`)로 취득한 표시 언어**로 선택하고 해당 키가 없으면 `en` 폴백(에러 아님, `Accept-Language` 비의존). `user`는 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 폴백하며 교차 모듈 **값 참조**(no-FK)다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)).
- **③ 분기(서버 결정)**: 대학/지역 단계는 **분기 메타 없이** 두 질문(`university`·`district`)이 데이터로 각각 존재하고, `GET /api/v1/diagnoses/questions/{step}`이 호출되면 **diagnosis 서비스 비즈니스 로직**이 진행 중 진단에 저장된 `purpose`를 보고 어느 질문을 낼지 결정해 하나만 골라 내려준다 — `STUDY`면 대학 목록으로 `university` 질문, `NON_STUDY`면 지역구 목록으로 `district` 질문(한 응답에 두 목록을 함께 주지 않음, 클라 로컬 분기 아님).
- **① 지역 0건 예외질문(`regionRetry`)**: v2 흐름이 ① 지역 답 직후 매칭 0건일 때 끼워 넣는 "다른 지역 방을 찾아보시겠어요?" 문항이다 — **별도 컬렉션·별도 결과코드가 아니라 이 카탈로그의 일반 문항**(`field: "regionRetry"` · `select: { type: "SINGLE", max: 1 }` · `options: [{code:"YES"},{code:"NO"}]`)이며, 번역도 다른 문항과 같은 인라인 언어-키 맵에 둔다. ③ 분기(university/district)와 마찬가지로 **step 1에 `region`과 나란히 두고 응용 계층이 `field`로 택일**한다(step만으로는 지목할 수 없다). `options[].code`(`YES`/`NO`)는 흐름 제어 응답이라 **`diagnoses`의 제출 검증 enum과 1:1이 아니다** — 진단 답이 아니므로 `diagnoses`에 저장되지 않는 유일한 문항이다(다른 문항의 "코드=enum 1:1" 전제에서 ⑤ 월세 범위 carve-out과 나란한 예외). 응답 처리·결과코드는 [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md).
- **추천 사유/액션 번역**: 추천 0건 `suggestions`의 `reason`/`actions[].type`(언어 무관 enum 키)의 표시 `message`/`detail`은 **`diagnosisSuggestions` 전용 컬렉션**(아래)의 `reason`별 인라인 언어-키 맵에서 서버가 사용자 언어 키로 골라 제공한다(문항 카탈로그와 동일 방식, 없으면 `en` 폴백). **v1 전용**이다 — v2 흐름은 `NO_MATCH`에 제안을 싣지 않아 이 컬렉션을 참조하지 않는다(§4-4 `diagnosisFlowSessions`).
- **시드**: 카탈로그 적재는 마이그레이션이 아니라 **운영자가 정본 JSON을 주입**한다 — 정본은 [`diagnosis-questions.json`](../../src/test/resources/fixtures/diagnosis-questions.json)(8건)·[`diagnosis-suggestions.json`](../../src/test/resources/fixtures/diagnosis-suggestions.json)(1건)이고 절차는 [migration-policy §8-1](./migration-policy.md#8-1-시드-주입-절차)이다([ADR-0032](../adr/0032-mongodb-migration-runner.md) §4). 문항 `_id`는 `field` 값(`region`·`regionRetry`·`purpose`·`university`·`district`·`conditions`·`monthlyRent`·`arcStatus`)이라 재주입해도 중복되지 않는다. 인덱스는 부트스트랩이 멱등 생성한다.

#### 추천 조정 제안 — `diagnosisSuggestions`

추천 0건일 때의 조정 제안(`suggestions`) 표시 문자열을 사유(`reason`)별로 영속하는 카탈로그 컬렉션이다(US-2-2·US-2-6). 표시 문자열은 문항 카탈로그와 동일하게 **인라인 언어-키 맵**으로 두고, `reason`·`actions[].type`은 언어 무관 enum 식별 키다. 서버가 사용자 언어 키로 `message`/`detail`을 골라(없으면 `en` 폴백) 응답을 조립한다. 시드/마이그레이션으로 적재, 운영 중 갱신 가능.

`diagnosisSuggestions`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | string | PK · 사유 식별 키(언어 무관, 예 `NO_MATCH`) |
| `message` | object | NOT NULL · 사유 안내 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ko": "..." }`) |
| `actions` | object[] | 조정 액션 배열 · 각 항목은 `type`(언어 무관 식별 키, 예 `RELAX_REGION`)와 `detail`(표시 문자열 **인라인 언어-키 맵**)을 보유 |

#### v2 서버 주도 진행 세션 — `diagnosisFlowSessions`

서버 주도(next) 진단 흐름(v2, issue #157 · [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md))의 **진행 상태**를 담는 컬렉션이다. v1의 진행 중 초안(`diagnoses`의 `IN_PROGRESS`)을 공유하지 않고 분리한다 — `pendingField` 같은 절차 필드가 `diagnoses` 스키마에 없고, v1의 "사용자당 `IN_PROGRESS` 1건" 제약과 충돌하기 때문이다. **완료(마지막 슬롯 응답) 시에만** `draft`를 정본 `Diagnosis`로 확정해 `diagnoses`에 저장한다(사용자당 최대 1 세션). 세션은 **클라이언트가 `POST /api/v2/diagnoses/start`를 호출할 때만 생기고**(진행 중 세션이 있어도 버리고 빈 `draft`·`pendingField=region`으로 새로 만든다 — 중단했다 다시 시작하면 언제나 처음부터) 터미널(확정·재시도·종료)에서 삭제된다. 세션 없이 `POST /next`가 오면 서버가 흐름을 되살리지 않고 `400 DIAGNOSIS_SESSION_NOT_FOUND`로 거절한다(클라가 `/start`로 복구). 문항 카탈로그는 v1과 같은 `diagnosisQuestions`를 공유하고(예외질문 `regionRetry` 포함 — 위), v1 컬렉션(`diagnoses`·`diagnosisQuestions`·`diagnosisSuggestions`)의 **필드 구조는 변경하지 않는다**. 다만 `diagnoses.status` enum에 v2 전용 값 `DISCARDED`(미완주 시도 보존)가 추가되며, 그 값은 v1의 어떤 조회에도 잡히지 않아 v1 동작은 그대로다(위 §`diagnoses`).

`diagnosisFlowSessions`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK |
| `userId` | long | nullable · **회원 세션만 채운다** · partial UNIQUE(사용자당 1 세션 — 아래 인덱스 註) · → user(값 참조) |
| `guestSessionId` | string | nullable · **게스트 세션만 채운다**(회원 세션은 null) · partial UNIQUE(세션 키당 1 세션) · 값 형식 `anonymous<uuid>` · 클라이언트가 `X-Guest-Session-Id` 헤더로 에코한다 · **이 컬렉션 자체가 v2 전용**이라 게스트 세션은 `POST /api/v2/diagnoses/start`에서만 생긴다(v1은 회원 전용 — 위 §`diagnoses`) |
| `draft` | object | 누적 답 스냅샷 — `diagnoses`의 criteria와 동형(`region`·`purpose`·`university`/`district`·`conditions`·`monthlyRentMin`/`monthlyRentMax`·`arcStatus`)의 **부분 값**(단계별로 채워짐) |
| `pendingField` | string | 서버가 직전에 낸 문항의 `field`(예: `region`·`purpose`·`regionRetry`) — 다음 답의 기대값이자 **진행의 단일 정본** |

**인덱스**: PK `_id` / **partial UNIQUE `(userId)`**(사용자당 1 세션 — `userId` 존재 시로 좁힘) / **partial UNIQUE `(guestSessionId)`**(게스트 세션 키당 1 세션). **TTL 인덱스는 없다** — 게스트 세션 만료를 도입할지 자체가 미확정이고, 도입 시엔 기준 시각 필드부터 신설해야 한다(아래 註·§6-11). 기동 시 부트스트랩 initializer(`DiagnosisFlowSessionIndexInitializer`, `@Profile("!test")`)가 멱등 생성한다 — 인덱스는 Mongock이 아니라 부트스트랩으로 만든다([migration-policy §8](./migration-policy.md) — 인덱스=부트스트랩, 시드·데이터 진화=Mongock). **현행 코드가 실제로 만드는 것은 비-partial UNIQUE `userId_unique_idx` 하나뿐**이라 위 두 partial UNIQUE는 아직 전환 대상이다(아래 註).

- **왜 UNIQUE를 partial로 좁히고 게스트용을 따로 두는가(#181)**: 기존 `userId` UNIQUE를 그대로 두면 **게스트 세션 문서들의 `userId=null`이 서로 충돌**해 두 번째 게스트부터 세션을 만들 수 없다(단일 UNIQUE 인덱스에서 null도 하나의 값으로 취급된다). 그래서 (a) 회원용 인덱스는 `partialFilterExpression: { userId: { $exists: true } }`로 좁혀 **"사용자당 1 세션"만 남기고**, (b) 게스트용은 `partialFilterExpression: { guestSessionId: { $exists: true } }`인 **별도** UNIQUE 인덱스로 둔다. 한 인덱스에 두 신원을 섞지 않는다. 회원 경로의 제약(사용자당 1 세션)은 그대로다.
  - **기존 인덱스 교체가 선행돼야 한다(주의)**: 이미 배포된 환경에는 partial이 **아닌** UNIQUE 인덱스 `userId_unique_idx`가 있고, MongoDB는 **같은 이름에 다른 옵션으로 `createIndex`를 하면 `IndexOptionsConflict`로 거절**한다. 현행 initializer는 그 예외를 `catch (RuntimeException)` + `log.warn`으로 삼키므로 인덱스가 **조용히 옛 정의로 남고**, 그러면 두 번째 게스트 세션 삽입이 `userId=null` 중복으로 실패한다(기동은 정상, 실패는 런타임에만 보인다). 따라서 partial 전환은 **기존 인덱스 drop 후 재생성**이 필요하며, 이 drop이 부트스트랩 initializer 소관인지 Mongock `@ChangeUnit` 소관인지도 함께 정한다(§6-11 · [migration-policy §8](./migration-policy.md)).
- **게스트 세션 만료(TTL) — 도입 여부부터 결정 필요**: 회원 세션은 `userId` 키 upsert로 덮어써지지만 **게스트 세션은 키가 매번 달라 절대 덮어써지지 않아** 누적된다(터미널에서 삭제되지 않고 이탈한 세션이 남는다). 다만 **이 컬렉션에도 TTL 인덱스가 없다** — `DiagnosisFlowSessionIndexInitializer`가 만드는 것은 `userId` UNIQUE 하나뿐이다. 도입할지 자체가 미확정이며, 도입한다면 `guestSessionId`가 존재하는 문서로 좁힌 **partial TTL**이어야 하고(회원 세션은 upsert로 1건 유지라 만료 대상이 아니다) 보존 기간 후보는 **24시간**이다(위 `diagnoses` 게스트 문서 후보는 30일). 위 §`diagnoses`의 **부수 결정 ②**가 여기에 걸린다 — 이 컬렉션에는 **시각 필드가 아예 없어 TTL 기준 필드(생성 시각)를 신설**해야 한다(Mongo TTL은 BSON 날짜 필드를 요구하므로 `_id`의 ObjectId 내장 시각으로는 대체할 수 없다 — §6-11).

- **정본 순서**: `REGION → PURPOSE → UNIVERSITY_OR_DISTRICT(purpose로 university|district) → CONDITIONS → MONTHLY_RENT → ARC_STATUS`를 **`pendingField`가 강제**한다(직전에 낸 문항과 일치하는 답만 받고, 다음 문항은 `ofField(답한 field).next()`) — `Diagnosis`의 확정 검증(`validateComplete`)이 `conditions`를 필수로 보지 않아(비어도 통과) 답 필드 null로 진행을 추론하지 않는다. **순서의 정본은 코드**(`DiagnosisFlowStep`의 선언 순서)이고 이 컬렉션은 진행 슬롯 수를 세지 않는다 — 6슬롯에 없는 `regionRetry`가 끼어들면 "몇 개 답했나"와 "무엇을 물었나"가 어긋나기 때문이다([domain-model §4](../architecture/domain-model.md)·[ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md) 결정 2).
- **자동 확정·재시도·종료**: 마지막 슬롯(⑥ `arcStatus`)을 답하면 서버가 `draft`를 `Diagnosis.complete()`로 확정해 `diagnoses`에 저장하고 세션을 삭제한 뒤 결과코드 `COMPLETED`와 `diagnosisId`를 내려준다 — **이때 매칭을 조회하지 않는다**(매칭 0건이어도 `COMPLETED`다). **추천 매물은 클라이언트가 결정해 `GET /api/v2/diagnoses/{id}/recommendations`로 별도 조회**하며(응답 인라인 없음), 0건이면 그 응답의 `resultCode=NO_MATCH`가 알려준다(조정 제안 문구·액션은 없음 — `diagnosisSuggestions` 미참조). ① 지역 답 직후 지역 기준 매칭이 0건이면 카탈로그의 `regionRetry` 문항을 일반 질문으로 내려주고 `pendingField=regionRetry`로 기록하며(정본 슬롯은 전진하지 않는다), 그 응답은 **둘 다 터미널이라 세션을 삭제**한다 — "예"=`RESTART`(클라가 `/start`로 새 세션을 만들어 처음부터 재시도), "아니오"=`TERMINATED`(진단 종료). **세션을 지우기 전에 그 시도는 `diagnoses`에 `DISCARDED`로 남긴다**(재시도·종료 양쪽 — 위 §`diagnoses` "지역 수요 보존"). 그 외 이탈은 `/start`가 세션을 그냥 덮어쓰며 기록하지 않는다. 진행 중 세션을 되돌리는 전이(`draft.region`만 비우고 처음으로 되감기)는 두지 않는다. 상세 [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md).
- **저장소 선택**: v1 `diagnoses`와 동일한 MongoDB 영속 패턴(도메인 리포지토리 포트로 요청 사이 상태 보존)이다. Redis(TTL) 세션 대안은 검토했으나 v1 초안 저장과의 일관성으로 MongoDB를 채택했다([ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md) Alternatives).

### 4-5. `booking`

> 스토어: **MySQL** — `bookings`는 이미 [`V9__bookings.sql`](../../src/main/resources/db/migration/V9__bookings.sql)·[`V11__add_bookings_landlord_id.sql`](../../src/main/resources/db/migration/V11__add_bookings_landlord_id.sql)로 **MySQL에 배포된 사실**이다. 아래 물리 타입·DDL은 논리 스키마가 아니라 실제 MySQL 스키마이며, 신규 DDL(`V14`·`V16`·`V17`·`V18`)도 공유 Flyway 시퀀스의 버전 번호를 예약하는 실행 가능한 마이그레이션이다. 다만 [ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏 배치 표엔 `booking` 매핑이 **아직 미반영**(추후 결정)이라 ADR 차원의 결정은 열려 있다(확인 필요). domain-model `Booking`(동일 세입자–동일 방 상품 활성 1건 — 중복 방지 UNIQUE).
>
> **채팅 연결 경계**: 예약 저장과 조회의 정본은 계속 `booking` 모듈이 맡는다. `V24`에서 chat의 저장 기반은 마련됐지만, 예약 저장 뒤 `BookingCreatedEvent`를 발행하고 같은 채팅방에 `BOOKING_CARD`를 기록하는 연결 로직은 다음 구현 단계에서 완성한다. 이 연결을 위해 `bookings`에 `chat_room_id`나 카드 JSON을 중복 저장하지 않는다. booking은 신청 사실을 이벤트로 알리고, chat이 자기 테이블에 방과 카드를 저장해야 두 모듈의 책임이 섞이지 않기 때문이다(§4-6).

`bookings`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT · API `bookingId` |
| `tenant_id` | BIGINT | NOT NULL · 예약자(세입자) → user(값 참조) |
| `listing_id` | VARCHAR(24) | NOT NULL · Mongo `listings._id` ObjectId hex 문자열 값 참조 |
| `room_offer_id` | VARCHAR(24) | NOT NULL · Listing 내부 방 상품 식별자 ObjectId hex 문자열 값 참조 |
| `landlord_id` | BIGINT | NOT NULL · 매물 소유자(임대인) → user(값 참조). **생성 시 `listing.landlordId` 스냅샷** — 임대인 받은 신청 조회(US-4-6) 소유권 스코프 키 |
| `move_in_date` | DATE | NOT NULL · 타겟 입주일(날짜만) |
| `contract_period` | INT | NOT NULL · 계약 개월수(양의 정수, 예: 1·3·6·12·24) |
| `status` | VARCHAR(16) (enum `BookingStatus`) | NOT NULL · 생성 시 `REQUESTED` 고정 · `REQUESTED`/`ACCEPTED`/`REJECTED`/`CANCELED` |
| `created_at` | DATETIME(6) | NOT NULL · 예약 일시(UTC) |
| `tenant_deleted_at` | DATETIME(6) | NULL · **NULL = 미삭제** · 세입자가 자기 목록에서 이 예약을 숨긴 시각(UTC) · 세입자 분기 조회 술어(`IS NULL`) · 임대인 분기는 보지 않는다(§2-2 참여자별 소프트삭제 예외) |
| `landlord_deleted_at` | DATETIME(6) | NULL · **NULL = 미삭제** · 임대인이 자기 목록에서 이 예약을 숨긴 시각(UTC) · 임대인 분기 조회 술어(`IS NULL`) · 세입자 분기는 보지 않는다 |

**인덱스**: PK `id` / INDEX `(tenant_id, created_at desc)`(세입자 분기 — 내 예약 목록 최신순) / INDEX `(landlord_id, created_at desc)`(임대인 분기 — 내 매물에 신청된 목록: `landlord_id = 요청자`를 최신순으로 조회) / **UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`**(동일 세입자–동일 방 상품 예약(신청)은 활성 1건만 허용 — 중복 방지, 재신청은 `409 BOOKING_ALREADY_EXISTS`).

> `landlord_id` 컬럼과 `(landlord_id, created_at)` 인덱스는 임대인 조회 분기(US-4-6)를 위한 것으로, `bookings`는 이미 배포된 `V9__bookings.sql`이라 **구현 시 별도 신규 마이그레이션**(예: `V11__add_bookings_landlord_id.sql` — 컬럼 추가 + 인덱스, 확장 변경)으로 넣는다([migration-policy](migration-policy.md)). 기존 예약 행이 있으면 `landlord_id`를 매물 소유자로 백필해야 하나(cross-store라 앱레벨), MVP는 booking 테이블이 신규라 사실상 비어 있다. 소유권은 `bookings` 행(`landlord_id`)에서 판정하며 `listing::api` 소유권 조회는 불요다(아래 교차 모듈 no-FK 참조).

- **중복 방지(동일 세입자–동일 방 상품 활성 1건)**: 같은 세입자(`tenant_id`)가 같은 방 상품(`room_offer_id`)에 내는 예약(신청)은 **1건만 허용**된다 — `bookings`에 **UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`**를 두고 재신청은 `409 BOOKING_ALREADY_EXISTS`로 거절한다. 이 에러코드는 `ErrorCode`·메시지 번들("이미 신청한 매물입니다")에 **이미 선언돼 있으나 여태 아무도 던지지 않던 코드**로, 이 결정으로 **실제 사용(live)** 으로 전환된다(신규 코드 아님). 상태 전이(수락/거절/취소)가 **미구현이라 모든 예약이 `REQUESTED`(=활성)** 이므로 "활성 1건"이 곧 "전체 1건"이라 **조건 없는 `UNIQUE (tenant_id, room_offer_id)`** 로 규칙이 정확히 표현된다. ⚠️ **caveat**: 향후 상태 전이가 도입되면 `REJECTED`·`CANCELED` 건이 그 방 재신청을 **영구 차단**하므로 **활성 상태만 대상으로 하는 부분 유니크**로 교체해야 한다 — MySQL은 부분 유니크 인덱스를 지원하지 않아(예: `active_room_offer_id` nullable 컬럼 + UNIQUE 트릭, 또는 앱 레벨 검사) 표현 방식은 그때 정한다.
- **교차 모듈 no-FK**: `tenant_id`·`landlord_id`·`listing_id`·`room_offer_id` 모두 값 참조(FK 없음). `listing_id`·`room_offer_id`는 Mongo ObjectId 문자열이라 자동증가 숫자가 아니다. 매물·방 상품 존재·공개 여부 확인과 **생성 시 소유자(`landlord_id`) 캡처**는 `listing :: api` 공개 쿼리로 한다(cross-store 조인 금지, [ADR-0005](../adr/0005-polyglot-persistence.md)). `move_in_date`는 과거 날짜만 거른다 — 매물이 입주 가능일을 보유하지 않아 대조할 값이 없다. 조회 엔드포인트의 **임대인 분기**(`userType=LANDLORD`)는 "내 매물에 신청됨"을 booking 행의 `landlord_id`로 직접 스코핑한다 — 목록 `landlord_id = 요청자`, 단건 상세는 `booking.landlord_id == 요청자` 행 단위 확인(미소유/부재는 `404 BOOKING_NOT_FOUND` 통일). `landlord_id`는 매물 상태와 무관하게 저장돼 **세입자에게 더는 보이지 않는 매물**(`REJECTED`·`UPDATE_PENDING`)의 신청도 그대로 포함되며, 생성 시점 스냅샷이라 소유권 이전 시 백필이 필요하다(이전은 MVP 범위 밖). 이미 성사된 예약의 **카드 표시**에 필요한 매물명·사진·금액은 `bookings`에 없어 매번 `listing :: api`로 물어보므로, 그 조회만은 **매물 상태·방 상태를 모두 무시하는 전용 쿼리**로 읽는다 — 공개 여부를 묻는 쿼리를 그대로 쓰면 비공개로 넘어간 매물의 예약 카드가 제목·금액이 빈 채로 렌더된다. 신규 예약 **생성**은 계속 공개 매물만 대상으로 한다. `chat_rooms`의 `landlord_id` 비정규화 선례와 일치한다.
- **가격·성명 비영속(조회 시점 조인)**: 보증금·월세·총 금액·매물 요약·예약자 성명은 예약에 **스냅샷 저장하지 않는다** — 단건 상세(US-4-2) 조회 시점에 애플리케이션 레벨로 조합한다. `listing :: api`로 `(listing_id, room_offer_id)`의 매물 요약·`pricing`(보증금·월세)을, `user :: api`(`getUserName`)로 예약자 성명을 가져온다. **총 금액 = 보증금 + 월세 × 계약 개월수(`contract_period` 정수)**(**관리비 제외**)이며 가격 변경 시 상세는 **현재가 기준**(스냅샷 아님)이다. → 모듈 의존 `booking → { listing :: api, user :: api }`.
- **참여자별 소프트삭제**(#169 · US-4-7 — 종전 "소프트삭제 불요: 취소는 `status=CANCELED`" 개정): 예약 내역 삭제는 **`status=CANCELED`로 표현할 수 없다.** ① `status`는 두 참여자가 **공유하는 1행의 공유 필드**라 한쪽이 지우면 상대 목록에서도 예약이 사라진다(데이터 손실). ② **취소 ≠ 숨김**이다 — 취소는 "예약을 없던 일로 한다"는 양쪽에 보이는 사실이고, 삭제는 "내 목록에서만 안 보이게 한다"는 참여자 개인의 표시 상태다. ③ 애초에 **상태 전이 자체가 현재 미구현**이라(생성 시 `REQUESTED` 고정, 수락/거절/취소는 여전히 범위 밖) 취소로 삭제를 대신할 수단이 없다. 그래서 참여자별 삭제 시각 2컬럼(`tenant_deleted_at`·`landlord_deleted_at`)을 두며, 이는 §2-2 공통 컬럼 표준("소프트삭제는 `community`만")의 **명시적 예외**다. 요청자 역할에 맞는 컬럼만 채우고(세입자→`tenant_deleted_at`, 임대인→`landlord_deleted_at`) 상대 컬럼은 건드리지 않는다. 삭제는 **멱등**이라 이미 채워져 있어도 재삭제가 오류가 아니다. 상태 전이가 도입되면 그때 `updated_at`을 추가한다(삭제 시각 자체가 삭제 이벤트의 타임스탬프라 `updated_at` 없이 성립한다).
- **삭제·차단 필터는 조회 술어**(응용 계층 후처리 아님): 목록·상세 조회는 `tenant_deleted_at IS NULL`(임대인 분기는 `landlord_deleted_at IS NULL`) **그리고** 상대 식별자가 차단 목록에 없을 것을 **리포지토리 술어로** 내린다 — 목록이 별도 count 쿼리로 `totalPages`/`hasNext`를 유도하므로 조회 후 걸러내면 페이지네이션이 어긋난다. 차단 식별자는 `user :: api`의 공개 쿼리(`findBlockedUserIds`)로 받아 **애플리케이션 레벨 조인**하며, `booking`이 `user_blocks`(§4-2)를 직접 조인하지 않는다([ADR-0005](../adr/0005-polyglot-persistence.md)·[ADR-0002](../adr/0002-inter-module-communication-via-events.md)). **차단 목록이 비면 어댑터 내부에서 sentinel `-1L` 한 건으로 정규화**해 술어에 넘긴다 — `NOT IN ()`은 문법 오류이고 `NOT IN (null)`은 UNKNOWN이라 **모든 행이 사라져** 차단이 0건인 사용자가 목록을 통째로 잃는다. `-1L`은 `users.id`가 `BIGINT AUTO_INCREMENT`(§2-2 공통 컬럼 표준 — 양의 정수만 발급)라 **실제 식별자와 충돌할 수 없어** 안전한 자리표시자다. 반대로 **변이·신고 경로(삭제·차단·신고)는 필터되지 않은 조회**를 쓴다 — 필터된 조회를 쓰면 두 번째 삭제 요청이 `404`가 되어 멱등이 깨지고, 이미 삭제한 예약을 신고할 수 없게 된다.
- **차단 저장은 `user` 소유**: `POST /api/v1/bookings/{bookingId}/block`은 예약 행에서 상대(`요청자 == tenant_id ? landlord_id : tenant_id`)를 도출하지만 차단 자체는 `bookings`에 아무 컬럼도 만들지 않고 `user :: api`를 통해 `user_blocks`(§4-2)에 쓴다 — 차단은 예약이 아니라 사람에 걸리기 때문이다(근거는 §4-2 `user_blocks`). 차단해도 `*_deleted_at`을 세팅하지 않는다(숨김은 차단 필터가 한다).
- **신규 신청 가드(양방향)**: 예약 생성은 어느 한쪽이라도 차단 관계면 거절한다(`403 FORBIDDEN`) — 단방향만 막으면 차단당한 쪽이 신청해 행은 생기는데 양쪽 목록 어디에도 안 보이는 **블랙홀 예약**이 남는다.
- **인덱스 불변(`*_deleted_at`은 인덱스에 얹지 않는다)**: 목록 조회에 `tenant_deleted_at IS NULL`(임대인 분기는 `landlord_deleted_at IS NULL`) 술어가 붙지만 위 두 인덱스 `(tenant_id, created_at desc)`·`(landlord_id, created_at desc)`는 **그대로 둔다**. 삭제 컬럼은 선택도가 낮고(대부분의 행이 NULL=미삭제) 갱신되는 nullable 필터라 인덱스에 얹으면 쓰기 비용만 늘고 이득이 없다 — 조회는 이미 선행 컬럼으로 **사용자 1명의 예약**까지 좁혀지고, 남은 소수 행에서 `IS NULL`을 거르는 비용은 MVP 규모에서 무시할 수 있다. `community`가 목록 인덱스에 `deleted`를 포함하는 것(§4-7)과 다른 판단인데, 그쪽은 게시판 전체(전 사용자)를 훑는 목록이라 선행 컬럼만으로 좁혀지지 않기 때문이다. 삭제 비율이 실제로 높아지면 그때 `(tenant_id, tenant_deleted_at, created_at desc)`를 재검토한다.
- **chat 연결은 저장 기반 다음 단계에서 구현**: 현재 `V24`에는 채팅방·참여자·메시지 테이블까지 반영돼 있다. 다음 단계에서 예약 트랜잭션이 `BookingCreatedEvent`를 발행하면 chat listener가 `(listing_id, tenant_id, landlord_id)`로 같은 방을 보장하고 `BOOKING_CARD`를 한 번만 저장한다. `bookings`에는 `chat_room_id`나 카드 payload를 추가하지 않는다. 방의 유일성과 카드 중복 방지는 각각 chat의 UNIQUE 제약이 맡는다.
- **`*_deleted_at` 추가 — 전진 마이그레이션 V14 예정**(최신이 `V13__users_lang.sql`이므로 다음 번호는 `V14__add_bookings_deleted_at.sql`, [migration-policy](./migration-policy.md)): `bookings`는 이미 배포된 `V9__bookings.sql`(+`V11`)이라 두 컬럼도 **별도 신규 마이그레이션**(컬럼 추가만, 확장 변경)으로 넣는다. **nullable**(NULL = 미삭제)이라 백필도, NOT NULL 3단계 전개도 필요 없다 — 기존 행은 그대로 "미삭제"가 되어 **배포 t=0 항등**이 성립한다. 인덱스는 추가하지 않는다(위 註).

    ```sql
    -- bookings에 참여자별 소프트삭제 시각을 추가한다. 요청자 역할에 해당하는 컬럼만 채워 "내 목록에서만 숨김"을 표현한다(상대에겐 그대로 보인다).
    -- bookings는 tenant_id·landlord_id가 공유하는 1행이라 단일 flag·공유 status(=CANCELED)로는 참여자별 숨김을 표현할 수 없다(한쪽이 지우면 상대 기록까지 사라진다).
    -- 취소와 숨김은 별개이며 상태 전이 자체가 현재 미구현(생성 시 REQUESTED 고정)이다.
    -- NULL = 미삭제라 기존 행 백필이 없다(확장 변경). 인덱스는 추가하지 않는다(선택도가 낮은 nullable 필터).
    -- #169 · US-4-7 · docs/database/database-design.md §2-2·§4-5 · docs/api/specs/04-booking-inquiry-chat.md.
    ALTER TABLE bookings ADD COLUMN tenant_deleted_at   DATETIME(6) NULL;
    ALTER TABLE bookings ADD COLUMN landlord_deleted_at DATETIME(6) NULL;
    ```

- **중복 방지 UNIQUE 추가 — 전진 마이그레이션 V18**(`V14`=`bookings` 컬럼 추가, `V15`=`user_blocks` 신설, `V16`=`booking_reports` 신설, `V17`=`booking_report_reasons` 신설이라 다음 번호는 `V18__add_bookings_unique_tenant_room_offer.sql`, [migration-policy](migration-policy.md)): `bookings`는 이미 배포된 `V9__bookings.sql`이라 유니크 제약도 **별도 신규 마이그레이션**으로 넣으며, 이는 `V9`의 "중복 방지 유니크 제약을 두지 않는다(다건 신청 허용)" 결정을 되돌린다. 제약 강화라 [migration-policy §3](migration-policy.md)상 **비호환 변경**이므로 기존 중복 행 정리가 선행돼야 하나, `bookings`는 신규 테이블이라 사실상 비어 있어 정리 대상이 없다. #169 삭제·차단·신고·사유 카탈로그용 `V14`~`V17`과는 별개의 제약 변경이라 그 번호와 겹치지 않게 `V18`로 둔다.

    ```sql
    -- bookings에 동일 세입자–동일 방 상품 예약 중복 방지 UNIQUE를 추가한다. 재신청은 409 BOOKING_ALREADY_EXISTS("이미 신청한 매물입니다").
    -- V9의 "중복 방지 유니크 제약을 두지 않는다(같은 방 상품에 다건 신청 허용)" 결정을 되돌린다.
    -- 상태 전이(수락/거절/취소)가 미구현이라 모든 예약이 REQUESTED(=활성)이므로 "활성 1건" == "전체 1건" → 조건 없는 UNIQUE로 규칙이 정확하다.
    --   ⚠️ 상태 전이 도입 시 REJECTED·CANCELED 건이 같은 방 재신청을 영구 차단하므로 활성 상태만 대상으로 하는 부분 유니크로 교체 필요 —
    --      MySQL은 부분 유니크 인덱스를 미지원(active_room_offer_id nullable 컬럼 + UNIQUE 트릭 또는 앱 레벨 검사)이라 표현 방식은 그때 정한다.
    -- migration-policy §3상 제약 강화 = 비호환이라 기존 중복 행 정리가 선행돼야 하나 bookings는 신규 테이블이라 사실상 비어 있다.
    -- 삭제·차단·신고·사유 카탈로그용 V14~V17과 별개의 제약 변경이라 V18로 둔다(그 번호는 건드리지 않는다).
    -- #169 · docs/database/database-design.md §2-4·§4-5 · docs/api/specs/04-booking-inquiry-chat.md.
    ALTER TABLE bookings ADD CONSTRAINT uq_bookings_tenant_room_offer UNIQUE (tenant_id, room_offer_id);
    ```

#### 예약 신고 접수 — `booking_reports`

예약 상대를 신고한 **접수 기록**을 담는다(#169 · US-4-9). domain-model `BookingReport`(신고 사유는 JVM enum이 아니라 카탈로그 `booking_report_reasons` 행의 `code` 문자열 값 참조 — 아래).

**범위는 접수(capture)까지다** — 운영자 검토·제재·처리 결과는 이 이슈 범위 밖이라 상태 전이가 없고, 따라서 **`status` 컬럼을 두지 않는다**(§4-9 `reports`의 `ReportStatus`와 대비). 접수된 행은 생성 후 바뀌지 않는 **불변 기록**이라 `updated_at`·소프트삭제도 없다. 운영 흐름이 정의되면 그때 상태 컬럼을 추가한다(확장 변경).

**왜 `report` 모듈이 아니라 `booking`인가**: 신고 접수는 "대상 예약이 실재하는가 / 요청자가 그 예약의 참여자인가"를 검증해야 하는데 그건 **예약만 아는 정보**다. 불변식(참여자만 신고 가능)이 예약 상태에 의존하므로 소유자도 예약이며, `booking`이 접수하면 모듈 내부 호출이라 **새 모듈 의존 엣지가 0개**다(`report`가 접수하면 `report → booking :: api` 포트를 새로 뚫어야 한다). §4-9 `report`는 게시글·댓글·채팅 메시지 신고를 담당하며(미구현) **예약과 신고 대상이 겹치지 않으므로** 두 곳이 공존해도 충돌이 없다.

`booking_reports` — 불변(immutable). §4-9 `reports`와 **별개 테이블**이다(테이블명·대상 모두 겹치지 않는다).

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT · API `reportId` |
| `reporter_id` | BIGINT | NOT NULL · INDEX(복합 `reporter_id, created_at`) · 신고자 → user `users.id`(값 참조). **응답 비노출** |
| `booking_id` | BIGINT | NOT NULL · INDEX(`booking_id`) · 신고 대상 예약 → `bookings.id`(같은 모듈이지만 FK 없음 — 아래 註) |
| `reason` | VARCHAR(32) | **NULL 허용** · 신고 사유 · 카탈로그(`booking_report_reasons`) `code` 값 참조(FK 없음) · 보내면 활성 code 검증 후 저장, 안 보내면 NULL |
| `detail` | TEXT | NULL, ≤500 · 신고 상세(자유 입력) · **원문 응답 비노출** |
| `created_at` | DATETIME(6) | NOT NULL · 접수 시각(UTC) |

**인덱스**: PK `id` / INDEX `idx_booking_reports_booking (booking_id)`(예약별 신고 조회 — 운영자 검토) / INDEX `idx_booking_reports_reporter_created (reporter_id, created_at)`(신고자별 이력 — 향후 레이트리밋 집계용). **유니크 제약은 없다 — 동일 신고자·동일 예약 다건 신고를 허용한다**(새 사유·지속되는 문제를 다시 신고할 수 있어야 하기 때문 · 도배 방지는 후속 레이트리밋으로 다룬다, 아래 註).

- **`status` 없음**: 위 참조 — 접수까지만이라 상태 전이가 없다. §4-9 `reports`가 `status`(default `RECEIVED`)를 갖는 것과 의도적으로 다르다.
- **`reason` nullable**: 사유는 **선택 입력**이라 NOT NULL이 아니다(§4-9 `reports.reason`이 NOT NULL인 것과 다름). 저장값은 카탈로그 `booking_report_reasons`(아래)의 **`code` 문자열**(UPPER_SNAKE)이며 네이티브 `ENUM`을 쓰지 않는다(§2-3). 접수 시 reason이 있으면 **활성 카탈로그 code인지 검증**하고(아니면 `400 INVALID_INPUT`), 없으면 검증 없이 `201`이다. §4-9의 `ReportReason`과 값이 같아도 **예약 맥락 전용 별개 카탈로그**다 — 카탈로그를 공유하면 `booking → report` 모듈 의존이 생긴다.
- **표시 라벨은 `booking_reports`가 아니라 카탈로그(`booking_report_reasons`)에 있다(`reason`은 언어 무관 코드)**: `booking_reports.reason`은 UPPER_SNAKE 코드만 저장하는 **언어 무관 불변 키**이고, `GET /api/v1/bookings/report-reasons`가 내려주는 **표시 라벨은 카탈로그 `booking_report_reasons`**(아래)에서 온다 — 서버가 `user :: api` 공개 쿼리 `getLanguage(userId)`로 얻은 사용자 표시 언어(`en`·`ko`·`ja`, 미설정·미지원은 `en` 폴백)로 해당 언어의 `(code, lang)` 행 `label`을 골라 계약 `{ code, label }`로 조립하고, 그 언어 행이 없으면 **`en` 폴백**한다. 리소스 번들(`messages`)이 아니라 **MySQL 카탈로그 테이블**에 두는 이유는 신고 사유를 **코드 배포·스키마 변경 없이 행 INSERT만으로 동적 관리**하기 위해서다(사유 추가도, 언어 추가도 `(code, lang)` 행 추가). `messages` 번들은 다시 **에러 메시지 전용**이다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)). 진단(§4-4 `diagnosisQuestions`)·생활 팁(§4-10)의 **MongoDB 인라인 언어-키 맵**을 쓰지 않는 이유는 `booking`이 MySQL 모듈이라 사유 라벨만을 위해 **cross-store 컬렉션을 만들 이유가 없기** 때문이다(§2-4 교차 스토어 금지). `code`는 언어 무관 불변이고 `label`만 언어별이라는 원칙은 [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) Decision 6과 같다.
- **FK 없음(`booking_id`)**: `booking_id`는 같은 `booking` 모듈의 `bookings.id`라 §2-4상 FK가 **허용되지만** 걸지 않는다 — 레포의 마이그레이션 전체에 `FOREIGN KEY`/`REFERENCES`가 **0건**이라 전례를 따른다(대상 예약 존재·참여자 검증은 접수 시 응용 계층이 한다). `reporter_id`는 `user` 모듈 참조라 **FK 금지**(교차 모듈, §2-4).
- **동일 예약 다건 신고 허용(유니크 제약 없음)**: 같은 신고자가 같은 예약을 **여러 번 신고할 수 있다** — 새 사유·지속되는 문제를 다시 접수할 수 있어야 하기 때문이다. 그래서 `(reporter_id, booking_id)` 유니크로 중복을 막지 않으며 접수는 더 이상 `409`를 반환하지 않는다(옛 `BOOKING_REPORT_ALREADY_EXISTS` 코드·예외·i18n 키는 제거됨). **신고 도배 방지는 레이트리밋(`429`)으로 다루며 이는 후속·이연 항목이다(현재 미구현)** — 향후 신고자별 접수 빈도 집계를 위해 `idx_booking_reports_reporter_created (reporter_id, created_at)` 보조 인덱스를 미리 둔다. `idx_booking_reports_booking (booking_id)`는 "이 예약에 신고가 몇 건인가"를 묻는 운영자 조회용 보조 인덱스다.
- **삭제·차단과 무관하게 접수**: 신고 대상 판정은 `*_deleted_at`·차단 상태를 보지 않는다 — 이미 삭제·차단한 예약도 신고할 수 있어야 **증거가 보존**된다. 그래서 접수는 필터되지 않은 조회를 쓰며, 같은 행이 상세 조회에선 `404`인데 신고는 `201`이 되는 **의도된 비대칭**이 생긴다.
- **자기 신고 차단 컬럼 없음**: `tenant_id != landlord_id`가 구조적으로 보장돼(예약 생성은 세입자만 가능하고 `user_type`은 온보딩 확정 후 불변) 자기 신고가 성립하지 않는다 — 별도 제약·에러코드를 두지 않는다(§4-9 `reports`가 자기 신고를 `422`로 막는 것과 대비).
- **프라이버시**: `reporter_id`·`detail` 원문은 저장하되 응답에 노출하지 않는다([error-response-guide §6](../api/error-response-guide.md)) — §4-9 `reports`와 동일 원칙.
- **신설 — 전진 마이그레이션 V16 예정**(`V14`=`bookings` 컬럼 추가, `V15`=`user_blocks` 신설이므로 다음 번호는 `V16__create_booking_reports.sql`, [migration-policy](./migration-policy.md)): 신규 테이블이라 백필 없는 순수 확장 변경이며, V14·V15와 함께 한 릴리스에 나갈 수 있다.

    ```sql
    -- 예약 신고 접수(booking_reports) 테이블. 범위는 접수(capture)까지라 status 컬럼이 없다(운영자 검토·제재는 범위 밖 · 상태 전이 없는 불변 기록).
    -- 신고 접수는 대상 예약 존재·참여자 권한 검증이 필요해 예약을 아는 booking 모듈이 소유한다(report 모듈은 게시글·댓글·메시지 담당 · 대상이 겹치지 않는다).
    -- reason은 선택 입력이라 NULL 허용(보내면 저장). 값은 UPPER_SNAKE 문자열(네이티브 ENUM 미사용).
    -- reporter_id는 user 모듈 참조라 FK 금지(교차 모듈). booking_id는 같은 모듈이지만 레포 전체 FK 0건 전례를 따라 걸지 않는다.
    -- #169 · US-4-9 · docs/database/database-design.md §4-5 · docs/api/specs/04-booking-inquiry-chat.md.
    CREATE TABLE booking_reports (
        id          BIGINT      NOT NULL AUTO_INCREMENT,
        reporter_id BIGINT      NOT NULL,
        booking_id  BIGINT      NOT NULL,
        reason      VARCHAR(32) NULL,
        detail      TEXT        NULL,
        created_at  DATETIME(6) NOT NULL,
        PRIMARY KEY (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

    -- 유니크 제약을 두지 않는다 — 동일 신고자·동일 예약 다건 신고 허용(새 사유·지속 문제 재신고). 도배 방지는 후속 레이트리밋(429).
    -- 예약별 신고 조회(운영자 검토)용 보조 인덱스.
    CREATE INDEX idx_booking_reports_booking ON booking_reports (booking_id);
    -- 신고자별 접수 이력 조회 — 향후 레이트리밋 집계용 보조 인덱스.
    CREATE INDEX idx_booking_reports_reporter_created ON booking_reports (reporter_id, created_at);
    ```

#### 예약 신고 사유 카탈로그 — `booking_report_reasons`

신고 사유의 **코드·언어별 표시 라벨**을 담는 reference 카탈로그다(#169 · US-4-9). `booking` 모듈이 소유하며, `GET /api/v1/bookings/report-reasons`가 사용자 표시 언어의 라벨을 골라 `{ code, label }`로 내려주는 단일 출처다. **enum·리소스 번들 대신 DB 카탈로그에 두는 이유**는 신고 사유를 **코드 배포·스키마 변경 없이 행 INSERT만으로 동적 관리**하기 위해서다 — **사유 추가도, 언어 추가도 모두 `(code, lang)` 행 하나를 INSERT**한다(사유는 JVM enum이 아니라 카탈로그 행). MongoDB 인라인 언어-키 맵(§4-4·§4-10)을 쓰지 않는 이유는 `booking`이 MySQL 모듈이라 cross-store 컬렉션을 만들 이유가 없기 때문이다(§2-4).

`booking_report_reasons` — reference(운영 중 행 추가·비활성 가능).

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `code` | VARCHAR(32) | NOT NULL · UNIQUE(복합) · 언어 무관 불변 키(UPPER_SNAKE) · `booking_reports.reason`이 값 참조 |
| `lang` | VARCHAR(8) | NOT NULL · UNIQUE(복합) · 표시 언어(ISO 639-1 소문자 `en`·`ko`·`ja`) |
| `label` | VARCHAR(100) | NOT NULL · 해당 언어의 표시 라벨 |
| `display_order` | INT | NOT NULL · 목록 정렬 순서 |
| `active` | BIT | NOT NULL DEFAULT `b'1'` · 비활성 사유는 목록·검증에서 제외(Hibernate `boolean`↔`BIT` 매핑) |

**인덱스**: PK `id` / **UNIQUE `(code, lang)`**(한 사유·한 언어당 라벨 1개 — 중복 시드·중복 언어 방지). `(code, lang)` 한 쌍이 곧 한 라벨이다.

- **`(code, lang)` = 한 라벨**: 사유 6종 × 언어 3종(`en`·`ko`·`ja`)이 각각 한 행이다. 사유를 늘리려면 새 `code`의 언어별 행들을, 언어를 늘리려면 기존 code들의 새 `lang` 행들을 **INSERT**한다(코드·스키마 불변).
- **목록·폴백**: `GET /api/v1/bookings/report-reasons`는 `user :: api` `getLanguage(userId)`로 얻은 표시 언어의 `active=TRUE` 행을 `display_order`로 정렬해 `{ code, label }`로 내려주고, 그 언어 행이 없으면 **`en` 행으로 폴백**한다.
- **`booking_reports.reason` 값 참조(FK 없음)**: 접수된 신고의 `reason`은 이 카탈로그의 `code` 문자열을 **값으로만** 담는다(§4-2·§4-5 전례대로 FK 미설정 — 레포 전체 FK 0건). 접수 시 reason이 있으면 **활성 code인지 검증**하고(아니면 `400 INVALID_INPUT`), 없으면 검증 없이 `201`이다.
- **표준 컬럼 없음**: 코드·라벨을 담는 순수 reference라 `created_at`/`updated_at`·소프트삭제를 두지 않는다(비활성은 `active`로 표현 — `nickname_adjectives`·`countries`와 같은 취급).
- **신설 — 전진 마이그레이션 V17 예정**(`V14`=`bookings` 컬럼 추가, `V15`=`user_blocks` 신설, `V16`=`booking_reports` 신설이므로 다음 번호는 `V17__create_booking_report_reasons.sql`, [migration-policy](./migration-policy.md)): 테이블 생성 + 6종 × 3언어(`en`·`ko`·`ja`) 시드로, 백필 없는 순수 확장 변경이다(중복 방지 UNIQUE는 별개 제약 변경이라 그 뒤 `V18` — 위).

    ```sql
    -- 예약 신고 사유 카탈로그(booking_report_reasons) 테이블 + 6종 × 3언어(en/ko/ja) 시드.
    -- enum·리소스 번들 대신 DB 카탈로그에 둔다 — 사유 추가도 언어 추가도 코드 배포 없이 (code, lang) 행 INSERT로 한다.
    -- booking_reports.reason은 이 카탈로그의 code 문자열을 값 참조(FK 없음 · 레포 전체 FK 0건 전례).
    -- 목록 API는 사용자 표시 언어(user::api getLanguage) 라벨을 고르고 없으면 en 폴백. 접수 시 reason은 활성 code인지 검증(아니면 400).
    -- #169 · US-4-9 · docs/database/database-design.md §2-4·§4-5 · docs/api/specs/04-booking-inquiry-chat.md.
    CREATE TABLE booking_report_reasons (
        id            BIGINT       NOT NULL AUTO_INCREMENT,
        code          VARCHAR(32)  NOT NULL,
        lang          VARCHAR(8)   NOT NULL,
        label         VARCHAR(100) NOT NULL,
        display_order INT          NOT NULL,
        active        BIT(1)       NOT NULL DEFAULT b'1',
        PRIMARY KEY (id),
        CONSTRAINT uq_booking_report_reasons_code_lang UNIQUE (code, lang)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    -- 이어서 6종 × 3언어(en/ko/ja) = 18행을 INSERT로 시드한다(사유·언어 추가는 이후 행 INSERT).
    ```

### 4-6. `chat`

> 스토어: **MySQL** — [`V24__create_chat_core_tables.sql`](../../src/main/resources/db/migration/V24__create_chat_core_tables.sql)로 채팅방·참여자별 표시 상태·공유 메시지의 물리 스키마를 확정했다. domain-model `ChatRoom`·`ChatRoomMember`·`Message`, 값 객체 `ListingSnapshot`·`BookingCardPayload`와 일치한다.
>
> `V24`는 채팅 저장 기반을 만들었고 문의·신청·STOMP 흐름은 그 위에 구현한다. 신고 저장은 별도 `V26`의 `chat_reports`·`chat_report_evidence`가 담당하며, 자동 번역 테이블은 해당 기능 단계의 후속 전진 migration으로 추가한다.

`chat_rooms`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT · API의 `chatRoomId` |
| `category` | VARCHAR(16) | NOT NULL, default `LANDLORD` · CHECK `category = 'LANDLORD'` |
| `listing_id` | VARCHAR(24) | NOT NULL · Mongo `listings._id` ObjectId 문자열 값 참조 |
| `tenant_id` | BIGINT | NOT NULL · 세입자 `users.id` 값 참조 |
| `landlord_id` | BIGINT | NOT NULL · 임대인 `users.id` 값 참조 |
| `listing_snapshot` | JSON | NOT NULL · 생성 당시 `title`·`address` |
| `last_message_id` | BIGINT | NULL · 현재 마지막 `chat_messages.id`를 가리키는 조회용 포인터 |
| `last_message_at` | DATETIME(6) | NULL · 마지막 메시지의 서버 저장 시각 |
| `created_at` | DATETIME(6) | NOT NULL · 생성 시각(UTC) |
| `updated_at` | DATETIME(6) | NOT NULL · 마지막 메시지 포인터 등 방 상태 변경 시각(UTC) |

**제약·인덱스**:

- UNIQUE `uq_chat_rooms_listing_participants (listing_id, tenant_id, landlord_id)`: 문의하기와 신청하기 중 무엇이 먼저 실행돼도 같은 참여자·매물 조합은 하나의 `roomId`로 수렴한다.
- CHECK `ck_chat_rooms_distinct_participants (tenant_id <> landlord_id)`: 자기 자신과의 1:1 방을 DB에서도 막는다.
- CHECK `ck_chat_rooms_category (category = 'LANDLORD')`: 현재 외부 기능은 매물 임대인-임차인 채팅만 지원한다. 다른 카테고리는 컬럼의 임의 값으로 먼저 열지 않고, 요구사항이 생길 때 migration으로 CHECK를 확장한다.
- CHECK `ck_chat_rooms_listing_snapshot_object (JSON_TYPE(listing_snapshot) = 'OBJECT')`: MySQL JSON은 배열이나 스칼라도 저장할 수 있으므로 채팅방 헤더 스냅샷이 객체라는 최소 형태를 고정한다.
- CHECK `ck_chat_rooms_last_message_pair`: `last_message_id`와 `last_message_at`은 둘 다 null이거나 둘 다 값이 있어야 한다. 두 값이 어긋나면 목록 미리보기와 정렬 기준이 서로 다른 메시지를 가리킬 수 있기 때문이다.
- INDEX `idx_chat_rooms_tenant_last_message (tenant_id, last_message_at DESC, id DESC)` / `idx_chat_rooms_landlord_last_message (landlord_id, last_message_at DESC, id DESC)`: 먼저 각 사용자의 방으로 범위를 줄인다. 빈 방을 `created_at` 기준으로 섞는 최종 `COALESCE(last_message_at, created_at)` 정렬은 초기 규모에서 좁혀진 결과의 filesort를 허용하고, 실제 방 수가 커지면 `activity_at` 또는 함수 인덱스를 측정 후 추가한다.

`last_message_id`는 **메시지를 한 건만 저장한다는 뜻이 아니다**. 모든 메시지는 아래 `chat_messages`에 계속 쌓이고, 이 컬럼은 목록에서 가장 최근 메시지를 빠르게 찾기 위한 포인터만 보관한다.

`chat_room_members`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `chat_room_id` | BIGINT | NOT NULL · `chat_rooms.id` 값 참조 |
| `user_id` | BIGINT | NOT NULL · 이 표시 상태를 소유한 `users.id` |
| `counterpart_id` | BIGINT | NOT NULL · 1:1 상대 `users.id` |
| `member_role` | VARCHAR(16) | NOT NULL · `TENANT` 또는 `LANDLORD` |
| `room_hidden_at` | DATETIME(6) | NULL · null이면 목록에 보이고, 값이 있으면 이 사용자에게만 숨김 |
| `history_hidden_through_message_id` | BIGINT | NOT NULL, default `0` · 이 사용자에게 다시 보여 주지 않을 마지막 과거 `messageId`; `0`이면 숨긴 이력 없음 |
| `delete_requested_at` | DATETIME(6) | NULL · 가장 최근 실제 삭제 요청 시각 |
| `created_at` | DATETIME(6) | NOT NULL · 생성 시각(UTC) |
| `updated_at` | DATETIME(6) | NOT NULL · 숨김·재표시 상태 변경 시각(UTC) |

**제약·인덱스**:

- UNIQUE `uq_chat_room_members_room_user (chat_room_id, user_id)`: 방 하나에서 사용자별 표시 상태가 두 행으로 갈라지지 않게 한다.
- CHECK `ck_chat_room_members_distinct_counterpart (user_id <> counterpart_id)` / `ck_chat_room_members_role (member_role IN ('TENANT', 'LANDLORD'))`: 상대와 역할 값의 기본 불변식을 DB에서도 확인한다.
- CHECK `ck_chat_room_members_history_boundary (history_hidden_through_message_id >= 0)`: 실제 메시지 ID 또는 `0` sentinel만 허용한다. null 대신 `0`을 쓰면 `message_id > history_hidden_through_message_id` 조회가 SQL의 null 비교 때문에 전부 누락되지 않는다.
- INDEX `idx_chat_room_members_user_visibility (user_id, room_hidden_at, chat_room_id)`: 로그인 사용자의 현재 보이는 채팅방부터 좁혀 조회한다.

채팅방과 메시지는 두 사용자가 공유하지만 삭제는 사용자 화면별 동작이다. 그래서 방을 만들 때 임차인과 임대인의 member 행을 각각 만들고, 한 사용자가 삭제하면 그 사람의 `room_hidden_at`과 과거 숨김 경계만 바꾼다. 상대방 행은 그대로이므로 상대방 화면에서 대화가 사라지지 않는다. 새 메시지로 방을 다시 표시할 때도 `history_hidden_through_message_id`를 낮추지 않아 사용자가 이미 삭제한 과거 이력을 복원하지 않는다.

읽음 기능은 이번 범위가 아니므로 `last_read_message_id`와 `unread_count`는 저장하지 않는다.

`chat_messages`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT · 서버 `messageId` |
| `chat_room_id` | BIGINT | NOT NULL · 메시지가 속한 `chat_rooms.id` 값 참조 |
| `sender_id` | BIGINT | NULL · `TEXT` 발신자의 `users.id`; 서버 카드면 null |
| `type` | VARCHAR(32) | NOT NULL · `TEXT` 또는 `BOOKING_CARD` |
| `content` | TEXT | NULL · 사용자가 보낸 변경 불가 원문; 카드면 null |
| `payload` | JSON | NULL · `BookingCardPayload` 구조화 스냅샷; TEXT면 null |
| `booking_id` | BIGINT | NULL · 카드의 출처인 `bookings.id`; TEXT면 null |
| `client_message_id` | BINARY(16) | NULL · TEXT 전송 전에 앱이 만든 멱등 UUID; 카드면 null |
| `sent_at` | DATETIME(6) | NOT NULL · 클라이언트 시각이 아닌 서버 저장 시각(UTC) |

`chat_messages`는 상대방에게 보내는 동안만 쓰는 임시 큐가 아니라, **모든 채팅방에서 저장이 완료된 메시지의 공유 정본**이다. 임차인용·임대인용 사본을 따로 만들지 않고 메시지 한 건을 한 행으로 저장한다. `chat_room_id`가 소속 방을, `sender_id`가 TEXT 발신자를 알려 주며, 수신자는 그 방의 `chat_room_members`에서 서버가 결정하므로 `receiver_id` 컬럼이 필요 없다.

**타입별 CHECK `ck_chat_messages_type_fields`**:

| 타입 | 필수 값 | 반드시 null인 값 |
| --- | --- | --- |
| `TEXT` | `sender_id`, 1~3,000자의 `content`, `client_message_id` | `booking_id`, `payload` |
| `BOOKING_CARD` | `booking_id`, `payload` | `sender_id`, `content`, `client_message_id` |

TEXT와 카드를 한 테이블에 저장해야 신청 카드와 일반 메시지가 하나의 `messageId` 시간축에서 자연스럽게 정렬된다. 대신 잘못된 nullable 조합이 들어오지 않도록 CHECK를 둔다. 현재 메시지 타입은 이 둘뿐이며 `LISTING_CARD`나 일반 `SYSTEM` 타입은 `V24` 스키마에 없다.

**유일성·조회 인덱스**:

- UNIQUE `uq_chat_messages_client_message (chat_room_id, sender_id, client_message_id)`: 앱이 응답을 못 받아 같은 TEXT를 다시 보내도 동일 UUID는 한 번만 저장한다.
- UNIQUE `uq_chat_messages_booking (chat_room_id, booking_id)`: 동일 신청 이벤트가 재처리돼도 같은 방에 신청 카드가 두 장 생기지 않는다.
- INDEX `idx_chat_messages_room_id_desc (chat_room_id, id DESC)`: 과거 페이지 조회와 재연결 후 누락 메시지 보충을 같은 `messageId` 범위 조회로 처리한다.

**FK를 만들지 않는 이유**: 이 저장소의 기존 일관된 no-FK 정책에 맞춰 `listing_id`·사용자 ID·`booking_id`뿐 아니라 같은 chat 모듈의 `chat_room_id`·`last_message_id`도 값으로 참조한다. 존재 여부·참여 권한과 “메시지 저장 + 마지막 메시지 포인터 갱신”의 정합성은 응용 서비스의 한 트랜잭션과 DB UNIQUE/CHECK로 보장한다. 이렇게 하면 모듈 간 DB 결합과 삭제 순서 의존을 만들지 않으면서도 저장 규칙은 DB 제약으로 최종 방어할 수 있다.

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

> 스토어: **MongoDB** (문서형 카탈로그·인라인 언어-키 맵·무상태 채점). **1차 MVP 이후**. domain-model `Quiz`(+VO `QuizChoice`).

#### 학습 퀴즈 카탈로그 — `quizzes`

외국인의 한국 생활 학습 퀴즈의 문항·선택지·정답·해설(오답 사유)을 영속하는 카탈로그 컬렉션이다(US-6-1·US-6-2·US-6-3). **호출에 인증·역할 제한이 없다**(아래 "대상자 게이트" 註 — #181). 매 요청은 활성 문항 중 **무작위 4지선다** 1개를 서빙하고, 사용자가 보기를 클릭하면 서버가 채점한다 — **무제한 반복·무상태 채점**(제출·포인트 비영속, 멱등·재플레이). 표시 문자열(번역)은 별도 컬렉션 없이 도큐먼트 내부 `question`·`choices[].text`·`explanation`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드한다. 시드/마이그레이션으로 적재, 운영 중 `active`로 가변.

`quizzes`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | Long | PK · 명시 시드값(예: `4001`~) · `quizId`로 노출·채점 경로에 사용 |
| `question` | object | NOT NULL · 문항 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 `getLanguage` 표시 언어 키 선택, 없으면 `en` 폴백 |
| `choices` | object[] | NOT NULL · 선택지 배열 · 각 항목은 `key`(`A`\|`B`\|`C`\|`D`, 언어 불변·채점 키)와 `text`(표시 문자열의 **인라인 언어-키 맵**)를 보유 |
| `correctChoice` | string | NOT NULL · enum `ChoiceKey`(`A`~`D`) · 서버 채점용 · `GET /quizzes/random` 비노출 |
| `explanation` | object | NOT NULL · 오답 사유·해설 표시 문자열의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 채점 응답에 노출(정답·오답 모두) |
| `active` | bool | NOT NULL DEFAULT true · 비활성 문항은 랜덤 풀에서 제외 |

**인덱스**: PK `_id` / INDEX `(active)`(활성 문항 랜덤 풀 선택).

- **무상태 채점**: 제출·포인트를 영속하지 않는다(멱등·재플레이 가능). `GET /api/v1/quizzes/random`이 활성 문서 1개를 무작위로 골라 `{ quizId, question, choices:[{key,text}] }`(번역)로 내려주고(`correctChoice`·`explanation` 비노출), `POST /api/v1/quizzes/{quizId}/answer`가 `selectedChoice`를 저장된 `correctChoice`와 대조해 채점한다 — 정답 `{ correct:true, explanation }`, 오답 `{ correct:false, correctChoice, explanation }`(해설 번역). 제출 기록·포인트·`201 Created`/`Location` 없음.
- **교차 모듈 no-FK**: 표시 언어는 **user 모듈 공개 query(`getLanguage(userId)`)로 취득**하고 `user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 폴백한다(값 참조, [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) → 모듈 의존 `gamification`→`user`.
- **대상자 게이트 — 없다(#181)**: `SecurityConfig`의 `/api/v1/quizzes/**`는 `permitAll`이라 비로그인 게스트도 호출하고, 응용 계층(`GamificationService`)의 `userType=TENANT` 검사(`assertTenant` → `TenantOnlyException`)도 제거한다 — 게스트에게 열린 콘텐츠를 로그인한 임대인에게만 `403`으로 막는 것은 실효가 없기 때문이다(임대인이 로그아웃하면 그대로 볼 수 있다). 따라서 `403 FORBIDDEN`(세입자 아님)·`403 AUTH_ONBOARDING_REQUIRED`(비-ACTIVE)·`401 UNAUTHENTICATED`는 이 경로에서 발생하지 않는다(만료 토큰만 `401 TOKEN_EXPIRED` 유지). 표시 언어만 호출자별로 갈린다 — 게스트는 `getLanguage` 미호출로 `en` 고정, 회원은 `users.lang`(임대인은 온보딩 때 서버가 `ko`를 고정 부여하므로 한국어). 스펙 [06-gamification](../api/specs/06-gamification.md) "호출자별 결과" 표가 정본. 이 컬렉션 스키마는 게이트 변경과 무관하게 그대로다(신원 필드 없음 — 무상태 채점).
- **`QUIZ_NOT_FOUND`(404)**: `quizId`가 없거나(잘못된 식별자) 활성 풀이 공백일 때. (**확인 필요**) `random`=활성 풀 무작위 **SELECTION**(동적 생성 아님).

### 4-9. `report`

> 스토어: **MySQL** — [`V26__create_chat_reports.sql`](../../src/main/resources/db/migration/V26__create_chat_reports.sql)로 1:1 채팅방 신고 접수와 최초 원문 증거를 물리화했다.

`chat_reports`는 신고 번호, 채팅방, JWT 신고자, 방에서 찾은 상대방, 고정 사유, `RECEIVED` 상태, 증거 상한, 접수·1년 보관 만료 시각을 가진다. **UNIQUE `(reporter_id, chat_room_id)`**로 같은 사용자의 같은 방 신고는 한 건만 저장한다.

`chat_report_evidence`는 신고별 JSON 증거 한 건을 가진다. **UNIQUE `(report_id)`**이며, schema version, 증거 상한, 최근 보이는 TEXT 원문 최대 20개 snapshot, SHA-256 검증값과 캡처 시각을 저장한다. `BOOKING_CARD`와 자동 번역문은 포함하지 않는다.

- **표시 문구는 프런트 소유**: 사유 label은 `ko/en` UI에서 code로 매핑한다. DB catalog와 사유 목록 API는 없다.
- **클라이언트 값 최소화**: 요청은 `roomId`와 `reason`만 받으며 신고자·상대방·증거는 서버가 결정한다.
- **no-FK**: 기존 저장소 관례대로 숫자 값을 참조하고 신고·증거를 한 트랜잭션으로 저장한다.
- **프라이버시**: 사용자 응답에는 증거 원문, 상대 ID, 보관 만료 시각을 노출하지 않는다.
- **후속 운영**: 관리자 목록·처리와 실제 만료 정리 작업은 [후속 설계](../architecture/chat/future/README.md)에서 구현한다.

### 4-10. `lifetip`

> 스토어: **MongoDB** (문서형·가변 스키마·언어-키 맵 임베드. [ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏 · [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md) 진단 카탈로그 저장 방식과 정합). **1차 MVP 이후**(홈 부가 기능·읽기 전용). domain-model `LifeTipTopic`·`LifeTip`(US-8-1·US-8-2·US-8-3).
>
> 외국인이 한국 생활 정보를 **주제(topic)** 별로 조회하는 읽기 전용 큐레이션이다. **호출에 인증·역할 제한이 없다** — `/api/v1/life-tips/**`는 `permitAll`이고 응용 계층 세입자 게이트(`LifeTipService.assertTenant` → `TenantOnlyException`)도 제거하므로(#181) 게스트·세입자·임대인·온보딩 미완료가 모두 200이다(퀴즈 §4-8과 동일 근거). 주제·팁은 운영이 시드로 적재하는 큐레이션 콘텐츠라 사용자 작성·수정·좋아요·신고가 없다(발행/구독 도메인 이벤트 없음). 표시 문자열(주제명·주제 짧은/긴 설명·팁 제목·내용)은 별도 메시지 컬렉션 없이 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드하며, 진단 i18n(§4-4 `diagnosisQuestions`, [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md)·US-2-6)과 **완전히 동일한 전략**을 재사용한다 — 서버가 `user` 공개 query `getLanguage(userId)`로 취득한 언어 키(없으면 `en` 폴백, 에러 아님)로 문자열을 골라 조립하고, 식별자(`code`/`id`)·이미지 URL(주제 `imageUrl`·`backgroundImageUrl`, 팁 `imageUrl`)은 언어 무관 불변이다. `Accept-Language`·토큰 클레임은 쓰지 않는다. 모듈 의존 `lifetip → {common, user}`(진단과 동일 근거 — [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5). 주제 : 팁 = **1 : N**.

#### 주제 카탈로그 — `lifeTipTopics`

생활 팁을 묶는 **주제(topic)** 카탈로그다(US-8-1). 각 주제는 언어 무관 식별 `code`(UPPER_SNAKE, `_id`)와 노출 순서(`order`)를 가지며, 표시명(`name`)·짧은 설명(`shortDescription`)·긴 설명(`longDescription`)은 **인라인 언어-키 맵**으로 임베드하고, 홈 카드 이미지(`imageUrl`)·상세 상단 배경 이미지(`backgroundImageUrl`)는 언어 무관 절대 CDN URL로 둔다. 홈 화면 주제 카드는 `imageUrl`+`shortDescription`으로, 주제 상세 상단은 `backgroundImageUrl`+`longDescription`으로 그린다 — 앱이 목록에서 받은 주제 객체를 상세 화면까지 들고 가므로 이 6필드(`code`·`name`·`shortDescription`·`longDescription`·`imageUrl`·`backgroundImageUrl`)를 **한 응답에 함께 싣는다**(주제는 5건 고정·소규모라 과다 전송 부담이 없다). `GET /api/v1/life-tips/topics`가 (이 카탈로그 + 사용자 언어 키)으로 노출 순서대로 전체 배열을 조립해 내려준다(고정·소규모라 비페이지 — api-design-guide §4 목록 규약 미적용, US-7-3과 동일 성격). `_id`(주제 코드)는 US-8-2에서 특정 주제의 팁을 지정하는 path 키(`{topicCode}`)로 쓰인다. 시드/마이그레이션으로 적재, 운영 중 갱신 가능.

`lifeTipTopics`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | string | PK · 주제 코드(UPPER_SNAKE, 언어 무관 불변 식별자, 예 `MOVING_IN`·`ADMINISTRATION`·`TRANSPORT`·`FINANCE`·`HOUSING`) · `GET /topics/{topicCode}/tips` path 키 |
| `name` | object | NOT NULL · 주제 표시명(번역 대상)의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `shortDescription` | object | NOT NULL · 주제 짧은 설명(홈 카드용, 번역 대상)의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `longDescription` | object | NOT NULL · 주제 긴 설명(상세 상단용, 번역 대상)의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `imageUrl` | string | NOT NULL · 홈 카드 이미지 URL(`LifeTipTopic.imageUrl`) · 언어 무관 불변 절대 CDN URL · 팁 사진 `LifeTip.imageUrl`(nullable)과는 다른 리소스 |
| `backgroundImageUrl` | string | NOT NULL · 주제 상세 상단 배경 이미지 URL · 언어 무관 불변 절대 CDN URL |
| `order` | int | NOT NULL · 노출 순서(오름차순) |

**인덱스**: PK `_id` / INDEX `{ order: 1 }`(노출 순서 정렬 조회).

- **번역**: 표시 문자열은 도큐먼트 내부 `name`·`shortDescription`·`longDescription`의 **인라인 언어-키 맵**(`{ lang → message }`)에 임베드한다 — 서버가 `getLanguage(userId)`로 취득한 표시 언어 키를 골라 조립하고, 해당 키가 없으면 `en` 폴백(에러 아님, `Accept-Language` 비의존). 표시 언어는 `user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`으로 폴백하며 교차 모듈 **값 참조**(no-FK)다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)). `_id`(주제 코드)와 이미지 URL(`imageUrl`·`backgroundImageUrl`, 절대 CDN URL)은 언어와 무관하게 동일하다 — 언어 키 선택을 거치지 않는다.
- **비페이지**: 주제 수는 고정·소규모라 페이지네이션 없이 `order` 오름차순 전체 배열을 한 번에 반환한다(페이지 객체 없음).
- **필수(NOT NULL)**: 주제의 `shortDescription`·`longDescription`·`imageUrl`·`backgroundImageUrl`은 모두 필수다 — 홈 카드와 상세 상단이 항상 이미지·설명을 그리므로 "이미지 없는 주제" 경계 케이스를 두지 않는다(사진이 없을 수 있어 nullable인 팁 사진 `LifeTip.imageUrl`과 대비된다).
- **인덱스 불변**: 신설 4필드는 조회 필터·정렬 키가 아니라 인덱스를 추가하지 않는다 — `{ order: 1 }`(노출 순서 정렬) 인덱스만 그대로 유지한다.
- **적재**: 신설 4필드는 정본 시드가 **예시 기본값**(모든 주제 공통)으로 들고 있고, 실제 이미지·설명은 **운영이 DB에서 갱신**한다(예시 기본값은 계약 형태를 만족시키는 자리표시자). 시드 갱신이 배포와 분리돼 있어 값을 고치는 데 재빌드가 필요 없다 — 절차는 진단 카탈로그(§4-4)와 동일하다([ADR-0032](../adr/0032-mongodb-migration-runner.md) §4 · [migration-policy §8-1](./migration-policy.md#8-1-시드-주입-절차)).

**예시 도큐먼트** (`lifeTipTopics`)

```json
{
  "_id": "MOVING_IN",
  "name": { "en": "Moving In", "ja": "入居・引っ越し", "ko": "입주·이사" },
  "shortDescription": {
    "en": "Registration, utilities, and the first steps after you move in.",
    "ja": "転入届や公共料金など、入居後にまず必要な手続き。",
    "ko": "전입신고·공과금 등 입주 후 가장 먼저 필요한 절차."
  },
  "longDescription": {
    "en": "Everything you need to settle in after moving into your new home in Korea — resident registration, setting up electricity, water, and gas, and other first tasks.",
    "ja": "転入届や電気・水道・ガスの開通など、韓国での新生活を始めるために入居後に必要な手続きをまとめました。",
    "ko": "전입신고, 전기·수도·가스 개통 등 한국에서 새 보금자리에 정착하기 위해 입주 후 처리해야 할 일들을 모았습니다."
  },
  "imageUrl": "https://cdn.kohere.app/life-tips/topics/moving-in/card.png",
  "backgroundImageUrl": "https://cdn.kohere.app/life-tips/topics/moving-in/background.png",
  "order": 1
}
```

#### 팁 카탈로그 — `lifeTips`

한 주제(`topicCode`)에 속한 생활 팁(**제목 · 내용 · 사진**)을 영속하는 카탈로그다(US-8-2). 주제 : 팁 = **1 : N**. `title`·`content`는 표시 문자열이라 **인라인 언어-키 맵**으로 임베드하고, `imageUrl`은 언어 무관(사진, 없으면 `null`)이다. `GET /api/v1/life-tips/topics/{topicCode}/tips`가 path의 주제 `code`로 그 주제의 팁 전체를 노출 순서(`order`)대로 조립해 내려준다(주제당 팁 수가 제한적이라 비페이지 — "해당 주제에 맞는 모든 리스트"). 경로의 `{topicCode}`가 `lifeTipTopics`에 없으면 `404 LIFE_TIP_TOPIC_NOT_FOUND`(신규 도메인 에러코드 — `ErrorCode` 등록 필요, `*_NOT_FOUND` 규약). 시드/마이그레이션으로 적재, 운영 중 갱신 가능.

`lifeTips`

| 필드 | 타입 | 키/제약 |
| --- | --- | --- |
| `_id` | ObjectId | PK · 팁 식별자(API에서는 24자리 hex 문자열 `id`) |
| `topicCode` | string | NOT NULL · 소속 주제 코드 · → `lifeTipTopics._id` 값 참조(애플리케이션 레벨 조인, DB 조인·FK 없음) |
| `order` | int | NOT NULL · 주제 내 노출 순서(오름차순) |
| `title` | object | NOT NULL · 제목(번역 대상)의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `content` | object | NOT NULL · 내용(번역 대상)의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`) — 서버가 사용자 언어 키 선택, 없으면 `en` 폴백 |
| `imageUrl` | string | NULL · 사진 이미지 URL(언어 무관 불변) · 사진이 없으면 `null`(또는 생략) |

**인덱스**: PK `_id` / 복합 `{ topicCode: 1, order: 1 }`(주제별 팁을 노출 순서대로 조회).

- **주제 참조**: `topicCode`는 `lifeTipTopics._id`(주제 코드)를 **값으로만** 참조한다 — 같은 `lifetip` 모듈 안이지만 Mongo 컬렉션 간 조인은 두지 않고 애플리케이션 레벨 조인으로 팁을 조립한다. 경로 `{topicCode}`가 `lifeTipTopics`에 없으면 팁을 조회하지 않고 `404 LIFE_TIP_TOPIC_NOT_FOUND`.
- **번역**: 표시 문자열은 도큐먼트 내부 `title`·`content`의 **인라인 언어-키 맵**에 임베드한다 — 서버가 사용자 언어 키를 골라 조립하고, 없으면 `en` 폴백(에러 아님, `Accept-Language` 비의존). 식별자(`_id`)·`imageUrl`은 언어 무관 불변이라 응답 스키마는 언어와 무관하게 동일하다(서버가 언어 문자열만 채운다).
- **사진 nullable**: `imageUrl`은 언어 무관이며 사진이 없는 팁은 `null`(또는 필드 생략)로 두고, 이때도 `title`·`content`는 정상 노출한다(US-8-2 "사진 없는 팁").
- **비페이지**: 주제당 팁 수가 제한적이라 `order` 오름차순 전체 리스트를 한 번에 반환한다(페이지 객체 없음).
- **교차 모듈 no-FK**: `lifetip`은 표시 언어 도출을 위해 `user` 공개 query(`getLanguage`)만 값 참조로 동기 호출하고(`user`가 `users.lang`이 있으면 그 값, 없으면 `en`으로 폴백 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), 자체 컬렉션 외 다른 모듈을 참조하지 않는다. **게스트는 이 호출조차 하지 않고 `en` 고정**이며, 세입자 게이트 제거(#181)로 `getUserType` 호출은 사라져 `user` 의존은 `getLanguage` 하나만 남는다(임대인은 `users.lang='ko'`라 한국어). 읽기 전용이라 발행/구독 도메인 이벤트가 없다.

**예시 도큐먼트** (`lifeTips`)

```json
{
  "_id": { "$oid": "665f1b2c4a3e2f0012a4c7d1" },
  "topicCode": "MOVING_IN",
  "order": 1,
  "title": {
    "en": "Setting up utilities",
    "ja": "公共料金の手続き",
    "ko": "공과금 설정하기"
  },
  "content": {
    "en": "How to register for electricity, water, and gas after moving in.",
    "ja": "入居後の電気・水道・ガスの登録方法。",
    "ko": "입주 후 전기·수도·가스를 등록하는 방법."
  },
  "imageUrl": "https://cdn.kohere.app/life-tips/moving-in/utilities.png"
}
```

> `imageUrl`이 없는(사진 미제공) 팁 예시 — 언어-키 맵은 그대로 채우고 `imageUrl`만 `null`(또는 생략)이다.

```json
{
  "_id": { "$oid": "665f1b2c4a3e2f0012a4c7d2" },
  "topicCode": "MOVING_IN",
  "order": 2,
  "title": { "en": "Resident registration", "ja": "転入届", "ko": "전입신고" },
  "content": { "en": "Where and when to file your move-in report.", "ja": "転入届の提出先と期限。", "ko": "전입신고를 어디에·언제 하는지." },
  "imageUrl": null
}
```

- **시드**: `lifeTipTopics`·`lifeTips`는 진단 카탈로그(§4-4)와 동일하게 **운영자가 정본 JSON을 주입**한다 — [`life-tip-topics.json`](../../src/test/resources/fixtures/life-tip-topics.json)(5건)·[`life-tips.json`](../../src/test/resources/fixtures/life-tips.json)(6건), 절차는 [migration-policy §8-1](./migration-policy.md#8-1-시드-주입-절차)([ADR-0032](../adr/0032-mongodb-migration-runner.md) §4). 주제 `_id`는 주제 코드, 팁 `_id`는 고정 ObjectId다(응답에 나가는 값이라 형식을 유지한다). 인덱스(`lifeTipTopics.{order}`·`lifeTips.{topicCode,order}`)는 부트스트랩이 기동 시 멱등 보장한다.

## 5. 관련 문서

- [domain-model](../architecture/domain-model.md) — 모듈별 애그리거트·속성·불변식(설계 정본) · [system-overview](../architecture/system-overview.md) — 스토어 토폴로지
- [ADR-0005](../adr/0005-polyglot-persistence.md)(폴리글랏) · [ADR-0006](../adr/0006-refresh-token-store-redis.md)(refresh=Redis) · [ADR-0002](../adr/0002-inter-module-communication-via-events.md)(교차 모듈 통신)
- [migration-policy](./migration-policy.md) · [api-design-guide](../api/api-design-guide.md) · [error-response-guide](../api/error-response-guide.md) · [api/specs](../api/specs/README.md)

## 6. 결정 필요 (Open Questions)

영속 물리화 전 닫아야 할 **저장소·인프라 결정**(도메인 설계는 [domain-model](../architecture/domain-model.md)에서 확정됨).

1. **저장소 결정 완료**: `booking`은 [`V9`](../../src/main/resources/db/migration/V9__bookings.sql), `chat`은 [`V24`](../../src/main/resources/db/migration/V24__create_chat_core_tables.sql), 채팅방 신고는 [`V26`](../../src/main/resources/db/migration/V26__create_chat_reports.sql)으로 MySQL 물리 스키마를 확정했다. [ADR-0005](../adr/0005-polyglot-persistence.md)의 폴리글랏 배치 표에도 실제 배치를 반영해야 한다.
2. **카운트 정합 전략**: `listings.favoriteCount`·community 카운트의 갱신/배치 재계산 주기, MySQL `CHECK` 가능 버전 확인.
3. **검색/레이트리밋**: community FULLTEXT(ngram) 도입 시점(MVP 이후), 공유·신고 레이트리밋 카운터 저장소(Redis 등 — DB 외).
4. **채팅 저장소·카테고리 — 해결됨**: `V24`는 MySQL과 `LANDLORD` 카테고리만 허용하고 `listing_id`를 NOT NULL로 고정했다. 따라서 `NEIGHBOR`나 `listing_id=null` 방의 유일성은 현재 스키마의 결정 필요 항목이 아니다. 향후 실제 요구가 생기면 기존 컬럼에 임의 값을 넣지 않고 새 migration에서 카테고리 CHECK와 유일성 규칙을 함께 설계한다.
5. **문자열 길이**: 스펙 미명시 항목(`title`·이름·`nickname`·`email`·`country`·`provider_user_id`·`terms_version` 등) 실제 검증 규칙 확정.
6. **이메일 인증 정책(세입자)**: 인증번호 길이·만료(TTL)·검증 시도 상한·재발송 레이트리밋 미확정. 메일 발송은 **SMTP**(구체 relay/provider는 인프라 결정 — [ADR-0021](../adr/0021-cost-optimization-profile.md))(§4-1 A-2).
7. **연락처 SMS 인증 정책(임대인)**: SMS provider 선정·인증번호 정책=**이메일과 통일**(6자리·코드 5분·마커 30분·시도 5회·재발송 60초)·**프로필 연락처 변경 시 SMS 재인증**은 확정(provider 상세는 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). ~~연락처 유니크 제약 채택 여부~~ → **해결됨** — `uq_users_phone_number`를 V23으로 채택했다([§4-2](#4-2-user) · [ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md) §5). 남은 미확정: SMS provider 단가·국내외(한국) 번호 발신.
8. **직업(`Occupation`) 분류값**: ~~요구사항 정의서 드롭다운 항목 잘림 → 임시값, 실제 선택지 확정 필요~~ → **해결됨(#93, #138 개편)** — 확정 7종(`UNDERGRADUATE_STUDENT`/`GRADUATE_STUDENT`/`EXCHANGE_STUDENT`/`LANGUAGE_TEACHING`/`MANUFACTURING_PRODUCTION`/`BUSINESS_TRADE`/`ETC`) 반영.
9. **닉네임 풀**: `nickname_adjectives`·`nickname_nouns` 단어 시딩·로케일(언어)·조합 포맷(연결/구분자), 재조합 재시도 상한·fallback 규칙, 무작위 선택 전략(앱 로드 vs `RAND()`) 미확정.
10. **국가(`countries`)**: 표시명 다국어(단일 vs `name_en`/`name_ko`), 시드 출처(ISO 3166-1·전체 국가 확장), `users.country`→`countries.code` FK 적용 여부. (`flag`는 국기 이미지 URL(flagcdn.com SVG)로 확정 — 외부 CDN 의존, 자체 호스팅 전환은 후속 검토.)
11. **게스트 진단 데이터 보존(#181)**: 범위는 **v2 진단 컬렉션 한정**이다 — v1 진단(`/api/v1/diagnoses/**`)은 **회원 전용으로 유지**하기로 확정돼 게스트 문서는 v2 흐름에서만 생성된다(§4-4). 따라서 v1 게스트 세션 키 발급 지점·v1 이력/최근 조회의 게스트 시맨틱은 **결정 필요 항목이 아니다**(게스트는 v1에 401로 막힌다).
    - **TTL 도입 여부 자체가 미확정**: **현재 진단 컬렉션에 TTL 인덱스가 하나도 없다** — `DiagnosisIndexInitializer`가 만드는 것은 `userId_submittedAt_idx`와 `active_step_idx`뿐, `DiagnosisFlowSessionIndexInitializer`가 만드는 것은 `userId` UNIQUE뿐이며 회원 진단도 만료 없이 영구 보존한다. 게스트 문서 누적(및 전역 순차 채번 `diagnosisSequences` 소모) 때문에 TTL을 **새로 도입할지부터** 정해야 하고, 도입 시엔 부트스트랩 initializer에 신규 작성이 필요하다([migration-policy §8](./migration-policy.md) — 인덱스=부트스트랩).
    - **도입 시 보존 기간(후보)**: 설계 검토에서 제안된 후보는 `diagnosisFlowSessions` 게스트 세션 **24시간** / `diagnoses` 게스트 문서 **30일**이나 **제품 결정이 필요**하다. 회원 문서를 지우지 않도록 `guestSessionId` 존재로 좁힌 **partial TTL**이어야 한다.
    - **도입 시 부수 결정 2건**: ① `diagnoses`의 TTL 기준 필드 후보 `submittedAt`은 **`IN_PROGRESS` 문서에 값이 없어 그런 문서가 만료되지 않는다**(게스트 문서는 v2가 종료 상태로만 써서 항상 값이 있지만, v1 회원 draft는 영구히 남는다) → 기준 필드를 `submittedAt`으로 둘지 별도 생성 시각을 둘지 확정. ② `diagnosisFlowSessions`엔 **시각 필드가 아예 없어 TTL 기준 필드(생성 시각) 신설**이 선행돼야 한다.
    - **partial UNIQUE 전환 시 기존 인덱스 drop 주체 미확정**: `diagnosisFlowSessions`의 기존 비-partial UNIQUE 인덱스(`userId_unique_idx`)를 partial로 바꾸려면 **drop 후 재생성**이 필요하다(같은 이름·다른 옵션은 `IndexOptionsConflict`). 현행 initializer는 그 예외를 `catch (RuntimeException)`+`log.warn`으로 **삼켜** 인덱스가 조용히 옛 정의로 남고, 그러면 두 번째 게스트 세션 삽입이 `userId=null` 중복으로 런타임에만 실패한다. drop을 부트스트랩 initializer가 할지 Mongock `@ChangeUnit`이 할지 **미확정**이다(§4-4).
    - **테스트 전략**: 두 initializer가 `@Profile("!test")`라 **테스트 환경엔 인덱스가 없어**(현행 스위트로 partial UNIQUE·TTL이 검증되지 않는다) 별도 통합 테스트 전략을 함께 정한다.
    - **비인증 rate limiting 미확정**: 게스트가 `POST /api/v2/diagnoses/start`를 무제한 호출해 문서·전역 시퀀스를 소모할 수 있다 — 저장소가 아니라 앱 계층 또는 ALB/WAF 소관이라 §6-3의 레이트리밋 항목과 함께 본다.

> refresh 토큰 저장(Redis)·회전·재사용 탐지·TTL(=만료)은 [ADR-0006](../adr/0006-refresh-token-store-redis.md)으로 **확정**돼 결정 필요 항목이 아니다(§4-1 참조).

## 체크리스트

- [ ] 새 테이블/컬렉션이 §2 규약(명명·공통컬럼·타입·enum 문자열·UTC·KRW 정수)을 따른다
- [ ] domain-model의 애그리거트·속성·VO·enum과 필드가 일치한다(필드 의미는 domain-model이 정본)
- [ ] 교차 모듈 참조에 FK를 걸지 않았다(값 참조) — 같은 모듈 안에서만 FK
- [ ] 도메인 불변식의 유니크/인덱스를 반영했다(찜·좋아요·예약 활성·신고 중복·소셜연동)
- [ ] MongoDB 지오 컬렉션에 `2dsphere`, 만료성 데이터에 TTL 인덱스를 두었다
- [ ] 저장소 미정 모듈은 논리 스키마로만 두고 store를 단정하지 않았다
- [ ] MySQL 변경은 [migration-policy](./migration-policy.md) 마이그레이션으로 관리한다
