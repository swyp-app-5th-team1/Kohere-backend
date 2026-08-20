package com.kohere.chat.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data가 {@code chat_rooms} SQL을 생성하게 하는 내부 저장소다. 도메인 계층에는 이 타입을 노출하지 않는다. */
interface ChatRoomJpaRepository extends JpaRepository<ChatRoomJpaEntity, Long> {

  /**
   * 방의 DB UNIQUE와 같은 세 값으로 기존 행을 찾는다.
   *
   * @param listingId 매물 ID
   * @param tenantId 임차인 ID
   * @param landlordId 임대인 ID
   * @return 이미 존재하는 채팅방 엔티티
   */
  Optional<ChatRoomJpaEntity> findByListingIdAndTenantIdAndLandlordId(
      String listingId, Long tenantId, Long landlordId);
}
