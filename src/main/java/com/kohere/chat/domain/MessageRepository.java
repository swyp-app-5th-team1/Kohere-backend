package com.kohere.chat.domain;

import java.util.Optional;

/**
 * 메시지 영속 포트. 구현은 infrastructure 계층에 둔다(docs/convention/code-style.md §3-3).
 *
 * <p>TODO: 방별 과거 cursor 조회와 재연결용 afterMessageId 조회를 추가한다. 안읽음 수 집계는 후속 기능에서 구현한다.
 */
public interface MessageRepository {

  Optional<Message> findById(Long messageId);
}
