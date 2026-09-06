package com.kohere.notification.application;

import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 커밋된 채팅 메시지 이벤트를 수신자의 등록 기기 FCM 발송으로 연결한다.
 *
 * <p>{@link ApplicationModuleListener}가 원래 채팅 트랜잭션의 커밋 뒤 별도 비동기 트랜잭션에서 실행한다. 따라서 Firebase 전체 호출 실패가
 * 발생해도 채팅 메시지와 STOMP 저장 결과는 롤백되지 않으며, 미완료 publication을 운영에서 확인할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessagePushListener {

  private final PushDeviceRepository pushDeviceRepository;
  private final NotificationPreferenceService preferenceService;
  private final ChatPushMessageFactory messageFactory;
  private final PushMessageSender messageSender;

  /** 수신자의 모든 활성 기기에 한 번씩 발송하고 FCM이 명확히 해지됐다고 답한 토큰만 삭제한다. */
  @ApplicationModuleListener(id = "notification.push-on-chat-message-created")
  public void onChatMessageCreated(ChatMessageCreatedEvent event) {
    if (!preferenceService.isChatPushEnabled(event.recipientUserId())) {
      log.info(
          "Chat push skipped by recipient preference: eventId={}, roomId={}, messageId={}, recipientUserId={}",
          event.eventId(),
          event.roomId(),
          event.messageId(),
          event.recipientUserId());
      return;
    }

    List<PushDevice> devices = pushDeviceRepository.findAllByUserId(event.recipientUserId());
    if (devices.isEmpty()) {
      log.info(
          "Chat push skipped without registered device: eventId={}, roomId={}, messageId={}, recipientUserId={}",
          event.eventId(),
          event.roomId(),
          event.messageId(),
          event.recipientUserId());
      return;
    }

    List<String> tokens = devices.stream().map(PushDevice::getFcmToken).toList();
    List<PushSendResult> results = messageSender.send(messageFactory.create(event, tokens));
    if (results.size() != tokens.size()) {
      throw new IllegalStateException("push sender result count must match target count");
    }

    Map<PushSendResult.Status, Integer> counts = countResults(results);
    int deletedTokens = deleteInvalidTokens(results);
    logResult(event, devices.size(), deletedTokens, counts);
  }

  /** provider 상태별 개수를 계산하되 민감한 FCM 토큰 원문은 로그 집계에 사용하지 않는다. */
  private static Map<PushSendResult.Status, Integer> countResults(List<PushSendResult> results) {
    Map<PushSendResult.Status, Integer> counts = new EnumMap<>(PushSendResult.Status.class);
    for (PushSendResult result : results) {
      counts.merge(result.status(), 1, Integer::sum);
    }
    return counts;
  }

  /** 영구 해지된 토큰만 사용자·설치본 연결에서 제거하고 실제 삭제된 행 수를 반환한다. */
  private int deleteInvalidTokens(List<PushSendResult> results) {
    int deleted = 0;
    for (PushSendResult result : results) {
      if (result.status() == PushSendResult.Status.INVALID_TOKEN
          && pushDeviceRepository.deleteByFcmToken(result.fcmToken())) {
        deleted++;
      }
    }
    return deleted;
  }

  /** 개인정보 없이 이벤트 식별자와 상태별 집계만 구조화 로그에 남긴다. */
  private static void logResult(
      ChatMessageCreatedEvent event,
      int targetCount,
      int deletedTokens,
      Map<PushSendResult.Status, Integer> counts) {
    log.info(
        "Chat push processed: eventId={}, messageType={}, roomId={}, messageId={}, recipientUserId={}, targets={}, sent={}, invalid={}, temporaryFailure={}, configurationFailure={}, permanentFailure={}, skipped={}, deletedTokens={}",
        event.eventId(),
        event.messageType(),
        event.roomId(),
        event.messageId(),
        event.recipientUserId(),
        targetCount,
        counts.getOrDefault(PushSendResult.Status.SENT, 0),
        counts.getOrDefault(PushSendResult.Status.INVALID_TOKEN, 0),
        counts.getOrDefault(PushSendResult.Status.TEMPORARY_FAILURE, 0),
        counts.getOrDefault(PushSendResult.Status.CONFIGURATION_FAILURE, 0),
        counts.getOrDefault(PushSendResult.Status.PERMANENT_FAILURE, 0),
        counts.getOrDefault(PushSendResult.Status.SKIPPED, 0),
        deletedTokens);
  }
}
