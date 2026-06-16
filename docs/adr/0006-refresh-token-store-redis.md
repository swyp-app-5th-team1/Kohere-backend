# ADR-0006. 리프레시 토큰 저장소는 Redis로 둔다 (ADR-0005 보완)

| 항목 | 값 |
|---|---|
| 번호 | ADR-0006 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-15 |
| 관련 문서 | [ADR-0003](./0003-jwt-auth-after-oauth-login.md), [ADR-0005](./0005-polyglot-persistence.md), [01-auth-onboarding](../api/specs/01-auth-onboarding.md), [system-overview §3-2·§3-7](../architecture/system-overview.md) |

## Status

Accepted

> [ADR-0003](./0003-jwt-auth-after-oauth-login.md)은 refresh 토큰을 "불투명 + 서버에 해시로 저장"으로 정하면서 **저장 backend(DB vs Redis)는 후속 결정으로 남겼다**(0003 §후속). [ADR-0005](./0005-polyglot-persistence.md)는 데이터 특성 기준 폴리글랏을 정하며 `auth`(`RefreshToken` 포함)를 MySQL에 두었다. 본 ADR은 그 후속을 닫아 **리프레시 토큰의 *저장*만 Redis로** 옮긴다 — [ADR-0005](./0005-polyglot-persistence.md)의 `RefreshToken` 배치를 이 점에서 **보완**한다. `auth`의 계정·소셜 연동 등 나머지는 MySQL을 유지한다.

## Context

- [ADR-0003](./0003-jwt-auth-after-oauth-login.md): **access = 무상태 JWT**, **refresh = 불투명 랜덤 토큰을 서버에 해시로 저장**(회전·무효화·재사용 탐지). 저장 backend는 "DB vs Redis"로 **미결**.
- [ADR-0005](./0005-polyglot-persistence.md): 데이터 특성 기준으로 `auth`는 "계정 lifecycle·토큰 회전/무효화의 트랜잭션성"을 근거로 MySQL에 배치(이때 `RefreshToken` 포함).
- refresh 토큰의 실제 접근 패턴은 관계형의 강점(조인·다엔티티 트랜잭션)을 쓰지 않는다.
  - (a) **해시 등치 조회 1건**(reissue 검증), (b) 매 reissue마다 **회전**(기존 폐기 + 신규 저장), (c) 만료 시 **자동 소멸**, (d) 재사용 탐지 시 **사용자 전체 일괄 폐기**.
  - 즉 **고빈도 쓰기 + TTL 기반 만료**가 본질이며, 이는 키-값 + TTL 저장소(Redis)의 강점과 정확히 맞는다.
- 운영은 AWS. access는 무상태라 인스턴스 다중화 시 공유가 불필요하지만, **refresh 상태는 인스턴스 간 공유 저장소**가 필요하다.
- 따라서 ADR-0003이 연 "refresh 저장소" 후속을 닫고, ADR-0005의 `RefreshToken` 배치를 데이터 접근 특성 기준으로 보완해야 한다.

## Decision

**리프레시 토큰을 Redis(AWS ElastiCache)에 저장한다.** 세부 정책:

> **정정(2026-06-16):** 회전·무효화를 *키 삭제* → **`status` 전이(보존)** 로 바로잡았다(아래 1·3·4). 폐기 토큰을 만료까지 보존해야 **재사용 탐지**가 성립하기 때문이며(삭제하면 위조·만료와 구분 불가), 도메인 모델 `RefreshTokenStatus`(`ACTIVE`/`ROTATED`/`REVOKED`)·[database-design §4-1](../database/database-design.md)와 정합한다.

