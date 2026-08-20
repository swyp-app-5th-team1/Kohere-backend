/**
 * 채팅 Bounded Context. 매물 임대인과의 1:1 채팅방(ChatRoom)과 메시지(Message)를 담당한다. 문의(inquiry)는 매물 임대인과의 채팅방을
 * 생성/반환하고, 채팅방 목록·메시지 조회·STOMP TEXT 전송·서버 BOOKING_CARD 생성을 제공한다. 읽음 처리는 후속 범위이며 커뮤니티의 이웃(NEIGHBOR)
 * 채팅도 현재 사용자 API에는 노출하지 않는다.
 *
 * <p>도메인 에러 코드 prefix: {@code CHAT}. 현재 채팅 스펙: docs/architecture/chat/README.md.
 *
 * <p>모듈 경계·계층 규칙은 docs/convention/code-style.md §3을 따른다. 공유 커널 {@code common}에 의존하고, 모듈 간 통신은 도메인
 * 이벤트 기반이다(ADR-0002). booking 모듈이 발행하는 {@code BookingCreatedEvent}를 구독하므로 {@code booking}을 허용 의존에
 * 둔다(이벤트 타입에 대한 단방향 의존; booking은 chat을 모른다). 문의 방을 만들 때는 {@code listing :: api}로 공개 매물·임대인을 확인하고,
 * 참여자 표시 정보·언어·차단 여부는 {@code user :: api} 공개 계약으로만 조회한다.
 *
 * <p>문의 방 생성처럼 즉시 결과가 필요한 협력은 공개 query API로, 신청 카드 생성처럼 비동기 부수효과는 도메인 이벤트로 연결한다. 다른 모듈의 내부 도메인·영속
 * 타입을 직접 import하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Chat",
    allowedDependencies = {"common", "booking", "listing :: api", "user :: api"})
package com.kohere.chat;
