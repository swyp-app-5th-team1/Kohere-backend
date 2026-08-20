package com.kohere.chat.application;

import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.domain.ChatListingUnavailableException;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatTenantOnlyException;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.SelfInquiryNotAllowedException;
import com.kohere.common.response.CursorResponse;
import com.kohere.common.response.PageResponse;
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
 * <p>컨트롤러가 받은 식별자와 인증 사용자를 도메인·repository port에 연결하되, 참여자 검증·사용자별 숨김 경계·멱등 방 생성 같은 규칙은 도메인과 트랜잭션
 * 안에서 일관되게 적용한다. 컨트롤러는 {@code @AuthenticationPrincipal AuthPrincipal}에서 검증된 {@code userId}를 꺼내 서비스에
 * 전달하며, body나 query로 사용자 ID를 선택하게 두지 않는다.
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
  private final MessageRepository messageRepository;
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

  /**
   * 현재 사용자에게 보이는 채팅방만 최근 활동 순으로 조회한다.
   *
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 항목 수
   * @return 채팅방 목록과 페이지 메타데이터
   */
  public PageResponse<ChatRoomResponse> listRooms(int page, int size) {
    throw new UnsupportedOperationException("TODO: 내 채팅방 목록 조회(lastMessageAt desc)");
  }

  /**
   * 한 채팅방의 저장된 메시지를 과거 조회 또는 재연결 누락 보충 방식으로 읽는다.
   *
   * <p>응용 계층은 요청자가 참여자인지와 사용자별 삭제 경계를 함께 확인한다. 원문은 항상 반환하고 현재 사용자를 위한 저장된 번역본이 있을 때만 별도 translation
   * 객체를 붙인다.
   *
   * @param roomId 조회 대상 채팅방 식별자
   * @param cursor 과거 방향 조회 기준 메시지 ID
   * @param afterMessageId 미래 방향 누락 조회 기준 메시지 ID
   * @param size 조회할 최대 메시지 수
   * @return 메시지 목록과 다음 커서 정보
   */
  public CursorResponse<MessageResponse> getMessages(
      Long roomId, String cursor, String afterMessageId, int size) {
    throw new UnsupportedOperationException("TODO: 과거 또는 누락 메시지 커서 조회");
  }
}
