package com.kohere.chat.application;

import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 한 행과 임차인·임대인 참여자 두 행을 같은 MySQL 트랜잭션으로 저장한다.
 *
 * <p>별도 컴포넌트로 분리한 이유는 {@link ChatService}가 동시 생성 UNIQUE 충돌을 트랜잭션 밖에서 처리할 수 있게 하기 위해서다. 실패한 JPA 트랜잭션
 * 안에서 기존 방을 다시 조회하면 rollback-only 상태 때문에 조회 결과를 안전하게 사용할 수 없다. 문의로 시작하는 방은 첫 INQUIRY_CARD와 마지막 메시지
 * 포인터까지 이 트랜잭션에 포함한다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomCreator {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;
  private final ChatMessageCreatedEventPublisher pushEventPublisher;

  /**
   * 새 채팅방과 정확히 두 명의 참여자를 원자적으로 저장한다.
   *
   * <p>방 저장이나 두 참여자 저장 중 하나라도 실패하면 {@link Transactional} 경계가 전체 작업을 롤백한다. 따라서 참여자가 한 명뿐인 불완전한 방은 남지
   * 않는다.
   *
   * @param seed 검증이 끝난 매물의 실제 임대인과 표시 정보
   * @param tenantId JWT에서 얻은 임차인 사용자 ID
   * @param now 방과 참여자의 공통 생성 시각
   * @return DB가 발급한 ID를 포함한 새 채팅방
   */
  @Transactional
  public ChatRoom create(ChatRoomSeed seed, long tenantId, Instant now) {
    return createRoomAndMembers(seed, tenantId, now);
  }

  /**
   * 문의로 처음 만드는 채팅방·참여자 두 명·문의서 첫 메시지를 원자적으로 저장한다.
   *
   * <p>문의서가 빠진 빈 문의 방이나 방 없이 남은 문의서가 생기지 않도록 네 저장 작업을 하나의 트랜잭션으로 묶는다. 저장을 마친 문의서 ID는 방 목록의 마지막 메시지
   * 포인터가 된다.
   *
   * @param seed 검증이 끝난 매물의 실제 임대인과 방 표시 정보
   * @param tenantId JWT에서 얻은 임차인 사용자 ID
   * @param inquiryPayload 서버가 공개 매물에서 만든 문의서 사본
   * @param now 방·참여자·문의서의 공통 생성 시각
   * @return 저장된 방과 문의서 메시지
   */
  @Transactional
  public InquiryRoomCreation createInquiry(
      ChatRoomSeed seed, long tenantId, InquiryCardPayload inquiryPayload, Instant now) {
    ChatRoom room = createRoomAndMembers(seed, tenantId, now);
    Message inquiryMessage =
        messageRepository.save(
            Message.builder()
                .chatRoomId(room.getId())
                .type(MessageType.INQUIRY_CARD)
                .inquiryPayload(inquiryPayload)
                .sentAt(now)
                .build());

    // 첫 메시지 저장이 성공한 뒤에만 목록 정렬과 미리보기가 문의서를 가리키도록 방 포인터를 갱신한다.
    ChatRoom roomWithMessage =
        chatRoomRepository.save(room.recordMessage(inquiryMessage.getId(), now));

    // 문의를 실행한 임차인 본인이 아니라 새 문의를 받은 임대인에게만 외부 푸시 후보를 만든다.
    pushEventPublisher.publish(roomWithMessage, inquiryMessage, room.getLandlordId());
    return new InquiryRoomCreation(roomWithMessage, inquiryMessage);
  }

  /** 방과 두 참여자를 만드는 공통 부분을 문의·신청 생성 경로가 같은 규칙으로 사용하게 한다. */
  private ChatRoom createRoomAndMembers(ChatRoomSeed seed, long tenantId, Instant now) {
    ChatRoom room =
        chatRoomRepository.save(
            ChatRoom.builder()
                .listingId(seed.listingId())
                .tenantId(tenantId)
                .landlordId(seed.landlordId())
                .category(ChatCategory.LANDLORD)
                // 매물이 나중에 비공개·삭제돼도 기존 방 제목과 주소를 표시할 수 있도록 생성 시점 값을 보존한다.
                .listingSnapshot(new ListingSnapshot(seed.title(), seed.address()))
                .createdAt(now)
                .updatedAt(now)
                .build());

    // 참여자 상태는 사용자별 숨김·삭제 경계를 독립적으로 관리해야 하므로 방 행과 별도로 정확히 두 행을 만든다.
    memberRepository.saveAll(
        List.of(
            newMember(room.getId(), tenantId, seed.landlordId(), ChatParticipantRole.TENANT, now),
            newMember(
                room.getId(), seed.landlordId(), tenantId, ChatParticipantRole.LANDLORD, now)));
    return room;
  }

  /** 역할과 상대가 서로 뒤바뀌지 않도록 신규 참여자 생성 코드를 한곳에 둔다. */
  private static ChatRoomMember newMember(
      long roomId, long userId, long counterpartId, ChatParticipantRole role, Instant now) {
    return ChatRoomMember.builder()
        .chatRoomId(roomId)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** 신규 문의 트랜잭션의 최종 방과 첫 문의서 메시지를 호출자에게 함께 돌려주는 결과다. */
  public record InquiryRoomCreation(ChatRoom room, Message message) {}
}
