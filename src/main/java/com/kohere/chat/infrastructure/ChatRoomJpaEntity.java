package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ListingSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code chat_rooms} 전용 JPA 엔티티다.
 *
 * <p>도메인 {@link com.kohere.chat.domain.ChatRoom}과 분리해 Hibernate 매핑 책임만 가진다. 다른 모듈의 매물·사용자 ID는 숫자 또는
 * 문자열 값으로 보관하고 JPA 연관관계를 만들지 않는다. 생성 시점 매물 표시는 JSON으로 고정해 원본 매물의 변경·삭제와 무관하게 기존 방 헤더를 표시한다.
 */
@Entity
@Table(name = "chat_rooms")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ChatRoomJpaEntity {

  /** MySQL IDENTITY가 API의 chatRoomId로 사용할 양의 정수를 발급한다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** DB에는 enum 순서가 아니라 이름을 저장해 Java enum 항목 재정렬에 영향받지 않게 한다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ChatCategory category;

  /** MongoDB ObjectId 문자열이므로 24자로 고정하며 listing 엔티티와 관계 매핑하지 않는다. */
  @Column(nullable = false, length = 24)
  private String listingId;

  /** users.id의 값 참조이며 교차 모듈 FK를 두지 않는다. */
  @Column(nullable = false)
  private Long tenantId;

  /** users.id의 값 참조이며 교차 모듈 FK를 두지 않는다. */
  @Column(nullable = false)
  private Long landlordId;

  /** Hibernate JSON 매퍼가 타입이 있는 스냅샷과 MySQL JSON 사이를 변환한다. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  private ListingSnapshot listingSnapshot;

  /** 메시지가 아직 없는 방은 null이고, 이후 메시지 저장 트랜잭션에서 최신 chat_messages.id로 갱신한다. */
  private Long lastMessageId;

  /** lastMessageId와 한 쌍으로만 설정하도록 DB CHECK가 보호한다. */
  private Instant lastMessageAt;

  /** 생성·수정 시각은 애플리케이션 서버가 UTC로 결정하며 Hibernate 자동 시각에 숨겨 의존하지 않는다. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 마지막 메시지 포인터 등 방 상태를 실제로 바꾼 시각이다. */
  @Column(nullable = false)
  private Instant updatedAt;
}
