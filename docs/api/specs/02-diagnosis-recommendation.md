# 맞춤 진단 & 매물 추천 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

6단계 진단(① 지역 / ② 입국 목적(유학 여부) / ③ 대학·지역 선택 / ④ 주거 환경 조건 / ⑤ 월세 범위(최소~최대) / ⑥ ARC 발급 여부)의 진행 중 답을 **서버가 DB에 저장**하고, 모든 단계 답이 채워진 뒤 별도 제출로 진단을 확정한다. 확정된 진단 조건으로 매칭한 추천 전용 매물 요약과 지도용 좌표를 반환한다. 진단 이력·재진단·최근 진단 다시 보기를 제공한다.

② 입국 목적은 유학 여부(`STUDY`/`NON_STUDY`) 분기이며, 이에 따라 ③ 단계가 대학 선택(유학) 또는 지역(구) 선택(비유학)으로 갈린다. 진단 문항·선택지는 앱이 하드코딩하지 않고 백엔드가 제공한다. 클라이언트가 받을 단계(`step` 1~6)를 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`로 그 단계 질문 1개를 조회하고, 그 단계 답 1개(`field`+`code`)를 `POST /api/v1/diagnoses/answers`로 보내면 서버가 그 답을 **진행 중(IN_PROGRESS) 진단에 저장**한다. 다음 step 번호는 클라이언트가 정한다(서버가 다음 질문을 함께 끼워 주지 않는다 — 질문 조회와 답 저장이 분리된 두 엔드포인트다). ③ 단계의 대학/지역 분기는 클라이언트 분기가 아니라 **서버 비즈니스 로직이 진행 중 진단에 저장된 `purpose`로 결정**해 한쪽 질문만 내려준다. 요청에 누적 답을 묶어 다시 보내지 않는다 — 진행 상태는 서버가 DB에 들고 있다. 표시 라벨은 사용자 표시 언어로 번역되어 내려간다(US-2-5·US-2-6).

> **v2 서버 주도 흐름(issue #157)**: 위 v1 흐름과 별개로, 클라이언트가 `step`을 모르고 **`POST /api/v2/diagnoses/start`** 로 시작한 뒤 **`POST /api/v2/diagnoses/next`** 를 반복 호출하면 서버가 진행 위치로 다음 질문을 결정하는 **서버 주도 대화형 흐름**을 `/api/v2`에 신설한다 — **서버는 질문·분기만 주도**하고 진단을 시작하는 시점도, 확정된 매물을 조회하는 시점도 클라이언트가 정한다(확정 응답은 `diagnosisId`만 주고 매물은 클라이언트가 `GET /api/v2/diagnoses/{id}/recommendations`로 별도 조회). 서버가 미리 필터링하는 지점은 **① 지역 하나뿐**이고(0건이면 카탈로그의 "다른 지역?" 문항을 일반 질문으로 끼워 넣음), 빌더 완성 시 자동 확정하되 **매칭은 조회하지 않는다** — 매칭 0건인지는 클라이언트가 추천을 조회한 응답의 `resultCode: NO_MATCH`로 알려준다(조정 제안 문구·액션은 없음). 상세는 아래 **[v2 — 서버 주도 진단 흐름](#v2--서버-주도-진단-흐름-issue-157)** 절, 결정은 [ADR-0036](../../adr/0036-diagnosis-v2-server-driven-flow.md), 시퀀스는 [US-2-7](../../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-7-v2-server-driven-flow.md). **v1(`/api/v1/diagnoses/*`)은 그대로 유지된다.**

공통 규약:

- 경로 프리픽스 `/api/v1`, 경로는 kebab-case, JSON 필드·쿼리 파라미터는 lowerCamelCase.
- enum은 UPPER_SNAKE 문자열, 시각은 UTC ISO-8601(`2026-06-15T08:30:00Z`), 금액은 KRW 정수(소수점 없음), 좌표는 WGS84 십진수(`lat`/`lng`).
- 목록은 **오프셋 기반 페이지네이션**(api-design-guide §4-1: `page` 0-base, `size` 기본 20·최대 100, `sort=field,(asc|desc)`).
- 모든 엔드포인트는 본인 진단만 접근(소유권 검증). 인증은 `Authorization: Bearer <accessToken>`이며, **v1(§1~§7)은 회원 전용으로 토큰이 필수**다. **비회원(게스트) 진단의 정본 경로는 v2 3개 엔드포인트(v2-1~v2-3)뿐**이며 거기서만 토큰이 선택이다 — 게스트 신원·세션 키·소유권 규칙은 아래 **[게스트 접근](#게스트-접근--비회원-진단-issue-181)** 절이 정본이다.
- 입력 검증 실패(필수값 누락·enum 불일치·조건 개수 초과·월세 범위 위반(`monthlyRentMin`/`monthlyRentMax` 음수 또는 `monthlyRentMin > monthlyRentMax`)·페이지 파라미터 범위·잘못된 `sort` 키)는 공통 코드 `INVALID_INPUT`(400) + `errors[]`로 표현한다(error-response-guide §3·§4). 진단 도메인은 별도 검증 코드를 만들지 않는다.
- 추천 매물 요약은 listing 모듈의 공개 DTO `RecommendedListingView`를 사용하며 일반 탐색의 `ListingSummaryResponse`와 구조가 다르다. 지도 마커는 추천 요약의 `listingId`·`lat`·`lng`에서 조립하고, `listingId`는 MongoDB ObjectId hex 문자열이다.

## 게스트 접근 — 비회원 진단 (issue #181)

> 진단의 **v2 3개 엔드포인트(v2-1~v2-3)만 비회원(게스트)에게 연다**. **v1(§1~§7)은 회원 전용으로 유지**하며 게스트 진단의 정본 경로는 v2뿐이다. 이 절이 게스트 인가·신원·언어·세션 키·소유권 규칙의 정본이다. **기존 회원 경로의 계약(소유권·언어·응답 형태·403/404 의미)은 바뀌지 않는다** — 게스트 경로가 추가되는 것이다.

- **인가**: `SecurityConfig`에 **`/api/v2/diagnoses/**` `permitAll` 매처만 신규 추가**한다. 진단은 현재 전용 매처가 없어 `.anyRequest().authenticated()`로 떨어지므로, 이 줄을 넣지 않으면 게스트 요청이 계속 401이다. **v1(`/api/v1/diagnoses/**`)에는 `permitAll` 매처를 추가하지 않는다** — v1은 현행대로 `.anyRequest().authenticated()`에 남아 토큰이 없으면 401이다. 게스트용 ROLE은 두지 않는다.
- **게스트 신원 = 토큰 부재**(`userId` 없음). 서버가 게스트에게 임시 userId나 JWT를 발급하지 않으며, 게스트 진단 문서는 `userId` 대신 `guestSessionId`로 식별된다(진단 문서·세션에는 둘 중 정확히 하나만 채워진다).
- **언어**: 게스트는 **`en` 고정**이다(사용자 표시 언어 조회를 하지 않는다 — users 행이 없다). `Accept-Language`는 참조하지 않는다. 문항 `question`·옵션 `label`, 추천 카드의 매물명·`type`/`conditions` label이 모두 영어로 내려간다. 회원(온보딩 미완료 토큰 포함)은 기존대로 `users.lang`을 따른다. v1 §7의 `suggestions.message`/`detail`은 게스트가 v1에 접근하지 못하므로 게스트에게 내려가지 않는다(v2-3에는 `suggestions` 필드 자체가 없다).
- **토큰 상태별 처리**(v2 3개 엔드포인트 한정): 토큰 미전송·위조·형식 오류 → **게스트로 처리(2xx)**. 토큰을 보냈는데 **만료**면 게스트로 강등하지 않고 **401 `TOKEN_EXPIRED`** 를 그대로 반환한다(재발급이 필요한 회원이지 게스트가 아니다). 따라서 **v2 3개 엔드포인트에서만** 401 `UNAUTHENTICATED`가 발생하지 않는다. **v1 §1~§7은 토큰이 없거나 위조면 그대로 401 `UNAUTHENTICATED`** 다.
- **온보딩 미완료 토큰**(`ROLE_ONBOARDING`)도 v2를 호출할 수 있다(의도적 수용). 단 신원이 있으므로 언어는 `users.lang`을 따르고 소유권도 `userId` 기준이다. **진단에서는 이것이 새로 생긴 허용이 아니다** — 진단은 전용 매처 없이 `.anyRequest().authenticated()`로 떨어져 있었고 `authenticated()`가 `ROLE_ONBOARDING`도 통과시키므로 #181 이전에도 v1·v2 모두 호출할 수 있었다(`403 AUTH_ONBOARDING_REQUIRED`는 원래 이 경로에서 나오지 않았다). 회원 전용으로 남는 v1도 계속 `ROLE_ONBOARDING` 토큰을 통과시킨다. 매처를 `hasRole("USER")`에서 바꾸는 퀴즈([06](./06-gamification.md))·생활 팁([08](./08-life-tips.md))과 다른 점이다.
- **임대인**: 진단에는 원래 세입자/임대인 역할 게이트가 없어 임대인도 그대로 이용한다(§1 번역 항목) — 게스트 개방으로 달라지는 것이 없다.
- **추천 응답 계약은 그대로다.** 추천 조회는 `listing`의 공개 query(`recommendByCriteria`)를 호출하는데 이 인터페이스가 애초에 신원을 받지 않으므로 **`listing` 모듈 변경이 0건**이고, v2-3 `content`·`markers`·`page`·`resultCode`의 형태·의미가 회원/게스트 동일하다. 차이는 label 언어가 `en`이라는 것뿐이다.

### 게스트 세션 키 (`X-Guest-Session-Id`)

v2 진단은 **여러 요청에 걸친 대화**이므로 게스트도 요청 사이에 안정적인 식별자가 필요하다(퀴즈·생활팁은 저장이 없어 이 헤더를 쓰지 않는다). 서버가 키를 **발급**하고 클라이언트가 이후 요청에 **에코**한다.

| 항목 | 값 |
| --- | --- |
| 헤더 이름 | `X-Guest-Session-Id` |
| 값 형식 | `anonymous<uuid>` (예: `anonymous3f2a1c8e-9b47-4d1e-8a05-2c6f7d3b1e90`) |
| 발급 주체 | 서버(클라이언트가 임의로 만들어 보내지 않는다) |
| 발급 시점 | **`POST /api/v2/diagnoses/start` 응답 하나뿐**. v1에는 게스트 키 발급 지점이 없다 — v1은 회원 전용이다 |
| 회원 요청 | **보내지 않는다.** 토큰이 있으면 서버가 이 헤더를 무시하고 `userId`로만 식별한다 |

**엔드포인트별 요구**(게스트 호출 기준. 회원은 전 엔드포인트에서 불필요):

| Method | Path | `X-Guest-Session-Id` | 비고 |
| --- | --- | --- | --- |
| POST | `/api/v2/diagnoses/start` | 요청에는 불필요 — **응답으로 발급된다** | 보내도 무시하고 새 키를 발급한다 |
| POST | `/api/v2/diagnoses/next` | 필수 | 진행 세션 조회 키 |
| GET | `/api/v2/diagnoses/{diagnosisId}/recommendations` | 필수 | 소유권 검증 키 |

> **v1(§1~§7)은 이 표에 없다.** 회원 전용이라 게스트가 호출할 수 없고(토큰 없으면 401 `UNAUTHENTICATED`), 따라서 v1 엔드포인트는 `X-Guest-Session-Id`를 **읽지도 발급하지도 않는다**. 회원 요청에 이 헤더가 실려 와도 무시된다.
>
> 헤더 없이(또는 남의 키로) `POST /api/v2/diagnoses/next`를 부르면 진행 세션을 찾지 못해 **400 `DIAGNOSIS_SESSION_NOT_FOUND`** 다 — 회원이 세션 없이 `/next`를 부를 때와 같은 코드·같은 복구 절차(`POST /start`로 다시 시작)다.
>
> 키를 잃어버리면 진행 세션과 그 키로 확정한 진단에 **다시 접근할 수 없다**(서버가 되찾아 줄 방법이 없다). 클라이언트는 발급받은 키를 로컬에 보관해야 한다.

### v1은 회원 전용 — 게스트 진단은 v2로 닫힌다

**v1 진단(§1~§7, `/api/v1/diagnoses/**`)은 회원 전용으로 유지한다.** 게스트에게 열지 않으므로 `SecurityConfig`에 v1용 `permitAll` 매처를 추가하지 않고, v1은 현행대로 `.anyRequest().authenticated()`에 남는다 — 토큰 없이 v1을 호출하면 **401 `UNAUTHENTICATED`** 다.

귀결:

- **v1에는 게스트 세션 키 발급 지점이 없다.** `POST /api/v1/diagnoses/answers` 첫 호출에서 키를 발급한다는 제안은 **폐기**됐다. 응답 DTO `AnswerSavedResponse`(`{ saved }`)의 계약도 그대로다.
- **v1 이력(`GET /api/v1/diagnoses`)·최근(`GET /api/v1/diagnoses/latest`)의 게스트 시맨틱을 정의할 필요가 없다** — 게스트는 이 엔드포인트에 도달하지 못한다(401).
- v1 흐름(질문 조회 → 답 저장 → 확정 → 이력·상세·추천)의 서술·검증·에러는 #181 이전과 동일하다.

**게스트 진단 흐름은 v2 3개 엔드포인트로 닫힌다:**

1. `POST /api/v2/diagnoses/start` — 토큰 없이 호출하면 서버가 `anonymous<uuid>` 키를 발급해 응답 `data.guestSessionId`에 실어 주고 ① 지역 질문(`NEXT_QUESTION`)을 반환한다.
2. `POST /api/v2/diagnoses/next` — 받은 키를 `X-Guest-Session-Id` 헤더로 에코하며 문항 답을 하나씩 보낸다. 서버가 다음 질문·흐름 제어 코드·자동 확정(`COMPLETED` + `diagnosisId`)을 결과코드로 돌려준다.
3. `GET /api/v2/diagnoses/{diagnosisId}/recommendations` — 같은 키를 에코해 확정 진단의 추천 매물·지도 좌표를 조회한다(`MATCHED` / `NO_MATCH`).

이슈 #181의 완료 조건("비로그인 사용자가 챗봇 진단 API 호출 시 2xx")은 이 세 엔드포인트로 충족된다.

### 소유권 규칙 (게스트 확장)

소유권 검사는 **신원 종류가 같고 값이 같을 때만** 통과한다. 진단 id가 전역 순차 채번이라 열거가 쉬우므로, 게스트 접근이 열린 뒤 이 검사가 **유일한 IDOR 방어선**이다.

| 요청자 | 대상 진단 | 결과 |
| --- | --- | --- |
| 회원(`userId=A`) | `userId=A` | 통과 |
| 회원(`userId=A`) | `userId=B` | **403 `FORBIDDEN`** |
| 회원(`userId=A`) | 게스트 진단(`guestSessionId` 채워짐) | **403 `FORBIDDEN`** |
| 게스트(키 `K1`) | `guestSessionId=K1` | 통과 |
| 게스트(키 `K1`) | `guestSessionId=K2` | **403 `FORBIDDEN`** |
| 게스트(키 `K1`) | 회원 진단(`userId` 채워짐) | **403 `FORBIDDEN`** |
| 게스트(키 없음) | 임의의 진단 | **403 `FORBIDDEN`** |

> **한쪽이 null이면 무조건 거절**한다 — 게스트가 회원 진단을, 회원이 게스트 진단을 읽지 못한다. 진단 문서에는 `userId`와 `guestSessionId` 중 **정확히 하나만** 채워진다.
>
> 이 표는 **소유권 검사가 도는 지점**(§6 상세·§7 추천·v2-3 추천)에 적용된다. 다만 **게스트 행(키 `K1`·키 없음)이 실제로 도달할 수 있는 지점은 v2-3 추천 하나뿐**이다 — §6·§7은 회원 전용이라 토큰 없는 요청이 소유권 검사 이전에 401로 걸리고, 회원 행(`userId=A`↔`B`)만 §6·§7에서 실현된다. "회원(`userId=A`)이 게스트 진단을 조회 → 403"은 회원이 v2에서 만들어진 게스트 진단 id를 §6·§7이나 v2-3으로 찔러 볼 때 성립한다.
>
> 키 부재의 결과는 엔드포인트마다 다르다 — `POST /api/v2/diagnoses/next`는 400 `DIAGNOSIS_SESSION_NOT_FOUND`이며, 소유권 검사에 도달하기 전에 세션 조회에서 걸린다.
>
> 게스트가 나중에 로그인해도 **게스트 진단이 회원 계정으로 이관되지 않는다**(이관은 이번 범위 밖이며 스키마만 열어 둔다). 로그인 이후에는 그 키로 만든 진단에 접근할 수 없다.

### 게스트 데이터 보존

게스트 진단 문서(`diagnoses`)와 진행 세션(`diagnosisFlowSessions`)은 회원 문서와 달리 **덮어써지지 않고 누적된다** — 회원 세션은 `userId` 키로 upsert(`upsertByUserId`)되어 이전 세션을 교체하지만, 게스트 세션은 `POST /api/v2/diagnoses/start`가 호출될 때마다 **새 키를 발급**하므로 교체 대상이 없기 때문이다. v2에서 게스트 키가 **진단 1회 흐름 단위**라는 것은 여기서 따라 나오는 결과이지 미확정 사항이 아니다.

**현재 저장소에는 TTL 인덱스가 하나도 없다.** `diagnoses`·`diagnosisFlowSessions`를 포함해 어느 Mongo 컬렉션에도 만료 인덱스를 만드는 코드가 없고(인덱스 생성은 `DiagnosisIndexInitializer`·`DiagnosisFlowSessionIndexInitializer`가 담당하는데 만료 옵션을 쓰지 않는다), 따라서 **회원 진단도 영구 보존**된다. 게스트 문서만 예외적으로 만료시키자는 게 아니라, **만료 정책을 새로 도입할지 자체가 미정**이다.

> **결정 필요 — 아래 3건은 확정되지 않았다. 임의로 구현하지 말 것.**
>
> | 항목 | 상태 |
> | --- | --- |
> | 게스트 데이터 TTL **도입 여부와 수치** | 미확정. 현재 TTL 인덱스가 **하나도 없으므로**(회원 진단 포함 영구 보존) 게스트 때문에 만료 정책을 새로 도입할지부터 결정해야 한다. 후속 과제로 거론된 `diagnosisFlowSessions` 게스트 문서 24시간 · `diagnoses` 게스트 문서 30일은 **아직 제안값**이며 제품 결정이 필요하다 |
> | `userId_unique_idx`의 partial 전환 시 **drop 주체** | 미확정. `diagnosisFlowSessions`의 기존 `userId` UNIQUE 인덱스를 partial로 좁히려면 먼저 기존 인덱스를 drop해야 하는데, 이를 `DiagnosisFlowSessionIndexInitializer`가 할지 Mongock changeUnit이 할지 정해지지 않았다 |
> | 비인증 요청 rate limiting | 미확정. 게스트가 `POST /api/v2/diagnoses/start`를 무제한 호출해 문서와 전역 진단 시퀀스를 소모할 수 있으나 저장소에 관련 코드가 없다(앱 계층 또는 ALB/WAF) |

## 진단 입력 enum 정의

| 단계 | 필드 | 타입 | 허용 값 | 제약 |
| --- | --- | --- | --- | --- |
| ① 지역 | `region` | enum (단일) | `SEOUL`, `BUSAN`, `GYEONGGI` | 필수, 1택. MVP 매물 데이터는 `SEOUL` 기준 |
| ② 입국 목적 | `purpose` | enum (단일) | `STUDY`, `NON_STUDY` | 필수, 1택. 유학 여부(`STUDY`/`NON_STUDY`)에 따라 ③ 단계가 갈린다 |
| ③ 대학·지역 선택 | `university`(enum `UniversityGroup`) / `district`(enum `District`) — **두 필드(분리)** | enum (단일) | 유학(`STUDY`) 시 `university`(`UniversityGroup`, **6개 대학 그룹**): `HUFS_KHU_KOREA`, `SKKU_SUNGSHIN`, `SNU_CAU_SOONGSIL`, `HONGIK_YONSEI_EWHA`, `KONKUK_SEJONG_HYU`, `ETC` / 비유학(`NON_STUDY`) 시 `district`(`District`, UPPER_SNAKE): `GURO_GU`, `YEONGDEUNGPO_GU`, `GEUMCHEON_GU`, `GWANAK_GU`, `DONGDAEMUN_GU`, `ETC` | **두 필드로 분리**하고 입국 목적에 따라 **조건부 1택**: 유학(`STUDY`)→`university` 필수·`district` 없음 / 비유학(`NON_STUDY`)→`district` 필수·`university` 없음. 입국 목적에 맞는 하나만 채워진다. 사용자는 **그룹 1개만 단일 선택**하며(1택), 한 그룹은 소속 대학 **어느 하나라도 인근인** 매물과 매칭된다($in, ANY). 저장은 string enum(그룹 코드). 위반(조건부 필수 누락·입국 목적과 불일치)은 공통 `INVALID_INPUT`(400) + `errors[]`로 처리한다 ([ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)) |
| ④ 주거 환경 조건 | `conditions` | enum 배열 (다중) | `MOVE_IN_NOW`, `FEMALE_ONLY`, `PRIVATE_BATH`, `ENGLISH_OK`, `ADDRESS_REGISTRATION`, `NO_MAINT_FEE`, `MEALS_INCLUDED`, `DOUBLE_ROOM` | 선택(0개 허용), **최대 3개**, 중복 불가. listing `ConditionTag` 이름과 통일. 파생 필터 `NO_ARC`(⑥에서 생성)는 여기서 직접 선택 불가 |
| ⑤ 월세 범위 | `monthlyRentMin` / `monthlyRentMax` | integer (KRW) — **두 필드(범위)** | 각 0 이상 정수, `monthlyRentMin <= monthlyRentMax` | **둘 다 필수**. 각 KRW 정수 0 이상이고 최소 ≤ 최대. 위반은 공통 `INVALID_INPUT`(400) + `errors[]` ([ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)) |
| ⑥ ARC 발급 여부 | `arcStatus` | enum (단일) | `ARC_ISSUED`, `NO_ARC` | 필수, 1택. `NO_ARC`(미발급)이면 서버가 추천용 파생 조건 `NO_ARC`(`DiagnosisCondition`)를 `conditions`에 추가한다(§7; ④ 최대 3개 제한에서 제외, `ARC_ISSUED`이면 추가 없음) |

> `conditions` 4개 이상은 `INVALID_INPUT`의 `errors[]`(필드 `conditions`, reason "최대 3개까지 선택할 수 있습니다.")로 응답한다. 별도 도메인 코드를 두지 않는다.
>
> ③ 대학·지역 선택의 조건부 필수 위반(유학인데 `university` 누락 등) 또는 입국 목적과 대학/지역 선택 불일치(예: 비유학인데 `university`를 채움)도 공통 `INVALID_INPUT`(400) + `errors[]`로 처리한다. 진단 도메인에 신규 검증 코드를 두지 않는다.
>
> ③ `UniversityGroup` **그룹 → 소속 대학(member) 코드** 매핑(소속 코드는 기존 개별 대학 코드 그대로이며, 매물은 이 개별 코드를 `nearbyUniversityCodes`에 계속 저장한다 — 매물 저장 구조는 바뀌지 않는다):
>
> | 그룹 코드(`UniversityGroup`) | ko 라벨 | en 라벨 | 소속 대학(member) 코드 |
> | --- | --- | --- | --- |
> | `HUFS_KHU_KOREA` | 한국외대·경희대·고려대 | HUFS · Kyung Hee · Korea Univ. | `HUFS`, `KHU`, `KOREA` |
> | `SKKU_SUNGSHIN` | 성균관대·성신여대 | Sungkyunkwan · Sungshin Women's | `SKKU`, `SUNGSHIN` |
> | `SNU_CAU_SOONGSIL` | 서울대·중앙대·숭실대 | Seoul National · Chung-Ang · Soongsil | `SNU`, `CAU`, `SOONGSIL` |
> | `HONGIK_YONSEI_EWHA` | 홍익대·연세대·이화여대 | Hongik · Yonsei · Ewha Womans | `HONGIK`, `YONSEI`, `EWHA` |
> | `KONKUK_SEJONG_HYU` | 건국대·세종대·한양대 | Konkuk · Sejong · Hanyang | `KONKUK`, `SEJONG`, `HYU` |
> | `ETC` | 기타 | Other | (없음 — 빈 집합) |
>
> `ETC`는 소속 대학이 없는 빈 집합이라 대학 필터를 적용하지 않고 지역 기반 매칭으로만 폴백한다. 추천 매칭은 진단이 선택된 그룹을 소속 대학 코드 집합으로 펼쳐 `listing`에 넘기고, `listing`은 `nearbyUniversityCodes`를 그 코드 집합으로 `$in`(ANY member) 매칭한다(소속 대학 어느 하나라도 인근이면 매칭). 그룹 코드는 선택지 `options[].code`와 1:1 동일 출처다([ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)).

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/diagnoses/questions/{step}` | 단계별 질문 조회(path `step` 1~6 → 그 단계 질문 1개·선택지 반환, ③은 서버가 저장된 `purpose`로 대학/지역 선택, 사용자 표시 언어로 라벨 번역) | 필수 | 200 |
| POST | `/api/v1/diagnoses/answers` | 단계 답 저장(현재 단계 답 1개 `field`+`code`를 진행 중 진단에 저장) | 필수 | 200 |
| POST | `/api/v1/diagnoses` | 진행 중 진단 확정(IN_PROGRESS를 COMPLETED로 확정, 재진단 = 새 진행 중 진단 시작) | 필수 | 201 |
| GET | `/api/v1/diagnoses` | 내 진단 이력 목록(오프셋 페이지네이션) | 필수 | 200 |
| GET | `/api/v1/diagnoses/latest` | 최근 진단 단건(홈 완료 여부 분기용) | 필수 | 200 |
| GET | `/api/v1/diagnoses/{diagnosisId}` | 진단 단건 상세(입력 다시 보기) | 필수 | 200 |
| GET | `/api/v1/diagnoses/{diagnosisId}/recommendations` | 진단 결과: 추천 매물 + 지도 좌표(오프셋 페이지네이션) | 필수 | 200 |

> 추천 결과는 진단에 종속되는 조회이므로 `/diagnoses/{diagnosisId}` 하위 1단계 중첩으로 둔다(api-design-guide §2).
>
> **v1 7개 엔드포인트의 인증은 모두 "필수"(회원 전용)** 다 — 토큰이 없거나 위조면 401 `UNAUTHENTICATED`, 만료면 401 `TOKEN_EXPIRED`다. 비회원(게스트)이 호출할 수 있는 것은 [v2 3개 엔드포인트](#v2--서버-주도-진단-흐름-issue-157)뿐이며, 근거·범위는 [게스트 접근](#게스트-접근--비회원-진단-issue-181) 절이 정본이다.

---

## 상세

### 1. GET `/api/v1/diagnoses/questions/{step}` — 단계별 질문 조회

진단 문항을 **단계별로 1개씩** 조회한다. 클라이언트가 받을 단계(`step` 1~6)를 **path로 지정**하면, 서버가 그 단계 질문 1개와 선택지를 반환한다. 질문 조회와 답 저장은 분리된 두 엔드포인트이며(이 GET은 답을 저장하지 않는다), 다음 step 번호는 클라이언트가 정한다. 선택지 **코드는 진단 확정(`POST /api/v1/diagnoses`) 검증 enum과 1:1 동일 출처**다(US-2-5). 표시 라벨은 사용자 표시 언어로 번역되어 내려간다(US-2-6).

- **인증**: 필수(회원 전용). 토큰 없이 호출하면 401 `UNAUTHENTICATED`다 — 비회원은 v2 흐름(`POST /api/v2/diagnoses/start`)을 쓴다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).
- **동작**: path `step`(1~6)에 해당하는 질문 1개를 `diagnosisQuestions` 카탈로그에서 골라 반환한다 — `step`, `field`, `question`(사용자 언어 라벨 문자열), `select{ type, max }`, `options[{ code, label }]`. ④(`conditions`)처럼 다중 선택은 `select.type: "MULTI"`·`max: 3`으로 내려간다. ⑤(`monthlyRent`)는 고정 선택지 목록이 아니라 **숫자 범위 자유 입력**이므로 `select.type: "NUMBER_RANGE"`(min/max 두 숫자 입력)로 내려가고 `options`는 빈 배열이다 — "모든 단계가 코드 1:1 enum 선택지 목록"이라는 가정에서 의도적으로 carve-out한 단계다([ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)).
- **③ 단계 서버 분기**: ③(`step: 3`) 대학·지역 선택은 진행 중(IN_PROGRESS) 진단에 저장된 ② 입국 목적(`purpose`)에 따라 **서버 비즈니스 로직(diagnosis 서비스 코드)** 이 한쪽 질문만 골라 내려준다(클라이언트 분기 아님) — 저장된 `purpose`가 `STUDY`이면 `field: "university"`로 **대학 그룹 목록**(6개 그룹)을, `NON_STUDY`이면 `field: "district"`로 **지역(구) 목록**을 `options`에 담는다(두 목록을 함께 주지 않는다). `diagnosisQuestions` 카탈로그에는 대학 그룹 질문·지역 질문이 각각 데이터로 존재하고, 어느 질문을 낼지는 서비스가 저장된 `purpose`로 결정한다(카탈로그에 분기 메타는 두지 않는다 — 데이터만). 선택지 `code`는 확정 검증 enum(`UniversityGroup`/`District`)과 1:1 동일 출처다(그룹 1택).
- **번역(US-2-6)**: 반환 질문의 **표시 라벨만** 사용자 표시 언어로 번역한다 — 클라이언트가 언어를 지정하지 않으며 `Accept-Language` 헤더에 의존하지 않는다. 선택지 **코드는 언어와 무관하게 동일**(UPPER_SNAKE, 확정은 코드로 검증)하다. 미지원 언어는 **영어로 폴백**한다(에러 아님). 표시 언어는 `user` 모듈의 **공개 query(`getLanguage`)를 동기 호출**해 취득하며, `user`가 **사용자가 고른 표시 언어(`users.lang`)이 있으면 그 값, 없으면 `en`**을 반환한다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); [ADR-0002](../../adr/0002-inter-module-communication-via-events.md) Decision 5; 토큰 클레임 분기는 사용하지 않음 — `diagnosis → user` 모듈 의존). 진단에는 세입자/임대인 역할 게이트가 없어 임대인도 진단을 이용할 수 있으며, 임대인은 `lang='ko'` 고정이라 진단을 한국어로 본다. v1은 회원 전용이라 요청자에게 항상 users 행이 있으므로 이 경로에 게스트 분기가 없다 — 게스트의 `en` 고정은 v2 흐름에만 적용된다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).
- **카탈로그**: 문항·선택지는 **MongoDB `diagnosisQuestions` 컬렉션**에 데이터로만 둔다(분기 메타 없음 — 분기는 서비스 로직). 번역은 `diagnosisQuestions` 도큐먼트 내부 `question`·`label`의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에 임베드한다. 서버가 (카탈로그 + 사용자 언어 키, ③은 + 저장된 `purpose`)으로 질문 1개를 선정·조립하며, 사용자 언어 키가 없으면 영어(`en`)로 폴백한다. 표시 언어 도출(`users.lang`이 있으면 그 값, 없으면 `en`)은 `user`가 보유한다.

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 필수 | `Bearer <accessToken>` |

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `step` | integer | 필수 | 조회할 단계(1~6). 1=`region`, 2=`purpose`, 3=`university`/`district`(서버가 저장된 `purpose`로 선택), 4=`conditions`, 5=`monthlyRent`(min/max), 6=`arcStatus` |

#### 성공 Response — 200 OK (공통 래퍼)

라벨은 표시 언어가 일본어(`ja`)인 사용자 예시(미지원 언어면 영어 폴백). `code`는 번역과 무관하게 동일하다. 응답에 그 단계 질문 1개만 담긴다 — `step`, `field`, `question`(번역 문자열), `select{ type, max }`, `options[{ code, label }]`. `question`·`label` 문자열은 서버가 `diagnosisQuestions` 도큐먼트의 `question`·`label` 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en` 폴백) 채운 결과이며, 응답 형태(문자열)는 그대로다. 아래는 `GET /api/v1/diagnoses/questions/3` 호출에 대한 ③ `university` 질문 예시다(진행 중 진단의 저장된 `purpose: STUDY` 기준).

```jsonc
{
  "success": true,
  "data": {
    "step": 3,
    "field": "university",
    "question": "大学グループを選択してください",
    "select": { "type": "SINGLE", "max": 1 },
    "options": [
      { "code": "HUFS_KHU_KOREA", "label": "韓国外大・慶熙大・高麗大" },
      { "code": "SKKU_SUNGSHIN", "label": "成均館大・誠信女子大" },
      { "code": "SNU_CAU_SOONGSIL", "label": "ソウル大・中央大・崇実大" },
      { "code": "HONGIK_YONSEI_EWHA", "label": "弘益大・延世大・梨花女子大" },
      { "code": "KONKUK_SEJONG_HYU", "label": "建国大・世宗大・漢陽大" },
      { "code": "ETC", "label": "その他" }
    ]
  },
  "error": null
}
```

> ③(`step: 3`)의 `field`·`options`는 서버 비즈니스 로직이 저장된 `purpose`에 따라 대학 그룹(`STUDY`, 6개 그룹) 또는 지역구(`NON_STUDY`) 질문 중 알맞은 하나만 담는다(저장된 `purpose: NON_STUDY`이면 `field: "district"`로 `{ "code": "GURO_GU", "label": "九老区" }` 류 지역구 목록). 대학 그룹 질문의 `options[].code`는 `UniversityGroup` enum과 1:1 동일 출처다. `GET /api/v1/diagnoses/questions/1`에는 `step: 1`, `field: "region"`의 지역 질문이 돌아온다.
>
> 문항·선택지·번역의 정본은 MongoDB `diagnosisQuestions` 컬렉션이다 — 번역은 도큐먼트 내부 `question`·`label`의 인라인 언어-키 맵에 임베드하고, 서버가 사용자 언어 키를 선택(없으면 `en` 폴백)한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `step` 범위 밖(1~6 아님) + `errors[]`. ② 답 없이 `step: 3`을 조회해 저장된 `purpose`를 찾을 수 없는 경우도 여기에 해당한다(reason "purpose 답변이 선행되어야 합니다.") |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |

---

### 2. POST `/api/v1/diagnoses/answers` — 단계 답 저장

**현재 단계 답 1개**를 그 단계의 `field`+`code`로 보내면, 서버가 그 답을 **진행 중(IN_PROGRESS) 진단에 저장**한다. 사용자당 진행 중 진단은 1건이며, 없으면 첫 답 저장 시 서버가 확보(시작)한다. 요청에 누적 답(answers 묶음)을 담지 않는다 — 진행 상태는 서버가 DB에 들고 있다. `field`·`code`는 진단 입력 enum 정의와 동일 출처이고, 저장된 답은 확정(`POST /api/v1/diagnoses`)에서 다시 검증된다.

- **인증**: 필수(회원 전용). 진행 중 진단은 `userId`로 식별되며 사용자당 1건이다. 비회원은 이 엔드포인트를 호출할 수 없고(401 `UNAUTHENTICATED`) v2 흐름(`POST /api/v2/diagnoses/next`)을 쓴다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).
- **동작**: 본문 `{ field, code }`(다중 선택은 `codes` 배열, ⑤ 월세 범위는 `{ field: "monthlyRent", min, max }` 두 숫자 필드)를 받아 진행 중 진단의 해당 필드에 저장한다. ⑤ `monthlyRent`는 enum 선택지가 아니라 **숫자 범위 자유 입력**이므로 `code`/`codes` 대신 `min`·`max`(KRW 정수) 두 필드를 보낸다(③ 대학 `university`는 그룹 코드 1개를 `code`로 보낸다). 미정의 enum, 현재 단계와 맞지 않는 `field`, 목적-대학/지역 불일치, `monthlyRent`의 음수·`min > max` 등은 공통 `INVALID_INPUT`(400) + `errors[]`로 거부한다.

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 필수 | `Bearer <accessToken>` |

#### Request Body (래퍼 없이)

**현재 단계 답 1개**를 그 단계의 `field`+`code`로 담는다(누적 답 묶음이 아니다).

```jsonc
{
  "field": "purpose",
  "code": "STUDY"
}
```

> ② `purpose` 답을 보내면 서버가 그 답을 진행 중 진단에 저장한다(이후 클라이언트가 `GET /api/v1/diagnoses/questions/3`을 호출하면 저장된 `purpose`에 따라 ③ 대학/지역 질문이 갈린다). `conditions`처럼 다중 선택(④) 단계는 `code` 대신 `codes`(배열)로 보낸다.

```jsonc
{
  "field": "conditions",
  "codes": ["MOVE_IN_NOW", "FEMALE_ONLY"]
}
```

⑤ 월세 범위(`monthlyRent`)는 enum 코드 선택이 아니라 **숫자 범위**이므로 `code`/`codes` 배열이 아니라 `min`·`max`(KRW 정수) 두 숫자 필드로 보낸다. 둘 다 0 이상 정수이고 `min <= max`여야 한다(위반 시 `INVALID_INPUT` + `errors[]`).

```jsonc
{
  "field": "monthlyRent",
  "min": 300000,
  "max": 600000
}
```

> ③ 대학(`university`)은 그룹 코드 **1개**를 `code`로 보낸다(예: `{ "field": "university", "code": "SNU_CAU_SOONGSIL" }`). ⑤ `monthlyRent`만 `min`/`max` 숫자 두 필드를 쓰는 carve-out이며, 순서 없는 `codes[]` 배열을 재사용하지 않는다.

#### 성공 Response — 200 OK (공통 래퍼)

답이 진행 중 진단에 저장되면 `saved: true`를 반환한다. 클라이언트는 다음 step 번호를 스스로 정해 `GET /api/v1/diagnoses/questions/{step}`을 이어 호출한다.

```jsonc
{
  "success": true,
  "data": { "saved": true },
  "error": null
}
```

> 응답 필드는 `saved` 하나다(`AnswerSavedResponse`). 게스트 세션 키를 여기서 발급하지 않으므로 이 계약은 #181로 바뀌지 않는다 — v1은 회원 전용이다([게스트 접근](#게스트-접근--비회원-진단-issue-181)의 "v1은 회원 전용").

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 잘못된 현재 단계 답(미정의 enum, 현재 단계와 맞지 않는 `field`, 입국 목적과 대학/지역 불일치, `conditions` 4개 이상 등) + `errors[]` |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치(검증 이전) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |

---

### 3. POST `/api/v1/diagnoses` — 진행 중 진단 확정

진행 중(IN_PROGRESS) 진단을 **COMPLETED로 확정**한다. 모든 단계 답은 이미 `POST /api/v1/diagnoses/answers`로 서버 DB(진행 중 진단)에 저장돼 있으므로, 본 요청 본문은 **6필드 누적 답을 다시 보내는 것이 아니라** 진행 중 진단을 확정해 달라는 요청이다. 서버는 저장된 답을 다시 검증해 확정하고 `diagnosisId`·`submittedAt`을 발급한다(진단 생성·COMPLETED 시점은 이 확정이다). 재진단도 동일 엔드포인트로, 새 진행 중 진단을 시작해 채운 뒤 확정한다(항상 새 레코드, 기존 진단을 덮어쓰지 않음).

- **인증**: 필수(회원 전용). 확정할 진행 중(IN_PROGRESS) 초안은 `userId`로 찾고, 확정된 진단의 소유도 `userId`로 결정된다. 비회원의 확정은 v2 흐름의 자동 확정(`COMPLETED`)으로 일어난다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 필수 | `Bearer <accessToken>` |

#### Request Body (래퍼 없이)

본문은 6필드 누적 답을 다시 보내는 것이 **아니다**. 진행 중(IN_PROGRESS) 진단을 확정하는 요청이며, 본문 없이(`{}`) 보내도 된다.

```jsonc
{}
```

> 모든 단계 답은 이미 `POST /api/v1/diagnoses/answers`로 서버 DB(진행 중 진단)에 저장돼 있다. 서버는 그 진행 중 진단의 저장된 답을 **확정 시점에 다시 검증**해 COMPLETED로 굳힌다. 검증 대상 필드·규칙(아래 표)은 저장된 답에 적용되며, 본문에 6필드를 다시 담지 않는다. `university`(`UniversityGroup` 그룹 1택)/`district`는 **두 필드(분리)** 이고 enum 값 목록·조건부 필수 규칙은 "진단 입력 enum 정의" ③행을 정본으로 한다.

| 검증 대상 필드(저장된 답) | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `region` | enum | 필수 | 허용 enum 1택 |
| `purpose` | enum | 필수 | 허용 enum 1택 |
| `university` | enum (`UniversityGroup`) | 조건부 | 유학(`STUDY`) 시 필수·`district` 없음, 허용 그룹 enum 1택(값 목록은 ③행 정본) |
| `district` | enum (`District`) | 조건부 | 비유학(`NON_STUDY`) 시 필수·`university` 없음, 허용 enum 1택(값 목록은 ③행 정본) |
| `conditions` | enum 배열 | 선택 | 최대 3개, 허용 enum, 중복 제거 |
| `monthlyRentMin` / `monthlyRentMax` | integer (KRW) | 필수 | 각 0 이상 정수이고 `monthlyRentMin <= monthlyRentMax`(둘 다 필수) |
| `arcStatus` | enum | 필수 | 허용 enum 1택 |

> 저장된 답이 검증을 위반하면(단계가 덜 채워졌거나 enum 불일치 등) `400 INVALID_INPUT` + `errors[]`(필드별 `field`/`reason`). 예: `monthlyRentMin`/`monthlyRentMax` 음수 → reason "0 이상이어야 합니다.", `monthlyRentMin > monthlyRentMax` → reason "monthlyRentMin은 monthlyRentMax 이하여야 합니다.", `conditions` 4개 이상 → reason "최대 3개까지 선택할 수 있습니다.".
>
> ③ 대학·지역 선택의 조건부 필수 누락(유학인데 `university` 없음 등)·입국 목적과 대학/지역 선택 불일치도 동일하게 `400 INVALID_INPUT` + `errors[]`로 처리한다. 진단 도메인에 신규 검증 코드를 만들지 않는다.

#### 성공 Response — 201 Created (공통 래퍼)

`Location: /api/v1/diagnoses/{diagnosisId}` 헤더를 포함한다.

```jsonc
{
  "success": true,
  "data": {
    "diagnosisId": 1024,
    "status": "COMPLETED",
    "submittedAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 확정할 진행 중(IN_PROGRESS) 진단이 없음(reason "진행 중인 진단이 없습니다."). 그 밖에 저장된 답의 필수값 누락(단계 미완료), enum 불일치, `conditions` 4개 이상, `purpose` 누락, `monthlyRentMin`/`monthlyRentMax` 음수 또는 `monthlyRentMin > monthlyRentMax`, 대학/지역 조건부 필수 누락·입국 목적과 대학/지역 선택 불일치 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치(검증 이전) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |

---

### 4. GET `/api/v1/diagnoses` — 내 진단 이력 목록

사용자의 진단 이력을 최신순으로 반환한다. **오프셋 기반 페이지네이션**(api-design-guide §4-1).

- **인증**: 필수(회원 전용). 본인 진단만 반환된다(타인 진단은 애초에 목록에 없음). 게스트에게 이력을 노출하지 않는다 — 토큰 없이 호출하면 401 `UNAUTHENTICATED`이며, 게스트 진단(`guestSessionId` 소유)은 이 목록의 대상이 아니다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).
- **상태 필터**: 확정된 진단(`status=COMPLETED`)만 노출한다. 진행 중(IN_PROGRESS) 진단은 이력·목록에서 제외한다.

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | integer | 선택 | `0` | 0-base 페이지 번호 |
| `size` | integer | 선택 | `20` | 페이지 크기(최대 100) |
| `sort` | string | 선택 | `submittedAt,desc` | `field,(asc\|desc)`. 허용 키: `submittedAt` |

#### 성공 Response — 200 OK (공통 래퍼)

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "diagnosisId": 1024,
        "region": "SEOUL",
        "purpose": "STUDY",
        "university": "SNU_CAU_SOONGSIL",
        "district": null,
        "conditions": ["FEMALE_ONLY", "PRIVATE_BATH"],
        "monthlyRentMin": 300000,
        "monthlyRentMax": 600000,
        "arcStatus": "ARC_ISSUED",
        "status": "COMPLETED",
        "submittedAt": "2026-06-15T08:30:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 3,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

> 이력이 0건이면 `content: []`, `totalElements: 0`(에러 아님).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `size` 범위 초과, 허용되지 않은 `sort` 키 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |

---

### 5. GET `/api/v1/diagnoses/latest` — 최근 진단 단건

홈 화면의 "진단 시작 / 재진단" 문구 분기를 위해 사용자의 가장 최근 **확정(COMPLETED) 진단** 1건을 반환한다(진행 중 IN_PROGRESS 진단은 제외). 확정 이력이 없으면 `data.completed=false`(404 아님).

- **인증**: 필수(회원 전용). 토큰 없이 호출하면 401 `UNAUTHENTICATED`이며, 게스트 진단은 이 조회의 대상이 아니다(§4와 같은 사유 — [게스트 접근](#게스트-접근--비회원-진단-issue-181)).

#### 성공 Response — 200 OK (공통 래퍼) — 이력 있음

```jsonc
{
  "success": true,
  "data": {
    "completed": true,
    "diagnosisId": 1024,
    "region": "SEOUL",
    "purpose": "STUDY",
    "university": "SNU_CAU_SOONGSIL",
    "district": null,
    "conditions": ["FEMALE_ONLY", "PRIVATE_BATH"],
    "monthlyRentMin": 300000,
    "monthlyRentMax": 600000,
    "arcStatus": "ARC_ISSUED",
    "submittedAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 성공 Response — 200 OK — 이력 없음

```jsonc
{
  "success": true,
  "data": { "completed": false },
  "error": null
}
```

> `completed=false`일 때 진단 요약 필드(`diagnosisId` 등)는 포함하지 않는다. 클라이언트는 `completed` 한 필드로 분기한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |

---

### 6. GET `/api/v1/diagnoses/{diagnosisId}` — 진단 단건 상세

진단 단건의 입력 전체를 반환한다(지난 진단 다시 보기). 본인 소유 진단만 조회 가능.

- **인증**: 필수(회원 전용). **본인 소유가 아니면 `403 FORBIDDEN`.** 소유는 **신원 종류가 같고 값이 같을 때만** 인정되므로, 회원이 v2에서 만들어진 게스트 진단(`guestSessionId` 소유) id를 조회해도 403이다([게스트 접근](#게스트-접근--비회원-진단-issue-181)의 소유권 규칙 표).

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `diagnosisId` | Long | 필수 | 진단 식별자 |

#### 성공 Response — 200 OK (공통 래퍼)

```jsonc
{
  "success": true,
  "data": {
    "diagnosisId": 1024,
    "region": "SEOUL",
    "purpose": "STUDY",
    "university": "SNU_CAU_SOONGSIL",
    "district": null,
    "conditions": ["FEMALE_ONLY", "PRIVATE_BATH"],
    "monthlyRentMin": 300000,
    "monthlyRentMax": 600000,
    "arcStatus": "ARC_ISSUED",
    "status": "COMPLETED",
    "submittedAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |
| 403 | `FORBIDDEN` | 타인 소유 진단 접근. 회원 토큰으로 게스트 진단(`guestSessionId` 소유)을 조회하는 경우도 여기에 해당한다 |
| 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않음 |

---

### 7. GET `/api/v1/diagnoses/{diagnosisId}/recommendations` — 진단 결과(추천 매물 + 지도 좌표)

진단 조건으로 매칭한 매물 요약 리스트와 지도 마커 좌표를 반환한다. 매물 요약은 모듈 간 공개 DTO `RecommendedListingView`를 사용하며 일반 탐색의 `ListingSummaryResponse`와는 필드 구성이 다르다. 매칭이 0건이면 빈 목록 + 조건/월세 범위/키워드 조정 제안(`suggestions`)을 함께 반환한다(에러 아님).

- **인증**: 필수(회원 전용). **본인 소유가 아니면 `403 FORBIDDEN`.** 소유권 규칙은 §6과 같다(신원 종류·값이 모두 일치할 때만 통과 — 회원 토큰으로 게스트 진단을 조회하면 403). 게스트의 추천 조회는 v2-3(`GET /api/v2/diagnoses/{id}/recommendations`)이 담당한다([게스트 접근](#게스트-접근--비회원-진단-issue-181)).
- **페이지네이션**: 오프셋 기반(매물 목록, api-design-guide §4-1). 지도 마커(`markers`)는 응답 매물의 `listingId`·`lat`·`lng` 좌표를 함께 제공하며, 클러스터링은 프론트 지도 SDK가 처리한다.
- **모듈 간 협력(diagnosis → listing)**: 추천은 즉시 결과가 필요하므로 이벤트가 아니라 **동기 공개 query 호출**로 실현한다([ADR-0002](../../adr/0002-inter-module-communication-via-events.md) Decision 5). `diagnosis`가 진단 조건을 `RecommendationCriteria`(지역·월세 범위·`conditions` + 대학/지역(③) 등) 값객체로 묶어 `listing`의 공개 query(`recommendByCriteria`)를 동기 호출하고, `RecommendedListingView` 페이지를 수신해 위 응답과 좌표를 조립한다(엔티티 비공유, 공개 DTO/포트로만). 계약 영향: (1) **대학** — `RecommendationCriteria.universityCodes`는 선택된 그룹을 펼친 **소속 대학 코드 집합 `Set<String>`**(member codes)이다. 진단이 `UniversityGroup`→member 펼침을 소유(`ETC`는 빈 집합 → 대학 필터 생략·지역 기반 폴백)하고, `listing`은 이 집합으로 `nearbyUniversityCodes`를 `$in`(ANY member) 매칭한다. (2) **월세** — `RecommendationCriteria`는 `monthlyRentMin`/`monthlyRentMax`(각 nullable, null/미지정=해당 경계 무제한)를 싣고, `listing`은 각 경계가 있을 때 같은 ACTIVE `roomOffers[]` 원소의 `pricing.monthlyRent`에 하한·상한을 적용한다([ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)). (3) **ARC** — ⑥ `arcStatus=NO_ARC`면 `diagnosis`가 파생 조건 `NO_ARC`를 전달하고, `listing`은 `propertyPolicies.arcRequired=false`로 해석한다. `ARC_ISSUED`이면 ARC 필터를 적용하지 않는다. 응답의 `monthlyRentMin/Max`·`minDeposit/maxDeposit`·`conditions`는 현재 매칭된 방 상품만이 아니라 해당 매물의 전체 ACTIVE `roomOffers`를 기준으로 계산한다. `conditions`에는 ACTIVE 방 상품 태그 합집합과 `NO_ARC` 같은 매물 정책 파생 조건이 포함된다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `diagnosisId` | Long | 필수 | 진단 식별자(본인 소유) |

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | integer | 선택 | `0` | 0-base 페이지 번호 |
| `size` | integer | 선택 | `20` | 페이지 크기(최대 100) |
| `sort` | string | 선택 | `recommended,desc` | `field,(asc\|desc)`. 허용 키: `recommended`(추천순) / `price`(가격순) / `distance`(거리순) |

> **현재 정렬 구현 제약:** 요청 검증은 위 세 키와 `asc`/`desc`를 허용하지만, 저장소는 `price*`를 월세 오름차순으로만 처리하고 나머지(`recommended`, `distance`)는 `favoriteCount desc, updatedAt desc` 기본 정렬로 처리한다. 따라서 `price,desc` 방향은 반영되지 않고 `distance` 거리 계산도 아직 구현되지 않았다.

#### 성공 Response — 200 OK (공통 래퍼) — 결과 있음

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Sinchon Co-living House A",
        "type": { "code": "CO_LIVING", "label": "Co-living" },
        "monthlyRentMin": 550000,
        "monthlyRentMax": 700000,
        "minDeposit": 1000000,
        "maxDeposit": 1500000,
        "lat": 37.555134,
        "lng": 126.936893,
        "conditions": [
          { "code": "FEMALE_ONLY", "label": "Female Only" },
          { "code": "PRIVATE_BATH", "label": "Private Bath" },
          { "code": "NO_ARC", "label": "No ARC" }
        ],
        "thumbnailUrl": "https://cdn.kohere.app/listings/6858e2000000000000000001/thumb.jpg"
      }
    ],
    "markers": [
      { "listingId": "6858e2000000000000000001", "lat": 37.555134, "lng": 126.936893 }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 12,
      "totalPages": 1,
      "hasNext": false
    },
    "suggestions": null
  },
  "error": null
}
```

> 추천 카드의 매물명·type/conditions label은 진단 사용자가 계정에서 선택한 표시 언어가 적용된다. 프론트는 label을 표시하고 code를 필터 요청·비교에 사용한다([ADR-0037](../../adr/0037-listing-localization-and-code-catalog.md)).

#### 성공 Response — 200 OK — 결과 0건 (조정 제안 포함)

```jsonc
{
  "success": true,
  "data": {
    "content": [],
    "markers": [],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 0,
      "totalPages": 0,
      "hasNext": false
    },
    "suggestions": {
      "reason": "NO_MATCH",
      "message": "조건에 맞는 매물이 없습니다. 조건이나 예산을 조정해 보세요.",
      "actions": [
        { "type": "RELAX_REGION", "detail": "BUSAN/GYEONGGI는 현재 매물 데이터가 준비 중입니다. SEOUL로 변경해 보세요." },
        { "type": "RELAX_CONDITIONS", "detail": "선택한 조건 중 일부를 해제하면 결과가 늘어납니다." },
        { "type": "INCREASE_BUDGET", "detail": "월세 범위를 넓혀 보세요(최소를 낮추거나 최대를 높이세요)." }
      ]
    }
  },
  "error": null
}
```

> `suggestions.actions[].type` 후보: `RELAX_REGION`, `RELAX_CONDITIONS`, `INCREASE_BUDGET`, `ADJUST_KEYWORD`(확인 필요 — 제안 액션 enum 카탈로그는 기획 확정 필요).
>
> **번역(US-2-6 일관)**: `reason`·`actions[].type`은 언어 무관 **enum 키**이고, 사람이 보는 `message`·`detail`은 **서버가 사용자 표시 언어로 조립해 전송**한다(클라이언트 매핑 아님). 서버가 **MongoDB `diagnosisSuggestions` 컬렉션**(`reason`을 `_id`로)의 `reason`/`type`별 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)에서 사용자 언어 키를 골라(없으면 영어 폴백) `message`/`detail`을 채운다 — 문항 `question`·옵션 `label`과 **동일한 인라인 언어-키 맵 방식**이다. 표시 언어는 `user` 공개 query(`getLanguage`)로 취득(US-2-5·US-2-6과 동일 i18n 경로)하고 미지원 언어는 영어로 폴백한다. 위 예시 `message`/`detail` 문자열은 한국어 표기다. **`suggestions`는 v1 전용이며 v1은 회원 전용이므로 게스트에게 내려가지 않는다**(v2-3 응답에는 이 필드가 없다).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `size` 범위 초과, 허용되지 않은 `sort` 키 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |
| 403 | `FORBIDDEN` | 타인 소유 진단 접근. 회원 토큰으로 게스트 진단(`guestSessionId` 소유)을 조회하는 경우도 여기에 해당한다 |
| 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않음 |

---

## v2 — 서버 주도 진단 흐름 (issue #157)

> 위 v1 흐름(§1~§7, `/api/v1/diagnoses/*`)은 **그대로 유지**하고, 서버 주도 대화형 흐름을 `/api/v2`에 **신설**한다. 결정: [ADR-0036](../../adr/0036-diagnosis-v2-server-driven-flow.md) · 시퀀스: [US-2-7](../../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-7-v2-server-driven-flow.md) · 유저 스토리: [US-2-7](../../requirements/user-stories.md).

클라이언트가 `step`을 지정하지 않고 **`POST /api/v2/diagnoses/start`** 로 진단을 시작한 뒤 **`POST /api/v2/diagnoses/next`** 를 반복 호출하면, 서버가 직전에 낸 문항에서 **다음 질문을 결정**하고, 빌더가 다 채워지면 **자동 확정**한다.

**서버는 질문과 분기만 주도한다.** 진단을 시작하는 시점도, 확정된 매물을 조회하는 시점도 **클라이언트가 결정**한다 — 확정 응답(`COMPLETED`)에 추천 매물을 인라인으로 싣지 않고 `diagnosisId`만 주며, 클라이언트가 그 식별자로 **v2-3 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`** 를 별도 호출해 매물 목록·지도 좌표를 받는다. **확정 시점에 서버는 매칭 유무조차 확인하지 않는다** — 그러려면 클라이언트가 요청하지도 않은 추천 쿼리를 돌려야 하기 때문이다.

- **서버가 미리 필터링하는 지점은 ① 지역 하나뿐이다.** ① 지역(`region`) 답 직후 매칭 매물이 0건이면 서버가 **"현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?"** 예외질문을 끼워 넣는다. 이 예외질문은 따로 관리하지 않고 **일반 question으로 관리**한다 — 서버 코드에 하드코딩한 합성 문구가 아니라 문항 카탈로그(`diagnosisQuestions`)의 일반 문항(`step: 1`, `field: "regionRetry"`, `select: { type: "SINGLE", max: 1 }`, `options: [{code:"YES"},{code:"NO"}]`)이며, 별도 결과코드가 아니라 일반 **`NEXT_QUESTION`** 으로 내려간다. **그 예/아니오 응답에만** "프론트가 행할 행위"를 코드로 알린다 — 예=`RESTART`(클라이언트가 `POST /start`로 재시도) / 아니오=`TERMINATED`(진단 종료).
- **6번 질문까지 마친 뒤 매물이 0건인 경우엔 어떤 suggestion도 없다.** 다만 그 사실은 흐름 응답이 아니라 **클라이언트가 추천을 조회한 v2-3 응답의 빈 `content`** 로 드러난다 — no-match를 확정 시점에 결과코드로 미리 주려면 서버가 추천 쿼리를 선행해야 하므로 그렇게 하지 않는다. v1의 조정 제안(`suggestions`·`diagnosisSuggestions` 시드)은 **v1 전용으로 그대로 두고 v2는 참조하지 않는다**(v2-3 응답에는 `suggestions` 필드가 없다).
- 문항 카탈로그(`diagnosisQuestions`)·번역(사용자 표시 언어 기준, `en` 폴백)·진단 입력 enum·③ 대학/지역 분기 규칙은 **v1과 동일 출처를 공유**한다(§1·§3의 "진단 입력 enum 정의"·[ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md)·[ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md)를 그대로 따른다). 정본 순서는 `REGION(1) → PURPOSE(2) → UNIVERSITY_OR_DISTRICT(3, purpose로 university|district) → CONDITIONS(4) → MONTHLY_RENT(5) → ARC_STATUS(6)`이다. step 1에는 `region`·`regionRetry` 두 문항이 나란히 있으므로 서버가 낼 문항을 `field`로 지목한다(목록 순서에 기대지 않는다). **v1 계약은 바뀌지 않는다** — `GET /api/v1/diagnoses/questions/1`은 계속 `field: "region"` 지역 질문 1개를 반환한다(§1).
- 진행 상태는 v1의 `diagnoses`(IN_PROGRESS 초안)를 공유하지 않고 **v2 전용 세션**(`diagnosisFlowSessions` 컬렉션: `{ userId, guestSessionId, draft, pendingField }`)에 담는다. 회원 세션은 `userId`가, 게스트 세션은 `guestSessionId`가 채워지며 **정확히 하나만** 채워진다(`userId` UNIQUE 인덱스는 partial로 좁히고 `guestSessionId` partial UNIQUE를 별도로 둔다 — [게스트 접근](#게스트-접근--비회원-진단-issue-181)). 세션은 `POST /start`에서만 생기고 터미널(`COMPLETED`·`RESTART`·`TERMINATED`)에서 삭제된다. 완료 시에만 정본 진단을 만들어 기존 `diagnoses` 컬렉션에 저장한다(v1 이력/상세 조회 재사용). **① 지역 0건으로 끝난 시도는 버리지 않는다** — 세션을 지우기 전에 부분 답을 `diagnoses`에 `status=DISCARDED`로 남겨 수요 분석("어느 지역을 원했는데 매물이 없었나")에 쓴다(재시도·종료 양쪽). 그 외 이탈은 돌아왔을 때에야 알 수 있어 집계가 편향되므로 기록하지 않는다(`/start`가 이전 세션을 그냥 덮어쓴다). **API로 노출되지 않는다** — 이력·최근·추천 조회는 `COMPLETED`만 본다. 상세는 [ADR-0036](../../adr/0036-diagnosis-v2-server-driven-flow.md).
- **카탈로그 시드**: `regionRetry` 문항은 `DiagnosisCatalogSeedChangeUnit`(order `0000`) 시드에 포함되고, 이미 배포된 환경에는 `DiagnosisRegionRetryQuestionChangeUnit`(order `0005`, 멱등)이 적재한다.

### 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| POST | `/api/v2/diagnoses/start` | 진단을 처음부터 시작 — 진행 중 세션 폐기 후 ① 지역 질문(게스트면 세션 키 발급) | 선택(게스트 허용) | 200 |
| POST | `/api/v2/diagnoses/next` | 서버 주도 대화형 진단: 현재 문항 답 1개를 적용하고 다음 결과(다음 질문/흐름 제어 코드/자동 확정 결과)를 결과코드로 반환 | 선택(게스트 허용) | 200 |
| GET | `/api/v2/diagnoses/{diagnosisId}/recommendations` | 확정 진단의 추천 매물·지도 좌표(조정 제안 없음) — 조회 시점은 클라이언트가 결정 | 선택(게스트 허용) | 200 |

> **게스트**: 세 엔드포인트 모두 토큰 없이 호출할 수 있다. `/start`가 게스트 세션 키(`X-Guest-Session-Id`, `anonymous<uuid>`)를 응답으로 발급하고, `/next`와 추천 조회는 그 키를 헤더로 에코받는다 — 상세는 [게스트 접근](#게스트-접근--비회원-진단-issue-181) 절.

### 결과코드(`FlowResultCode`) 계약

정상 `200 OK`, 공통 래퍼 `{ success, data, error }`의 `data`가 **태그드 유니온**이다 — `data.resultCode`(UPPER_SNAKE enum) 값에 따라 채워지는 payload가 다르며, 채워지지 않는 payload 필드는 생략된다(`NON_NULL`). **`TERMINATED`는 에러가 아니라 정상 결과**이므로 `error`가 아니라 `data.resultCode`로 표현한다. 응답 DTO는 `DiagnosisFlowResponse { FlowResultCode resultCode, QuestionResponse question?, Long diagnosisId?, String guestSessionId? }`이며, `/start`와 `/next`가 같은 DTO를 쓴다. `guestSessionId`는 **게스트가 `/start`를 호출했을 때만** 채워진다(회원 응답·`/next` 응답에서는 생략).

| `resultCode` | 의미 | payload |
| --- | --- | --- |
| `NEXT_QUESTION` | 다음 질문이 남음(마지막 슬롯 전). ① 지역 0건 예외질문(`field: "regionRetry"`)도 **이 코드**로 내려간다 | `question`(그 단계 문항 1개, §1과 동일 형태) |
| `RESTART` | 지역 예외질문에서 "예" → 클라이언트가 `POST /start`로 처음부터 재시도(세션 삭제) | 없음(코드만) |
| `COMPLETED` | 빌더 완성 → 자동 확정(매칭 유무는 확인하지 않는다) | `diagnosisId`만(추천 매물 없음 — 클라이언트가 v2-3으로 별도 조회) |
| `TERMINATED` | 지역 예외질문에서 "아니오" → 진단 종료(세션 삭제) | 없음(코드만) |

> **매칭 0건(no-match) 결과코드는 없다.** 0건인지는 추천을 실제로 조회해야 알 수 있는 사실이라(v2-3의 빈 `content`), 서버가 확정 시점에 미리 계산해 내려보내지 않는다 — 그러려면 클라이언트가 요청하지도 않은 추천 쿼리를 서버가 돌려야 하고, 이는 "매물 조회 시점은 클라이언트가 정한다"와 배치된다. **서버가 미리 필터링하는 지점은 ① 지역 하나뿐**이며 그건 명시적으로 둔 예외다(0건인 지역으로 6단계 끝까지 진행시키는 게 낭비이므로).

### v2-1. POST `/api/v2/diagnoses/start` — 진단 시작

진단을 **처음부터** 시작하고 ① 지역 질문을 반환한다. 시작 시점은 클라이언트가 정한다.

- **인증**: 선택(게스트 허용). **게스트 세션 키의 발급 지점**이다 — 토큰 없이 호출하면 서버가 `anonymous<uuid>` 키를 발급해 그 키로 세션을 만들고 응답에 실어 준다([게스트 접근](#게스트-접근--비회원-진단-issue-181)의 "게스트 세션 키").
- **동작**: 진행 중 세션이 있어도 **무조건 버리고** 새 세션(빈 `draft`, `pendingField=region`)을 만든 뒤 ① 지역 질문을 `NEXT_QUESTION`으로 반환한다. 진단을 하다 홈으로 갔다 다시 시작하면 **서버는 기존 진단 정보를 보고 진행하지 않는다** — 언제나 처음부터다. 세션을 찾는 키는 회원이면 `userId`(기존 세션을 upsert로 교체)이고, 게스트는 **매번 새 키를 발급**하므로 교체할 기존 세션이 없다 — 이전 게스트 세션은 버려지는 것이 아니라 **서버에 남아 누적된다**(위 "게스트 데이터 보존" — 현재 TTL 인덱스가 하나도 없어 만료 정책 도입 여부부터 결정 필요다).

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 선택 | `Bearer <accessToken>`. 없으면 게스트로 처리한다(만료 토큰은 401 `TOKEN_EXPIRED`) |
| `X-Guest-Session-Id` | 불필요 | 요청에 싣지 않는다 — 게스트 세션 키는 **이 엔드포인트의 응답으로 발급**된다(보내더라도 무시하고 새 키를 발급한다) |

#### Request Body

**없다**(본문을 보내지 않는다).

#### 성공 Response — 200 OK (공통 래퍼)

```jsonc
// POST /start → ① 지역 질문
{ "success": true, "data": { "resultCode": "NEXT_QUESTION",
  "question": { "step": 1, "field": "region", "question": "Which region will you live in?",
    "select": { "type": "SINGLE", "max": 1 },
    "options": [ { "code": "SEOUL", "label": "Seoul" }, { "code": "BUSAN", "label": "Busan" }, { "code": "GYEONGGI", "label": "Gyeonggi" } ] } }, "error": null }
```

게스트(토큰 없이 호출)에게는 발급된 세션 키가 `guestSessionId`로 함께 실린다. 회원 응답에는 이 필드가 **생략**된다(`NON_NULL`).

```jsonc
// POST /start (Authorization 없음) → ① 지역 질문 + 게스트 세션 키 발급
{ "success": true, "data": { "resultCode": "NEXT_QUESTION",
  "guestSessionId": "anonymous3f2a1c8e-9b47-4d1e-8a05-2c6f7d3b1e90",
  "question": { "step": 1, "field": "region", "question": "Which region will you live in?",
    "select": { "type": "SINGLE", "max": 1 },
    "options": [ { "code": "SEOUL", "label": "Seoul" }, { "code": "BUSAN", "label": "Busan" }, { "code": "GYEONGGI", "label": "Gyeonggi" } ] } }, "error": null }
```

> `question`·`label` 표시 문자열은 §1과 동일하게 사용자 표시 언어로 번역되며(미지원 언어는 `en` 폴백), `code`는 언어와 무관하게 동일하다. **게스트는 `en` 고정**이다(users 행이 없어 표시 언어를 조회하지 않으며 `Accept-Language`도 참조하지 않는다).
>
> 클라이언트는 `guestSessionId`를 보관했다가 이후 `POST /next`와 `GET /api/v2/diagnoses/{id}/recommendations`에 **`X-Guest-Session-Id` 헤더**로 에코해야 한다. `/start`를 다시 부르면 **항상 새 키가 발급된다** — 게스트 키는 진단 1회 흐름 단위이며, 이전 키로 만든 세션은 교체되지 않고 남는다([게스트 접근](#게스트-접근--비회원-진단-issue-181)의 "게스트 데이터 보존" — 현재 TTL 인덱스가 하나도 없어 만료 정책 **도입 여부와 수치가 결정 필요**다).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보냄. **토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`(401)는 이 엔드포인트에서 발생하지 않는다** |

### v2-2. POST `/api/v2/diagnoses/next` — 현재 문항 답 적용

현재 문항의 답 **1개**를 보내면 서버가 답을 진행 세션에 적용하고 **다음에 할 일**을 결과코드로 돌려준다. `field`·`code`·`codes`·`min/max` 규약은 v1 §2(`POST /answers`)의 `AnswerRequest`와 동일하며, 지역 예외질문 응답만 `{ "field": "regionRetry", "code": "YES" | "NO" }`로 보낸다. **답(`field`)은 반드시 있어야 한다** — 무답 호출은 `INVALID_INPUT`이다.

- **인증**: 선택(게스트 허용). 게스트는 `/start`가 발급한 키를 **`X-Guest-Session-Id` 헤더로 반드시 에코**해야 한다 — 이 헤더가 게스트의 진행 세션을 찾는 유일한 키다.
- **동작**: (1) 진행 세션을 조회한다(회원은 `userId`, 게스트는 `guestSessionId` 기준) — 없으면 `400 DIAGNOSIS_SESSION_NOT_FOUND`. (2) 답(`field`)이 없으면 `INVALID_INPUT`. (3) **`field`가 `pendingField`(서버가 직전에 낸 문항의 field)와 다르면 `INVALID_INPUT`** — 정본 슬롯 문항과 예외질문이 같은 규칙으로 검증된다. (4) `pendingField`가 `regionRetry`이면 예/아니오만 처리해 `RESTART`(예) 또는 `TERMINATED`(아니오)를 반환한다(둘 다 세션 삭제·`draft` 미변경). (5) 정본 슬롯 field이면 답을 진행 세션에 적용한다. (6) 방금 ① 지역을 답했으면 매칭 존재 확인을 하고, 0건이면 `regionRetry` 문항을 `NEXT_QUESTION`으로 반환하며 `pendingField=regionRetry`로 저장한다(서버가 미리 필터링하는 유일한 지점). (7) 아니면 **정본 순서상 다음 슬롯** 문항을 `NEXT_QUESTION`으로 내고, 방금 답한 게 마지막 슬롯(⑥ `arcStatus`)이면 자동 확정 후 `COMPLETED`를 반환한다(매칭을 조회하지 않으므로 매칭 유무와 무관하게 `COMPLETED`다).
- **세션 없이 `/next`**: 진행 중 세션이 없으면 **`400 DIAGNOSIS_SESSION_NOT_FOUND`** 를 반환한다(앱 재시작·터미널 이후 재전송·만료). 서버가 임의로 흐름을 되살리거나 새로 시작하지 않으며, 클라이언트가 `POST /start`로 복구한다. **게스트가 `X-Guest-Session-Id`를 빠뜨렸거나 다른 키로 보낸 경우도 같은 코드·같은 복구 절차**다 — 남의 세션에 닿지 않는다.

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 선택 | `Bearer <accessToken>`. 없으면 게스트로 처리한다(만료 토큰은 401 `TOKEN_EXPIRED`) |
| `X-Guest-Session-Id` | 조건부 | 게스트 필수 — `POST /start` 응답으로 받은 `anonymous<uuid>` 키. 없거나 남의 키면 400 `DIAGNOSIS_SESSION_NOT_FOUND`. 회원은 보내지 않는다 |

#### Request Body (래퍼 없이)

현재 문항 답 1개(v1 §2 `AnswerRequest`와 동일 구조). 지역 예외질문 응답은 `regionRetry`로 보낸다.

```jsonc
// 일반 단계 답(§2와 동일) — 예: ② 입국 목적
{ "field": "purpose", "code": "STUDY" }

// ① 지역 0건 예외질문 응답 — 예=재시도 / 아니오=진단 종료
{ "field": "regionRetry", "code": "YES" }
```

#### 성공 Response — 200 OK (공통 래퍼) — `resultCode`별

```jsonc
// POST /next { "field":"region", "code":"BUSAN" } — 그 지역 매물 0건 → 예외질문(일반 질문)
{ "success": true, "data": { "resultCode": "NEXT_QUESTION",
  "question": { "step": 1, "field": "regionRetry", "question": "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?",
    "select": { "type": "SINGLE", "max": 1 },
    "options": [ { "code": "YES", "label": "예" }, { "code": "NO", "label": "아니오" } ] } }, "error": null }
```

```jsonc
// POST /next { "field":"regionRetry", "code":"YES" } → 재시도(클라가 /start를 다시 건다)
{ "success": true, "data": { "resultCode": "RESTART" }, "error": null }

// POST /next { "field":"regionRetry", "code":"NO" } → 진단 종료
{ "success": true, "data": { "resultCode": "TERMINATED" }, "error": null }
```

```jsonc
// POST /next { "field":"arcStatus", "code":"ARC_ISSUED" } (⑥ 마지막) → 자동 확정
// 매칭 유무와 무관하게 항상 COMPLETED다(서버는 이 시점에 추천을 조회하지 않는다).
{ "success": true, "data": { "resultCode": "COMPLETED", "diagnosisId": 1024 }, "error": null }
// 이후 매물은 클라가 시점을 정해 조회: GET /api/v2/diagnoses/1024/recommendations?page=0&size=20
// 그 응답의 content가 비어 있으면 그게 곧 no-match다(제안 없음).
```

```jsonc
// 세션 없이 /next
{ "success": false, "data": null, "error": { "code": "DIAGNOSIS_SESSION_NOT_FOUND", "message": "진행 중인 진단이 없습니다. 진단을 다시 시작해 주세요." } }
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `DIAGNOSIS_SESSION_NOT_FOUND` | 진행 중 세션 없이 `/next`(앱 재시작·터미널 이후 재전송·만료). 게스트가 `X-Guest-Session-Id`를 빠뜨렸거나 다른 키로 보낸 경우 포함 → 클라이언트가 `POST /start`로 복구 |
| 400 | `INVALID_INPUT` | 답(`field`) 없음, 현재 단계와 맞지 않는 `field`, 미정의 enum, `regionRetry` 규칙 위반(`code`가 `YES`/`NO` 아님), 자동 확정 시 저장된 답 재검증 실패 등 + `errors[]` |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치(검증 이전) |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보냄. **토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`(401)는 이 엔드포인트에서 발생하지 않는다** |

---

### v2-3. GET `/api/v2/diagnoses/{diagnosisId}/recommendations` — 진단 결과 추천 매물

확정 진단(`COMPLETED`로 받은 `diagnosisId`)의 추천 매물·지도 좌표를 조회한다. **이 호출 자체가 "매물을 받겠다"는 클라이언트의 결정**이며, 시점·페이지·정렬을 클라이언트가 정한다.

v1 §7(`GET /api/v1/diagnoses/{id}/recommendations`)과 필터·매핑·페이지 계약이 같고 **다른 점은 하나** — 0건일 때의 **조정 제안 문구·액션이 없다**. v1은 0건이면 `suggestions { reason: "NO_MATCH", message, actions[] }`로 **사유와 제안을 한 덩어리로** 주는데, v2는 제안 기능을 쓰지 않으므로 그 덩어리에서 **사유만** 떼어 최상위 `resultCode`로 둔다 — 제안을 안 쓰는 것과 사유를 안 주는 것은 다르다. v1의 `suggestions`는 v1 전용으로 그대로 유지된다.

`resultCode`는 **항상** 실린다(UPPER_SNAKE enum, 에러 아님):

| `resultCode` | 의미 |
| --- | --- |
| `MATCHED` | 조건에 맞는 매물이 있음(`content` 비어 있지 않음) |
| `NO_MATCH` | 조건에 맞는 매물이 0건(`content`·`markers` 빈 목록, 조정 제안 없음) |

> 흐름 응답(`FlowResultCode`)에 `NO_MATCH`가 **없는** 것과 헷갈리지 않도록: 흐름은 아직 추천을 조회하기 **전**이라 0건인지 모르고(알려면 클라가 요청하지 않은 쿼리를 서버가 돌려야 한다), 여기는 조회를 마친 **뒤**라 안다. 그래서 no-match는 이 응답의 결과코드로만 표현된다.

- **인증**: 선택(게스트 허용). 본인 소유 진단만 — 타인 `403 FORBIDDEN`, 미존재 `404 DIAGNOSIS_NOT_FOUND`. 게스트는 `X-Guest-Session-Id`가 **필수**이며 소유는 신원 종류·값이 모두 일치할 때만 인정된다(게스트↔회원 교차 조회는 양방향 모두 403 — [게스트 접근](#게스트-접근--비회원-진단-issue-181)의 소유권 규칙 표).
- **응답 계약은 회원/게스트 동일**: 추천은 `listing` 공개 query를 부르는데 그 인터페이스가 신원을 받지 않아 `listing` 모듈 변경이 0건이다. `resultCode`·`content`·`markers`·`page`의 형태·의미가 같고, 게스트는 label 언어만 `en`이다.

#### Path · Query

| 이름 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- |
| `diagnosisId` | 필수 | — | `COMPLETED` 응답으로 받은 확정 진단 식별자 |
| `page` | 선택 | `0` | 0-base 페이지 번호 |
| `size` | 선택 | `20` | 페이지 크기(1~100) |
| `sort` | 선택 | `recommended,desc` | `recommended`·`price`·`distance` + `,asc`/`,desc` |

#### 성공 Response — 200 OK (공통 래퍼)

```jsonc
// 매칭 있음 — MATCHED + content/markers/page (§7과 동일 형태, suggestions 없음)
{
  "success": true,
  "data": {
    "resultCode": "MATCHED",
    "content": [ /* §7과 동일: type·conditions는 {code,label}, title·label은 사용자 언어 */ ],
    "markers": [ { "listingId": "6858e2000000000000000001", "lat": 37.555134, "lng": 126.936893 } ],
    "page": { "number": 0, "size": 20, "totalElements": 12, "totalPages": 1, "hasNext": false }
  },
  "error": null
}
```

```jsonc
// 매칭 0건 — NO_MATCH(조정 제안 문구·액션 없음, 에러 아님)
{
  "success": true,
  "data": {
    "resultCode": "NO_MATCH",
    "content": [],
    "markers": [],
    "page": { "number": 0, "size": 20, "totalElements": 0, "totalPages": 0, "hasNext": false }
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `page`/`size` 범위 위반, 허용되지 않은 `sort` 키·방향 |
| 403 | `FORBIDDEN` | 타인 소유 진단. 게스트↔회원 교차 조회와 게스트 키 미전송도 여기에 해당한다 |
| 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않음 |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보냄. **토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`(401)는 이 엔드포인트에서 발생하지 않는다** |

---

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `INTERNAL_ERROR` 등)는 [error-response-guide](../error-response-guide.md) §4를 따르며 여기서 재정의하지 않는다. 아래는 본 기능 고유 코드만 정의한다. prefix는 `DIAGNOSIS`.

| code | status | 의미 |
| --- | --- | --- |
| `DIAGNOSIS_NOT_FOUND` | 404 | 요청한 진단이 존재하지 않음 |
| `DIAGNOSIS_SESSION_NOT_FOUND` | 400 | 진행 중인 v2 흐름 세션 없이 `POST /api/v2/diagnoses/next`가 옴(앱 재시작·터미널 이후 재전송·만료, **게스트의 `X-Guest-Session-Id` 미전송·불일치 포함**) — 클라이언트가 `POST /api/v2/diagnoses/start`로 복구한다 |

> 게스트 개방으로 신규 도메인 에러 코드를 만들지 않는다 — 세션 키 문제는 기존 `DIAGNOSIS_SESSION_NOT_FOUND`(400), 소유권 위반은 공통 `FORBIDDEN`(403)에 흡수된다([게스트 접근](#게스트-접근--비회원-진단-issue-181)). **`UNAUTHENTICATED`(401)가 발생하지 않는 것은 게스트에게 연 v2 3개 엔드포인트(v2-1~v2-3)뿐**이며 거기서는 `TOKEN_EXPIRED`(401)만 남는다 — 만료 토큰은 게스트로 강등하지 않기 때문이다. **회원 전용으로 유지되는 v1 §1~§7은 토큰이 없거나 위조면 그대로 `UNAUTHENTICATED`(401)** 다.
>
> 타인 진단 접근은 공통 `FORBIDDEN`(403), 입력 검증 실패(enum 불일치·필수값 누락·조건 4개 이상·월세 범위 위반(`monthlyRentMin`/`monthlyRentMax` 음수 또는 `monthlyRentMin > monthlyRentMax`)·대학/지역 조건부 필수 누락·입국 목적과 대학/지역 선택 불일치·페이지 파라미터 범위·잘못된 `sort` 키)는 공통 `INVALID_INPUT`(400) + `errors[]`를 그대로 사용한다. 진단 도메인에서 별도 검증 코드를 만들지 않는다.
