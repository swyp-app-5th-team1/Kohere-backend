package com.kohere.notification.infrastructure.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.kohere.notification.application.PushMessage;
import com.kohere.notification.application.PushMessageSendException;
import com.kohere.notification.application.PushSendResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Firebase 토큰별 오류 코드가 삭제·보존 정책에 맞는 발송 결과로 변환되는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class FirebasePushMessageSenderTest {

  @Mock private FirebaseMessaging firebaseMessaging;
  @Mock private BatchResponse batchResponse;

  /** 성공·무효·일시·설정·payload 실패를 입력 토큰 순서대로 분류한다. */
  @Test
  @DisplayName("FCM 토큰별 응답을 애플리케이션 상태로 분류한다")
  void classifiesPerTokenResponses() throws Exception {
    List<String> tokens = List.of("sent", "invalid", "temporary", "configuration", "permanent");
    List<SendResponse> responses =
        List.of(
            successfulResponse(),
            failedResponse(MessagingErrorCode.UNREGISTERED),
            failedResponse(MessagingErrorCode.UNAVAILABLE),
            failedResponse(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR),
            failedResponse(MessagingErrorCode.INVALID_ARGUMENT));
    given(batchResponse.getResponses()).willReturn(responses);
    given(firebaseMessaging.sendEach(anyList())).willReturn(batchResponse);
    FirebasePushMessageSender sender = new FirebasePushMessageSender(firebaseMessaging);

    List<PushSendResult> results = sender.send(message(tokens));

    assertThat(results).extracting(PushSendResult::fcmToken).containsExactlyElementsOf(tokens);
    assertThat(results)
        .extracting(PushSendResult::status)
        .containsExactly(
            PushSendResult.Status.SENT,
            PushSendResult.Status.INVALID_TOKEN,
            PushSendResult.Status.TEMPORARY_FAILURE,
            PushSendResult.Status.CONFIGURATION_FAILURE,
            PushSendResult.Status.PERMANENT_FAILURE);
  }

  /** FCM이 토큰별 결과를 전혀 만들지 못하면 이벤트 미완료 처리가 가능한 전체 실패로 감싼다. */
  @Test
  @DisplayName("FCM 전체 호출 실패는 PushMessageSendException으로 전달한다")
  void wrapsTotalProviderFailure() throws Exception {
    FirebaseMessagingException failure = org.mockito.Mockito.mock(FirebaseMessagingException.class);
    given(firebaseMessaging.sendEach(anyList())).willThrow(failure);
    FirebasePushMessageSender sender = new FirebasePushMessageSender(firebaseMessaging);

    assertThatThrownBy(() -> sender.send(message(List.of("token-a"))))
        .isInstanceOf(PushMessageSendException.class)
        .hasCause(failure);
  }

  /** 한 토큰이 FCM에 접수된 성공 응답을 만든다. */
  private static SendResponse successfulResponse() {
    SendResponse response = org.mockito.Mockito.mock(SendResponse.class);
    given(response.isSuccessful()).willReturn(true);
    return response;
  }

  /** 지정한 MessagingErrorCode를 가진 실패 응답을 만든다. */
  private static SendResponse failedResponse(MessagingErrorCode errorCode) {
    FirebaseMessagingException failure = org.mockito.Mockito.mock(FirebaseMessagingException.class);
    given(failure.getMessagingErrorCode()).willReturn(errorCode);
    SendResponse response = org.mockito.Mockito.mock(SendResponse.class);
    given(response.isSuccessful()).willReturn(false);
    given(response.getException()).willReturn(failure);
    return response;
  }

  /** Firebase 어댑터 테스트가 공유할 최소 provider 중립 요청을 만든다. */
  private static PushMessage message(List<String> tokens) {
    return new PushMessage(
        tokens,
        "채팅",
        "\"고시원3\"으로부터 새 메시지가 도착했어요",
        Map.of(
            "type", "CHAT_MESSAGE",
            "roomId", "10",
            "messageId", "125",
            "listingId", "listing-1",
            "listingTitle", "고시원3",
            "sentAt", "2026-08-29T06:30:00Z"));
  }
}
