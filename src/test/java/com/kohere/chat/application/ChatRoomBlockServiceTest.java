package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅방 차단이 실제 참여자 중 상대방을 찾아 기존 user 모듈에 위임하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomBlockServiceTest {

  private static final long ROOM_ID = 10L;
  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;

  @Mock private AppUserGuard appUserGuard;

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private UserBlockService userBlockService;

  private ChatRoomBlockService service;

  @BeforeEach
  void setUp() {
    service =
        new ChatRoomBlockService(
            chatRoomRepository, appUserGuard, memberRepository, userBlockService);
  }

  /** 임차인이 요청하면 같은 방의 임대인을 차단 대상으로 선택한다. */
  @Test
  @DisplayName("임차인은 채팅방의 임대인을 차단한다")
  void tenantBlocksLandlord() {
    prepareRoom();

    service.blockCounterpart(TENANT_ID, ROOM_ID);

    verify(userBlockService).block(TENANT_ID, LANDLORD_ID);
    verifyLockOrder();
  }

  /** 임대인이 요청하면 같은 방의 임차인을 차단 대상으로 선택한다. */
  @Test
  @DisplayName("임대인은 채팅방의 임차인을 차단한다")
  void landlordBlocksTenant() {
    prepareRoom();

    service.blockCounterpart(LANDLORD_ID, ROOM_ID);

    verify(userBlockService).block(LANDLORD_ID, TENANT_ID);
  }

  /** roomId를 추측한 제3자는 참여자가 아니므로 어느 사용자도 차단하지 못한다. */
  @Test
  @DisplayName("비참여자의 차단 요청은 404 대상이다")
  void outsiderCannotBlockRoomParticipant() {
    prepareRoom();

    assertThatThrownBy(() -> service.blockCounterpart(999L, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(userBlockService, never())
        .block(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
  }

  /** 참여자 행이 둘이 아니면 임의 상대를 선택하지 않고 손상된 서버 상태를 드러낸다. */
  @Test
  @DisplayName("참여자 두 행 불변식이 깨지면 차단하지 않는다")
  void brokenMemberInvariantDoesNotBlockAnyone() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID)).willReturn(List.of(tenantMember()));

    assertThatThrownBy(() -> service.blockCounterpart(TENANT_ID, ROOM_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");

    verify(userBlockService, never())
        .block(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
  }

  /** 방이 없으면 참여자 조회와 차단 저장을 시작하지 않는다. */
  @Test
  @DisplayName("존재하지 않는 채팅방은 404 대상이다")
  void missingRoomDoesNotLoadMembersOrBlock() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.blockCounterpart(TENANT_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(memberRepository, never()).findByChatRoomIdForUpdate(ROOM_ID);
    verify(userBlockService, never())
        .block(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
  }

  /** 정상 테스트가 공통으로 사용하는 방과 정확히 두 참여자를 준비한다. */
  private void prepareRoom() {
    given(chatRoomRepository.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room()));
    given(memberRepository.findByChatRoomIdForUpdate(ROOM_ID))
        .willReturn(List.of(tenantMember(), landlordMember()));
  }

  /** 같은 방을 바꾸는 삭제·재진입·TEXT와 동일하게 room 다음 member 순서로 잠그는지 확인한다. */
  private void verifyLockOrder() {
    InOrder lockOrder = inOrder(chatRoomRepository, memberRepository, userBlockService);
    lockOrder.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
    lockOrder.verify(memberRepository).findByChatRoomIdForUpdate(ROOM_ID);
    lockOrder.verify(userBlockService).block(TENANT_ID, LANDLORD_ID);
  }

  private static ChatRoom room() {
    Instant now = Instant.parse("2026-08-21T10:15:30Z");
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("listing-1")
        .tenantId(TENANT_ID)
        .landlordId(LANDLORD_ID)
        .category(ChatCategory.LANDLORD)
        .createdAt(now)
        .updatedAt(now)
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
    Instant now = Instant.parse("2026-08-21T10:15:30Z");
    return ChatRoomMember.builder()
        .id(id)
        .chatRoomId(ROOM_ID)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
