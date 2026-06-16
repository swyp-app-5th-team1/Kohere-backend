# ADR-0003. OAuth(OIDC) 로그인 이후 인증은 서버 발급 JWT(stateless) 방식으로 한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0003 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-15 |
| 관련 문서 | [01-auth-onboarding](../api/specs/01-auth-onboarding.md), [error-response-guide §3·§4](../api/error-response-guide.md), [ADR-0001](./0001-bounded-context-module-decomposition.md), [code-style §3](../convention/code-style.md), [ADR-0006](./0006-refresh-token-store-redis.md) |

## Status

Accepted

> 클라이언트는 **모바일 앱**이고, 로그인은 **소셜(Apple/Google) OIDC** 다([01-auth-onboarding](../api/specs/01-auth-onboarding.md)). 본 ADR은 "OAuth 검증 이후 세션을 무엇으로 유지할지"를 결정한다. 스펙 01은 이미 JWT(access+refresh)를 전제하며, 본 ADR이 그 근거를 명시한다.

## Context

- **클라이언트가 모바일 앱**이다([api-design-guide](../api/api-design-guide.md)는 "클라이언트(모바일 앱)"를 전제). 브라우저 쿠키/세션 모델이 자연스럽지 않다.
- **로그인은 소셜 OIDC**: 앱이 Apple/Google에서 받은 `idToken`을 서버가 서명·`aud`·`iss`·`exp`로 검증한다. 이 검증은 **로그인 시점 1회**이며, 이후 매 요청마다 provider에 다시 묻는 것은 비현실적이다 → 서버가 **자체 인증 상태**를 발급해야 한다.
- 인증 헤더는 `Authorization: Bearer <accessToken>`, 갱신은 `POST /api/v1/auth/reissue`로 스펙에 고정되어 있다([01-auth-onboarding](../api/specs/01-auth-onboarding.md)). 만료는 `401 TOKEN_EXPIRED`로 재발급을 유도한다([error-response-guide §3](../api/error-response-guide.md)).
- 아키텍처는 현재 단일 모듈러 모놀리식이지만, 수평 확장(인스턴스 다중화) 가능성을 열어 둔다.
- 온보딩 단계(PENDING 사용자)에는 온보딩 API만 통과시키는 **스코프 제한 임시 토큰**(`onboardingCompleted=false`)이 필요하다.
- 따라서 "OAuth 이후 인증 상태 유지 방식"을 **서버 세션 vs JWT** 중에서 결정해야 한다.

## Decision

**OIDC `idToken` 검증 성공 후, 서버가 자체 발급한 JWT(access token + refresh token)로 무상태(stateless) 인증을 수행한다.** 세부 정책:

1. **소셜 검증은 로그인 1회**: provider `idToken` 검증 후 서버 JWT를 발급하고, 이후 요청은 provider와 무관하게 서버 JWT로 인증한다(provider 결합 분리).
2. **access token**: 짧은 만료(예: 1시간 — 정책값). `Authorization: Bearer`로 전송. **무상태**(서버 조회 없이 서명·만료만 검증).
3. **refresh token**: 긴 만료. **불투명(opaque) 랜덤 토큰**으로 발급하고 **서버에 해시로 저장**해 무효화·회전·재사용 탐지를 가능하게 한다([auth 모듈의 `RefreshToken` 애그리거트](../../src/main/java/com/kohere/auth/domain/RefreshToken.java)). 어차피 서버가 저장·조회하므로 refresh를 JWT로 둘 이점이 없고(클레임 비노출·폐기 용이), 형식은 불투명 토큰으로 한다([01-auth-onboarding §개요 "토큰 모델"](../api/specs/01-auth-onboarding.md)). 재발급 시 **회전(rotation)**, 폐기 토큰 재사용 탐지 시 해당 사용자의 모든 refresh 무효화([01-auth-onboarding §3](../api/specs/01-auth-onboarding.md)).
4. **로그아웃/탈퇴**: refresh token을 서버에서 무효화한다. (access는 짧은 만료로 위험 창을 최소화.)
5. **온보딩 임시 토큰**: 신규 회원에게는 `onboardingCompleted=false` 클레임의 스코프 제한 access 토큰만 발급(refresh 미발급), 온보딩 완료 시 정식 access+refresh로 교체.
6. 결과적으로 본 설계는 **"무상태 JWT access + 불투명(서버 저장) refresh"** 하이브리드다 — JWT의 확장성과 세션의 무효화 가능성을 절충한다.

> JWT의 **검증**(매 요청 토큰 파싱)은 도메인 `auth` 모듈이 아니라 **횡단 보안 필터**의 책임이다(인증 메커니즘 ≠ `auth` BC). 그 배치는 별도 ADR로 다룬다.

## Alternatives

표는 "OAuth 검증 이후 **세션 유지(주로 access 경로)** 를 무엇으로 할지"를 비교한다. refresh 토큰을 **불투명 + 서버 저장**으로 두는 것은 채택안의 일부이며 별도 차원이다(아래 참고).

