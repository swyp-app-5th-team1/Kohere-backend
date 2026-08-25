package com.kohere.auth.domain;

import java.util.Optional;

/**
 * 이메일 찾기용 연락처 인증 상태 영속 포트(Redis 백킹). 인증번호 챌린지({@code find-email:code:{정규화번호}})와 검증 완료 마커({@code
 * find-email:verified:{정규화번호}})를 다룬다. 구현은 infrastructure(ADR-0006 패턴). 스펙 §1-5·§1-6.
 *
 * <p><b>가입용 {@link SignupPhoneVerificationRepository}와 메서드 모양이 같지만 같은 포트를 쓰지 않는다</b> — 그쪽을 주입받는 순간
 * 이 흐름이 {@code signup-phone:*} 키를 읽고 쓰게 되고, 마커에 용도 필드가 없어 <b>이메일 찾기용 인증 하나로 회원가입(§1-3) 게이트까지
 * 통과</b>한다(인가 범위 확대). 반대 방향도 성립한다 — 가입 화면에서 받은 마커가 §1-7의 이메일 조회를 열어 준다.
 *
 * <p>전달하는 번호는 <b>모두 정규화된 값</b>이어야 하며, 정규화는 응용 계층 경계에서 한 번만 한다({@code
 * FindEmailPhoneVerificationService}).
 *
 * <p>검증 마커의 <b>소비처는 이메일 찾기(§1-7) 하나뿐</b>이다 — 웹 회원가입(§1-3)이 아니다.
 */
public interface FindEmailPhoneVerificationRepository {

  /** 인증번호 챌린지 저장(TTL=만료 시각). 재발송·시도 누적 시 기존 레코드를 대체한다. */
  void saveChallenge(FindEmailPhoneVerification challenge);

  Optional<FindEmailPhoneVerification> findChallenge(String phoneNumber);

  void deleteChallenge(String phoneNumber);

  /** 검증 완료 마커 저장(TTL 30분). 값은 존재 자체가 의미인 상수라 번호를 담지 않는다 — 번호가 곧 키다. */
  void markVerified(String phoneNumber, long ttlSeconds);

  /**
   * 검증 완료 마커가 살아 있는지. 값은 상수이고 <b>키의 존재 자체가 판정</b>이라 값을 읽어 비교하지 않는다(만료는 Redis TTL이 지운다).
   *
   * <p>이메일 찾기(§1-7)의 첫 게이트다 — 없으면 422 {@code AUTH_PHONE_NOT_VERIFIED}이고 계정 조회 자체를 하지 않는다. 이 게이트가
   * 있어서 호출자가 조회할 수 있는 번호가 <b>방금 소유를 증명한 자기 번호 하나</b>로 닫히고, 그래서 §1-7이 계정 부재를 404로 드러내도 열거가 성립하지 않는다.
   */
  boolean isVerified(String phoneNumber);

  /**
   * 검증 완료 마커 소비(삭제). 이메일 조회에 <b>성공한</b> 뒤 한 번 지워 마커 하나로 무제한 반복 조회하는 것을 막는다(1회용).
   *
   * <p>마커가 이미 없어도(TTL 만료·중복 호출) 실패하지 않는 멱등 삭제다.
   */
  void deleteVerified(String phoneNumber);
}
