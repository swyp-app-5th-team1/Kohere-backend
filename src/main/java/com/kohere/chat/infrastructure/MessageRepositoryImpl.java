package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * 메시지 도메인 포트와 Spring Data JPA 저장소를 연결하는 영속 어댑터다.
 *
 * <p>모든 조회는 서버 messageId를 정렬 기준으로 사용한다. 클라이언트 전송 시각이나 UUID는 전역 순서를 보장하지 않으므로 이력 cursor에 사용하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class MessageRepositoryImpl implements MessageRepository {

  /** 실제 {@code chat_messages} SQL을 실행하는 내부 Spring Data 저장소다. */
  private final MessageJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public Message save(Message message) {
    // IDENTITY INSERT 결과의 messageId를 ACK와 cursor에 사용해야 하므로 저장 결과를 즉시 도메인으로 변환한다.
    return toDomain(jpaRepository.save(toEntity(message)));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Message> findById(Long messageId) {
    return jpaRepository.findById(messageId).map(MessageRepositoryImpl::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<Message> findByIds(Collection<Long> messageIds) {
    if (messageIds.isEmpty()) {
      return List.of();
    }
    // 현재 페이지가 참조하는 마지막 메시지를 단일 IN 쿼리로 읽어 방 개수만큼 SQL이 늘어나는 것을 막는다.
    return jpaRepository.findAllById(messageIds).stream()
        .map(MessageRepositoryImpl::toDomain)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Message> findByChatRoomIdAndSenderIdAndClientMessageId(
      Long chatRoomId, Long senderId, UUID clientMessageId) {
    return jpaRepository
        .findByChatRoomIdAndSenderIdAndClientMessageId(chatRoomId, senderId, clientMessageId)
        .map(MessageRepositoryImpl::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Message> findByChatRoomIdAndBookingId(Long chatRoomId, Long bookingId) {
    return jpaRepository
        .findByChatRoomIdAndBookingId(chatRoomId, bookingId)
        .map(MessageRepositoryImpl::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<Message> findBefore(Long chatRoomId, Long beforeMessageId, int size) {
    // 첫 페이지에는 cursor가 없으므로 별도 쿼리를 사용한다. SQL의 OR :cursor IS NULL보다 인덱스 범위가 명확하다.
    List<MessageJpaEntity> entities =
        beforeMessageId == null
            ? jpaRepository.findByChatRoomIdOrderByIdDesc(chatRoomId, PageRequest.of(0, size))
            : jpaRepository.findByChatRoomIdAndIdLessThanOrderByIdDesc(
                chatRoomId, beforeMessageId, PageRequest.of(0, size));

    return entities.stream().map(MessageRepositoryImpl::toDomain).toList();
  }

  /** {@inheritDoc} */
  @Override
  public List<Message> findAfter(Long chatRoomId, Long afterMessageId, int size) {
    // 오름차순으로 반환해야 앱이 빈 구간 없이 연속 checkpoint를 검증한 뒤 앞으로 전진시킬 수 있다.
    return jpaRepository
        .findByChatRoomIdAndIdGreaterThanOrderByIdAsc(
            chatRoomId, afterMessageId, PageRequest.of(0, size))
        .stream()
        .map(MessageRepositoryImpl::toDomain)
        .toList();
  }

  /**
   * JPA 엔티티를 영속 기술이 없는 메시지 도메인 객체로 복원한다.
   *
   * @param entity MySQL에서 읽은 메시지 엔티티
   * @return 애플리케이션 계층이 사용할 메시지
   */
  private static Message toDomain(MessageJpaEntity entity) {
    // TEXT와 BOOKING_CARD의 nullable 필드는 DB CHECK를 통과한 조합 그대로 복원한다.
    return Message.builder()
        .id(entity.getId())
        .chatRoomId(entity.getChatRoomId())
        .senderId(entity.getSenderId())
        .type(entity.getType())
        .content(entity.getContent())
        .payload(entity.getPayload())
        .bookingId(entity.getBookingId())
        .clientMessageId(entity.getClientMessageId())
        .sentAt(entity.getSentAt())
        .build();
  }

  /**
   * 메시지 도메인 객체를 {@code chat_messages} JPA 엔티티로 변환한다.
   *
   * @param domain 저장할 메시지
   * @return Hibernate가 관리할 메시지 엔티티
   */
  private static MessageJpaEntity toEntity(Message domain) {
    // payload와 UUID의 실제 JSON/BINARY 변환은 엔티티에 선언한 Hibernate 타입이 담당한다.
    return MessageJpaEntity.builder()
        .id(domain.getId())
        .chatRoomId(domain.getChatRoomId())
        .senderId(domain.getSenderId())
        .type(domain.getType())
        .content(domain.getContent())
        .payload(domain.getPayload())
        .bookingId(domain.getBookingId())
        .clientMessageId(domain.getClientMessageId())
        .sentAt(domain.getSentAt())
        .build();
  }
}
