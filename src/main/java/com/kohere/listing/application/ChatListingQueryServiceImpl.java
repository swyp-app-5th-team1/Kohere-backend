package com.kohere.listing.application;

import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채팅방 생성에 필요한 공개 매물 정보를 제공하는 {@link ChatListingQueryService} 구현체다.
 *
 * <p>chat 모듈이 listing 저장소를 직접 읽지 않도록 조회와 변환을 listing 모듈 안에서 끝낸다. 공개 상태인 매물만 반환하므로 문의할 수 없는 매물의 소유자나
 * 표시 정보가 chat 모듈로 새어 나가지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatListingQueryServiceImpl implements ChatListingQueryService {

  private final ListingRepository listingRepository;

  /**
   * {@code listingId}로 공개 매물을 찾아 채팅 전용 최소 정보로 변환한다.
   *
   * <p>매물이 없거나 공개 상태가 아니면 예외 대신 빈 값을 반환한다. 어떤 채팅 오류로 응답할지는 이 공개 쿼리의 호출자인 chat 모듈이 결정한다.
   */
  @Override
  public Optional<ChatListingView> findPublishedListing(String listingId) {
    return listingRepository
        .findById(listingId)
        // 공개되지 않은 매물에는 새 문의 채팅방을 만들 수 없다.
        .filter(listing -> listing.getStatus() == Listing.ListingStatus.PUBLISHED)
        .map(ListingResponseMapper::toChatListingView);
  }
}
