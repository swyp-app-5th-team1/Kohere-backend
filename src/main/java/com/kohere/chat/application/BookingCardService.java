package com.kohere.chat.application;

import com.kohere.booking.BookingCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * BookingCreatedEvent 한 건을 "같은 채팅방 보장 → 신청 카드 1회 저장" 순서로 처리한다.
 *
 * <p>이 서비스는 HTTP API가 아니며 프런트가 호출하지 않는다. 예약 커밋 후 {@link BookingEventHandler}가 자동으로 호출한다. 문의하기와 동일한
 * {@link ChatRoomEnsurer}를 사용하므로 이전 대화가 있는 경우 새 방을 만들지 않고 기존 roomId에 카드를 이어 붙인다.
 */
@Service
@RequiredArgsConstructor
public class BookingCardService {

  private final ChatRoomEnsurer roomEnsurer;
  private final BookingCardWriter cardWriter;

  /** 신청 이벤트를 동일 매물 채팅방의 BOOKING_CARD로 반영한다. */
  public ProcessResult process(BookingCreatedEvent event) {
    BookingCreatedEvent.ListingSnapshot listing = event.cardSnapshot().listing();
    ChatRoomSeed seed =
        new ChatRoomSeed(event.listingId(), event.landlordId(), listing.title(), listing.address());

    ChatRoomEnsurer.EnsureResult room =
        roomEnsurer.ensure(seed, event.tenantId(), event.occurredAt());
    BookingCardWriter.WriteResult card = cardWriter.saveIfAbsent(room.room().getId(), event);

    return new ProcessResult(room.room().getId(), room.created(), card.messageId(), card.created());
  }

  /** 운영 로그와 통합 테스트에서 방·카드가 새로 생성됐는지 구분하기 위한 내부 처리 결과다. */
  public record ProcessResult(
      Long chatRoomId, boolean roomCreated, Long messageId, boolean cardCreated) {}
}
