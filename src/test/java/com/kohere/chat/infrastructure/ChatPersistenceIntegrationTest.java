package com.kohere.chat.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.chat.application.ChatTextMessageService;
import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberPage;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.user.api.UserBlockService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway V24와 채팅 JPA 어댑터를 실제 MySQL 8에서 함께 검증하는 통합 테스트다.
 *
 * <p>H2 같은 대체 DB는 MySQL JSON, BINARY(16), CHECK와 nullable UNIQUE 동작을 정확히 재현하지 못하므로 Testcontainers를
 * 사용한다. {@link DataJpaTest}가 각 테스트를 롤백해 행은 격리하고, 컨텍스트 시작 시 Flyway 전체 적용 뒤 {@code
 * ddl-auto=validate}가 엔티티와 스키마를 비교한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
  ChatRoomRepositoryImpl.class,
  ChatRoomMemberRepositoryImpl.class,
  MessageRepositoryImpl.class,
  ChatTextMessageService.class
})
@Testcontainers
class ChatPersistenceIntegrationTest {

  /** 실제 운영과 같은 MySQL 8 문법·제약을 사용하도록 테스트 데이터소스를 제공한다. */
  @Container @ServiceConnection
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

  /** 도메인 채팅방을 JPA 엔티티 노출 없이 저장·복원하는 포트다. */
  @Autowired private ChatRoomRepository chatRoomRepository;

  /** 사용자별 역할·가시성 행을 저장·복원하는 포트다. */
  @Autowired private ChatRoomMemberRepository memberRepository;

  /** TEXT와 BOOKING_CARD 정본을 저장·범위 조회하는 포트다. */
  @Autowired private MessageRepository messageRepository;

  /** 실제 JPA 포트를 하나의 TEXT 저장 트랜잭션으로 묶는 응용 서비스다. */
  @Autowired private ChatTextMessageService textMessageService;

  /** 이 통합 테스트는 chat 저장 원자성에 집중하므로 user 모듈의 차단 조회 경계만 대체한다. */
  @MockitoBean private UserBlockService userBlockService;

  /** CHECK 제약을 의도적으로 위반하는 SQL과 물리 타입 확인에만 사용하는 테스트 도구다. */
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 저장 직후 1차 캐시를 비워 JSON과 UUID를 실제 MySQL 행에서 다시 읽는 데 사용한다. */
  @Autowired private EntityManager entityManager;

