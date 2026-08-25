# Test Strategy

> 코드를 작성하기 **전에** 정하는 테스트 컨벤션이다. 이 프로젝트(모듈러 모놀리식 Spring Modulith + DDD 계층, 폴리글랏 영속)에서 **무엇을·어느 레벨에서·어떤 도구로** 검증할지의 정본으로, 특정 모듈·클래스가 아니라 **레벨·원칙·도구**를 규정한다. 빌드·포맷은 [code-style](./code-style.md), CI는 [ci.yml](../../.github/workflows/ci.yml).

## 1. 목표

- **빠른 피드백**: 도메인·응용 로직은 Spring 컨텍스트 없이 순수 단위로 검증한다.
- **경계 보증**: 모듈 경계(의존 방향·이벤트)는 빌드 시점에 강제한다.
- **시나리오 보증**: 인수 조건(AC)에 해당하는 종단 흐름은 통합 테스트로 회귀를 막는다.
- **중복 최소화**: 같은 것을 여러 레벨에서 반복 검증하지 않는다(아래에서 깊게, 위에서 얇게).

## 2. 테스트 피라미드

| 레벨 | 무엇을 검증 | 도구 | 컨텍스트 |
| --- | --- | --- | --- |
| **단위(domain)** | 애그리거트 불변식·상태 전이·값 객체 규칙 | JUnit5 + AssertJ | 없음 |
| **단위(application)** | 유스케이스 조율 — 분기·예외·협력 호출 | JUnit5 + Mockito(포트·타 모듈 공개 API 모킹) | 없음 |
| **단위(infrastructure)** | 외부 기술을 걷어내도 남는 어댑터 순수 로직 — 해시·서명·프로토콜/상태→예외 매핑 | JUnit5 + AssertJ(HTTP는 `MockRestServiceServer`) | 없음/경량 |
| **어댑터 통합** | 프로토콜에 실제로 닿아야 드러나는 어댑터 동작 — 요청 조립·응답 매핑·오류→예외 변환 | JUnit5 + AssertJ + Testcontainers(오브젝트 스토리지는 `MinIOContainer`) | 없음(컨테이너만 띄우고 어댑터는 직접 생성) |
| **슬라이스(선택)** | 컨트롤러 직렬화·입력 검증·보안 매핑 | `@WebMvcTest`(+ `spring-security-test`) | 웹 계층 |
| **통합(integration)** | 모듈 간 협력·영속·보안 종단 흐름 + API 문서 스니펫 | `@SpringBootTest` + MockMvc (+ REST Docs) | 전체 |
| **모듈 경계** | 의존 방향·NamedInterface·이벤트 | `ApplicationModules.verify()` / `@ApplicationModuleTest` | Modulith |

원칙: **로직은 아래(단위)에서, 배선·시나리오는 위(통합)에서.**

## 3. 레벨별 지침

### 3-1. 도메인 단위

순수 도메인 객체(불변)가 **불변식·상태 전이**를 강제하는지 검증한다(위반 시 예외 등). 컨텍스트 없이 빠르게 — 가장 두껍게 깐다.

### 3-2. 응용 단위

도메인 포트와 타 모듈 공개 API를 모킹하고, 유스케이스의 **분기·예외·협력 호출(verify)** 을 검증한다. 외부 연동·실제 저장소는 호출하지 않는다.

### 3-3. 인프라 어댑터

어댑터에서 외부 기술(DB·Redis·HTTP·오브젝트 스토리지·암호 라이브러리)을 걷어내도 남는 **자체 로직**이 있으면 단위로 검증한다 — 해시·서명, 프로토콜/상태→예외 매핑 등. 기준은 **동어반복 경계**다: 외부 기술에 단순 위임하는 부분(Redis/JPA 호출)은 mock해도 구현을 그대로 되읊을 뿐이라 통합(Testcontainers)으로 미루고, 위임을 벗어난 변환·판단만 단위로 고정한다. 순수 해시·서명은 컨텍스트 없이, HTTP 어댑터는 `MockRestServiceServer`로 네트워크 없이 상태→예외 매핑을 검증한다. 통합에서 외부 연동을 가짜로 대체(`@TestConfiguration @Primary`·설정 스위치의 스텁 폴백)하면 어댑터의 실제 변환 경로는 통합이 닿지 못하므로, 이 단위 검증이 그 경로의 유일한 보증이 된다. 다만 **프로토콜 호환 구현을 컨테이너로 띄울 수 있으면 단위 대신 그 위에서 어댑터를 통째로 검증한다** — 사진 저장 어댑터(`S3ListingImageStorage`)가 그렇다. 목으로는 키·URL 조립까지만 보이고 콘텐츠 길이·경로 스타일·삭제 요청의 체크섬 요구처럼 프로토콜에 닿는 부분은 드러나지 않으므로, Spring 컨텍스트 없이 `MinIOContainer` 위에서 어댑터를 직접 만들어 업로드·복사·삭제와 저장소 오류→예외 매핑(없는 원본은 키 오류, 그 밖의 실패는 업로드 실패)을 검증한다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)). 이때 단위로 남는 것은 SDK를 타지 않는 키 조립·입력 검증 타입(`ListingImageKeys`·`ListingImageKeySet`·`PendingListingImage`)이다.

