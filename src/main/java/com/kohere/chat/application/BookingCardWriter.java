package com.kohere.chat.application;

import com.kohere.booking.BookingCreatedEvent;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 채팅방에 BOOKING_CARD 한 건을 저장하고 마지막 메시지 상태까지 한 트랜잭션으로 갱신한다.
 *
 * <p>비동기 이벤트는 같은 내용이 다시 전달될 수 있다. 그래서 처리 시작 시 방 행을 잠근 뒤 {@code (chatRoomId, bookingId)}로 기존 카드를
 * 확인한다. 이미 있으면 메시지·방·참여자 어느 것도 다시 변경하지 않는다. DB UNIQUE는 코드 검사를 통과한 동시 요청에 대한 마지막 방어선이다.
 */
@Component
@RequiredArgsConstructor
public class BookingCardWriter {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;

  /**
   * 신청 카드를 아직 저장하지 않은 경우에만 생성한다.
   *
   * @param roomId 카드를 추가할 동일 매물 채팅방 ID
   * @param event 예약 저장 시점 사본을 가진 이벤트
   * @return 최종 메시지와 신규 생성 여부, 신규 활동을 반영한 참여자별 목록 표시 결과
   */
  @Transactional
  public WriteResult saveIfAbsent(long roomId, BookingCreatedEvent event) {
    // 방을 먼저 잠그는 순서를 TEXT 저장에도 동일하게 적용하면 방 마지막 포인터가 서로 덮어써지는 일을 막을 수 있다.
    ChatRoom room =
        chatRoomRepository
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> new IllegalStateException("BOOKING_CARD 대상 채팅방을 찾을 수 없습니다."));
    assertSameConversation(room, event);

    // 중복 이벤트는 기존 결과만 반환한다. 이 분기에서는 숨긴 방 재표시나 lastMessage 갱신도 하지 않는다.
    Message existing =
        messageRepository.findByChatRoomIdAndBookingId(roomId, event.bookingId()).orElse(null);
    if (existing != null) {
      // 중복 이벤트에서는 실시간 발행도 하지 않으므로 참여자 목록 결과가 필요하지 않다.
      return new WriteResult(existing, false, List.of());
    }

    Instant storedAt = Instant.now();
    Message saved =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .type(MessageType.BOOKING_CARD)
                .bookingId(event.bookingId())
                .payload(toPayload(event))
                // sentAt은 비동기 worker가 실제 카드 정본을 저장한 시각이다. 예약 발생 시각은 재표시 선후 판정에 별도로 사용한다.
                .sentAt(storedAt)
                .build());

    // 메시지 INSERT가 성공한 뒤에만 방의 마지막 메시지 포인터를 새 카드로 이동한다.
    chatRoomRepository.save(room.recordMessage(saved.getId(), storedAt));

    List<ChatRoomMember> members = memberRepository.findByChatRoomId(roomId);
    if (members.size() != 2) {
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    List<MemberActivityResult> memberActivities = new ArrayList<>(members.size());
    for (ChatRoomMember member : members) {
      ChatRoomMember visible = member.showForNewActivity(event.occurredAt(), storedAt);
      boolean reopened = visible != member;
      if (visible != member) {
        memberRepository.save(visible);
      }

      // 지연 이벤트보다 나중에 사용자가 방을 삭제했다면 hidden 상태가 유지된다. 그 사용자에게 목록 갱신 이벤트를 보내지 않도록 최종 상태도 반환한다.
      memberActivities.add(
          new MemberActivityResult(
              visible.getUserId(), visible.getRoomHiddenAt() == null, reopened));
    }

    return new WriteResult(saved, true, memberActivities);
  }

  /** 이벤트가 조회된 방과 동일한 매물·두 참여자를 가리키는지 방어적으로 확인한다. */
  private static void assertSameConversation(ChatRoom room, BookingCreatedEvent event) {
    boolean same =
        room.getListingId().equals(event.listingId())
            && room.getTenantId().equals(event.tenantId())
            && room.getLandlordId().equals(event.landlordId());
    if (!same) {
      throw new IllegalStateException("예약 이벤트와 채팅방 참여자 정보가 일치하지 않습니다.");
    }
  }

  /** booking 공개 이벤트의 사본을 chat 모듈이 소유하는 JSON payload 타입으로 옮긴다. */
  private static BookingCardPayload toPayload(BookingCreatedEvent event) {
    BookingCreatedEvent.CardSnapshot card = event.cardSnapshot();
    BookingCreatedEvent.ListingSnapshot listing = card.listing();
    BookingCreatedEvent.ApplicantSnapshot applicant = card.applicant();

    return new BookingCardPayload(
        event.bookingId(),
        new BookingCardPayload.Listing(
            listing.listingId(),
            listing.thumbnailUrl(),
            listing.title(),
            listing.address(),
            listing.monthlyRent()),
        new BookingCardPayload.Applicant(
            applicant.userId(),
            applicant.name(),
            applicant.gender(),
            applicant.country(),
            applicant.countryName(),
            applicant.email()),
        card.roomOfferId(),
        card.roomOfferName(),
        card.moveInDate(),
        card.contractPeriod(),
        card.deposit(),
        card.totalAmount());
  }

  /** listener와 실시간 발행기가 신규 저장 결과를 다시 조회하지 않고 사용할 수 있게 하는 내부 결과다. */
  public record WriteResult(
      Message message, boolean created, List<MemberActivityResult> memberActivities) {

    /** 호출자가 전달한 변경 가능한 목록이 트랜잭션 밖에서 바뀌지 않도록 복사한다. */
    public WriteResult {
      memberActivities = List.copyOf(memberActivities);
    }

    /** 기존 로그와 테스트가 최종 서버 messageId를 간단히 읽을 수 있도록 제공한다. */
    public Long messageId() {
      return message.getId();
    }
  }

  /** 카드 저장 뒤 각 참여자의 채팅방 목록 표시 상태다. */
  public record MemberActivityResult(long userId, boolean roomVisible, boolean roomReopened) {}
}
