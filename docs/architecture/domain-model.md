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
| [`auth`](#1-auth--인증온보딩) | `SocialAccount`, `RefreshToken` | `SocialIdentity`, `TokenHash` | ✅ |
| [`user`](#2-user--회원-프로필계정-lifecycle) | `User` | `FullName`, `PhoneContact`, `Consent` | ✅ |
| [`listing`](#3-listing--매물-탐색찜) | `Listing`, `Favorite`, `RecentListing` | `Location`, `Landlord`, `MatchedPlace` | ✅ |
| [`diagnosis`](#4-diagnosis--5단계-맞춤-진단) | `Diagnosis` | `DiagnosisCriteria`, `RecommendationSuggestions` | ✅ |
| [`booking`](#5-booking--매물-신청예약) | `Booking` | `GreetingMessage` | ✅ |
| [`chat`](#6-chat--인앱-채팅) | `ChatRoom`(+`Message`·`ReadCursor`) | `BookingCard`, `ListingCard`, `ListingSnapshot` | ✅ |
| [`community`](#7-community--커뮤니티) | `Post`(+`Comment`·`PostLike`) | `Hashtag` | 이후 |
| [`gamification`](#8-gamification--퀴즈포인트) | `Quiz`, `QuizSubmission`, `PointHistory` | `QuizChoice` | 이후 |
| [`report`](#9-report--신고-처리) | `Report` | `ReportTarget`, `ReportDetail` | 이후 |

> `common`은 공유 커널(애그리거트 없음): 응답 래퍼·예외 표준만 제공.

---

## 1. `auth` — 인증·온보딩

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/auth`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

소셜 로그인(Apple/Google) 자격을 회원 식별자로 매핑하고, 서버 자체 세션 토큰(불투명 refresh)의 발급·회전·재사용 탐지·무효화를 책임지는 인증 경계다. 회원 프로필·상태(`PENDING`/`ACTIVE`/`WITHDRAWN`)는 `user` 모듈 소관이므로 여기선 회원 식별자(`userId`)로만 참조한다.

**`SocialAccount`** — 소셜 제공자 자격을 한 명의 회원에 묶는 자격 매핑 애그리거트 루트. 식별자 `id`, 비즈니스 키 `(provider, providerUserId)`.

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `provider` | enum `Provider` | 소셜 로그인 제공자(`APPLE`/`GOOGLE`) |
| `providerUserId` | String | 제공자가 발급한 사용자 고유 식별자(검증된 `idToken`의 `sub` 클레임) |
| `email` | String | 제공자가 제공한 이메일(검증된 `idToken`에서 추출) — 민감정보 |
| `userId` | 식별자 | 이 자격이 연결된 회원 → `User` 식별자 참조 |
| `linkedAt` | Instant | 자격 연결(최초 발견) 시각(UTC) |

**불변식:** `(provider, providerUserId)` 조합은 전역 유니크 — 동일 제공자 자격은 정확히 한 회원에만 매핑; 소셜 로그인 시 `(provider, providerUserId)`로 조회해 존재하면 기존 회원 로그인, 없으면 신규 자격 생성 + `user` 모듈에 새 `PENDING` 회원 생성을 요청한 뒤 그 식별자로 연결(기존/신규 분기의 단일 진실원); `providerUserId`는 검증 통과 `idToken`(서명·`iss`·`aud`·`exp`)에서만 채워짐 — 검증 실패 토큰으로는 자격을 만들지 않음(`401 AUTH_INVALID_SOCIAL_TOKEN`); 한 번 연결된 `userId`는 재할당 불가(자격 소유권 고정); `email`은 응답·로그 마스킹.

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

**값 객체(VO):**

- `SocialIdentity` — `provider`(enum `Provider`) + `providerUserId`(String). 소셜 자격의 자연키를 한 단위로 표현(동일 값이면 같은 자격).
- `TokenHash` — 불투명 refresh 토큰 원문의 단방향 해시. 동치성은 해시 값으로 판정하며 원문은 재구성 불가.

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `Provider` | `APPLE` | 애플 소셜 로그인 제공자 |
| | `GOOGLE` | 구글 소셜 로그인 제공자 |
| `RefreshTokenStatus` | `ACTIVE` | 발급 후 유효(재발급에 사용 가능) |
| | `ROTATED` | 회전으로 새 세대에 자리를 넘겨 무효화됨(재제출 시 재사용 탐지 대상) |
| | `REVOKED` | 로그아웃·탈퇴·재사용 탐지로 강제 무효화됨 |

**협력 / 이벤트:** 회원 프로필·상태는 `user`가 소유하고 `auth`는 `userId`로만 참조한다(ADR-0002). 신규 분기의 `PENDING` 회원 생성, 온보딩 시 `ACTIVE` 전이, 탈퇴 시 `WITHDRAWN` 전이는 `user`의 공개 명령/쿼리로 협력하고 결과 식별자를 `SocialAccount.userId`로 보유한다. 온보딩 입력의 `gender`·`visaType`는 `user` 소유 enum이라 타입을 공유하지 않고 원시 값으로 전달한다. `user`의 탈퇴 이벤트(`UserWithdrawnEvent`)를 구독해 해당 `userId`의 refresh 토큰을 일괄 무효화한다.

---

## 2. `user` — 회원 프로필·계정 lifecycle

> [API 스펙](../api/specs/01-auth-onboarding.md)(`/api/v1/users/me`) · [시퀀스](sequence-diagrams/01-auth-onboarding/README.md) · `allowedDependencies = {common}`

회원 프로필과 계정 생애주기(가입·온보딩·수정·탈퇴)를 소유한다. 사용자는 소셜 검증만 끝난 `PENDING`에서 온보딩 완료로 `ACTIVE`, 탈퇴로 `WITHDRAWN`이 되는 단방향 상태 모델을 가진다.

**`User`** — 회원의 프로필·동의·계정 생애주기를 일관성 경계로 묶는 애그리거트 루트. 식별자 `id`(소셜 자격→회원 매핑·세션 토큰은 `auth` 소관). [값 객체: `FullName`, `PhoneContact`, `Consent`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자(타 모듈은 이 값으로만 회원 참조) |
| `name` | VO `FullName` | 이름(`firstName`)·성(`lastName`). 온보딩 시 확정 |
| `gender` | enum `Gender` | 성별. 온보딩 필수 |
| `birthDate` | LocalDate | 생년월일(과거 날짜만). 온보딩 필수 |
| `phone` | VO `PhoneContact` | 국가번호·전화번호 — 민감정보 |
| `visaType` | enum `VisaType` | 비자 유형 — 민감정보. 온보딩 필수 |
| `status` | enum `UserStatus` | 계정 상태(생성 시 `PENDING`) |
| `consent` | VO `Consent` | 이용약관·개인정보처리방침·마케팅 동의 3종(동의 여부·시각·약관 버전) |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 최종 수정 시각(UTC) |

**불변식:** 상태 전이는 `PENDING → ACTIVE → WITHDRAWN`만 허용(역전이·건너뛰기 금지); 온보딩 제출은 `PENDING`에서만 가능하며 성공 시 `ACTIVE`로 전이하면서 `name`·`gender`·`birthDate`·`phone`·`visaType`·`consent`를 한 번에 확정(이미 `ACTIVE` 재요청은 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`); 온보딩 완료에는 이용약관·개인정보처리방침 동의가 모두 필요(미동의 `422 AUTH_REQUIRED_AGREEMENT_MISSING`), 마케팅 동의는 선택(기본 미동의); 필수 약관 동의는 프로필 수정으로 철회 불가(탈퇴 경로로만); 프로필 부분 수정은 `ACTIVE`에서만, 전송 필드만 변경(미전송 ≠ 비움), `birthDate`는 과거만; 탈퇴는 `PENDING`/`ACTIVE`에서 `WITHDRAWN`으로(이미 `WITHDRAWN` 재요청 `409 USER_ALREADY_WITHDRAWN`); `WITHDRAWN`·부재 사용자 조회·수정은 `404 USER_NOT_FOUND`; 모든 변경은 `updatedAt`을 갱신; `phoneNumber`·`visaType`은 민감정보로 외부 노출 시 마스킹.

**값 객체(VO):**

- `FullName` — `firstName`(String)·`lastName`(String). 둘 다 빈 문자열 불가.
- `PhoneContact` — `countryCode`(String, 예 `+84`)·`phoneNumber`(String, 국가번호 제외 숫자). 둘 다 빈 문자열 불가. 민감정보.
- `Consent` — `termsOfServiceAgreed`·`privacyPolicyAgreed`·`marketingAgreed`(boolean)·`agreedAt`(Instant)·`termsVersion`(String). 동의 3종과 동의 시점·약관 버전을 기록.

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `UserStatus` | `PENDING` | 소셜 검증만 완료, 온보딩 미완료 |
| | `ACTIVE` | 온보딩 완료, 정상 이용 |
| | `WITHDRAWN` | 탈퇴 |
| `Gender` | `MALE` | 남성 |
| | `FEMALE` | 여성 |
| `VisaType` | `VISA_STUDENT` | 유학·연수 |
| | `VISA_WORK` | 취업 |
| | `VISA_RESIDENCE` | 거주·가족동반 |
| | `VISA_WORKING_HOLIDAY` | 워킹홀리데이 |
| | `VISA_TOURISM` | 관광 |
| | `VISA_ETC` | 기타 |

**협력 / 이벤트:** 모든 타 모듈은 사용자를 `User` 식별자(`id`)로만 참조한다(엔티티 비공유). 소셜 자격→회원 매핑은 `auth`의 `SocialAccount`가 소유하며, `user`는 **회원 생성(`PENDING`)·온보딩 완료(`ACTIVE` 전이)·탈퇴(`WITHDRAWN` 전이)** 를 공개 명령으로, 프로필을 공개 쿼리로 제공한다(`auth`가 소셜 로그인 분기에서 호출). 탈퇴 시 도메인 이벤트(예: `UserWithdrawnEvent`)를 발행해 `auth`가 refresh 토큰을 일괄 무효화하게 한다(ADR-0002). 닉네임·국적 등 표시정보가 필요한 타 모듈(예: `community`)에는 식별자 기반 공개 쿼리를 제공한다.

---

## 3. `listing` — 매물 탐색·찜

> [API 스펙](../api/specs/03-listings-favorites.md) · [시퀀스](sequence-diagrams/03-listings-favorites/README.md) · `allowedDependencies = {common}`

외국인 대상 주거 매물의 탐색(리스트·지도·키워드)·상세·찜·최근 본 매물을 소유한다. 좌표는 WGS84 십진수, 금액은 KRW 정수, 시각은 UTC.

**`Listing`** — 외국인 대상 주거 매물(임대인이 게시한 단건) 애그리거트 루트. 식별자 `id`. [값 객체: `Location`, `Landlord`]

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `title` | String | 매물 제목 |
| `type` | enum `ListingType` | 매물 유형 |
| `monthlyRent` | int | 월세(KRW 정수, ≥0) |
| `deposit` | int | 보증금(KRW 정수, ≥0) |
| `location` | VO `Location` | 좌표·주소·상세주소 |
| `conditions` | `Set<ConditionTag>` | 주거 환경 조건 집합(중복 불가) |
| `contractTermOptions` | `Set<ContractTerm>` | 선택 가능한 계약기간 옵션 집합 |
| `imageUrls` | `List<String>` | 매물 이미지 URL 목록(순서 보존, 첫 번째가 썸네일) |
| `arcRequired` | boolean | 입주에 ARC(외국인등록증)가 필요한지 |
| `availableFrom` | LocalDate | 입주 가능일 |
| `landlord` | VO `Landlord` | 임대인 식별자·표시명·연락 채널 |
| `published` | boolean | 공개 여부(비공개·삭제 매물은 조회 불가) |
| `favoriteCount` | int | 찜 수 집계(≥0) |
| `createdAt` | Instant | 생성 시각(UTC) |
| `updatedAt` | Instant | 수정 시각(UTC) |

**불변식:** `published=false`(비공개·삭제)는 상세·찜·문의 대상에서 제외, 조회 시 부재(`404 LISTING_NOT_FOUND`); `monthlyRent`·`deposit`은 KRW 정수 ≥0; `conditions`·`contractTermOptions`는 중복 없는 집합(계약기간은 최소 1개); `imageUrls` 첫 항목이 썸네일; `landlord.contactChannel`은 항상 `CHAT`(직접 연락처 미노출); `favoriteCount`는 0 미만 불가이며 찜 등록/해제와 정합(멱등 토글에서 중복 증감 없음); `favorited`(사용자별 찜 여부)·`distanceMeters`(기준 좌표 대비 거리)는 조회 시점에 요청 주체·파라미터로 산출되는 표현값이며 애그리거트 영속 속성이 아니다.

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

- `Location` — `lat`(double, 위도)·`lng`(double, 경도)·`address`(String)·`addressDetail`(String). WGS84 좌표와 표기 주소를 묶은 불변 값.
- `Landlord` — `landlordId`(→ `User` 식별자 참조)·`name`(String, 표시명)·`contactChannel`(enum `ContactChannel`, 항상 `CHAT`). 직접 연락처 미보유, 채팅 채널만 노출.
- `MatchedPlace` — `type`(enum `MatchedPlaceType`)·`name`(String)·`lat`·`lng`(double). 키워드 검색에서 입력어가 매칭된 POI(학교·지역·역) 위치를 표현하는 조회 결과 값(매칭 없으면 부재).

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `ListingType` | `GOSIWON` | 고시원 |
| | `CO_LIVING` | 코리빙 |
| | `SHARE_HOUSE` | 셰어하우스 |
| | `OTHER` | 기타 |
| `ConditionTag` | `IMMEDIATE_MOVE_IN` | 즉시 입주 |
| | `FEMALE_ONLY` | 여성 전용 |
| | `PRIVATE_TOILET` | 개인 화장실 |
| | `PRIVATE_BATH` | 개인 욕실 |
| | `ENGLISH_AVAILABLE` | 영어 소통 가능 |
| | `RESIDENT_REGISTRATION` | 전입신고 가능 |
| | `NO_MAINTENANCE_FEE` | 관리비 없음 |
| | `MEALS_PROVIDED` | 식사 제공 |
| | `DOUBLE_ROOM` | 2인실 |
| `ContractTerm` | `ONE_MONTH` | 1개월 |
| | `THREE_MONTHS` | 3개월 |
| | `SIX_MONTHS` | 6개월 |
| | `TWELVE_MONTHS` | 12개월 |
| `ContactChannel` | `CHAT` | 인앱 채팅으로만 연결(직접 연락처 미노출) |
| `MatchedPlaceType` | `UNIVERSITY` | 학교 |
| | `REGION` | 지역 |
| | `SUBWAY_STATION` | 지하철역 |

> 정렬 프리셋(`ListingSort`: `RECOMMENDED`·`PRICE_ASC`·`DISTANCE`)과 지도 검색의 bbox/반경 모드·클러스터 단위·결과 상한은 **조회 파라미터**이지 애그리거트 영속 속성이 아니다(거리순은 기준 좌표 필요, 누락 시 `400 LISTING_INVALID_SORT_PARAM`; 과대 영역 `400 LISTING_AREA_TOO_LARGE`; bbox 모순 `400 LISTING_INVALID_BBOX`).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다(ADR-0002). `Favorite`·`RecentListing`·`Listing.landlord.landlordId`는 `user`를 식별자로만 보유하고 표시정보는 `user` 공개 쿼리로 협력한다. 진단 기반 추천은 `diagnosis`가 본 모듈의 **공개 추천 쿼리**(조건·예산·지역으로 매물 조회)를 호출해 충족하며 매물 엔티티를 공유하지 않고 식별자·요약만 넘긴다. 신청·문의(`booking`·`chat`)는 매물 존재·공개·임대인 식별자가 필요할 때 공개 쿼리로 검증한다.

---

## 4. `diagnosis` — 5단계 맞춤 진단

> [API 스펙](../api/specs/02-diagnosis-recommendation.md) · [시퀀스](sequence-diagrams/02-diagnosis-recommendation/README.md) · `allowedDependencies = {common}`

5단계 맞춤 진단(지역·입국 목적·주거 조건·월 예산 상한·ARC 발급 여부)을 본인 소유 레코드로 영속하고, 진단 조건으로 `listing` 공개 쿼리와 협력해 추천 매물을 제공한다. 재진단은 기존을 수정하지 않고 항상 새 레코드로 이력을 보존한다.

**`Diagnosis`** — 한 사용자가 한 번에 제출한 5단계 진단 입력 묶음(애그리거트 루트). 식별자 `id`, 비즈니스 키 `(userId, idempotencyKey)`(멱등성 키가 제시된 경우에 한해 유일).

**속성:**

| 속성 | 타입 | 설명 |
| --- | --- | --- |
| `id` | 식별자 | 애그리거트 식별자 |
| `userId` | 식별자 | 진단 소유자 → `User` 식별자 참조 |
| `criteria` | VO `DiagnosisCriteria` | 5단계 입력 전체(지역·목적·조건·예산·ARC)를 담은 불변 값 |
| `status` | enum `DiagnosisStatus` | 진단 상태(제출 완료) |
| `idempotencyKey` | String | 중복 제출 방지용 멱등성 키(선택) |
| `submittedAt` | Instant | 제출 시각(UTC) |

**불변식:** `criteria`는 제출 시 한 번 확정 후 불변(재진단은 수정이 아니라 새 `Diagnosis` 생성); `status`는 생성 시 `COMPLETED` 고정, 상태 전이 없음; 조회·추천은 `userId`가 요청자와 일치하는 본인 소유에 한함(타인 `403 FORBIDDEN`); 부재 진단 조회 `404 DIAGNOSIS_NOT_FOUND`; `idempotencyKey`가 제시되면 동일 소유자 범위에서 (키 + 정규화 `criteria`)가 같은 재시도는 1건만 생성·같은 진단 반환(멱등), 같은 키에 다른 `criteria` 재제출은 `409 DIAGNOSIS_IDEMPOTENCY_CONFLICT`.

**값 객체(VO):**

- `DiagnosisCriteria` — `region`(enum `Region`)·`purposes`(`Set<Purpose>`)·`conditions`(`Set<DiagnosisCondition>`)·`monthlyBudgetMax`(int, KRW)·`arcStatus`(enum `ArcStatus`). 생성 검증: `region` 필수 1택; `purposes` 최소 1개·중복 제거; `conditions` 0~3개·중복 제거; `monthlyBudgetMax` 0 이상; `arcStatus` 필수 1택. 위반은 `400 INVALID_INPUT`(필드별 사유).
- `RecommendationSuggestions` — `reason`(enum `NoMatchReason`)·`message`(String, 다국어 fallback)·`actions`(`List<SuggestionAction>`). 추천 매칭 0건일 때의 조건/예산/지역 완화 제안(1건 이상이면 비어 있음).
- `SuggestionAction` — `type`(enum `SuggestionActionType`)·`detail`(String). 결과를 늘리기 위한 단일 조정 제안.

> 진단 결과 화면은 `Diagnosis.criteria`를 입력으로 `listing`의 공개 추천 쿼리를 호출해 매물 요약·좌표를 조립한다. 매물은 본 모듈 애그리거트가 아니므로 식별자(`listingId`)로만 참조하며, 추천 결과는 진단에 종속된 읽기 결과로 영속하지 않는다.

**상태(enum):**

| enum | 값 | 의미 |
| --- | --- | --- |
| `Region` | `SEOUL` | 서울 |
| | `BUSAN` | 부산 |
| | `GYEONGGI` | 경기 |
| `Purpose` | `STUDY` | 학업(유학·연수) |
| | `NON_STUDY` | 비학업(취업 등) |
| `DiagnosisCondition` | `INSTANT_MOVE_IN` | 즉시입주 |
| | `FEMALE_ONLY` | 여성전용 |
| | `PRIVATE_TOILET` | 개인화장실 |
| | `PRIVATE_BATH` | 개인욕실 |
| | `ENGLISH_SPEAKING` | 영어가능 |
| | `RESIDENT_REGISTRATION` | 전입신고가능 |
| | `NO_MAINTENANCE_FEE` | 관리비없음 |
| | `MEALS_PROVIDED` | 식사제공 |
| | `TWIN_ROOM` | 2인실 |
| `ArcStatus` | `ARC_ISSUED` | ARC(외국인등록증) 발급 완료 |
| | `ARC_PENDING` | ARC 미발급·발급 예정 |
| `DiagnosisStatus` | `COMPLETED` | 제출 완료(단일 값, 상태 전이 없음) |
| `NoMatchReason` | `NO_MATCH` | 조건에 맞는 매물 없음 |
| `SuggestionActionType` | `RELAX_REGION` | 지역 조건 완화 |
| | `RELAX_CONDITIONS` | 주거 조건 일부 해제 |
| | `INCREASE_BUDGET` | 월 예산 상한 상향 |
| | `ADJUST_KEYWORD` | 키워드 조정 |

> `DiagnosisCondition`은 `listing`의 `ConditionTag`와 병렬이나 일부 상수명이 다르다(`INSTANT_MOVE_IN`↔`IMMEDIATE_MOVE_IN`, `ENGLISH_SPEAKING`↔`ENGLISH_AVAILABLE`, `TWIN_ROOM`↔`DOUBLE_ROOM`). 각 모듈이 자기 enum을 소유한다(공유 금지).

**협력 / 이벤트:** 타 애그리거트는 식별자로만 참조한다 — `userId`(→ `user`), 추천 결과의 `listingId`(→ `listing`). 추천은 `Diagnosis.criteria`로 `listing` 공개 추천 쿼리를 호출해 매물 요약·좌표를 받아 조립한다(엔티티 비공유, ADR-0002). 진단 제출·재진단은 본 모듈 내부에서 완결되며 외부 발행 이벤트는 없다.

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

- `GreetingMessage` — `text`(공백 제외 1~500자). 신청과 함께 보내는 첫 인사. 미제공 시 부재, 길이 초과는 `400 INVALID_INPUT`. 존재할 때만 채팅 첫 텍스트 메시지로 전달.

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

- `BookingCard` — `moveInDate`(LocalDate)·`contractPeriod`·`monthlyRent`(int, KRW)·`listingTitle`·`bookingId`(→ `Booking` 식별자 참조). 예약 생성 시점 정보를 굳혀 상단 고정하는 불변 카드.
- `ListingCard` — `listingId`(→ `Listing` 식별자 참조)·`title`·`monthlyRent`(int, KRW)·`thumbnailUrl`. 문의 시점 매물 정보를 굳혀 고정하는 불변 카드.
- `ListingSnapshot` — `listingId`(→ `Listing` 식별자 참조)·`title`·`thumbnailUrl`·`monthlyRent`(int, KRW). 채팅방 목록을 매물 조회 없이 표시하는 비정규화 뷰.

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

- `Hashtag` — `name`(String). `#` 없이 정규화된 태그명. 동일 게시글 내 중복 불가, 게시글당 최대 10개.

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

- `QuizChoice` — `key`(enum `ChoiceKey`)·`text`(String). 4지선다 보기 한 개. 동일 `Quiz` 내 `key` 유일, `text` 비어 있지 않음.

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

- `ReportTarget` — `targetType`(enum `ReportTargetType`)·`targetId`(→ 대상 콘텐츠 식별자 참조). 대상의 유형과 다형 식별자를 묶는다. `targetId`는 `POST`/`COMMENT`면 `community`, `MESSAGE`면 `chat`의 콘텐츠를 가리키며, 동일 `targetId`라도 `targetType`이 다르면 다른 대상.
- `ReportDetail` — `text`(String). 신고에 덧붙이는 선택적 자유 텍스트(비어 있을 수 있음, 최대 길이 초과 불가). `reason=ETC`일 때 입력 권장.

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
