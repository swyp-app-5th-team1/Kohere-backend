# ADR-0030. 에러 응답 메시지 i18n은 리소스 번들(Spring MessageSource)로 번역한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0030 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-24 |
| 관련 문서 | [ADR-0004](./0004-api-response-envelope.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0028](./0028-diagnosis-questions-catalog-store.md), [ADR-0029](./0029-diagnosis-i18n-strategy.md), [error-response-guide](../api/error-response-guide.md), [#52](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/52) |

## Status

Accepted

> [ADR-0004](./0004-api-response-envelope.md)가 에러 응답을 공통 래퍼 `{ code, message, errors }`로 표준화했고, `ErrorCode` enum(코드 + 기본 메시지 + status)과 `GlobalExceptionHandler`가 변환을 담당한다. 본 ADR은 그 위에서 "**에러 응답의 `message`를 어떤 방식으로 다국어 번역할지**"를 정한다. 진단 표시 콘텐츠 i18n([ADR-0029](./0029-diagnosis-i18n-strategy.md))과 성격이 달라 별도 결정이 필요하다.

## Context

- 대상 사용자는 외국인이다. 에러 응답의 `message`도 사용자 언어로 보여야 접근성이 유지된다. 현재 `message`는 `ErrorCode.getDefaultMessage()`(단일 한국어) 또는 `BusinessException`의 동적 메시지(한국어)로 내려간다.
- 에러 메시지는 진단 문항·추천 제안 텍스트와 성격이 다르다:
  - **닫힌 집합·코드 소유**: `ErrorCode` enum으로 고정된 code-owned 집합이며, 운영 중 비개발자가 무배포로 바꿀 콘텐츠가 아니다(코드와 함께 버저닝).
  - **동적 보간**: 일부 메시지는 throw 지점에서 값을 끼워 만든다(예: "step은 1~6 사이여야 합니다: 9"). 정적 맵으로는 부족하고 파라미터 템플릿이 필요하다.
  - **인증 전 발생**: 401 등은 사용자(userId)가 정해지기 전에도 발생한다 — 사용자별 언어 결정(사용자 표시 언어 `users.lang`, [ADR-0029](./0029-diagnosis-i18n-strategy.md))을 그대로 쓸 수 없다.
- 번역 텍스트를 둘 위치 선택지: ㉠ DB(ADR-0029처럼 언어-키 맵) / ㉡ 리소스 번들(`messages[_<lang>].properties`) / ㉢ 클라이언트가 `code`로 매핑. 언어 결정 선택지: `Accept-Language` 헤더 / 등록 국가(ADR-0029) / 고정.
- 에러는 `common` 모듈 횡단 관심사다 — 폴리글랏 저장소([ADR-0005](./0005-polyglot-persistence.md))에 `common` 전용 저장소가 없어 DB에 두면 소유 모듈이 모호하다.

## Decision

**에러 응답 `message`는 `ErrorCode` 코드를 키로 하는 리소스 번들(`messages[_<lang>].properties`, Spring `MessageSource`)에서 요청 locale로 번역한다. locale은 `Accept-Language`로 결정하고 기본·폴백 언어는 영어다.** 세부 정책:

1. **키는 `ErrorCode` 이름(언어 무관 식별자), 값은 표시 문자열.** `messages.properties`(영어=기본/폴백), `messages_ko.properties`(한국어) …로 두고, 언어 추가는 `messages_<lang>.properties` 추가로 끝난다.
2. **`GlobalExceptionHandler`가 `code` + locale로 메시지를 해소한다.** `messageSource.getMessage(code, args, fallback, locale)` — 키/언어가 없으면 기본 번들(영어), 그래도 없으면 `ErrorCode.getDefaultMessage()`/예외 메시지로 폴백한다(번역 누락이 장애가 되지 않음).
3. **locale은 `Accept-Language`(`AcceptHeaderLocaleResolver`·`LocaleContextHolder`)로 결정하고 기본 locale은 `en`이다**(`spring.web.locale=en`, `spring.messages.fallback-to-system-locale=false`). 에러는 인증 전에도 발생하므로 사용자별 언어(`users.lang`, [ADR-0029](./0029-diagnosis-i18n-strategy.md))에 의존하지 않는다.
4. **`code`는 언어 무관 불변**이고 클라이언트 분기 기준이다(message로 분기 금지). 서버 번역 여부와 무관하게 `code`는 동일하다.
5. **동적 메시지는 키 부재 시 그대로 노출**된다(현행 유지). 파라미터 템플릿화(throw를 `code + args`로)는 후속 작업으로 남긴다(과설계 금지).

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. DB 언어-키 맵([ADR-0029](./0029-diagnosis-i18n-strategy.md) 방식)** | 무배포 편집, 진단 i18n과 통합 | `common` 저장소 소유 모호, 핫패스 캐싱 필요, 동적 보간 미해결, 에러는 코드와 함께 버저닝되는 콘텐츠라 무배포 편집 이점이 작음 | 에러 문구는 code-owned·저빈도 변경이라 번들이 더 단순·적합 |
| **B. 클라이언트가 `code`로 번역** | 서버 무변경, 오프라인·동적 보간 클라 처리 | 클라마다 번역표 중복·동기화, 서버 번역 콘텐츠(진단)와 언어 불일치 위험 | 서버가 일관 제공하는 편이 단순하고 `code`는 그대로 노출하므로 클라 매핑도 병행 가능 |
| **C. 리소스 번들 + Accept-Language(채택)** | Spring 네이티브(`MessageSource`·`{0}` 보간), 코드와 함께 버저닝, `common` 저장소 소유 문제 없음, 핫패스 메모리 | 무배포 편집 불가(배포 필요), 진단 표시 언어(ADR-0029)와 언어 결정 출처가 다름(헤더 vs `users.lang`) | — (채택) |

## Consequences

- **긍정**
  - 에러 메시지가 `Accept-Language`로 번역되고 키/언어 부재 시 영어로 폴백한다(번역 누락이 장애 아님).
  - `code`는 불변이라 클라이언트 분기에 영향이 없고, 클라 측 추가 번역도 `code`로 매핑할 수 있다.
  - 폴리글랏 저장소의 `common` 소유 모호 문제를 피하고, Spring 네이티브 보간(`{0}`)을 그대로 쓴다.
- **부정/트레이드오프**
  - **언어 결정 출처 불일치**: 에러는 `Accept-Language`, 진단 표시는 사용자 표시 언어(`users.lang`, [ADR-0029](./0029-diagnosis-i18n-strategy.md) 개정(#141))다 — 한 앱에서 두 출처가 다른 언어를 낼 수 있다(범모듈 i18n 통합 시 재검토).
  - **동적 메시지 미번역**: throw 지점에서 만든 동적 한국어 메시지는 키가 없어 그대로 노출된다 — 파라미터 템플릿화가 후속 과제다.
  - 번역 추가가 **배포**를 필요로 한다(운영 중 무배포 편집 불가).
- **후속 작업**
  - 동적 메시지를 `code + args` 템플릿으로 전환(`messages`에 `{0}` 플레이스홀더 + throw 지점을 코드+인자로 리팩터).
  - 범모듈 i18n 통합 ADR — 에러·진단·기타 표시 텍스트의 **언어 결정 출처 단일화**(`users.lang` vs `Accept-Language`)와 번역 저장소(번들 vs DB) 정책.

## Validation

- **번역·폴백**: `Accept-Language: ko`면 ko, `en`/미지원이면 영어로 `message`가 내려가는지(키 부재 시 코드 기본 메시지 폴백) 단위 테스트로 검증한다([GlobalExceptionHandlerI18nTest](../../src/test/java/com/kohere/common/exception/GlobalExceptionHandlerI18nTest.java)).
- **code 불변**: locale이 달라도 `error.code`는 동일한지 검증한다.
- **번들 커버리지**: `messages.properties`(영어)의 키 집합이 `ErrorCode` 전체 상수와 일치하는지 관측(누락 시 코드 기본 메시지로 조용히 폴백되므로 커버리지를 챙긴다).
