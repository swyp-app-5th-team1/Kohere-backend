# 생활 팁 (주제별 생활 정보) API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md) — 8. 생활 팁(US-8-1/US-8-2/US-8-3)

## 개요

온보딩을 마친 세입자(외국인)가 한국 생활에 필요한 정보를 **주제(topic)** 별로 묶어 조회하는 **읽기 전용 큐레이션** 기능이다. 사용자는 먼저 주제 목록을 보고(US-8-1), 특정 주제를 고르면 그 주제에 속한 생활 팁(**제목 · 내용 · 사진**) 전체 리스트를 받는다(US-8-2). 한 주제에는 여러 개의 제목-내용-사진 항목이 들어갈 수 있다(주제 : 팁 = **1 : N**). 콘텐츠는 운영이 시드로 적재하는 큐레이션 콘텐츠이며 사용자 작성·수정·좋아요·신고가 없다(UGC인 커뮤니티(05)와 구분된다).

- **번역이 이 기능의 바탕이다**: 주제명(`name`)·제목(`title`)·내용(`content`) 표시 텍스트는 사용자의 **등록 국가→언어**로 번역해 내려준다(US-8-3). 진단 i18n과 **완전히 동일한 전략**을 재사용하며 별도 메커니즘을 만들지 않는다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md), US-2-6). 표시 문자열은 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드되고, 서버가 `user` 모듈 공개 query `getLanguage(userId)`로 취득한 언어 키로 문자열을 골라 조립하며, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님).
- **식별자·사진은 언어 무관 불변**: 주제·팁의 식별자(`code`/`id`)와 사진 `imageUrl`은 언어와 무관하게 동일하고, 표시 텍스트만 언어별이다. 응답 스키마도 언어와 무관하게 동일하다(서버가 언어 문자열만 채운다).
- **비페이지**: 두 목록 모두 **고정·소규모 카탈로그**라 페이지네이션 없이 전체 배열을 한 번에 반환한다(페이지 객체 없음 — api-design-guide §4 목록 규약 미적용, 신고 사유 목록 US-7-3과 동일 성격).
- **읽기 전용**: 생활 팁 도메인은 조회만 제공하며, 발행/구독하는 도메인 이벤트가 없다(1차 MVP 이후 홈 부가 기능).
- **저장소**: 문서형·가변 스키마·언어-키 맵 임베드 특성상 **MongoDB**에 둔다([ADR-0005](../../adr/0005-polyglot-persistence.md) 폴리글랏, [ADR-0028](../../adr/0028-diagnosis-questions-catalog-store.md) 진단 카탈로그 저장 방식과 정합). 카탈로그는 Mongock `@ChangeUnit`(모듈별)로 `lifeTipTopics`/`lifeTips`에 시드 적재한다(진단 카탈로그 시드와 동일 방식, [ADR-0032](../../adr/0032-mongodb-migration-runner.md)).

공통 규약:

