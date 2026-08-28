package com.kohere.chat.application;

/**
 * 저장이 끝난 INQUIRY_CARD를 실시간 채널로 전달하는 애플리케이션 포트다.
 *
 * <p>문의 유스케이스는 Spring STOMP 구현을 직접 알지 않는다. 실제 room topic과 사용자별 목록 이벤트 전송은 infrastructure 구현체가 담당하며,
 * 전송에 실패해도 이미 커밋된 문의서는 REST 메시지 이력으로 복구할 수 있다.
 */
public interface InquiryCardRealtimePublisher {

  /**
   * 문의서와 참여자별 채팅방 목록 신호를 전달한다.
   *
   * @param result 저장된 문의서와 신규·재표시 여부를 포함한 처리 결과
   */
  void publishNewInquiryCard(InquiryCardProcessResult result);
}
