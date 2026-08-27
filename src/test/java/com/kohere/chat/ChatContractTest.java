package com.kohere.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kohere.chat.application.dto.BookingCardApplicantResponse;
import com.kohere.chat.application.dto.BookingCardListingResponse;
import com.kohere.chat.application.dto.BookingCardResponse;
import com.kohere.chat.application.dto.ChatCounterpartResponse;
import com.kohere.chat.application.dto.ChatLastMessageResponse;
import com.kohere.chat.application.dto.ChatListingSummaryResponse;
import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.InquiryCardResponse;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.application.dto.MessageTranslationResponse;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageSendPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 프런트엔드와 합의한 REST·STOMP JSON 모양을 서비스 구현보다 먼저 고정하는 직렬화 계약 테스트다.
 *
 * <p>실제 WebSocket broker 없이도 DTO 필드명, UUID 형식, 원문·번역 분리, 서버 생성 BOOKING_CARD 규칙을 검증할 수 있다. 연결·인가·DB
 * commit 이후 publish는 STOMP 구현 단계의 통합 테스트에서 별도로 확인한다.
 */
class ChatContractTest {

  private static final UUID CLIENT_MESSAGE_ID =
      UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849");
  private static final Instant SENT_AT = Instant.parse("2026-08-19T10:15:30.123456Z");

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** 문의 응답에 경로와 상세 조회로 이미 알 수 있는 값을 중복 노출하지 않는지 확인한다. */
  @Test
  void inquiryResponseContainsOnlyRoomIdAndCreated() {
    JsonNode json = objectMapper.valueToTree(new InquiryResponse(556L, false));

    assertThat(fieldNames(json)).containsExactly("chatRoomId", "created");
    assertThat(json.path("chatRoomId").asLong()).isEqualTo(556L);
    assertThat(json.path("created").asBoolean()).isFalse();
  }

  /** 이번 범위에서 제외한 category와 unreadCount가 방 목록 계약에 되살아나지 않게 한다. */
  @Test
  void roomListItemDoesNotExposeCategoryOrUnreadCount() {
    ChatRoomResponse response =
        new ChatRoomResponse(
            556L,
            ChatParticipantRole.LANDLORD,
            new ChatListingSummaryResponse(
                "6858e2000000000000000001", "Hongdae Studio share", "Seogyo-dong, Mapo-gu"),
            new ChatCounterpartResponse(7L, "Gil dong Hong"),
            new ChatLastMessageResponse(70051L, MessageType.TEXT, "안녕하세요", SENT_AT),
            false);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(fieldNames(json))
        .containsExactly(
            "chatRoomId", "myRole", "listing", "counterpart", "lastMessage", "blocked");
    assertThat(json.has("category")).isFalse();
    assertThat(json.has("unreadCount")).isFalse();
    assertThat(json.path("listing").has("thumbnailUrl")).isFalse();
    assertThat(json.path("counterpart").has("profileImageUrl")).isFalse();
  }

