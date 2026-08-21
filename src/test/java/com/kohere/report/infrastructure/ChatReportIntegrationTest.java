package com.kohere.report.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.TestcontainersConfiguration;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.report.application.ReportCreationResult;
import com.kohere.report.application.ReportService;
import com.kohere.report.domain.ReportReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL에서 채팅 원문 캡처부터 신고·증거 저장과 중복 수렴까지 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatReportIntegrationTest {

  private static final long TENANT_ID = 801L;
  private static final long LANDLORD_ID = 842L;

  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository memberRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private ReportService reportService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 사용자가 이전에 숨긴 원문과 BOOKING_CARD는 제외하고 최신 TEXT 20개만 시간순으로 보관하며, 재요청은 같은 신고를 반환하는지 확인한다. */
  @Test
  @DisplayName("채팅방 신고는 현재 보이는 최근 TEXT 20개와 함께 한 번만 저장된다")
  void reportStoresVisibleRecentTextEvidenceOnce() {
    ChatRoom room = createRoomWithTwoMembers();

    Message oldHiddenText = saveText(room.getId(), TENANT_ID, "old-hidden", 1);
    saveBookingCard(room.getId(), 5001L);

    // 방은 현재 보이지만 과거 삭제 경계는 유지되는 재진입 상태를 재현한다.
    ChatRoomMember tenant =
        memberRepository.findByChatRoomIdAndUserId(room.getId(), TENANT_ID).orElseThrow();
    memberRepository.save(
        tenant.toBuilder()
            .roomHiddenAt(null)
            .historyHiddenThroughMessageId(oldHiddenText.getId())
            .updatedAt(Instant.parse("2026-08-22T09:05:00Z"))
            .build());

    // 22개를 저장해 최근 20개 제한과 오래된 두 건 제외를 실제 SQL LIMIT로 확인한다.
    Message newest = null;
    for (int index = 1; index <= 22; index++) {
      newest =
          saveText(
              room.getId(), index % 2 == 0 ? TENANT_ID : LANDLORD_ID, "new-" + index, index + 10);
    }

    ReportCreationResult created =
        reportService.createChatRoomReport(TENANT_ID, room.getId(), ReportReason.SPAM);
    ReportCreationResult duplicate =
        reportService.createChatRoomReport(TENANT_ID, room.getId(), ReportReason.OTHER);

    assertThat(created.created()).isTrue();
    assertThat(duplicate.created()).isFalse();
    assertThat(duplicate.report().getId()).isEqualTo(created.report().getId());
    // 두 번째 요청의 다른 사유로 최초 신고를 덮어쓰지 않는다.
    assertThat(duplicate.report().getReason()).isEqualTo(ReportReason.SPAM);
    assertThat(created.report().getReportedUserId()).isEqualTo(LANDLORD_ID);
    assertThat(created.report().getEvidenceThroughMessageId()).isEqualTo(newest.getId());

    Integer reportCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_reports", Integer.class);
    Integer evidenceCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_report_evidence", Integer.class);
    Integer snapshotMessageCount =
        jdbcTemplate.queryForObject(
            "SELECT JSON_LENGTH(snapshot, '$.messages') FROM chat_report_evidence", Integer.class);
    String firstStoredContent =
        jdbcTemplate.queryForObject(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.messages[0].originalContent')) "
                + "FROM chat_report_evidence",
            String.class);
    String hash =
        jdbcTemplate.queryForObject("SELECT content_hash FROM chat_report_evidence", String.class);
    Boolean oneCalendarYear =
        jdbcTemplate.queryForObject(
            "SELECT retention_expires_at = DATE_ADD(received_at, INTERVAL 1 YEAR) "
                + "FROM chat_reports",
            Boolean.class);

    assertThat(reportCount).isEqualTo(1);
    assertThat(evidenceCount).isEqualTo(1);
    assertThat(snapshotMessageCount).isEqualTo(20);
    // new-1, new-2는 최근 20개 밖이고 old-hidden과 카드는 애초에 증거 대상이 아니다.
    assertThat(firstStoredContent).isEqualTo("new-3");
    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(oneCalendarYear).isTrue();
  }

  /** 실제 서비스와 같은 공유 방 한 행과 사용자별 참여자 두 행을 준비한다. */
  private ChatRoom createRoomWithTwoMembers() {
    Instant now = Instant.parse("2026-08-22T09:00:00Z");
    ChatRoom room =
        chatRoomRepository.save(
            ChatRoom.builder()
                .listingId("68a700000000000000000260")
                .tenantId(TENANT_ID)
                .landlordId(LANDLORD_ID)
                .category(ChatCategory.LANDLORD)
                .listingSnapshot(new ListingSnapshot("Hongdae Studio", "Mapo-gu"))
                .createdAt(now)
                .updatedAt(now)
                .build());

    memberRepository.saveAll(
        List.of(
            member(room.getId(), TENANT_ID, LANDLORD_ID, ChatParticipantRole.TENANT, now),
            member(room.getId(), LANDLORD_ID, TENANT_ID, ChatParticipantRole.LANDLORD, now)));
    return room;
  }

  /** 최근 증거 순서를 확실히 비교할 수 있도록 각 메시지에 서로 다른 저장 시각과 UUID를 사용한다. */
  private Message saveText(long roomId, long senderId, String content, int seconds) {
    return messageRepository.save(
        Message.builder()
            .chatRoomId(roomId)
            .senderId(senderId)
            .type(MessageType.TEXT)
            .content(content)
            .clientMessageId(UUID.randomUUID())
            .sentAt(Instant.parse("2026-08-22T09:10:00Z").plusSeconds(seconds))
            .build());
  }

  /** 카드가 TEXT 사이에 있어도 신고 증거 SQL이 처음부터 제외하는지 확인할 최소 신청 스냅샷을 만든다. */
  private void saveBookingCard(long roomId, long bookingId) {
    BookingCardPayload payload =
        new BookingCardPayload(
            bookingId,
            new BookingCardPayload.Listing(
                "68a700000000000000000260",
                "https://example.com/room.jpg",
                "Hongdae Studio",
                "Mapo-gu",
                420_000),
            new BookingCardPayload.Applicant(
                TENANT_ID, "Gil Dong Hong", "M", "US", "United States", "tenant@example.com"),
            "room-offer-1",
            "Room A",
            LocalDate.of(2026, 9, 15),
            3,
            0,
            1_260_000);

    messageRepository.save(
        Message.builder()
            .chatRoomId(roomId)
            .type(MessageType.BOOKING_CARD)
            .bookingId(bookingId)
            .payload(payload)
            .sentAt(Instant.parse("2026-08-22T09:10:05Z"))
            .build());
  }

  private static ChatRoomMember member(
      long roomId, long userId, long counterpartId, ChatParticipantRole role, Instant now) {
    return ChatRoomMember.builder()
        .chatRoomId(roomId)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
