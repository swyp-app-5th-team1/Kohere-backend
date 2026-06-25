package com.kohere.chat.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 채팅방 애그리거트 루트. (매물, 세입자, 임대인) 조합으로 유일하다. 영속 기술(JPA)에 의존하지 않는 순수 도메인 모델이며, 영속 매핑은 infrastructure
 * 계층의 어댑터에서 처리한다(docs/convention/code-style.md §3-3).
 *
 * <p>TODO: 참여자 검증·읽음 위치·고정 카드 등 도메인 불변식 메서드를 채운다.
 */
@Getter
@Builder
public class ChatRoom {

  private final Long id;
  private final String listingId;
  private final Long tenantId;
  private final Long landlordId;
  private final ChatCategory category;
  private final Instant lastMessageAt;
  private final Instant createdAt;
}
