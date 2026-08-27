package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.Message;

/**
 * 새 문의 방과 함께 커밋된 INQUIRY_CARD를 실시간 채널로 전달하는 애플리케이션 포트다.
 *
 * <p>문의 유스케이스는 Spring STOMP 구현을 직접 알지 않는다. 실제 room topic과 사용자별 목록 이벤트 전송은 infrastructure 구현체가 담당하며,
 * 전송에 실패해도 이미 커밋된 문의서는 REST 메시지 이력으로 복구할 수 있다.
 */
public interface InquiryCardRealtimePublisher {

  /**
   * 신규 문의서와 새 채팅방 목록 신호를 두 참여자에게 전달한다.
   *
   * @param room 신규 문의서와 같은 트랜잭션으로 저장된 채팅방
   * @param message 저장이 끝난 INQUIRY_CARD 메시지
   */
  void publishNewInquiryCard(ChatRoom room, Message message);
}
