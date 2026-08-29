package com.kohere.notification.infrastructure.firebase;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.kohere.notification.application.PushMessage;
import com.kohere.notification.application.PushMessageSendException;
import com.kohere.notification.application.PushMessageSender;
import com.kohere.notification.application.PushSendResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Firebase Admin SDK의 토큰별 응답을 notification 모듈의 발송 결과로 변환하는 실제 FCM 어댑터다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
public class FirebasePushMessageSender implements PushMessageSender {

  /** FCM HTTP v1 sendEach 호출 한 번이 허용하는 최대 메시지 수다. */
  private static final int MAX_BATCH_SIZE = 500;

  private final FirebaseMessaging firebaseMessaging;

  /**
   * 최대 500개씩 FCM에 전달하고 요청 순서대로 각 토큰의 결과를 합친다.
   *
   * <p>토큰별 오류는 결과로 반환해 다른 기기 발송을 계속한다. FCM이 전체 요청 결과를 만들지 못한 경우에만 예외를 던져 Modulith publication을 미완료
   * 상태로 남긴다.
   */
  @Override
  public List<PushSendResult> send(PushMessage message) {
    List<PushSendResult> results = new ArrayList<>(message.fcmTokens().size());
    for (int start = 0; start < message.fcmTokens().size(); start += MAX_BATCH_SIZE) {
      int end = Math.min(start + MAX_BATCH_SIZE, message.fcmTokens().size());
      List<String> batchTokens = message.fcmTokens().subList(start, end);
      results.addAll(sendBatch(message, batchTokens));
    }
    return List.copyOf(results);
  }

  /** 한 FCM batch의 요청 메시지를 만들고 토큰별 응답을 같은 순서의 애플리케이션 결과로 변환한다. */
  private List<PushSendResult> sendBatch(PushMessage message, List<String> batchTokens) {
    List<Message> firebaseMessages =
        batchTokens.stream().map(token -> toFirebaseMessage(message, token)).toList();

    BatchResponse response;
    try {
      response = firebaseMessaging.sendEach(firebaseMessages);
    } catch (FirebaseMessagingException failure) {
      // 전체 토큰을 예외 메시지나 로그에 넣지 않는다. listener 실패로 publication 재처리 가능성만 남긴다.
      throw new PushMessageSendException("FCM did not return per-device send results", failure);
    }

    if (response.getResponses().size() != batchTokens.size()) {
      throw new IllegalStateException("FCM response count must match request count");
    }

    List<PushSendResult> results = new ArrayList<>(batchTokens.size());
    for (int index = 0; index < batchTokens.size(); index++) {
      results.add(toResult(batchTokens.get(index), response.getResponses().get(index)));
    }
    return results;
  }

  /** provider 중립 PushMessage를 FCM notification/data와 단일 등록 토큰 요청으로 변환한다. */
  @SuppressWarnings("deprecation")
  private static Message toFirebaseMessage(PushMessage message, String token) {
    Notification notification =
        Notification.builder().setTitle(message.title()).setBody(message.body()).build();
    return Message.builder()
        .setToken(token)
        .setNotification(notification)
        .putAllData(message.data())
        .build();
  }

  /** 한 FCM 응답을 토큰 삭제 여부와 운영 진단에 필요한 최소 상태로 분류한다. */
  private static PushSendResult toResult(String token, SendResponse response) {
    if (response.isSuccessful()) {
      return new PushSendResult(token, PushSendResult.Status.SENT);
    }

    FirebaseMessagingException failure = response.getException();
    MessagingErrorCode errorCode = failure == null ? null : failure.getMessagingErrorCode();
    return new PushSendResult(token, classify(errorCode));
  }

  /** FCM 오류 중 UNREGISTERED만 토큰 삭제로 분류하고 나머지는 원인별 보존 상태로 나눈다. */
  private static PushSendResult.Status classify(MessagingErrorCode errorCode) {
    if (errorCode == null) {
      return PushSendResult.Status.PERMANENT_FAILURE;
    }
    return switch (errorCode) {
      case UNREGISTERED -> PushSendResult.Status.INVALID_TOKEN;
      case INTERNAL, QUOTA_EXCEEDED, UNAVAILABLE -> PushSendResult.Status.TEMPORARY_FAILURE;
      case THIRD_PARTY_AUTH_ERROR, SENDER_ID_MISMATCH ->
          PushSendResult.Status.CONFIGURATION_FAILURE;
      case INVALID_ARGUMENT -> PushSendResult.Status.PERMANENT_FAILURE;
    };
  }
}
