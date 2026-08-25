# API Specs

> Kohere 백엔드 API의 도메인별 상세 스펙이다. 공통 설계 규약은 [api-design-guide](../api-design-guide.md), 에러 응답은 [error-response-guide](../error-response-guide.md)를 정본으로 한다.
> 유저 스토리·인수 조건(AC)은 [user-stories](../../requirements/user-stories.md)를 본다.
> 모든 응답은 공통 래퍼 `{ success, data, error }`로 감싸고, 경로는 `/api/v1` 또는 `/api/v2` 프리픽스를 가진다. **기본은 `/api/v1`이고, 계약이 바뀐 일부 도메인만 `/api/v2`를 둔다** — 매물은 등록·수정과 조회 계열이 `/api/v2` 정본이고(임대인이 자기 매물을 고치는 `PUT /api/v2/listings/{listingId}`와 임대인 전용 내 매물 조회 `/api/v2/users/me/listings*`도 여기 얹힌다) `/api/v1` 조회는 빈 결과·404를 주는 `deprecated` 스텁이며, 관리자 매물 심사만 새 네임스페이스라 `/api/v1/admin/listings*`에서 시작한다([03-listings-favorites](03-listings-favorites.md)). 진단은 서버 주도 흐름 3종이 `/api/v2`에 신설돼 `/api/v1` 7종과 함께 동작한다([02-diagnosis-recommendation](02-diagnosis-recommendation.md)). 버전은 도메인마다 다르므로 각 스펙의 엔드포인트 요약 표를 정본으로 본다.

| # | 도메인 | 스펙 |
| --- | --- | --- |
| 1 | 소셜 로그인 · 온보딩 | [01-auth-onboarding](01-auth-onboarding.md) |
| 2 | 맞춤 진단 & 매물 추천 | [02-diagnosis-recommendation](02-diagnosis-recommendation.md) |
| 3 | 매물 등록 · 탐색 · 찜 | [03-listings-favorites](03-listings-favorites.md) |
| 4 | 매물 예약(신청) 독립 · (후속) 문의·인앱 채팅 | [04-booking-inquiry-chat](04-booking-inquiry-chat.md) |
| 5 | 커뮤니티 (게시판 · 동네친구) | [05-community](05-community.md) |
| 6 | 게이미피케이션 (퀴즈) | [06-gamification](06-gamification.md) |
| 7 | 신고 처리 | [07-reports](07-reports.md) |
| 8 | 생활 팁 (주제별 생활 정보) | [08-life-tips](08-life-tips.md) |
