/**
 * 다른 모듈이 사용할 수 있는 chat 모듈의 공개 계약이다.
 *
 * <p>현재 report 모듈은 채팅방 신고 증거를 만들 때 이 Named Interface만 사용한다. 내부 도메인 객체나 JPA 엔티티를 노출하지 않아 채팅 저장 구조가
 * 바뀌어도 신고 모듈의 결합을 최소화한다.
 */
@org.springframework.modulith.NamedInterface("api")
package com.kohere.chat.api;
