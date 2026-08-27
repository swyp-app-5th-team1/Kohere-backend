package com.kohere.chat.application;

import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.domain.ChatListingUnavailableException;
import com.kohere.chat.domain.ChatTenantOnlyException;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.SelfInquiryNotAllowedException;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채팅 REST 유스케이스를 조율하는 응용 서비스다.
 *
 * <p>현재는 매물 문의로 채팅방을 조회하거나 만드는 유스케이스를 담당한다. 메시지 이력·목록·단건 조회는 책임이 큰 한 서비스에 몰리지 않도록 각각의 읽기 전용 서비스가
 * 담당한다. 컨트롤러는 {@code @AuthenticationPrincipal AuthPrincipal}에서 검증된 {@code userId}를 꺼내 서비스에 전달하며,
 * body나 query로 사용자 ID를 선택하게 두지 않는다.
 *
 * <p>TEXT 저장은 STOMP 처리 흐름의 별도 유스케이스가 담당한다. REST 전송 메서드를 이 서비스에 함께 두지 않아 같은 메시지에 두 개의 진입 경로와 서로 다른
 * 중복 처리 규칙이 생기는 것을 막는다. 읽음 처리는 이번 범위에서 제외한다.
 *
 * <p>문의와 신청이 동일한 방 생성 규칙을 사용하도록 실제 조회·생성·동시성 수렴은 {@link ChatRoomEnsurer}에 위임한다. 이 서비스는 문의 요청자만 수행할
 * 역할·매물·차단 검증과 기존 방 재표시를 담당한다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private static final String USER_TYPE_TENANT = "TENANT";

  private final ChatListingQueryService listingQueryService;
  private final UserAccountService userAccountService;
  private final UserBlockService userBlockService;
  private final ChatRoomCreator roomCreator;
  private final ChatRoomEnsurer roomEnsurer;
  private final InquiryCardRealtimePublisher inquiryCardRealtimePublisher;

  /**
   * 매물·세입자·임대인 조합의 채팅방을 조회하거나 문의서와 함께 하나만 생성한다.
   *
   * @param tenantId JWT에서 확인한 요청자 {@code users.id}
   * @param listingId 문의 대상 매물 식별자
   * @return 방 ID와 이번 호출에서 새로 생성했는지 여부
   */
  public InquiryResponse createInquiry(long tenantId, String listingId) {
    assertTenant(tenantId);

    ChatListingView listing =
        listingQueryService
            .findPublishedListing(listingId)
            .orElseThrow(ChatListingUnavailableException::new);
    assertDifferentUsers(tenantId, listing.landlordId());
    assertChatAvailable(tenantId, listing.landlordId());

    ChatRoomSeed seed =
        new ChatRoomSeed(
            listing.listingId(), listing.landlordId(), listing.title(), listing.address());
    InquiryCardPayload inquiryPayload = toInquiryCardPayload(listing);
    Instant now = Instant.now();
    ChatRoomEnsurer.InquiryEnsureResult ensured =
        roomEnsurer.ensureInquiry(seed, tenantId, inquiryPayload, now);

    // 직접 문의는 사용자의 명시적 재진입이다. 기존 방만 다시 표시하며 과거 메시지 숨김 경계는 복원하지 않는다.
    if (!ensured.created()) {
      roomCreator.showExistingRoomForTenant(ensured.room().getId(), tenantId, now);
    } else {
      // createInquiry 트랜잭션이 반환된 뒤이므로 DB commit이 끝난 카드만 실시간 채널에 전달한다.
      inquiryCardRealtimePublisher.publishNewInquiryCard(ensured.room(), ensured.message());
    }
    return new InquiryResponse(ensured.room().getId(), ensured.created());
  }

  /** listing 공개 뷰를 chat 모듈이 영구 보존할 문의서 payload로 복사한다. */
  private static InquiryCardPayload toInquiryCardPayload(ChatListingView listing) {
    return new InquiryCardPayload(
        listing.listingId(),
        listing.thumbnailUrl(),
        listing.title(),
        listing.city(),
        listing.district(),
        listing.listingType(),
        listing.monthlyRentMin(),
        listing.monthlyRentMax());
  }

  /** 매물 문의는 세입자 전용이다. JWT userId로 서버의 현재 사용자 역할을 다시 확인한다. */
  private void assertTenant(long userId) {
    if (!USER_TYPE_TENANT.equals(userAccountService.getUserType(userId))) {
      throw new ChatTenantOnlyException();
    }
  }

  /** 매물 소유자와 요청자가 같으면 자기 자신과의 1:1 방을 만들지 않는다. */
  private static void assertDifferentUsers(long tenantId, long landlordId) {
    if (tenantId == landlordId) {
      throw new SelfInquiryNotAllowedException();
    }
  }

  /** 어느 방향이든 차단 관계가 있으면 방 조회·생성 전에 동일한 403으로 거부한다. */
  private void assertChatAvailable(long tenantId, long landlordId) {
    if (userBlockService.isBlockedBetween(tenantId, landlordId)) {
      throw new ChatUnavailableException();
    }
  }
}
