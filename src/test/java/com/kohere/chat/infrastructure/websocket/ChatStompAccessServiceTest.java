package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** roomId를 알아도 비참여자나 숨긴 사용자가 실시간 구독하지 못하는지 검증한다. */
class ChatStompAccessServiceTest {

  private static final long USER_ID = 42L;
  private static final long ROOM_ID = 556L;

  private final ChatRoomRepository roomRepository = mock(ChatRoomRepository.class);
  private final ChatRoomMemberRepository memberRepository = mock(ChatRoomMemberRepository.class);

  private ChatStompAccessService service;

  @BeforeEach
  void setUp() {
    service = new ChatStompAccessService(roomRepository, memberRepository);
  }

  @Test
  @DisplayName("참여자에게 보이는 마지막 메시지 번호를 high-watermark로 반환한다")
  void returnsVisibleHighWatermark() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(member(null, 100L)));
    given(roomRepository.findById(ROOM_ID))
        .willReturn(Optional.of(ChatRoom.builder().id(ROOM_ID).lastMessageId(120L).build()));

    assertThat(service.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID)).isEqualTo(120L);
  }

  @Test
  @DisplayName("방은 보여도 마지막 메시지가 사용자 숨김 경계 안이면 null을 반환한다")
  void hidesHighWatermarkInsidePersonalHistoryBoundary() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(member(null, 120L)));
    given(roomRepository.findById(ROOM_ID))
        .willReturn(Optional.of(ChatRoom.builder().id(ROOM_ID).lastMessageId(120L).build()));

    assertThat(service.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID)).isNull();
  }

  @Test
  @DisplayName("비참여자와 사용자가 숨긴 방은 같은 권한 오류로 거부한다")
  void deniesOutsiderAndHiddenRoomWithoutRevealingReason() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.empty());
    assertThatThrownBy(() -> service.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID))
        .isInstanceOf(AccessDeniedException.class);

    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(member(Instant.parse("2026-08-21T00:00:00Z"), 100L)));
    assertThatThrownBy(() -> service.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static ChatRoomMember member(Instant roomHiddenAt, long hiddenThrough) {
    return ChatRoomMember.builder()
        .chatRoomId(ROOM_ID)
        .userId(USER_ID)
        .roomHiddenAt(roomHiddenAt)
        .historyHiddenThroughMessageId(hiddenThrough)
        .build();
  }
}
