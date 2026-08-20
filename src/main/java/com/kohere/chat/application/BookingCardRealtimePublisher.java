package com.kohere.chat.application;

/**
 * MySQL에 새로 저장된 BOOKING_CARD를 실시간 채널로 전달하는 애플리케이션 포트다.
 *
 * <p>신청 이벤트를 처리하는 애플리케이션 코드는 STOMP나 Spring의 메시징 클래스를 직접 알 필요가 없다. 대신 이 작은 인터페이스만 호출하고, 실제 WebSocket
 * 전송은 infrastructure 구현체가 담당한다. 이렇게 분리하면 카드 저장 규칙을 WebSocket 기술과 독립적으로 테스트할 수 있다.
 */
public interface BookingCardRealtimePublisher {

  /**
   * 신규 카드 저장 결과를 현재 접속 중인 채팅방 참여자에게 전달한다.
   *
   * <p>호출 시점에는 카드 저장 트랜잭션이 이미 커밋되어 있다. 따라서 실시간 전송에 실패하더라도 카드는 MySQL과 REST 메시지 이력에 남는다.
   *
   * @param result 저장된 카드와 채팅방·참여자 표시 결과
   */
  void publishNewCard(BookingCardService.ProcessResult result);
}