### 3-4. 통합

전체 컨텍스트(`@SpringBootTest` + MockMvc)로 종단을 구동한다. 통제 불가·느린 외부 연동만 가짜로 주입(`@TestConfiguration @Primary`)하고, 보안·영속·이벤트는 실제로 구동한다. 같은 레벨이라도 테스트당 책임은 하나로 둔다.

- **회귀 검증** — AC 단위 흐름을 **깊게** 단정한다(상태코드·공통 래퍼 `error.code`·`data` 값·교차 단계 상태).
- **API 문서** — 같은 통합 테스트이되 단정은 **얕게** 두고 REST Docs로 요청/응답 스니펫을 생성한다([ADR-0007](../adr/0007-api-docs-spring-rest-docs.md)).

#### 문서 테스트(`*DocsTest`) 작성 규약

`com.kohere.docs`의 공용 헬퍼(`ApiDocsTags`·`ApiDocsFields`·`ApiDocsParams`·`ApiDocsErrors`·`DocsTokens`)를 쓴다. 로컬에 `field`/`errorFields` 같은 헬퍼를 다시 만들지 않는다 — 파일마다 문구가 갈리면 같은 공통 래퍼가 Swagger에서 여러 갈래로 보인다.

생성기가 같은 `(path, method)` 스니펫을 오퍼레이션 하나로 병합하므로 아래를 지킨다(상세·근거는 [ADR-0017](../adr/0017-openapi-swagger-ui-from-restdocs.md) 「문서 작성 규약」):

- 모든 `document()`에 **태그와 오퍼레이션 상수**(summary·description)를 지정한다. 성공·에러 스니펫이 같은 문자열을 쓴다.
- 같은 `(path, method, status)`의 스니펫은 **같은 필드 헬퍼**를 호출한다. 파일이 달라도 마찬가지다.
- 케이스 구분은 summary가 아니라 **identifier**로 한다. identifier가 Swagger Examples 드롭다운의 항목명이 된다.

**파일을 받는 엔드포인트를 문서화할 때**([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)) 한 가지를 더 지킨다.

- **파일 part는 문서에 스키마로 남길 수 없다.** `restdocs-api-spec` 0.19.4가 multipart를 표현하지 못하고, 요청을 그대로 실으면 boundary가 붙은 `Content-Type`과 사람이 못 읽는 본문이 OpenAPI에 남는다. MockMvc가 part를 요청 본문과 따로 들고 있어 REST Docs가 보는 본문에 boundary 섞인 바이너리가 남으므로, `preprocessRequest(new ContentModifyingOperationPreprocessor(...))`로 본문을 비워 OpenAPI에 `requestBody`가 생기지 않게 한다. part 이름·개수·형식·크기 규칙은 **오퍼레이션 description**에 적는다(매물 사진 업로드 예: `ListingDocsFields.LISTING_IMAGE_UPLOAD_DESCRIPTION`). 그래서 **파일을 받는 요청과 구조화된 요청은 엔드포인트를 가르는 편이 낫다** — 매물 등록이 사진을 파일로 받다가 키 참조로 바뀌면서 요청이 JSON으로 돌아왔고, 그 덕에 요청 스키마가 Swagger에 복구됐다.

**같은 리소스가 `/api/v1`·`/api/v2`에 병존할 때**([api-design-guide §2-1](../api/api-design-guide.md)) 두 가지를 더 지킨다.

- **identifier를 버전별로 가른다.** `operationId`는 그 오퍼레이션 스니펫 identifier들의 **공통 접두사**이고 전역 유일이어야 하므로, 두 버전이 같은 이름 계열을 쓰면 `verifyOpenApiSpec`이 중복으로 빌드를 깬다. **정본이 무접두사, 종료되는 쪽에 접두사**를 붙인다 — 매물 예: `listings-list`(정본 v2) ↔ `v1-listings-list`(종료된 v1).
- **오퍼레이션 상수(summary·description)를 버전별로 따로 둔다.** path가 달라 두 버전은 애초에 병합되지 않으므로, 상수를 공유하면 한쪽 설명이 반대쪽에 그대로 붙는다 — 데이터를 주지 않는 v1이 v2의 설명을 달게 된다. v1 description은 「폐지됐다」 같은 변경 이력이 아니라 **현재 동작**으로 쓴다([ADR-0017](../adr/0017-openapi-swagger-ui-from-restdocs.md) description 작성 규약).

**리뷰 체크리스트** — `verifyOpenApiSpec`이 자동으로 잡지 못해 사람이 봐야 하는 것:

