package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberPage;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageResponse;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅방 목록 서비스가 사용자별 가시성 경계와 batch 조회 규칙을 지키는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomListServiceTest {

  private static final long USER_ID = 7L;
  private static final long COUNTERPART_ID = 42L;
  private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ChatMessageTranslationRepository translationRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private UserBlockService userBlockService;

  private ChatRoomListService service;

  /** 각 테스트에서 같은 실제 서비스 객체에 mock 포트를 연결한다. */
  @BeforeEach
  void setUp() {
    service =
        new ChatRoomListService(
            chatRoomRepository,
            memberRepository,
            messageRepository,
            translationRepository,
            userAccountService,
            userBlockService);
  }

  /** 현재 페이지의 방과 마지막 메시지를 각각 한 번만 batch 조회하고, member 페이지가 정한 순서를 그대로 반환한다. */
  @Test
  @DisplayName("채팅방 목록은 현재 페이지의 방과 마지막 메시지를 일괄 조회한다")
  void loadsRoomsAndLastMessagesInBatches() {
    ChatRoomMember firstMember = member(20L, ChatParticipantRole.LANDLORD, 0L);
    ChatRoomMember secondMember = member(10L, ChatParticipantRole.TENANT, 0L);
    ChatRoom firstRoom = room(20L, 202L, NOW.plusSeconds(2));
    ChatRoom secondRoom = room(10L, 101L, NOW.plusSeconds(1));
    Message card = bookingCard(202L, 9001L, NOW.plusSeconds(2));
    Message text = text(101L, "안녕하세요", NOW.plusSeconds(1));

    given(memberRepository.findVisiblePageByUserId(USER_ID, 0, 2))
        .willReturn(new ChatRoomMemberPage(List.of(firstMember, secondMember), 3L));
    // batch 저장소의 반환 순서는 보장되지 않는다. 서비스가 ID로 조립하는지 확인하려고 일부러 반대로 반환한다.
    given(chatRoomRepository.findByIds(anyCollection())).willReturn(List.of(secondRoom, firstRoom));
    given(messageRepository.findByIds(anyCollection())).willReturn(List.of(text, card));
    given(userAccountService.getUserName(COUNTERPART_ID)).willReturn("상대 사용자");
    given(userBlockService.isBlockedBetween(USER_ID, COUNTERPART_ID)).willReturn(false);

    PageResponse<ChatRoomResponse> result = service.listRooms(USER_ID, 0, 2);

    assertThat(result.content()).extracting(ChatRoomResponse::chatRoomId).containsExactly(20L, 10L);
    assertThat(result.content().get(0).lastMessage().type()).isEqualTo(MessageType.BOOKING_CARD);
    assertThat(result.content().get(0).lastMessage().preview()).isNull();
    assertThat(result.content().get(1).lastMessage().preview()).isEqualTo("안녕하세요");
    assertThat(result.page().totalElements()).isEqualTo(3L);
    assertThat(result.page().hasNext()).isTrue();

    // 두 방의 상대가 같으므로 이름·차단 조회도 요청 안에서 한 번만 수행한다.
    verify(chatRoomRepository, times(1)).findByIds(anyCollection());
    verify(messageRepository, times(1)).findByIds(anyCollection());
    verify(userAccountService, times(1)).getUserName(COUNTERPART_ID);
    verify(userBlockService, times(1)).isBlockedBetween(USER_ID, COUNTERPART_ID);
  }

  /** 다시 표시된 방이라도 사용자가 삭제한 경계 이하의 옛 마지막 메시지는 조회하거나 preview로 반환하지 않는다. */
  @Test
  @DisplayName("삭제한 과거 메시지는 재표시된 채팅방의 미리보기에 노출하지 않는다")
  void doesNotExposeLastMessageAtOrBeforeHiddenBoundary() {
    ChatRoomMember member = member(10L, ChatParticipantRole.TENANT, 150L);
    ChatRoom room = room(10L, 101L, NOW);
    given(memberRepository.findVisiblePageByUserId(USER_ID, 0, 20))
        .willReturn(new ChatRoomMemberPage(List.of(member), 1L));
    given(chatRoomRepository.findByIds(anyCollection())).willReturn(List.of(room));
    given(messageRepository.findByIds(anyCollection())).willReturn(List.of());
    given(userAccountService.getUserName(COUNTERPART_ID)).willReturn("상대 사용자");
    given(userBlockService.isBlockedBetween(USER_ID, COUNTERPART_ID)).willReturn(false);

    PageResponse<ChatRoomResponse> result = service.listRooms(USER_ID, 0, 20);

    assertThat(result.content()).singleElement().extracting(ChatRoomResponse::lastMessage).isNull();
    verify(messageRepository).findByIds(anyCollection());
  }

  /** 잘못된 page·size를 저장소까지 전달하지 않고 공통 400 예외로 바꾼다. */
  @Test
  @DisplayName("채팅방 목록의 페이지 범위를 검증한다")
  void rejectsInvalidPageRange() {
    assertThatThrownBy(() -> service.listRooms(USER_ID, -1, 20))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> service.listRooms(USER_ID, 0, 101))
        .isInstanceOf(InvalidInputException.class);

    verify(memberRepository, never()).findVisiblePageByUserId(USER_ID, -1, 20);
  }

  /** 사용자별 목록 상태 fixture를 만든다. */
  private static ChatRoomMember member(
      long roomId, ChatParticipantRole role, long historyHiddenThroughMessageId) {
    return ChatRoomMember.builder()
        .id(roomId + 1_000)
        .chatRoomId(roomId)
        .userId(USER_ID)
        .counterpartId(COUNTERPART_ID)
        .role(role)
        .historyHiddenThroughMessageId(historyHiddenThroughMessageId)
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }

  /** 마지막 메시지 포인터를 가진 채팅방 fixture를 만든다. */
  private static ChatRoom room(long roomId, long lastMessageId, Instant lastMessageAt) {
    return ChatRoom.builder()
        .id(roomId)
        .listingId("6858e2000000000000000" + roomId / 10)
        .tenantId(USER_ID)
        .landlordId(COUNTERPART_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio", "Seogyo-dong, Mapo-gu"))
        .lastMessageId(lastMessageId)
        .lastMessageAt(lastMessageAt)
        .createdAt(NOW)
        .updatedAt(lastMessageAt)
        .build();
  }

  /** 사용자 TEXT 마지막 메시지 fixture를 만든다. */
  private static Message text(long messageId, String content, Instant sentAt) {
    return Message.builder()
        .id(messageId)
        .chatRoomId(10L)
        .senderId(USER_ID)
        .type(MessageType.TEXT)
        .content(content)
        .clientMessageId(UUID.randomUUID())
        .sentAt(sentAt)
        .build();
  }

  /** 서버 BOOKING_CARD 마지막 메시지 fixture를 만든다. */
  private static Message bookingCard(long messageId, long bookingId, Instant sentAt) {
    BookingCardPayload payload =
        new BookingCardPayload(
            bookingId,
            new BookingCardPayload.Listing(
                "6858e2000000000000000001", null, "Hongdae Studio", "Mapo-gu", 420_000),
            new BookingCardPayload.Applicant(
                USER_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "user@example.com"),
            "room-a",
            "Room A",
            LocalDate.of(2026, 6, 15),
            3,
            0,
            1_260_000);
    return Message.builder()
        .id(messageId)
        .chatRoomId(20L)
        .type(MessageType.BOOKING_CARD)
        .bookingId(bookingId)
        .payload(payload)
        .sentAt(sentAt)
        .build();
  }
}
