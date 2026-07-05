# ADR-0028. 진단 문항·선택지 카탈로그는 MongoDB diagnosisQuestions 컬렉션에 저장·제공한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0028 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-23 |
| 관련 문서 | [ADR-0002](./0002-inter-module-communication-via-events.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0029](./0029-diagnosis-i18n-strategy.md), [diagnosis spec](../api/specs/02-diagnosis-recommendation.md), [US-2-5 시퀀스](../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-5-2-6-diagnosis-questions.md), [user-stories US-2-5](../requirements/user-stories.md) |

## Status

Accepted

> [ADR-0005](./0005-polyglot-persistence.md)가 `diagnosis` 모듈의 영속을 MongoDB로 정했다. 본 ADR은 그 위에서 "**진단 문항·선택지 카탈로그를 어디에 어떤 형태로 저장하고 어떻게 제공할지**"(US-2-5, `GET /api/v1/diagnoses/questions/{step}` — 단계별 조회)를 결정한다. 카탈로그는 **데이터만** 담고(분기 메타 없음), ③ 분기는 비즈니스 로직(서비스가 저장된 답으로 결정)이다. 진행 중 답의 서버 저장(`POST /api/v1/diagnoses/answers` → in-progress 진단)은 별도 흐름이며 본 ADR은 카탈로그 저장·제공에 집중한다. 번역(i18n)은 후속 [ADR-0029](./0029-diagnosis-i18n-strategy.md)에서 다룬다.

## Context

