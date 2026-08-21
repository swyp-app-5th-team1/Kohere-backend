/**
 * 1:1 채팅방 신고 Bounded Context다. 사용자가 선택한 고정 사유로 신고를 접수하고, 신고 당시 최근 TEXT 원문을 별도 증거 스냅샷으로 보관한다.
 *
 * <p>신고자와 신고 대상자는 클라이언트가 보내지 않는다. {@code chat :: api} 공개 계약이 방 참여자·개인 숨김 경계를 검사하고 상대방과 원문 증거를 제공한다.
 * report 모듈은 받은 스냅샷을 자기 테이블에 저장해 후속 관리자 검토가 채팅 보존 상태와 독립적으로 진행될 수 있게 한다.
 *
 * <p>현재 범위는 사용자 접수만 포함한다. 관리자 목록·상태 처리·제재·보관 만료 파기는 future 설계에서 구현한다.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Report",
    allowedDependencies = {"common", "chat :: api"})
package com.kohere.report;
