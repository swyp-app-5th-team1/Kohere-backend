/**
 * booking 모듈이 다른 기능에 공개하는 읽기 전용 계약이다.
 *
 * <p>다른 모듈은 booking의 저장소나 내부 도메인 타입에 직접 접근하지 않고 이 공개 경계를 통해 필요한 결과만 받는다.
 */
@org.springframework.modulith.NamedInterface("api")
package com.kohere.booking.api;
