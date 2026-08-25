package com.kohere.chat.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.TestcontainersConfiguration;
import com.kohere.chat.application.ChatRoomBlockService;
import com.kohere.chat.application.ChatTextMessageService;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL {@code user_blocks}와 채팅 TEXT 저장을 연결해 room 기반 차단의 전체 결과를 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatRoomBlockIntegrationTest {

  private static final long TENANT_ID = 701L;
  private static final long LANDLORD_ID = 742L;

  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository memberRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private ChatRoomBlockService roomBlockService;
  @Autowired private ChatTextMessageService textMessageService;
  @Autowired private UserBlockService userBlockService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 사용자용 API 허용 목록 게이트가 실제 {@code users} 행을 읽으므로 세입자 한 명을 미리 만든다.
   *
   * <p>이 테스트는 채팅 차단의 저장 결과에 집중하느라 회원을 만들지 않았는데, 게이트가 생기면서 회원 유형 조회가 앞에 붙었다. 목으로 대체하지 않고 실제 행을 넣는 것은
   * 나머지 협력이 전부 실제 저장소이기 때문이다.
   */
  @BeforeEach
  void seedTenantRow() {
    jdbcTemplate.update(
        """
        INSERT INTO users (
            id, nickname, user_type, status,
            terms_of_service_agreed, privacy_policy_agreed, marketing_agreed,
            created_at, updated_at)
        VALUES (?, ?, 'TENANT', 'ACTIVE', true, true, false, NOW(), NOW())
        ON DUPLICATE KEY UPDATE user_type = 'TENANT'
        """,
        TENANT_ID,
        "차단테스트세입자");
  }

  /** roomId만으로 상대를 차단하고, 반복 요청 뒤에도 한 행이며 이후 TEXT는 저장하지 않는지 확인한다. */
  @Test
  @DisplayName("채팅방 차단은 user_blocks 한 행을 만들고 이후 양방향 TEXT를 막는다")
  void roomBlockPersistsOnceAndPreventsTextInBothDirections() {
    long roomId = createRoomWithTwoMembers();

    roomBlockService.blockCounterpart(TENANT_ID, roomId);
    // 네트워크 재시도처럼 같은 차단 요청이 다시 와도 기존 user 모듈의 멱등 규칙을 그대로 사용한다.
    roomBlockService.blockCounterpart(TENANT_ID, roomId);

    assertThat(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).isTrue();
    Integer storedBlockCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_user_id = ?",
            Integer.class,
            TENANT_ID,
            LANDLORD_ID);
    assertThat(storedBlockCount).isEqualTo(1);

    assertThatThrownBy(
            () ->
                textMessageService.saveText(
                    roomId, TENANT_ID, UUID.randomUUID(), "차단 뒤에는 저장되지 않습니다."))
        .isInstanceOf(ChatUnavailableException.class);
    assertThatThrownBy(
            () ->
                textMessageService.saveText(
                    roomId, LANDLORD_ID, UUID.randomUUID(), "반대 방향도 저장되지 않습니다."))
        .isInstanceOf(ChatUnavailableException.class);

    // 차단 거부는 원문 INSERT 전에 끝나야 하므로 어느 방향의 TEXT도 MySQL에 남지 않는다.
    assertThat(messageRepository.findBefore(roomId, null, 10)).isEmpty();
  }

  /** 실제 서비스가 기대하는 공유 방 한 행과 사용자별 참여자 두 행을 같은 테스트 트랜잭션에 만든다. */
  private long createRoomWithTwoMembers() {
    Instant now = Instant.parse("2026-08-21T10:15:30Z");
    ChatRoom room =
        chatRoomRepository.save(
            ChatRoom.builder()
                .listingId("68a700000000000000000252")
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
    return room.getId();
  }

  /** 두 참여자의 상대 ID와 역할을 서로 반대로 저장해 실제 1:1 방 불변식을 재현한다. */
  private static ChatRoomMember member(
      long roomId, long userId, long counterpartId, ChatParticipantRole role, Instant createdAt) {
    return ChatRoomMember.builder()
        .chatRoomId(roomId)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(createdAt)
        .updatedAt(createdAt)
        .build();
  }
}
