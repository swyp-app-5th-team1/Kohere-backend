package com.kohere.chat.application;

import com.kohere.chat.application.dto.ChatCounterpartResponse;
import com.kohere.chat.application.dto.ChatLastMessageResponse;
import com.kohere.chat.application.dto.ChatListingSummaryResponse;
import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberPage;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 사용자의 채팅방 목록 화면에 필요한 데이터를 조립하는 읽기 전용 서비스다.
 *
 * <p>사용자별 숨김 상태는 {@code chat_room_members}, 공유 방 정보는 {@code chat_rooms}, 마지막 메시지는 {@code
 * chat_messages}가 각각 소유한다. 현재 페이지의 roomId와 lastMessageId를 먼저 모아 두 번의 batch 조회로 읽기 때문에 방 개수만큼 SQL을
 * 반복하지 않는다.
 *
 * <p>상대 이름과 차단 여부는 user 모듈의 기존 공개 API를 재사용한다. 같은 상대와 여러 매물 채팅방이 한 페이지에 있어도 요청 안에서는 한 번만 조회하도록 캐시한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomListService {

  /** API 계약상 한 요청에서 허용하는 최대 채팅방 수다. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;
  private final UserAccountService userAccountService;
  private final UserBlockService userBlockService;

  /**
   * 현재 사용자에게 보이는 채팅방을 최근 활동 순으로 반환한다.
   *
   * @param userId JWT에서 확인한 로그인 사용자의 {@code users.id}
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 크기(1~100)
   * @return 채팅방 목록과 페이지 정보
   */
  @Transactional(readOnly = true)
  public PageResponse<ChatRoomResponse> listRooms(long userId, int page, int size) {
    validatePage(page, size);

    // 먼저 사용자별 member 행을 조회해야 사용자가 삭제해 숨긴 방을 결과와 전체 개수에서 함께 제외할 수 있다.
    ChatRoomMemberPage memberPage = memberRepository.findVisiblePageByUserId(userId, page, size);
    if (memberPage.content().isEmpty()) {
      return pageResponse(List.of(), page, size, memberPage.totalElements());
    }

    // 현재 페이지의 방을 한 번에 읽고 ID map으로 바꿔 member 쿼리의 정렬 순서대로 다시 조립한다.
    Map<Long, ChatRoom> roomsById =
        indexById(chatRoomRepository.findByIds(roomIds(memberPage.content())), ChatRoom::getId);

    // 사용자가 과거 메시지를 삭제했다면 그 경계 이하의 lastMessageId는 batch 조회 대상에서도 제외한다.
    Collection<Long> visibleLastMessageIds = visibleLastMessageIds(memberPage.content(), roomsById);
    Map<Long, Message> messagesById =
        indexById(messageRepository.findByIds(visibleLastMessageIds), Message::getId);

    Map<Long, String> counterpartNames = new HashMap<>();
    Map<Long, Boolean> blockedRelationships = new HashMap<>();
    List<ChatRoomResponse> content =
        memberPage.content().stream()
            .map(
                member ->
                    toResponse(
                        userId,
                        member,
                        requiredRoom(roomsById, member.getChatRoomId()),
                        messagesById,
                        counterpartNames,
                        blockedRelationships))
            .toList();

    return pageResponse(content, page, size, memberPage.totalElements());
  }

  /** API가 조용히 값을 보정하지 않도록 잘못된 페이지 범위를 400 INVALID_INPUT으로 거부한다. */
  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page", "validation.min", 0, page);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size", "validation.range", 1, MAX_PAGE_SIZE, size);
    }
  }

  /** member의 DB 정렬을 보존하면서 batch 조회에 사용할 중복 없는 roomId를 만든다. */
  private static Collection<Long> roomIds(List<ChatRoomMember> members) {
    return members.stream()
        .map(ChatRoomMember::getChatRoomId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * 사용자에게 실제로 보일 수 있는 마지막 메시지 ID만 고른다.
   *
   * <p>직접 재문의로 삭제한 방이 다시 목록에 나타나더라도, 삭제 당시 경계 이하의 메시지를 preview로 되살리면 안 된다. 따라서 방의 공유 {@code
   * lastMessageId}가 개인 경계보다 큰 경우에만 조회한다.
   */
  private static Collection<Long> visibleLastMessageIds(
      List<ChatRoomMember> members, Map<Long, ChatRoom> roomsById) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    for (ChatRoomMember member : members) {
      ChatRoom room = requiredRoom(roomsById, member.getChatRoomId());
      Long lastMessageId = room.getLastMessageId();
      if (lastMessageId != null && lastMessageId > member.getHistoryHiddenThroughMessageId()) {
        ids.add(lastMessageId);
      }
    }
    return ids;
  }

  /** 한 member와 공유 방·마지막 메시지를 앱의 목록 한 줄 응답으로 변환한다. */
  private ChatRoomResponse toResponse(
      long userId,
      ChatRoomMember member,
      ChatRoom room,
      Map<Long, Message> messagesById,
      Map<Long, String> counterpartNames,
      Map<Long, Boolean> blockedRelationships) {
    long counterpartId = member.getCounterpartId();
    String counterpartName =
        counterpartNames.computeIfAbsent(counterpartId, userAccountService::getUserName);
    boolean blocked =
        blockedRelationships.computeIfAbsent(
            counterpartId, ignored -> userBlockService.isBlockedBetween(userId, counterpartId));

    return new ChatRoomResponse(
        room.getId(),
        member.getRole(),
        new ChatListingSummaryResponse(
            room.getListingId(),
            room.getListingSnapshot().title(),
            room.getListingSnapshot().address()),
        new ChatCounterpartResponse(counterpartId, counterpartName),
        visibleLastMessage(member, room, messagesById),
        blocked);
  }

  /** 삭제 경계보다 뒤에 있는 마지막 메시지만 목록 미리보기로 만든다. */
  private static ChatLastMessageResponse visibleLastMessage(
      ChatRoomMember member, ChatRoom room, Map<Long, Message> messagesById) {
    Long lastMessageId = room.getLastMessageId();
    if (lastMessageId == null || lastMessageId <= member.getHistoryHiddenThroughMessageId()) {
      return null;
    }

    Message message = messagesById.get(lastMessageId);
    if (message == null) {
      // last_message_id가 존재하지 않는 행을 가리키면 목록 일부를 조용히 숨기지 않고 정본 불일치로 드러낸다.
      throw new IllegalStateException("Missing last message: " + lastMessageId);
    }

    // BOOKING_CARD의 문구는 myRole과 앱 언어에 따라 프런트가 고정 label을 선택하므로 preview는 null이다.
    String preview = message.getType() == MessageType.TEXT ? message.getContent() : null;
    return new ChatLastMessageResponse(
        message.getId(), message.getType(), preview, message.getSentAt());
  }

  /** batch 조회에서 방이 빠진 경우 no-FK 정합성 문제를 명확한 서버 오류로 남긴다. */
  private static ChatRoom requiredRoom(Map<Long, ChatRoom> roomsById, Long roomId) {
    ChatRoom room = roomsById.get(roomId);
    if (room == null) {
      throw new IllegalStateException("Missing chat room: " + roomId);
    }
    return room;
  }

  /** batch 조회 결과를 식별자 기반 O(1) 조립에 사용할 map으로 바꾼다. */
  private static <T> Map<Long, T> indexById(List<T> values, Function<T, Long> idExtractor) {
    return values.stream()
        .collect(Collectors.toMap(idExtractor, Function.identity(), (first, ignored) -> first));
  }

  /** 공통 PageResponse 계산을 한곳에 모아 빈 페이지도 같은 메타데이터 규칙을 사용하게 한다. */
  private static PageResponse<ChatRoomResponse> pageResponse(
      List<ChatRoomResponse> content, int page, int size, long totalElements) {
    int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    boolean hasNext = page + 1 < totalPages;
    return PageResponse.of(content, new PageInfo(page, size, totalElements, totalPages, hasNext));
  }
}
