package com.kohere.chat.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 저장 완료 메시지의 영속 포트다.
 *
 * <p>이 포트는 메시지를 상대에게 전달하는 네트워크 큐가 아니라 MySQL 정본을 읽고 쓴다. 과거 이력은 {@code beforeMessageId}보다 작은 ID를
 * 내림차순으로, 재연결 누락 보충은 {@code afterMessageId}보다 큰 ID를 오름차순으로 읽는다. 안 읽은 메시지 집계는 후속 기능이라 포함하지 않는다.
 */
public interface MessageRepository {

  /**
   * 검증이 끝난 TEXT 또는 서버 BOOKING_CARD를 저장한다.
   *
   * @param message 저장할 불변 메시지
   * @return DB가 발급한 messageId를 포함한 메시지
   */
  Message save(Message message);

  /**
   * 서버 messageId로 메시지를 찾는다.
   *
   * @param messageId 서버가 발급한 메시지 번호
   * @return 존재하면 메시지, 없으면 빈 값
   */
  Optional<Message> findById(Long messageId);

  /**
   * TEXT 재시도의 멱등 키로 이미 저장된 메시지를 찾는다.
   *
   * @param chatRoomId 메시지를 보낸 방 번호
   * @param senderId 인증 Principal에서 얻은 발신자 번호
   * @param clientMessageId 프런트가 생성하고 재시도에도 재사용한 UUID
   * @return 같은 멱등 키의 TEXT가 있으면 기존 메시지
   */
  Optional<Message> findByChatRoomIdAndSenderIdAndClientMessageId(
      Long chatRoomId, Long senderId, UUID clientMessageId);

  /**
   * 같은 신청 이벤트로 이미 만든 카드를 찾는다.
   *
   * @param chatRoomId 카드가 속한 방 번호
   * @param bookingId 카드의 원본 신청 번호
   * @return 이미 저장된 카드가 있으면 해당 메시지
   */
  Optional<Message> findByChatRoomIdAndBookingId(Long chatRoomId, Long bookingId);

  /**
   * 채팅 화면에서 위로 스크롤할 때 기준 ID보다 오래된 메시지를 최신순으로 읽는다.
   *
   * @param chatRoomId 조회할 방 번호
   * @param beforeMessageId 이 값보다 작은 messageId만 조회하며 첫 페이지는 {@code null}
   * @param size 최대 조회 개수
   * @return messageId 내림차순의 과거 메시지
   */
  List<Message> findBefore(Long chatRoomId, Long beforeMessageId, int size);

  /**
   * WebSocket 재연결 뒤 마지막 연속 동기화 지점보다 새 메시지를 오래된 순으로 읽는다.
   *
   * @param chatRoomId 조회할 방 번호
   * @param afterMessageId 마지막으로 연속 확인한 messageId
   * @param size 최대 조회 개수
   * @return messageId 오름차순의 누락 후보 메시지
   */
  List<Message> findAfter(Long chatRoomId, Long afterMessageId, int size);
}
