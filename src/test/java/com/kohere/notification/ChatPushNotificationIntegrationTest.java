package com.kohere.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.TestcontainersConfiguration;
import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.chat.ChatMessageKind;
import com.kohere.notification.application.NotificationPreferenceService;
import com.kohere.notification.application.PushMessage;
import com.kohere.notification.application.PushMessageSender;
import com.kohere.notification.application.PushSendResult;
import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 MySQL Event Publication Registry가 chat 공개 이벤트를 notification listener로 전달하는 전체 경로를 검증한다.
 *
 * <p>외부 Firebase 호출만 fake 발송 포트로 바꾸고 이벤트 직렬화, 비동기 listener 트랜잭션, 실제 push_devices 조회·삭제는 운영 코드와 같은
 * 경로를 사용한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChatPushNotificationIntegrationTest {

  private static final long RECIPIENT_ID = 77L;
  private static final Instant SENT_AT = Instant.parse("2026-08-29T06:30:00Z");

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PushDeviceRepository pushDeviceRepository;
  @Autowired private NotificationPreferenceService preferenceService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private PushMessageSender messageSender;

  /** 이전 비동기 publication과 기기 행을 비운 뒤 각 테스트가 독립적인 수신자 상태에서 시작하게 한다. */
  @BeforeEach
  void cleanDatabase() {
    await().atMost(Duration.ofSeconds(5)).until(() -> count("event_publication") == 0);
    jdbcTemplate.update("DELETE FROM event_publication");
    jdbcTemplate.update("DELETE FROM push_devices");
    jdbcTemplate.update("DELETE FROM notification_preferences");
  }

  /** 공통 chat 이벤트가 두 등록 기기로 전달되고 provider가 해지했다고 답한 한 토큰만 실제 DB에서 제거되는지 확인한다. */
  @Test
  @DisplayName("커밋된 채팅 이벤트를 모든 기기에 발송하고 무효 토큰을 삭제한다")
  void dispatchesCommittedEventAndDeletesInvalidToken() {
    registerDevice(1L, "sent-token");
    registerDevice(2L, "invalid-token");
    AtomicReference<PushMessage> capturedMessage = new AtomicReference<>();
    given(messageSender.send(any(PushMessage.class)))
        .willAnswer(
            invocation -> {
              PushMessage message = invocation.getArgument(0);
              capturedMessage.set(message);
              return List.of(
                  new PushSendResult("sent-token", PushSendResult.Status.SENT),
                  new PushSendResult("invalid-token", PushSendResult.Status.INVALID_TOKEN));
            });

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(ignored -> eventPublisher.publishEvent(event()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(capturedMessage.get()).isNotNull();
              assertThat(count("event_publication")).isZero();
              assertThat(pushDeviceRepository.findByFcmToken("invalid-token")).isEmpty();
            });

    PushMessage message = capturedMessage.get();
    assertThat(message.fcmTokens()).containsExactly("sent-token", "invalid-token");
    assertThat(message.title()).isEqualTo("채팅");
    assertThat(message.body()).isEqualTo("\"고시원3\"에 새로운 문의가 도착했어요");
    assertThat(message.data())
        .containsEntry("type", "CHAT_MESSAGE")
        .containsEntry("roomId", "10")
        .containsEntry("messageId", "125")
        .doesNotContainKey("messageType");
    assertThat(pushDeviceRepository.findByFcmToken("sent-token")).isPresent();
  }

  /** 수신자가 채팅 푸시를 끄면 커밋된 이벤트는 완료하되 등록 기기가 있어도 provider를 호출하지 않는다. */
  @Test
  @DisplayName("채팅 푸시를 거부한 수신자에게는 FCM을 발송하지 않는다")
  void skipsCommittedEventWhenRecipientDisabledChatPush() {
    registerDevice(1L, "registered-token");
    preferenceService.updateChatPushEnabled(RECIPIENT_ID, false);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(ignored -> eventPublisher.publishEvent(event()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(count("event_publication")).isZero());

    verifyNoInteractions(messageSender);
    assertThat(pushDeviceRepository.findByFcmToken("registered-token")).isPresent();
  }

  /** 실제 JPA adapter를 통해 수신자의 발송 대상 설치본을 저장한다. */
  private void registerDevice(long idSeed, String token) {
    pushDeviceRepository.save(
        PushDevice.register(RECIPIENT_ID, new UUID(0L, idSeed), token, PushPlatform.IOS, SENT_AT));
  }

  /** 트랜잭션 안에서 기록해 listener가 커밋 이후에만 받을 문의 카드 이벤트를 만든다. */
  private static ChatMessageCreatedEvent event() {
    return new ChatMessageCreatedEvent(
        UUID.fromString("61ee3bde-2015-4317-9d68-460955520154"),
        ChatMessageKind.INQUIRY_CARD,
        10L,
        125L,
        RECIPIENT_ID,
        "listing-1",
        "고시원3",
        SENT_AT);
  }

  /** 비동기 publication 완료 여부를 실제 테이블 행 수로 확인한다. */
  private long count(String table) {
    Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return value == null ? 0 : value;
  }
}
