package com.kohere.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.chat.ChatMessageKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 내부 채팅 메시지 종류별 표시 문구와 공통 FCM data 계약을 검증한다. */
class ChatPushMessageFactoryTest {

  private final ChatPushMessageFactory factory = new ChatPushMessageFactory();

  /** 세 내부 종류가 서로 다른 본문을 만들지만 프론트에는 같은 여섯 data 필드만 보내는지 확인한다. */
  @ParameterizedTest
  @MethodSource("messageBodies")
  @DisplayName("메시지 종류별 notification과 공통 data를 만든다")
  void createsNotificationAndSharedData(ChatMessageKind kind, String expectedBody) {
    PushMessage message = factory.create(event(kind), List.of("token-a"));

    assertThat(message.title()).isEqualTo("채팅");
    assertThat(message.body()).isEqualTo(expectedBody);
    assertThat(message.data())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(
                "type", "CHAT_MESSAGE",
                "roomId", "10",
                "messageId", "125",
                "listingId", "listing-1",
                "listingTitle", "고시원3",
                "sentAt", "2026-08-29T06:30:00Z"));
    assertThat(message.data()).doesNotContainKey("messageType");
  }

  /** 파라미터 테스트가 검증할 메시지 종류와 최종 표시 본문을 제공한다. */
  private static Stream<Arguments> messageBodies() {
    return Stream.of(
        Arguments.of(ChatMessageKind.TEXT, "\"고시원3\"으로부터 새 메시지가 도착했어요"),
        Arguments.of(ChatMessageKind.INQUIRY_CARD, "\"고시원3\"에 새로운 문의가 도착했어요"),
        Arguments.of(ChatMessageKind.BOOKING_CARD, "\"고시원3\"에 새로운 신청이 도착했어요"));
  }

  /** 문구 이외 식별자는 모든 메시지 종류에서 같은 이벤트 fixture를 만든다. */
  private static ChatMessageCreatedEvent event(ChatMessageKind kind) {
    return new ChatMessageCreatedEvent(
        UUID.fromString("61ee3bde-2015-4317-9d68-460955520154"),
        kind,
        10L,
        125L,
        77L,
        "listing-1",
        "고시원3",
        Instant.parse("2026-08-29T06:30:00Z"));
  }
}
