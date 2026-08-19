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
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.application.dto.MessageTranslationResponse;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageSendPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatContractTest {

  private static final UUID CLIENT_MESSAGE_ID =
      UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849");
  private static final Instant SENT_AT = Instant.parse("2026-08-19T10:15:30.123456Z");

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void inquiryResponseContainsOnlyRoomIdAndCreated() {
    JsonNode json = objectMapper.valueToTree(new InquiryResponse(556L, false));

    assertThat(fieldNames(json)).containsExactly("chatRoomId", "created");
    assertThat(json.path("chatRoomId").asLong()).isEqualTo(556L);
    assertThat(json.path("created").asBoolean()).isFalse();
  }

  @Test
  void roomListItemDoesNotExposeCategoryOrUnreadCount() {
    ChatRoomResponse response =
        new ChatRoomResponse(
            556L,
            ChatParticipantRole.LANDLORD,
            new ChatListingSummaryResponse(
                "6858e2000000000000000001",
                "Hongdae Studio share",
                "https://cdn.example.com/listings/cover.jpg",
                "Seogyo-dong, Mapo-gu"),
            new ChatCounterpartResponse(7L, "Gil dong Hong"),
            new ChatLastMessageResponse(70051L, MessageType.TEXT, "안녕하세요", SENT_AT),
            false);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(fieldNames(json))
        .containsExactly(
            "chatRoomId", "myRole", "listing", "counterpart", "lastMessage", "blocked");
    assertThat(json.has("category")).isFalse();
    assertThat(json.has("unreadCount")).isFalse();
  }

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
            new MessageTranslationResponse(
                "아직 방을 구할 수 있나요?", "en", "ko", TranslationProvider.GOOGLE_CLOUD_TRANSLATION),
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
            "translation",
            "sentAt");
    assertThat(json.path("mine").asBoolean()).isFalse();
    assertThat(json.path("bookingCard").isNull()).isTrue();
    assertThat(json.path("translation").path("targetLanguage").asText()).isEqualTo("ko");
  }

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
            SENT_AT);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.path("clientMessageId").isNull()).isTrue();
    assertThat(json.path("senderId").isNull()).isTrue();
    assertThat(json.path("originalContent").isNull()).isTrue();
    assertThat(json.path("translation").isNull()).isTrue();
    assertThat(json.path("bookingCard").path("contractPeriod").asInt()).isEqualTo(3);
    assertThat(json.path("bookingCard").has("contractPeriodMonths")).isFalse();
  }

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

  @Test
  void stompDestinationAndControlEventContractsAreFixed() {
    assertThat(ChatStompDestinations.MESSAGE_SEND).isEqualTo("/app/chat-rooms/{roomId}/messages");
    assertThat(ChatStompDestinations.ROOM_TOPIC).isEqualTo("/topic/chat-rooms/{roomId}");

    JsonNode json =
        objectMapper.valueToTree(
            new ChatControlEventPayload(
                1, ChatStompEventType.SUBSCRIPTION_READY, null, 556L, 70052L));

    assertThat(fieldNames(json))
        .containsExactly("version", "eventType", "requestId", "roomId", "highWatermark");
    assertThat(json.path("eventType").asText()).isEqualTo("SUBSCRIPTION_READY");
  }

  @Test
  void publicMessageTypesContainOnlyTextAndBookingCard() {
    assertThat(MessageType.values()).containsExactly(MessageType.TEXT, MessageType.BOOKING_CARD);
  }

  private static List<String> fieldNames(JsonNode json) {
    List<String> names = new ArrayList<>();
    json.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
