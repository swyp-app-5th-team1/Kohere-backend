package com.kohere.chat.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kohere.TestcontainersConfiguration;
import com.kohere.booking.BookingCreatedEvent;
import com.kohere.booking.application.BookingService;
import com.kohere.booking.presentation.dto.BookingRequest;
import com.kohere.chat.application.BookingCardService;
import com.kohere.chat.application.ChatMessageCreatedEventPublisher;
import com.kohere.chat.application.ChatMessageHistoryService;
import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.common.response.CursorResponse;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.user.api.ApplicantProfileView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예약 커밋 뒤 Spring Modulith 이벤트가 동일 1:1 채팅방에 BOOKING_CARD를 한 번만 저장하는지 실제 MySQL로 검증한다.
 *
 * <p>listing·user 모듈의 공개 조회는 mock으로 고정하지만 예약, event publication, 채팅방, 참여자, 메시지는 모두 실제 JPA/Flyway
 * 경로를 사용한다. 따라서 단순 메서드 호출 테스트가 놓칠 수 있는 JSON 직렬화, 비동기 listener, 트랜잭션과 UNIQUE 제약을 함께 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BookingCardIntegrationTest {

  private static final String LISTING_ID = "6858e2000000000000000001";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000abc";
  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;

  @Autowired private BookingService bookingService;
  @Autowired private ChatService chatService;
  @Autowired private BookingCardService bookingCardService;
  @Autowired private ChatMessageHistoryService messageHistoryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository memberRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private BookingListingQueryService bookingListingQueryService;
  @MockitoBean private ChatListingQueryService chatListingQueryService;
  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private UserBlockService userBlockService;
  @MockitoBean private ChatMessageCreatedEventPublisher pushEventPublisher;

  /** 비동기 테스트가 남긴 행을 no-FK 삭제 순서로 비우고 모든 외부 협력 응답을 한곳에서 준비한다. */
  @BeforeEach
  void cleanAndPrepareCollaborators() {
    // 앞 테스트가 실패로 중단됐어도 listener는 계속 돌기 때문에, 먼저 비우고 뒤늦은 INSERT를 맞으면 이 테스트가 남의 행을
    // 세게 된다. 남은 publication이 빌 때까지 잠깐 기다리되, 여기서 실패시키면 원인 테스트가 아니라 다음 테스트가
    // 빨간불이 되므로 시간이 지나면 그냥 정리를 진행한다.
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> count("event_publication") == 0);
    } catch (ConditionTimeoutException ignored) {
      // 정리를 계속한다.
    }

    jdbcTemplate.update("DELETE FROM event_publication");
    jdbcTemplate.update("DELETE FROM chat_messages");
    jdbcTemplate.update("DELETE FROM chat_room_members");
    jdbcTemplate.update("DELETE FROM chat_rooms");
    jdbcTemplate.update("DELETE FROM bookings");

    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(userAccountService.getApplicantProfile(TENANT_ID)).willReturn(applicant());
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(false);
    given(bookingListingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offer()));
    given(bookingListingQueryService.findRoomOfferForExistingBooking(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offer()));
    given(chatListingQueryService.findPublishedListing(LISTING_ID))
        .willReturn(
            Optional.of(
                new ChatListingView(
                    LISTING_ID,
                    LANDLORD_ID,
                    "Hongdae Studio share",
                    "Seogyo-dong, Mapo-gu",
                    "https://cdn.kohere.com/listings/inquiry-thumb.jpg",
                    "SEOUL",
                    "MAPO_GU",
                    "CO_LIVING",
                    350_000,
                    500_000)));
  }

  /** 신청만 먼저 해도 서버가 방·참여자·카드를 자동 생성하고 카드 JSON을 신청 시점 값으로 보존한다. */
  @Test
  @DisplayName("예약 커밋 뒤 동일 매물 채팅방과 BOOKING_CARD를 자동 저장한다")
  void createsRoomAndBookingCardAfterBookingCommit() {
    var booking =
        bookingService.createBooking(
            TENANT_ID, LISTING_ID, new BookingRequest(ROOM_OFFER_ID, "2030-01-01", 3));

    awaitCardAndCompletedPublication(booking.bookingId());

    ChatRoom room = room();
    Message card =
        messageRepository
            .findByChatRoomIdAndBookingId(room.getId(), booking.bookingId())
            .orElseThrow();
    BookingCardPayload payload = card.getPayload();

    assertThat(memberRepository.findByChatRoomId(room.getId())).hasSize(2);
    assertThat(card.getType()).isEqualTo(MessageType.BOOKING_CARD);
    assertThat(card.getSenderId()).isNull();
    assertThat(card.getClientMessageId()).isNull();
    assertThat(card.getContent()).isNull();
    assertThat(payload.listing().thumbnailUrl()).endsWith("/thumb.jpg");
    assertThat(payload.listing().monthlyRent()).isEqualTo(500_000);
    assertThat(payload.applicant().name()).isEqualTo("Gil dong Hong");
    assertThat(payload.roomOfferName()).isEqualTo("Room A");
    assertThat(payload.moveInDate()).isEqualTo(LocalDate.of(2030, 1, 1));
    assertThat(payload.contractPeriod()).isEqualTo(3);
    assertThat(payload.deposit()).isEqualTo(5_000_000);
    assertThat(payload.totalAmount()).isEqualTo(6_500_000);
    assertThat(room.getLastMessageId()).isEqualTo(card.getId());
    verify(pushEventPublisher).publish(any(ChatRoom.class), any(Message.class), eq(LANDLORD_ID));
  }

  /** 문의 중이던 기존 roomId를 유지하고 과거 TEXT 뒤에 카드가 추가되는 실제 화면 흐름을 검증한다. */
  @Test
  @DisplayName("기존 문의 대화가 있으면 같은 채팅방의 다음 메시지로 BOOKING_CARD를 추가한다")
  void appendsCardToExistingInquiryHistory() {
    InquiryResponse inquiry = chatService.createInquiry(TENANT_ID, LISTING_ID);
    Message oldText =
        messageRepository.save(
            Message.builder()
                .chatRoomId(inquiry.chatRoomId())
                .senderId(TENANT_ID)
                .type(MessageType.TEXT)
                .content("Is this room available?")
                .clientMessageId(UUID.fromString("b10a74c7-e9b1-42b2-852a-b7cd1ab7d419"))
                .sentAt(Instant.parse("2026-08-20T01:00:00Z"))
                .build());

    var booking =
        bookingService.createBooking(
            TENANT_ID, LISTING_ID, new BookingRequest(ROOM_OFFER_ID, "2030-01-01", 3));
    awaitCardAndCompletedPublication(booking.bookingId());

    ChatRoom room = room();
    assertThat(room.getId()).isEqualTo(inquiry.chatRoomId());
    assertThat(count("chat_rooms")).isEqualTo(1);

    CursorResponse<MessageResponse> history =
        messageHistoryService.getMessages(TENANT_ID, room.getId(), null, null, 30);
    assertThat(history.content())
        .extracting(MessageResponse::type)
        .containsExactly(MessageType.BOOKING_CARD, MessageType.TEXT, MessageType.INQUIRY_CARD);
    assertThat(history.content().get(1).messageId()).isEqualTo(oldText.getId());
  }

  /** Event Publication Registry가 같은 이벤트를 다시 전달해도 bookingId UNIQUE와 선행 확인으로 카드 한 장만 유지한다. */
  @Test
  @DisplayName("같은 예약 이벤트 재처리는 기존 카드 결과만 반환한다")
  void duplicateEventDoesNotCreateSecondCard() {
    BookingCreatedEvent event = event(9001L);

    BookingCardService.ProcessResult first = bookingCardService.process(event);
    BookingCardService.ProcessResult second = bookingCardService.process(event);

    assertThat(first.cardCreated()).isTrue();
    assertThat(second.cardCreated()).isFalse();
    assertThat(second.chatRoomId()).isEqualTo(first.chatRoomId());
    assertThat(second.messageId()).isEqualTo(first.messageId());
    assertThat(count("chat_messages")).isEqualTo(1);
    assertThat(room().getLastMessageId()).isEqualTo(first.messageId());
    verify(pushEventPublisher, times(1))
        .publish(any(ChatRoom.class), any(Message.class), eq(LANDLORD_ID));
  }

  /**
   * 비동기 카드 저장과 publication 완료 삭제가 끝날 때까지만 기다린다. 지속 polling 기능을 구현하는 코드가 아니다.
   *
   * <p>방 조회는 {@code orElseThrow}가 아니라 {@code isPresent} 단언으로 확인한다. Awaitility의 {@code
   * untilAsserted}는 AssertionError만 재시도하고 그 밖의 예외는 그대로 던지므로, 첫 polling(기본 100ms) 시점에 아직 방이 없으면
   * NoSuchElementException으로 즉시 실패했다. 느린 CI에서 이 테스트가 깨졌고, 중단된 뒤에도 listener가 계속 돌아 다음 테스트의
   * 정리(DELETE) 뒤에 행을 다시 넣는 오염까지 일으켰다.
   */
  private void awaitCardAndCompletedPublication(long bookingId) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Optional<ChatRoom> room = findRoom();
              assertThat(room).isPresent();
              assertThat(
                      messageRepository.findByChatRoomIdAndBookingId(room.get().getId(), bookingId))
                  .isPresent();
              assertThat(count("event_publication")).isZero();
            });
  }

  private ChatRoom room() {
    return findRoom().orElseThrow();
  }

  private Optional<ChatRoom> findRoom() {
    return chatRoomRepository.findByListingIdAndTenantIdAndLandlordId(
        LISTING_ID, TENANT_ID, LANDLORD_ID);
  }

  private long count(String table) {
    Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return value == null ? 0 : value;
  }

  private static RoomOfferBookingView offer() {
    return new RoomOfferBookingView(
        LISTING_ID,
        ROOM_OFFER_ID,
        "Hongdae Studio share",
        "https://cdn.kohere.com/listings/" + LISTING_ID + "/thumb.jpg",
        "Seogyo-dong, Mapo-gu",
        "Room A",
        5_000_000,
        500_000,
        LANDLORD_ID);
  }

  private static ApplicantProfileView applicant() {
    return new ApplicantProfileView(
        TENANT_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com");
  }

  private static BookingCreatedEvent event(long bookingId) {
    return new BookingCreatedEvent(
        UUID.fromString("61ee3bde-2015-4317-9d68-460955520154"),
        bookingId,
        LISTING_ID,
        TENANT_ID,
        LANDLORD_ID,
        Instant.parse("2026-08-20T01:02:03Z"),
        new BookingCreatedEvent.CardSnapshot(
            new BookingCreatedEvent.ListingSnapshot(
                LISTING_ID,
                "https://cdn.kohere.com/listings/" + LISTING_ID + "/thumb.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                500_000),
            new BookingCreatedEvent.ApplicantSnapshot(
                TENANT_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com"),
            ROOM_OFFER_ID,
            "Room A",
            LocalDate.of(2030, 1, 1),
            3,
            5_000_000,
            6_500_000));
  }
}
