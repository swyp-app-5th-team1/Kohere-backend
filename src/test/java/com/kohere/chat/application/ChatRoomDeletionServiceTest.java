package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅방 삭제가 요청자 한 명에게만 적용되고 잠금 순서를 지키는지 빠르게 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomDeletionServiceTest {

  private static final long ROOM_ID = 10L;
  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;
  private static final long LAST_MESSAGE_ID = 501L;

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;

  private ChatRoomDeletionService service;

  @BeforeEach
  void setUp() {
    service = new ChatRoomDeletionService(chatRoomRepository, memberRepository);
  }

  /** 요청자 행만 숨기고 방의 현재 마지막 메시지를 개인 이력 경계로 저장한다. */
  @Test
  @DisplayName("채팅방 삭제는 요청자에게만 적용한다")
  void hidesOnlyRequesterThroughCurrentLastMessage() {
    ChatRoomMember tenant = tenantMember();
    ChatRoomMember landlord = landlordMember();
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(tenant, landlord));

    service.deleteRoom(TENANT_ID, ROOM_ID);

    ArgumentCaptor<ChatRoomMember> captor = ArgumentCaptor.forClass(ChatRoomMember.class);
    verify(memberRepository).save(captor.capture());
    ChatRoomMember hidden = captor.getValue();

    assertThat(hidden.getUserId()).isEqualTo(TENANT_ID);
    assertThat(hidden.getRoomHiddenAt()).isNotNull();
    assertThat(hidden.getDeleteRequestedAt()).isEqualTo(hidden.getRoomHiddenAt());
    assertThat(hidden.getHistoryHiddenThroughMessageId()).isEqualTo(LAST_MESSAGE_ID);
    // 서비스는 상대방 객체를 수정하거나 저장하지 않는다.
    assertThat(landlord.getRoomHiddenAt()).isNull();
    assertThat(landlord.getHistoryHiddenThroughMessageId()).isZero();

    // 모든 쓰기 흐름이 room -> 두 member 순서로 잠가야 TEXT·문의 재진입과 교착 없이 직렬화된다.
    InOrder lockOrder = inOrder(chatRoomRepository, memberRepository);
    lockOrder.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
    lockOrder.verify(memberRepository).findByChatRoomIdForUpdate(ROOM_ID);
  }

  /** 이미 숨긴 방을 다시 삭제해도 최초 삭제 시각과 경계를 연장하지 않는다. */
  @Test
  @DisplayName("같은 숨김 상태의 삭제 재시도는 멱등이다")
  void repeatedDeleteDoesNotChangeExistingState() {
    Instant firstDeletedAt = Instant.parse("2026-08-20T10:15:30Z");
    ChatRoomMember hiddenTenant =
        tenantMember().toBuilder()
            .roomHiddenAt(firstDeletedAt)
            .historyHiddenThroughMessageId(400L)
            .deleteRequestedAt(firstDeletedAt)
            .updatedAt(firstDeletedAt)
            .build();
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(hiddenTenant, landlordMember()));

    service.deleteRoom(TENANT_ID, ROOM_ID);

    // 204 재시도는 성공하지만 새 UPDATE가 없으므로 삭제 보존 기준이 무한히 연장되지 않는다.
    verify(memberRepository, never()).save(any(ChatRoomMember.class));
  }

  /** roomId를 추측한 제3자는 참여자가 아니므로 상대방 상태를 바꾸지 못한다. */
  @Test
  @DisplayName("비참여자의 삭제 요청은 404 대상이다")
  void rejectsOutsiderWithoutSavingMember() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(tenantMember(), landlordMember()));

    assertThatThrownBy(() -> service.deleteRoom(999L, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(memberRepository, never()).save(any(ChatRoomMember.class));
  }

  private static ChatRoom room() {
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("listing-1")
        .tenantId(TENANT_ID)
        .landlordId(LANDLORD_ID)
        .category(ChatCategory.LANDLORD)
        .lastMessageId(LAST_MESSAGE_ID)
        .lastMessageAt(Instant.parse("2026-08-21T10:15:30Z"))
        .createdAt(Instant.parse("2026-08-19T10:15:30Z"))
        .updatedAt(Instant.parse("2026-08-21T10:15:30Z"))
        .build();
  }

  private static ChatRoomMember tenantMember() {
    return member(1L, TENANT_ID, LANDLORD_ID, ChatParticipantRole.TENANT);
  }

  private static ChatRoomMember landlordMember() {
    return member(2L, LANDLORD_ID, TENANT_ID, ChatParticipantRole.LANDLORD);
  }

  private static ChatRoomMember member(
      long id, long userId, long counterpartId, ChatParticipantRole role) {
    Instant createdAt = Instant.parse("2026-08-19T10:15:30Z");
    return ChatRoomMember.builder()
        .id(id)
        .chatRoomId(ROOM_ID)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(createdAt)
        .updatedAt(createdAt)
        .build();
  }
}
