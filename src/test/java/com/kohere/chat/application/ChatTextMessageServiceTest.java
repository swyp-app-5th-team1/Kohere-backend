package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatClientMessageConflictException;
import com.kohere.chat.domain.ChatMessageTooLongException;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** TEXT 저장의 멱등성·권한·수신자 재표시 규칙을 broker 없이 빠르게 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatTextMessageServiceTest {

  private static final long ROOM_ID = 10L;
  private static final long SENDER_ID = 42L;
  private static final long RECIPIENT_ID = 77L;
  private static final UUID CLIENT_MESSAGE_ID =
      UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849");

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private UserBlockService userBlockService;

  private ChatTextMessageService service;

  @BeforeEach
  void setUp() {
    service =
        new ChatTextMessageService(
            chatRoomRepository, memberRepository, messageRepository, userBlockService);
  }

  /** 신규 원문은 한 번 저장하고 저장된 ID로 방 마지막 메시지 포인터를 이동한다. */
  @Test
  @DisplayName("신규 TEXT를 저장하고 마지막 메시지를 갱신한다")
  void savesNewTextAndUpdatesRoomPointer() {
    prepareVisibleConversation();
    given(messageRepository.save(any(Message.class)))
        .willAnswer(invocation -> withId(invocation.getArgument(0), 501L));

    TextMessageSaveResult result = service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "안녕하세요");

    assertThat(result.duplicate()).isFalse();
    assertThat(result.message().getId()).isEqualTo(501L);
    assertThat(result.message().getContent()).isEqualTo("안녕하세요");
    assertThat(result.recipientUserId()).isEqualTo(RECIPIENT_ID);
    assertThat(result.recipientRoomReopened()).isFalse();

    ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
    verify(chatRoomRepository).save(roomCaptor.capture());
    assertThat(roomCaptor.getValue().getLastMessageId()).isEqualTo(501L);
    assertThat(roomCaptor.getValue().getLastMessageAt()).isEqualTo(result.message().getSentAt());
    verify(memberRepository, never()).save(any(ChatRoomMember.class));
  }

  /** 숨긴 수신자는 방만 다시 보이게 하고 삭제 전에 숨긴 messageId 경계와 삭제 시각은 그대로 유지한다. */
  @Test
  @DisplayName("새 TEXT가 오면 수신자 방만 재표시하고 과거 숨김 경계는 유지한다")
  void reopensRecipientRoomWithoutRestoringHiddenHistory() {
    Instant deletedAt = Instant.parse("2026-08-20T01:00:00Z");
    ChatRoomMember hiddenRecipient =
        recipientMember().toBuilder()
            .roomHiddenAt(deletedAt)
            .historyHiddenThroughMessageId(400L)
            .deleteRequestedAt(deletedAt)
            .build();
    prepareConversation(senderMember(), hiddenRecipient);
    given(messageRepository.save(any(Message.class)))
        .willAnswer(invocation -> withId(invocation.getArgument(0), 501L));

    TextMessageSaveResult result =
        service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "새 메시지입니다");

    assertThat(result.recipientRoomReopened()).isTrue();
    ArgumentCaptor<ChatRoomMember> memberCaptor = ArgumentCaptor.forClass(ChatRoomMember.class);
    verify(memberRepository).save(memberCaptor.capture());
    ChatRoomMember reopened = memberCaptor.getValue();
    assertThat(reopened.getRoomHiddenAt()).isNull();
    assertThat(reopened.getHistoryHiddenThroughMessageId()).isEqualTo(400L);
    assertThat(reopened.getDeleteRequestedAt()).isEqualTo(deletedAt);
  }

  /** 같은 UUID·같은 본문 재전송은 기존 결과만 반환하고 어떠한 쓰기도 다시 만들지 않는다. */
  @Test
  @DisplayName("같은 clientMessageId 재전송은 기존 메시지를 중복 결과로 반환한다")
  void returnsExistingMessageForSameRetry() {
    ChatRoomMember hiddenRecipient =
        recipientMember().toBuilder()
            .roomHiddenAt(Instant.parse("2026-08-20T01:00:00Z"))
            .historyHiddenThroughMessageId(400L)
            .build();
    prepareConversation(senderMember(), hiddenRecipient);
    Message existing = textMessage(501L, "안녕하세요");
    given(
            messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID))
        .willReturn(Optional.of(existing));

    TextMessageSaveResult result = service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "안녕하세요");

    assertThat(result.message()).isSameAs(existing);
    assertThat(result.duplicate()).isTrue();
    assertThat(result.recipientRoomReopened()).isFalse();
    verify(messageRepository, never()).save(any(Message.class));
    verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    verify(memberRepository, never()).save(any(ChatRoomMember.class));
  }

  /** UUID는 같은데 본문이 바뀌면 기존 메시지 의미가 달라지므로 명시적인 충돌로 거부한다. */
  @Test
  @DisplayName("같은 clientMessageId에 다른 본문을 보내면 충돌한다")
  void rejectsSameIdWithDifferentContent() {
    prepareVisibleConversation();
    given(
            messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID))
        .willReturn(Optional.of(textMessage(501L, "첫 본문")));

    assertThatThrownBy(() -> service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "바뀐 본문"))
        .isInstanceOf(ChatClientMessageConflictException.class);

    verify(messageRepository, never()).save(any(Message.class));
    verify(chatRoomRepository, never()).save(any(ChatRoom.class));
  }

  /** 어느 방향이든 차단 관계면 원문 INSERT 이전에 중단한다. */
  @Test
  @DisplayName("차단 관계에서는 TEXT를 저장하지 않는다")
  void rejectsBlockedConversation() {
    prepareVisibleConversation();
    given(userBlockService.isBlockedBetween(SENDER_ID, RECIPIENT_ID)).willReturn(true);

    assertThatThrownBy(() -> service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "안녕하세요"))
        .isInstanceOf(ChatUnavailableException.class);

    verify(messageRepository, never()).save(any(Message.class));
  }

  /** 숨긴 방을 기억한 오래된 화면이 직접 SEND해도 먼저 문의 재진입을 거치지 않았으므로 거부한다. */
  @Test
  @DisplayName("발신자가 숨긴 채팅방에는 직접 TEXT를 보낼 수 없다")
  void rejectsSendFromHiddenRoom() {
    ChatRoomMember hiddenSender =
        senderMember().toBuilder().roomHiddenAt(Instant.parse("2026-08-20T01:00:00Z")).build();
    prepareConversation(hiddenSender, recipientMember());

    assertThatThrownBy(() -> service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, "안녕하세요"))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verifyNoInteractions(messageRepository);
  }

  /** 사용자 입력 제한은 UTF-16 배열 길이가 아니라 이모지를 한 글자로 세는 Unicode code point 기준이다. */
  @Test
  @DisplayName("3,001 code point TEXT는 저장 전에 거부한다")
  void rejectsTextOverThreeThousandCodePoints() {
    String tooLong = "가".repeat(3_000) + "🙂";

    assertThatThrownBy(() -> service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, tooLong))
        .isInstanceOf(ChatMessageTooLongException.class);

    verifyNoInteractions(chatRoomRepository, memberRepository, messageRepository);
  }

  /** 빈 문자열·공백·줄바꿈만 있는 요청은 대화 내용이 아니므로 INVALID_INPUT으로 거부한다. */
  @Test
  @DisplayName("공백뿐인 TEXT는 저장 전에 거부한다")
  void rejectsBlankText() {
    assertThatThrownBy(() -> service.saveText(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, " \n\t"))
        .isInstanceOf(InvalidInputException.class);

    verifyNoInteractions(chatRoomRepository, memberRepository, messageRepository);
  }

  /** 방 잠금·두 참여자·차단 없음이라는 정상 대화 공통 조건을 준비한다. */
  private void prepareVisibleConversation() {
    prepareConversation(senderMember(), recipientMember());
  }

  private void prepareConversation(ChatRoomMember sender, ChatRoomMember recipient) {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomId(ROOM_ID)).willReturn(List.of(sender, recipient));
  }

  private static ChatRoom room() {
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("listing-1")
        .tenantId(SENDER_ID)
        .landlordId(RECIPIENT_ID)
        .category(ChatCategory.LANDLORD)
        .createdAt(Instant.parse("2026-08-19T00:00:00Z"))
        .updatedAt(Instant.parse("2026-08-19T00:00:00Z"))
        .build();
  }

  private static ChatRoomMember senderMember() {
    return ChatRoomMember.builder()
        .id(1L)
        .chatRoomId(ROOM_ID)
        .userId(SENDER_ID)
        .counterpartId(RECIPIENT_ID)
        .role(ChatParticipantRole.TENANT)
        .historyHiddenThroughMessageId(0L)
        .createdAt(Instant.parse("2026-08-19T00:00:00Z"))
        .updatedAt(Instant.parse("2026-08-19T00:00:00Z"))
        .build();
  }

  private static ChatRoomMember recipientMember() {
    return ChatRoomMember.builder()
        .id(2L)
        .chatRoomId(ROOM_ID)
        .userId(RECIPIENT_ID)
        .counterpartId(SENDER_ID)
        .role(ChatParticipantRole.LANDLORD)
        .historyHiddenThroughMessageId(0L)
        .createdAt(Instant.parse("2026-08-19T00:00:00Z"))
        .updatedAt(Instant.parse("2026-08-19T00:00:00Z"))
        .build();
  }

  private static Message textMessage(long messageId, String content) {
    return Message.builder()
        .id(messageId)
        .chatRoomId(ROOM_ID)
        .senderId(SENDER_ID)
        .type(MessageType.TEXT)
        .content(content)
        .clientMessageId(CLIENT_MESSAGE_ID)
        .sentAt(Instant.parse("2026-08-21T10:15:30Z"))
        .build();
  }

  /** 저장소 mock이 DB IDENTITY로 ID를 채운 결과와 같은 새 불변 메시지를 돌려준다. */
  private static Message withId(Message source, long messageId) {
    return Message.builder()
        .id(messageId)
        .chatRoomId(source.getChatRoomId())
        .senderId(source.getSenderId())
        .type(source.getType())
        .content(source.getContent())
        .clientMessageId(source.getClientMessageId())
        .sentAt(source.getSentAt())
        .build();
  }
}