- 두 버전이 서로의 상수(summary·description)나 파라미터·필드 기술자를 재사용하는가? 특히 **종료된 버전이 정본의 기술자를 빌려 쓰면** 동작하지 않는 필터를 살아 있는 것처럼 문서화한다.
- description에 「null」을 언급했는데 그 필드가 `optional()`이 아닌가?
- **요청** 필드를 `optional()`로 낮추면서 **그 필드를 생략한 성공 케이스**를 추가했는가? 응답 필드의 `doesNotExist()` 규칙은 요청 본문에 걸 수 없으므로 등가 방어는 이것뿐이다. 애너테이션만 떼고 기술자를 그대로 두면 예시 페이로드에 값이 남아 있어 **테스트는 통과하고 OpenAPI만 required로 남는다**
- description에 UPPER_SNAKE 값을 나열했는데 `enumField`/`codeField`를 안 썼는가?
- 배열 필드에 스칼라 `codeField`를 썼는가? (테스트는 통과하고 문서만 틀린다)
- 필드를 `optional`로 낮추면서 그 필드가 없어야 하는 케이스에 `doesNotExist()` 단정을 빠뜨렸는가?
- 에러 스니펫이 불필요한 요청 본문을 보내는가? (필터 단계 401·403과 `MALFORMED_REQUEST`는 본문 없이)

### 3-5. 모듈 경계

`ApplicationModules.verify()`로 순환 의존·내부 패키지 접근·허용 의존 위반을 빌드 시점에 잡는다. 새 모듈/의존을 추가하면 반드시 통과해야 한다.

## 4. 테스트 데이터·인프라

- **통합 테스트 = Testcontainers**: 실제 엔진을 띄워 검증한다. 폴리글랏([ADR-0005](../adr/0005-polyglot-persistence.md)/[ADR-0006](../adr/0006-refresh-token-store-redis.md))에서 **해당 테스트가 실제로 쓰는 엔진만** 띄우고, 쓰지 않는 엔진은 띄우지 않는다 — MySQL·Redis는 공용 `TestcontainersConfiguration`, MongoDB는 필요한 테스트가 직접 선언하며 모두 `@ServiceConnection`으로 연결이 자동 주입된다. 오브젝트 스토리지는 **MinIO**(`org.testcontainers:minio`, [ADR-0041](../adr/0041-listing-image-upload-to-s3.md))를 띄우되 `@ServiceConnection` 대상이 아니라 컨테이너가 알려 준 endpoint·자격증명으로 `S3Client`를 직접 조립한다. Docker 데몬이 필요하다(로컬·CI 공통).
- **단위 테스트는 컨테이너가 필요 없다.** 네트워크 너머의 외부 연동은 통합에서도 실물을 부르지 않는다. 대체 수단은 둘이고 **프로토콜 호환 구현을 로컬 컨테이너로 띄울 수 있으면 가짜보다 그것을 먼저 쓴다** — 어댑터는 그 컨테이너로 검증하고(S3 ↔ MinIO: `S3ListingImageStorageIntegrationTest`), 띄울 수 없으면 가짜로 대체한다(소셜 로그인 검증: `@TestConfiguration @Primary`). 애플리케이션 통합(`@SpringBootTest`)은 어느 쪽이든 스텁으로 가볍게 돌린다 — 사진 저장소처럼 설정 스위치로 스텁 폴백이 있으면 테스트 프로파일에서 그 스위치를 끈다(`app.images.enabled=false` → `StubListingImageStorage`).
- 로컬 개발은 docker-compose로 동일 엔진을 제공하고, 운영 스키마는 Flyway로 관리한다([ADR-0008](../adr/0008-mysql-migration-flyway.md)).
- 비밀값은 테스트 전용 더미를 쓰고 운영 시크릿은 커밋하지 않는다.

## 5. 규약

- 클래스명 `*Test`. 메서드명은 `행위_상황_기대` 형태의 서술형.
- 단정은 **AssertJ**로 통일하고, 예외는 `assertThatThrownBy(...).isInstanceOf(...)`로 검증한다.
- 통합 테스트는 응답을 **공통 래퍼 기준**으로 단정한다(`$.success`·`$.data.*`·`$.error.code`).
- 커밋 전 `./gradlew spotlessApply` 후 `build`로 포맷·테스트를 함께 통과시킨다(CI가 `spotlessCheck build`를 강제).

## 6. 커버리지 우선순위

도메인 불변식 > 응용 분기/예외 > 보안 인가 매핑 > 시나리오 종단 > 직렬화 세부. 외부 기술에 위임하는 인프라(Redis·JPA 어댑터)는 통합으로 미루되, 외부 의존이 없는 어댑터 순수 로직(해시·서명·상태 매핑)은 단위로 검증한다 — mock이 구현을 되읊는 동어반복이 되는 경계가 기준이다.
