# 시퀀스 다이어그램 (Sequence Diagrams)

> 핵심 기능 유저 스토리별 **사용자 → 클라이언트(`앱(클라이언트)` 또는 `웹(브라우저)`) → 백엔드 모듈** 시퀀스 다이어그램이다. API 흐름(요청 메서드·경로·상태코드) 중심으로 그렸다.
> 백엔드는 단일 서버가 아니라 Spring Modulith **모듈 단위 컴포넌트**(`auth` / `diagnosis` / `listing` / `booking` / `chat` / `community` / `gamification` / `report` / `lifetip`)로 분해하고, 한 스토리에 **실제 관여하는 모듈만** 참가자로 둔다.
>
> 표기 규약:
>
> - **클라이언트 참가자**: 기본은 모바일 앱이라 `participant C as 앱(클라이언트)`로 둔다. **임대인 전용 웹**(매물 등록 클라이언트)의 흐름만 `participant C as 웹(브라우저)`로 표기한다 — US-1-11(웹 가입)·US-1-12(웹 로그인)·US-1-13(가입용 휴대폰 인증). 백엔드는 같지만 **refresh 토큰 채널이 다르다**(앱=응답 본문, 웹=HttpOnly 쿠키 — [ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md))는 점이 흐름에 드러나므로 클라이언트 종류를 구분한다. **US-1-15(계정 병합)는 앱 온보딩에서 시작하는 흐름이라 `앱(클라이언트)`** 이다. 한편 **웹 화면이 사용자 유형별로 갈리는 흐름은 그 유형을 참가자 이름에 담는다** — 관리자 심사는 `관리자 웹`(US-3-7), 임대인 매물 관리는 `임대인 웹`(US-3-8·US-3-9)이다. 같은 브라우저 클라이언트지만 화면과 인가 게이트가 다르고, 한 다이어그램에 세입자 앱이 함께 등장할 때 어느 쪽 요청인지가 이름만으로 갈린다.
> - **모듈 간 통신**(code-style §3): 상태 전파·후속 처리는 **비동기 이벤트(`-)`)**, 단순 조회/질의는 **동기 호출(`->>` / `-->>`)**. (예: 신청→채팅 = 이벤트, 진단→매물·커뮤니티→채팅방 생성 = 호출)
> - **공통 JWT 인증**은 컨트롤러 앞단의 `공통 보안 필터(participant SEC)`로 표기한다: `C->>SEC`(Bearer) → SEC가 JWT 검증 → `SEC->>모듈`(`userId`+온보딩 스코프 주입 후 요청 전달). 보호경로 인가는 **3티어**([ADR-0010](../../adr/0010-jwt-authentication-filter.md)):
>   - **공개(permitAll)**: `social-login`·`reissue`·**임대인 웹 인증·계정 복구 10종**(`POST /api/v1/auth/signup`·`/auth/login`·`/auth/phone/signup/verification-code`·`/auth/phone/signup/verify`·`/auth/phone/find-email/verification-code`·`/auth/phone/find-email/verify`·`/auth/email/find`·`/auth/password/reset-link`·`/auth/password/reset-token/verify`·`/auth/password/reset` — 가입·로그인 전이거나 로그인하지 못해 들어오는 자리라 토큰이 없다)·**매물 조회 v2**(`GET /api/v2/listings`·`/api/v2/listings/*` — 목록/지도/키워드 검색/상세)·`GET /api/v1/listings/places`·**actuator**. 미인증도 익명으로 모듈에 전달된다 — 필터를 **우회하는 게 아니라** 필터는 통과하되 인증을 요구하지 않을 뿐이므로, 다이어그램 표기상 SEC를 생략한다(필터 미통과가 아님). **예외로 임대인 웹 인증·계정 복구 10종은 SEC를 생략하지 않고 그린다** — 공개 티어 등록이 `SecurityConfig`(1)과 `PublicPaths.ALL` **두 곳 모두**에 필요하다는 점(#181)과 만료된 access 토큰이 실려 와도 401로 끊지 않는다는 점이 흐름의 전제라서다.
>   - **온보딩 스코프(ROLE_ONBOARDING) 토큰만 통과**: `onboarding`·`DELETE /users/me`. 온보딩 임시 토큰(또는 정식 토큰)으로 접근 가능한 티어.
>   - **정식 자원(ROLE_USER)**: 나머지 보호 엔드포인트. **PENDING(온보딩 스코프) 토큰으로 ROLE_USER 자원에 접근하면 SEC가 403 `AUTH_ONBOARDING_REQUIRED`**(`AccessDeniedHandler`, 모듈 도달 전).
> - **매물 API 버전 경계**: 매물 조회 5종(목록 `GET /listings` · 지도 `/listings/map` · 상세 `/listings/{listingId}` · 찜 토글 `POST`·`DELETE /listings/{listingId}/favorite` · 내 스코프 `/users/me/favorites`·`/users/me/recent-listings`)은 **`/api/v2`가 정본**이므로 다이어그램도 v2 경로로 그린다. **같은 `/api/v2/listings/**` 안에서 티어가 갈린다** — **GET은 공개(`permitAll`)**, **쓰기(등록 `POST /api/v2/listings`·사진 업로드 `POST /api/v2/listings/images`·찜 토글 `POST`/`DELETE`)와 `me` 스코프 조회(`/api/v2/users/me/**`)는 정식 자원(`ROLE_USER`)** 이다. 한편 **`/api/v1` 조회 계열은 구버전 앱 호환용 `deprecated` 스텁**(제거 시점 미정)이라 저장소에 접근하지 않고 빈 결과(목록형은 `content: []`·`totalElements: 0`, 지도는 마커 0건) 또는 `404 LISTING_NOT_FOUND`(상세·찜 토글)만 반환하므로 시퀀스로 그리지 않는다 — 예외로 **`GET /api/v1/listings/places`(네이버 장소 검색)는 매물 데이터를 쓰지 않아 v1 그대로 동작**한다(US-3-3).
> - 인증 실패 경계: 토큰 무효/만료/누락은 **EntryPoint가 401**(`UNAUTHENTICATED`/`TOKEN_EXPIRED`). **스코프 부족 403은 SEC(AccessDeniedHandler) 책임**, **리소스 소유권 403(`FORBIDDEN`)은 모듈 책임**으로 구분한다. 비즈니스 규칙(409/422)도 **모듈**이 판단한다.
> - **게스트(비회원) 개방 경로 예외**(#181): 퀴즈(`/api/v1/quizzes/**`)·생활 팁(`/api/v1/life-tips/**`)·**v2 진단(`/api/v2/diagnoses/**`)** 은 `permitAll`이라 위 401/403 경계가 그대로 적용되지 않는다(**v1 진단(`/api/v1/diagnoses/**`)은 회원 전용으로 유지**되어 이 예외의 대상이 아니다 — 매처를 추가하지 않아 위 경계가 그대로 적용된다) — **토큰 미전송·위조/형식 오류는 401이 아니라 게스트**(`userId == null`, 합성 userId를 발급하지 않는다)로 모듈에 전달되고, **토큰을 보냈는데 만료된 경우만 `401 TOKEN_EXPIRED`** 를 유지한다. 따라서 이 세 영역의 다이어그램은 공개 티어인데도 **SEC를 생략하지 않고** 그 분기를 그린다. 모듈 책임인 소유권·역할 403(`FORBIDDEN`)은 회원 경로에서 그대로다 — 로그인한 임대인의 퀴즈·생활 팁 호출은 여전히 403이다.
> - **도메인 상태 영속**은 가장 오른쪽의 `저장소(participant)` 컴포넌트로 표기한다(`모듈->>저장소` 저장/조회/갱신, `저장소-->>모듈` 결과). 저장소 배치는 **폴리글랏으로 확정**됐다([ADR-0005](../../adr/0005-polyglot-persistence.md)·[ADR-0006](../../adr/0006-refresh-token-store-redis.md)) — 각 `모듈->>저장소` 화살표는 **그 화살표 왼쪽 모듈이 소유한 저장소**를 가리킨다:
>   - **MongoDB**: `listing`(+`favorite`·`recent-listing`)·`diagnosis`·`lifetip`·`gamification`(읽기 전용, 1차 MVP 이후)
>   - **MySQL**: `auth`(계정·소셜·회원상태)·`user`(프로필)·`community`
>   - **Redis**: `auth`의 **refresh 토큰**(해시 저장·조회·회전·무효화, TTL 기반)
>   - **오브젝트 스토리지(S3)**: `listing`의 매물 사진 원본(업로드 API가 한 장씩 받아 임시 위치에 두고, 등록이 확정될 때 확정 위치로 복사한다). 도메인 상태가 아니라 파일이라 폴리글랏 결정([ADR-0005](../../adr/0005-polyglot-persistence.md)) 대상이 아니고, 매물 문서에는 파일이 아니라 읽기 URL만 남는다(배포 환경은 CloudFront 도메인 — 버킷이 비공개 OAC라 S3 URL로는 읽히지 않는다). 로컬은 MinIO로 **같은 어댑터에 endpoint만 바꿔** 붙인다([ADR-0041](../../adr/0041-listing-image-upload-to-s3.md))
>   - **저장소(추후 결정)**: `booking`·`chat`(F-03)·`report` — ADR 미정이라 구체 저장소를 임의 확정하지 않는다
> - 한 흐름이 **여러 저장소**에 걸치면 저장소별 참가자를 따로 둔다 — 예: 로그인·온보딩·로그아웃/탈퇴 = **MySQL(계정·상태) + Redis(refresh)**, **매물 등록 = 이미지 저장소(S3)(사진 파일) + MongoDB(매물 문서)**, 매물 신청 = **MongoDB(매물 조회) + 저장소(추후 결정)(예약·채팅 기록)**, 동네친구 채팅 시작 = **MySQL(게시글 확인) + 저장소(추후 결정)(채팅방)**. 매물 등록처럼 **두 저장소에 쓰면서 한쪽 쓰기가 다른 쪽의 전제**가 되는 흐름은 분산 트랜잭션이 없으므로 **쓰기 순서와 보상 삭제**까지 그린다 — 복사 → 저장 순이고 저장이 실패하면 방금 복사한 객체를 지운다(US-3-6, [ADR-0041](../../adr/0041-listing-image-upload-to-s3.md)). co-location이라도 **cross-store·cross-collection 조인은 하지 않으며**, 모듈은 자기 저장소만 읽고 쓴다([ADR-0005](../../adr/0005-polyglot-persistence.md) D2·D5). 인증/검증 실패(401) 경로에는 저장소 접근이 없다. 정적 enum 응답(신고 사유)은 저장소를 두지 않는다.
> - 외부 의존(푸시 등)은 모듈의 `Note`로 표기하되, **OAuth 로그인(US-1-1)** 에 한해 외부 제공자(Apple/Google)를 참가자로 두고, **US-3-3(네이버 지역 검색)** 처럼 외부 API 호출 자체가 흐름의 핵심인 경우도 해당 외부 API(네이버 지역 검색 API)를 참가자로 둔다. **US-3-6(매물 등록)** 처럼 요청이 외부 저장소에 쓰는 흐름도 그 저장소(`participant S3 as 이미지 저장소(S3)`)를 참가자로 둔다 — 업로드·복사와 실패 시 보상 삭제가 흐름의 분기라 `Note`로는 그릴 수 없다.
>
> 관련: [user-stories](../../requirements/user-stories.md) · [api/specs](../../api/specs/README.md) · [code-style §3](../../convention/code-style.md) · [system-overview](../system-overview.md)

| # | 모듈 | 폴더 | 다이어그램 수 |
| --- | --- | --- | --- |
| 1 | 소셜 로그인 · 온보딩 (+ 임대인 웹 인증·계정 복구) | [01-auth-onboarding/](01-auth-onboarding/README.md) | 16 |
| 2 | 맞춤 진단 & 매물 추천 | [02-diagnosis-recommendation/](02-diagnosis-recommendation/README.md) | 6 |
| 3 | 매물 등록 · 탐색 · 찜 | [03-listings-favorites/](03-listings-favorites/README.md) | 9 |
| 4 | 매물 예약(신청) 독립 · (후속) 문의·인앱 채팅 | [04-booking-inquiry-chat/](04-booking-inquiry-chat/README.md) | 9 |
| 5 | 커뮤니티 (게시판 · 동네친구) | [05-community/](05-community/README.md) | 5 |
| 6 | 게이미피케이션 (퀴즈) | [06-gamification/](06-gamification/README.md) | 3 |
| 7 | 신고 처리 | [07-reports/](07-reports/README.md) | 3 |
| 8 | 생활 팁 (주제별 생활 정보) | [08-life-tips/](08-life-tips/README.md) | 2 |

총 53개 다이어그램.
