/**
 * 커뮤니티(게시판·동네친구) Bounded Context. 자유게시판({@code FREE})·동네생활({@code NEIGHBORHOOD})의 게시글
 * 작성/수정/삭제/목록/상세, 댓글, 좋아요 토글, 공유 카운트, 그리고 게시글 작성자와의 동네친구 1:1 채팅 시작을 담당한다.
 *
 * <p>도메인 에러 코드 prefix: {@code POST}/{@code COMMENT}. 스펙: docs/api/specs/05-community.md.
 *
 * <p>모듈 경계·계층 규칙은 docs/convention/code-style.md §3을 따른다. 공유 커널 {@code common}에만 의존한다.
 *
 * <p>TODO: 동네친구 채팅 시작은 04(채팅) 스펙의 {@code NEIGHBOR} 카테고리 채팅방 생성을 chat 모듈에 위임한다. 모듈 간 직접 호출 대신 공개
 * API·이벤트로 연결하며, 그 전까지는 application 계층의 placeholder로 둔다(chat 모듈 타입을 import 하지 않는다).
 *
 * <p>TODO: 작성자 닉네임·국적·탈퇴 여부 마스킹은 user 모듈의 공개 API·이벤트로 받아온다(직접 의존 금지).
 *
 * <p>TODO: 구현 시 <b>세입자·임대인 허용 목록 게이트</b>를 함께 넣는다(US-3-7). 관리자({@code userType=ADMIN})는 관리자 전용 API만
 * 호출할 수 있는데, {@code hasRole("USER")} 매처는 회원 유형을 보지 않아 관리자도 통과한다. 다른 모듈의 {@code AppUserGuard}와 같은
 * 모양이며 {@code allowedDependencies}에 {@code user :: api}를 더해야 한다. 지금은 전 메서드가 스텁이라 게이트를 붙일 대상이 없어
 * 보류한다.
 *
 * <p>TODO: 채팅 차단({@code POST_CHAT_BLOCKED})은 report 모듈의 차단 모델에 의존한다(확정 후 공개 API·이벤트로 연결).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Community",
    allowedDependencies = {"common"})
package com.kohere.community;
