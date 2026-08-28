package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import java.util.List;

/**
 * 문의서 저장 결과와 참여자별 채팅방 목록 상태를 실시간 발행 단계에 전달한다.
 *
 * <p>신규 방과 기존 방 모두 같은 실시간 발행기를 사용하도록 결과 모양을 통일한다. REST 응답에는 노출되지 않는 서버 내부 객체다.
 *
 * @param room 문의서 저장 뒤 마지막 메시지 포인터가 갱신된 채팅방
 * @param message 새로 저장된 INQUIRY_CARD
 * @param roomCreated 이번 문의로 채팅방 자체가 새로 생성됐는지 여부
 * @param memberActivities 문의서 저장 뒤 각 참여자의 채팅방 목록 표시 상태
 */
public record InquiryCardProcessResult(
    ChatRoom room, Message message, boolean roomCreated, List<MemberActivity> memberActivities) {

  /** 잘못된 메시지 타입과 변경 가능한 참여자 목록이 트랜잭션 밖으로 전달되지 않게 확인한다. */
  public InquiryCardProcessResult {
    if (message.getType() != MessageType.INQUIRY_CARD) {
      throw new IllegalArgumentException("문의서 처리 결과에는 INQUIRY_CARD만 담을 수 있습니다.");
    }
    memberActivities = List.copyOf(memberActivities);
  }

  /**
   * 신규 방 생성 트랜잭션의 결과를 두 참여자에게 ROOM_CREATED를 보낼 수 있는 형태로 만든다.
   *
   * @param room 방과 참여자 두 명이 새로 저장된 채팅방
   * @param message 같은 트랜잭션에서 저장된 첫 문의서
   * @return 신규 방용 문의서 처리 결과
   */
  public static InquiryCardProcessResult forNewRoom(ChatRoom room, Message message) {
    return new InquiryCardProcessResult(
        room,
        message,
        true,
        List.of(
            new MemberActivity(room.getTenantId(), true, false),
            new MemberActivity(room.getLandlordId(), true, false)));
  }

  /**
   * 문의서 저장 뒤 사용자 한 명의 채팅방 목록 표시 결과다.
   *
   * @param userId 목록 이벤트를 받을 사용자 ID
   * @param roomVisible 최종적으로 사용자 목록에 방이 보이는지 여부
   * @param roomReopened 숨겨져 있던 방이 이번 문의로 다시 표시됐는지 여부
   */
  public record MemberActivity(long userId, boolean roomVisible, boolean roomReopened) {}
}
