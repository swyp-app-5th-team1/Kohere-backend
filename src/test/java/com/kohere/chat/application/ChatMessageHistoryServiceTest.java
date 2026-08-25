package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.CursorResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 메시지 이력의 참여자 권한, 삭제 경계, 두 커서 방향과 응답 변환을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatMessageHistoryServiceTest {

  private static final long USER_ID = 7L;
  private static final long COUNTERPART_ID = 42L;
  private static final long ROOM_ID = 556L;
  private static final Instant SENT_AT = Instant.parse("2026-08-20T10:15:30Z");

  @Mock private AppUserGuard appUserGuard;

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ChatMessageTranslationRepository translationRepository;

  private ChatMessageHistoryService service;

  /** 각 테스트가 실제 서비스 로직만 확인하도록 저장소는 mock으로 연결한다. */
  @BeforeEach
  void setUp() {
    service =
        new ChatMessageHistoryService(
            chatRoomRepository,
            appUserGuard,
            memberRepository,
            messageRepository,
            translationRepository);
  }

  /** 첫 진입은 최근 메시지를 최신순으로 반환하고 size+1번째 행으로 다음 페이지를 판단한다. */
  @Test
  @DisplayName("최근 메시지 첫 페이지를 조회한다")
  void getsRecentMessages() {
    prepareVisibleRoom(0L);
    given(messageRepository.findBefore(ROOM_ID, null, 4))
        .willReturn(
            List.of(
                text(105L, USER_ID),
                text(104L, COUNTERPART_ID),
                text(103L, USER_ID),
                text(102L, COUNTERPART_ID)));

    CursorResponse<MessageResponse> result = service.getMessages(USER_ID, ROOM_ID, null, null, 3);

    assertThat(result.content())
        .extracting(MessageResponse::messageId)
        .containsExactly(105L, 104L, 103L);
    assertThat(result.content())
        .extracting(MessageResponse::mine)
        .containsExactly(true, false, true);
    assertThat(result.nextCursor()).isEqualTo("103");
    assertThat(result.hasNext()).isTrue();
  }

  /** 위로 스크롤할 때는 cursor보다 작은 메시지만 요청하고 사용자 삭제 경계 이하를 제외한다. */
  @Test
  @DisplayName("과거 메시지에서 삭제한 범위를 제외한다")
  void hidesMessagesAtOrBeforePersonalBoundary() {
    prepareVisibleRoom(100L);
    given(messageRepository.findBefore(ROOM_ID, 104L, 4))
        .willReturn(
            List.of(text(103L, COUNTERPART_ID), text(100L, USER_ID), text(99L, COUNTERPART_ID)));

    CursorResponse<MessageResponse> result = service.getMessages(USER_ID, ROOM_ID, "104", null, 3);

    assertThat(result.content()).extracting(MessageResponse::messageId).containsExactly(103L);
    assertThat(result.nextCursor()).isNull();
    assertThat(result.hasNext()).isFalse();
  }

  /** 이미 삭제 경계에 도달한 cursor라면 DB를 더 조회하지 않고 빈 결과를 반환한다. */
  @Test
  @DisplayName("삭제 경계보다 오래된 페이지는 조회하지 않는다")
  void stopsAtPersonalBoundary() {
    prepareVisibleRoom(100L);

    CursorResponse<MessageResponse> result = service.getMessages(USER_ID, ROOM_ID, "100", null, 30);

    assertThat(result.content()).isEmpty();
    assertThat(result.hasNext()).isFalse();
    verify(messageRepository, never()).findBefore(ROOM_ID, 100L, 31);
  }

  /** 재연결 조회는 앱 checkpoint와 개인 삭제 경계 중 더 큰 ID 뒤에서 시작한다. */
  @Test
  @DisplayName("재연결 뒤 놓친 새 메시지를 오래된 순으로 조회한다")
  void getsMessagesAfterReconnectCheckpoint() {
    prepareVisibleRoom(100L);
    given(messageRepository.findAfter(ROOM_ID, 100L, 3))
        .willReturn(List.of(text(101L, COUNTERPART_ID), text(103L, USER_ID)));

    CursorResponse<MessageResponse> result = service.getMessages(USER_ID, ROOM_ID, null, "90", 2);

    assertThat(result.content()).extracting(MessageResponse::messageId).containsExactly(101L, 103L);
    assertThat(result.hasNext()).isFalse();
  }

  /** 서버 신청 카드는 TEXT 필드 없이 구조화된 bookingCard 응답으로 변환한다. */
  @Test
  @DisplayName("BOOKING_CARD 메시지를 신청 카드 응답으로 변환한다")
  void mapsBookingCard() {
    prepareVisibleRoom(0L);
    given(messageRepository.findBefore(ROOM_ID, null, 2)).willReturn(List.of(bookingCard(201L)));

    MessageResponse result =
        service.getMessages(USER_ID, ROOM_ID, null, null, 1).content().getFirst();

    assertThat(result.type()).isEqualTo(MessageType.BOOKING_CARD);
    assertThat(result.clientMessageId()).isNull();
    assertThat(result.senderId()).isNull();
    assertThat(result.originalContent()).isNull();
    assertThat(result.translation()).isNull();
    assertThat(result.bookingCard().bookingId()).isEqualTo(15L);
    assertThat(result.bookingCard().listing().thumbnailUrl())
        .isEqualTo("https://cdn.example.com/cover.jpg");
  }

  /** 받은 TEXT에는 저장된 최종 번역을 원문과 함께 반환하고 내가 보낸 TEXT에는 상대용 번역을 붙이지 않는다. */
  @Test
  @DisplayName("수신자용 번역 결과를 원문과 함께 반환한다")
  void mapsRecipientTranslationWithOriginal() {
    prepareVisibleRoom(0L);
    Message received = text(301L, COUNTERPART_ID);
    Message mine = text(302L, USER_ID);
    given(messageRepository.findBefore(ROOM_ID, null, 3)).willReturn(List.of(mine, received));
    given(translationRepository.findByMessageIdsAndRecipientUserId(List.of(302L, 301L), USER_ID))
        .willReturn(List.of(succeededTranslation(received.getId())));

    CursorResponse<MessageResponse> page = service.getMessages(USER_ID, ROOM_ID, null, null, 2);

    assertThat(page.content().get(0).translation()).isNull();
    MessageResponse translated = page.content().get(1);
    assertThat(translated.originalContent()).isEqualTo("message-301");
    assertThat(translated.translation().status()).isEqualTo(TranslationResultStatus.SUCCEEDED);
    assertThat(translated.translation().content()).isEqualTo("translated-301");
    assertThat(translated.translation().targetLanguage()).isEqualTo("ko");
  }

  /** 최종 번역 실패는 translation 자체를 숨기지 않고 FAILED 상태와 원문을 함께 반환한다. */
  @Test
  @DisplayName("번역 실패 메시지는 FAILED 상태와 원문을 반환한다")
  void mapsFailedTranslationWithOriginalFallback() {
    prepareVisibleRoom(0L);
    Message received = text(303L, COUNTERPART_ID);
    given(messageRepository.findBefore(ROOM_ID, null, 2)).willReturn(List.of(received));
    given(translationRepository.findByMessageIdsAndRecipientUserId(List.of(303L), USER_ID))
        .willReturn(List.of(failedTranslation(received.getId())));

    MessageResponse result =
        service.getMessages(USER_ID, ROOM_ID, null, null, 1).content().getFirst();

    assertThat(result.originalContent()).isEqualTo("message-303");
    assertThat(result.translation()).isNotNull();
    assertThat(result.translation().status()).isEqualTo(TranslationResultStatus.FAILED);
    assertThat(result.translation().content()).isNull();
  }

  /** cursor와 afterMessageId를 함께 보내면 어느 방향으로 정렬할지 모호하므로 400 입력 오류로 거부한다. */
  @Test
  @DisplayName("두 종류의 커서를 동시에 사용할 수 없다")
  void rejectsTwoCursorModesTogether() {
    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, "100", "101", 30))
        .isInstanceOf(InvalidInputException.class);

    verify(memberRepository, never()).findByChatRoomIdAndUserId(ROOM_ID, USER_ID);
  }

  /** 숫자가 아니거나 0 이하인 messageId와 1~100 밖의 size는 DB 조회 전에 거부한다. */
  @Test
  @DisplayName("잘못된 커서와 페이지 크기를 거부한다")
  void rejectsInvalidCursorAndSize() {
    prepareVisibleRoom(0L);

    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, "abc", null, 30))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, null, "0", 30))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, null, null, 101))
        .isInstanceOf(InvalidInputException.class);
  }

  /** 다른 사용자와 방을 삭제해 숨긴 사용자는 같은 404를 받아 방 존재 여부를 알 수 없다. */
  @Test
  @DisplayName("비참여자와 숨긴 방의 메시지를 조회할 수 없다")
  void rejectsOutsiderAndHiddenRoom() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.empty());
    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, null, null, 30))
        .isInstanceOf(ChatRoomNotFoundException.class);

    ChatRoomMember hidden = visibleMember(0L).toBuilder().roomHiddenAt(SENT_AT).build();
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(hidden));
    assertThatThrownBy(() -> service.getMessages(USER_ID, ROOM_ID, null, null, 30))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(messageRepository, never()).findBefore(ROOM_ID, null, 31);
  }

  /** 참여자와 공유 채팅방이 존재하는 공통 조회 조건을 준비한다. */
  private void prepareVisibleRoom(long hiddenThrough) {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(visibleMember(hiddenThrough)));
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room()));
  }

  /** 현재 사용자에게 보이는 참여자 상태 fixture다. */
  private static ChatRoomMember visibleMember(long hiddenThrough) {
    return ChatRoomMember.builder()
        .id(1L)
        .chatRoomId(ROOM_ID)
        .userId(USER_ID)
        .counterpartId(COUNTERPART_ID)
        .role(ChatParticipantRole.TENANT)
        .historyHiddenThroughMessageId(hiddenThrough)
        .createdAt(SENT_AT)
        .updatedAt(SENT_AT)
        .build();
  }

  /** 서비스가 room 행의 존재를 확인할 때 사용하는 최소 채팅방 fixture다. */
  private static ChatRoom room() {
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("6858e2000000000000000001")
        .tenantId(USER_ID)
        .landlordId(COUNTERPART_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio share", "Seogyo-dong, Mapo-gu"))
        .createdAt(SENT_AT)
        .updatedAt(SENT_AT)
        .build();
  }

  /** 지정한 서버 ID와 발신자를 가진 TEXT fixture다. */
  private static Message text(long messageId, long senderId) {
    return Message.builder()
        .id(messageId)
        .chatRoomId(ROOM_ID)
        .senderId(senderId)
        .type(MessageType.TEXT)
        .content("message-" + messageId)
        .clientMessageId(UUID.nameUUIDFromBytes(("client-" + messageId).getBytes()))
        .sentAt(SENT_AT.plusSeconds(messageId))
        .build();
  }

  /** 신청 시점의 화면 정보를 JSON payload로 가진 BOOKING_CARD fixture다. */
  private static Message bookingCard(long messageId) {
    BookingCardPayload payload =
        new BookingCardPayload(
            15L,
            new BookingCardPayload.Listing(
                "6858e2000000000000000001",
                "https://cdn.example.com/cover.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                420_000),
            new BookingCardPayload.Applicant(
                USER_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com"),
            "room-a",
            "Room A",
            LocalDate.parse("2026-09-01"),
            3,
            0,
            1_260_000);

    return Message.builder()
        .id(messageId)
        .chatRoomId(ROOM_ID)
        .type(MessageType.BOOKING_CARD)
        .payload(payload)
        .bookingId(payload.bookingId())
        .sentAt(SENT_AT)
        .build();
  }

  /** REST 이력에 붙일 완료 번역 fixture다. */
  private static ChatMessageTranslation succeededTranslation(long messageId) {
    return ChatMessageTranslation.builder()
        .id(901L)
        .messageId(messageId)
        .recipientUserId(USER_ID)
        .targetLanguage("ko")
        .detectedSourceLanguage("en")
        .status(ChatTranslationStatus.SUCCEEDED)
        .translatedContent("translated-" + messageId)
        .provider(TranslationProvider.GOOGLE_CLOUD_TRANSLATION)
        .model("NMT")
        .attemptCount(1)
        .translatedAt(SENT_AT)
        .createdAt(SENT_AT)
        .updatedAt(SENT_AT)
        .build();
  }

  /** 최종 실패 상태도 REST가 원문 fallback 판단에 사용할 수 있게 하는 fixture다. */
  private static ChatMessageTranslation failedTranslation(long messageId) {
    return ChatMessageTranslation.builder()
        .id(902L)
        .messageId(messageId)
        .recipientUserId(USER_ID)
        .targetLanguage("ko")
        .status(ChatTranslationStatus.FAILED)
        .provider(TranslationProvider.GOOGLE_CLOUD_TRANSLATION)
        .model("NMT")
        .attemptCount(5)
        .lastFailureCode("MAX_ATTEMPTS_EXHAUSTED")
        .translatedAt(SENT_AT)
        .createdAt(SENT_AT)
        .updatedAt(SENT_AT)
        .build();
  }
}
