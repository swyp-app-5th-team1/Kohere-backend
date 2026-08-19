package com.kohere.chat.domain;

import java.util.Optional;

/**
 * 채팅방 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 *
 * <p>TODO: 참여자별 채팅방 목록 조회(lastMessageAt desc)와 (매물·세입자·임대인) 조합 조회 메서드를 추가한다. 내부 category는 유지하지만 현재
 * 사용자 API의 필터로 노출하지 않는다.
 */
public interface ChatRoomRepository {

  Optional<ChatRoom> findById(Long roomId);
}
