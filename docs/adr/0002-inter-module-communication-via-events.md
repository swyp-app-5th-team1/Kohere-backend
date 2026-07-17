# ADR-0002. 모듈 간 통신은 도메인 이벤트(Application Events) 기반으로 한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0002 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-15 |
| 관련 문서 | [ADR-0001](./0001-bounded-context-module-decomposition.md), [code-style §3-2](../convention/code-style.md), [04-booking-inquiry-chat](../api/specs/04-booking-inquiry-chat.md), [error-response-guide](../api/error-response-guide.md) |

## Status

Accepted

> [ADR-0001](./0001-bounded-context-module-decomposition.md)의 후속 결정이다. ADR-0001이 스케치한 모듈 간 의존(예: "booking → chat")의 **구체적 실현 방식**을 정의한다. 일부는 이벤트 도입으로 의존 방향이 뒤집힌다(아래 Decision 5).

## Context

- [ADR-0001](./0001-bounded-context-module-decomposition.md)로 BC별 모듈을 분해했고, 모듈을 넘나드는 협력 흐름이 존재한다: 온보딩(`auth`↔`user`), 신청 시 채팅방·예약카드·알림(`booking`→`chat`), 추천(`diagnosis`→`listing`), 신고 대상 검증(`report`→`community`/`chat`), 신규 메시지 푸시 알림(`chat`→…).
- [code-style §3-2](../convention/code-style.md)는 이미 방향을 제시한다: **"모듈 간 직접 호출 대신 이벤트(Application Events)로 통신해 결합을 낮춘다. 비동기는 `@ApplicationModuleListener`."** 또한 **엔티티 모듈 간 비공유**를 못박는다.
- 스펙도 이벤트를 전제한다: [04-booking-inquiry-chat](../api/specs/04-booking-inquiry-chat.md)는 "새 메시지 수신 시 도메인 이벤트로 푸시 알림 발행", "예약 생성 시 임대인에게 푸시 알림 이벤트 발행"을 명시한다.
- 따라서 "모듈 간 통신을 **무엇으로** 할지"를 확정해야 한다(이벤트 vs 직접 호출 vs 공유 DB vs 브로커).
- 제약: 현 단계는 스켈레톤이며 영속 계층·트랜잭션 매니저가 아직 없다(`@ApplicationModuleListener`는 트랜잭션 바운드 리스너라 영속/트랜잭션 도입 후에 완전 동작).

## Decision

**모듈 간 통신은 도메인 이벤트(Spring `ApplicationEventPublisher` + Spring Modulith `@ApplicationModuleListener`)를 기본값으로 채택한다.** 규약은 다음과 같다.

1. **이벤트 타입은 발행 모듈의 공개 API에 둔다** — 모듈 base 패키지(예: `com.kohere.booking.BookingCreatedEvent`) 또는 `@NamedInterface`로 노출한 패키지. (sub-package에 두면 internal이라 구독 모듈이 접근할 수 없다.)
2. **이벤트 페이로드는 원시/공유 타입만** 담는다. 발행 모듈의 internal 도메인 타입(예: `booking.domain.ContractPeriod` enum, JPA 엔티티)을 노출하지 않는다 — 그래서 enum은 문자열로 전달한다. (엔티티 비공유 원칙 준수.)
3. **발행은 `ApplicationEventPublisher.publishEvent(...)`**, **구독은 `@ApplicationModuleListener`**(= `@Async` + `@TransactionalEventListener(phase=AFTER_COMMIT)` + 새 트랜잭션). 같은 트랜잭션의 커밋 이후 비동기로 처리해 발행 트랜잭션과 분리한다.
4. **발행 모듈은 구독자를 모른다(역의존 없음).** 의존은 *구독자 → 발행 모듈(이벤트 타입에 한해)* 단방향으로만 생기며, 구독 모듈의 `allowedDependencies`에 발행 모듈을 등록한다. 발행 모듈의 `allowedDependencies`에는 구독자가 추가되지 않는다.
5. **예외 — 즉시 결과가 필요하면 동기 공개 API를 쓴다.** 호출 결과가 응답에 즉시 필요한 협력(예: 문의 시 `chatRoomId`를 바로 반환)은 이벤트(결과적 일관성)로 풀 수 없다. 이 경우 발행/직접 호출 대신 **노출된 query/command 인터페이스를 동기 호출**한다(이때도 DTO/포트로만, 엔티티 비공유). 즉 **부수효과(알림·카드 전송·읽기 모델 갱신·감사)는 이벤트, 즉시 결과가 필요한 조회/명령은 공개 API**로 나눈다.
6. **ADR-0001 의존 스케치의 재해석.** 예로 ADR-0001이 적은 "`booking → chat`"은 이벤트로 실현하면 방향이 **뒤집힌다**: `booking`이 `BookingCreatedEvent`를 발행하고 `chat`이 구독하므로 `chat → booking`(이벤트 타입) 단방향이 되고, `booking`은 `chat`을 전혀 의존하지 않는다.
7. **MSA 전환 대비 — 통신 방식 = 미래 분해선.** 동기/비동기 구분은 추후 서비스로 분해할 때 전송 계층에 그대로 매핑되므로 **지금부터 명확히 나눈다**: **동기 공개 API(query/command) → REST/gRPC**, **도메인 이벤트 → Kafka(Modulith event externalization)**. 그래서 새 협력을 추가할 때 D5 경계(즉시 결과가 필요하면 동기 API, 부수효과면 이벤트)를 흐리지 않는다 — 이 경계가 곧 **미래 서비스 인터페이스의 경계**가 된다. (예: `auth↔user` 온보딩은 동기 공개 API → 분해 시 REST/gRPC, 탈퇴 정리는 `UserWithdrawnEvent` → Kafka 토픽.)

