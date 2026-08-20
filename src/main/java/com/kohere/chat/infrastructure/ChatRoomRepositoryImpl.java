package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 채팅방 도메인 포트와 Spring Data JPA 저장소를 연결하는 영속 어댑터다.
 *
 * <p>애플리케이션 계층은 이 어댑터가 감싼 {@link ChatRoomRepository}만 사용하므로 Hibernate 엔티티가 도메인 밖으로 새지 않는다. 변환 코드는
 * 한곳에 모아 Flyway 컬럼을 바꾸었을 때 도메인 영향 범위를 명확히 한다.
 */
@Repository
@RequiredArgsConstructor
public class ChatRoomRepositoryImpl implements ChatRoomRepository {

  /** 실제 SQL 생성과 실행은 Spring Data에 위임하고 저장 결과를 다시 순수 도메인 객체로 반환한다. */
  private final ChatRoomJpaRepository jpaRepository;

  /**
   * {@inheritDoc}
   *
   * <p>IDENTITY PK를 사용하므로 신규 객체의 null ID는 INSERT 뒤 채워지고, 기존 ID는 UPDATE 대상 식별에 사용된다.
   */
  @Override
  public ChatRoom save(ChatRoom chatRoom) {
    // JPA 엔티티가 애플리케이션 계층으로 반환되지 않도록 저장 직후 도메인으로 되돌린다.
    return toDomain(jpaRepository.save(toEntity(chatRoom)));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatRoom> findById(Long roomId) {
    // Optional.map을 사용해 부재를 예외로 바꾸지 않고 포트의 계약 그대로 유지한다.
    return jpaRepository.findById(roomId).map(ChatRoomRepositoryImpl::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<ChatRoom> findByIds(Collection<Long> roomIds) {
    if (roomIds.isEmpty()) {
      return List.of();
    }
    // Spring Data의 findAllById는 하나의 IN 쿼리를 사용한다. DB 반환 순서는 목록 정렬과 무관하므로 서비스가 ID map으로 복원한다.
    return jpaRepository.findAllById(roomIds).stream()
        .map(ChatRoomRepositoryImpl::toDomain)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatRoom> findByListingIdAndTenantIdAndLandlordId(
      String listingId, Long tenantId, Long landlordId) {
    return jpaRepository
        .findByListingIdAndTenantIdAndLandlordId(listingId, tenantId, landlordId)
        .map(ChatRoomRepositoryImpl::toDomain);
  }

  /**
   * JPA 전용 객체를 영속 기술에 의존하지 않는 채팅방으로 복원한다.
   *
   * @param entity MySQL에서 읽은 JPA 엔티티
   * @return 애플리케이션이 사용할 채팅방 도메인 객체
   */
  private static ChatRoom toDomain(ChatRoomJpaEntity entity) {
    // JSON 스냅샷도 타입이 있는 값 객체라 별도의 Map 캐스팅 없이 그대로 도메인에 전달할 수 있다.
    return ChatRoom.builder()
        .id(entity.getId())
        .listingId(entity.getListingId())
        .tenantId(entity.getTenantId())
        .landlordId(entity.getLandlordId())
        .category(entity.getCategory())
        .listingSnapshot(entity.getListingSnapshot())
        .lastMessageId(entity.getLastMessageId())
        .lastMessageAt(entity.getLastMessageAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /**
   * 순수 채팅방을 {@code chat_rooms} 매핑 객체로 변환한다.
   *
   * @param domain 저장할 채팅방
   * @return Hibernate가 관리할 JPA 엔티티
   */
  private static ChatRoomJpaEntity toEntity(ChatRoom domain) {
    // 연관 엔티티를 조회하거나 붙이지 않는다. 교차 모듈 ID는 전달받은 값 그대로 저장하는 것이 no-FK 경계다.
    return ChatRoomJpaEntity.builder()
        .id(domain.getId())
        .listingId(domain.getListingId())
        .tenantId(domain.getTenantId())
        .landlordId(domain.getLandlordId())
        .category(domain.getCategory())
        .listingSnapshot(domain.getListingSnapshot())
        .lastMessageId(domain.getLastMessageId())
        .lastMessageAt(domain.getLastMessageAt())
        .createdAt(domain.getCreatedAt())
        .updatedAt(domain.getUpdatedAt())
        .build();
  }
}
