package com.kohere.chat.application;

import com.kohere.chat.application.dto.ChatCounterpartResponse;
import com.kohere.chat.application.dto.ChatListingSummaryResponse;
import com.kohere.chat.application.dto.ChatRoomDetailResponse;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 화면을 열 때 필요한 기본 정보를 한 건 조회하는 읽기 전용 서비스다.
 *
 * <p>앱은 채팅방 목록을 거치지 않고 알림이나 딥링크의 {@code roomId}만으로 화면을 열 수도 있다. 이 서비스는 그때 필요한 매물 제목·주소, 상대 이름, 로그인
 * 사용자의 역할과 차단 여부를 다시 확인해 반환한다. 실제 대화 내용은 메시지 이력 API가 별도로 담당한다.
 *
 * <p>채팅방이 실제로 존재하더라도 요청자가 참여자가 아니거나 본인이 삭제해 숨긴 상태라면 동일한 {@code 404 CHAT_ROOM_NOT_FOUND}를 반환한다. 제3자가
 * roomId를 바꿔 보며 채팅방 존재 여부를 알아내지 못하게 하기 위한 규칙이다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomDetailService {

  private final ChatRoomRepository chatRoomRepository;
  private final AppUserGuard appUserGuard;
  private final ChatRoomMemberRepository memberRepository;
  private final UserAccountService userAccountService;
  private final UserBlockService userBlockService;

  /**
   * 로그인 사용자가 볼 수 있는 채팅방 한 건의 헤더 정보를 반환한다.
   *
   * @param userId JWT에서 확인한 로그인 사용자의 {@code users.id}
   * @param roomId 앱이 목록·알림·딥링크에서 받은 서버 채팅방 ID
   * @return 앱이 채팅방 헤더와 역할별 UI를 그리는 데 필요한 정보
   * @throws ChatRoomNotFoundException 방이 없거나, 요청자가 참여자가 아니거나, 요청자에게 숨겨진 방인 경우
   */
  @Transactional(readOnly = true)
  public ChatRoomDetailResponse getRoom(long userId, long roomId) {
    appUserGuard.requireAppUser(userId);
    // member 행부터 확인하면 제3자의 요청은 공유 채팅방 정보 자체를 읽기 전에 중단된다.
    ChatRoomMember member = visibleMember(roomId, userId);
    ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(ChatRoomNotFoundException::new);

    long counterpartId = member.getCounterpartId();
    String counterpartName = userAccountService.getUserName(counterpartId);
    boolean blocked = userBlockService.isBlockedBetween(userId, counterpartId);

    // 매물 원본을 다시 조회하지 않고 방 생성 시 저장한 snapshot을 사용한다. 매물이 비공개·삭제돼도 대화 맥락이 유지된다.
    ChatListingSummaryResponse listing =
        new ChatListingSummaryResponse(
            room.getListingId(),
            room.getListingSnapshot().title(),
            room.getListingSnapshot().address());

    return new ChatRoomDetailResponse(
        room.getId(),
        member.getRole(),
        listing,
        new ChatCounterpartResponse(counterpartId, counterpartName),
        blocked);
  }

  /** 참여 중이고 현재 사용자 화면에서 숨기지 않은 member 행만 반환한다. */
  private ChatRoomMember visibleMember(long roomId, long userId) {
    ChatRoomMember member =
        memberRepository
            .findByChatRoomIdAndUserId(roomId, userId)
            .orElseThrow(ChatRoomNotFoundException::new);

    // roomHiddenAt이 있으면 사용자가 삭제한 방이다. 일반 사용자는 roomId를 기억해도 직접 다시 열 수 없다.
    if (member.getRoomHiddenAt() != null) {
      throw new ChatRoomNotFoundException();
    }
    return member;
  }
}
