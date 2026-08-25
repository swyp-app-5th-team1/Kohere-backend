# ADR-0004. API 응답을 공통 래퍼(`{ success, data, error }`)로 표준화한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0004 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-15 |
| 관련 문서 | [api-design-guide §3](../api/api-design-guide.md), [error-response-guide §1](../api/error-response-guide.md), [ADR-0003](./0003-jwt-auth-after-oauth-login.md) |

## Status

Accepted

> 클라이언트는 **모바일 앱** 단일이다([api-design-guide](../api/api-design-guide.md)). 본 ADR은 "성공·에러 응답을 어떤 외형으로 내려줄지"를 결정한다. 에러 코드/스키마의 정본은 [error-response-guide](../api/error-response-guide.md)이며, 본 ADR은 성공·에러를 감싸는 **봉투(envelope) 형태**를 고정한다.

## Context

- 클라이언트가 모바일 앱 하나이고, 수십 개 엔드포인트의 성공/실패를 **한 가지 방식으로 파싱·분기**해야 한다.
- 표준 외형이 없으면 엔드포인트마다 응답 모양이 제각각이 되어 클라이언트의 디코딩·에러 처리·다국어 매핑이 복잡해진다.
- 에러는 `error.code`(기계 식별자)로 분기해야 하고([error-response-guide §7](../api/error-response-guide.md)), 입력 검증 실패는 필드별 상세(`errors[]`)를 담아야 한다.
- 인증 실패(`401 TOKEN_EXPIRED` 등) 같은 횡단 응답도 도메인 응답과 **동일한 외형**으로 내려가야 클라이언트 처리가 단순하다.
- 따라서 "성공·에러 응답 봉투"를 **공통 래퍼 vs 원본 DTO + 별도 에러 형식** 중에서 결정해야 한다.

## Decision

**모든 API 응답을 공통 래퍼 `{ success, data, error }`로 감싼다.** 세부 정책:

1. **성공**: `success=true`, `data`=페이로드(객체/배열/페이지 객체), `error=null`.
2. **실패**: `success=false`, `data=null`, `error={ code, message, errors?, details? }`. `code`는 UPPER_SNAKE_CASE 식별자, `errors[]`는 입력 검증 시 필드별 상세, `details`는 **그 코드의 스펙에 명시된 경우에만** 실리는 부가 데이터다([error-response-guide §1](../api/error-response-guide.md)).
3. 서버는 공통 타입 **`ApiResponse<T>`** 로 응답을 감싸고(공유 커널 **`common` 모듈**, [code-style §3-1](../convention/code-style.md)), 컨트롤러는 **DTO만** 반환한다(엔티티 직접 노출 금지).
4. 클라이언트는 **`success` 한 필드로 1차 분기**, 실패 시 **`error.code`로 2차 분기**한다(메시지 문자열로 분기 금지).
5. 본문이 없는 응답(예: `204 No Content`)은 래퍼 없이 빈 본문을 허용한다.
6. 목록은 `data`에 페이지 객체(`content` + `page`/`nextCursor`)를 담는다([api-design-guide §4](../api/api-design-guide.md)).

## Amended (#270) — 실패 봉투에 선택 필드 `details`를 더한다

`error`에 **선택 필드 `details`(객체)** 를 추가한다. Decision 2의 실패 봉투는 `{ code, message, errors?, details? }`가 된다.

- **`errors[]`와 역할이 다르다.** `errors[]`는 **입력 검증** 실패의 필드별 상세(`field`/`reason`)로 용도가 고정돼 있다. `details`는 그 틀에 들어가지 않는 **코드별 부가 데이터**를 담는다 — 첫 사용처는 임대인 웹 로그인 실패의 누적 실패 횟수와 잠금 상한이다([01-auth-onboarding §1-4](../api/specs/01-auth-onboarding.md)).
- **값이 없으면 키 자체가 나가지 않는다**(`@JsonInclude(NON_NULL)`). 따라서 이 개정으로 **기존 에러 응답의 외형은 한 글자도 바뀌지 않는다.**
- **도메인 개념을 봉투에 새기지 않는다.** `details`는 문자열 키 맵이고 어떤 키가 실리는지는 각 API 스펙이 정한다. 공유 커널이 특정 모듈의 어휘를 알게 되면 모듈 경계가 깨진다.
- **남용 금지.** 스펙에 적히지 않은 데이터를 임의로 싣지 않는다. Decision 4(클라이언트는 `error.code`로 분기)는 그대로이며 `details`는 분기 기준이 아니라 **표시용 데이터**다.

