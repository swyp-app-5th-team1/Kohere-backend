# ADR-0007. API 문서는 테스트 기반 Spring REST Docs로 생성한다 (Swagger/springdoc 대비)

| 항목 | 값 |
|---|---|
| 번호 | ADR-0007 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-16 |
| 관련 문서 | [ADR-0004](./0004-api-response-envelope.md), [api-design-guide](../api/api-design-guide.md), [error-response-guide](../api/error-response-guide.md), [api/specs](../api/specs/README.md), [system-overview §3-5](../architecture/system-overview.md), [code-style §3](../convention/code-style.md), [build.gradle](../../build.gradle) |

## Status

Accepted

> 현재 API 정본은 [docs/api/specs](../api/specs/README.md)의 **수기 Markdown**이다(설계·합의용). 코드↔문서 드리프트를 막고 컨트롤러를 깨끗하게 유지하면서 API 레퍼런스를 생성·발행할 도구를 정한다. 본 ADR은 **런타임 어노테이션 기반(Swagger UI / springdoc-openapi)** 과 **테스트 기반(Spring REST Docs)** 중 후자를 택한다.

## Context

- **API 정본이 수기 Markdown이다**([api/specs](../api/specs/README.md)). 설계 의도·계약은 잘 담지만 **실제 응답과 어긋날 수 있고**(검증 장치 없음), 코드 변경 시 사람이 직접 동기화해야 한다.
- **테스트 문화가 강하다.** JUnit 5·AssertJ·Mockito·Modulith test가 배선돼 있고 Testcontainers 도입 예정이다([system-overview §3-5](../architecture/system-overview.md)). 공통 응답 래퍼(`{success,data,error}`, [ADR-0004](./0004-api-response-envelope.md))와 에러 포맷([error-response-guide](../api/error-response-guide.md))이 표준화돼 있어 **문서 스니펫으로 재사용하기 쉽다.**
- **클린 계층을 지향한다.** 컨트롤러는 DTO만 반환하고 엔티티를 노출하지 않으며 계층 규칙을 지킨다([code-style §3](../convention/code-style.md)). 컨트롤러·DTO에 문서용 어노테이션을 더하는 것은 이 원칙과 상충한다.
- **API 소비자가 자사 모바일 앱(알려진 단일 클라이언트)이다.** 외부 개발자에게 공개하는 포털이나 인터랙티브 "try it out"의 필요가 현재 크지 않다.
- 현재 [build.gradle](../../build.gradle)에는 **어떤 API 문서 도구도 없다**(springdoc·restdocs 미배선).
- 따라서 "API 문서를 **어떤 방식으로 생성·검증·유지**할지"를 정해야 한다.

## Decision

**테스트 기반 [Spring REST Docs]를 채택한다.** 컨트롤러 테스트(MockMvc)에서 요청/응답 스니펫(AsciiDoc)을 생성하고, 수기 prose와 합쳐 HTML 레퍼런스로 발행한다. **Swagger UI / springdoc-openapi(런타임 어노테이션 방식)는 채택하지 않는다.**

세부 정책:

