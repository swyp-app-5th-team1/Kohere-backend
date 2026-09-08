# ADR-0038. 로그는 다섯 가지 용도가 요구하는 것만 남긴다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0038 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-07-21 |
| 기준 코드 | `develop` @ `4b64c92`. 본 ADR의 수치·파일 참조는 전부 이 시점 기준이며, 재검증 없이 인용하지 않는다 |
| 관련 문서 | [ADR-0001](./0001-bounded-context-module-decomposition.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0010](./0010-jwt-authentication-filter.md), [ADR-0014](./0014-withdrawal-pii-anonymization.md), [ADR-0019](./0019-infrastructure-as-code-terraform.md), [ADR-0026](./0026-dev-host-memory-budget.md), [ADR-0031](./0031-apple-sign-in-authorization-code-flow.md), [ADR-0039](./0039-listing-schema-v4-registration-form.md), [ADR-0040](./0040-listing-query-api-v2-and-v1-sunset.md), [#152](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/152) |

## Status

Proposed

> 이슈 #152는 ① `BusinessException` 외 모든 예외 로깅 ② 사용자별 활동 추적 ③ CloudWatch 중앙 수집을 요구했다. 본 ADR은 ①의 "빠짐없이 로깅한다"를 폐기하고, 로그를 남기는 용도를 먼저 정의한 뒤 그 용도가 요구하는 것만 남긴다.

## Context

| 항목 | 실태 |
|---|---|
| 로깅 설정 | `logback-spring.xml`·`logging.*`·MDC·`traceId` 전부 0건. 전량 STDOUT |
| 인증·인가 실패 | 401/403은 `SecurityErrorResponder`가 직접 write해 `GlobalExceptionHandler`를 안 거친다 — 로그 0건. write 호출자는 `RestAuthenticationEntryPoint`·`RestAccessDeniedHandler`·`JwtAuthenticationFilter` 만료 분기 셋이다 |
| 게스트 접근 | 매물 탐색 GET·퀴즈·생활 팁·v2 진단이 `permitAll`(#181·#182·#185). 게스트는 `AuthPrincipals.userIdOrNull`로 `userId == null` — 익명이 오류가 아니라 정상 트래픽이다 |
| 외부 연동 | 6개 중 3건 타임아웃 미설정, 재시도·서킷브레이커 0건. 스텁 폴백 3개가 조용히 통과시킨다 |
| cross-store | `@Transactional`이 `JpaTransactionManager`뿐이라 Mongo·Redis는 롤백 대상이 아니다([ADR-0005](./0005-polyglot-persistence.md)) |
| 비동기 | `@Async`·`@Scheduled`·브로커 0건 — MDC 스레드풀 오염·전파는 존재하지 않는 문제다 |
| 인프라 | dev는 t3.small(2GB)에 컨테이너 5개 공존([ADR-0026](./0026-dev-host-memory-budget.md)). prod ECS는 `awslogs` 배선이 정의만 되어 있고 CD 미연결이라 실적재는 미검증 |

## Decision

### 다섯 용도

**로그는 아래 다섯 용도 중 하나에 기여한다. 어디에도 기여하지 않으면 남기지 않는다.**

| # | 용도 | 답하는 질문 | 신설 로그 라인 |
|---|---|---|---|
| 1 | 사용자 활동 추적 | 이 사용자가 무엇을 했나 | 접근 로그, 데이터 변경 7종 |
| 2 | 외부 의존성 관측 | 우리 잘못인가 외부 잘못인가 | 외부 호출 래퍼, 활성 어댑터 |
| 3 | 보안 감사 | 누가 접근했고 누가 권한을 얻었나 | 인증·인가·감사 이벤트 |
| 4 | 성능 지연 확인 | 어디서 느려졌나 | 없음 — `latencyMs` 필드로 충족 |
| 5 | 서버 오류 확인 | 우리 서버가 무엇을 잘못했나 | 5xx·`Error` 스택, 삼킨 예외 |

한 라인이 여러 용도에 기여하므로 용도 수만큼 라인을 만들지 않는다. 접근 로그는 용도 1이 주(主)이고 용도 4·5에도 기여한다.

| 관측 대상 | 전용 라인 대신 | 얹히는 라인 |
|---|---|---|
| 용도 4 성능 지연 | `latencyMs` 필드 | 접근 로그, 외부 호출 래퍼 |
| cross-store 부분 실패(용도 5) | `stores` 필드 | 데이터 변경 7종, 인증·감사 이벤트 |
| 스텁 폴백 활성(용도 5) | 기동 시 1회 라인 | 용도 2의 활성 어댑터 |

### 공통 기반

**필드 키는 아래로 고정한다.** 문서·구현·Logs Insights가 같은 키를 쓴다. `actor` 같은 별칭은 쓰지 않는다.

| 키 | 값 | 조달 |
|---|---|---|
| `traceId` | UUID | `MdcLoggingFilter`(`HIGHEST_PRECEDENCE`)가 put, `finally`에서 `MDC.clear()` |
| `userId` | 숫자 또는 `anonymous` | `JwtAuthenticationFilter` 인증 성공 분기([ADR-0010](./0010-jwt-authentication-filter.md)) |
| `userId`(로그인류) | 발급 결과 | social-login·reissue는 익명 요청이라 MDC에 값이 없다. `AuthService`가 직접 채운다 |
| `userId`(게스트) | `anonymous` | `permitAll` 경로의 비회원. 코드는 `null`(`AuthPrincipals.userIdOrNull`)이지만 MDC가 `Map<String,String>`이라 문자열로 적는다 — 미인증과 같은 값이라 로그만으로는 구분되지 않는다 |
| `userId`(만료 401) | 숫자 | JJWT가 서명을 먼저 검증하고 `exp`를 나중에 보므로 `ExpiredJwtException`은 **서명이 유효했다**는 뜻이다. `sub`를 신뢰할 수 있어 거부를 실제 사용자에게 귀속시킨다(숫자 19자 이하만 통과시키는 심층 방어 포함) |
| `userId`(위조 401) | `anonymous` | 서명 검증이 실패해 `sub`가 검증되지 않은 공격자 입력이다. 채우면 감사 로그의 신원이 위조 가능해져 용도 3이 무력화되고, 콘솔 appender가 값을 그대로 써서 개행 섞인 `sub`가 가짜 로그 줄을 만든다 |
| `guestSessionId` | 세션 id 또는 없음 | v2 진단 전용. 게스트는 `userId`가 없어 이 키가 유일한 활동 추적 축이다(`DiagnosisFlowService.next`) |
| `onboarding` | boolean | `AuthPrincipal` |

**기반은 아래로 확정한다.**

| 항목 | 결정 |
|---|---|
| appender | `CONSOLE` 텍스트(전 프로파일) + `JSON_FILE` 1줄 JSON(`test` 제외 전 프로파일). 콘솔까지 JSON이면 `docker logs` 가독성이 죽는다. `local`도 켜서 "배포하면 어떤 모양인지"를 로컬에서 그대로 확인한다 — `test`만 빼는 이유는 테스트가 작업 트리에 파일을 남기지 않게 하기 위해서다 |
| 로그 파일 경로 | `app.log.dir`이 정한다 — `local`(bootRun)은 상대경로 `logs`(절대경로면 Windows에서 `C:\logs`로 튄다), `local`(compose)·`dev`·`prod`는 `/logs`. compose는 `./logs`·`/opt/kohere/logs`를 바인드해 컨테이너 밖에서 읽는다 |
| 의존 | `logstash-logback-encoder` 하나. 앱은 AWS SDK를 물지 않는다 |
| 내용·전송 직교 | 로그 내용(JSON·MDC)과 전송 경로(CloudWatch)는 독립 결정이다. 앱은 파일까지만 책임진다 |
| 분산 추적 | Micrometer Tracing 미도입. 단일 EC2라 전파 이점이 없고, 키만 `traceId`로 맞춰 MSA 전환 시 이행 |
| 배치 | 필터·인터셉터·`LogMasker`·호출 래퍼는 전부 `common`([ADR-0001](./0001-bounded-context-module-decomposition.md)) |

**레벨의 단일 출처는 아래 표다.** `TOKEN_EXPIRED`만 401 중 유일하게 INFO다 — 짧은 access + refresh 회전 구조상 만료 401은 일상이라 WARN에 두면 이상 신호가 묻힌다.

| 레벨 | 대상 |
|---|---|
| ERROR | 5xx 응답, `java.lang.Error`, 이니셜라이저의 삼킨 실패 |
| WARN | 외부 호출 실패, 토큰 위조·서명 오류, refresh 재사용, 403, `TOKEN_EXPIRED` 외 401, 지연 임계 초과 |
| INFO | 접근 로그, 데이터 변경 7종, 감사 이벤트, 외부 호출 성공, 활성 어댑터, `TOKEN_EXPIRED` 401 |
| DEBUG | local 전용. `dev`·`prod`는 `root=INFO`로 억제 |

**PII는 원천 배제한다**([ADR-0014](./0014-withdrawal-pii-anonymization.md)).

| 값 | 정책 |
|---|---|
| `userId`·`onboarding` | `AuthPrincipal`에 PII 없음 — 마스킹 불필요 |
| `pathVars` | 현 매핑의 경로 변수는 전부 id·코드(`bookingId`·`listingId`·`postId`·`commentId`·`userId`·`diagnosisId`·`quizId`·`roomId`·`step`·`topicCode`) — 마스킹 불필요. 비-id를 경로에 두는 엔드포인트가 생기면 `LogMasker` 경유가 전제다 |
| 토큰·Apple private key | 어떤 레벨에서도 금지 |
| 이메일·전화·실명·여권·사업자번호 | `common`의 `LogMasker`로 마스킹(`mask(phone)` 선례 승격) |
| 매물 등록 본문의 `businessRegistrationNumber`·`contact.phone` | `LogMasker`로 마스킹. `POST /api/v2/listings` 요청 본문이 싣는 임대인 PII로, 매물 문서에는 원문·평문으로 저장되지만([ADR-0039](./0039-listing-schema-v4-registration-form.md)) **로그에는 어떤 레벨에서도 원문을 남기지 않는다** — `LISTING_REGISTERED` 이벤트·검증 실패 경로·`toString` 모두 해당한다 |
| 인증번호 | `LoggingVerificationSmsSender`가 `code`를 평문 WARN으로 찍는다 — 제거. 고정 인증번호 발급자 2종은 `userId`만 남겨 이미 안전하다 |

### 용도 1 — 사용자 활동 추적

**컨트롤러 매핑 65개 전 요청에 접근 로그 한 줄을 남긴다.** `HandlerInterceptor`·INFO. 동작 46개와 미구현 껍데기 19개(chat 5·community 12·report 2)를 모두 포함한다 — 껍데기 호출은 500이라 용도 5와 같은 `traceId`로 묶여야 한다. 프리픽스는 `/api/v1`과 `/api/v2`(v2 진단·매물 등록·매물 조회) 둘 다이며 인터셉터는 프리픽스로 거르지 않는다.

**종료된 v1 매물 조회의 스텁 응답도 접근 로그를 남긴다**([ADR-0040](./0040-listing-query-api-v2-and-v1-sunset.md)). 빈 페이지(200)든 `404 LISTING_NOT_FOUND`든 요청은 요청이라 인터셉터를 그대로 탄다. 프리픽스로 거르지 않는 규칙의 실익이 여기서 나온다 — `pathPattern`이 `/api/v1/listings…`인 라인의 잔량이 **구버전 앱이 얼마나 남았는지**를 보여주는 유일한 지표이고, 이관 진척은 같은 기능의 v1/v2 접근 로그 비율로 읽는다. 스텁의 404는 4xx `BusinessException`이라 스택 없이 `status`·`errorCode`로만 관측되며(범위 제외 절), 의도된 동작이므로 그것으로 족하다.

| 항목 | 값 |
|---|---|
| 필드 | `traceId`, `userId`, `method`, `pathPattern`, `pathVars`, `status`, `latencyMs`, `errorCode`, `onboarding` |
| 제외 경로 | `/actuator/health`, `/docs/**`, `/swagger-ui/**`, 정적 리소스. `PublicPaths`와 셋이 겹치지만 별개 개념이다 — social-login·reissue는 공개 티어여도 용도 3이 필요로 하므로 접근 로그에 **남긴다** |
| `pathPattern` | 원경로가 아닌 템플릿 — 엔드포인트 집계의 전제 |
| `pathVars` | 템플릿에 채워진 실제 값(`{bookingId:42}`). 인터셉터가 `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`로 취득 |
| `onboarding` | `SecurityConfig` 정확 매처 구멍에 `ROLE_ONBOARDING` 토큰이 도달했는지가 로그로만 관측된다 |

**경로만으로 재구성되지 않는 데이터 변경 7종만 별도 이벤트로 남긴다.** `userId`·`traceId`는 전 이벤트 공통이며 이 키로 접근 로그와 조인한다. 단 v2 진단은 게스트가 닿아 `userId`가 `anonymous`일 수 있다.

| 이벤트 | 추가로 남길 값 | 위치 |
|---|---|---|
| `BOOKING_CREATED` | `bookingId`, `listingId`, `roomOfferId`, `moveInDate` | `BookingService.createBooking` |
| `USER_BLOCKED` | `blockedUserId`, `bookingId` | `BookingService.blockBooking` |
| `BOOKING_REPORTED` | `reportId`, `reportedUserId`, `reasonCode` | `BookingService.reportBooking` |
| `DIAGNOSIS_COMPLETED` | `diagnosisId`, 추천 결과 유형 | `POST /api/v2/diagnoses/next` (마지막 슬롯 자동 확정) |
| `DIAGNOSIS_STEP_ADVANCED` | `sessionId`, `step`, 선택지·분기 | `DiagnosisFlowService.next` — 게스트도 닿아 `userId`가 `anonymous`일 수 있고 그때는 `guestSessionId`가 유일한 축이다 |
| `PROFILE_UPDATED` | 바뀐 필드명 목록만(값은 PII), `lang`은 값까지(번역을 좌우) | `UserService.updateMyProfile` |
| `LISTING_REGISTERED` | `listingId`, `roomOfferCount`, `status`(항상 `PENDING`) | 매물 등록 서비스(`POST /api/v2/listings`) — 발급된 `listingId`가 경로에 없어 접근 로그로 복원되지 않는다 |

**`LISTING_REGISTERED`는 임대인 PII가 실린 유일한 데이터 변경 이벤트다.** 요청 본문의 `businessRegistrationNumber`·`contact.phone`은 이벤트에 담지 않고, 어쩔 수 없이 값이 필요한 경로에서는 `LogMasker`를 거친다(위 PII 표). 저장은 원문·평문이지만([ADR-0039](./0039-listing-schema-v4-registration-form.md)) 로그는 그 예외를 따라가지 않는다.

**아래는 접근 로그로 충분해 이벤트를 만들지 않는다.**

| 제외 | 이유 |
|---|---|
| 예약 소프트 삭제 · 차단 해제 · 찜 추가·해제 | 식별자가 경로에 있어 `pathVars`로 포착되고, `method`가 추가·해제를 가른다 |
| 최근 본 매물 기록 | 최대 볼륨인데 UX 편의 기능 |

접근 로그의 전 요청 INFO는 단일 t3.small에서 최대 수집원이다. **일 수집량 상한은 200MB로 둔다.** 넘으면 `DIAGNOSIS_STEP_ADVANCED`(진단 1회당 5~8건) → 조회성 GET 접근 로그 → `JSON_FILE` 반출 WARN 이상 제한 순으로 줄인다.

**상한을 볼륨이 아니라 비용으로 정했다.** CloudWatch Logs는 수집 GB당 과금(서울 기준 ~$0.76/GB)이라 상한이 곧 월 비용 상한이다.

| 일 수집량 | 월 수집 | 월 비용(수집+저장 어림) | 판단 |
|---|---|---|---|
| 200MB(**채택**) | 6GB | **~$5** | dev 호스트(~$17/월)의 1/3 — 수용 가능 |
| 1GB | 30GB | ~$24 | **호스트 비용을 넘는다** — [ADR-0021](./0021-cost-optimization-profile.md) 비용 최소화와 배치 |

접근 로그 1줄이 ~400B이므로 200MB는 **일 50만 요청** 수준이다. dev 실트래픽은 그보다 훨씬 낮을 것으로 보므로 상한은 정상 운영을 제약하지 않고 **사고(무한 루프·봇 트래픽)를 조기에 잡는 브레이크로 기능한다.** 단가는 리전·시점에 따라 바뀌므로 실청구로 재확인한다.

**상한은 알람으로 감시하되 차단하지는 않는다.** AWS는 Log Group당 수집량 하드 리밋을 제공하지 않으므로, 상한은 본질적으로 "정책 + 경보 + 대응"이다. CloudWatch Logs가 Log Group별로 자동 발행하는 **`IncomingBytes`**(`AWS/Logs`, AWS 기본 지표라 무료)에 일 단위 합계 알람을 걸어 [ADR-0027](./0027-dev-discord-alerting.md)의 SNS→Discord 경로로 통보한다. Agent의 `metrics` 섹션이나 `PutMetricData` 권한은 필요 없다 — 호스트 메모리·스왑이 미수집인 것과 대비되는 지점이다.

### 용도 2 — 외부 의존성 관측

**외부 호출 6개를 공통 래퍼로 감싸 호출당 1건을 남긴다.** 구현이 `RestClient`·`NimbusJwtDecoder`·SDK·`JavaMailSender`로 제각각이라 공통 AOP 지점이 없어 어댑터 내부에서 호출한다.

| 항목 | 값 |
|---|---|
| 필드 | `target`, `operation`, `latencyMs`, `outcome` |
| `outcome` | `SUCCESS` · `CLIENT_ERROR` · `UPSTREAM_ERROR` · `TIMEOUT` |
| 레벨 | 성공 INFO, 실패 WARN |
| 이중 로깅 방지 | 래퍼가 WARN을 남긴 실패에서 파생된 5xx는 `GlobalExceptionHandler`가 스택을 다시 남기지 않는다 |

| # | 연동 | 클래스 | 타임아웃 | 실패 매핑 |
|---|---|---|---|---|
| 1 | Apple 토큰 교환·폐기 | `AppleAuthClientImpl` | 3000 / 5000ms | 4xx→401, 그 외 502 |
| 2 | OIDC JWKS(Apple·Google) | `OidcTokenVerifierImpl:54-57` | **미설정** | 전부 401 |
| 3 | 네이버 지역검색 | `NaverPlaceSearchClient` | 3000 / 5000ms | 전부 502. 키 미주입이면 호출 없이 502 |
| 4 | 비즈노 사업자번호 | `BiznoBusinessRegistryVerifier` | 3000 / 5000ms | 4xx→422, 그 외 502 |
| 5 | SOLAPI SMS | `SolapiVerificationSmsSender` | **미설정**(SDK 기본) | 502 |
| 6 | SMTP 메일 | `SmtpVerificationEmailSender` | **미설정**(무한 대기) | 502 |

타임아웃 3건을 실제로 설정하는 일은 이 ADR의 범위가 아니다. 본 ADR은 관측 수단만 정한다.

**기동 시 어느 어댑터가 활성인지 1회 INFO로 남긴다.** 스텁은 실패를 내지 않고 통과시키므로, 이 라인이 없으면 "검증 통과"가 실검증인지 스텁 통과인지 사후에 구분할 수 없다.

| 스텁 빈 | 게이트 | 위험 |
|---|---|---|
| `LoggingVerificationSmsSender` | `app.solapi.enabled=false` | 인증번호 평문 출력 |
| `StubBusinessRegistryVerifier` | `app.bizno.enabled=false` | 숫자 10자리면 무조건 통과 |
| `TestLoginOidcTokenVerifier` | `@Profile({local,dev})` + `app.auth.test-login.enabled` | `master:<role>:<secret>`이면 OIDC 검증 없이 세션 발급 |
| `FixedCodeEmailVerificationCodeIssuer` | `@Profile({local,dev})` + `app.auth.fixed-verification.enabled` | 지정 심사 계정은 이메일 인증번호가 고정값이고 메일이 안 나간다(#184) |
| `FixedCodePhoneVerificationCodeIssuer` | `@Profile({local,dev})` + `app.auth.fixed-verification.enabled` | 지정 심사 계정은 SMS 인증번호가 고정값이고 문자가 안 나간다(#184) |

### 용도 3 — 보안 감사

**401/403을 `SecurityErrorResponder.write`에서 직접 로깅한다.** 현재 0건이라 무차별 토큰 대입·권한 우회 시도가 통째로 관측 밖에 있다. 용도 3의 최대 근거다. write 호출자가 `RestAuthenticationEntryPoint`·`RestAccessDeniedHandler`·`JwtAuthenticationFilter`(만료 분기) 셋이므로, 호출자마다가 아니라 `write` 한 곳에 넣어야 누락이 없다.

| 갈래 | 이벤트·판정 | 레벨 |
|---|---|---|
| 인증 | social-login 성공·실패, `reissue`(회전), logout, 이메일·휴대폰 인증코드 발송·검증 | INFO |
| 인가 | 401 거부, 403 거부 | `TOKEN_EXPIRED`=INFO, 그 외 WARN |
| 인가 | 만료 401을 `PublicPaths` 밖에서는 필터가 직접 끊고 공개 티어는 통과시킨다(#181). 게스트 허용 경로는 `permitAll`이라 EntryPoint가 안 돌아 필터가 유일한 관측점이다 | INFO |
| 보안 | 토큰 위조·서명 오류 | WARN |
| 보안 | refresh 재사용 — 키 삭제 대신 `status`가 `REVOKED`로 전이되므로 이미 `REVOKED`인 토큰의 `reissue`로 판정 | WARN |
| 감사 | `users.status` 전이(`PENDING`→`TERMS_AGREED`→`ACTIVE`→`WITHDRAWN`), 임대인 승격, 사업자 검증 결과, 탈퇴 | INFO |

### 용도 4 — 성능 지연 확인

**별도 로그 라인을 만들지 않는다.** 두 라인의 `latencyMs`가 같은 `traceId`로 묶여 "이 요청 800ms 중 620ms가 비즈노"가 나온다.

| 구간 | 필드가 붙는 라인 | 임계(잠정) |
|---|---|---|
| 요청 전체 | 접근 로그 | 1000ms 초과 시 WARN 승격 |
| 외부 대기 | 외부 호출 래퍼 | 3000ms 초과 시 WARN 승격 |

임계값은 dev 2주 실측 후 p95로 재조정한다. 타임아웃 미설정 3건에는 이 필드가 사실상 유일한 탐지 수단이다.

### 용도 5 — 서버 오류 확인

**용도 5는 실패 전용이다. 성공 시에는 아무것도 남기지 않는다.** 정상 흐름을 기록하는 다른 네 용도와 대비되는 비대칭이며, 이 비대칭이 용도 5의 범위와 기동 경로 판정을 결정한다.

| 오류 | 처리 | 현행 대비 델타 |
|---|---|---|
| 5xx(껍데기 19개 포함, 코드 버그) | ERROR + 스택 | 이미 `GlobalExceptionHandler:38-48`(5xx만)·`:81-84`가 남긴다. 델타는 `traceId` 결합뿐 |
| `java.lang.Error` | `handleUnexpected`의 catch를 `Throwable`로 확대 | 신규. `build.gradle:28-31`이 SOLAPI SDK Kotlin `NoSuchMethodError` 실사고를 기록 |
| 삼킨 예외 | 이니셜라이저 4개 WARN → ERROR 승격 | 신규. `Diagnosis`·`DiagnosisQuestion`·`Quiz`·`LifeTip` `IndexInitializer`. `DiagnosisFlowSession`은 아래 기동 경로 표 참조 |
| 커넥션 풀 고갈 | Hikari leak-detection + `latencyMs` 급증으로 간접 탐지 | 직접 관측은 메트릭 영역 |
| 기동 실패 | 신규 로깅 불필요 | 아래 기동 경로 표 |
| OOM 컨테이너 사망 | 로깅 불가 | 메트릭·알람 영역([ADR-0027](./0027-dev-discord-alerting.md)) |

**실패가 예외로 표면화되지 않는 둘만 예외적으로 성공 경로에 흔적을 남긴다.** 결정은 용도 5에 모으되 구현은 새 라인 없이 기존 라인에 필드를 얹는 방식이며, 용도 4가 `latencyMs`로 충족되는 것과 같은 패턴이다.

| 예외 사례 | 왜 실패를 못 잡나 | 성공 경로의 흔적 | 얹히는 라인 |
|---|---|---|---|
| cross-store 부분 실패 | 예외를 안 던진다. MySQL만 롤백되고 Mongo·Redis 쓰기는 남는다 | `stores` 필드 | 아래 흐름별 표 |
| 스텁 폴백 활성 | 스텁이 통과시키는 것이라 실패가 아니다 | 활성 어댑터 1회 | 용도 2의 활성 어댑터 |

`stores` 값은 `키:상태` 목록이고 상태는 넷이다 — `ok` · `partial`(일부만 반영) · `skipped`(선행 조건 미충족) · `fail`. 각 흐름의 마지막 커밋 지점에서 서비스가 한 번 세팅한다.

| 흐름 | 저장소 구성 | `stores` 키 | 얹히는 라인 |
|---|---|---|---|
| `UserService.withdraw` | MySQL tx + Apple HTTP + Redis | `mysql`, `apple`, `redis` | 용도 3 탈퇴 감사 |
| `AuthService.socialLogin` | MySQL tx + Apple HTTP + Redis | `mysql`, `apple`, `redis` | 용도 3 social-login |
| `BookingService.createBooking` | MySQL tx + Mongo 읽기 | `mysql`, `mongo` | 용도 1 `BOOKING_CREATED` |

근거: `revokeAllByUserId`는 `SMEMBERS`→루프 `HSET`이라 원자성이 없다. `AuthService:182-191` 주석이 롤백 시 Apple refresh token 유실을 자인한다. 유실은 영구가 아니라 **다음 Apple 로그인까지의 창**이다 — [ADR-0031 #4](./0031-apple-sign-in-authorization-code-flow.md)가 "교환 응답에 보통 매번 포함"이라고 못박았고(최초 1회만 내려오는 건 refresh token이 아니라 `email`·`fullName`이다), 같은 파일의 재로그인 분기가 실제로 매번 upsert한다. 창 안에 탈퇴하면 `UserWithdrawnEventListener`의 skip WARN으로 드러난다. `apple` 키는 그 WARN을 대체하지 않고 창의 존재를 탈퇴 시점보다 먼저 드러낸다. **트랜잭션 경계로는 이 창이 닫히지 않는다** — 교환을 밖으로 빼면 외부 호출 동안 DB 커넥션을 잡지 않아 롤백 유발 요인이 줄어 빈도는 낮아지지만, 토큰이 커밋 전까지 메모리에만 있는 건 같다. 없애려면 토큰을 별도 커밋으로 먼저 저장해야 하고, 신규 가입은 붙일 `social_accounts` 행이 아직 없어 구조 변경이 선행된다. `createBooking`의 Mongo는 읽기라 잔여물이 없고, `mongo` 키는 유령 참조 판별용이다.

**기동 경로에는 새 로그를 추가하지 않는다.** 이미 충분히 남으며, logback 초기화가 아래 전부보다 앞서 `JSON_FILE`이 기동 로그를 포착한다.

| 항목 | 실태 | 조치 |
|---|---|---|
| Flyway 11.7.2 | `Successfully applied N migrations` INFO. 실패는 예외 전파 | 없음 |
| Mongock 5.5.0 | ChangeUnit마다 `APPLIED` INFO. 실패는 catch 없이 전파 | 없음 |
| 예외 전파 3경로의 실패 | `Application run failed` + 전체 스택 | 없음 |
| `ListingMongoIndexInitializer` | 로그 0건. `try/catch`가 없어 실패는 예외 전파로 커버 | **철회.** 침묵은 성공 경로뿐이고 용도 5는 성공을 안 남긴다 |
| 이니셜라이저 4개 | 실패를 `catch(RuntimeException)`→WARN으로 삼키고 기동 계속 | **ERROR 승격.** 유일한 '실패해도 기동은 계속' 경로 |
| `DiagnosisFlowSessionIndexInitializer` | 두 분기다 — 인덱스 조회 실패는 WARN 삼킴, 정합화 실패는 `IllegalStateException` 전파(#185) | 삼키는 앞 분기만 **ERROR 승격**. 전파 분기는 무변경 |
| `MasterAccountSeedRunner` | 성공을 WARN 2줄로 남김 | **INFO 강등 또는 제거** |
| `logging.level` | 저장소 전체 0건, Boot 기본값에 암묵 의존 | **명시.** `root`·`org.flywaydb`·`io.mongock`=INFO |

기동 성공 로그는 프레임워크가 이미 남기는 배포 감사 성격이다. 정책은 '새로 추가한다'가 아니라 '억제하지 않는다'로 족하다.

### 수집·반출

**dev는 CloudWatch Agent가 앱이 쓴 클린 JSON 파일을 tail하고, prod는 정의된 ECS `awslogs` 배선을 그대로 쓴다.** Docker json-file 드라이버는 각 줄을 `{"log":"…"}`로 이중 래핑하지만, ECS `awslogs`는 stdout을 그대로 실어 이중 래핑이 없다.

| 항목 | dev | prod |
|---|---|---|
| 경로 | Agent가 `/logs/app.json` tail. 컨테이너의 `/logs`는 호스트 `/opt/kohere/logs` 바인드 마운트다 — 마운트가 없으면 컨테이너 쓰기 레이어에 갇혀 Agent가 못 읽고 `--force-recreate`에 사라진다 | `awslogs` 드라이버(`infra/terraform/modules/prod/ecs/main.tf:57-64`) |
| Log Group | `/kohere/dev/app` | `/ecs/${name_prefix}`(기존 유지) |
| Log Stream | `{instance-id}/kohere-app` | `app/{container-name}/{task-id}`(`awslogs-stream-prefix = "app"`) |
| retention | 30일, 무기한 금지 | 기존 `log_retention_days` 변수 |
| IAM | `CreateLogStream`·`PutLogEvents`·`DescribeLogStreams`를 Log Group ARN에 스코프한 인라인 정책(관리형 `logs:*` 미사용) | 기존 task execution role |
| 도입 게이트 | **통과.** 실측 `available` 656 MiB > 300MB, 스왑 2 GiB 중 29 MiB만 사용([ADR-0026](./0026-dev-host-memory-budget.md)). `free`가 아니라 `available`로 판정한다 — `free`가 낮은 것은 회수 가능한 페이지 캐시 때문이다. 되돌리려면 `enable_cloudwatch_agent=false` | CD 연결 시점에 실적재 검증 |

Log Group 네이밍이 환경별로 갈리는 것은 prod가 ECS 관례를 이미 커밋했기 때문이다. 통일은 prod CD 연결 시점에 재검토하고, 그전까지 쿼리는 Log Group을 명시한다. 둘 다 terraform 관리다([ADR-0019](./0019-infrastructure-as-code-terraform.md)).

**진짜 갭은 앱 코드가 아니라 반출 경로에 있다.** 아래 셋은 앱이 무엇을 남기든 해결되지 않는 인프라·파이프라인 작업이며 별개 이슈로 추적한다.

| 갭 | 내용 |
|---|---|
| 기동 실패 로그 영구 소실 | `container_name` 고정 + `deploy.yml:87`의 `--force-recreate`가 옛 컨테이너를 rm → 로그 디렉터리째 삭제. 보존 기간이 구조적으로 0이고 복구 수단이 곧 증거 삭제 수단이다 |
| CI가 기동 결과를 안 본다 | `deploy.yml:83-89`가 SSM CommandId만 echo하고 종료. `up -d`는 즉시 반환해 마이그레이션 성패가 출력에 들어올 수 없다 — 기동 실패해도 워크플로가 초록색이다 |
| ~~로테이션 없음~~ **해소** | 두 겹을 함께 닫았다 — `/logs/app.json`은 `RollingFileAppender`(50MB×7일, 총 500MB 상한), 컨테이너 stdout은 compose `logging` 블록(app 50MB×3, 나머지 10MB×3). 접근 로그가 전 요청 INFO라 방치하면 20GB 루트 볼륨이 차는 것은 시간 문제였다 |

### 범위 제외

**#152 원안 ①의 무차별 예외 로깅은 채택하지 않는다.** 예외는 용도 5(5xx·`Error`·삼킨 예외)와 용도 3(401/403)이 나눠 담는다. 4xx `BusinessException`·검증 실패·malformed·405·404는 접근 로그의 `status`·`errorCode`로만 관측되고 스택은 남지 않는다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| 모든 예외를 빠짐없이 로깅(#152 원안) | 누락 없음 | 4xx·검증 실패·봇 404까지 전량 적재 | 용도를 먼저 한정하는 편이 관측 가치·비용 모두 낫다 |
| 용도별 라인을 1:1로 신설 | 용도와 라인이 대응 | 같은 요청이 3~4줄로 흩어지고 수집량 배가 | 용도 4·cross-store를 필드로 흡수 |
| cross-store 결정을 용도 1·3에 분산 | 라인 소유자와 결정 위치가 일치 | 용도 5를 읽는 사람이 찾지 못한다 | 결정은 용도 5, 구현만 기존 라인에 |
| 활동 로깅을 `@Aspect`로 | 서비스 코드 무침습 | 어드바이스가 도메인 인자에서 값을 꺼내야 해 결합이 더 깊다 | 인터셉터 + 서비스 명시 호출 7종 |
| 활동 추적을 도메인 이벤트 구독으로 | 이벤트명이 곧 비즈니스 의미 | 실제 발행은 `UserWithdrawnEvent` 1건뿐, `BookingCreatedEvent`는 구독만 있는 dead path | 접근 로그 + 명시적 7종 |
| logback CloudWatch appender(AWS SDK) | 파일·Agent 불필요 | 앱↔AWS 결합, 힙·스레드 부담, 전송 실패가 앱에 전이 | 내용과 전송을 직교로 유지 |

## Consequences

| 구분 | 내용 |
|---|---|
| 긍정 | 401/403이 처음 관측되고, 타임아웃 없는 외부 호출 3건이 `latencyMs`로 드러나며, 스텁 폴백 활성 여부가 기동 로그로 판별된다 |
| 긍정 | `userId`가 숫자(게스트는 `anonymous`)라 원천 PII-안전이고 MDC 생명주기가 필터 하나로 닫힌다 |
| 부정 | 신규 의존·필터·인터셉터로 표면적이 늘고 `JSON_FILE`이 볼륨 마운트·로테이션을 요구한다 |
| 부정 | 데이터 변경 7종과 `stores`는 도메인 서비스에 로깅 호출이 들어가 침습적이다 |

| 잔여 사각지대 | 이유 |
|---|---|
| 커넥션 풀 포화·힙 사용량 | 메트릭 영역. 풀 설정 자체가 전무해 선행 과제다 |
| 호스트 메모리·스왑 | 도입한 CloudWatch Agent가 `logs` 전용이라 `metrics` 섹션이 없다. 도입 게이트를 통과시킨 근거(`available`·스왑)를 정작 지속 관측할 수단이 없어 확인이 SSM `free -m` 수동이다 — 지표화는 [ADR-0026](./0026-dev-host-memory-budget.md) 후속 작업 |
| `GET /api/v2/listings/{listingId}`의 Mongo 쓰기(`ListingService:162`) | 최근 본 매물 이벤트 제외의 대가로 수용. v1 상세는 스텁(404)이라 쓰기 자체가 없다 |
| 게스트와 미인증이 로그에서 같은 `anonymous` | 정상 게스트 트래픽과 토큰 미전송 오류가 `userId`만으로는 안 갈린다 — `pathPattern`이 `permitAll` 경로인지로 사후 판별한다 |
| `ApplicationRunner` 8개의 실행 순서 | `@Order`가 없어 미보장 — 관측만 하고 보장하지 않는다 |
| prod 반출 검증 | `deploy.yml`이 dev만 배선. ECS Terraform은 CD 미연결이라 실적재 확인 불가 |

| 롤아웃 | 내용 |
|---|---|
| ① | MDC + `logback-spring.xml` + `JSON_FILE` + `logging.level` 명시 |
| ② | 용도 3 + 기동 레벨 정정(ERROR 승격·시드 러너 강등) |
| ③ | 용도 1 접근 로그(용도 4 `latencyMs` 포함) |
| ④ | 용도 1 데이터 변경 7종 + `stores` 필드 |
| ⑤ | 용도 2 호출 래퍼 + 용도 5 `Throwable` 확대 |
| ⑥ | CloudWatch Agent·Log Group·로테이션 |

①이 먼저다. `traceId` 없이 용도 3부터 넣으면 보안 로그가 접근 로그와 조인되지 않는 기간이 생긴다. ①~⑤는 재배포로 끝나고 ⑥만 `user_data` 변경이라 EC2 재생성 1회가 필요하다.

## Open Questions

| 질문 | 파급 | 기한 |
|---|---|---|
| 용도 1 활동 로그를 CloudWatch만 볼지, 폴리글랏 DB에도 영속할지 | 저장소 선택·모듈 소유권 — 영속하면 활동 로그가 도메인 자산이 된다 | ④ 착수 전 |
| 외부 연동 3건의 타임아웃 값 | 본 ADR 범위 밖이나 용도 4 임계와 정합해야 한다 | 별도 이슈 |

## Validation

| 용도 | 검증 | 통과 기준 |
|---|---|---|
| 1 | 매핑 65개와 제외 경로 호출, 연속 요청 | 65건 각 1줄·제외 0줄·익명은 `anonymous`, `traceId` 잔류 0건 |
| 1 | 제외 4종(예약 삭제·차단 해제·찜 추가·해제) 유발 | `pathVars`로 대상 식별자가 복원돼 별도 이벤트 없이 "누가 무엇을"이 확정된다 |
| 1 | 데이터 변경 7종 유발 | 접근 로그와 같은 `traceId`로 조인, `PROFILE_UPDATED`는 `lang`만 값 노출 |
| 1 | 게스트로 퀴즈·생활 팁·v2 진단·매물 탐색 GET(`/api/v2/listings`) 호출 | `userId=anonymous`로 접근 로그 1줄, v2 진단 단계는 `guestSessionId`로 추적된다 |
| 1 | 종료된 v1 매물 조회(목록·상세) 호출 | `pathPattern`이 `/api/v1/listings…`인 접근 로그 1줄씩 — 200 빈 페이지와 404가 모두 남아 구버전 앱 잔존 트래픽이 집계된다 |
| 2 | 어댑터 6개를 성공·4xx·타임아웃으로 유발 | `outcome`·`latencyMs`가 기대값, 502 1건에 WARN 1줄만(ERROR 중복 0) |
| 2 | 스텁·폴백 5개 활성으로 기동 | 활성 어댑터 5줄, 인증번호 출력 0건 |
| 3 | 만료 401·위조 401·403·`REVOKED` 토큰 `reissue` | 각각 INFO·WARN·WARN·WARN |
| 3 | 만료 토큰으로 게스트 허용 경로(퀴즈)와 공개 티어(`reissue`) 호출 | 전자는 필터가 401 INFO 1줄, 후자는 의도대로 통과해 401 0줄 — `PublicPaths` 경계가 로그로 확인된다. 401 자체는 #181부터 나가고 있었고 본 ADR의 델타는 그 401을 관측 가능하게 만드는 것뿐이다 |
| 3 | social-login 성공 | `userId`가 발급된 숫자(`anonymous` 아님) |
| 3 | 만료 401과 위조 401을 각각 유발 | 만료는 `userId`가 실제 숫자, 위조는 `anonymous` — 검증되지 않은 `sub`가 로그에 절대 들어가지 않는다 |
| 4 | 외부 호출이 포함된 요청 1건 | 같은 `traceId`로 총 지연 중 외부 구간 분해 가능 |
| 5 | 5xx·`java.lang.Error`·이니셜라이저 실패 유발 | 전부 ERROR + 스택 |
| 5 | 정상 요청 100건 | 용도 5 라인 0건 |
| 5 | cross-store 3흐름 유발 | `stores`에 흐름별 키가 4상태 중 하나로 기록 |
| 공통 | 코드리뷰 게이트, `./gradlew build` | 토큰·인증코드·PII 원문 0건, `ModularityTest` green |
| 수집 | Logs Insights 쿼리, 수집량·메모리 실측 | 이중 래핑 없는 JSON에 `userId`·`traceId`·`pathPattern`·`pathVars`·`target` 필터 동작, 200MB/일 이내(≈ 월 $5)·여유 300MB 이상(미달 시 ⑥ 보류) |

재검토 시점: 비동기 경로가 생길 때(MDC 전파), MSA 전환 시(Micrometer Tracing), prod CD 연결 시(Log Group 통일), 수집량이 예산을 넘을 때(감축 순서).
