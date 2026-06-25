package com.kohere.chat.application;

import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.application.dto.ReadResponse;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.presentation.dto.ReadRequest;
import com.kohere.chat.presentation.dto.SendMessageRequest;
import com.kohere.common.response.CursorResponse;
import com.kohere.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채팅 유스케이스 조율. 도메인(포트)을 호출하고 흐름만 조율한다. 도메인 규칙은 엔티티/도메인 서비스에 둔다 (docs/convention/code-style.md
 * §3-3).
 *
 * <p>의존성은 생성자 주입({@code @RequiredArgsConstructor})으로 받는다(§3-4). 인증 주체(userId)는 SecurityContext에서
 * 가져온다(TODO: 보안 설정 후 연동). 본인 참여 방 검증·본인 매물 문의 차단도 여기서 수행한다(TODO).
 *
 * <p>TODO: 영속 계층(JPA) 도입 시 유스케이스에 트랜잭션 경계({@code @Transactional})를 추가한다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatRoomRepository chatRoomRepository;
  private final MessageRepository messageRepository;

  public InquiryResponse createInquiry(String listingId) {
    throw new UnsupportedOperationException("TODO: 매물 문의(임대인 채팅방 생성/조회 + 매물 카드 고정)");
  }

  public PageResponse<ChatRoomResponse> listRooms(String category, int page, int size) {
    throw new UnsupportedOperationException("TODO: 내 채팅방 리스트 조회(카테고리 필터·lastMessageAt desc)");
  }

  public CursorResponse<MessageResponse> getMessages(Long roomId, String cursor, int size) {
    throw new UnsupportedOperationException("TODO: 채팅방 메시지 조회(커서 페이지네이션, 최신순)");
  }

  public MessageResponse sendMessage(Long roomId, SendMessageRequest request) {
    throw new UnsupportedOperationException("TODO: 텍스트 메시지 전송(lastMessageAt 갱신 + 푸시 이벤트)");
  }

  public ReadResponse markRead(Long roomId, ReadRequest request) {
    throw new UnsupportedOperationException("TODO: 읽음 처리(마지막 읽은 메시지까지 전진, 멱등)");
  }
}
