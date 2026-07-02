# Error Response Guide

> Kohere 백엔드의 표준 에러 응답과 예외 처리 전략의 **정본**이다. 모든 에러는 이 형식을 따른다.
> 관련 문서: [api-design-guide](./api-design-guide.md) · [code-style](../convention/code-style.md)

## 목적

에러 응답의 **형식·코드·HTTP status**를 한 곳에서 표준화해, 클라이언트가 어떤 API에서든 같은 방식으로 실패를 처리하게 한다. 예외는 컨트롤러가 아니라 **전역 핸들러(`@RestControllerAdvice`)** 에서 일관 변환한다([code-style](../convention/code-style.md) §5).

## 1. 표준 에러 응답 스키마

[api-design-guide](./api-design-guide.md) §3의 공통 래퍼를 그대로 쓴다. 실패 시 `success=false`, `data=null`, `error`에 상세를 담는다.

```jsonc
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_INPUT",          // 기계가 분기하는 식별자 (UPPER_SNAKE_CASE)
    "message": "입력값이 올바르지 않습니다.", // 사람이 읽는 설명 (로그/디버깅용, UI 노출은 클라 재량)
    "errors": [                          // (선택) 검증 실패 시 필드별 상세
      { "field": "email", "reason": "형식이 올바르지 않습니다." },
      { "field": "budget", "reason": "0 이상이어야 합니다." }
    ]
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `error.code` | string | 필수 | 에러 식별 코드. 클라이언트 분기의 기준(메시지로 분기 금지) |
| `error.message` | string | 필수 | 사람이 읽는 설명. 민감정보·스택트레이스 노출 금지 |
| `error.errors[]` | array | 선택 | 입력 검증 실패 시 `field`/`reason` 목록 |

- `message`는 사용자에게 그대로 노출될 수 있으니 **내부 구현·민감정보를 담지 않는다.** `message`는 **서버가 `Accept-Language`로 번역**해 내려간다 — `ErrorCode` 코드를 키로 하는 리소스 번들(`messages[_<lang>].properties`)에서 해소하고, 미지원 언어·키 부재는 영어로 폴백한다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)). 클라이언트 분기는 언어 무관 `code`로 하며(메시지로 분기 금지), 추가 다국어 처리도 `code`로 매핑할 수 있다. (참고: 진단 표시 콘텐츠는 등록 국가 기반 번역 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md). 언어 결정 출처 단일화는 후속 과제.)

## 2. 예외 분류

| 분류 | 성격 | 대표 status | 예 |
| --- | --- | --- | --- |
| 입력 검증 | 요청 형식/제약 위반 | 400 | 필수값 누락, 형식 오류, enum 불일치 |
| 인증(Authentication) | 누구인지 모름 | 401 | 토큰 없음/만료/위조 |
| 인가(Authorization) | 권한 없음 | 403 | 남의 리소스 수정, 차단 사용자 |
| 리소스 없음 | 대상 부재 | 404 | 존재하지 않는 매물/게시글 |
| 충돌/상태 | 비즈니스 규칙 위반 | 409 / 422 | 중복 가입, 이미 신청한 예약 |
| 레이트리밋 | 과다 호출 | 429 | 신고/메시지 도배 |
| 시스템 | 서버/외부 연동 실패 | 500 / 502 / 503 | DB 오류, 외부 API 연동 실패·타임아웃 |

- **도메인/비즈니스 예외**는 의미가 드러나는 커스텀 예외로 던지고(`~Exception`), 전역 핸들러가 status·code로 변환한다.

## 3. HTTP Status 매핑

| status | 사용 시점 | 대표 code |
| --- | --- | --- |
| 400 Bad Request | 입력 검증 실패, 파라미터 오류 | `INVALID_INPUT` |
| 401 Unauthorized | 인증 실패(토큰 없음/만료/위조) | `UNAUTHENTICATED`, `TOKEN_EXPIRED` |
| 403 Forbidden | 인증은 됐으나 권한 없음 | `FORBIDDEN` |
| 404 Not Found | 리소스 없음, 미정의 경로 | `*_NOT_FOUND`, `RESOURCE_NOT_FOUND` |
| 405 Method Not Allowed | 허용되지 않은 메서드 | `METHOD_NOT_ALLOWED` |
| 409 Conflict | 상태 충돌·중복 | `*_ALREADY_EXISTS`, `DUPLICATE_*` |
| 422 Unprocessable Entity | 형식은 맞으나 비즈니스 규칙 위반 | 도메인별 코드 |
| 429 Too Many Requests | 레이트리밋 초과 | `TOO_MANY_REQUESTS` |
| 500 Internal Server Error | 처리되지 않은 서버 오류 | `INTERNAL_ERROR` |
| 502/503 | 외부 연동 실패/일시 불가 | `UPSTREAM_ERROR`, `SERVICE_UNAVAILABLE` |

> 400과 422: **요청 자체가 깨졌으면 400**, 요청은 정상이나 **도메인 규칙상 처리 불가**면 422를 쓴다. 팀 내 혼선을 줄이려 본 프로젝트는 비즈니스 규칙 위반에 **409(충돌형)** 또는 **422(그 외)** 를 사용한다.

## 4. 에러 코드 카탈로그

코드는 **`DOMAIN_REASON`** 형태의 UPPER_SNAKE_CASE다. 공통 코드는 아래에, 도메인 코드는 각 [API 스펙](./specs/)과 함께 등록하고 이 표에 누적한다.

### 공통 (common 모듈)

| code | status | 의미 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 | 입력 검증 실패(필드 상세는 `errors[]`) |
| `MALFORMED_REQUEST` | 400 | JSON 파싱 불가/타입 불일치 |
| `UNAUTHENTICATED` | 401 | 인증 필요 또는 인증 실패 |
| `TOKEN_EXPIRED` | 401 | 액세스 토큰 만료(재발급 유도) |
| `FORBIDDEN` | 403 | 권한 없음 |
| `RESOURCE_NOT_FOUND` | 404 | 일반 리소스 없음 |
| `METHOD_NOT_ALLOWED` | 405 | 미허용 메서드 |
| `TOO_MANY_REQUESTS` | 429 | 호출 한도 초과 |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 |
| `UPSTREAM_ERROR` | 502 | 외부 연동 실패 |

### 도메인 코드 prefix 규약

| 모듈 | prefix 예 |
| --- | --- |
| auth/user | `AUTH_*`, `USER_*` (`AUTH_INVALID_SOCIAL_TOKEN`, `USER_NOT_FOUND`) |
| diagnosis | `DIAGNOSIS_*` |
| listing | `LISTING_*` |
| booking/chat | `BOOKING_*`, `CHAT_*` |
| community | `POST_*`, `COMMENT_*` |
| gamification | `QUIZ_*` |
| report | `REPORT_*` |

각 API 스펙 문서는 자신이 쓰는 도메인 코드를 표로 정의하고, 이 카탈로그와 충돌하지 않게 한다.

## 5. 예외 계층 / 전역 핸들러

```text
RuntimeException
 └─ BusinessException (추상)         // code(ErrorCode) 보유
     ├─ AuthException
     ├─ ListingNotFoundException
     ├─ DuplicateBookingException
     └─ ...
