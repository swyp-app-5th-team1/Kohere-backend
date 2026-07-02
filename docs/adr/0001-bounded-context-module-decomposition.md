# ADR-0001. 도메인(Bounded Context) 기준으로 모듈을 분해한다

| 항목      | 값                                                                                                                                                                                        |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 번호      | ADR-0001                                                                                                                                                                                  |
| 작성자    | Kohere Backend 팀                                                                                                                                                                         |
| 작성일    | 2026-06-15                                                                                                                                                                                |
| 관련 문서 | [code-style §3](../convention/code-style.md), [error-response-guide §4](../api/error-response-guide.md), [API specs](../api/specs/README.md), [system-overview](../architecture/system-overview.md) |

## Status

Accepted

> 실제 유스케이스 구현 과정에서 cross-module 흐름이 과하면 일부 병합을 재검토한다.

## Context

- 아키텍처는 **모듈러 모놀리식(Spring Modulith)** 이며, 모듈 내부는 DDD 계층으로 구성한다([code-style §3](../convention/code-style.md)). 따라서 "**모듈 경계 = Bounded Context**"를 무엇을 기준으로 어떻게 그을지 정해야 한다.
- 결정에 쓸 수 있는 입력은 세 가지다.
  - **기능 단위**: 도메인별 [API 스펙 7건](../api/specs/README.md)(소셜 로그인·온보딩 / 진단·추천 / 매물·찜 / 신청·문의(채팅) / 커뮤니티 / 게이미피케이션 / 신고).
  - **에러코드 prefix 표**: [error-response-guide §4](../api/error-response-guide.md)가 모듈별 prefix를 명시한다(`AUTH`/`USER`, `DIAGNOSIS`, `LISTING`, `BOOKING`/`CHAT`, `POST`/`COMMENT`, `QUIZ`, `REPORT`).
  - **계층/주입 규약**: [code-style §3](../convention/code-style.md)(package-by-module, 도메인 우선 분해, 엔티티 모듈 간 비공유, 이벤트/공개 API 통신, `allowedDependencies` 화이트리스트).
- 제약: 현 단계는 **스켈레톤**이며 DB 설계는 아직 템플릿이다([database-design](../database/database-design.md)). 모듈 경계는 영속 모델보다 **도메인 책임**을 기준으로 먼저 확정해야 한다.
- 목표: 응집도 높은 모듈, 빌드 시점에 강제 가능한 명확한 경계, 병렬 개발 가능성, 에러코드/스펙 문서와의 1:1 추적성.

## Decision

도메인(Bounded Context)을 최상위 모듈 단위로 삼아 **공유 커널 `common`(OPEN) + 도메인 모듈 9개**, 총 10개로 분해한다.

| 모듈              | Bounded Context             | 핵심 애그리거트              | 에러 prefix          |
| ----------------- | --------------------------- | ---------------------------- | -------------------- |
| `common` (OPEN) | 공유 커널                   | (응답/에러/페이지 공통 타입) | —                   |
| `user`          | 회원 프로필·계정 lifecycle | `User`                     | `USER`             |
| `auth`          | 소셜 로그인·JWT·세션      | `RefreshToken`             | `AUTH`             |
| `diagnosis`     | 맞춤 진단·매물 추천        | `Diagnosis`                | `DIAGNOSIS`        |
| `listing`       | 매물 탐색·찜               | `Listing`, `Favorite`    | `LISTING`          |
| `booking`       | 신청(예약)                  | `Booking`                  | `BOOKING`          |
| `chat`          | 인앱 채팅·문의             | `ChatRoom`, `Message`    | `CHAT`             |
| `community`     | 커뮤니티(게시판·댓글)      | `Post`, `Comment`        | `POST`/`COMMENT` |
| `gamification`  | 퀴즈                        | `Quiz`                     | `QUIZ`             |
| `report`        | 신고                        | `Report`                   | `REPORT`           |

경계를 그은 **판단 기준(우선순위 순)** 은 다음과 같다.

1. **기능/스펙 단위를 1차 seam으로 삼는다.** 7개 API 스펙이 사용자 가치 단위의 자연스러운 분할선이므로 기본 경계로 채택한다.
2. **에러코드 prefix 표를 정본 모듈 목록으로 본다.** [error-response-guide §4](../api/error-response-guide.md)의 prefix가 곧 모듈이며, 코드의 도메인 예외(`*_NOT_FOUND` 등)와 모듈이 1:1로 매칭되도록 한다.
3. **애그리거트 응집과 "변경 이유의 단일성"(SRP)으로 검증한다.** 한 스펙 문서라도 애그리거트와 변경 이유가 다르면 쪼갠다 → `auth`(자격증명·세션, 보안 정책에 따라 변경)와 `user`(신원·프로필, 약관/프로필 정책에 따라 변경)를 분리. `booking`(예약 lifecycle)과 `chat`(메시징)도 동일 이유로 분리.
4. **단방향 의존이 성립할 때만 쪼갠다(순환 금지).** `auth → user`, `booking → listing·chat`, `community → chat`, `diagnosis → listing`처럼 한 방향으로만 흐를 때 분리한다. 양방향/순환이 생기면 합친다. (진단 문항 라벨 번역에 사용자 등록 국가가 필요해 `diagnosis → user`(등록 국가 조회) 협력도 같은 단방향으로 더해진다 — 순환 없음. 실현은 `user` 모듈 공개 query 동기 호출로 확정한다([ADR-0002](./0002-inter-module-communication-via-events.md) Decision 5; 토큰 클레임 미사용).)
5. **유비쿼터스 언어 경계를 존중한다.** 같은 용어가 맥락마다 다른 의미면 다른 컨텍스트로 본다(예: `ConditionTag`(매물 편의시설)와 진단 입력 `conditions`는 값 집합이 달라 각 모듈이 자기 enum을 갖는다 — `listing.ConditionTag` vs `diagnosis.DiagnosisCondition`). 같은 원칙으로 진단 3단계 대학·지역구 선택지(`UniversityGroup`·`District` 류)도 `diagnosis` 모듈이 자기 입력 enum으로 보유한다 — 진단 입력 어휘이지 `listing`의 매물 속성 어휘가 아니다(`UniversityGroup`/`District` 두 필드 분리·정확한 값 목록은 02 스펙·domain-model에 확정).
6. **횡단 공통 타입은 도메인 로직 없이 공유 커널로 격리한다.** `ApiResponse`·`ErrorCode`·`BusinessException`·전역 핸들러·페이지 응답을 `common`(OPEN)에 두고 모든 모듈이 의존한다.