  /** TEXT가 원문을 잃지 않은 채 현재 수신자용 번역본을 별도 객체로 제공하는지 확인한다. */
  @Test
  void textMessageKeepsOriginalAndOptionalTranslationFields() {
    MessageResponse response =
        new MessageResponse(
            70051L,
            CLIENT_MESSAGE_ID,
            556L,
            7L,
            false,
            MessageType.TEXT,
            "Is the room still available?",
            null,
            null,
            new MessageTranslationResponse(
                TranslationResultStatus.SUCCEEDED,
                "아직 방을 구할 수 있나요?",
                "en",
                "ko",
                TranslationProvider.GOOGLE_CLOUD_TRANSLATION),
            SENT_AT);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(fieldNames(json))
        .containsExactly(
            "messageId",
            "clientMessageId",
            "chatRoomId",
            "senderId",
            "mine",
            "type",
            "originalContent",
            "bookingCard",
            "inquiryCard",
            "translation",
            "sentAt");
    assertThat(json.path("mine").asBoolean()).isFalse();
    assertThat(json.path("bookingCard").isNull()).isTrue();
    assertThat(json.path("inquiryCard").isNull()).isTrue();
    assertThat(json.path("translation").path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(json.path("translation").path("targetLanguage").asText()).isEqualTo("ko");
  }

  /** BOOKING_CARD는 서버 생성 메시지이므로 프런트 UUID·발신자·TEXT·번역 필드가 비어 있는지 확인한다. */
  @Test
  void bookingCardUsesBookingNamesAndHasNoTextFields() {
    BookingCardResponse bookingCard =
        new BookingCardResponse(
            123L,
            new BookingCardListingResponse(
                "6858e2000000000000000001",
                "https://cdn.example.com/listings/cover.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                420_000),
            new BookingCardApplicantResponse(
                7L, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com"),
            "room-a",
            "Room A",
            LocalDate.parse("2026-06-15"),
            3,
            0,
            1_260_000);
    MessageResponse response =
        new MessageResponse(
            70052L,
            null,
            556L,
            null,
            false,
            MessageType.BOOKING_CARD,
            null,
            bookingCard,
            null,
            null,
            SENT_AT);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.path("clientMessageId").isNull()).isTrue();
    assertThat(json.path("senderId").isNull()).isTrue();
    assertThat(json.path("originalContent").isNull()).isTrue();
    assertThat(json.path("translation").isNull()).isTrue();
    assertThat(json.path("bookingCard").path("contractPeriod").asInt()).isEqualTo(3);
    assertThat(json.path("bookingCard").has("contractPeriodMonths")).isFalse();
  }

  /** INQUIRY_CARD는 상세 이동과 문의서 UI에 필요한 매물 요약만 사용하고 TEXT·신청 필드는 비운다. */
  @Test
  void inquiryCardUsesListingSummaryAndHasNoOtherMessageFields() {
    InquiryCardResponse inquiryCard =
        new InquiryCardResponse(
            "6858e2000000000000000001",
            "https://cdn.example.com/listings/cover.jpg",
            "Hongdae Studio share",
            "SEOUL",
            "MAPO_GU",
            "CO_LIVING",
            350_000,
            500_000);
    MessageResponse response =
        new MessageResponse(
            70050L,
            null,
            556L,
            null,
            false,
            MessageType.INQUIRY_CARD,
            null,
            null,
            inquiryCard,
            null,
            SENT_AT);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.path("clientMessageId").isNull()).isTrue();
    assertThat(json.path("senderId").isNull()).isTrue();
    assertThat(json.path("originalContent").isNull()).isTrue();
    assertThat(json.path("bookingCard").isNull()).isTrue();
    assertThat(json.path("translation").isNull()).isTrue();
    assertThat(json.path("inquiryCard").path("listingType").asText()).isEqualTo("CO_LIVING");
    assertThat(json.path("inquiryCard").path("monthlyRentMin").asInt()).isEqualTo(350_000);
    assertThat(json.path("inquiryCard").path("monthlyRentMax").asInt()).isEqualTo(500_000);
  }

  /** 프런트엔드가 만든 UUID와 원문만 SEND하며 잘못된 UUID는 binding 단계에서 거부되는지 확인한다. */
  @Test
  void stompSendPayloadUsesClientUuidAndContentOnly() throws Exception {
    ChatMessageSendPayload payload = new ChatMessageSendPayload(CLIENT_MESSAGE_ID, "안녕하세요");

    JsonNode json = objectMapper.valueToTree(payload);

    assertThat(fieldNames(json)).containsExactly("clientMessageId", "content");
    assertThat(ChatMessageSendPayload.MAX_CONTENT_CODE_POINTS).isEqualTo(3_000);
    assertThatThrownBy(
            () ->
                objectMapper.readValue(
                    "{\"clientMessageId\":\"not-a-uuid\",\"content\":\"안녕하세요\"}",
                    ChatMessageSendPayload.class))
        .isInstanceOf(JsonProcessingException.class);
  }

  /** destination 문자열과 구독 준비 high-watermark 이벤트가 문서 계약에서 벗어나지 않게 한다. */
  @Test
  void stompDestinationAndControlEventContractsAreFixed() {
    assertThat(ChatStompDestinations.MESSAGE_SEND).isEqualTo("/app/chat-rooms/{roomId}/messages");
    assertThat(ChatStompDestinations.messageSend(556L)).isEqualTo("/app/chat-rooms/556/messages");
    assertThat(ChatStompDestinations.ROOM_TOPIC).isEqualTo("/topic/chat-rooms/{roomId}");
    assertThat(ChatStompDestinations.roomTopic(556L)).isEqualTo("/topic/chat-rooms/556");
    assertThat(ChatStompDestinations.CONTROL_SEND).isEqualTo("/app/chat/control/ping");
    assertThat(ChatStompDestinations.CONTROL_QUEUE).isEqualTo("/user/queue/chat-control");

    JsonNode json =
        objectMapper.valueToTree(ChatControlEventPayload.subscriptionReady(556L, 70052L));

    assertThat(fieldNames(json))
        .containsExactly("version", "eventType", "requestId", "roomId", "highWatermark");
    assertThat(json.path("eventType").asText()).isEqualTo("SUBSCRIPTION_READY");
  }

  /** 사용자가 보내는 TEXT와 서버가 만드는 두 카드 외 타입을 공개 계약에 실수로 추가하지 않게 한다. */
  @Test
  void publicMessageTypesContainOnlySupportedTypes() {
    assertThat(MessageType.values())
        .containsExactly(MessageType.TEXT, MessageType.INQUIRY_CARD, MessageType.BOOKING_CARD);
  }

  /** Jackson이 내보낸 실제 필드 순서를 간결하게 비교하기 위한 도우미. */
  private static List<String> fieldNames(JsonNode json) {
    List<String> names = new ArrayList<>();
    json.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
