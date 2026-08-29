package com.kohere.notification.application;

import com.kohere.chat.ChatMessageCreatedEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 공통 채팅 메시지 이벤트를 iOS에 보낼 최종 notification/data 계약으로 변환한다. */
@Component
public class ChatPushMessageFactory {

  static final String PUSH_TYPE = "CHAT_MESSAGE";
  static final String TITLE = "채팅";

  /**
   * 내부 메시지 종류로 표시 본문을 고르고, 앱의 공통 채팅방 이동에 필요한 여섯 data 필드를 만든다.
   *
   * <p>내부 {@code messageType}은 문구 선택에만 사용한다. 세 종류 모두 같은 채팅방으로 이동하므로 FCM data에는 넣지 않는다.
   */
  public PushMessage create(ChatMessageCreatedEvent event, List<String> fcmTokens) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("type", PUSH_TYPE);
    data.put("roomId", Long.toString(event.roomId()));
    data.put("messageId", Long.toString(event.messageId()));
    data.put("listingId", event.listingId());
    data.put("listingTitle", event.listingTitle());
    data.put("sentAt", event.sentAt().toString());

    return new PushMessage(fcmTokens, TITLE, body(event), data);
  }

  /** 내부 메시지 종류를 사용자가 알림에서 이해할 수 있는 매물별 고정 문구로 변환한다. */
  private static String body(ChatMessageCreatedEvent event) {
    return switch (event.messageType()) {
      case TEXT -> String.format("\"%s\"으로부터 새 메시지가 도착했어요", event.listingTitle());
      case INQUIRY_CARD -> String.format("\"%s\"에 새로운 문의가 도착했어요", event.listingTitle());
      case BOOKING_CARD -> String.format("\"%s\"에 새로운 신청이 도착했어요", event.listingTitle());
    };
  }
}
