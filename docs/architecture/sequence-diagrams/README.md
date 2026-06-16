# 시퀀스 다이어그램 (Sequence Diagrams)

> 핵심 기능 유저 스토리별 **사용자 → 앱(클라이언트) → 백엔드 모듈** 시퀀스 다이어그램이다. API 흐름(요청 메서드·경로·상태코드) 중심으로 그렸다.
> 백엔드는 단일 서버가 아니라 Spring Modulith **모듈 단위 컴포넌트**(`auth` / `diagnosis` / `listing` / `booking` / `chat` / `community` / `gamification` / `report`)로 분해하고, 한 스토리에 **실제 관여하는 모듈만** 참가자로 둔다.
>
> 표기 규약:
>
> - **모듈 간 통신**(code-style §3): 상태 전파·후속 처리는 **비동기 이벤트(`-)`)**, 단순 조회/질의는 **동기 호출(`->>` / `-->>`)**. (예: 신청→채팅 = 이벤트, 진단→매물·커뮤니티→채팅방 생성 = 호출)
> - **공통 JWT 인증**은 컨트롤러 앞단의 `공통 보안 필터(participant SEC)`로 표기한다: `C->>SEC`(Bearer) → SEC가 JWT 검증 → `SEC->>모듈`(인증된 요청 전달). 토큰 무효/만료는 **SEC가 401**(`UNAUTHENTICATED`/`TOKEN_EXPIRED`) 반환, 권한·스코프 부족(403)·비즈니스 규칙(409/422)은 **모듈**이 판단. 공개/비로그인 허용 엔드포인트(`social-login`·`reissue`·목록/상세/검색)는 SEC를 거치지 않고 모듈로 직접 간다.
> - **도메인 상태 영속**은 가장 오른쪽의 `저장소(participant)` 컴포넌트로 표기한다(`모듈->>저장소` 저장/조회/갱신, `저장소-->>모듈` 결과). 저장소 배치는 **폴리글랏으로 확정**됐다([ADR-0005](../../adr/0005-polyglot-persistence.md)·[ADR-0006](../../adr/0006-refresh-token-store-redis.md)) — 각 `모듈->>저장소` 화살표는 **그 화살표 왼쪽 모듈이 소유한 저장소**를 가리킨다:
>   - **MongoDB**: `listing`(+`favorite`·`recent-listing`)·`diagnosis`
>   - **MySQL**: `auth`(계정·소셜·회원상태)·`user`(프로필)·`community`
>   - **Redis**: `auth`의 **refresh 토큰**(해시 저장·조회·회전·무효화, TTL 기반)
>   - **저장소(추후 결정)**: `booking`·`chat`(F-03)·`gamification`·`report` — ADR 미정이라 구체 저장소를 임의 확정하지 않는다
> - 한 흐름이 **여러 저장소**에 걸치면 저장소별 참가자를 따로 둔다 — 예: 로그인·온보딩·로그아웃/탈퇴 = **MySQL(계정·상태) + Redis(refresh)**, 매물 신청 = **MongoDB(매물 조회) + 저장소(추후 결정)(예약·채팅 기록)**, 동네친구 채팅 시작 = **MySQL(게시글 확인) + 저장소(추후 결정)(채팅방)**. co-location이라도 **cross-store·cross-collection 조인은 하지 않으며**, 모듈은 자기 저장소만 읽고 쓴다([ADR-0005](../../adr/0005-polyglot-persistence.md) D2·D5). 인증/검증 실패(401) 경로에는 저장소 접근이 없다. 정적 enum 응답(신고 사유)은 저장소를 두지 않는다.
> - 외부 의존(푸시 등)은 모듈의 `Note`로 표기하되, **OAuth 로그인(US-1-1)** 에 한해 외부 제공자(Apple/Google)를 참가자로 둔다.
>
> 관련: [user-stories](../../requirements/user-stories.md) · [api/specs](../../api/specs/README.md) · [code-style §3](../../convention/code-style.md) · [system-overview](../system-overview.md)

| # | 모듈 | 폴더 | 다이어그램 수 |
| --- | --- | --- | --- |
| 1 | 소셜 로그인 · 온보딩 | [01-auth-onboarding/](01-auth-onboarding/README.md) | 5 |
| 2 | 맞춤 진단 & 매물 추천 | [02-diagnosis-recommendation/](02-diagnosis-recommendation/README.md) | 4 |
| 3 | 매물 탐색 · 찜 | [03-listings-favorites/](03-listings-favorites/README.md) | 5 |
| 4 | 신청 · 문의 (인앱 채팅) | [04-booking-inquiry-chat/](04-booking-inquiry-chat/README.md) | 4 |
| 5 | 커뮤니티 (게시판 · 동네친구) | [05-community/](05-community/README.md) | 5 |
| 6 | 게이미피케이션 (퀴즈 · 포인트) | [06-gamification/](06-gamification/README.md) | 3 |
| 7 | 신고 처리 | [07-reports/](07-reports/README.md) | 3 |

총 29개 다이어그램.
