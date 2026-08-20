package com.kohere.chat.presentation.stomp.dto;

import java.util.UUID;

/**
 * PONG 또는 방 구독 준비 완료를 개인 control queue로 전달하는 이벤트다.
 *
 * <p>단순히 SUBSCRIBE frame을 보낸 시점에는 broker 등록이 끝났다고 보장할 수 없다. 서버가 {@code SUBSCRIPTION_READY}와 DB
 * high-watermark를 보낸 뒤 프런트엔드가 REST 누락 조회를 실행하게 해 그 사이의 메시지를 메운다.
 */
public record ChatControlEventPayload(
    /** payload 호환성을 판단하는 계약 버전. 현재 값은 1이다. */
    int version,
    /** PONG 또는 SUBSCRIPTION_READY를 구분하는 이벤트 종류. */
    ChatStompEventType eventType,
    /** PONG을 원래 ping과 연결하는 ID. SUBSCRIPTION_READY에는 null일 수 있다. */
    UUID requestId,
    /** 준비가 끝난 채팅방 ID. PONG처럼 특정 방과 무관한 이벤트에는 null일 수 있다. */
    Long roomId,
    /** 구독 준비 시점에 DB에 저장된 마지막 메시지 ID. 빈 방이거나 PONG이면 null이다. */
    Long highWatermark) {

  public static final int CURRENT_VERSION = 1;

  /**
   * 개인 control queue 구독이 준비됐음을 원래 ping에 답한다.
   *
   * <p>PONG에는 roomId와 highWatermark가 들어가지 않는다. 호출부가 null 위치를 직접 외우지 않게 이 이름 있는 생성 메서드를 사용한다.
   */
  public static ChatControlEventPayload pong(UUID requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required for PONG");
    }
    return new ChatControlEventPayload(
        CURRENT_VERSION, ChatStompEventType.PONG, requestId, null, null);
  }

  /**
   * 특정 채팅방 topic이 실제 broker에 등록된 뒤 REST 누락 조회의 상한을 알려 준다.
   *
   * <p>빈 방이거나 사용자에게 보이는 메시지가 없으면 highWatermark는 {@code null}이다. 이 이벤트는 ping 응답이 아니므로 requestId를 넣지
   * 않는다.
   */
  public static ChatControlEventPayload subscriptionReady(long roomId, Long highWatermark) {
    if (roomId < 1) {
      throw new IllegalArgumentException("roomId must be positive");
    }
    if (highWatermark != null && highWatermark < 1) {
      throw new IllegalArgumentException("highWatermark must be positive when present");
    }
    return new ChatControlEventPayload(
        CURRENT_VERSION, ChatStompEventType.SUBSCRIPTION_READY, null, roomId, highWatermark);
  }
}
