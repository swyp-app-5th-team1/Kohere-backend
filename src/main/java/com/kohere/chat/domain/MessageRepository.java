package com.kohere.chat.domain;

import java.util.Collection;
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
   * 채팅방 목록 페이지가 가리키는 마지막 메시지들을 한 번에 조회한다.
   *
   * <p>각 채팅방의 {@code lastMessageId}마다 단건 조회를 반복하지 않기 위한 batch 포트다. 반환 순서는 보장하지 않으며 호출자가 messageId를
   * 키로 다시 조립한다.
   *
   * @param messageIds 조회할 서버 메시지 ID 모음
   * @return 존재하는 메시지 목록
   */
  List<Message> findByIds(Collection<Long> messageIds);

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

  /**
   * 채팅방 신고 당시 사용자에게 보이는 경계 이후의 최근 TEXT 원문만 조회한다.
   *
   * <p>일반 이력 조회 뒤 애플리케이션에서 BOOKING_CARD를 걸러내면 카드가 많은 방에서 TEXT 20개를 채우지 못한다. DB에서 처음부터 타입과 경계를 적용해
   * 정확한 증거 개수를 읽는다.
   *
   * @param chatRoomId 신고할 채팅방 ID
   * @param hiddenThroughMessageId 신고자에게 계속 숨길 마지막 과거 messageId. 숨긴 이력이 없으면 0
   * @param size 보관할 최근 TEXT 최대 개수
   * @return messageId 내림차순의 최근 TEXT 메시지
   */
  List<Message> findRecentTextForReport(Long chatRoomId, long hiddenThroughMessageId, int size);
}
