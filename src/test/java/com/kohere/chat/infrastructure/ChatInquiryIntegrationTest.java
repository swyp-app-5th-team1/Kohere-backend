package com.kohere.chat.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kohere.chat.application.ChatRoomCreator;
import com.kohere.chat.application.ChatRoomEnsurer;
import com.kohere.chat.application.ChatRoomSeed;
import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.InquiryCardRealtimePublisher;
import com.kohere.chat.application.InquiryCardWriter;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatListingUnavailableException;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatTenantOnlyException;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.SelfInquiryNotAllowedException;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 문의하기 유스케이스가 실제 MySQL에서 방과 참여자를 원자적으로 만들고 동시 요청을 하나의 방으로 수렴시키는지 검증한다.
 *
 * <p>listing·user 모듈과의 협력은 공개 API mock으로 대체한다. 이 테스트의 관심사는 chat 모듈의 검증 순서, 트랜잭션, UNIQUE 충돌 복구와 사용자별
 * 재표시 상태다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  ChatRoomRepositoryImpl.class,
  ChatRoomMemberRepositoryImpl.class,
  MessageRepositoryImpl.class,
  ChatRoomCreator.class,
  ChatRoomEnsurer.class,
  InquiryCardWriter.class,
  ChatService.class
})
class ChatInquiryIntegrationTest {

  private static final String LISTING_ID = "6858e2000000000000000001";
  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;

  @Container @ServiceConnection
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

  @Autowired private ChatService chatService;
  @Autowired private ChatRoomCreator roomCreator;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository memberRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatListingQueryService listingQueryService;
  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private UserBlockService userBlockService;
  @MockitoBean private InquiryCardRealtimePublisher inquiryCardRealtimePublisher;

