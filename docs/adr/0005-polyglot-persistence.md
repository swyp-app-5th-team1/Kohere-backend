# ADR-0005. 영속은 폴리글랏으로 — 데이터 특성에 따라 MongoDB와 MySQL로 나눈다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0005 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-15 |
| 관련 문서 | [ADR-0001](./0001-bounded-context-module-decomposition.md), [ADR-0002](./0002-inter-module-communication-via-events.md), [project-brief §7](../project/project-brief.md), [database-design](../database/database-design.md), [listings spec](../api/specs/03-listings-favorites.md), [diagnosis spec](../api/specs/02-diagnosis-recommendation.md), [ADR-0006](./0006-refresh-token-store-redis.md) |

## Status

Accepted

> 모듈 경계는 [ADR-0001](./0001-bounded-context-module-decomposition.md)에서, 모듈 간 통신은 [ADR-0002](./0002-inter-module-communication-via-events.md)에서 정했고 DB 설계는 아직 템플릿 상태다([database-design](../database/database-design.md)). 본 ADR은 "각 모듈의 영속 저장소를 **데이터 특성**에 따라 무엇으로 둘지"를 결정한다. **저장소 배치는 사람/팀 트랙 분담과 독립적이다**(분담은 [project-brief §7](../project/project-brief.md)에서 기능 응집·크리티컬 패스로 정한다).

## Context

- 아키텍처는 모듈러 모놀리식이며 모듈 경계 = Bounded Context다([ADR-0001](./0001-bounded-context-module-decomposition.md)). 엔티티는 모듈 간 비공유, cross-BC 협력은 도메인 이벤트/공개 쿼리 API로 한다([ADR-0002](./0002-inter-module-communication-via-events.md)).
- 모듈별 데이터 특성이 두 부류로 갈린다.
  - **문서·지오성 (MongoDB가 유리)**
    - **매물(`listing` + `favorite` + `recent-listing`)**: 핵심 질의가 **위치 기반**이다 — 지도 bbox(`$geoWithin`), 거리순(`$near`), 반경([listings spec](../api/specs/03-listings-favorites.md)). 매물 유형(고시원/코리빙/셰어하우스/기타)마다 속성 집합이 다르고 외부 수급으로 채워 스키마가 변동될 수 있다. **읽기 위주, 대량.**
    - **진단(`diagnosis`)**: 5단계 답(`purposes[]`·`conditions[]` 같은 **배열**) + 결과를 **통째로 읽고 쓰는 self-contained 애그리거트**다. 모듈 경계상 다른 모듈과 **조인이 없고**(추천은 값 객체 전달, 아래 Decision 2), 한 진단 = 한 도큐먼트라 **단일 도큐먼트 원자적 쓰기**로 충분하다. 유저당 소수 레코드로 규모 압력이 없다.
      - 핵심 질의도 `userId` 기준 **필터·정렬·소유권 검증**(이력 조회 / 최신 진단 / 본인 소유 확인)뿐이라 **JOIN·FK·다엔티티 트랜잭션 같은 관계형의 강점을 하나도 쓰지 않는다.** 관계형 DB는 "관계"가 있을 때 본전을 뽑는데 진단엔 관계가 없다 — 단순 질의 자체는 두 DB가 동등하므로 판별이 안 되고, 남는 기준인 **데이터 형태(배열·문서·원자적 쓰기)가 문서 모델을 가리킨다.** (반례로 `community`는 좋아요 유니크 제약·카운트 정합이라는 관계/정합이 실재해 MySQL에 둔다.)
  - **관계·트랜잭션성 (MySQL이 유리)**
    - **인증·회원(`auth` · `user`)**: 계정 lifecycle·리프레시 토큰 회전/무효화 등 **유니크 제약·트랜잭션 일관성**이 의미 있다.
    - **커뮤니티(`community`)**: `PostLike (postId, userId)` **유니크 제약**으로 중복 좋아요를 막고, `likeCount`/`commentCount` **카운트 정합**을 게시글·댓글·좋아요 **여러 엔티티에 걸쳐** 유지해야 한다(관계형 트랜잭션·행 잠금이 자연스럽다).
