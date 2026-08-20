package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code chat_messages} 전용 JPA 엔티티다.
 *
 * <p>TEXT와 BOOKING_CARD를 한 시간축과 하나의 messageId cursor로 조회하기 위해 단일 테이블을 사용한다. 두 타입이 요구하는 필드가 다르므로
 * nullable 컬럼을 사용하되, Flyway의 타입별 CHECK가 잘못된 조합을 최종 차단한다.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class MessageJpaEntity {

  /** 서버가 발급하는 정렬 가능한 messageId로 cursor와 재연결 high-watermark에 함께 사용한다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 메시지가 속한 chat_rooms.id 값이며 no-FK 정책에 따라 숫자로만 참조한다. */
  @Column(nullable = false)
  private Long chatRoomId;

  /** TEXT 발신자 users.id이며 서버가 만든 BOOKING_CARD에는 값이 없다. */
  private Long senderId;

  /** Java enum 이름을 그대로 저장해 DB CHECK의 TEXT/BOOKING_CARD 값과 일치시킨다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private MessageType type;

  /** 사용자가 보낸 변경 불가 원문이며 카드에서는 null이다. 길이 3,000자 상한은 DB CHECK도 검증한다. */
  @Column(columnDefinition = "text")
  private String content;

  /** 서버 BOOKING_CARD의 구조화 스냅샷이며 TEXT에서는 null이다. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "json")
  private BookingCardPayload payload;

  /** 카드의 출처인 bookings.id이며 같은 방·신청 중복 카드를 막는 UNIQUE에 사용한다. */
  private Long bookingId;

  /**
   * 프런트 UUID를 36자 문자열이 아닌 16바이트로 보관한다. Hibernate에 BINARY JDBC 타입을 명시해 Flyway의 BINARY(16)과 일치시킨다.
   */
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(columnDefinition = "binary(16)")
  private UUID clientMessageId;

  /** 클라이언트가 보낸 시간이 아니라 서버가 메시지를 저장한 UTC 시각이다. */
  @Column(nullable = false)
  private Instant sentAt;
}
