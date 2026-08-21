package com.kohere.chat.api;

/** report 모듈이 채팅방 신고 당시의 현재 보이는 TEXT 증거를 요청하는 공개 query 계약이다. */
public interface ChatReportEvidenceProvider {

  /**
   * 신고자가 볼 수 있는 채팅방인지 확인하고 상대방과 최근 TEXT 원문을 반환한다.
   *
   * @param reporterId JWT에서 확인한 신고자 users.id
   * @param chatRoomId 신고 화면의 현재 채팅방 ID
   * @return 현재 보이는 최근 TEXT 원문과 상대방 정보
   */
  ChatReportEvidenceSnapshot capture(long reporterId, long chatRoomId);
}
