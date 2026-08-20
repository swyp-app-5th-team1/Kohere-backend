package com.kohere.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data가 {@code chat_messages} SQL과 messageId 범위 조회를 생성하게 하는 내부 저장소다. */
interface MessageJpaRepository extends JpaRepository<MessageJpaEntity, Long> {

  /** TEXT 멱등 UNIQUE와 같은 키로 기존 메시지를 찾는다. */
  Optional<MessageJpaEntity> findByChatRoomIdAndSenderIdAndClientMessageId(
      Long chatRoomId, Long senderId, UUID clientMessageId);

  /** BOOKING_CARD 멱등 UNIQUE와 같은 키로 기존 카드를 찾는다. */
  Optional<MessageJpaEntity> findByChatRoomIdAndBookingId(Long chatRoomId, Long bookingId);

  /** 첫 과거 페이지를 messageId 최신순으로 읽고 {@link Pageable}로 SQL LIMIT를 적용한다. */
  List<MessageJpaEntity> findByChatRoomIdOrderByIdDesc(Long chatRoomId, Pageable pageable);

  /** 기준 messageId보다 오래된 행만 최신순으로 읽고 {@link Pageable}로 SQL LIMIT를 적용한다. */
  List<MessageJpaEntity> findByChatRoomIdAndIdLessThanOrderByIdDesc(
      Long chatRoomId, Long beforeMessageId, Pageable pageable);

  /** 재연결 기준보다 새 행을 오래된 순으로 읽어 중간 누락 없이 checkpoint를 전진시킬 수 있게 한다. */
  List<MessageJpaEntity> findByChatRoomIdAndIdGreaterThanOrderByIdAsc(
      Long chatRoomId, Long afterMessageId, Pageable pageable);
}