- 진단은 6단계 문항으로 구성된다: ① 지역 `region` / ② 입국 목적(유학 여부) `purpose` / ③ 대학(그룹)·지역 (`university` 또는 `district`) / ④ 주거 조건 `conditions[]` / ⑤ 월세 범위 `monthlyRentMin`·`monthlyRentMax` / ⑥ ARC `arcStatus`. 각 문항은 문항 표시 텍스트(`question`), 선택지 코드·라벨 목록, 선택 제약(단일/다중·최대 개수)을 가진다(분기 메타는 두지 않는다 — 분기는 서비스가 결정). ⑤ 월세 범위는 선택지 목록이 아니라 두 숫자 입력(`NUMBER_RANGE`)으로, code-enum 1:1 불변식의 예외다. 표시 문자열(번역)은 도큐먼트 안에 **언어 코드를 키로 하는 맵**으로 임베드한다.
- 앱은 진단 화면을 그리기 위해 문항·선택지를 서버에서 받아야 한다. 제공은 **단계별 조회**다 — 앱이 받을 단계 `step`(1~6)을 path로 지정해 `GET /api/v1/diagnoses/questions/{step}`로 그 단계 질문 1개를 받고, 그 단계 답 1개(그 단계 `field`+`code`; `conditions`처럼 다중은 `codes` 배열)를 `POST /api/v1/diagnoses/answers`로 보내면 서버가 그 답을 진행 중(in-progress) 진단에 저장한다(요청에 누적 답 묶음 없음, 한 번에 6단계를 다 주지 않음; 다음 단계 번호는 클라이언트가 정한다). ③ 단계는 서버가 저장된 `purpose`로 대학/지역 질문을 골라 내려준다(클라 분기 아님). 앱·DB 어디에도 카탈로그를 하드코딩하지 않는다([US-2-5 시퀀스](../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-5-2-6-diagnosis-questions.md)).
- 카탈로그가 담을 것은 **단계·필드·문항·선택지·제약**(데이터만)이고, 문항·선택지의 **표시 문자열은 도큐먼트 안에 언어-키 맵으로 임베드한다**(별도 메시지 컬렉션 없음). 즉 `question`은 `{ "en": "Select a region", "ja": "エリアを選択", "ko": "지역 선택" }`, 각 선택지는 `{ code, label{ "en": "Seoul", "ja": "ソウル" } }` 형태다. 분기 규칙 같은 메타는 카탈로그에 두지 않는다(③의 대학/지역 질문은 각각 별도 데이터로 존재하고, 어느 것을 낼지는 서비스가 저장된 `purpose`로 결정한다). 응답 조립 시 서버가 사용자 언어 키로 표시 문자열을 고르며(없으면 `en` 폴백), 선택지 `code`는 언어 무관 불변이다([ADR-0029](./0029-diagnosis-i18n-strategy.md)).
- 선택지 `code`는 **진단 제출**(`POST /api/v1/diagnoses`) 시 본문 검증에 쓰는 enum과 동일해야 한다. 카탈로그에서 받은 `code`로 본문을 구성하면 같은 enum으로 검증·저장되어야 하므로, **카탈로그의 code와 제출 검증 enum이 서로 다른 출처면 정합이 깨진다.**
- 확정 도메인 모델(코드·문서 정렬, 본 작업 기준):
  - ② 입국 목적은 **단일 enum** `purpose`(`Purpose: STUDY | NON_STUDY`)다. 기존 `purposes[]`(배열) 모델을 단일 `purpose`로 교체한다.
  - ③ 대학·지역은 두 필드 `university`(enum `UniversityGroup`: 개별 대학 15값을 6개 그룹으로 묶은 `HUFS_KHU_KOREA,SKKU_SUNGSHIN,SNU_CAU_SOONGSIL,HONGIK_YONSEI_EWHA,KONKUK_SEJONG_HYU,ETC`) / `district`(enum `District`: `GURO_GU,YEONGDEUNGPO_GU,GEUMCHEON_GU,GWANAK_GU,DONGDAEMUN_GU,ETC`)다. **조건부 필수**: `purpose=STUDY` → `university` 필수·`district` 없음 / `NON_STUDY` → `district` 필수·`university` 없음. 위반은 공통 `INVALID_INPUT`. (필드 키는 `university` 유지, enum 타입/값만 그룹으로 교체.)
  - ⑤ 월세는 단일 상한 `monthlyBudgetMax`가 아니라 최소-최대 범위 `monthlyRentMin`·`monthlyRentMax`(KRW 정수, 둘 다 필수·각 0 이상·`min<=max`)다. 답 제출은 `codes[]`가 아니라 필드 키 `monthlyRent`로 `min`/`max` 두 숫자 필드를 본문에 담는다(`{ "field": "monthlyRent", "min": 300000, "max": 600000 }`).
  - ④ 주거 조건 enum은 `listing`의 `ConditionTag` 이름으로 통일한다: `MOVE_IN_NOW, FEMALE_ONLY, PRIVATE_BATH, ENGLISH_OK, ADDRESS_REGISTRATION, NO_MAINT_FEE, MEALS_INCLUDED, DOUBLE_ROOM`(초기 `INSTANT_MOVE_IN/ENGLISH_SPEAKING/TWIN_ROOM` → `IMMEDIATE_MOVE_IN/ENGLISH_AVAILABLE/…` → #110·#113에서 UI 필터명으로 재통일). 추가로 ⑥ `arcStatus`는 값이 `ARC_ISSUED`/`NO_ARC`(미발급)이며, `NO_ARC`이면 서버가 동명의 파생 필터 `NO_ARC`(`DiagnosisCondition`)를 `conditions`에 넣어 추천에 반영한다(사용자가 ④에서 직접 고르는 값이 아니며 최대 3개 제한에서 제외 — #113).
- ③의 분기(`STUDY`→대학 / `NON_STUDY`→지역구)는 **비즈니스 로직(서비스)이 저장된 답 `purpose`로 결정**해 알맞은 질문 하나만 내려준다(클라이언트 로컬 분기 아님, 카탈로그 분기 메타 아님). 카탈로그에는 대학 질문·지역 질문이 **각각 별도 데이터**로 존재하고, 어느 것을 노출할지는 서비스가 고른다.
- 제약: MongoDB 실구현은 [#34](../requirements/user-stories.md)(로컬 MongoDB 미설치)에 막혀 있다. 현 단계 코드는 컨트롤러 + 응답 DTO + stub 서비스(`UnsupportedOperationException`) 스켈레톤까지만 두고, 실 카탈로그 적재·조립은 기존 진단 스텁과 동일하게 TODO로 남긴다.
- 따라서 "카탈로그를 코드 enum 직렬화로 만들지, 정적 리소스로 둘지, DB에 영속할지"를 결정해야 한다.

## Decision

**진단 문항·선택지 카탈로그를 MongoDB `diagnosisQuestions` 컬렉션에 데이터만 영속하고, `GET /api/v1/diagnoses/questions/{step}`가 클라이언트가 지정한 단계의 질문 1개를 제공한다(단계별 조회). ③ 단계는 서버가 저장된 `purpose`로 대학/지역 질문을 골라 반환한다. ③ 분기는 카탈로그 메타가 아니라 비즈니스 로직(서비스가 저장된 `purpose`로 결정)이다.** 세부 정책은 다음과 같다.

1. **저장소는 MongoDB `diagnosisQuestions` 단일 컬렉션이며 데이터만 담는다.** `diagnosis` 모듈이 MongoDB에 있다는 [ADR-0005](./0005-polyglot-persistence.md) 결정과 정합하며, 문항·선택지·제약와 **표시 문자열(번역)을 한 도큐먼트(스텝 단위)에 임베드**한다(분기 메타는 두지 않는다). 번역은 도큐먼트 안에 **언어 코드를 키로 하는 맵**으로 둔다(별도 메시지 컬렉션 없음, [ADR-0029](./0029-diagnosis-i18n-strategy.md)).
2. **도큐먼트 스키마는 스텝 단위이며 표시 문자열을 언어-키 맵으로 임베드**한다 — 각 도큐먼트는 `step`(순서), `field`(제출 본문 키: `region`/`purpose`/`university`/`district`/`conditions`/`monthlyRent`/`arcStatus`), `question`(문항 표시 문자열의 **언어-키 맵**, 예: `{ "en": "Select a region", "ja": "エリアを選択", "ko": "지역 선택" }`), `select`(`select.type`=단일/다중/`NUMBER_RANGE`, `select.max`), `options[]{ code, label }`(선택지 코드 + 라벨 **언어-키 맵**, 예: `{ "code": "SEOUL", "label": { "en": "Seoul", "ja": "ソウル" } }`)를 가진다. **`branchOn` 같은 분기 메타는 두지 않는다**(③ 분기는 서비스가 결정). 대학 질문(`university`)·지역 질문(`district`)은 **각각 별도 step 데이터**로 둘 수 있고, 노출 여부는 서비스가 고른다. ⑤ 월세 범위 스텝은 `select.type=NUMBER_RANGE`(두 숫자 입력)이고 `options`를 비운다. 표시 문자열은 응답 조립 시 사용자 언어 키로 고른다(없으면 `en` 폴백).
3. **선택지 `code`는 제출 검증 enum과 1:1 동일 출처다 — 단, ⑤ 월세 범위 단계는 예외.** 카탈로그 `options[].code`의 값 집합은 해당 필드의 제출 검증 enum(`Purpose`/`UniversityGroup`/`District`/`ConditionTag`/`ArcStatus` 등)과 정확히 일치해야 한다. code는 언어 무관 UPPER_SNAKE 키이며 번역되지 않는다(라벨만 언어별, [ADR-0029](./0029-diagnosis-i18n-strategy.md)). 카탈로그 적재 시 이 enum을 정본으로 삼아 code 집합을 생성/검증한다. **예외(carve-out)**: ⑤ 월세 범위(`monthlyRent`)는 enum 옵션 목록이 아니라 두 숫자 자유 입력(`NUMBER_RANGE`)이라 이 "모든 단계는 고정 선택지 목록이고 code가 enum과 1:1"이라는 전제에서 빠진다 — `options`를 비우고 `min`/`max` 숫자 검증으로 대체한다.
4. **`field` 키는 단수 `purpose`로 통일한다.** ② 입국 목적이 단일 enum이 되었으므로 카탈로그의 `field`는 `purpose`(단수)를 가리킨다(과거 `purposes` 복수 키 제거). 분기 메타(`branchOn`)는 카탈로그에 두지 않으므로 통일 대상도 아니다.
5. **③ 대학·지역 분기는 비즈니스 로직(서비스)이 결정한다.** 카탈로그에는 대학 질문(옵션 `SNU_CAU_SOONGSIL` 등 6개 그룹 코드)과 지역 질문(옵션 `GURO_GU` 등)이 **각각 별도 데이터(step)**로 존재하고, 서비스가 **저장된 답 `purpose`**로 어느 질문을 낼지 고른다(`STUDY`→`university`, `NON_STUDY`→`district`; 클라이언트 로컬 분기 아님, 카탈로그 분기 메타 아님).
6. **`GET /api/v1/diagnoses/questions/{step}`는 클라이언트가 path로 지정한 단계(1~6)의 질문 1개를 선정·조립·반환**한다 — 선정한 스텝의 `question`·`options[].label` 언어-키 맵에서 사용자 언어 문자열을 골라 `{ step, field, question(표시 라벨), select{ type, max }, options[]{ code, label } }`를 만든다. ③(step 3)은 서버가 저장된 `purpose`로 대학(`university`)/지역(`district`) 질문 중 하나를 골라 반환한다(클라 분기 아님). 그 단계 답은 별도로 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; `conditions`처럼 다중은 `codes` 배열)로 보내 진행 중 진단에 저장하며(요청에 누적 답 묶음 없음), 다음 단계 번호는 클라이언트가 정한다. 모든 단계 답이 저장되면 앱은 `POST /api/v1/diagnoses`로 제출(확정)한다. 응답 형태는 그대로이며 언어 선택·폴백은 [ADR-0029](./0029-diagnosis-i18n-strategy.md)를 따른다(언어-키 맵에서 선택).
7. **현 단계는 스켈레톤만 둔다.** [#34](../requirements/user-stories.md)로 MongoDB 미도입이므로 컨트롤러 + 응답 DTO + stub 서비스(`UnsupportedOperationException`)까지 만들고, 컬렉션 적재·조립 로직은 TODO로 남긴다(기존 진단 제출 스텁과 동일 패턴).

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. 코드 enum 직렬화** — 제출 검증 enum을 그대로 카탈로그 응답으로 직렬화 | code 정합이 자동(동일 출처), 별도 저장소 불필요 | 제약·문항 텍스트를 enum 상수에 매달아야 함, 운영 중 문항 추가·구성·번역 변경에 배포 필요 | 문항 구성·번역은 콘텐츠라 코드 배포 없이 바뀌어야 해 enum에 담기 부적합. code 정합은 C에서 enum을 적재 정본으로 삼아 달성 |
| **B. 정적 리소스(JSON/YAML)** — 리소스 번들에 카탈로그 동봉 | DB 불요·단순, 버전이 코드와 함께 감 | 문항·구성·번역 변경에 재배포 필요, 환경별 차등·핫픽스 불가, [ADR-0005](./0005-polyglot-persistence.md)의 문서형 저장소를 놔두고 별도 형식 추가 | 콘텐츠를 코드 릴리스에 묶어 운영 유연성이 낮음 |
| **C. MongoDB `diagnosisQuestions` 컬렉션(채택)** | 문서형·가변 스키마에 적합, 제약·문항·선택지(코드 + 언어-키 맵 라벨)를 한 도큐먼트로(데이터만, 분기 메타 없음), 코드 배포 없이 문항 구성·번역 변경, [ADR-0005](./0005-polyglot-persistence.md)와 정합 | 별도 적재·시드 필요, code↔enum 정합을 적재 시 강제해야 함, 표시 라벨은 도큐먼트 내 언어-키 맵으로 임베드, MongoDB 도입 선행([#34](../requirements/user-stories.md)) | — (채택) |

## Consequences

- **긍정**
  - 문항 구성(필드·코드·제약)과 표시 문자열·번역을 **코드 배포 없이** 한 컬렉션에서 변경할 수 있다(콘텐츠/코드 분리). 라벨 수정·신규 언어 추가도 도큐먼트의 언어-키 맵에 키를 더하면 끝이다([ADR-0029](./0029-diagnosis-i18n-strategy.md)).
  - 문서형 모델이라 `question`(언어-키 맵)·제약·임베드 옵션(코드 + 라벨 언어-키 맵)을 **한 도큐먼트**로 자연스럽게 표현한다(분기 메타 없이 데이터만, [ADR-0005](./0005-polyglot-persistence.md) 정합).
  - **분기는 데이터가 아니라 서비스 로직**이라 카탈로그가 분기 규칙을 떠안지 않는다 — 대학/지역 질문은 데이터로 존재하고 서비스가 저장된 `purpose`로 고른다(분기 규칙 변경이 콘텐츠가 아니라 코드 변경으로 일관).
  - 문항·선택지·라벨이 **한 도큐먼트**에 모여 단건 조회로 표시 문자열까지 조립되며, 별도 메시지 컬렉션·조인이 없다.
  - 선택지 `code`가 제출 검증 enum과 **동일 출처**라 카탈로그로 만든 본문이 제출에서 그대로 검증·저장된다(정합 보장). code는 언어 무관 불변이고 라벨만 언어별이다.
  - 단수 `purpose` 통일로 카탈로그 `field`와 제출 본문·도메인 모델이 일관된다(과거 "코드 정합 #47에서 반영" 노트 불요 → 본 ADR로 닫힘).
- **부정/트레이드오프**
  - 카탈로그 **시드/적재 파이프라인**이 필요하고, 적재 시 code 집합을 enum과 대조해 **드리프트를 막아야** 한다(누락/오타 시 카탈로그-제출 불일치).
  - MongoDB **도입 선행**이 필요해([#34](../requirements/user-stories.md)) 현 단계는 stub만 동작한다(실 조립 TODO).
  - 콘텐츠가 DB에 있어 **버전·변경 이력 관리**(애플리케이션 레벨 버전 필드)가 별도로 필요하다.
  - 번역이 도큐먼트에 임베드돼 **신규 언어 추가 시 각 도큐먼트의 언어-키 맵을 손봐야** 한다(언어별 행 추가가 아니라 맵 키 추가).
- **후속 작업**
  - MongoDB 도입([#34](../requirements/user-stories.md)) 후 `diagnosisQuestions` 컬렉션 스키마 확정 + 6스텝 시드(`question`·`options[].label`을 언어-키 맵으로 임베드). 언어 선택·폴백 정책은 [ADR-0029](./0029-diagnosis-i18n-strategy.md)에서 연동.
  - 적재 시 `options[].code` ↔ 제출 검증 enum(`Purpose`/`UniversityGroup`/`District`/`ConditionTag`/`ArcStatus`) 정합 검증(테스트/시드 스크립트). ⑤ 월세 범위(`monthlyRent`)는 enum 옵션이 아니라 `NUMBER_RANGE`이므로 `options`를 비우고 `min`/`max` 숫자 검증으로 대체한다.
  - stub 서비스(`UnsupportedOperationException`)를 실 조립 로직으로 교체 — 도큐먼트의 `question`·`options[].label` 언어-키 맵에서 사용자 언어 문자열을 골라 채운다.
  - 표시 문자열 언어 선택·폴백은 [ADR-0029](./0029-diagnosis-i18n-strategy.md) 구현과 연동.

## Validation

- **code↔enum 정합**: `diagnosisQuestions`의 `options[].code` 집합이 해당 필드 제출 검증 enum과 완전히 일치하는지 검증하는 테스트를 둔다(카탈로그로 만든 본문이 제출에서 거부되면 실패).
- **응답 계약**: `GET /api/v1/diagnoses/questions/{step}`가 클라이언트가 지정한 단계의 질문 1개(`{ step, field, question, select{type,max}, options[]{code,label} }`, ③은 서비스가 저장된 `purpose`로 고른 대학/지역 질문)를 만족하는지, 그리고 `POST /api/v1/diagnoses/answers`(body `{ field, code }`; 다중은 `codes` 배열)가 그 단계 답을 진행 중 진단에 저장하는지 REST Docs/계약 테스트로 검증한다 — `question`·`label`은 도큐먼트의 `question`·`options[].label` 언어-키 맵에서 사용자 언어로 고른 표시 문자열이다(응답 형태 불변).
- **모듈 경계**: `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java)) green 유지.
- **재검토 시점**: 문항 변경 빈도가 매우 낮고 다국어가 불필요해지면 정적 리소스(대안 B)로 단순화할지 재검토한다.
