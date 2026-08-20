package com.kohere.chat.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 채팅방 도메인이 MySQL 구현 세부사항 없이 저장·조회할 수 있게 하는 영속 포트다.
 *
 * <p>구현은 infrastructure 계층에 두어 의존성을 역전한다. {@code category}는 내부 저장값으로 유지하지만 현재 사용자 API의 필터나 응답으로
 * 노출하지 않는다. 목록 조회는 참여자 포트가 먼저 정한 현재 페이지의 roomId만 이 포트로 일괄 조회해 사용자별 가시성과 공유 방 데이터를 분리한다.
 */
public interface ChatRoomRepository {

  /**
   * 채팅방을 신규 저장하거나 마지막 메시지 포인터가 바뀐 기존 방을 갱신한다.
   *
   * @param chatRoom 저장할 순수 도메인 객체
   * @return DB가 발급한 ID와 저장값을 포함한 도메인 객체
   */
  ChatRoom save(ChatRoom chatRoom);

  /**
   * 서버 roomId로 채팅방을 찾는다.
   *
   * <p>이 메서드는 저장 기반용 비필터 조회다. 요청자의 참여 여부와 숨김 상태를 검증하지 않으므로 컨트롤러에서 직접 사용하면 안 된다.
   *
   * @param roomId 서버가 발급한 채팅방 번호
   * @return 존재하면 채팅방, 없으면 빈 값
   */
  Optional<ChatRoom> findById(Long roomId);

  /**
   * 새 메시지를 저장할 때 같은 방의 동시 쓰기를 한 줄로 세우기 위해 방 행을 잠가 조회한다.
   *
   * <p>BOOKING_CARD 중복 확인, 메시지 INSERT, 마지막 메시지 포인터 갱신이 서로 엇갈리지 않게 하는 변이 전용 메서드다. 사용자 조회 API에서는 사용하지
   * 않는다.
   *
   * @param roomId 잠글 채팅방 번호
   * @return 존재하면 잠긴 채팅방
   */
  Optional<ChatRoom> findByIdForUpdate(Long roomId);

  /**
   * 한 목록 페이지에 포함된 채팅방들을 한 번에 조회한다.
   *
   * <p>방마다 {@link #findById(Long)}를 반복하면 20개 목록에 20번의 SQL이 추가되는 N+1 문제가 생긴다. 목록 서비스는 먼저 참여자 페이지에서
   * roomId를 모은 뒤 이 메서드를 한 번만 호출한다. 반환 순서는 보장하지 않으며 호출자가 ID map으로 조립한다.
   *
   * @param roomIds 조회할 서버 채팅방 ID 모음
   * @return 존재하는 채팅방 목록
   */
  List<ChatRoom> findByIds(Collection<Long> roomIds);

  /**
   * 문의하기와 신청하기가 공유하는 비즈니스 키로 기존 방을 찾는다.
   *
   * @param listingId 문의 대상 매물 ID
   * @param tenantId 임차인 사용자 ID
   * @param landlordId 임대인 사용자 ID
   * @return 같은 세 값으로 만든 방이 있으면 해당 방
   */
  Optional<ChatRoom> findByListingIdAndTenantIdAndLandlordId(
      String listingId, Long tenantId, Long landlordId);
}
