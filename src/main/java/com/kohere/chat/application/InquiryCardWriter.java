package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 채팅방에서 필요한 경우에만 새 INQUIRY_CARD를 저장한다.
 *
 * <p>문의서 바로 다음에 다시 문의하기를 누르면 같은 카드를 연속 저장하지 않는다. 반대로 마지막 문의서 뒤에 TEXT나 BOOKING_CARD 등 다른 메시지가 있거나,
 * 임차인이 기존 문의서를 볼 수 없는 상태라면 새 문의서를 저장한다. 방 행을 먼저 잠가 동시에 들어온 문의 요청도 문의서 한 건으로 수렴시킨다.
 */
@Component
@RequiredArgsConstructor
public class InquiryCardWriter {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;

  /**
   * 기존 방의 현재 이력과 임차인 표시 상태를 확인하고 필요한 경우 문의서를 저장한다.
   *
   * @param roomId 문의서를 추가할 기존 채팅방 ID
   * @param tenantId 문의하기를 누른 임차인의 사용자 ID
   * @param inquiryPayload 공개 매물 정보로 만든 문의서 사본
   * @param now 문의 요청을 처리하는 서버 UTC 시각
   * @return 문의서를 저장했으면 처리 결과, 연속 중복이라 저장하지 않았으면 빈 값
   */
  @Transactional
  public Optional<InquiryCardProcessResult> saveIfNeeded(
      long roomId, long tenantId, InquiryCardPayload inquiryPayload, Instant now) {
    // TEXT·BOOKING_CARD 저장과 같은 방 행을 먼저 잠가 마지막 메시지 순서가 서로 덮어써지지 않게 한다.
    ChatRoom room =
        chatRoomRepository
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> new IllegalStateException("문의서를 저장할 채팅방을 찾을 수 없습니다."));
    if (!room.getTenantId().equals(tenantId)) {
      throw new IllegalStateException("문의 요청자와 채팅방의 임차인이 일치하지 않습니다.");
    }

    List<ChatRoomMember> members = memberRepository.findByChatRoomIdForUpdate(roomId);
    if (members.size() != 2) {
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }
    ChatRoomMember tenant = findTenantMember(members, tenantId);

    Message latestInquiry = messageRepository.findLatestInquiryCard(roomId).orElse(null);
    if (!shouldSaveInquiryCard(room, tenant, latestInquiry)) {
      return Optional.empty();
    }

    Message saved =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .type(MessageType.INQUIRY_CARD)
                .inquiryPayload(inquiryPayload)
                .sentAt(now)
                .build());
    ChatRoom roomWithMessage = chatRoomRepository.save(room.recordMessage(saved.getId(), now));

    List<InquiryCardProcessResult.MemberActivity> activities =
        updateMemberVisibility(members, tenantId, now);
    return Optional.of(new InquiryCardProcessResult(roomWithMessage, saved, false, activities));
  }

  /** 방의 두 참여자 중 문의하기를 누른 임차인 행을 찾는다. */
  private static ChatRoomMember findTenantMember(List<ChatRoomMember> members, long tenantId) {
    return members.stream()
        .filter(member -> member.getUserId().equals(tenantId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("채팅방의 임차인 참여자 정보를 찾을 수 없습니다."));
  }

  /** 최신 문의서가 없거나, 보이지 않거나, 문의서 뒤에 다른 메시지가 있으면 새 문의서를 저장한다. */
  private static boolean shouldSaveInquiryCard(
      ChatRoom room, ChatRoomMember tenant, Message latestInquiry) {
    if (latestInquiry == null) {
      return true;
    }
    if (!tenant.canSeeMessage(latestInquiry.getId())) {
      return true;
    }

    // room.lastMessageId가 최신 문의서와 같으면 화면에서 문의서가 바로 보이는 상태이므로 연속 저장하지 않는다.
    Long lastMessageId = room.getLastMessageId();
    return lastMessageId != null && lastMessageId > latestInquiry.getId();
  }

  /** 문의서를 저장한 활동에 맞춰 참여자별 방 표시 상태를 갱신하고 실시간 목록 이벤트 정보를 만든다. */
  private List<InquiryCardProcessResult.MemberActivity> updateMemberVisibility(
      List<ChatRoomMember> members, long tenantId, Instant now) {
    List<InquiryCardProcessResult.MemberActivity> activities = new ArrayList<>(members.size());
    for (ChatRoomMember member : members) {
      // 임차인은 문의하기를 직접 눌렀으므로 즉시 재표시한다. 임대인은 새 문의서가 새 활동으로 도착했을 때만 재표시한다.
      ChatRoomMember visible =
          member.getUserId().equals(tenantId)
              ? member.showAgain(now)
              : member.showForNewActivity(now, now);
      boolean reopened = visible != member;
      if (reopened) {
        memberRepository.save(visible);
      }
      activities.add(
          new InquiryCardProcessResult.MemberActivity(
              visible.getUserId(), visible.getRoomHiddenAt() == null, reopened));
    }
    return activities;
  }
}