- 경로 프리픽스 `/api/v1`, 경로는 kebab-case, JSON 필드는 lowerCamelCase.
- 식별자·enum 키는 UPPER_SNAKE 문자열(주제 `code`), 시각은 UTC ISO-8601, 금액은 KRW 정수. 본 도메인에는 시각·금액 필드가 없다.
- 모든 응답은 공통 래퍼 `{ success, data, error }`([ADR-0004](../../adr/0004-api-response-envelope.md)). 인증은 `Authorization: Bearer <accessToken>`.
- 표시 언어 결정은 `Accept-Language` 헤더·토큰 클레임에 의존하지 않고 **사용자 등록 국가**(온보딩 수집값)에서 도출한다. 상세는 아래 [i18n(번역) 절](#i18n번역--adr-0029-재사용)을 참조한다.

### 핵심 개념·리소스

| 개념 | 형태 | 설명 |
| --- | --- | --- |
| 주제 `code` (`LifeTipTopic._id`) | UPPER_SNAKE string | 언어 무관 불변 식별자. 예: `MOVING_IN`, `ADMINISTRATION`, `TRANSPORT`, `FINANCE`, `HOUSING`. 노출 순서(`order`) 오름차순으로 정렬된다. US-8-2에서 특정 주제의 팁을 지정하는 path 키로 쓰인다 |
| 팁 `id` (`LifeTip._id`) | ObjectId hex string | 언어 무관 불변 팁 식별자. 하나의 주제(`topicCode`)에 속한다(주제 : 팁 = 1 : N) |
| 표시 텍스트 `name`/`title`/`content` | 번역 문자열 | 도큐먼트 안 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en` 폴백) 채운 결과. 응답에는 언어 문자열 하나만 담긴다 |
| `imageUrl` | string \| null | 팁 사진(언어 무관). 사진이 없는 팁은 `null`(또는 생략) |

---

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/life-tips/topics` | 생활 팁 주제 전체 목록(노출 순서, 등록 국가 언어로 번역). 비페이지 | 필수 | 200 |
| GET | `/api/v1/life-tips/topics/{topicCode}/tips` | 해당 주제의 팁 전체(제목·내용·사진, 노출 순서, 등록 국가 언어로 번역). 비페이지 | 필수 | 200 |

> **인증 = ROLE_USER(ACTIVE 세입자)**. 표시 언어를 등록 국가에서 도출하려면 온보딩으로 국가·언어가 확정된 사용자여야 하므로, 대상 액터는 **ACTIVE 상태(온보딩 완료)의 세입자(`userType=TENANT`)** 이고 모든 조회는 정식 인증(`ROLE_USER`)을 요구한다(진단 보호 엔드포인트와 동일 게이트). 온보딩 미완료(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰은 `403 AUTH_ONBOARDING_REQUIRED`, 인증 누락/위조는 `401 UNAUTHENTICATED`, 만료는 `401 TOKEN_EXPIRED`다. 구현 시 [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)의 정식 인증(ROLE_USER) 티어에 `/api/v1/life-tips/**`를 등록한다 — 기본 `anyRequest().authenticated()`는 온보딩 스코프 토큰도 통과시켜 ACTIVE 게이트가 아니기 때문이다.

---

## 상세

### 1. GET `/api/v1/life-tips/topics` — 생활 팁 주제 목록 조회

생활 팁이 어떤 주제로 나뉘어 있는지 **주제 전체 목록**을 노출 순서(`order` 오름차순)대로 반환한다(US-8-1). 주제(`LifeTipTopic`)는 운영이 적재한 큐레이션 카탈로그이며, 각 주제는 언어 무관 식별 `code`(UPPER_SNAKE)와 표시명 `name`(언어-키 맵)을 가진다. 서버는 `user`의 `getLanguage(userId)`로 표시 언어를 정하고 그 언어 키(없으면 `en`)로 `name`을 채워 노출 순서대로 반환한다. 주제 수는 고정·소규모라 **페이지네이션 없이 전체 배열을 한 번에** 반환한다.

- **인증**: 필수(ROLE_USER = ACTIVE 세입자).
- **동작**: `lifeTipTopics` 컬렉션의 전체 주제를 `order` 오름차순으로 정렬해 `{ code, name }` 목록으로 조립한다. `code`는 도큐먼트 `_id`(UPPER_SNAKE)를 그대로 싣고, `name`은 도큐먼트 `name` 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en` 폴백) 채운다.
- **번역(US-8-3)**: 반환 표시명(`name`)만 사용자 표시 언어로 번역한다. `code`는 언어와 무관하게 동일하다. 표시 언어는 `Accept-Language` 헤더가 아니라 `user`의 등록 국가(`countries.lang`)에서 도출한다(아래 [i18n 절](#i18n번역--adr-0029-재사용)).
- **페이지네이션**: 없음. 고정·소규모 카탈로그라 페이지 객체 없이 전체 배열을 반환한다(api-design-guide §4 비적용, US-7-3과 동일 성격).

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 필수 | `Bearer <accessToken>`(ROLE_USER) |

#### Path / Query 파라미터

없음.

#### Request Body

없음.

#### 성공 Response — 200 OK (공통 래퍼)

표시명(`name`)은 등록 국가가 일본인 사용자 예시(미지원 언어면 `en` 폴백). `code`는 번역과 무관하게 동일하다. 페이지 객체 없이 `topics[]` 전체 배열이 노출 순서대로 담긴다.

```jsonc
{
  "success": true,
  "data": {
    "topics": [
      { "code": "MOVING_IN",      "name": "入居・引っ越し" },
      { "code": "ADMINISTRATION", "name": "行政手続き" },
      { "code": "TRANSPORT",      "name": "交通" },
      { "code": "FINANCE",        "name": "銀行・金融" },
      { "code": "HOUSING",        "name": "住まい" }
    ]
  },
  "error": null
}
```

> `topics[]`는 `order` 오름차순으로 정렬된 전체 주제다(페이지네이션 없음). `code`는 언어 무관 불변 식별자(UPPER_SNAKE)이며, `name`만 서버가 `lifeTipTopics` 도큐먼트의 `name` 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en`) 채운 번역 문자열이다. 미지원 언어면 같은 `code`에 대해 `name`이 영어로 폴백된다(에러 아님). 주제가 0건이면 `topics: []`(에러 아님).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰으로 호출(정식 인증 ROLE_USER=ACTIVE만 허용) |

---

### 2. GET `/api/v1/life-tips/topics/{topicCode}/tips` — 특정 주제의 생활 팁 목록 조회

고른 주제(`topicCode`)에 속한 생활 팁(**제목 · 내용 · 사진**) 전체를 노출 순서(`order` 오름차순)대로 반환한다(US-8-2). 생활 팁(`LifeTip`)은 하나의 주제(`topicCode`)에 속하며(주제 : 팁 = 1 : N), `title`·`content`는 언어-키 맵으로 임베드되고 `imageUrl`은 언어 무관(사진)이다. 서버는 그 주제의 팁 전체를 조립해 각 팁의 `title`·`content`를 사용자 언어 키(없으면 `en`)로 채우고 `imageUrl`은 그대로 싣는다. 주제당 팁 수가 제한적이므로 **페이지네이션 없이 전체 리스트를 한 번에** 반환한다("해당 주제에 맞는 제목-내용-사진의 모든 리스트").

- **인증**: 필수(ROLE_USER = ACTIVE 세입자).
- **동작**: `lifeTipTopics._id == topicCode` 주제의 존재를 확인한 뒤, `lifeTips`에서 `topicCode`가 일치하는 팁을 `order` 오름차순(복합 인덱스 `{ topicCode: 1, order: 1 }`)으로 조회해 `{ id, title, content, imageUrl }` 목록으로 조립한다. `topicCode` 참조는 애플리케이션 레벨 조인이며 DB 조인을 쓰지 않는다(폴리글랏 규약).
- **번역(US-8-3)**: 각 팁의 `title`·`content`만 사용자 표시 언어로 번역한다. `id`(팁 식별자)와 `imageUrl`(사진)은 언어와 무관하게 동일하다. 표시 언어는 `Accept-Language` 헤더가 아니라 `user`의 등록 국가(`countries.lang`)에서 도출한다(아래 [i18n 절](#i18n번역--adr-0029-재사용)).
- **사진 없는 팁**: 사진이 없는 팁은 `imageUrl`을 `null`(또는 생략)로 싣고 나머지 필드(`title`·`content`)는 정상 노출한다.
- **존재하지 않는 주제**: 경로의 `{topicCode}`가 `lifeTipTopics`에 없으면 `404 LIFE_TIP_TOPIC_NOT_FOUND`(신규 도메인 에러코드, `*_NOT_FOUND` 규약)를 반환한다.
- **페이지네이션**: 없음. 고정·소규모 카탈로그라 페이지 객체 없이 전체 배열을 반환한다(api-design-guide §4 비적용).

#### Headers

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 필수 | `Bearer <accessToken>`(ROLE_USER) |

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `topicCode` | string | 필수 | 주제 코드(UPPER_SNAKE, `lifeTipTopics._id` 참조). US-8-1 응답의 `topics[].code`를 그대로 사용한다. 카탈로그에 없으면 `404 LIFE_TIP_TOPIC_NOT_FOUND` |

Query 파라미터: 없음.

Request Body: 없음.

#### 성공 Response — 200 OK (공통 래퍼)

제목·내용(`title`·`content`)은 등록 국가가 일본인 사용자 예시(미지원 언어면 `en` 폴백). `id`·`imageUrl`은 번역과 무관하게 동일하다. 아래는 `GET /api/v1/life-tips/topics/MOVING_IN/tips` 호출 예시로, 팁 3건이 `order` 순으로 담기며 마지막 팁은 사진이 없어 `imageUrl`이 `null`이다.

```jsonc
{
  "success": true,
  "data": {
    "tips": [
      {
        "id": "6858e2000000000000000101",
        "title": "住民登録（外国人登録）の手続き",
        "content": "入居後14日以内に、お住まいの区役所（区庁）で外国人登録を行ってください。パスポート・在留カード・賃貸借契約書が必要です。",
        "imageUrl": "https://cdn.kohere.app/life-tips/6858e2000000000000000101/cover.jpg"
      },
      {
        "id": "6858e2000000000000000102",
        "title": "電気・ガス・水道の開通",
        "content": "公共料金は管理事務所または各供給会社に連絡して名義変更・開通します。管理費に含まれる場合もあるため契約内容を確認してください。",
        "imageUrl": "https://cdn.kohere.app/life-tips/6858e2000000000000000102/cover.jpg"
      },
      {
        "id": "6858e2000000000000000103",
        "title": "ゴミの分別ルール",
        "content": "韓国では従量制ごみ袋（종량제봉투）と食品ごみの分別が必須です。地域ごとに収集日が異なるので掲示を確認してください。",
        "imageUrl": null
      }
    ]
  },
  "error": null
}
```

> `tips[]`는 해당 주제의 팁 전체를 `order` 오름차순으로 담은 배열이다(페이지네이션 없음). `id`(팁 식별자)와 `imageUrl`(사진)은 언어 무관 불변이며, `title`·`content`만 서버가 `lifeTips` 도큐먼트의 인라인 언어-키 맵에서 사용자 언어 키를 골라(없으면 `en`) 채운 번역 문자열이다. 미지원 언어면 같은 `id`에 대해 `title`·`content`가 영어로 폴백된다(에러 아님). 사진이 없는 팁은 `imageUrl`이 `null`(또는 생략)이다. 주제는 존재하나 팁이 0건이면 `tips: []`(에러 아님).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰으로 호출(정식 인증 ROLE_USER=ACTIVE만 허용) |
| 404 | `LIFE_TIP_TOPIC_NOT_FOUND` | 경로의 `topicCode`가 카탈로그(`lifeTipTopics`)에 존재하지 않음 |

---

## i18n(번역) — ADR-0029 재사용

생활 팁의 번역 전략은 **진단 i18n**([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md), US-2-6)과 **완전히 동일**하며 별도 메커니즘·메시지 컬렉션·번역 키를 만들지 않는다(US-8-3). 진단(02) 스펙의 표시 라벨 번역 방식을 그대로 재사용한다.

- **번역 기준 = 사용자 등록 국가**: 표시 언어는 온보딩에서 수집한 등록 국가에서 도출한다. `Accept-Language` 헤더·토큰 클레임은 사용하지 않는다 — 사용자가 `Accept-Language`를 다른 값으로 보내도 응답 언어는 등록 국가로 결정된다.
- **표시 언어 취득**: 서버가 `user` 모듈의 **공개 query `getLanguage(userId)`를 동기 호출**해 표시 언어를 취득한다. `user`가 `countries.lang`으로 국가→언어를 도출한다([ADR-0002](../../adr/0002-inter-module-communication-via-events.md) Decision 5 — 모듈 의존 `lifetip → user` 추가, 진단 `diagnosis → user`와 동일 근거).
- **인라인 언어-키 맵 임베드**: 표시 문자열은 별도 메시지 컬렉션 없이 주제·팁 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드한다 — 주제는 `name`, 팁은 `title`·`content` 각각 언어-키 맵이다(진단 문항 `question`·옵션 `label`과 동일 방식).
- **`en` 폴백**: 서버는 사용자 언어 키로 문자열을 고르고, 그 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님).
- **식별자·사진 불변**: 주제·팁 식별자(`code`/`id`)와 `imageUrl`(사진)은 언어 무관 불변이고 표시 텍스트(`name`/`title`/`content`)만 언어별이다. **응답 스키마는 언어와 무관하게 동일**하며 서버가 언어 문자열만 채운다.

---

## 도메인 에러 코드

> 공통 코드(`UNAUTHENTICATED`, `TOKEN_EXPIRED`, `AUTH_ONBOARDING_REQUIRED`, `INTERNAL_ERROR` 등)는 [error-response-guide](../error-response-guide.md) §4의 정의를 그대로 쓰며 여기서 재정의하지 않는다. 5xx(`INTERNAL_ERROR` 등)는 전 엔드포인트에 공통 적용되므로 개별 표에 반복 기재하지 않는다. 아래는 본 도메인 고유 코드만 정의한다(prefix `LIFE_TIP`).

| code | status | 의미 |
| --- | --- | --- |
| `LIFE_TIP_TOPIC_NOT_FOUND` | 404 | 경로의 `topicCode`가 카탈로그(`lifeTipTopics`)에 존재하지 않음(`*_NOT_FOUND` 규약, `ErrorCode` 신규 등록 필요) |

> 온보딩 미완료 토큰 접근은 공통 `AUTH_ONBOARDING_REQUIRED`(403), 인증 누락/만료는 공통 `UNAUTHENTICATED`/`TOKEN_EXPIRED`(401)를 그대로 사용한다(진단 보호 엔드포인트와 동일 게이트). 생활 팁 도메인은 읽기 전용이라 입력 검증(`INVALID_INPUT`)·본문 파싱(`MALFORMED_REQUEST`)이 발생하지 않으며, 주제 미존재만 도메인 고유 코드로 둔다.
