package com.kohere.chat.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * 서버 저장이 끝난 채팅 메시지의 불변 도메인 모델이다.
 *
 * <p>이 객체는 상대방마다 복제하는 전달 큐가 아니다. 한 메시지를 {@code chat_messages}에 한 번 저장하고 {@code chatRoomId}와 {@code
 * senderId}로 소속 방과 발신자를 구분한다. {@link MessageType#TEXT}는 사용자 원문과 프런트 UUID를, {@link
 * MessageType#BOOKING_CARD}는 서버가 만든 신청 스냅샷과 bookingId를 가진다. 타입별 nullable 조합은 애플리케이션과 DB CHECK가 함께
 * 보호한다.
 */
@Getter
public class Message {

  /** TEXT 원문에 허용하는 Unicode code point 수다. DB와 STOMP 요청 검증도 같은 값인 3,000을 사용한다. */
  public static final int MAX_TEXT_CODE_POINTS = 3_000;

  /** DB가 발급하고 API에서 {@code messageId}로 사용하는 번호다. 신규 저장 전에는 {@code null}이다. */
  private final Long id;

  /** 메시지가 속한 {@code chat_rooms.id} 값이다. */
  private final Long chatRoomId;

  /** TEXT를 보낸 {@code users.id}이며 서버 생성 BOOKING_CARD에서는 {@code null}이다. */
  private final Long senderId;

  /** 사용자 원문인지 서버 신청 카드인지 결정하고 나머지 필드의 유효한 조합을 정한다. */
  private final MessageType type;

  /** 변경하지 않는 TEXT 원문이며 BOOKING_CARD에서는 {@code null}이다. */
  private final String content;

  /** BOOKING_CARD의 구조화 스냅샷이며 TEXT에서는 {@code null}이다. */
  private final BookingCardPayload payload;

  /** 카드 생성의 원본 신청 번호이며 TEXT에서는 {@code null}이다. */
  private final Long bookingId;

  /** TEXT 전송 전에 프런트가 만든 멱등 UUID이며 서버 카드에서는 {@code null}이다. */
  private final UUID clientMessageId;

  /** 클라이언트 시간이 아니라 서버가 실제 저장할 때 정한 UTC 시각이다. */
  private final Instant sentAt;

  /**
   * 메시지 종류에 맞는 필드 조합만 도메인 객체로 만들 수 있게 한다.
   *
   * <p>DB CHECK는 최종 방어선이지만, 잘못된 객체를 SQL 실행 시점까지 전달하면 오류 원인을 찾기 어렵다. 특히 카드 컬럼의 {@code bookingId}와
   * payload 내부 값이 다르면 중복 판정과 앱 표시가 서로 다른 신청을 가리키므로 생성 즉시 거부한다.
   */
  @Builder
  private Message(
      Long id,
      Long chatRoomId,
      Long senderId,
      MessageType type,
      String content,
      BookingCardPayload payload,
      Long bookingId,
      UUID clientMessageId,
      Instant sentAt) {
    this.id = id;
    this.chatRoomId = chatRoomId;
    this.senderId = senderId;
    this.type = type;
    this.content = content;
    this.payload = payload;
    this.bookingId = bookingId;
    this.clientMessageId = clientMessageId;
    this.sentAt = sentAt;

    validate();
  }

  /** 공통 필수값과 TEXT·BOOKING_CARD의 서로 배타적인 필드를 검증한다. */
  private void validate() {
    if (chatRoomId == null || type == null || sentAt == null) {
      throw new IllegalArgumentException("chatRoomId, type and sentAt are required");
    }

    switch (type) {
      case TEXT -> validateText();
      case BOOKING_CARD -> validateBookingCard();
    }
  }

  /** 사용자 TEXT에만 허용되는 발신자·원문·멱등 UUID 조합과 3,000자 제한을 확인한다. */
  private void validateText() {
    if (senderId == null
        || content == null
        || content.isBlank()
        || clientMessageId == null
        || bookingId != null
        || payload != null) {
      throw new IllegalArgumentException("TEXT message fields are invalid");
    }

    int codePointCount = content.codePointCount(0, content.length());
    if (codePointCount > MAX_TEXT_CODE_POINTS) {
      throw new IllegalArgumentException("TEXT message exceeds 3000 Unicode code points");
    }
  }

  /** 서버 카드에만 허용되는 신청 번호·payload 조합과 두 bookingId의 일치를 확인한다. */
  private void validateBookingCard() {
    if (senderId != null
        || content != null
        || clientMessageId != null
        || bookingId == null
        || payload == null) {
      throw new IllegalArgumentException("BOOKING_CARD message fields are invalid");
    }
    if (!bookingId.equals(payload.bookingId())) {
      throw new IllegalArgumentException("BOOKING_CARD bookingId must match payload bookingId");
    }
  }
}
