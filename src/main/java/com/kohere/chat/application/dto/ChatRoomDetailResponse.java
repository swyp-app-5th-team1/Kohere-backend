package com.kohere.chat.application.dto;

import com.kohere.chat.domain.ChatParticipantRole;

/**
 * 채팅방 화면을 열 때 필요한 헤더와 참여자 정보를 한 번에 반환한다.
 *
 * <p>메시지 이력과 분리한 이유는 새로고침·딥링크에서도 대화 목록을 먼저 내려받지 않고 방의 소유권과 표시 정보를 확인할 수 있게 하기 위해서다. 차단 여부는 요청자 관점의
 * 값이다. 양방향 중 하나라도 차단 관계면 {@code blocked=true}지만, 누가 차단했는지는 별도 필드로 노출하지 않는다.
 */
public record ChatRoomDetailResponse(
    /** 서버가 발급한 채팅방 식별자. 매물 식별자와 다른 값이다. */
    Long chatRoomId,
    /** 현재 로그인 사용자가 이 방에서 맡는 역할. 프런트가 임의로 정하지 않는다. */
    ChatParticipantRole myRole,
    /** 방 생성 시 저장한 매물 표시용 사본. */
    ChatListingSummaryResponse listing,
    /** 현재 사용자와 반대편에 있는 참여자. */
    ChatCounterpartResponse counterpart,
    /** 어느 방향이든 차단 관계가 있어 새 채팅을 보낼 수 없으면 true. 차단 방향은 드러내지 않는다. */
    boolean blocked) {}