  /** 방·두 참여자·두 메시지를 저장한 뒤 JSON, UUID와 cursor 정렬까지 손실 없이 복원되는지 확인한다. */
  @Test
  void storesAndRestoresRoomMembersTextAndBookingCard() {
    Instant createdAt = now();
    ChatRoom savedRoom = chatRoomRepository.save(newRoom("64f000000000000000000001", createdAt));

    // 방 생성 트랜잭션은 실제 서비스에서 두 행을 함께 저장한다. 여기서는 포트가 두 도메인 값을 모두 복원하는지 확인한다.
    List<ChatRoomMember> savedMembers =
        memberRepository.saveAll(
            List.of(
                newMember(savedRoom.getId(), 101L, 202L, ChatParticipantRole.TENANT, createdAt),
                newMember(savedRoom.getId(), 202L, 101L, ChatParticipantRole.LANDLORD, createdAt)));

    UUID clientMessageId = UUID.randomUUID();
    Message savedText =
        messageRepository.save(
            newText(savedRoom.getId(), 101L, clientMessageId, "Is the room available?", createdAt));
    Message savedCard =
        messageRepository.save(newBookingCard(savedRoom.getId(), 9001L, createdAt.plusSeconds(1)));

    // INSERT를 실제 DB에 반영한 뒤 영속성 컨텍스트를 비워야 findById가 같은 Java 객체를 되돌리지 않고
    // MySQL JSON/BINARY(16)를 다시 역직렬화한다.
    entityManager.flush();
    entityManager.clear();

    ChatRoom storedRoom = chatRoomRepository.findById(savedRoom.getId()).orElseThrow();
    Message storedText = messageRepository.findById(savedText.getId()).orElseThrow();
    Message storedCard = messageRepository.findById(savedCard.getId()).orElseThrow();

    assertThat(storedRoom.getListingSnapshot())
        .isEqualTo(new ListingSnapshot("Hongdae Studio", "Seogyo-dong, Mapo-gu"));
    assertThat(savedMembers)
        .extracting(ChatRoomMember::getRole)
        .containsExactly(ChatParticipantRole.TENANT, ChatParticipantRole.LANDLORD);
    assertThat(memberRepository.findByChatRoomId(savedRoom.getId())).hasSize(2);

    // UUID는 문자열 변환을 거치지 않고도 같은 Java 값으로 왕복해야 멱등 재조회가 정확히 동작한다.
    assertThat(storedText.getClientMessageId()).isEqualTo(clientMessageId);
    assertThat(
            messageRepository.findByChatRoomIdAndSenderIdAndClientMessageId(
                savedRoom.getId(), 101L, clientMessageId))
        .get()
        .extracting(Message::getId)
        .isEqualTo(storedText.getId());
    assertThat(storedCard.getPayload()).isEqualTo(bookingPayload(9001L));
    assertThat(messageRepository.findByChatRoomIdAndBookingId(savedRoom.getId(), 9001L))
        .get()
        .extracting(Message::getId)
        .isEqualTo(storedCard.getId());

    // 과거 이력은 최신순, 재연결 누락은 오래된 순이라는 서로 다른 API 계약을 같은 messageId 인덱스로 만족시킨다.
    assertThat(messageRepository.findBefore(savedRoom.getId(), null, 10))
        .extracting(Message::getId)
        .containsExactly(savedCard.getId(), savedText.getId());
    assertThat(messageRepository.findAfter(savedRoom.getId(), savedText.getId(), 10))
        .extracting(Message::getId)
        .containsExactly(savedCard.getId());
    assertThat(messageRepository.findByIds(List.of(savedCard.getId(), savedText.getId())))
        .extracting(Message::getId)
        .containsExactlyInAnyOrder(savedText.getId(), savedCard.getId());

    // 물리 저장이 BINARY(16)인지 길이를 직접 확인해 Hibernate가 VARCHAR UUID로 조용히 바꾸는 회귀를 막는다.
    Integer storedUuidBytes =
        jdbcTemplate.queryForObject(
            "SELECT OCTET_LENGTH(client_message_id) FROM chat_messages WHERE id = ?",
            Integer.class,
            savedText.getId());
    assertThat(storedUuidBytes).isEqualTo(16);
  }

  /** 숨긴 방은 제외하고 보이는 방만 마지막 활동 시각 순으로 페이지 조회하는지 실제 MySQL 조인으로 확인한다. */
  @Test
  void findsVisibleRoomMembersOrderedByLatestActivity() {
    Instant base = now();
    ChatRoom olderRoom = chatRoomRepository.save(newRoom("64f000000000000000000011", base));
    ChatRoom activeRoom =
        chatRoomRepository.save(newRoom("64f000000000000000000012", base.plusSeconds(1)));
    ChatRoom hiddenRoom =
        chatRoomRepository.save(newRoom("64f000000000000000000013", base.plusSeconds(2)));

    memberRepository.save(
        newMember(olderRoom.getId(), 101L, 202L, ChatParticipantRole.TENANT, base));
    memberRepository.save(
        newMember(activeRoom.getId(), 101L, 202L, ChatParticipantRole.TENANT, base));
    ChatRoomMember savedHidden =
        memberRepository.save(
            newMember(hiddenRoom.getId(), 101L, 202L, ChatParticipantRole.TENANT, base));
    jdbcTemplate.update(
        "UPDATE chat_room_members SET room_hidden_at = ? WHERE id = ?",
        base.plusSeconds(3),
        savedHidden.getId());

    ChatRoomMemberPage page = memberRepository.findVisiblePageByUserId(101L, 0, 10);

    assertThat(page.totalElements()).isEqualTo(2L);
    assertThat(page.content())
        .extracting(ChatRoomMember::getChatRoomId)
        .containsExactly(activeRoom.getId(), olderRoom.getId());
  }

