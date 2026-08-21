package com.kohere.report.domain;

/**
 * 채팅방 신고 사유의 언어와 무관한 고정 코드다.
 *
 * <p>화면의 한국어·영어 문구는 프런트가 현지화한다. 백엔드는 사용자가 보낸 문자열이 이 enum 중 하나인지 검증하고, DB에는 문구가 아닌 이 코드를 저장한다. 코드가
 * 언어에 따라 달라지지 않으므로 후속 관리자 화면과 통계도 같은 값을 안정적으로 사용할 수 있다.
 */
public enum ReportReason {
  ABUSE_HARASSMENT_DISCRIMINATION,
  ILLEGAL_CONTENT,
  SEXUAL_INAPPROPRIATE_CONTENT,
  PERSONAL_INFORMATION,
  SPAM,
  OTHER
}
