# ADR-0035. 학습 퀴즈를 무상태 랜덤 4지선다로 재설계하고 문항 카탈로그를 MongoDB에 언어-키 맵으로 저장한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0035 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-07-02 |
| 관련 문서 | [ADR-0002](./0002-inter-module-communication-via-events.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0028](./0028-diagnosis-questions-catalog-store.md), [ADR-0029](./0029-diagnosis-i18n-strategy.md), [gamification spec](../api/specs/06-gamification.md), [US-6 시퀀스](../architecture/sequence-diagrams/06-gamification/README.md), [user-stories US-6-1·US-6-2·US-6-3](../requirements/user-stories.md) |

## Status

Proposed (2026-07-02)

> 기존 스캐폴드(specs/06-gamification.md, [domain-model](../architecture/domain-model.md) 8절, us-6-* 시퀀스 다이어그램)는 **오늘의 퀴즈(daily) + 포인트 + 제출 기록** 모델로 초안이 작성돼 있었다. 본 ADR은 그 스캐폴드된 설계·스펙을 **무상태 랜덤 학습 퀴즈**로 대체한다. 이는 선행 ADR을 뒤집는 것이 아니라 **설계/스펙 수준의 supersede**(스캐폴드 초안 교체)다 — 별도 ADR로 확정된 적 없던 스캐폴드를 정본화한다. 정본 요구는 [user-stories.md 6절(US-6-1/2/3)](../requirements/user-stories.md)이며, 본 ADR·스펙·도메인 모델·시퀀스가 여기에 정확히 정렬한다.

## Context

- 스캐폴드는 **오늘의 퀴즈(하루 1회 제한) + 정답 시 포인트 적립 + 제출 기록 영속** 모델을 구현했다 — `(userId, quizDate)` unique 제출, `QUIZ_CORRECT` 포인트, `/points/summary`·`/points/histories`, `PointHistory`, `201 Created + Location`, `QUIZ_NOT_TODAY(422)`·`QUIZ_ALREADY_SUBMITTED(409)` 등이 포함됐다.
- 제품 결정이 바뀌었다 — 게이미피케이션(포인트)은 **1차 MVP 이후로 유보**하고, 1차 MVP에는 **외국인 임차인을 위한 무상태 학습 퀴즈**만 둔다. 매 요청마다 랜덤 4지선다 1개를 사용자 언어로 **번역**해 제공하고, 사용자가 보기를 클릭하면 서버가 채점한다. **무제한 반복·무상태**이며, 하루 1회 제한·제출 기록·포인트가 모두 사라진다.
- **다국어 번역이 기본값**이다 — 대상 사용자는 한국어가 익숙하지 않은 외국인 임차인이라 문제 지문·보기·오답 사유를 사용자 언어로 보여야 한다(진단 i18n과 동일 접근성 요구, [ADR-0029](./0029-diagnosis-i18n-strategy.md)).
- 따라서 이 무상태 랜덤 학습 퀴즈에 대해 **영속(어디에 어떤 형태로)·i18n(어떻게 번역)·엔드포인트(조회/채점)·에러코드**를 한꺼번에 정합적으로 확정해야 한다. 진단 문항 카탈로그([ADR-0028](./0028-diagnosis-questions-catalog-store.md))·진단 i18n([ADR-0029](./0029-diagnosis-i18n-strategy.md))이 이미 확립한 **MongoDB 도큐먼트 카탈로그 + 인라인 언어-키 맵** 패턴을 재사용하는 것이 자연스럽다.

## Decision

**학습 퀴즈를 외국인 임차인 전용 무상태 랜덤 4지선다로 재설계하고, 문항 카탈로그를 MongoDB `quizzes` 컬렉션에 인라인 언어-키 맵으로 저장한다. 조회는 활성 풀에서 랜덤 1개를 사용자 언어로 번역해 내려주고, 채점은 저장된 정답 키와 대조하되 제출·포인트를 남기지 않는다(무상태·멱등·무제한).** 세부 정책은 다음과 같다.

