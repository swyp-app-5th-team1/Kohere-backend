package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.Message;
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

  /**
   * 문의용 기존 방을 반환하거나 새 방과 참여자·INQUIRY_CARD를 함께 만든다.
   *
   * <p>신청 흐름의 {@link #ensure(ChatRoomSeed, long, Instant)}와 분리한 이유는 신청으로 처음 생긴 방에 문의서를 잘못 추가하지 않기
   * 위해서다. 문의서 생성은 이 메서드가 실제로 새 방을 만든 경우에만 실행된다.
   *
   * @param seed 방 생성에 필요한 매물·임대인 정보
   * @param tenantId 방의 임차인 users.id
   * @param inquiryPayload 공개 매물에서 만든 문의서 사본
   * @param now 신규 방과 문의서 생성 시각
   * @return 방, 신규 여부, 신규일 때 저장된 문의서 메시지
   */
  public InquiryEnsureResult ensureInquiry(
      ChatRoomSeed seed, long tenantId, InquiryCardPayload inquiryPayload, Instant now) {
    return chatRoomRepository
        .findByListingIdAndTenantIdAndLandlordId(seed.listingId(), tenantId, seed.landlordId())
        .map(room -> new InquiryEnsureResult(room, null, false))
        .orElseGet(() -> createOrFindConcurrentInquiryRoom(seed, tenantId, inquiryPayload, now));
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

  /** 동시 문의 중 한 요청만 방과 문의서를 저장하고 나머지는 UNIQUE 충돌 후 같은 기존 방으로 수렴시킨다. */
  private InquiryEnsureResult createOrFindConcurrentInquiryRoom(
      ChatRoomSeed seed, long tenantId, InquiryCardPayload inquiryPayload, Instant now) {
    try {
      ChatRoomCreator.InquiryRoomCreation created =
          roomCreator.createInquiry(seed, tenantId, inquiryPayload, now);
      return new InquiryEnsureResult(created.room(), created.message(), true);
    } catch (DataIntegrityViolationException conflict) {
      return chatRoomRepository
          .findByListingIdAndTenantIdAndLandlordId(seed.listingId(), tenantId, seed.landlordId())
          .map(room -> new InquiryEnsureResult(room, null, false))
          // 방 UNIQUE 외의 저장 결함이라면 기존 ensure와 마찬가지로 원래 예외를 숨기지 않는다.
          .orElseThrow(() -> conflict);
    }
  }

  /** 같은 방을 반환하더라도 신규 생성인지 기존 방인지 호출자가 구분할 수 있게 하는 내부 결과다. */
  public record EnsureResult(ChatRoom room, boolean created) {}

  /** 신규 문의일 때만 message가 존재하며 기존 방 결과에서는 {@code null}인 문의 전용 결과다. */
  public record InquiryEnsureResult(ChatRoom room, Message message, boolean created) {

    /** created와 message가 서로 모순되는 결과를 만들 수 없게 내부 계약을 즉시 확인한다. */
    public InquiryEnsureResult {
      if (created != (message != null)) {
        throw new IllegalArgumentException(
            "New inquiry room must contain exactly one card message");
      }
    }
  }
}
