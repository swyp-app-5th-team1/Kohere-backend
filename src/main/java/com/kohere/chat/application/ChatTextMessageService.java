package com.kohere.chat.application;

import com.kohere.chat.domain.ChatClientMessageConflictException;
import com.kohere.chat.domain.ChatMessageTooLongException;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ChatUnavailableException;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 보낸 TEXT를 MySQL에 정확히 한 번 저장하는 애플리케이션 서비스다.
 *
 * <p>이 클래스는 WebSocket이나 STOMP를 모른다. 저장·중복 판정·마지막 메시지 갱신·수신자 방 재표시를 하나의 트랜잭션으로 끝낸 뒤 결과만 반환한다. 따라서
 * 나중에 다른 진입점이 생겨도 동일한 저장 규칙을 재사용할 수 있고, broker 발행은 커밋이 성공한 다음에만 별도 계층에서 실행된다.
 */
@Service
@RequiredArgsConstructor
public class ChatTextMessageService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;
  private final ChatMessageTranslationRepository translationRepository;
  private final UserAccountService userAccountService;
  private final UserBlockService userBlockService;

  /**
   * 인증 사용자의 TEXT를 신규 저장하거나 같은 재시도의 기존 결과를 반환한다.
   *
   * @param roomId STOMP destination에서 읽은 서버 채팅방 ID
   * @param senderId 검증된 STOMP Principal의 {@code users.id}
   * @param clientMessageId 프런트가 만든 UUID. 같은 메시지 재시도에는 같은 값을 사용한다.
   * @param content 변경하지 않고 보관할 사용자 원문
   * @return 신규·중복 여부와 수신자 재표시 여부를 포함한 최종 저장 결과
   */
  @Transactional
  public TextMessageSaveResult saveText(
      long roomId, long senderId, UUID clientMessageId, String content) {
    validateInput(clientMessageId, content);

    /*
     * 방 행을 모든 메시지 쓰기의 공통 잠금으로 사용한다. TEXT와 서버 생성 카드가 동시에 들어와도
     * messageId 확인 → INSERT → lastMessageId 갱신 순서가 한 줄로 실행돼 마지막 포인터가 과거로 돌아가지 않는다.
     */
    ChatRoom room =
        chatRoomRepository.findByIdForUpdate(roomId).orElseThrow(ChatRoomNotFoundException::new);

    List<ChatRoomMember> members = memberRepository.findByChatRoomId(roomId);
    if (members.size() != 2) {
      // 1:1 방의 내부 데이터가 깨진 경우다. 임의 상대를 선택해 메시지를 보내는 것보다 트랜잭션을 실패시키는 편이 안전하다.
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    ChatRoomMember sender = findMember(members, senderId);
    if (sender == null || sender.getRoomHiddenAt() != null) {
      /*
       * 숨긴 방은 직접 문의로 다시 표시한 뒤 사용해야 한다. 오래된 화면이 roomId만 기억해 전송하는 경우를 허용하면
       * 자기 목록에는 방이 없는데 메시지만 보내지는 모순이 생긴다. 비참여자와 같은 404로 처리해 방 존재도 노출하지 않는다.
       */
      throw new ChatRoomNotFoundException();
    }

    ChatRoomMember recipient = findCounterpart(members, senderId);
    if (recipient == null) {
      throw new IllegalStateException("1:1 채팅방의 상대 참여자를 찾을 수 없습니다.");
    }

    // 차단 방향을 공개하지 않고 어느 한쪽이 차단했어도 같은 CHAT_UNAVAILABLE로 신규 저장을 막는다.
    if (userBlockService.isBlockedBetween(senderId, recipient.getUserId())) {
      throw new ChatUnavailableException();
    }

    Message existing =
        messageRepository
            .findByChatRoomIdAndSenderIdAndClientMessageId(roomId, senderId, clientMessageId)
            .orElse(null);
    if (existing != null) {
      assertSameOriginal(existing, content);
      // 중복 재시도는 lastMessageId·가시성·후속 번역 작업을 절대 다시 변경하지 않는다.
      return new TextMessageSaveResult(existing, true, recipient.getUserId(), false, null);
    }

    Instant sentAt = Instant.now();
    Message saved =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(senderId)
                .type(MessageType.TEXT)
                .content(content)
                .clientMessageId(clientMessageId)
                .sentAt(sentAt)
                .build());

    /*
     * 수신 언어는 프런트 입력을 믿지 않고 users.lang에서 읽는다. 이 PENDING 행까지 같은 트랜잭션에 넣어야
     * 원문만 커밋되고 번역 작업이 사라지는 부분 성공을 막을 수 있다.
     */
    String targetLanguage =
        supportedTargetLanguage(userAccountService.getLanguage(recipient.getUserId()));
    ChatMessageTranslation translation =
        translationRepository.save(
            ChatMessageTranslation.pending(
                saved.getId(), recipient.getUserId(), targetLanguage, sentAt));

    // INSERT가 성공한 최종 messageId만 채팅방 목록의 마지막 메시지 포인터로 기록한다.
    chatRoomRepository.save(room.recordMessage(saved.getId(), sentAt));

    ChatRoomMember visibleRecipient = recipient.showForNewActivity(sentAt, sentAt);
    boolean reopened = visibleRecipient != recipient;
    if (reopened) {
      /*
       * roomHiddenAt만 null로 바뀌고 historyHiddenThroughMessageId는 그대로다. 따라서 수신자에게 방은 다시 보이지만
       * 삭제 전에 숨긴 과거 메시지는 복원되지 않고 이번 새 messageId부터 조회된다.
       */
      memberRepository.save(visibleRecipient);
    }

    return new TextMessageSaveResult(
        saved, false, recipient.getUserId(), reopened, translation.getId());
  }

  /** 외부 입력의 필수값과 Unicode code point 3,000자 제한을 도메인 객체 생성 전에 명확한 오류 code로 바꾼다. */
  private static void validateInput(UUID clientMessageId, String content) {
    if (clientMessageId == null) {
      throw new InvalidInputException("clientMessageId", "validation.required");
    }
    if (content == null || content.isBlank()) {
      throw new InvalidInputException("content", "validation.required");
    }
    int codePointCount = content.codePointCount(0, content.length());
    if (codePointCount > Message.MAX_TEXT_CODE_POINTS) {
      throw new ChatMessageTooLongException();
    }
  }

  /** 같은 UUID가 기존 TEXT와 다른 본문에 재사용되면 원래 메시지를 덮어쓰지 않고 명시적인 충돌로 거부한다. */
  private static void assertSameOriginal(Message existing, String content) {
    if (existing.getType() != MessageType.TEXT || !content.equals(existing.getContent())) {
      throw new ChatClientMessageConflictException();
    }
  }

  /** 참여자 두 명 중 인증 사용자와 ID가 같은 행을 찾는다. */
  private static ChatRoomMember findMember(List<ChatRoomMember> members, long userId) {
    return members.stream().filter(member -> member.getUserId() == userId).findFirst().orElse(null);
  }

  /** 참여자 두 명 중 발신자가 아닌 상대 행을 찾는다. */
  private static ChatRoomMember findCounterpart(List<ChatRoomMember> members, long senderId) {
    return members.stream()
        .filter(member -> member.getUserId() != senderId)
        .findFirst()
        .orElse(null);
  }

  /** 현재 채팅 지원 언어 ko/en만 유지하고 미설정·미지원 값은 기존 사용자 정책대로 영어로 폴백한다. */
  private static String supportedTargetLanguage(String language) {
    return "ko".equalsIgnoreCase(language) ? "ko" : "en";
  }
}