1. **외국인 임차인 전용이다.** 학습 퀴즈는 `userType=TENANT`이며 `status=ACTIVE`인 사용자만 이용한다. 임대인·비활성(온보딩 미완) 사용자는 접근하지 못한다(3절 조회·채점 모두 동일 전제).
2. **`GET /api/v1/quizzes/random`이 활성 풀에서 랜덤 1개를 번역해 반환한다.** `200 OK`, `data = { quizId, question, choices: [{ key: "A|B|C|D", text }] }`로, `question`·각 `choices[].text`는 사용자 언어로 번역된 표시 문자열이다. **`correctChoice`·`explanation`은 조회 응답에 포함하지 않는다**(채점 전 정답 노출 금지).
3. **`POST /api/v1/quizzes/{quizId}/answer`가 서버 저장 정답과 대조해 채점한다.** 요청 `{ selectedChoice: "A|B|C|D" }`, 응답 `200 OK`. 서버가 해당 `quizId` 도큐먼트의 저장 `correctChoice`와 대조해 — 정답이면 `data = { quizId, selectedChoice, correct: true, explanation }`, 오답이면 `data = { quizId, selectedChoice, correct: false, correctChoice, explanation }`(`explanation` = 해설, 사용자 언어로 번역)로 응답한다. **`explanation`은 정답·오답 모두 반환**하고(#148 — 정답이어도 왜 그것이 정답인지 학습할 수 있어야 한다), **`correctChoice`는 오답에만** 반환한다(정답이면 `selectedChoice`가 곧 정답이라 불필요). **무상태·멱등·무제한**이며 **제출 기록·포인트를 남기지 않는다**(`201 Created`·`Location` 없음, 같은 요청을 반복해도 동일 결과).
4. **i18n은 진단 방식을 재사용한다([ADR-0029](./0029-diagnosis-i18n-strategy.md)).** 문제 지문(`question`)·각 보기 텍스트(`choices[].text`)·해설(`explanation`)을 문항 도큐먼트 안에 **언어 코드를 키로 하는 맵**(`Map<lang, String>`, 예: `{ "en": …, "ja": …, "ko": … }`)으로 인라인 임베드한다. 표시 언어는 `user` 모듈 공개 query `getLanguage(userId)`(`users.lang`이 있으면 그 값, 없으면 `en`; [ADR-0029](./0029-diagnosis-i18n-strategy.md) #141 개정으로 국가→언어 도출은 폐기)로 취득한다. **보기 키 `A`~`D`는 언어 불변**이며 채점은 키로 수행한다(어느 언어로 표시돼도 같은 키로 채점).
5. **영속은 MongoDB `quizzes` 문서 카탈로그다([ADR-0005](./0005-polyglot-persistence.md) 정합).** 퀴즈는 콘텐츠/문서라 진단 `diagnosisQuestions`([ADR-0028](./0028-diagnosis-questions-catalog-store.md))와 동일한 방식으로 MongoDB `quizzes` 컬렉션에 저장한다 — 각 도큐먼트가 `correctChoice`(A~D 키)와 지문·보기·오답 사유의 언어-키 맵, `active` 플래그를 담는다. **제출 테이블·포인트 테이블은 두지 않는다**(무상태 채점). 시드는 Mongock `@ChangeUnit`으로 적재하며, `active` 불리언이 랜덤 대상 풀을 게이팅한다(비활성 문항은 랜덤에서 제외).
6. **제거를 명시한다.** 다음은 재설계로 **삭제**한다 — 오늘의 퀴즈/`daily`·`today` 개념, 하루 1회 제한, `(userId, quizDate)` unique 제약, `quizDate`·`submittedAt` 필드, 모든 포인트(`QUIZ_CORRECT`, `/points/summary`·`/points/histories`, `PointHistory`, `PointReason`, `totalPoint`·`earnedPoint`·`amount`), `201 Created` + `Location`, 제출 영속, `QUIZ_NOT_TODAY(422)`·`QUIZ_ALREADY_SUBMITTED(409)`.
7. **에러코드를 재정의한다.** `QUIZ_NOT_FOUND(404)`(`quizId` 부재 또는 랜덤 조회 시 활성 풀이 비어 있음), `INVALID_INPUT(400)`(`selectedChoice`가 A~D가 아님), `MALFORMED_REQUEST(400)`, `UNAUTHENTICATED`/`TOKEN_EXPIRED(401)`, `AUTH_ONBOARDING_REQUIRED(403)`(비활성 사용자 — 온보딩 미완, [01-auth-onboarding](../api/specs/01-auth-onboarding.md) 교차 참조). `QUIZ_NOT_TODAY`·`QUIZ_ALREADY_SUBMITTED`는 **폐기**한다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. MySQL 관계형 저장 + 제출/포인트 테이블(이전 스캐폴드)** — 퀴즈·제출·포인트를 관계형으로 영속, 하루 1회·정답 포인트 적립 | 트랜잭션·집계·중복 제출 방지가 자연스럽고 게이미피케이션 확장에 유리 | **무상태·번역 학습 퀴즈 모델과 불일치** — 제출 기록·포인트·하루 1회가 모두 무상태 정책에 반하고, 콘텐츠 번역을 관계형에 두기 부적합. 게이미피케이션은 1차 MVP 이후로 유보 | 정본 요구(무상태·무제한·번역)와 근본적으로 어긋나며, 포인트는 MVP 범위 밖 |
| **B. 별도 번역 테이블/컬렉션** — 지문·보기·오답 사유를 `(quizId, lang) → text`로 평탄 저장 | 언어 추가 = 행 추가, 문항과 번역을 분리 | **진단 인라인 언어-키 맵 방식과 불일치** — 카탈로그/번역이 두 저장소로 갈려 조립 시 키 조회 한 단계가 늘고 정합·누락을 관측해야 함([ADR-0029](./0029-diagnosis-i18n-strategy.md) 대안 A와 동일 문제) | 진단과 다른 i18n 패턴을 두면 운영·조립이 이원화됨 |
| **C. 동적 생성(LLM) 퀴즈** — 요청마다 LLM으로 문항·정답·오답 사유를 생성 | 무한 다양성, 카탈로그 시드 불요 | **MVP 과범위** — 비용·지연·정답 신뢰성·번역 품질 검증 부담, 채점 기준(저장 정답) 자체가 사라짐 | MVP에는 과도하며, 무상태 채점은 저장된 정답 키가 있어야 성립 |
| **D. MongoDB 인라인 언어-키 맵 카탈로그(채택)** — `quizzes` 도큐먼트에 `correctChoice` + 지문·보기·오답 사유 언어-키 맵 + `active`, 무상태 채점 | 진단 카탈로그·i18n과 동일 패턴, 단건 조회로 번역까지 조립, 코드 배포 없이 문항·번역 변경, 제출/포인트 테이블 불요, [ADR-0005](./0005-polyglot-persistence.md)·[ADR-0028](./0028-diagnosis-questions-catalog-store.md)·[ADR-0029](./0029-diagnosis-i18n-strategy.md)와 정합 | 시드/적재 파이프라인 필요, 번역 커버리지 관측 필요, MongoDB 도입 선행 | — (채택) |

## Consequences

- **긍정**
  - 진단([ADR-0028](./0028-diagnosis-questions-catalog-store.md)·[ADR-0029](./0029-diagnosis-i18n-strategy.md))과 **동일한 카탈로그·i18n 패턴**(MongoDB 도큐먼트 + 인라인 언어-키 맵 + `getLanguage` + `en` 폴백)을 재사용해 학습·운영 부담이 낮다.
  - **무상태·멱등·무제한** 채점이라 제출 기록·중복 방지·하루 1회 상태를 관리할 필요가 없다 — 제출/포인트 테이블·`(userId, quizDate)` unique·`quizDate`·`submittedAt`이 모두 사라져 저장·정합 부담이 크게 준다.
  - 문항 지문·보기·오답 사유·신규 언어를 **코드 배포 없이** `quizzes` 도큐먼트의 언어-키 맵에서 변경·추가한다. `active` 플래그로 랜덤 풀을 코드 변경 없이 게이팅한다.
  - 보기 키 `A`~`D`가 언어 불변이라 **번역과 채점이 완전히 분리**된다 — 어느 언어로 표시돼도 같은 키로 채점된다. 조회 응답에 정답·오답 사유를 넣지 않아 채점 전 정답 노출이 없다.
  - 게이미피케이션(포인트)을 1차 MVP 밖으로 밀어내 스코프가 명확해진다(포인트는 여전히 **1차 MVP 이후** 유보).
- **부정/트레이드오프**
  - `gamification → user` **동기 의존**이 생긴다(표시 언어 query `getLanguage`). 추후 서비스 분해 시 원격 호출이 되어 가용성 결합·타임아웃 처리가 필요하다([ADR-0002 Decision 7](./0002-inter-module-communication-via-events.md)).
  - 번역이 도큐먼트에 임베드돼 **신규 언어 추가 시 각 `quizzes` 도큐먼트의 언어-키 맵을 손봐야** 한다(언어별 행 추가가 아니라 맵 키 추가). 누락 언어 키는 `en`으로 폴백되므로 커버리지를 관측해야 한다.
  - **무상태**라 사용자의 학습 이력·정답률·진척을 서버가 보유하지 않는다 — 훗날 게이미피케이션·통계를 붙이려면 제출/집계 저장을 별도로 재도입해야 한다.
  - 카탈로그 **시드/적재(Mongock)·번역 커버리지 관측**이 필요하고, MongoDB **도입 선행**이 필요하다.
- **후속 작업**
  - (완료) [specs/06-gamification.md](../api/specs/06-gamification.md)·[domain-model 8절](../architecture/domain-model.md)·[database-design 4-8절](../database/database-design.md)·[us-6-* 시퀀스 다이어그램](../architecture/sequence-diagrams/06-gamification/README.md)을 무상태 랜덤 학습 퀴즈로 갱신.
  - (완료) 스캐폴드 코드(`src/main/java/com/kohere/gamification/**`)를 무상태 랜덤 채점·`quizzes` 카탈로그 조회로 **재구현**(오늘의 퀴즈·포인트·제출 코드 제거).
  - (완료) `quizzes` 컬렉션 스키마 확정 + Mongock `@ChangeUnit` 시드(`question`·`choices[].text`·`explanation`을 언어-키 맵으로, `correctChoice`·`active` 포함).
  - (완료) `user` 모듈 표시 언어 조회 공개 query(`getLanguage`, `@NamedInterface`) 재사용 + `gamification` `allowedDependencies`에 `user :: api` 등록.
  - (완료) `SecurityConfig`의 `/api/v1/quizzes/**`를 `hasRole("USER")`(ACTIVE)로 게이팅 + 응용 계층 `userType=TENANT` 검사(`TenantOnlyException` → `403 FORBIDDEN`).
  - 이슈 #78 본문을 무상태 랜덤 학습 퀴즈로 갱신(미완).

## Validation

- **조회 계약**: `GET /api/v1/quizzes/random`이 활성 풀에서 랜덤 1개를 `{ quizId, question, choices[{key, text}] }`로 반환하고, `question`·`choices[].text`가 사용자 언어로 번역되며 `correctChoice`·`explanation`을 **포함하지 않는지** 검증한다. 활성 풀이 비어 있으면 `QUIZ_NOT_FOUND(404)`인지 검증한다.
- **채점 계약**: `POST /api/v1/quizzes/{quizId}/answer`가 저장 `correctChoice`와 대조해 정답 `{ correct: true, explanation }`, 오답 `{ correct: false, correctChoice, explanation }`(`explanation`은 두 경우 모두 사용자 언어로 번역, `correctChoice`는 오답에만)를 `200 OK`로 반환하고, **제출·포인트를 남기지 않으며 멱등·반복 가능**한지(같은 요청 반복 시 동일 응답), `201`·`Location`이 없는지 검증한다.
- **i18n·폴백·키 불변**: 지원 언어 사용자는 해당 언어를, 미지원·미매핑 사용자는 `en`을 받는지(에러 아님), 보기 키 `A`~`D`가 언어 무관 동일하고 채점이 키로 수행되는지 검증한다([ADR-0029](./0029-diagnosis-i18n-strategy.md) 정합).
- **에러코드**: `selectedChoice`가 A~D가 아니면 `INVALID_INPUT(400)`, 본문 파손은 `MALFORMED_REQUEST(400)`, 미인증·만료는 `UNAUTHENTICATED`/`TOKEN_EXPIRED(401)`, 비활성(온보딩 미완)은 `AUTH_ONBOARDING_REQUIRED(403)`, `quizId` 부재는 `QUIZ_NOT_FOUND(404)`인지 검증하고, `QUIZ_NOT_TODAY`·`QUIZ_ALREADY_SUBMITTED`가 더는 반환되지 않는지 확인한다.
- **모듈 경계**: `gamification → user` 동기 의존이 `allowedDependencies`에 등록돼 `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java)) green을 유지하는지 검증한다.
- **게이트(구현됨)**: `SecurityConfig`가 `/api/v1/quizzes/**`를 `hasRole("USER")`(ACTIVE)로 게이팅하고, `GamificationService`가 `userType=TENANT`를 검사한다(비-세입자 `403 FORBIDDEN`). 비-ACTIVE(온보딩 미완)는 `403 AUTH_ONBOARDING_REQUIRED`.
- **(확정 · #148)**: (1) 정답 시에도 `explanation`을 **반환한다** — 정답·오답 모두 해설을 사용자 언어로 번역해 내려준다(`correctChoice`만 오답 한정 유지).
- **(확인 필요)**: (2) "random"의 정의 — **활성 풀에서의 무작위 SELECTION**이며 동적 생성이 아님.
- **재검토 시점**: 게이미피케이션(포인트·랭킹)을 1차 MVP 이후 도입할 때, 무상태 채점 위에 제출/집계 저장(대안 A의 관계형 제출·포인트)을 어떻게 얹을지 재검토한다. 학습 이력·정답률 요구가 커지면 무상태 전제를 재검토한다.
