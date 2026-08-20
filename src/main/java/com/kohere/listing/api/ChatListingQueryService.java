package com.kohere.listing.api;

import java.util.Optional;

/**
 * 채팅 문의 생성에 필요한 매물 정보를 제공하는 listing 모듈의 공개 조회 계약.
 *
 * <p>chat 모듈은 listing 저장소나 내부 엔티티를 직접 조회하지 않고 이 인터페이스에만 의존한다. 이렇게 하면 문의 채팅방 생성에 필요한 정보의 범위가 명확해지고,
 * MongoDB 구조가 바뀌어도 chat 모듈까지 함께 수정되는 것을 막는다.
 *
 * <p>조회 구현은 listing 모듈이 소유하고, chat 모듈은 반환된 공개 뷰만 사용한다.
 */
public interface ChatListingQueryService {

  /**
   * 문의 가능한 공개 매물을 조회한다.
   *
   * <p>매물이 없거나 공개 상태가 아니면 {@link Optional#empty()}를 반환한다. 호출자는 이 결과를 채팅 도메인의 매물 없음 오류로 변환한다.
   *
   * @param listingId 문의를 시작하려는 매물 식별자
   * @return 공개 매물의 임대인과 표시 정보, 또는 조회할 수 없으면 빈 값
   */
  Optional<ChatListingView> findPublishedListing(String listingId);
}