  /** DB UNIQUE가 같은 매물·임차인·임대인 조합의 두 번째 방을 막는지 확인한다. */
  @Test
  void rejectsDuplicateRoomBusinessKey() {
    Instant createdAt = now();
    chatRoomRepository.save(newRoom("64f000000000000000000002", createdAt));

    // 서비스의 선조회는 동시 요청에서 경합할 수 있으므로 최종 판정은 반드시 DB UNIQUE가 맡아야 한다.
    assertThatThrownBy(
            () ->
                chatRoomRepository.save(
                    newRoom("64f000000000000000000002", createdAt.plusSeconds(1))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** DB UNIQUE가 같은 방·사용자의 참여자 상태를 두 행으로 분리하지 못하게 하는지 확인한다. */
  @Test
  void rejectsDuplicateMemberInRoom() {
    ChatRoom room = chatRoomRepository.save(newRoom("64f000000000000000000003", now()));
    memberRepository.save(newMember(room.getId(), 101L, 202L, ChatParticipantRole.TENANT, now()));

    assertThatThrownBy(
            () ->
                memberRepository.save(
                    newMember(room.getId(), 101L, 202L, ChatParticipantRole.TENANT, now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 숨김 이력이 없는 신규 참여자 행이 nullable 값이 아니라 안전한 경계 0으로 시작하는지 확인한다. */
  @Test
  void defaultsHistoryHiddenBoundaryToZero() {
    Instant createdAt = now();

    // 컬럼을 INSERT에서 의도적으로 생략해 JPA의 primitive 기본값이 아니라 Flyway DEFAULT 자체를 검증한다.
    jdbcTemplate.update(
        """
        INSERT INTO chat_room_members
          (chat_room_id, user_id, counterpart_id, member_role, created_at, updated_at)
        VALUES (?, ?, ?, 'TENANT', ?, ?)
        """,
        999L,
        101L,
        202L,
        createdAt,
        createdAt);

    Long boundary =
        jdbcTemplate.queryForObject(
            """
            SELECT history_hidden_through_message_id
            FROM chat_room_members
            WHERE chat_room_id = ? AND user_id = ?
            """,
            Long.class,
            999L,
            101L);

    // 0이면 조회 조건을 단순히 message_id > boundary로 쓸 수 있고 NULL 비교의 UNKNOWN으로 전체 이력이 사라지지 않는다.
    assertThat(boundary).isZero();
  }

  /** 같은 방·발신자·clientMessageId의 TEXT 재전송이 두 행으로 저장되지 않는지 확인한다. */
  @Test
  void rejectsDuplicateTextIdempotencyKey() {
    ChatRoom room = chatRoomRepository.save(newRoom("64f000000000000000000004", now()));
    UUID clientMessageId = UUID.randomUUID();
    messageRepository.save(newText(room.getId(), 101L, clientMessageId, "first", now()));

    // 본문이 달라도 멱등 키가 같으면 두 번째 원문을 만들지 않는다. 후속 서비스는 기존 본문과 비교해 충돌 응답을 결정한다.
    assertThatThrownBy(
            () ->
                messageRepository.save(
                    newText(room.getId(), 101L, clientMessageId, "different", now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 실제 MySQL 트랜잭션에서 신규·중복 재시도가 한 행으로 수렴하고 사용자별 숨김 경계가 유지되는지 확인한다. */
  @Test
  void savesTextExactlyOnceAndReopensOnlyRecipientVisibility() {
    Instant createdAt = now();
    ChatRoom room = chatRoomRepository.save(newRoom("64f000000000000000000014", createdAt));
    memberRepository.save(
        newMember(room.getId(), 101L, 202L, ChatParticipantRole.TENANT, createdAt));

    // 실제 시나리오처럼 삭제가 먼저 일어나고 그 뒤 서비스가 새 메시지 시각을 만들도록 과거 시각을 사용한다.
    Instant deletedAt = createdAt.minusSeconds(1);
    ChatRoomMember hiddenRecipient =
        newMember(room.getId(), 202L, 101L, ChatParticipantRole.LANDLORD, createdAt).toBuilder()
            .roomHiddenAt(deletedAt)
            .historyHiddenThroughMessageId(400L)
            .deleteRequestedAt(deletedAt)
            .updatedAt(deletedAt)
            .build();
    memberRepository.save(hiddenRecipient);

    UUID clientMessageId = UUID.randomUUID();
    TextMessageSaveResult first =
        textMessageService.saveText(room.getId(), 101L, clientMessageId, "new message");
    TextMessageSaveResult retry =
        textMessageService.saveText(room.getId(), 101L, clientMessageId, "new message");

    entityManager.flush();
    entityManager.clear();

    assertThat(first.duplicate()).isFalse();
    assertThat(first.recipientRoomReopened()).isTrue();
    assertThat(retry.duplicate()).isTrue();
    assertThat(retry.message().getId()).isEqualTo(first.message().getId());
    assertThat(messageRepository.findBefore(room.getId(), null, 10))
        .extracting(Message::getId)
        .containsExactly(first.message().getId());
    assertThat(chatRoomRepository.findById(room.getId()).orElseThrow().getLastMessageId())
        .isEqualTo(first.message().getId());

    ChatRoomMember recipient =
        memberRepository.findByChatRoomIdAndUserId(room.getId(), 202L).orElseThrow();
    assertThat(recipient.getRoomHiddenAt()).isNull();
    assertThat(recipient.getHistoryHiddenThroughMessageId()).isEqualTo(400L);
    assertThat(recipient.getDeleteRequestedAt()).isEqualTo(deletedAt);
  }

  /** 같은 방·bookingId의 신청 이벤트 재처리가 카드를 중복 저장하지 않는지 확인한다. */
  @Test
  void rejectsDuplicateBookingCard() {
    ChatRoom room = chatRoomRepository.save(newRoom("64f000000000000000000005", now()));
    messageRepository.save(newBookingCard(room.getId(), 9002L, now()));

    assertThatThrownBy(() -> messageRepository.save(newBookingCard(room.getId(), 9002L, now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 카드 컬럼과 JSON payload가 서로 다른 신청을 가리키는 잘못된 도메인 객체를 SQL 실행 전에 거부하는지 확인한다. */
  @Test
  void rejectsBookingCardWhosePayloadHasDifferentBookingId() {
    BookingCardPayload mismatchedPayload = bookingPayload(9005L);

    assertThatThrownBy(
            () ->
                Message.builder()
                    .chatRoomId(999L)
                    .type(MessageType.BOOKING_CARD)
                    .bookingId(9004L)
                    .payload(mismatchedPayload)
                    .sentAt(now())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bookingId");
  }

  /** TEXT에 필요한 발신자·원문·UUID가 빠지거나 카드 필드가 섞이면 DB CHECK가 거부하는지 확인한다. */
  @Test
  void rejectsInvalidTextFieldCombinationAndTooLongContent() {
    Instant sentAt = now();

    // sender_id가 없는 TEXT는 사용자가 아닌 서버 메시지처럼 보일 수 있으므로 타입별 CHECK가 막아야 한다.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO chat_messages
                      (chat_room_id, sender_id, type, content, payload, booking_id, client_message_id, sent_at)
                    VALUES (?, NULL, 'TEXT', ?, NULL, NULL, UUID_TO_BIN(?), ?)
                    """,
                    999L,
                    "hello",
                    UUID.randomUUID().toString(),
                    sentAt))
        .isInstanceOf(DataAccessException.class);

    // HTTP/STOMP 검증을 우회해도 원문 정본은 최대 3,000자를 넘을 수 없다.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO chat_messages
                      (chat_room_id, sender_id, type, content, payload, booking_id, client_message_id, sent_at)
                    VALUES (?, ?, 'TEXT', ?, NULL, NULL, UUID_TO_BIN(?), ?)
                    """,
                    999L,
                    101L,
                    "가".repeat(3001),
                    UUID.randomUUID().toString(),
                    sentAt))
        .isInstanceOf(DataAccessException.class);
  }

  /** BOOKING_CARD에 발신자·원문·UUID가 섞이거나 bookingId·payload가 빠지면 DB CHECK가 거부하는지 확인한다. */
  @Test
  void rejectsInvalidBookingCardFieldCombination() {
    // 서버 카드에 sender_id가 있으면 사용자가 카드를 보낸 것처럼 해석될 수 있으므로 DB에서도 허용하지 않는다.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO chat_messages
                      (chat_room_id, sender_id, type, content, payload, booking_id, client_message_id, sent_at)
                    VALUES (?, ?, 'BOOKING_CARD', NULL, JSON_OBJECT('bookingId', ?), ?, NULL, ?)
                    """,
                    999L,
                    101L,
                    9003L,
                    9003L,
                    now()))
        .isInstanceOf(DataAccessException.class);
  }

  /** 테스트 비교와 DATETIME(6) 왕복이 일치하도록 현재 시각을 MySQL 마이크로초 정밀도로 맞춘다. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** 고유 매물 ID와 시각을 받아 메시지가 없는 신규 채팅방 테스트 fixture를 만든다. */
  private static ChatRoom newRoom(String listingId, Instant createdAt) {
    return ChatRoom.builder()
        .listingId(listingId)
        .tenantId(101L)
        .landlordId(202L)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio", "Seogyo-dong, Mapo-gu"))
        .createdAt(createdAt)
        .updatedAt(createdAt)
        .build();
  }

  /** 역할에 맞는 사용자별 방 상태 테스트 fixture를 만든다. */
  private static ChatRoomMember newMember(
      Long chatRoomId,
      Long userId,
      Long counterpartId,
      ChatParticipantRole role,
      Instant createdAt) {
    return ChatRoomMember.builder()
        .chatRoomId(chatRoomId)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .createdAt(createdAt)
        .updatedAt(createdAt)
        .build();
  }

  /** 프런트 UUID와 사용자 원문을 가진 TEXT 테스트 fixture를 만든다. */
  private static Message newText(
      Long chatRoomId, Long senderId, UUID clientMessageId, String content, Instant sentAt) {
    return Message.builder()
        .chatRoomId(chatRoomId)
        .senderId(senderId)
        .type(MessageType.TEXT)
        .content(content)
        .clientMessageId(clientMessageId)
        .sentAt(sentAt)
        .build();
  }

  /** 서버가 신청 데이터로 만든 BOOKING_CARD 테스트 fixture를 만든다. */
  private static Message newBookingCard(Long chatRoomId, Long bookingId, Instant sentAt) {
    return Message.builder()
        .chatRoomId(chatRoomId)
        .type(MessageType.BOOKING_CARD)
        .bookingId(bookingId)
        .payload(bookingPayload(bookingId))
        .sentAt(sentAt)
        .build();
  }

  /** JSON 왕복을 확인할 수 있도록 nullable 이미지와 신청자 개인정보를 포함한 카드 payload를 만든다. */
  private static BookingCardPayload bookingPayload(Long bookingId) {
    return new BookingCardPayload(
        bookingId,
        new BookingCardPayload.Listing(
            "64f000000000000000000001", null, "Hongdae Studio", "Seogyo-dong, Mapo-gu", 420_000),
        new BookingCardPayload.Applicant(
            101L, "Gil dong Hong", "MALE", "MN", "Mongolia", "tenant@example.com"),
        "room-a",
        "Room A",
        LocalDate.of(2026, 6, 15),
        3,
        0,
        1_260_000);
  }
}
