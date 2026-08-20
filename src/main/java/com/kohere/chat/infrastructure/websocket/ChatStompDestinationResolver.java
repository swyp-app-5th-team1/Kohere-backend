package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 클라이언트가 보낸 STOMP destination을 정확한 채팅 경로로 판별한다.
 *
 * <p>{@code startsWith("/topic/chat-rooms/")}처럼 앞부분만 비교하면 뒤에 와일드카드나 다른 문자열이 붙은 경로까지 실수로 허용할 수 있다. 이
 * 클래스는 허용한 경로 전체가 정확히 일치하는지 한곳에서 확인해 권한 규칙과 실제 handler가 서로 다른 문자열을 사용하지 않게 한다.
 */
@Component
public class ChatStompDestinationResolver {

  /** 0이나 앞자리 0 없이 양의 10진수 Long roomId 하나만 허용한다. */
  private static final Pattern ROOM_TOPIC_PATTERN =
      Pattern.compile("^/topic/chat-rooms/([1-9][0-9]*)$");

  /** TEXT SEND는 양의 roomId 뒤에 정확히 {@code /messages}만 오는 경로를 허용한다. */
  private static final Pattern MESSAGE_SEND_PATTERN =
      Pattern.compile("^/app/chat-rooms/([1-9][0-9]*)/messages$");

  /**
   * 현재 앱이 구독할 수 있는 개인 queue의 정확한 목록이다.
   *
   * <p>방 목록 알림과 번역 발행은 후속 단계지만, 앱은 연결 직후 개인 queue를 한 번에 준비할 수 있다. 서버가 아직 이벤트를 발행하지 않는 queue를 구독하는
   * 것은 안전하며 임의 queue는 이 목록에 없으므로 계속 거부된다.
   */
  private static final Set<String> ALLOWED_USER_SUBSCRIPTIONS =
      Set.of(
          ChatStompDestinations.ACK_QUEUE,
          ChatStompDestinations.ERROR_QUEUE,
          ChatStompDestinations.ROOM_EVENT_QUEUE,
          ChatStompDestinations.TRANSLATION_QUEUE,
          ChatStompDestinations.CONTROL_QUEUE);

  /**
   * 정확한 채팅방 topic이면 경로 안의 roomId를 반환한다.
   *
   * <p>숫자가 Java {@code long} 범위를 넘는 경우도 잘못된 경로로 처리해 서버 내부 숫자 변환 오류가 밖으로 새지 않게 한다.
   *
   * @param destination 클라이언트가 SUBSCRIBE한 전체 destination
   * @return 올바른 방 topic이면 roomId, 아니면 빈 값
   */
  public OptionalLong resolveRoomTopic(String destination) {
    return resolvePositiveRoomId(destination, ROOM_TOPIC_PATTERN);
  }

  /**
   * 정확한 사용자 TEXT SEND destination이면 경로 안의 roomId를 반환한다.
   *
   * <p>클라이언트가 {@code /topic}이나 임의 하위 경로로 직접 SEND하는 것을 막기 위해 handler와 같은 전체 경로만 인정한다.
   *
   * @param destination 클라이언트가 SEND한 전체 destination
   * @return 올바른 메시지 전송 경로이면 roomId, 아니면 빈 값
   */
  public OptionalLong resolveMessageSend(String destination) {
    return resolvePositiveRoomId(destination, MESSAGE_SEND_PATTERN);
  }

  /** 지정된 destination이 프런트에 공개한 개인 queue 중 하나인지 정확히 확인한다. */
  public boolean isAllowedUserSubscription(String destination) {
    return destination != null && ALLOWED_USER_SUBSCRIPTIONS.contains(destination);
  }

  /** 메시지 전송과 별개인 control ping 전체 경로인지 정확히 확인한다. */
  public boolean isControlSend(String destination) {
    return ChatStompDestinations.CONTROL_SEND.equals(destination);
  }

  /** 정규식 첫 번째 그룹을 양의 Java long으로 안전하게 바꾸는 공통 파서다. */
  private static OptionalLong resolvePositiveRoomId(String destination, Pattern pattern) {
    if (destination == null) {
      return OptionalLong.empty();
    }

    Matcher matcher = pattern.matcher(destination);
    if (!matcher.matches()) {
      return OptionalLong.empty();
    }

    try {
      return OptionalLong.of(Long.parseLong(matcher.group(1)));
    } catch (NumberFormatException ignored) {
      return OptionalLong.empty();
    }
  }
}