  /** 동시성 테스트가 커밋한 행도 다음 테스트에 남지 않도록 no-FK 삭제 순서로 직접 비운다. */
  @BeforeEach
  void cleanAndPrepareMocks() {
    jdbcTemplate.update("DELETE FROM chat_messages");
    jdbcTemplate.update("DELETE FROM chat_room_members");
    jdbcTemplate.update("DELETE FROM chat_rooms");
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedListing(LISTING_ID))
        .willReturn(Optional.of(listingView(LANDLORD_ID)));
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(false);
  }

  /** 새 문의는 공유 방·참여자 두 명·문의서 첫 메시지를 같은 트랜잭션으로 저장한다. */
  @Test
  @DisplayName("신규 문의는 채팅방과 임차인·임대인 참여자를 저장한다")
  void createsRoomAndTwoMembers() {
    InquiryResponse response = chatService.createInquiry(TENANT_ID, LISTING_ID);

    assertThat(response.created()).isTrue();
    ChatRoom room = chatRoomRepository.findById(response.chatRoomId()).orElseThrow();
    assertThat(room.getListingId()).isEqualTo(LISTING_ID);
    assertThat(room.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(room.getLandlordId()).isEqualTo(LANDLORD_ID);
    assertThat(room.getListingSnapshot().title()).isEqualTo("Hongdae Studio share");
    assertThat(room.getListingSnapshot().address()).isEqualTo("Seogyo-dong, Mapo-gu");

    List<ChatRoomMember> members = memberRepository.findByChatRoomId(room.getId());
    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(ChatRoomMember::getRole)
        .containsExactly(ChatParticipantRole.TENANT, ChatParticipantRole.LANDLORD);
    assertThat(members)
        .extracting(ChatRoomMember::getUserId)
        .containsExactly(TENANT_ID, LANDLORD_ID);
    assertThat(members)
        .extracting(ChatRoomMember::getCounterpartId)
        .containsExactly(LANDLORD_ID, TENANT_ID);

    Message inquiryCard = messageRepository.findBefore(room.getId(), null, 10).getFirst();
    assertThat(inquiryCard.getType()).isEqualTo(MessageType.INQUIRY_CARD);
    assertThat(inquiryCard.getSenderId()).isNull();
    assertThat(inquiryCard.getContent()).isNull();
    assertThat(inquiryCard.getClientMessageId()).isNull();
    assertThat(inquiryCard.getInquiryPayload().thumbnailUrl())
        .isEqualTo("https://cdn.kohere.com/listings/inquiry-thumb.jpg");
    assertThat(inquiryCard.getInquiryPayload().listingType()).isEqualTo("CO_LIVING");
    assertThat(inquiryCard.getInquiryPayload().monthlyRentMin()).isEqualTo(350_000);
    assertThat(inquiryCard.getInquiryPayload().monthlyRentMax()).isEqualTo(500_000);
    assertThat(room.getLastMessageId()).isEqualTo(inquiryCard.getId());
    verify(inquiryCardRealtimePublisher).publishNewInquiryCard(any());
  }

  /** 같은 요청을 반복하면 새 행을 만들지 않고 최초 roomId를 200 응답용 결과로 돌려준다. */
  @Test
  @DisplayName("반복 문의는 기존 채팅방을 반환한다")
  void repeatedInquiryReturnsExistingRoom() {
    InquiryResponse first = chatService.createInquiry(TENANT_ID, LISTING_ID);
    InquiryResponse second = chatService.createInquiry(TENANT_ID, LISTING_ID);

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.chatRoomId()).isEqualTo(first.chatRoomId());
    assertThat(count("chat_rooms")).isEqualTo(1);
    assertThat(count("chat_room_members")).isEqualTo(2);
    assertThat(count("chat_messages")).isEqualTo(1);
    verify(inquiryCardRealtimePublisher, times(1)).publishNewInquiryCard(any());
  }

  /** 신청하기로 먼저 만들어진 같은 방에도 문의하기를 누르면 첫 문의서를 이어서 저장한다. */
  @Test
  @DisplayName("신청서가 먼저 있는 기존 방에 문의하면 문의서를 저장한다")
  void inquiryAfterBookingCardStoresInquiryCard() {
    Instant createdAt = Instant.parse("2026-08-21T10:00:00Z");
    ChatRoom room =
        roomCreator.create(
            new ChatRoomSeed(
                LISTING_ID, LANDLORD_ID, "Hongdae Studio share", "Seogyo-dong, Mapo-gu"),
            TENANT_ID,
            createdAt);
    appendBookingCard(room.getId(), 9001L, createdAt.plusSeconds(1));

    InquiryResponse response = chatService.createInquiry(TENANT_ID, LISTING_ID);

    assertThat(response.created()).isFalse();
    assertThat(response.chatRoomId()).isEqualTo(room.getId());
    assertThat(messageRepository.findBefore(room.getId(), null, 10))
        .extracting(Message::getType)
        .containsExactly(MessageType.INQUIRY_CARD, MessageType.BOOKING_CARD);
    verify(inquiryCardRealtimePublisher).publishNewInquiryCard(any());
  }

  /** 문의서 뒤에 일반 대화가 생기면 다음 문의 진입은 새 문의서를 현재 대화 끝에 추가한다. */
  @Test
  @DisplayName("문의서 뒤에 TEXT가 있으면 재문의서를 저장한다")
  void inquiryAfterTextStoresAnotherInquiryCard() {
    InquiryResponse first = chatService.createInquiry(TENANT_ID, LISTING_ID);
    appendText(first.chatRoomId(), LANDLORD_ID, "문의서 뒤의 답변", Instant.now());

    InquiryResponse second = chatService.createInquiry(TENANT_ID, LISTING_ID);

    assertThat(second.created()).isFalse();
    assertThat(messageRepository.findBefore(first.chatRoomId(), null, 10))
        .extracting(Message::getType)
        .containsExactly(MessageType.INQUIRY_CARD, MessageType.TEXT, MessageType.INQUIRY_CARD);
    verify(inquiryCardRealtimePublisher, times(2)).publishNewInquiryCard(any());
  }

  /** 문의서 뒤에 신청서가 저장된 경우도 다른 활동이 생긴 것이므로 다시 문의할 때 새 문의서를 추가한다. */
  @Test
  @DisplayName("문의서 뒤에 BOOKING_CARD가 있으면 재문의서를 저장한다")
  void inquiryAfterBookingStoresAnotherInquiryCard() {
    InquiryResponse first = chatService.createInquiry(TENANT_ID, LISTING_ID);
    appendBookingCard(first.chatRoomId(), 9002L, Instant.now());

    InquiryResponse second = chatService.createInquiry(TENANT_ID, LISTING_ID);

    assertThat(second.created()).isFalse();
    assertThat(messageRepository.findBefore(first.chatRoomId(), null, 10))
        .extracting(Message::getType)
        .containsExactly(
            MessageType.INQUIRY_CARD, MessageType.BOOKING_CARD, MessageType.INQUIRY_CARD);
    verify(inquiryCardRealtimePublisher, times(2)).publishNewInquiryCard(any());
  }

  /** 직접 재문의는 새 문의서를 저장해 방을 다시 표시하고 사용자가 이미 숨긴 과거 메시지 경계와 삭제 시각은 보존한다. */
  @Test
  @DisplayName("숨긴 기존 방에 다시 문의하면 과거 이력 복원 없이 새 문의서를 저장한다")
  void repeatedInquiryShowsHiddenRoomWithoutRestoringHistory() {
    InquiryResponse first = chatService.createInquiry(TENANT_ID, LISTING_ID);
    long firstInquiryId =
        messageRepository.findBefore(first.chatRoomId(), null, 10).getFirst().getId();
    Instant deletedAt = Instant.parse("2026-08-20T01:02:03Z");
    LocalDateTime deletedAtUtc = LocalDateTime.of(2026, 8, 20, 1, 2, 3);
    jdbcTemplate.update(
        """
        UPDATE chat_room_members
        SET room_hidden_at = ?, history_hidden_through_message_id = ?, delete_requested_at = ?
        WHERE chat_room_id = ? AND user_id = ?
        """,
        deletedAtUtc,
        firstInquiryId,
        deletedAtUtc,
        first.chatRoomId(),
        TENANT_ID);

    InquiryResponse second = chatService.createInquiry(TENANT_ID, LISTING_ID);
    ChatRoomMember tenant =
        memberRepository.findByChatRoomIdAndUserId(first.chatRoomId(), TENANT_ID).orElseThrow();

    assertThat(second.created()).isFalse();
    assertThat(tenant.getRoomHiddenAt()).isNull();
    assertThat(tenant.getHistoryHiddenThroughMessageId()).isEqualTo(firstInquiryId);
    assertThat(tenant.getDeleteRequestedAt()).isEqualTo(deletedAt);
    assertThat(messageRepository.findBefore(first.chatRoomId(), null, 10))
        .extracting(Message::getType)
        .containsExactly(MessageType.INQUIRY_CARD, MessageType.INQUIRY_CARD);
    verify(inquiryCardRealtimePublisher, times(2)).publishNewInquiryCard(any());
  }

  /** 선행 조회가 동시에 비어도 DB UNIQUE와 충돌 후 재조회로 모든 요청이 같은 roomId를 받는다. */
  @Test
  @DisplayName("동시 문의도 채팅방 하나와 참여자 두 명만 만든다")
  void concurrentInquiriesConvergeToOneRoom() throws Exception {
    int requestCount = 8;
    CountDownLatch ready = new CountDownLatch(requestCount);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(requestCount);

    try {
      List<Future<InquiryResponse>> futures = new ArrayList<>();
      for (int i = 0; i < requestCount; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 문의 시작 신호를 기다리지 못했습니다.");
                  }
                  return chatService.createInquiry(TENANT_ID, LISTING_ID);
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<InquiryResponse> results = new ArrayList<>();
      for (Future<InquiryResponse> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }

      assertThat(results)
          .extracting(InquiryResponse::chatRoomId)
          .containsOnly(results.getFirst().chatRoomId());
      assertThat(results).filteredOn(InquiryResponse::created).hasSize(1);
      assertThat(count("chat_rooms")).isEqualTo(1);
      assertThat(count("chat_room_members")).isEqualTo(2);
      assertThat(count("chat_messages")).isEqualTo(1);
      verify(inquiryCardRealtimePublisher, times(1)).publishNewInquiryCard(any());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 세입자가 아닌 계정은 매물이나 방 존재 여부를 확인하기 전에 거부한다. */
  @Test
  @DisplayName("임대인 계정은 문의할 수 없다")
  void rejectsNonTenant() {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("LANDLORD");

    assertThatThrownBy(() -> chatService.createInquiry(TENANT_ID, LISTING_ID))
        .isInstanceOf(ChatTenantOnlyException.class);
  }

  /** 없는 매물·비공개 매물은 listing 공개 쿼리가 빈 값으로 표현하고 chat이 404 도메인 오류로 바꾼다. */
  @Test
  @DisplayName("문의 가능한 공개 매물이 없으면 거부한다")
  void rejectsUnavailableListing() {
    given(listingQueryService.findPublishedListing(LISTING_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> chatService.createInquiry(TENANT_ID, LISTING_ID))
        .isInstanceOf(ChatListingUnavailableException.class);
  }

  /** 매물 정본의 소유자와 요청자가 같으면 자기 자신과의 방을 만들지 않는다. */
  @Test
  @DisplayName("본인 소유 매물 문의를 거부한다")
  void rejectsSelfInquiry() {
    given(listingQueryService.findPublishedListing(LISTING_ID))
        .willReturn(Optional.of(listingView(TENANT_ID)));

    assertThatThrownBy(() -> chatService.createInquiry(TENANT_ID, LISTING_ID))
        .isInstanceOf(SelfInquiryNotAllowedException.class);
  }

  /** 어느 방향이든 차단 관계가 있으면 기존 방 조회나 신규 저장 전에 중단한다. */
  @Test
  @DisplayName("차단 관계의 문의를 거부한다")
  void rejectsBlockedInquiry() {
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(true);

    assertThatThrownBy(() -> chatService.createInquiry(TENANT_ID, LISTING_ID))
        .isInstanceOf(ChatUnavailableException.class);
    assertThat(count("chat_rooms")).isZero();
  }

  /** 채팅용 매물 공개 뷰의 fixture를 한곳에서 만들어 검증값이 테스트마다 달라지지 않게 한다. */
  private static ChatListingView listingView(long landlordId) {
    return new ChatListingView(
        LISTING_ID,
        landlordId,
        "Hongdae Studio share",
        "Seogyo-dong, Mapo-gu",
        "https://cdn.kohere.com/listings/inquiry-thumb.jpg",
        "SEOUL",
        "MAPO_GU",
        "CO_LIVING",
        350_000,
        500_000);
  }

  /** 테스트 채팅방의 마지막 메시지 포인터까지 실제 TEXT 저장 결과로 이동시킨다. */
  private void appendText(long roomId, long senderId, String content, Instant sentAt) {
    Message text =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(senderId)
                .type(MessageType.TEXT)
                .content(content)
                .clientMessageId(UUID.randomUUID())
                .sentAt(sentAt)
                .build());
    ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
    chatRoomRepository.save(room.recordMessage(text.getId(), sentAt));
  }

  /** 테스트 채팅방에 신청서 메시지를 저장하고 마지막 메시지 포인터도 같은 카드로 이동시킨다. */
  private void appendBookingCard(long roomId, long bookingId, Instant sentAt) {
    BookingCardPayload payload =
        new BookingCardPayload(
            bookingId,
            new BookingCardPayload.Listing(
                LISTING_ID,
                "https://cdn.kohere.com/listings/booking-thumb.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                420_000),
            new BookingCardPayload.Applicant(
                TENANT_ID, "Tenant", "MALE", "KR", "Korea", "tenant@example.com"),
            "room-offer-1",
            "Room A",
            LocalDate.of(2026, 9, 1),
            3,
            0,
            1_260_000);
    Message bookingCard =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .type(MessageType.BOOKING_CARD)
                .bookingId(bookingId)
                .payload(payload)
                .sentAt(sentAt)
                .build());
    ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
    chatRoomRepository.save(room.recordMessage(bookingCard.getId(), sentAt));
  }

  /** no-FK 테이블의 실제 행 수를 확인해 도메인 반환만 맞고 저장이 중복되는 회귀를 막는다. */
  private long count(String table) {
    Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return value == null ? 0L : value;
  }
}
