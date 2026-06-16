# ADR-0008. MySQL 스키마 마이그레이션은 Flyway로 관리한다 (폴리글랏 마이그레이션 전략)

| 항목 | 값 |
|---|---|
| 번호 | ADR-0008 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-16 |
| 관련 문서 | [ADR-0005](./0005-polyglot-persistence.md), [ADR-0006](./0006-refresh-token-store-redis.md), [migration-policy](../database/migration-policy.md), [database-design §3](../database/database-design.md), [system-overview §3-2](../architecture/system-overview.md), [build.gradle](../../build.gradle) |

## Status

Accepted

> [ADR-0005](./0005-polyglot-persistence.md) Decision 7이 "MySQL 스키마 변경은 마이그레이션 도구로 관리한다(**Flyway 후보**, [migration-policy](../database/migration-policy.md)에서 확정)"로 남긴 후속을 닫는다. 폴리글랏(MySQL·MongoDB·Redis)이라 **스토어별 변경 관리 방식이 다르다** — 본 ADR은 **MySQL 도구를 Flyway로 확정**하고, MongoDB·Redis의 변경 관리 방식도 함께 정한다. 세부 운영 규칙은 [migration-policy](../database/migration-policy.md)가 정본이다.

## Context

- 영속은 폴리글랏이다([ADR-0005](./0005-polyglot-persistence.md)·[ADR-0006](./0006-refresh-token-store-redis.md)): **MySQL**(`auth` 소셜연동·`user`·`community`), **MongoDB**(`listing`·`diagnosis`), **Redis**(refresh 토큰).
- **MySQL은 관계형 스키마**(테이블·컬럼·제약·인덱스 DDL)라 변경을 **버전 관리·재현·검증**해야 한다. 로컬↔클라우드가 **동일 이미지**로 돌고([system-overview §1-3](../architecture/system-overview.md)) 무중단 배포를 목표로 하므로, 환경 간 스키마 드리프트를 막는 결정적 적용이 필요하다.
- Hibernate `ddl-auto`(auto-DDL: `update`/`create`)는 **운영에서 위험**하다 — 비결정적이고 데이터 손실 가능, 변경 이력·검증이 없다.
- **MongoDB는 스키마리스**라 DDL 마이그레이션이 없지만 **인덱스**(`2dsphere`·TTL·unique)와 문서 구조 진화는 관리해야 한다([database-design](../database/database-design.md)). **Redis는 스키마가 없다**(키스페이스·TTL은 코드 상수, [ADR-0006](./0006-refresh-token-store-redis.md)).
- 현재 [build.gradle](../../build.gradle)에는 마이그레이션 도구가 **미배선**이다.
- 따라서 "MySQL 마이그레이션 도구"를 확정하고, 폴리글랏 전체의 변경 관리 방식을 정해야 한다.

## Decision

**MySQL 스키마 마이그레이션은 [Flyway]로 관리한다.** 스토어별 변경 관리는 다음과 같다(세부는 [migration-policy](../database/migration-policy.md)).

