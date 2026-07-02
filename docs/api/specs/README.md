# API Specs

> Kohere 백엔드 API의 도메인별 상세 스펙이다. 공통 설계 규약은 [api-design-guide](../api-design-guide.md), 에러 응답은 [error-response-guide](../error-response-guide.md)를 정본으로 한다.
> 유저 스토리·인수 조건(AC)은 [user-stories](../../requirements/user-stories.md)를 본다.
> 모든 응답은 공통 래퍼 `{ success, data, error }`로 감싸고, 경로는 `/api/v1` 프리픽스를 가진다.

| # | 도메인 | 스펙 |
| --- | --- | --- |
| 1 | 소셜 로그인 · 온보딩 | [01-auth-onboarding](01-auth-onboarding.md) |
| 2 | 맞춤 진단 & 매물 추천 | [02-diagnosis-recommendation](02-diagnosis-recommendation.md) |
| 3 | 매물 탐색 · 찜 | [03-listings-favorites](03-listings-favorites.md) |
| 4 | 신청 · 문의 (인앱 채팅) | [04-booking-inquiry-chat](04-booking-inquiry-chat.md) |
| 5 | 커뮤니티 (게시판 · 동네친구) | [05-community](05-community.md) |
| 6 | 게이미피케이션 (퀴즈) | [06-gamification](06-gamification.md) |
| 7 | 신고 처리 | [07-reports](07-reports.md) |