1. **저장 형태**: 키 `refresh:{tokenHash}` → 값 `{ userId, status, issuedAt, expiresAt }`(`status` ∈ `ACTIVE`/`ROTATED`/`REVOKED` — 도메인 `RefreshTokenStatus`), **TTL = refresh 만료 시각**. 만료 시 폐기(`ROTATED`/`REVOKED`) 레코드까지 자동 소멸하므로 **별도 정리 배치가 불필요**하다. (초안의 boolean `revoked`를 3-상태 `status`로 일반화.)
2. **해시**: ADR-0003대로 **불투명 랜덤 토큰의 SHA-256(+pepper) 해시**로 저장한다(등치 조회가 필요 → BCrypt/Argon2 같은 adaptive hash는 불가).
3. **회전(rotation)·재사용 탐지**: reissue 시 제출 토큰을 `status=ROTATED`로 **전이(만료까지 보존)** 하고 신규 `ACTIVE` 토큰을 저장한다. **폐기 토큰을 삭제하지 않고 보존하는 이유**는, 이미 `ROTATED`/`REVOKED`인 토큰이 다시 제출되는 것을 **재사용(탈취) 정황으로 탐지**하기 위함이다 — 삭제하면 위조·만료 토큰과 구분할 수 없어 탐지가 불가능하다. 사용자별 일괄 폐기를 위해 `refresh:user:{userId}` 인덱스(Set)로 보유 토큰을 추적하고, **재사용 탐지 시 해당 사용자의 모든 refresh 토큰을 `REVOKED`로 전이(일괄 무효화)** 한다(ADR-0003의 재사용 탐지 정책 실현).
4. **로그아웃/탈퇴**: 해당 refresh 토큰(들)을 `REVOKED`로 전이한다(이미 폐기여도 멱등). 보존 레코드는 TTL로 자동 정리된다.
5. **범위 한정**: 본 결정은 **refresh 토큰 저장에 한정**한다. **access 토큰은 무상태 유지**(변경 없음)이고, `auth`의 계정·소셜 연동·`user`·`community`는 **MySQL을 유지**한다([ADR-0005](./0005-polyglot-persistence.md)). 결과적으로 `auth`는 **MySQL(계정) + Redis(refresh)** 혼합이다.
6. **내구성 설정**: ElastiCache는 **AOF + 복제(replication)** 를 활성화한다(§Consequences의 부활 위험 완화).

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. Redis (채택)** | TTL 자동만료(정리 배치 불필요), O(1) 회전/조회, 관계형 쓰기 부하 분리, 다중 인스턴스 공유 | 운영 저장소 +1, 내구성이 AOF/복제 설정에 의존, 페일오버 시 폐기 토큰 부활 위험 | — |
| **B. MySQL (ADR-0005 원안)** | 강한 내구성·일관성, `auth` 단일 store | 만료 토큰 정리 배치 필요, 매 회전마다 RDB 쓰기 churn, 네이티브 TTL 없음 | refresh 접근 패턴이 관계형 강점(조인·트랜잭션)을 쓰지 않아 본전을 못 뽑음 |
| **C. MySQL(내구) + Redis(캐시) 이중화** | 내구성 + 속도 | 이중 쓰기·정합 복잡도 | MVP에 과투자 |

## Consequences

- **긍정**
  - **TTL 자동만료**로 만료 토큰 정리 배치가 불필요하다.
  - 회전·조회가 저지연(O(1))이고, refresh 쓰기 churn을 관계형 DB에서 분리한다.
  - 다중 인스턴스에서 refresh 상태를 공유한다(access 무상태와 결합해 수평 확장 친화).
- **부정/트레이드오프**
  - 운영 저장소가 **3종(MySQL·MongoDB·Redis)** 이 된다(인프라·모니터링·백업 비용 증가).
  - 내구성이 **AOF/복제 설정에 의존**한다. **페일오버/재시작 시 폐기·로그아웃된 토큰이 부활(재생)** 할 수 있다 → 완화: **AOF + 복제**, **TTL 타이트**(= refresh 만료), 강한/규제성 즉시 폐기가 필요하면 폐기 상태를 MySQL로 옮기거나 **access 단기 블랙리스트**(ADR-0003 후속) 추가.
  - **[ADR-0005](./0005-polyglot-persistence.md) 보완**: `RefreshToken`의 *저장*만 Redis로 이동(나머지 `auth`는 MySQL). **[ADR-0003](./0003-jwt-auth-after-oauth-login.md)의 "refresh 저장소(DB vs Redis)" 후속이 닫힌다.**
- **후속 작업**
  - [build.gradle](../../build.gradle)에 `spring-boot-starter-data-redis` 추가, ElastiCache(AOF·복제) 구성.
  - `RefreshTokenRepository` 구현을 Redis로(키 설계·사용자 인덱스·TTL), 통합 테스트 추가.

## Validation

- **인증 흐름 테스트**(Testcontainers Redis): 회전, **폐기 토큰 재사용 시 사용자 전체 무효화**, 로그아웃 후 reissue 실패, **TTL 만료 후 자동 소멸**을 검증한다.
- **운영 점검**: ElastiCache AOF/복제 활성 여부, 페일오버 시 동작.
- **재검토 시점**: 강한/규제성 즉시 무효화가 필수가 되거나 페일오버 부활 위험이 허용 불가가 되면, 폐기 상태를 MySQL로 이전하거나 이중화(대안 C)를 재검토한다.
