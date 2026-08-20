package com.kohere.chat.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 1:1 매물 채팅방 애그리거트 루트다.
 *
 * <p>{@code (listingId, tenantId, landlordId)} 조합은 DB UNIQUE로 하나만 허용한다. 따라서 문의하기와 신청하기 중 어느 흐름이 먼저
 * 실행돼도 같은 {@code roomId}를 사용한다. 이 클래스는 영속 기술을 모르는 순수 도메인 모델이고 JPA 변환은 infrastructure 어댑터가 담당한다.
 *
 * <p>{@code lastMessageId}는 메시지를 하나만 보관한다는 뜻이 아니다. 모든 메시지는 {@code chat_messages}에 저장하고, 목록 조회를 빠르게
 * 하기 위한 가장 최근 메시지 포인터만 방에 함께 보관한다. 읽음 위치와 안 읽은 개수는 후속 기능이므로 포함하지 않는다.
 */
@Getter
@Builder
public class ChatRoom {

  /** DB가 발급하고 REST에서 {@code chatRoomId}로 사용하는 방 번호다. 신규 저장 전에는 {@code null}이다. */
  private final Long id;

  /** 문의 대상 MongoDB 매물 ObjectId의 문자열 값이다. 모듈 간 값 참조라 JPA 연관관계를 만들지 않는다. */
  private final String listingId;

  /** 방의 임차인 {@code users.id} 값이다. */
  private final Long tenantId;

  /** 방의 임대인 {@code users.id} 값이다. */
  private final Long landlordId;

  /** 현재 내부 저장 범위를 나타내며 이번 구현에서는 {@link ChatCategory#LANDLORD}만 허용한다. */
  private final ChatCategory category;

  /** 매물 변경·삭제와 무관하게 기존 채팅 헤더를 보여 주기 위한 생성 시점 표시 정보다. */
  private final ListingSnapshot listingSnapshot;

  /** {@code chat_messages.id} 중 현재 가장 최근 값이며 메시지가 없는 방에서는 {@code null}이다. */
  private final Long lastMessageId;

  /** 마지막 메시지의 서버 저장 시각이며 {@code lastMessageId}와 함께 설정하거나 함께 비운다. */
  private final Instant lastMessageAt;

  /** 서버가 방을 처음 저장한 UTC 시각이다. */
  private final Instant createdAt;

  /** 마지막 메시지 포인터나 방 메타데이터를 마지막으로 변경한 UTC 시각이다. */
  private final Instant updatedAt;
}
