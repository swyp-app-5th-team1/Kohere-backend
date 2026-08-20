package com.kohere.chat.application;

import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.domain.ChatListingUnavailableException;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatTenantOnlyException;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.SelfInquiryNotAllowedException;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <p>방과 두 참여자의 생성 트랜잭션은 {@link ChatRoomCreator}가 소유한다. 이 서비스는 트랜잭션이 끝난 뒤 UNIQUE 충돌을 받아 기존 방으로
 * 수렴시키므로 실패한 JPA 트랜잭션 안에서 재조회하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private static final String USER_TYPE_TENANT = "TENANT";

  private final ChatRoomRepository chatRoomRepository;
  private final ChatListingQueryService listingQueryService;
  private final UserAccountService userAccountService;
  private final UserBlockService userBlockService;
  private final ChatRoomCreator roomCreator;

  /**
   * 매물·세입자·임대인 조합의 채팅방을 조회하거나 하나만 생성한다.
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

    // 선행 조회는 대부분의 재호출을 INSERT 없이 끝내는 최적화다. 동시 요청의 최종 중복 방지는 DB UNIQUE가 맡는다.
    return chatRoomRepository
        .findByListingIdAndTenantIdAndLandlordId(
            listing.listingId(), tenantId, listing.landlordId())
        .map(room -> existingInquiry(room, tenantId))
        .orElseGet(() -> createOrFindConcurrentRoom(tenantId, listing));
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

  /** 기존 방은 같은 ID를 반환하고, 직접 문의로 재진입한 임차인에게 방만 다시 표시한다. */
  private InquiryResponse existingInquiry(ChatRoom room, long tenantId) {
    roomCreator.showExistingRoomForTenant(room.getId(), tenantId, Instant.now());
    return new InquiryResponse(room.getId(), false);
  }

  /**
   * 새 방 생성을 시도하고, 동시에 먼저 생성한 요청이 있으면 그 방으로 수렴한다.
   *
   * <p>{@link ChatRoomCreator#create}의 트랜잭션은 이 메서드로 예외가 돌아오기 전에 이미 롤백된다. 따라서 UNIQUE 충돌 후 재조회는
   * rollback-only 트랜잭션 밖에서 실행된다. 다른 제약 위반이라 기존 방도 찾을 수 없다면 원래 예외를 다시 던져 실제 결함을 숨기지 않는다.
   */
  private InquiryResponse createOrFindConcurrentRoom(long tenantId, ChatListingView listing) {
    try {
      ChatRoom created = roomCreator.create(listing, tenantId, Instant.now());
      return new InquiryResponse(created.getId(), true);
    } catch (DataIntegrityViolationException conflict) {
      return chatRoomRepository
          .findByListingIdAndTenantIdAndLandlordId(
              listing.listingId(), tenantId, listing.landlordId())
          .map(room -> new InquiryResponse(room.getId(), false))
          .orElseThrow(() -> conflict);
    }
  }
}