```

- `ErrorCode`는 **enum**으로 `code(String)` + `httpStatus` + 기본 `message`를 보유한다. 새 에러는 enum 상수 추가로 등록한다.
- 모든 비즈니스 예외는 `BusinessException(ErrorCode)`를 상속해 던진다. 컨트롤러에서 `try/catch`로 응답을 만들지 않는다.
- 전역 핸들러는 **`@RestControllerAdvice`** 하나에 모은다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode ec = e.getErrorCode();
    return ResponseEntity.status(ec.getHttpStatus())
        .body(ApiResponse.error(ec.getCode(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class) // Bean Validation
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
    List<FieldErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
        .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
        .toList();
    return ResponseEntity.badRequest()
        .body(ApiResponse.error("INVALID_INPUT", "입력값이 올바르지 않습니다.", details));
  }

  @ExceptionHandler(Exception.class) // 최후의 보루
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e); // 스택트레이스는 로그에만
    return ResponseEntity.status(500)
        .body(ApiResponse.error("INTERNAL_ERROR", "일시적인 오류가 발생했습니다."));
  }
}
```

- Spring MVC 표준 예외(`HttpMessageNotReadableException`, `NoHandlerFoundException`, `HttpRequestMethodNotSupportedException` 등)도 핸들러를 두어 위 코드(`MALFORMED_REQUEST`/`RESOURCE_NOT_FOUND`/`METHOD_NOT_ALLOWED`)로 매핑한다.
- 모듈러 모놀리식이므로 공통 타입(`ApiResponse`, `ErrorCode`, `BusinessException`, 핸들러)은 **`common` 모듈(OPEN)** 에 둔다([code-style](../convention/code-style.md) §3-1).

