# Architecture Decision Records

> 중요한 기술/아키텍처 결정을 기록하는 ADR 폴더입니다.

## 목적

> (작성) ADR로 무엇을·왜 기록하는지 적는다.

## 언제 ADR을 작성하는가

> (작성) ADR로 남길 결정의 기준(되돌리기 비용이 큰 결정 등)을 적는다.

## 상태(Status) 정의

> (작성) Proposed/Accepted/Deprecated/Superseded/Rejected 등 사용할 상태를 정의한다.

## 파일 네이밍 규칙

> 파일명: `docs/adr/NNNN-kebab-case-title.md` (NNNN은 4자리 일련번호).
> 새 ADR은 [0000-adr-template.md](./0000-adr-template.md)를 복사해 작성한다.

## ADR 인덱스

| 번호 | 제목 | 상태 | 날짜 |
| --- | --- | --- | --- |
| [0000](./0000-adr-template.md) | ADR 템플릿 | — (템플릿) | — |
| [0001](./0001-bounded-context-module-decomposition.md) | 도메인(Bounded Context) 기준으로 모듈을 분해한다 | Accepted | 2026-06-15 |
| [0002](./0002-inter-module-communication-via-events.md) | 모듈 간 통신은 도메인 이벤트(Application Events) 기반으로 한다 | Accepted | 2026-06-15 |
| [0003](./0003-jwt-auth-after-oauth-login.md) | OAuth(OIDC) 로그인 이후 인증은 서버 발급 JWT(stateless) 방식으로 한다 | Accepted | 2026-06-15 |
| [0004](./0004-api-response-envelope.md) | API 응답을 공통 래퍼(`{ success, data, error }`)로 표준화한다 | Accepted | 2026-06-15 |
| [0005](./0005-polyglot-persistence.md) | 영속은 폴리글랏으로 — 데이터 특성에 따라 MongoDB와 MySQL로 나눈다 | Accepted | 2026-06-15 |
| [0006](./0006-refresh-token-store-redis.md) | 리프레시 토큰 저장소는 Redis로 둔다(ADR-0005 보완) | Accepted | 2026-06-15 |
| [0007](./0007-api-docs-spring-rest-docs.md) | API 문서는 테스트 기반 Spring REST Docs로 생성한다(Swagger 대비) | Accepted | 2026-06-16 |
| [0008](./0008-mysql-migration-flyway.md) | MySQL 스키마 마이그레이션은 Flyway로 관리한다(폴리글랏 전략) | Accepted | 2026-06-16 |
| [0009](./0009-jwt-signing-algorithm-hs256.md) | 서버 JWT 서명은 HS256으로 한다(MSA/외부 검증자 시 RS256+JWKS 전환) | Accepted | 2026-06-16 |

> 새 ADR을 추가하면 이 표에 한 행을 추가한다.

## 체크리스트

> (작성) ADR 작성 시 확인할 항목을 적는다.