> 검토한 대안: ⓐ `message` 문장에 값을 끼워 넣기 — 번역 문자열이라 클라이언트가 파싱해야 하고 Decision 4와 충돌한다. ⓑ `errors[]` 재활용 — `field`가 「클라이언트가 보낸 요청 필드 이름」으로 정의돼 있어 의미가 맞지 않는다. ⓒ 응답 헤더 — 저장소에 선례가 없고 CORS 노출 설정이 새로 필요하다.

## Alternatives

| 항목 | **공통 래퍼 (채택)** | 원본 DTO + 커스텀 에러 | 원본 DTO + RFC 9457 ProblemDetail |
|---|---|---|---|
| 성공 외형 | `{ success, data, error }` 고정 | 리소스 DTO 그대로 | 리소스 DTO 그대로 |
| 에러 외형 | 같은 래퍼의 `error` | 커스텀 `{ code, message, errors }` | 표준 `ProblemDetail`(+커스텀 `code`) |
| 클라 분기 | **`success` 하나로 통일** | 성공/실패 외형이 달라 분기 갈림 | 성공/실패 외형이 다름 |
| HTTP 의미 | status와 일부 중복(`success`) | **status를 그대로 활용** | **status를 그대로 활용, 표준** |
| 페이로드 | 한 겹 중첩(`data.…`) | 평평함 | 평평함 |
| 프레임워크 적합 | 래퍼/Advice 직접 구성 | 단순 | **Spring 6+ 기본 지원** |
| 생태계 표준성 | 사내 관례 | 사내 관례 | **RFC 표준** |

- **원본 DTO + 커스텀 에러 미채택 이유**: HTTP 의미를 살리는 장점은 있으나, 성공과 실패의 응답 외형이 달라 모바일 클라이언트가 **두 경로를 따로 파싱**해야 한다. 단일 클라이언트 환경에서는 외형 통일의 이득이 더 크다.
- **ProblemDetail(RFC 9457) 미채택 이유**: 표준·프레임워크 친화적이나 성공 응답까지 통일되지는 않으며, 클라이언트가 RFC 필드(`type`/`title`/`detail`)에 맞춰야 한다. 본 프로젝트는 성공·실패를 한 외형으로 묶는 단순성을 우선한다. (향후 외부 공개 API가 생기면 재검토.)

## Consequences

- **긍정**
  - 모바일 클라이언트가 **모든 응답을 단일 파서**로 처리하고 `success`로 분기 — 디코딩·에러 처리·다국어 매핑이 단순.
  - 인증 실패 등 횡단 응답도 도메인 응답과 같은 외형이라 처리 일관성이 높다.
  - `error.code` 카탈로그([error-response-guide §4](../api/error-response-guide.md))와 결합해 안정적인 클라이언트 분기 계약을 만든다.
- **부정/트레이드오프**
  - HTTP status와 `success`가 **의미상 일부 중복**되고, 페이로드가 `data` 아래로 **한 겹 중첩**된다.
  - 모든 응답에 래퍼를 씌우는 **보일러플레이트** → `ApiResponse` 유틸과 `@RestControllerAdvice`에서 일괄 처리로 완화.
  - 표준(ProblemDetail)에서 벗어나므로 외부 연동/공개 API 시 변환이 필요할 수 있다.
- **후속 작업**
  - `common` 모듈에 `ApiResponse<T>`·`error` 모델 구현, 전역 핸들러가 동일 래퍼로 변환([error-response-guide §5](../api/error-response-guide.md)).
  - 성공 응답 자동 래핑(`ResponseBodyAdvice`) — [ADR-0013](./0013-response-auto-wrapping.md)에서 채택 확정.
  - 페이지 응답(`content`/`page`/`nextCursor`) 공통 타입 정의.

## Validation

- **외형 계약 테스트**: 대표 성공/실패(검증 실패 `INVALID_INPUT`+`errors[]`, `401`, `404`, `5xx`)가 모두 `{ success, data, error }` 형태인지 통합 테스트로 검증.
- **컨트롤러 규약**: 컨트롤러가 엔티티를 직접 반환하지 않고 DTO+래퍼로 응답하는지 리뷰/아키텍처 테스트로 확인.
- **재검토 시점**: 외부 공개 API/서드파티 연동이 생겨 표준(RFC 9457) 호환이 필요해지면 본 결정을 재검토한다.