- 제약: 현재 코드는 골격 + CI만 있고 영속 스택은 도입 전이다([build.gradle](../../build.gradle)).
- 따라서 "단일 DB로 통일할지, 데이터 특성에 맞춰 저장소를 나눌지"를 결정해야 한다.

## Decision

**데이터 특성에 맞춰 영속 저장소를 둘로 나누는 폴리글랏(polyglot persistence)을 채택한다.** 배치 기준은 데이터 특성이며 모듈 묶음/사람 트랙이 아니다.

| DB | 모듈 | 핵심 애그리거트 | 배치 근거 |
|---|---|---|---|
| **MongoDB** (Spring Data MongoDB) | `listing`(+`favorite`·`recent-listing`), `diagnosis` | `Listing`, `Favorite`, `RecentListing`, `Diagnosis` | 지오·가변 스키마·대량 읽기 / 문서형 애그리거트·임베드 배열·조인 불필요·단일 도큐먼트 원자적 쓰기 |
| **MySQL** (Spring Data JPA) | `auth`, `user`, `community` | `RefreshToken`, `User`, `Post`/`Comment`/`PostLike` | 계정·토큰 트랜잭션 / 유니크 제약(`PostLike`)·다엔티티 카운트 정합 |

> **보완([ADR-0006](./0006-refresh-token-store-redis.md), 2026-06-15):** `RefreshToken`의 **저장은 Redis**로 옮긴다(불투명 토큰의 회전·TTL 적합성, [ADR-0003](./0003-jwt-auth-after-oauth-login.md)의 "refresh 저장소" 후속이 닫힘). `auth`의 계정·소셜 연동은 MySQL 유지 — 즉 `auth`는 **MySQL(계정) + Redis(refresh)** 혼합이다.

세부 정책:

1. **리포지토리 스택을 패키지로 분리한다.** `@EnableMongoRepositories`는 `listing`·`diagnosis` 패키지로, `@EnableJpaRepositories`는 `auth`·`user`·`community` 패키지로 한정해 두 스택이 같은 스캔에 섞이지 않게 한다.
2. **같은 store에 있어도 모듈 간 직접 조인은 하지 않는다.** `listing`과 `diagnosis`가 둘 다 MongoDB에 있지만, 추천은 cross-collection 조인이 아니라 [ADR-0002](./0002-inter-module-communication-via-events.md)의 공개 쿼리로 흐른다 — `diagnosis`가 진단 조건을 값 객체 `RecommendationCriteria`로 만들어 넘기면 `listing`이 `recommendByCriteria(criteria)`로 자기 컬렉션만 질의한다. **둘이 같은 store인 것은 부수적이며 조인 최적화 목적이 아니다**(그래서 `diagnosis`의 store 선택은 추천과 무관하게 데이터 특성으로만 정했다).
3. **매물 지오 질의는 MongoDB 네이티브로 처리한다.** `2dsphere` 인덱스 + `$geoWithin`(bbox)·`$near`(거리순)·`$geoNear`(집계).
4. **찜·최근 본 매물은 `listing`과 같은 MongoDB에 둔다.**
5. **cross-store 조인을 금지하고 애플리케이션 레벨로 합친다.** store를 넘는 조회는 공개 쿼리/이벤트로 데이터를 받아 코드에서 합친다(N+1·배치 조회 주의).
6. **cross-store 분산 트랜잭션(XA)을 쓰지 않는다.** 쓰기 경로는 단일 store 안으로 한정한다. store를 넘는 정합이 필요하면 도메인 이벤트 기반 최종 일관성으로 설계한다.
7. **MySQL 스키마 변경은 마이그레이션 도구로 관리한다**(**Flyway 채택 — [ADR-0008](./0008-mysql-migration-flyway.md)**, 세부는 [migration-policy](../database/migration-policy.md)). MongoDB는 애플리케이션 레벨 버전 필드로 관리한다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. 폴리글랏 (채택)** — 데이터 특성별 MongoDB/MySQL | 각 데이터를 가장 맞는 엔진에(지오·문서 vs 관계·트랜잭션), 매물 지오 네이티브, 진단 문서 모델 적합 | 운영 DB 2종, cross-store 조인·트랜잭션 불가, Spring Data 스택 2개 설정 | — |
| **B. 단일 MySQL (매물·진단 포함)** | 단일 스택·운영 단순, 트랜잭션 통일 | 지오는 공간 확장/근사 계산 필요, 매물·진단의 가변/배열 속성을 JSON 컬럼으로 우회 | 매물의 위치 검색·유연 스키마 이점을 잃어 보호 핵심(추천/지도) 구현 비용이 커짐 |
| **C. 단일 MongoDB (전체)** | 단일 스택, 스키마 유연 | 토큰·좋아요 유니크/카운트 정합의 트랜잭션 보장이 약함(정합 위험) | 인증·커뮤니티의 일관성 요구를 충족하기 어려움 |
| **D. 매물도 PostgreSQL + PostGIS (단일 RDB)** | 강력한 지오 + 관계형을 한 DB로 | PostGIS 학습곡선(2~3일), 가변 속성은 여전히 JSONB로 우회, 팀 합의는 MongoDB | 기한이 빠듯하고 팀이 MongoDB로 합의 — 운영 2종 부담이 커지면 재검토 여지(Validation 참조) |