1. **MySQL = Flyway** (`flyway-core` + `flyway-mysql`). **버전드 SQL 마이그레이션**(`V{버전}__{설명}.sql`)을 `src/main/resources/db/migration`에 두고, **forward-only·불변**(적용된 파일 수정 금지, 변경은 새 버전으로)으로 관리한다. 반복 실행 객체(뷰 등)는 `R__`. 기존 스키마는 **baseline**로 채택한다.
2. **DDL 권한은 Flyway만.** Hibernate는 **`ddl-auto=validate`**(운영) — 스키마 생성/변경은 하지 않고 엔티티↔스키마 일치만 검증한다.
3. **MongoDB = 스키마리스 + 명시적 관리.** 인덱스(`2dsphere`·TTL·unique)는 **부트스트랩/마이그레이션 스크립트**로 기동 시 **멱등 생성**한다. 문서 구조 진화는 **애플리케이션 레벨 `schemaVersion` 필드** + 점진(lazy/배치) 마이그레이션으로 처리하고, **파괴적 일괄 변경은 피한다**([ADR-0005](./0005-polyglot-persistence.md) D7).
4. **Redis = 스키마 없음.** 키스페이스·TTL은 코드 상수([ADR-0006](./0006-refresh-token-store-redis.md))다. 키 구조를 바꿔야 하면 **키 네임스페이스 버전**(예: `refresh:v2:{...}`) 또는 **TTL 만료에 의한 자연 교체**로 이행한다(별도 DDL 없음).
5. **무중단 배포 = expand-contract.** 호환(확장) 변경을 먼저 배포 → 데이터 이행 → 구 컬럼/인덱스 제거(축소)는 후속 릴리스로 분리한다(특히 MySQL `NOT NULL` 추가·컬럼 제거·rename).
6. **세부 운영 규칙**(네이밍·되돌리기 분류·`NOT NULL` 추가 절차·인덱스 락·리뷰/배포 흐름·체크리스트)은 [migration-policy](../database/migration-policy.md)를 정본으로 한다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. Flyway (채택)** | 네이티브 **SQL**이라 단순·가독성↑, **Spring Boot 일급 통합**, MySQL 일급 지원, 학습 쉬움, baseline 용이 | DB별 SQL(이식성 낮음), 고급 리팩토링·롤백 기능 적음 | — |
| **B. Liquibase** | XML/YAML/JSON **changelog**로 DB 독립·자동 롤백·고급 기능 | DSL 학습곡선·추상화 오버헤드, SQL 대비 가독성↓ | MVP엔 SQL 직접이 단순·충분(대상이 **MySQL 단일**이라 DB 독립 이점이 작음) |
| **C. Hibernate `ddl-auto`(update/create)** | 도구 0, 빠른 프로토타이핑 | **운영 위험**(데이터 손실·비결정), 버전 이력·검증 없음 | 운영 부적합 — `validate`로만 보조 사용 |
| **D. 수동 SQL 스크립트** | 도구 0 | 적용 이력·재현·검증 없음, 환경 드리프트 | 추적성·자동 적용 부재 |

## Consequences

- **긍정**
  - 스키마 변경이 **버전·재현·검증**된다 — 로컬·CI·클라우드가 같은 마이그레이션으로 동일 스키마를 만든다.
  - JPA `validate`로 **엔티티↔스키마 불일치를 기동 시 차단**한다.
  - **expand-contract**로 무중단 배포가 가능하다([system-overview §1-3](../architecture/system-overview.md)).
- **부정/트레이드오프**
  - 마이그레이션 작성 **규율**이 필요하다(forward-only·불변·1 변경 1 파일).
  - DB별 SQL이라 **이식성이 낮다**(단 대상이 MySQL 단일이라 영향 작음).
  - 대형 테이블 인덱스 추가·`NOT NULL` 전환 시 **락**에 주의해야 한다(MVP 데이터량은 작아 영향 작음 — [migration-policy §6](../database/migration-policy.md)).
- **후속 작업**
  - [build.gradle](../../build.gradle)에 `flyway-core`·`flyway-mysql` 추가, `spring.jpa.hibernate.ddl-auto=validate` 설정.
  - `src/main/resources/db/migration` 골격 + **V1 baseline**(`users`·`social_accounts`·`posts`/`comments`/`post_likes`/`post_hashtags`).
  - **MongoDB 인덱스 부트스트랩** 컴포넌트(`2dsphere`·TTL·unique 멱등 생성).
  - CI에서 빈 DB에 마이그레이션 적용 검증(Testcontainers).
  - [migration-policy](../database/migration-policy.md) 작성(✅ 본 작업) · [database-design §3](../database/database-design.md)와 정합.

## Validation

- **적용 검증**: 빈 MySQL에 전체 마이그레이션 적용 → 기대 스키마 생성, JPA `validate` green(Testcontainers, 로컬·CI).
- **무중단 리허설**: expand-contract 시퀀스(호환 변경 → 이행 → 축소)가 가동 중 무중단인지 스테이징에서 확인.
- **재검토 시점**: 여러 RDB/DB 이식성이 필요해지거나 복잡한 자동 롤백이 요구되면 **Liquibase**(대안 B)를 재검토한다.

[Flyway]: https://documentation.red-gate.com/fd/flyway-documentation
