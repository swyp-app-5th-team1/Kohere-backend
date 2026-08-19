package com.kohere.chat.domain;

/** 채팅방에서 로그인 사용자의 역할. 앱은 이 값으로 신청 카드를 임차인용 또는 임대인용으로 표시한다. */
public enum ChatParticipantRole {
  TENANT,
  LANDLORD
}
