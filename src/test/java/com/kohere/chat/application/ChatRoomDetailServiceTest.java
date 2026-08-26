package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.booking.api.BookingEligibilityQueryService;
import com.kohere.chat.application.dto.ChatRoomDetailResponse;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅방 단건 조회의 참여자 권한·숨김 처리와 응답 조립을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomDetailServiceTest {

  private static final long USER_ID = 7L;
  private static final long COUNTERPART_ID = 42L;
  private static final long ROOM_ID = 556L;
  private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

  @Mock private AppUserGuard appUserGuard;

  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private ChatRoomMemberRepository memberRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private UserBlockService userBlockService;
  @Mock private BookingEligibilityQueryService bookingEligibilityQueryService;

  private ChatRoomDetailService service;

  /** 실제 서비스에 mock 공개 포트와 저장소를 연결한다. */
  @BeforeEach
  void setUp() {
    service =
        new ChatRoomDetailService(
            chatRoomRepository,
            appUserGuard,
            memberRepository,
            userAccountService,
            userBlockService,
            bookingEligibilityQueryService);
  }

  /** 참여자에게는 방 생성 당시 매물 정보, 상대 이름, 내 역할, 차단 여부와 신청 가능 여부를 반환한다. */
  @Test
  @DisplayName("참여자는 채팅방 기본 정보를 조회한다")
  void participantGetsRoomDetail() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(visibleMember()));
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room()));
    given(userAccountService.getUserName(COUNTERPART_ID)).willReturn("Hongdae landlord");
    given(userBlockService.isBlockedBetween(USER_ID, COUNTERPART_ID)).willReturn(false);
    given(
            bookingEligibilityQueryService.canApply(
                USER_ID, "6858e2000000000000000001", COUNTERPART_ID))
        .willReturn(true);

    ChatRoomDetailResponse result = service.getRoom(USER_ID, ROOM_ID);

    assertThat(result.chatRoomId()).isEqualTo(ROOM_ID);
    assertThat(result.myRole()).isEqualTo(ChatParticipantRole.TENANT);
    assertThat(result.listing().title()).isEqualTo("Hongdae Studio share");
    assertThat(result.listing().address()).isEqualTo("Seogyo-dong, Mapo-gu");
    assertThat(result.counterpart().userId()).isEqualTo(COUNTERPART_ID);
    assertThat(result.counterpart().displayName()).isEqualTo("Hongdae landlord");
    assertThat(result.blocked()).isFalse();
    assertThat(result.canApply()).isTrue();
  }

  /** 차단 상태에서는 실제 신청도 거부되므로 booking 조회 없이 배너를 숨긴다. */
  @Test
  @DisplayName("차단 관계인 임차인은 신청 배너를 볼 수 없다")
  void blockedTenantCannotApply() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(visibleMember()));
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room()));
    given(userAccountService.getUserName(COUNTERPART_ID)).willReturn("Hongdae landlord");
    given(userBlockService.isBlockedBetween(USER_ID, COUNTERPART_ID)).willReturn(true);

    ChatRoomDetailResponse result = service.getRoom(USER_ID, ROOM_ID);

    assertThat(result.blocked()).isTrue();
    assertThat(result.canApply()).isFalse();
    verify(bookingEligibilityQueryService, never())
        .canApply(USER_ID, "6858e2000000000000000001", COUNTERPART_ID);
  }

  /** 임대인은 입주 신청 주체가 아니므로 신청 이력을 조회하지 않고 항상 배너를 숨긴다. */
  @Test
  @DisplayName("임대인에게는 신청 배너를 표시하지 않는다")
  void landlordCannotApply() {
    ChatRoomMember landlordMember =
        visibleMember().toBuilder().role(ChatParticipantRole.LANDLORD).build();
    ChatRoom landlordRoom = room().toBuilder().tenantId(COUNTERPART_ID).landlordId(USER_ID).build();
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(landlordMember));
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(landlordRoom));
    given(userAccountService.getUserName(COUNTERPART_ID)).willReturn("Hongdae tenant");
    given(userBlockService.isBlockedBetween(USER_ID, COUNTERPART_ID)).willReturn(false);

    ChatRoomDetailResponse result = service.getRoom(USER_ID, ROOM_ID);

    assertThat(result.myRole()).isEqualTo(ChatParticipantRole.LANDLORD);
    assertThat(result.canApply()).isFalse();
    verify(bookingEligibilityQueryService, never())
        .canApply(USER_ID, "6858e2000000000000000001", USER_ID);
  }

  /** 참여자 행이 없는 사용자는 방의 실제 존재 여부와 관계없이 동일한 404를 받는다. */
  @Test
  @DisplayName("비참여자는 채팅방 존재 정보를 조회할 수 없다")
  void outsiderGetsNotFoundWithoutLoadingSharedRoom() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRoom(USER_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    // 권한이 없는 요청에서는 공유 room 행과 상대 사용자 정보를 읽지 않는다.
    verify(chatRoomRepository, never()).findById(ROOM_ID);
    verify(userAccountService, never()).getUserName(COUNTERPART_ID);
  }

  /** 사용자가 삭제해 숨긴 방은 일반 단건 조회로 다시 열 수 없고 상대방 상태에는 영향을 주지 않는다. */
  @Test
  @DisplayName("사용자에게 숨겨진 채팅방은 404로 처리한다")
  void hiddenRoomGetsNotFound() {
    ChatRoomMember hiddenMember =
        visibleMember().toBuilder().roomHiddenAt(NOW.plusSeconds(1)).build();
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(hiddenMember));

    assertThatThrownBy(() -> service.getRoom(USER_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);

    verify(chatRoomRepository, never()).findById(ROOM_ID);
  }

  /** member는 남아 있지만 no-FK 공유 방 행이 유실된 경우도 외부에는 안전한 404만 반환한다. */
  @Test
  @DisplayName("채팅방 행이 없으면 404로 처리한다")
  void missingRoomGetsNotFound() {
    given(memberRepository.findByChatRoomIdAndUserId(ROOM_ID, USER_ID))
        .willReturn(Optional.of(visibleMember()));
    given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRoom(USER_ID, ROOM_ID))
        .isInstanceOf(ChatRoomNotFoundException.class);
  }

  /** 현재 사용자가 보는 member fixture다. */
  private static ChatRoomMember visibleMember() {
    return ChatRoomMember.builder()
        .id(1L)
        .chatRoomId(ROOM_ID)
        .userId(USER_ID)
        .counterpartId(COUNTERPART_ID)
        .role(ChatParticipantRole.TENANT)
        .historyHiddenThroughMessageId(0L)
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }

  /** 방 생성 당시 매물 snapshot을 가진 공유 채팅방 fixture다. */
  private static ChatRoom room() {
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("6858e2000000000000000001")
        .tenantId(USER_ID)
        .landlordId(COUNTERPART_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio share", "Seogyo-dong, Mapo-gu"))
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }
}