## Consequences

- **긍정**
  - 매물 지도/거리/반경 질의를 `2dsphere`로 **저비용** 구현 — 1차 MVP의 지도 시각화(보호 핵심)를 무리 없이 포함.
  - 진단은 임베드 배열을 가진 **문서 애그리거트**로 자연스럽게 모델링되고, 한 진단을 **한 번의 원자적 쓰기**로 저장한다.
  - 매물 유형별 가변 속성·외부 수급 데이터의 스키마 변동을 **문서 모델**로 흡수.
  - 인증·커뮤니티는 **유니크·카운트 정합·트랜잭션**을 RDB로 보장.
- **부정/트레이드오프**
  - 운영 DB가 **2종**이 되어 인프라·모니터링·백업·로컬 개발 환경 비용이 늘어난다.
  - **cross-store 조인 불가** → 애플리케이션 레벨 조인이라 N+1·배치 조회에 주의해야 한다.
  - **cross-store 트랜잭션 불가** → store를 넘는 정합은 최종 일관성으로 설계해야 한다(현재 MVP 쓰기 경로는 단일 store라 영향 작음).
  - Spring Data **두 스택 동시 설정**(리포지토리 패키지 분리, 트랜잭션 매니저 구분)이 필요하다.
- **후속 작업**
  - [build.gradle](../../build.gradle)에 `spring-boot-starter-data-jpa` + MySQL 드라이버, `spring-boot-starter-data-mongodb` 추가.
  - `@EnableJpaRepositories`/`@EnableMongoRepositories` 패키지 분리 + 데이터소스/트랜잭션 매니저 구성.
  - 추천 공개 쿼리 계약(`RecommendationCriteria`) 정의([ADR-0002](./0002-inter-module-communication-via-events.md), [diagnosis spec](../api/specs/02-diagnosis-recommendation.md)).
  - MySQL 마이그레이션 도구: **Flyway 확정([ADR-0008](./0008-mysql-migration-flyway.md))** · [migration-policy](../database/migration-policy.md) 작성 완료.
  - 매물 `2dsphere` 인덱스 정의 + 더미 매물 seed.
  - [database-design](../database/database-design.md)을 두 저장소 기준으로 갱신.

## Validation

- **모듈 경계**: `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java))가 green을 유지해 모듈이 엔티티/리포지토리를 공유하지 않음을 빌드 시점에 확인.
- **스택 분리**: JPA 리포지토리가 Mongo 패키지를(또는 그 반대) 스캔하지 않는지 통합 테스트로 검증(컨텍스트 기동 + 리포지토리 빈 타입 확인).
- **지오 인덱스 사용**: 매물 지도/거리 질의가 `2dsphere` 인덱스를 타는지 `explain`으로 관측.
- **재검토 시점**: 운영 DB 2종 부담이 과도하거나 cross-store 애플리케이션 조인이 빈번해 성능/복잡도 문제가 생기면 대안 D(PostgreSQL+PostGIS 단일화)를 재검토한다. 진단을 MySQL로 되돌리는 비용은 낮다(조인 의존이 없어 store 이전이 단순).
