# 게이미피케이션 (퀴즈) API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

외국인 세입자의 학습용 퀴즈다. 요청마다 활성 퀴즈 풀에서 **랜덤 4지선다 1개**를 사용자 언어로 번역해 조회하고, 사용자가 보기를 선택해 제출하면 서버가 저장된 정답과 대조해 즉시 채점한다. 제출 기록·포인트가 없는 **무상태(stateless)** 설계로, 같은 퀴즈를 **무제한 반복**할 수 있다.

- **정답 판정은 서버 전용**: 저장된 정답(`correctChoice`)과 대조해 판정한다. 정답·해설은 **조회 응답에 포함하지 않으며**, 오답 채점 응답에서만 `correctChoice`와 `explanation`을 공개한다.
- **무상태(stateless)**: 제출 기록·포인트가 없다. 하루 1회 제한·`(userId, quizDate)` 유니크 제약이 없으며, 채점은 멱등하고 무제한 반복 가능하다(같은 요청을 몇 번 보내도 동일 결과).
- **다국어 번역 기반**: 퀴즈의 `question`·각 보기 `text`·`explanation`은 퀴즈 도큐먼트 내부의 **인라인 언어-키 맵**(`{ "en": "...", "ja": "...", "ko": "..." }`)으로 저장한다. 표시 언어는 `user` 모듈의 공개 query(`getLanguage(userId)`)를 동기 호출해 취득하며, `user`가 등록 국가(`countries.lang`)로 도출한다. 사용자 언어 키가 없으면 영어(`en`)로 폴백한다(에러 아님). 보기 키 `A`~`D`는 **언어와 무관하게 동일**하며(채점은 키로 수행), 번역되지 않는다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md)).
- **대상/인증**: 외국인 세입자(`userType=TENANT`, `status=ACTIVE`, `ROLE_USER`) 전용이다. 모든 엔드포인트는 인증 필수다.

> **인증·대상**: `/api/v1/quizzes/**`는 `hasRole("USER")`(ACTIVE)로 게이팅되며, 응용 계층에서 `userType=TENANT`를 검사한다 — 비-ACTIVE는 `403 AUTH_ONBOARDING_REQUIRED`, 세입자가 아니면 `403 FORBIDDEN`으로 거부한다.

### 핵심 개념·enum

| 개념 | 값 | 설명 |
| --- | --- | --- |
| 보기 키 `selectedChoice` / `correctChoice` | `A`, `B`, `C`, `D` | 4지선다 보기 식별 키. 단일 대문자이며, 요청 시 이 네 값 중 하나여야 한다(그 외 값은 검증 실패). **언어 불변** — 채점은 키로 수행한다 |

- 보기 키 `A`~`D`는 4지선다 식별용 단일 대문자 키이며, 의미를 갖는 도메인 enum이 아니다. 번역과 무관하게 동일하고, 채점은 이 키로 수행한다.
- 다국어: `question`·보기 `text`·`explanation`은 인라인 언어-키 맵에 저장되며, 표시 언어는 `getLanguage(userId)`(등록 국가 `countries.lang`)로 취득한다. 사용자 언어 키가 없으면 영어(`en`)로 폴백한다(에러 아님, [ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md)).

