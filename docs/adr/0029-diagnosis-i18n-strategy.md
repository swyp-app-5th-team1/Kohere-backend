# ADR-0029. 진단 i18n(국가별 번역) 전략 — 서버가 등록 국가→언어 매핑으로 표시 라벨을 번역한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0029 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-23 |
| 관련 문서 | [ADR-0002](./0002-inter-module-communication-via-events.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0028](./0028-diagnosis-questions-catalog-store.md), [diagnosis spec](../api/specs/02-diagnosis-recommendation.md), [US-2-5/US-2-6 시퀀스](../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-5-2-6-diagnosis-questions.md), [user-stories US-2-5·US-2-6](../requirements/user-stories.md) |

## Status

Accepted

> [ADR-0028](./0028-diagnosis-questions-catalog-store.md)이 진단 문항·선택지 카탈로그를 MongoDB `diagnosisQuestions` 컬렉션에 두기로 정했다(문항·선택지 표시 라벨을 도큐먼트 안 **언어-키 맵**으로 임베드). 본 ADR은 그 위에서 "**진단 문항·선택지·추천 제안의 표시 텍스트를 어떤 기준으로 어떻게 번역해 저장·조립할지**"(US-2-6)를 확정한다. 단계별 조회(`GET /api/v1/diagnoses/questions/{step}`)로 한 단계씩 받는 질문의 표시 라벨·선택지 라벨, 그리고 추천 응답의 제안 메시지(`suggestions.message`)·상세(`suggestions.actions[].detail`)가 번역 대상이다.

