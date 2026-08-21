package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.chat.api.ChatReportEvidenceSnapshot;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 신고 증거 공개 API가 참여자·숨김 경계·TEXT 원문 규칙을 지키는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatReportEvidenceQueryServiceTest {

  private static final long ROOM_ID = 10L;
  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private MessageRepository messageRepository;

  private ChatReportEvidenceQueryService service;

  @BeforeEach
  void setUp() {
    service =
        new ChatReportEvidenceQueryService(chatRoomRepository, memberRepository, messageRepository);
  }

  /** DB 최신순 원문을 관리자에게 읽기 쉬운 시간순으로 바꾸고 상대방을 서버가 정하는지 확인한다. */
  @Test
  @DisplayName("최근 TEXT 원문과 채팅방 상대를 신고 증거로 반환한다")
  void capturesVisibleTextMessagesAndCounterpart() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(member(1L, TENANT_ID, LANDLORD_ID, 100L, null), landlordMember()));
    given(messageRepository.findRecentTextForReport(ROOM_ID, 100L, 20))
        .willReturn(List.of(text(102L, LANDLORD_ID, "newer"), text(101L, TENANT_ID, "older")));

    ChatReportEvidenceSnapshot result = service.capture(TENANT_ID, ROOM_ID);

    assertThat(result.chatRoomId()).isEqualTo(ROOM_ID);
    assertThat(result.reporterId()).isEqualTo(TENANT_ID);
    assertThat(result.reportedUserId()).isEqualTo(LANDLORD_ID);
    assertThat(result.evidenceThroughMessageId()).isEqualTo(102L);
    assertThat(result.messages())
        .extracting(message -> message.messageId())
        .containsExactly(101L, 102L);
    verify(messageRepository).findRecentTextForReport(ROOM_ID, 100L, 20);
  }

  /** 삭제해 현재 숨긴 방은 roomId를 알고 있어도 신고 증거를 읽을 수 없다. */
  @Test
  @DisplayName("현재 숨긴 채팅방은 신고할 수 없다")
  void hiddenRoomIsNotReportable() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(
            List.of(
                member(1L, TENANT_ID, LANDLORD_ID, 100L, Instant.parse("2026-08-22T10:00:00Z")),
                landlordMember()));

    assertThatThrownBy(() -> service.capture(TENANT_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(messageRepository, never())
        .findRecentTextForReport(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  /** roomId를 추측한 제3자는 상대방이나 원문을 얻지 못한다. */
  @Test
  @DisplayName("비참여자는 채팅방 신고 증거를 조회할 수 없다")
  void outsiderCannotCaptureEvidence() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(tenantMember(), landlordMember()));

    assertThatThrownBy(() -> service.capture(999L, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);
  }

  /** 방이 없으면 참여자나 메시지 조회를 시작하지 않는다. */
  @Test
  @DisplayName("존재하지 않는 채팅방 신고는 404 대상이다")
  void missingRoomStopsBeforeLoadingMembers() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.capture(TENANT_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(memberRepository, never()).findByChatRoomIdForUpdate(ROOM_ID);
  }

  private static ChatRoom room() {
    Instant now = Instant.parse("2026-08-22T09:00:00Z");
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("listing-1")
        .tenantId(TENANT_ID)
        .landlordId(LANDLORD_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio", "Mapo-gu"))
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static ChatRoomMember tenantMember() {
    return member(1L, TENANT_ID, LANDLORD_ID, 0L, null);
  }

  private static ChatRoomMember landlordMember() {
    return member(2L, LANDLORD_ID, TENANT_ID, 0L, null);
  }

  private static ChatRoomMember member(
      long id, long userId, long counterpartId, long hiddenThrough, Instant roomHiddenAt) {
    Instant now = Instant.parse("2026-08-22T09:00:00Z");
    return ChatRoomMember.builder()
        .id(id)
        .chatRoomId(ROOM_ID)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(userId == TENANT_ID ? ChatParticipantRole.TENANT : ChatParticipantRole.LANDLORD)
        .roomHiddenAt(roomHiddenAt)
        .historyHiddenThroughMessageId(hiddenThrough)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static Message text(long id, long senderId, String content) {
    return Message.builder()
        .id(id)
        .chatRoomId(ROOM_ID)
        .senderId(senderId)
        .type(MessageType.TEXT)
        .content(content)
        .clientMessageId(UUID.randomUUID())
        .sentAt(Instant.parse("2026-08-22T09:30:00Z").plusSeconds(id))
        .build();
  }
}