## 6. 로깅 / 재시도 정책

| 분류 | 로그 레벨 | 재시도 |
| --- | --- | --- |
| 4xx 클라이언트 오류(검증/인증/권한/404) | `WARN` 또는 `INFO` (스택트레이스 X) | 클라이언트가 입력 교정 후 재시도 |
| 409/422 비즈니스 규칙 | `INFO`/`WARN` | 상태 변경 후에만 의미 있음 |
| 429 | `WARN` | `Retry-After` 헤더 후 재시도 |
| 5xx 서버 오류 | `ERROR` (스택트레이스 포함) | 멱등 요청에 한해 지수 백오프 |
| 외부 연동(소셜 검증 등) | `ERROR` | 타임아웃·재시도·서킷브레이커 검토 |

- **민감정보(토큰, 비자번호, 전화번호 등)는 로그에 남기지 않는다.** 식별이 필요하면 마스킹한다.
- 요청 추적용 `traceId`(또는 `X-Request-Id`)를 로그에 남기고, 5xx 응답 `message`에 동일 식별자를 포함하는 것을 검토한다.

## 7. 클라이언트 처리 가이드

- 먼저 **HTTP status**로 큰 분기(2xx/4xx/5xx), 다음 **`error.code`** 로 세부 분기한다. **`message` 문자열로 분기하지 않는다.**
- `401 TOKEN_EXPIRED` → `POST /api/v1/auth/reissue`로 토큰 재발급 후 원요청 1회 재시도. 재발급도 실패하면 로그인 화면으로.
- `400 INVALID_INPUT` → `errors[]`의 `field`를 입력 폼에 매핑해 표시.
- `5xx` → 사용자에게 일반 메시지 + 재시도 버튼. 자동 재시도는 멱등 요청에만.
- 다국어: `code`별 문구 테이블을 클라이언트가 보유한다(서버 `message`는 fallback).

## 체크리스트

- [ ] 모든 에러가 공통 래퍼(`success=false`/`error.code`/`message`)로 응답된다
- [ ] 컨트롤러에서 `try/catch`로 응답을 만들지 않고 전역 핸들러로 변환한다
- [ ] 새 에러는 `ErrorCode` enum + 카탈로그(§4)에 등록했고 status 매핑(§3)을 지켰다
- [ ] 비즈니스 예외는 `BusinessException`을 상속하고 의미 있는 이름을 가진다
- [ ] 검증 실패는 `INVALID_INPUT` + `errors[]`로 내려간다
- [ ] 5xx는 `ERROR` 로그(스택트레이스 포함), 4xx는 스택트레이스를 남기지 않는다
- [ ] 응답 `message`·로그에 민감정보를 노출하지 않는다
