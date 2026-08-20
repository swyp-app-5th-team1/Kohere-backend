package com.kohere.chat.application.dto;

/**
 * 현재 로그인 사용자를 기준으로 계산한 1:1 채팅 상대 정보다.
 *
 * <p>클라이언트가 상대 ID를 요청으로 지정하게 두지 않고, 서버가 채팅방의 두 참여자 중 본인이 아닌 사용자를 선택한다. 따라서 차단·신고 대상도 이 값과 같은 기준으로
 * 결정할 수 있다. 현재 user 모듈에는 프로필 이미지 계약이 없으므로 이미지 URL을 반환하지 않으며, 앱은 기본 프로필 아이콘을 표시한다.
 */
public record ChatCounterpartResponse(
    /** 상대 사용자의 {@code users.id}. */
    Long userId,
    /** 목록과 채팅방 헤더에 표시할 상대 이름. */
    String displayName) {}