> **개정(2026-06-24, #51 구현)**: 본 ADR 구현 중 두 가지를 조정했다. (1) **국가→언어 매핑을 `diagnosis`가 아니라 `user`의 `countries.lang` 컬럼이 보유**한다 — `diagnosis`는 `user` 공개 query `getLanguage(userId)`로 등록 국가 코드가 아니라 **표시 언어(lang)를 직접 취득**한다(`user`가 `countries.lang`으로 도출, 미설정·미매핑이면 `en` 폴백). 아래 Decision 2·5·8의 "`diagnosis`가 보유하는 country→language 매핑"은 이 항목으로 갈음한다(i18n이 범모듈 관심사이므로 언어 결정을 사용자 소유 모듈로 옮긴다). (2) **추천 제안(suggestions) 번역을 MongoDB `diagnosisSuggestions` 전용 컬렉션**(사유 `reason`을 `_id`로)에 인라인 언어-키 맵으로 영속한다 — 추천 제안은 문항 도큐먼트에 임베드할 부모가 없어 전용 컬렉션을 두되, `messageKey` 평탄 컬렉션(대안 A)이 아니라 인라인 언어-키 맵 방식은 유지한다. 아래 "별도 컬렉션 없이"는 추천 제안에 한해 이 항목으로 갈음한다.

> **개정(2026-07-16, [#141](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/141))**: **표시 언어의 결정 기준을 개정한다** — 사용자가 직접 선택한 표시 언어(`users.lang`)가 **유일한 정본**이고, 미설정이면 곧바로 **`en`으로 폴백**한다. `getLanguage(userId)`의 도출은 `users.lang`(있으면 그 값) → `en`의 **2단계**이며, **국가→언어 도출과 그 근거였던 `countries.lang` 컬럼을 제거한다**(본 ADR의 2026-06-24 개정에서 매핑을 `diagnosis`→`user`로 옮겨 도입했던 `countries.lang`도 함께 **폐기**한다 — 별도 드롭 마이그레이션). 국적(`users.country`)은 이제 비자·매물 등에 쓰이는 사실값일 뿐 표시 언어와 무관하다. 따라서 아래 **Decision 1("번역 기준은 사용자 등록 국가다")·Decision 5(국가→언어 매핑 도출)와 2026-06-24 개정의 `countries.lang` 도입은 이 항목으로 갈음한다**. **Decision 2·3·4·6·7·8·9는 국가→언어 도출 언급을 뺀 나머지가 그대로 유효하다**(Decision 2의 "`user`가 등록 국가(`countries.lang`)로 도출"·Decision 8의 "국가→언어 매핑은 `user`의 `countries.lang`이 보유"·Decision 7의 "등록 국가의 언어 키" 부분은 위 갈음에 포함된다) — 표시 언어는 여전히 `user` 모듈 공개 query를 **동기 취득**하고(Decision 2), 표시 문자열은 도큐먼트 **인라인 언어-키 맵**이며(Decision 3·4), `code`/`type`/`reason`은 **언어 무관 불변**이고(Decision 6), 미지원 언어는 **`en` 폴백**(Decision 7), 번역 라벨의 **정본은 DB**(Decision 8), **API 응답 형태 불변**(Decision 9)이다. `getLanguage(long)` **시그니처가 바뀌지 않으므로** `diagnosis`·`gamification`·`lifetip` 소비자는 **무영향**이다(도출 출처만 달라진다).

## Context

- 대상 사용자는 한국어가 익숙하지 않은 외국인이다. 진단 문항·선택지·추천 제안 텍스트를 사용자가 이해할 수 있는 언어로 보여야 한다(US-2-6, 핵심 접근성).
- 문항 제공은 **단계별 server-stateful 조회**다 — 클라이언트가 받을 단계 `step`(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`로 그 단계 질문 1개와 선택지를 받고(③은 서버가 저장된 `purpose`로 대학/지역 질문을 고른다), 그 단계 답 1개(그 단계 `field`+`code`)를 `POST /api/v1/diagnoses/answers`로 보내면 서버가 진행 중 진단에 저장한다(요청에 누적 답 묶음 없음, 다음 단계 번호는 클라이언트가 정한다). 따라서 번역은 한 응답에 모든 단계가 아니라 **그 단계의 질문·선택지 표시 라벨**에 적용된다.
- 번역 대상은 **표시 텍스트**다: 질문 라벨(`question`), 선택지 라벨(`options[].label`), 그리고 추천 응답의 제안 메시지(`suggestions.message`)와 제안 상세(`suggestions.actions[].detail`). 선택지·제안 액션의 `code`/`type`(enum)과 제안 사유 `reason`(enum)은 표시 텍스트가 아니라 식별 키다.
- 선택지 `code`는 진단 제출(`POST /api/v1/diagnoses`) 검증 enum과 1:1 동일 출처다([ADR-0028](./0028-diagnosis-questions-catalog-store.md)). 카탈로그에서 받은 `code`로 본문을 구성해 같은 enum으로 검증·저장하므로, **code가 언어에 따라 달라지면 제출 검증이 깨진다.**
- 언어를 무엇으로 결정할지 선택지가 있다: 클라이언트가 보내는 `Accept-Language` 헤더 / 토큰 클레임 / 사용자 등록 국가(온보딩 수집값). 등록 국가는 가입 시 확보돼 기기 설정(`Accept-Language`)보다 안정적이고, 본인 국가 정보 기반이라 일관적이다(US-2-6).
- 등록 국가는 `auth`/`user` 영역의 값이다. `diagnosis`가 이를 취득하려면 모듈 간 통신이 필요하다 — 진단 응답에 즉시 번역 언어가 필요하므로 결과적 일관성(이벤트)이 아니라 **즉시 결과가 필요한 동기 조회**다([ADR-0002 Decision 5](./0002-inter-module-communication-via-events.md)).
- 번역 라벨을 **어디에 어떤 모양으로** 둘지 선택지가 있다: 클라이언트 enum 매핑 테이블 / 서버 리소스 번들(properties/JSON) / DB. DB에 둔다 해도 모양이 갈린다 — **㉠ 별도 메시지 컬렉션**(별도 컬렉션에 `(messageKey, lang) → message`로 평탄 저장) / **㉡ `{lang, message}` 객체 배열**(도큐먼트 안에 언어별 객체 배열) / **㉢ 인라인 언어-키 맵**(`diagnosisQuestions` 문항·옵션 도큐먼트에 `{ "en": …, "ja": … }`를 임베드). 문항·선택지 라벨은 코드 배포 없이 바뀌어야 하는 콘텐츠다.
- 표시 문자열은 문항 라벨·선택지 라벨·추천 제안의 `message`/`detail`로 나뉜다. 문항·선택지 라벨은 [ADR-0028](./0028-diagnosis-questions-catalog-store.md)의 `diagnosisQuestions` 도큐먼트에 임베드되고, 추천 제안 텍스트도 같은 방식(언어-키 맵)으로 둬 별도 컬렉션을 만들지 않는다.
- 모든 언어 번역이 항상 준비돼 있지는 않다 — 미지원 언어 처리(폴백 vs 에러)와 **언어 결정 기준**(국가 vs 언어)을 정해야 한다. 등록 국가↔언어는 다대일이라 표시 맵은 **언어 코드(`en`/`ja`/`ko` 등)** 키로 두고 등록 국가→언어는 매핑으로 도출하는 편이 중복이 없다.

## Decision

**진단 i18n은 표시 문자열을 `diagnosisQuestions` 도큐먼트 안에 언어 코드를 키로 하는 맵(`{ "en": …, "ja": …, "ko": … }`)으로 임베드하고, 서버가 사용자 등록 국가→언어로 그 맵에서 해당 언어 문자열을 골라 조립하는 방식을 채택한다.** 별도 메시지 컬렉션·messageKey를 두지 않는다. 세부 정책은 다음과 같다.

1. **번역 기준은 사용자 등록 국가다.** 클라이언트가 언어를 지정하지 않는다. 서버가 온보딩 때 수집한 등록 국가를 국가→언어로 매핑해 번역 언어를 결정한다. **`Accept-Language` 헤더·토큰 클레임 분기는 사용하지 않는다**(가입 시 확보한 국가가 기기 설정보다 안정적).
2. **표시 언어(lang)는 `user` 모듈 공개 query(`getLanguage`)를 동기 호출해 취득한다.** 진단 응답에 즉시 번역 언어가 필요하므로 [ADR-0002 Decision 5](./0002-inter-module-communication-via-events.md)에 따라 이벤트가 아닌 동기 공개 query/포트(DTO)로 가져온다(`diagnosis → user` 의존, 엔티티 비공유). `user`가 등록 국가(`countries.lang`)로 언어를 도출하며, 토큰 클레임은 쓰지 않는다.
3. **표시 문자열은 `diagnosisQuestions` 도큐먼트 안에 언어-키 맵으로 임베드한다.** 별도 메시지 컬렉션을 만들지 않는다([ADR-0028](./0028-diagnosis-questions-catalog-store.md)). 문항 라벨은 `question: { "en": "Select a region", "ja": "エリアを選択", "ko": "지역 선택" }`, 선택지 라벨은 `options[]: { code, label: { "en": "Seoul", "ja": "ソウル" } }` 형태다 — 언어 코드가 표시 문자열로 사상된다. ③ 대학 그룹(`UniversityGroup`) 선택지 라벨도 같은 방식의 번역 대상이다(예: `{ code: "SNU_CAU_SOONGSIL", label: { "ko": "서울대·중앙대·숭실대", "en": "Seoul National · Chung-Ang · Soongsil" } }`, [ADR-0028](./0028-diagnosis-questions-catalog-store.md)). 응답 조립 시 서버가 사용자 언어 키로 문자열을 고른다.
4. **번역 단위는 도큐먼트 임베드 언어-키 맵이며, 별도 키 네임스페이스를 두지 않는다.** 문항·선택지 라벨은 해당 스텝 도큐먼트의 `question`·`options[].label` 맵에서 직접 고른다. 추천 제안 텍스트도 동일하게 사유(`reason`)·액션(`type`)별 언어-키 맵으로 둬, 응답 조립 시 사용자 언어 키로 `message`/`detail`을 고른다(메시지 키 변환 없음) — 추천 제안은 문항 도큐먼트에 부모가 없어 **`diagnosisSuggestions` 전용 컬렉션**(`reason`을 `_id`로)에 인라인 언어-키 맵으로 둔다. 선택지 `code`·제안 `type`·사유 `reason`은 enum 식별 키라 언어 무관 불변이다.
5. **표시 맵은 언어 코드 키로 두고 국가→언어는 매핑으로 도출한다.** 키는 `en`/`ja`/`ko` 등 언어 코드다. 등록 국가→언어는 **`user`의 `countries.lang` 컬럼**이 보유·도출하고 `diagnosis`는 `getLanguage`로 lang을 받기만 한다(국가↔언어 다대일이므로 표시 맵을 국가가 아니라 언어로 둬 중복을 없앤다).
6. **선택지·제안 `code`/`type`·사유 `reason`(enum)은 언어 무관 동일 키다.** `code`는 UPPER_SNAKE 식별 키이며 번역되지 않는다 — ③ 대학(`UniversityGroup` 그룹 코드)·② 목적(`Purpose`)·④ 조건(`ConditionTag`)·⑥ ARC(`ArcStatus`) 등 제출 검증 enum이 모두 같은 원칙이다(그룹 라벨만 언어별 표시 문자열). 어느 언어로 표시되든 같은 `code`로 제출·검증되므로([ADR-0028](./0028-diagnosis-questions-catalog-store.md)) 번역과 제출 검증이 분리된다. 표시 문자열(`label`/`message`)만 언어별이다. ⑤ 월세 범위(`monthlyRent`)는 enum 옵션이 아니라 숫자 입력이라 번역 대상 라벨이 없다([ADR-0028](./0028-diagnosis-questions-catalog-store.md)).
7. **미지원 언어는 영어(`en`)로 폴백한다(에러 아님).** 등록 국가의 언어 키가 표시 맵에 없으면(또는 국가→언어 매핑 미정의 국가면) `en` 값으로 폴백해 정상 200으로 응답한다. 번역 누락이 장애가 되지 않는다.
8. **번역 라벨의 정본은 DB다 — 문항·선택지는 `diagnosisQuestions`, 추천 제안은 `diagnosisSuggestions` 컬렉션 내 언어-키 맵이다.** 콘텐츠(표시 문자열)·신규 언어를 코드 배포 없이 추가·수정하기 위함이며([ADR-0005](./0005-polyglot-persistence.md) 정합), 신규 언어는 도큐먼트의 언어-키 맵에 키를 더하면 된다. 국가→언어 매핑은 `user`의 `countries.lang`이 보유한다.
9. **번역 시점은 응답 조립 시점이며 API 응답 형태는 그대로다.** `GET /api/v1/diagnoses/questions/{step}`는 (카탈로그 + 서버가 저장한 답)으로 클라이언트가 지정한 단계의 질문을 선정하고(③은 저장된 `purpose`로 대학/지역 질문을 고른다), 결정된 언어로 그 **질문 1개의 `question`·선택지 `options[].label` 언어-키 맵에서 해당 언어 문자열을 골라** 내려간다(`question` 문자열, `options[{ code, label }]`). 그 단계 답은 별도로 `POST /api/v1/diagnoses/answers`로 진행 중 진단에 저장한다. 추천 응답도 같은 언어로 `reason`/`type`별 언어-키 맵에서 `suggestions.message`/`actions[].detail`을 고른다. 응답 스키마(필드 모양)는 바뀌지 않고 서버가 사용자 언어 문자열로 채운다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. 별도 메시지 컬렉션** — 별도 `diagnosisMessages` 컬렉션에 `(messageKey, lang) → message`로 평탄 저장하고 카탈로그는 `questionKey`/`code`만 보유 | 표시 문자열을 `(key, lang)`로 평탄 저장해 언어 추가 = 행 추가, 문항·옵션·추천을 단일 컬렉션으로 묶음 | 카탈로그(코드/키)와 메시지(표시 문자열)가 **두 컬렉션으로 분리**돼 응답 조립 시 키 기반 조회 한 단계가 더 생기고, **messageKey 누락**(카탈로그-메시지 불일치)을 막아야 하며, 컬렉션·시드 파이프라인이 둘로 늘어남 | 표시 문자열이 코드 배포 없이 바뀌는 점은 같으나, 단건 조회로 끝나지 않고 messageKey 정합·누락 관측이 추가 부담이라 도큐먼트 임베드보다 무겁다 |
| **B. `{lang, message}` 객체 배열** — 도큐먼트 안에 `[{ lang, message }]` 형태의 언어별 객체 배열로 임베드 | 카탈로그와 라벨이 한 도큐먼트라 단건 조회로 끝, 별도 컬렉션 불요 | 특정 언어 조회가 배열 선형 탐색(키 직접 접근 아님)이라 조립부가 번거롭고, 같은 도큐먼트에 언어 중복 항목이 들어갈 여지가 있어 무결성 보장이 약함 | 한 도큐먼트 임베드라는 장점은 (c)와 같으나, 언어 코드를 키로 직접 접근하는 맵이 조회·무결성에서 더 단순하다 |
| **C. 인라인 언어-키 맵(채택)** — `diagnosisQuestions` 문항·옵션 도큐먼트에 `question: {"en":…,"ja":…}`·`label: {"en":…,"ja":…}`를 임베드, 추천 제안도 동일 | 카탈로그와 라벨이 **한 도큐먼트**라 단건 조회로 표시 문자열까지 조립(별도 컬렉션·조인 없음), 언어 코드를 **키로 직접 접근**, 신규 언어 추가 = 맵에 키 추가, 코드/앱 배포 없이 문구·번역 변경, 폴백·국가→언어 매핑을 서버 단일 지점에서 일관 처리 | 신규 언어 추가 시 각 도큐먼트의 언어-키 맵을 손봐야 함(언어별 행 추가가 아님), 번역 누락 관측·운영 필요, MongoDB 도입 선행([#34](../requirements/user-stories.md)) | — (채택) |

## Consequences

- **긍정**
  - 번역 문구·신규 언어를 **코드·앱 배포 없이** 추가·수정한다 — 도큐먼트의 언어-키 맵에 **키를 더하면 신규 언어가 추가**되며 별도 컬렉션이 없다(콘텐츠/코드 분리, [ADR-0028](./0028-diagnosis-questions-catalog-store.md)·[ADR-0005](./0005-polyglot-persistence.md) 정합).
  - 문항 라벨·옵션 라벨·추천 제안 `message`/`detail`을 **모두 도큐먼트 임베드 언어-키 맵**으로 두고 같은 방식(사용자 언어 키 선택·`en` 폴백)으로 조립해 번역 운영·폴백이 일관된다(메시지 키 변환 없음).
  - 카탈로그와 라벨이 **한 도큐먼트**에 있어 단건 조회로 표시 문자열까지 조립되며, 별도 컬렉션·조인·키 정합이 필요 없다.
  - 번역 기준이 **등록 국가**라 기기 설정(`Accept-Language`)에 흔들리지 않고, 같은 사용자에게 일관된 언어를 준다.
  - 선택지·제안 `code`/`type`·사유 `reason`이 언어 무관 동일 키라 번역과 **제출 검증이 완전히 분리**된다 — 어느 언어로 표시돼도 같은 code로 검증·저장된다. **API 응답 형태는 그대로**(DTO 무영향)이고 서버가 사용자 언어 문자열로 채운다.
  - 미지원 언어 **영어(`en`) 폴백**으로 번역 누락이 장애가 되지 않는다(에러 아님).
- **부정/트레이드오프**
  - `diagnosis → user` **동기 의존**이 생긴다(표시 언어 query `getLanguage`). 추후 서비스 분해 시 원격 호출이 되어 가용성 결합·타임아웃 처리가 필요하다([ADR-0002 Decision 7](./0002-inter-module-communication-via-events.md)).
  - 번역이 도큐먼트에 임베드돼 **신규 언어 추가 시 해당 도큐먼트들의 언어-키 맵을 손봐야** 한다(언어별 행 추가가 아니라 각 맵에 키 추가). 누락된 언어 키는 `en` 폴백으로 메워지므로 커버리지를 관측해야 한다.
  - 번역 라벨이 DB에 있어 **시드/적재·번역 커버리지 관측**이 필요하고, 미번역 폴백이 조용히 영어로 나가므로 누락을 모니터링해야 한다.
  - 국가→언어 매핑을 `user`의 `countries.lang`이 보유·유지하며(국가↔언어 다대일 등), 매핑 미정의 국가도 영어로 폴백한다.
- **후속 작업**
  - MongoDB 도입([#34](../requirements/user-stories.md)) 후 `diagnosisQuestions` 도큐먼트에 `question`·`options[].label`을 언어-키 맵으로 시드 + 추천 제안은 `diagnosisSuggestions` 컬렉션(사유별 인라인 언어-키 맵)에 시드 + `countries.lang` 시드(user)([ADR-0028](./0028-diagnosis-questions-catalog-store.md)).
  - `user` 모듈 표시 언어 조회 공개 query/포트 노출(`getLanguage`, `@NamedInterface`) + `diagnosis` `allowedDependencies`에 `user` 등록.
  - 문항·옵션·추천 제안의 언어별 표시 문자열을 언어-키 맵에 적재 + 응답 조립부(사용자 언어 키 선택·`en` 폴백) 연동.
  - 번역 커버리지·폴백 발생률·언어 키 누락 관측 지표 추가.

## Validation

- **번역·폴백 계약**: 등록 국가가 지원 언어인 사용자는 해당 언어 문자열을, 미지원 언어(또는 매핑 미정의 국가)인 사용자는 영어(`en`) 문자열을 200으로 받는지(에러 아님) 계약/REST Docs 테스트로 검증한다. 언어 키가 없으면 `en`으로 폴백한다.
- **언어-키 맵 조립**: 응답의 `question`·`options[].label`·`suggestions.message`·`actions[].detail`이 `diagnosisQuestions` 도큐먼트의 `question`·`options[].label` 언어-키 맵과 추천 제안 사유·액션별 언어-키 맵에서 사용자 언어 키로 골라 채워지는지(별도 메시지 컬렉션·messageKey를 쓰지 않는지) 검증한다.
- **code 불변**: 표시 언어가 달라도 `options[].code`·제안 `type`·사유 `reason`이 동일하고, 그 code로 `POST /api/v1/diagnoses` 제출이 정상 검증·저장되는지 검증한다(라벨만 언어별, code는 언어 무관 불변).
- **API 형태 불변**: 언어가 달라도 응답 스키마(`question` 문자열·`options[{code,label}]`·`suggestions{message, actions[{type,detail}]}`)가 동일하고 `message`만 언어별로 채워지는지 검증한다.
- **언어 결정 출처**: 번역 언어가 `Accept-Language`·토큰 클레임이 아니라 **등록 국가→언어 매핑**(`user`의 `countries.lang`)으로 결정되는지(헤더를 바꿔도 응답 언어가 불변) 검증한다.
- **모듈 경계**: `diagnosis → user` 동기 의존이 `allowedDependencies`에 등록돼 `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java)) green을 유지하는지 검증한다.
- **재검토 시점**: 번역 변경 빈도가 매우 낮아지면 리소스 번들(대안 B)로 단순화할지, 클라이언트 표시 요구가 커지면 매핑 분담(대안 A)을 재검토한다.
