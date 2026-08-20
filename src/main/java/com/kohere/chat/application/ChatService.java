package com.kohere.chat.application;

import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.common.response.CursorResponse;
import com.kohere.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채팅 REST 유스케이스를 조율하는 응용 서비스다.
 *
 * <p>컨트롤러가 받은 식별자와 인증 사용자를 도메인·repository port에 연결하되, 참여자 검증·사용자별 숨김 경계·멱등 방 생성 같은 규칙은 도메인과 트랜잭션
 * 안에서 일관되게 적용한다. 후속 기능 단계에서는 컨트롤러가 {@code @AuthenticationPrincipal AuthPrincipal}에서 검증된 {@code
 * userId}를 꺼내 각 서비스 메서드에 전달하며, body나 query로 사용자 ID를 선택하게 두지 않는다.
 *
 * <p>TEXT 저장은 STOMP 처리 흐름의 별도 유스케이스가 담당한다. REST 전송 메서드를 이 서비스에 함께 두지 않아 같은 메시지에 두 개의 진입 경로와 서로 다른
 * 중복 처리 규칙이 생기는 것을 막는다. 읽음 처리는 이번 범위에서 제외한다.
 *
 * <p>TODO: 후속 단계에서 실제 상태 변경 유스케이스를 구현할 때 각 명령에 트랜잭션 경계를 추가한다. 영속 어댑터 자체는 준비됐지만, 방 생성·메시지 저장의 원자성은
 * repository 한 개가 아니라 응용 서비스가 묶어야 한다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatRoomRepository chatRoomRepository;
  private final MessageRepository messageRepository;

  /**
   * 매물·세입자·임대인 조합의 채팅방을 조회하거나 하나만 생성한다.
   *
   * @param listingId 문의 대상 매물 식별자
   * @return 방 ID와 이번 호출에서 새로 생성했는지 여부
   */
  public InquiryResponse createInquiry(String listingId) {
    throw new UnsupportedOperationException("TODO: 매물 문의 채팅방 생성 또는 조회");
  }

  /**
   * 현재 사용자에게 보이는 채팅방만 최근 활동 순으로 조회한다.
   *
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 항목 수
   * @return 채팅방 목록과 페이지 메타데이터
   */
  public PageResponse<ChatRoomResponse> listRooms(int page, int size) {
    throw new UnsupportedOperationException("TODO: 내 채팅방 목록 조회(lastMessageAt desc)");
  }

  /**
   * 한 채팅방의 저장된 메시지를 과거 조회 또는 재연결 누락 보충 방식으로 읽는다.
   *
   * <p>응용 계층은 요청자가 참여자인지와 사용자별 삭제 경계를 함께 확인한다. 원문은 항상 반환하고 현재 사용자를 위한 저장된 번역본이 있을 때만 별도 translation
   * 객체를 붙인다.
   *
   * @param roomId 조회 대상 채팅방 식별자
   * @param cursor 과거 방향 조회 기준 메시지 ID
   * @param afterMessageId 미래 방향 누락 조회 기준 메시지 ID
   * @param size 조회할 최대 메시지 수
   * @return 메시지 목록과 다음 커서 정보
   */
  public CursorResponse<MessageResponse> getMessages(
      Long roomId, String cursor, String afterMessageId, int size) {
    throw new UnsupportedOperationException("TODO: 과거 또는 누락 메시지 커서 조회");
  }
}
