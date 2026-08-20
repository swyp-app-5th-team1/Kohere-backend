package com.kohere.chat.domain;

import java.util.List;

/**
 * 로그인 사용자에게 현재 보이는 채팅방 참여자 행의 한 페이지다.
 *
 * <p>목록 정렬에는 {@code chat_rooms}의 마지막 활동 시각이 필요하지만, 사용자별 숨김 여부는 {@code chat_room_members}가 소유한다.
 * infrastructure가 두 테이블을 조인해 정렬한 뒤, 응용 계층에는 Spring Data {@code Page} 대신 이 작은 도메인 값만 반환한다.
 *
 * @param content 현재 페이지에 포함된 사용자별 채팅방 상태. DB가 정한 최근 활동 순서를 유지한다.
 * @param totalElements 로그인 사용자에게 보이는 전체 채팅방 수
 */
public record ChatRoomMemberPage(List<ChatRoomMember> content, long totalElements) {

  /** 호출자가 전달한 변경 가능한 목록이 페이지 내부 상태를 바꾸지 못하도록 불변 사본을 보관한다. */
  public ChatRoomMemberPage {
    content = List.copyOf(content);
  }
}
