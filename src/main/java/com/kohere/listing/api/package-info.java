/**
 * listing 모듈의 공개 API(Named Interface "api"). 다른 모듈(예: diagnosis·booking·chat)이 의존할 수 있는 공개 쿼리·계약을
 * 노출한다.
 *
 * <p>diagnosis는 진단 결과 추천에서 {@link com.kohere.listing.api.ListingRecommendationService}를 동기 호출한다(즉시
 * 결과가 필요한 조회 → ADR-0002 Decision 5, 이벤트 아님). 내부 도메인/영속 타입은 노출하지 않으며, 모듈 간 enum은 원시 문자열로
 * 주고받는다(domain-model §1·§2). 매칭 로직·listing 컬렉션 스키마는 본 API 범위
 * 밖이다(docs/api/specs/02-diagnosis-recommendation.md §7).
 *
 * <p>chat은 {@link com.kohere.listing.api.ChatListingQueryService}로 공개 매물의 실제 임대인과 화면 snapshot만
 * 가져간다. 클라이언트가 임대인 번호를 지정하게 하지 않고 listing 정본에서 결정하므로 다른 사람을 상대방으로 넣는 요청 변조를 막는다. 실제 조회 구현체는 채팅 기능
 * 3단계에서 연결한다.
 */
@org.springframework.modulith.NamedInterface("api")
package com.kohere.listing.api;