경계는 `package-info.java`의 `@ApplicationModule(allowedDependencies = {"common"})`로 선언해 **허용 의존을 화이트리스트로 강제**하고, `ApplicationModules.verify()` 테스트로 빌드 시점에 지속 검증한다. (현 스켈레톤은 모듈 간 직접 의존을 두지 않고 `common`에만 의존하며, cross-BC 협력은 공개 API/이벤트로 연결할 지점을 각 `package-info` Javadoc에 TODO로 명시했다.)

## Alternatives

| 대안                                                                                         | 장점                                                                | 단점                                                           | 채택 안 한 이유                                                                                     |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| **A. 계층 우선 분해** (`controller`/`service`/`repository`를 최상위 패키지로)    | 익숙함, 작은 앱에 단순                                              | 도메인 경계를 강제 못 함, 모듈러 모놀리식 의미 상실            | [code-style §3](../convention/code-style.md)이 도메인 우선(package-by-module)을 명시                  |
| **B. 스펙 1:1 = 7개 모듈** (`auth`+`user` 통합, `booking`+`chat` 통합)         | 더 단순, 온보딩/예약 등 cross 흐름이 모듈 내부로 들어와 조율 불필요 | 보안(인증)·메시징 책임이 다른 책임과 한 모듈에 섞임, SRP 약화 | 변경 이유 분리·인증 로직 격리 이점이 더 크다고 판단(단,**되돌리기 쉬움** — Validation 참조) |
| **C. 더 잘게 분해** (`favorite`/`recent-listing`/`like`/`comment`를 별도 모듈) | 초세분화된 경계                                                     | 애그리거트 응집 저하, 모듈 간 마찰·이벤트 폭증, 과설계        | 찜·댓글 등은 소유 애그리거트(매물·게시글)와 강결합이라 같은 BC에 둠                               |
| **D. 단일 모듈(분해 안 함)**                                                           | 초기 속도                                                           | 경계·의존 방향 강제 불가, 결합 누적                           | 모듈러 모놀리식 채택 전제와 배치                                                                    |

## Consequences

- **긍정**
  - 경계가 `ModularityTest`로 **빌드 시점 강제**된다(순환 의존·internal 침범·미허용 의존을 CI에서 차단).
  - 모듈별 **병렬 개발**과 변경 영향 격리(보안 민감한 `auth`를 독립적으로 다룸).
  - 에러코드 prefix·API 스펙 문서와 모듈이 **1:1 추적**된다.
- **부정/트레이드오프**
  - cross-BC 흐름은 모듈 경계를 넘어 **조율 비용**이 든다: 온보딩(`auth`↔`user` 사용자 생성/전이), 신청 시 채팅방·카드 생성(`booking`↔`chat`), 추천(`diagnosis`↔`listing`), 진단 문항 라벨 번역용 표시 언어 조회(`diagnosis`→`user` `getLanguage`, 실현 방식은 [ADR-0002](./0002-inter-module-communication-via-events.md)), 신고 대상 검증(`report`↔`community`/`chat`).
  - **엔티티 모듈 간 비공유** 원칙상 동일 데이터(예: 사용자 닉네임)를 여러 모듈이 각자 보유/이벤트 수신해야 해 일부 중복이 생긴다.
- **후속 작업**
  - 모듈 간 통신 방식(도메인 이벤트 `@ApplicationModuleListener` vs 공개 query API) 결정 → 별도 ADR.
  - 인증 주체(`userId`) 전달 방식(SecurityContext) 확정.
  - `report`의 신고 대상 존재 검증 방식(이벤트/포트) 확정.

## Validation

- **지속 검증**: `KohereApplication` 기준 `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java))가 CI(`./gradlew spotlessCheck build`)에서 green을 유지하는지로 경계 무결성을 관측한다.
- **선언적 가드**: 각 모듈 `package-info.java`의 `allowedDependencies` 화이트리스트.
- **재검토 시점**: 실제 유스케이스 구현 중 특정 모듈 쌍 사이 호출/이벤트가 과도하면(특히 `auth`↔`user`, `booking`↔`chat`) 대안 B(병합)로 재검토한다. 병합은 패키지 이동 + `package-info` 통합으로 비교적 저비용이다.