| 항목 | 서버 세션 방식 | **JWT access + 불투명 refresh (채택)** | 전면 불투명 토큰(access 포함) |
| --- | --- | --- | --- |
| 상태 저장 | 서버 세션 저장소(메모리/Redis) | **무상태**(access는 토큰 자체로 검증) | 서버 저장(매 요청 조회) |
| 수평 확장 | 세션 공유 저장소 또는 sticky session 필요 | **인스턴스 무상태로 용이** | 저장소 조회 필요 |
| 모바일 적합성 | 쿠키 기반이라 모바일 앱에 부자연 | **`Authorization` 헤더로 자연스러움** | 헤더로 가능 |
| 무효화(로그아웃·강제 차단) | 즉시(세션 삭제) | access는 만료까지 유효(약점) → 짧은 만료 + refresh 회전/서버저장으로 보완 | 즉시(저장소에서 폐기) |
| 매 요청 비용 | 세션 조회 1회 | **서명 검증만(저장소 무조회)** | 저장소 조회 1회 |
| CSRF | 쿠키라 노출(대응 필요) | **헤더 토큰이라 CSRF 표면 작음**(XSS·기기 저장 보안은 별도) | 헤더면 작음 |
| 대역폭 | 작음(세션 id) | 토큰이 매 요청 전송(다소 큼) | 토큰 전송(작게 가능) |
| OAuth provider 결합 | — | **로그인 후 분리(자체 토큰)** | 분리 |
| 구현/운영 | 프레임워크 기본, 단순 | 발급/검증/회전/키 관리 직접 | 발급+저장소+조회 |

- **세션 방식 미채택 이유**: 클라이언트가 모바일 앱이라 쿠키/세션 모델이 부자연스럽고, 수평 확장 시 세션 공유 저장소(Redis)나 sticky session이 필요해 무상태 이점을 잃는다. OIDC 토큰 검증 결과를 매 요청 세션 조회로 잇는 것은 모바일 + 무상태 지향과 맞지 않는다.
- **access까지 불투명 토큰(introspection)으로 두는 방식 미채택 이유**: access마저 매 요청 서버 저장소 조회가 필요해 무상태 이점이 사라진다. 즉 불투명 토큰을 **전면 거부한 것이 아니라 access에만 미적용**이며, **refresh token은 불투명 + 서버 저장(해시)을 채택**한다(Decision 3·6). 따라서 본 결정은 "무상태 JWT access + 불투명 refresh" **하이브리드**다.

## Consequences

- **긍정**
  - access 경로가 **무상태**라 인스턴스 수평 확장에 세션 공유 인프라가 불필요하다.
  - 모바일 앱과 자연스럽게 맞는 `Bearer` 헤더 모델, 쿠키 CSRF 표면 축소.
  - 로그인 이후 OAuth provider와 분리되어 provider 장애·지연이 일반 요청에 영향 없다.
  - 토큰 클레임(예: `onboardingCompleted`, 역할)로 스코프 제한 토큰을 자연스럽게 표현한다.
- **부정/트레이드오프**
  - **access token 즉시 무효화가 어렵다**(만료까지 유효) → **짧은 access 만료 + refresh 회전·서버 저장·재사용 탐지**로 완화. 강제 차단이 필요하면 access 블랙리스트(짧은 TTL 캐시)를 추가 검토.
  - 토큰 탈취 시 위험 → 앱 **보안 저장소(Keychain/Keystore)** 보관, 전 구간 HTTPS, 민감정보(비자번호·전화번호 등)는 **클레임/로그에 비포함**([error-response-guide §6](../api/error-response-guide.md)).
  - 서명 **키 관리·회전** 운영 부담(아래 후속).
- **후속 작업**
  - 서명 알고리즘 결정: 대칭 **HS256**(단일 서버, 단순) vs 비대칭 **RS256/ES256**(검증 측 분리·키 회전 유리) → **확정: [ADR-0009](./0009-jwt-signing-algorithm-hs256.md)**(현재 HS256·단일 신뢰 경계, MSA 분해·외부 검증자 도입 시 RS256/ES256+JWKS 전환).
  - access/refresh **만료 시간**, refresh **회전 정책**, refresh **저장소**는 **[ADR-0006](./0006-refresh-token-store-redis.md)에서 Redis로 확정**(스펙의 "확인 필요" 항목).
  - JWT **검증 횡단 필터**(Spring Security 필터 체인) 배치 — 인증 메커니즘 ADR로 분리.
  - 키/시크릿 주입(환경변수/시크릿 매니저), 키 회전 절차.

## Validation

- **인증 흐름 테스트**: social-login → (신규)온보딩 토큰 → onboarding → access+refresh, reissue 회전, 폐기 토큰 재사용 시 전체 무효화, logout 후 reissue 실패를 통합 테스트로 검증.
- **만료/재발급 계약**: 만료 access로 보호 API 호출 시 `401 TOKEN_EXPIRED`, reissue로 복구되는지 검증([error-response-guide §3](../api/error-response-guide.md)).
- **보안 점검**: 토큰 페이로드·로그에 민감정보 미포함, refresh 무효화 동작, HTTPS 강제.
- **재검토 시점**: 강제 로그아웃/세션 무효화 요구가 강해지거나(규제 등) access 즉시 무효화가 필수가 되면 불투명 토큰/세션 하이브리드를 재검토한다.