1. **스니펫 생성**: `spring-restdocs-mockmvc`로 컨트롤러 슬라이스 테스트에서 `curl`/`http-request`/`http-response`/`request-fields`/`response-fields` 스니펫을 만든다. 공통 래퍼(`success`/`data`/`error`)·에러 카탈로그는 공용 스니펫/프리프로세서로 재사용한다.
2. **문서 빌드**: AsciiDoctor(Gradle `org.asciidoctor.jvm.convert`)로 `src/docs/asciidoc`의 prose + 생성 스니펫을 합쳐 HTML로 변환하고 빌드 산출물에 포함한다.
3. **정확성 강제**: 문서화 대상 엔드포인트는 **테스트가 있어야 스니펫이 나온다.** 요청/응답이 바뀌면 테스트가 깨지므로 문서도 강제로 갱신된다 → **문서↔코드 드리프트 차단.**
4. **컨트롤러 무오염**: 문서용 어노테이션을 프로덕션 코드에 두지 않는다([code-style §3](../convention/code-style.md) 클린 계층 유지).
5. **Markdown specs와의 관계**: [api/specs](../api/specs/README.md)는 **설계 시점 정본**(사람이 쓰고 리뷰·합의하는 계약)으로 유지하고, REST Docs는 **테스트로 검증된 실제 동작 레퍼런스**로 보완한다(스펙=계약, REST Docs=검증). 중복은 점진적으로 REST Docs 산출물로 수렴시킨다.
6. **OpenAPI 확장 경로**: 외부 소비자·코드 생성·Swagger UI가 필요해지면 [`restdocs-api-spec`]로 **테스트에서 OpenAPI 3 + Swagger UI를 추가 생성**한다(테스트 기반 정확성을 유지한 채 Swagger UI 확보 — 아래 대안 D).

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. Spring REST Docs (채택)** — 테스트에서 스니펫 생성 | 테스트로 **정확성 보장·드리프트 차단**, 컨트롤러 무오염, 수기 prose 결합 유연, 공통 래퍼/에러 스니펫 재사용 | 엔드포인트별 테스트 작성 비용, 기본 인터랙티브 "try it out" UI 없음, AsciiDoctor 빌드 셋업 | — |
| **B. Swagger UI / springdoc-openapi** — 어노테이션·런타임 자동 생성 | 즉시 **Swagger UI(try it out)** + OpenAPI 스펙 무료, 도입 빠름, 생태계 친숙 | **문서↔동작 드리프트**(설명·예시가 코드로 검증되지 않음), 컨트롤러/DTO **어노테이션 오염**(`@Operation`/`@Schema`), 런타임 의존·노출면 관리 필요 | 정확성·클린 계층이 우선이고 외부 try-it-out 수요가 낮음 |
| **C. 수기 Markdown만 (현행 유지)** | 도구 0, 설계 의도 표현 자유 | 실제 응답과 **수기 동기화·검증 없음** → 드리프트 | 정확성 보장 불가, 규모가 커지면 신뢰 하락 |
| **D. REST Docs + `restdocs-api-spec`로 OpenAPI 겸용** | 테스트 정확성 + Swagger UI/코드젠 | 셋업·산출물 2종으로 복잡도↑ | MVP엔 과투자 — 채택안(A)의 **후속 전환 경로**로 남긴다(필요 시 Decision 6) |

## Consequences

- **긍정**
  - API 문서가 **테스트로 검증**돼 항상 실제 동작과 일치한다(드리프트 구조적 차단).
  - 컨트롤러/DTO에 **문서 어노테이션 오염이 없다** — 클린 계층([code-style §3](../convention/code-style.md)) 유지.
  - 공통 래퍼·에러 스니펫 재사용으로 문서 일관성↑. **테스트 커버리지가 곧 문서 커버리지**가 된다.
- **부정/트레이드오프**
  - 문서화할 엔드포인트마다 **테스트가 필요**하다(초기 작성 비용↑ — 단, 어차피 작성할 컨트롤러 테스트와 겹쳐 한계비용은 작다).
  - 기본 제공 **인터랙티브 "try it out" UI가 없다**(필요 시 대안 D).
  - **AsciiDoctor 빌드 파이프라인**이 추가되고, 작성자가 AsciiDoc/스니펫 작성법을 익혀야 한다.
- **후속 작업**
  - [build.gradle](../../build.gradle)에 `spring-restdocs-mockmvc` + `org.asciidoctor.jvm.convert` 플러그인 추가, `src/docs/asciidoc` 골격 작성.
  - 공통 래퍼/에러 응답 **스니펫 템플릿**(프리프로세서) 마련.
  - CI에서 문서 빌드(선택적으로 산출물 게시).
  - [system-overview §3-5](../architecture/system-overview.md) API 문서 항목 갱신(✅ 본 결정 반영).
  - 외부 공개 수요가 생기면 `restdocs-api-spec`(대안 D) 평가.

## Validation

- **드리프트 차단 동작**: 요청/응답 스키마를 의도적으로 바꿨을 때 해당 컨트롤러 테스트(및 스니펫)가 실패하는지 확인한다.
- **커버리지**: 1차 MVP 보호 핵심 엔드포인트(`auth`·`diagnosis`·`listing`·`booking`/`chat`)에 REST Docs 테스트가 부착되는지 추적한다.
- **재검토 시점**: 외부 개발자/파트너에게 API를 공개하거나 클라이언트가 다양해져 **인터랙티브 탐색·코드 생성 수요**가 생기면 대안 D(`restdocs-api-spec`로 OpenAPI/Swagger UI 겸용)를 재검토한다.

[Spring REST Docs]: https://docs.spring.io/spring-restdocs/docs/current/reference/htmlsingle/
[`restdocs-api-spec`]: https://github.com/ePages-de/restdocs-api-spec