### 스켈레톤에 반영한 참조 예시

`booking` → `chat` 흐름을 검증 가능한 형태로 넣었다.

- 발행: [com/kohere/booking/BookingCreatedEvent.java](../../src/main/java/com/kohere/booking/BookingCreatedEvent.java) (base 패키지, 공개) — `BookingService`가 `ApplicationEventPublisher`로 발행.
- 구독: `chat`의 `BookingEventHandler`가 이벤트를 받아 채팅방 보장 + `BOOKING_CARD` 고정 + 임대인 푸시 알림을 처리(현재 stub).
- 경계: `chat`의 `package-info`에 `allowedDependencies = {"common", "booking"}` 등록 → `ModularityTest`가 이 단방향 의존을 강제.

> 현재 스켈레톤은 영속/트랜잭션이 없어 구독자를 우선 `@EventListener`(동기)로 두고, 영속·트랜잭션 도입 시 `@ApplicationModuleListener`(비동기·트랜잭션 바운드)로 교체하도록 코드에 TODO를 남겼다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. 직접 메서드 호출** (다른 모듈 서비스 주입) | 단순·동기·즉시 결과 | 강결합, 순환 의존 위험, 경계 침식 | 기본값으로 부적합. **즉시 결과가 꼭 필요한 경우에 한해** 공개 API 동기 호출로 제한 허용(Decision 5) |
| **B. 공유 DB 테이블 직접 접근** | 구현이 가장 간단 | 영속 결합(가장 풀기 어려움), 경계 붕괴 | [code-style §3-2](../convention/code-style.md)가 엔티티 비공유를 명시 |
| **C. 도메인 이벤트(채택)** | 느슨한 결합, 발행측이 구독자 무지, 트랜잭션 분리, 추후 서비스 추출 용이 | 결과적 일관성(즉시 결과 X), 흐름 추적 난이도 | — (채택) |
| **D. 외부 메시지 브로커**(Kafka 등) | 서비스 물리 분리·확장성 | 운영 복잡도·인프라 비용, 모놀리식엔 과투자 | 현 단계 과함. 필요 시 Spring Modulith **event externalization**으로 점진 도입 가능(코드 변경 최소) |

## Consequences

- **긍정**
  - 발행 모듈과 구독 모듈이 **느슨하게 결합**된다. 구독자 추가/변경이 발행 모듈 코드 변경을 유발하지 않는다.
  - 의존 그래프가 단순해진다(역의존·순환 제거). 예: `booking`이 `chat`을 모름.
  - 발행 트랜잭션과 후처리가 분리되어 한 모듈 장애가 다른 모듈로 전파되기 어렵다.
  - 추후 특정 모듈을 별도 서비스로 추출할 때 **통신 방식이 전송 계층에 그대로 매핑**돼 전환 비용이 작다 — 이벤트는 Kafka(externalization), 동기 공개 API는 REST/gRPC 클라이언트(Decision 7).
- **부정/트레이드오프**
  - **결과적 일관성**: 비동기 처리라 즉시 결과를 줄 수 없다(예: 예약 응답에 `chatRoomId`를 바로 못 담음 → 동기 공개 API 또는 후속 조회 필요).
  - 흐름이 코드에서 한눈에 안 보여 **디버깅/추적이 어렵다**(이벤트 카탈로그 문서화로 보완).
  - `@ApplicationModuleListener`는 트랜잭션·영속 인프라가 필요(현재 미도입 → 임시 `@EventListener`).
  - 이벤트 **유실 대비**가 필요(리스너 실패 시 재시도/복구).
  - **MSA 전환 시 주의**(Decision 7): 동기 협력이 원격(REST/gRPC)이 되면 **가용성 결합**·실패/타임아웃 처리가 생기고, Kafka 이벤트는 at-least-once라 구독자의 **멱등 소비**가 필요하다.
- **후속 작업**
  - 영속/트랜잭션 도입 시 `@EventListener` → `@ApplicationModuleListener` 전환 + Spring Modulith **Event Publication Registry**(미완료 이벤트 영속·재시도) 채택.
  - 동기 공개 API가 필요한 협력 식별(문의 `chatRoomId`, 추천의 매물 조회, `diagnosis`→`user` 표시 언어 조회(`getLanguage`; 진단 문항 라벨 번역 언어 결정 — 즉시 결과가 필요한 조회라 D5상 동기) 등)과 노출 인터페이스(`@NamedInterface`) 설계. (`diagnosis`→`user` 표시 언어 조회는 user 공개 query 동기 호출로 확정 — user가 `users.lang`으로 결정하고 미설정이면 `en`, 토큰 클레임 미사용. [ADR-0029](./0029-diagnosis-i18n-strategy.md) #141 개정으로 국가→언어 도출은 폐기.)
  - 이벤트 카탈로그(발행 모듈·이벤트·구독자) 문서화.

## Validation

- **경계 강제**: 구독자 → 발행 모듈 의존이 `allowedDependencies`에 등록되지 않으면 `ApplicationModules.verify()`([ModularityTest](../../src/test/java/com/kohere/ModularityTest.java))가 빌드에서 실패한다. 발행 모듈에 역방향 의존이 생기면(이벤트 규약 위반) 역시 검출된다.
- **이벤트 검증**: Spring Modulith `@ApplicationModuleTest` + `Scenario` API / `PublishedEvents`로 발행·소비를 테스트한다(영속 도입 후).
- **재검토 시점**: 동기 공개 API 호출 비중이 이벤트보다 커지면(즉, 사실상 직접 호출 위주가 되면) 모듈 경계 또는 통신 전략을 재검토한다.