---

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/quizzes/random` | 랜덤 퀴즈 1개 조회(사용자 언어 번역, 정답·해설 미포함) | 필수(ACTIVE 세입자) | 200 |
| POST | `/api/v1/quizzes/{quizId}/answer` | 정답 제출·즉시 채점(무상태, 오답 시 정답·해설 반환) | 필수(ACTIVE 세입자) | 200 |

---

## 상세

### 1. GET `/api/v1/quizzes/random` — 랜덤 퀴즈 조회

퀴즈 콘텐츠 풀(MongoDB 카탈로그)에서 활성 퀴즈 1개를 **무작위로 선정(SELECTION)** 해 반환한다(동적 생성이 아니라 기존 퀴즈 중 하나를 고르는 것 — **(확인 필요)**). 반환 문제는 사용자 언어로 번역되며, 정답(`correctChoice`)·해설(`explanation`)은 **포함하지 않는다**.

- **인증**: 필수(ACTIVE 세입자)
- Path 파라미터: 없음
- Query 파라미터: 없음
- Request Body: 없음

> **인증·대상**: `/api/v1/quizzes/**`는 `hasRole("USER")`(ACTIVE)로 게이팅되며, 응용 계층에서 `userType=TENANT`를 검사한다 — 비-ACTIVE는 `403 AUTH_ONBOARDING_REQUIRED`, 세입자가 아니면 `403 FORBIDDEN`으로 거부한다.

#### 성공 Response — 200 OK

`question`과 각 보기 `text`는 서버가 퀴즈 도큐먼트의 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en` 폴백) 채운 결과다. 보기 키 `A`~`D`는 번역과 무관하게 동일하다. 정답·해설은 내려가지 않는다.

```jsonc
{
  "success": true,
  "data": {
    "quizId": 4021,
    "question": "한국에서 전세 계약 시 임차인을 보호하는 제도는?",
    "choices": [
      { "key": "A", "text": "확정일자" },
      { "key": "B", "text": "관리비 정산" },
      { "key": "C", "text": "중도금 대출" },
      { "key": "D", "text": "재산세 납부" }
    ]
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료/위조 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 비-ACTIVE(온보딩 미완료) 접근 |
| 403 | `FORBIDDEN` | 세입자(`TENANT`)가 아님(임대인 등) |
| 404 | `QUIZ_NOT_FOUND` | 활성 퀴즈 풀이 비어 사용 가능한 퀴즈가 없음 |

---

### 2. POST `/api/v1/quizzes/{quizId}/answer` — 정답 제출·즉시 채점(무상태)

사용자가 선택한 보기를 제출한다. 서버가 저장된 정답(`correctChoice`)과 대조해 정답 여부를 판정한다. 채점은 **무상태**로, 제출 기록을 남기지 않고 멱등하며 무제한 반복할 수 있다. 오답인 경우에만 정답과 해설을 반환한다.

- **인증**: 필수(ACTIVE 세입자)

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `quizId` | Long | 필수 | 채점 대상 퀴즈 ID |

Query 파라미터: 없음

#### Request Body

```jsonc
{
  "selectedChoice": "B"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `selectedChoice` | string | 필수 | 보기 키 `A`/`B`/`C`/`D` 중 하나(단일 대문자). 그 외 값·빈 값·누락 시 `INVALID_INPUT` |

#### 성공 Response — 200 OK

**정답인 경우** — `correct=true`. 정답·해설은 노출하지 않는다.

```jsonc
{
  "success": true,
  "data": {
    "quizId": 4021,
    "selectedChoice": "A",
    "correct": true
  },
  "error": null
}
```

> **(확인 필요)** 정답 응답에도 `explanation`(해설)을 함께 내려줄지 여부 — 현재는 노출하지 않는다.

**오답인 경우** — `correct=false`이며 정답 키(`correctChoice`)와 오답 사유 해설(`explanation`, 사용자 언어 번역)을 함께 반환한다.

```jsonc
{
  "success": true,
  "data": {
    "quizId": 4021,
    "selectedChoice": "B",
    "correct": false,
    "correctChoice": "A",
    "explanation": "확정일자를 받으면 대항력과 우선변제권을 확보할 수 있습니다."
  },
  "error": null
}
```

> `explanation`은 퀴즈 도큐먼트의 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en` 폴백) 채운 오답 사유 해설이다. `correctChoice`는 언어와 무관한 보기 키다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `selectedChoice` 누락/빈값/허용 외 값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료/위조 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 비-ACTIVE(온보딩 미완료) 접근 |
| 403 | `FORBIDDEN` | 세입자(`TENANT`)가 아님(임대인 등) |
| 404 | `QUIZ_NOT_FOUND` | 경로의 `quizId`가 존재하지 않음 |

---

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `INTERNAL_ERROR` 등)는 [error-response-guide](../error-response-guide.md) §4의 정의를 그대로 쓰며 여기서 재정의하지 않는다. 5xx(`INTERNAL_ERROR` 등)는 전 엔드포인트에 공통 적용되므로 개별 표에 반복 기재하지 않는다. `AUTH_ONBOARDING_REQUIRED`(403, 비-ACTIVE)는 auth 도메인 코드([01-auth-onboarding](01-auth-onboarding.md)), `FORBIDDEN`(403, 세입자 아님)은 공통 코드로 각각 정의된 곳을 따르며 여기서 재정의하지 않는다(교차 참조). 아래는 본 도메인 고유 코드만 정의한다(prefix `QUIZ`).

| code | status | 의미 |
| --- | --- | --- |
| `QUIZ_NOT_FOUND` | 404 | 경로의 `quizId`가 존재하지 않거나, 랜덤 조회 시 활성 퀴즈 풀이 비어 사용 가능한 퀴즈가 없음 |
