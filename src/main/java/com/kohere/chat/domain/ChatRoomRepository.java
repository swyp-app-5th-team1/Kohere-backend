package com.kohere.chat.domain;

import java.util.Optional;

/**
 * 채팅방 도메인이 MySQL 구현 세부사항 없이 저장·조회할 수 있게 하는 영속 포트다.
 *
 * <p>구현은 infrastructure 계층에 두어 의존성을 역전한다. {@code category}는 내부 저장값으로 유지하지만 현재 사용자 API의 필터나 응답으로
 * 노출하지 않는다. 목록 조립과 사용자별 가시성 조회는 실제 REST 기능을 연결하는 후속 단계에서 참여자 포트와 함께 확장한다.
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
