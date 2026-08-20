package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 매물·임차인·임대인 조합에 해당하는 채팅방이 정확히 하나 존재하도록 보장한다.
 *
 * <p>문의하기와 신청하기가 각자 방 생성 코드를 가지면 동시에 실행될 때 서로 다른 방을 만들 수 있다. 두 흐름이 이 컴포넌트를 함께 사용하고, 마지막 중복 방지는
 * MySQL UNIQUE가 담당한다.
 *
 * <p>이 컴포넌트 자체는 트랜잭션을 열지 않는다. {@link ChatRoomCreator}의 생성 트랜잭션이 UNIQUE 충돌로 롤백된 뒤에야 기존 방을 다시 조회할 수
 * 있어야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomEnsurer {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomCreator roomCreator;

  /**
   * 기존 방을 반환하거나 새 방과 참여자 두 명을 만든다.
   *
   * @param seed 방 생성에 필요한 매물·임대인 정보
   * @param tenantId 방의 임차인 users.id
   * @param now 신규 방 생성 시각
   * @return 방과 이번 호출에서 실제 생성했는지 여부
   */
  public EnsureResult ensure(ChatRoomSeed seed, long tenantId, Instant now) {
    return chatRoomRepository
        .findByListingIdAndTenantIdAndLandlordId(seed.listingId(), tenantId, seed.landlordId())
        .map(room -> new EnsureResult(room, false))
        .orElseGet(() -> createOrFindConcurrentRoom(seed, tenantId, now));
  }

  /** 동시에 다른 요청이 먼저 방을 만들면 UNIQUE 충돌을 받은 뒤 그 방을 다시 읽어 같은 roomId로 수렴한다. */
  private EnsureResult createOrFindConcurrentRoom(ChatRoomSeed seed, long tenantId, Instant now) {
    try {
      ChatRoom created = roomCreator.create(seed, tenantId, now);
      return new EnsureResult(created, true);
    } catch (DataIntegrityViolationException conflict) {
      return chatRoomRepository
          .findByListingIdAndTenantIdAndLandlordId(seed.listingId(), tenantId, seed.landlordId())
          .map(room -> new EnsureResult(room, false))
          // 기존 방이 없다면 방 UNIQUE가 아닌 다른 저장 결함이므로 원래 예외를 숨기지 않는다.
          .orElseThrow(() -> conflict);
    }
  }

  /** 같은 방을 반환하더라도 신규 생성인지 기존 방인지 호출자가 구분할 수 있게 하는 내부 결과다. */
  public record EnsureResult(ChatRoom room, boolean created) {}
}
