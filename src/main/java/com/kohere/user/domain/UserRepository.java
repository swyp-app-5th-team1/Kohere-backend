package com.kohere.user.domain;

import java.util.List;
import java.util.Optional;

/**
 * 회원 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 */
public interface UserRepository {

  Optional<User> findById(Long id);

  /**
   * 연락처로 ACTIVE 임대인의 식별자를 조회하면서 <b>그 행에 쓰기 잠금을 건다</b>(SELECT … FOR UPDATE). 잠금은 호출자 트랜잭션이 끝날 때까지
   * 유지된다.
   *
   * <p>메서드 이름에 {@code ForUpdate}를 남기는 것은 의도다 — 잠금은 호출부의 정합성 전제이지 최적화가 아니라서, 이름에서 사라지면 "그냥 조회"로 오해되고
   * 다른 조회로 갈아 끼워진다. 반환이 식별자뿐인 것도 같은 이유다: 호출부(웹 가입 연동 판정)가 필요한 것은 <b>존재 여부와 id</b>뿐이고, 프로필을 돌려주면 그
   * 값을 폼 값으로 덮어쓰는 코드가 붙기 쉽다(연동 시 {@code users}는 한 칼럼도 바꾸지 않는다 — ADR-0047 §6).
   *
   * @param phoneNumber 숫자만 남긴 표준형 번호
   */
  Optional<Long> findActiveLandlordIdByPhoneNumberForUpdate(String phoneNumber);

  /**
   * 위와 같은 잠금 조회에 <b>자기 자신 제외</b>를 더한 형태 — 앱 임대인 온보딩의 웹 계정 병합(US-1-15) 전용이다. 병합은 "이미 로그인해 있는 계정"이 자기
   * 번호로 조회를 돌리는 상황이라, 제외가 없으면 <b>자기 자신을 병합 대상으로 잡아</b> 소셜 매핑을 자기에게 옮기고 자기 행을 지우는 자기파괴가 된다(지금은 온보딩
   * 전이라 번호가 비어 있어 걸리지 않지만, 그 역시 기대면 안 되는 암묵 불변식이다).
   *
   * <p><b>여기만 {@link User}를 돌려주는 것도 의도다.</b> 위 조회가 식별자만 주는 이유는 호출부(웹 가입 연동)가 <b>존재 여부</b>만 알면 되고
   * 프로필을 쥐여주면 폼 값으로 덮어쓰는 코드가 붙기 쉬워서인데, 병합은 반대로 <b>대상 계정의 프로필을 그대로 응답에 실어야</b> 한다(토큰도 프로필도 대상 기준이다 —
   * 스펙 §5-2). 이미 잠금으로 읽은 행을 식별자만 남기고 버린 뒤 같은 행을 또 조회하는 것은 낭비이고, 두 번째 조회는 잠금 밖의 값이라 첫 조회와 어긋날 여지까지
   * 생긴다. 병합 경로는 대상 행을 <b>한 칼럼도 쓰지 않으므로</b>(ADR-0047 §6) 덮어쓰기 위험도 없다.
   *
   * @param phoneNumber 숫자만 남긴 표준형 번호
   * @param excludedUserId 병합을 요청한 임시 계정(자기 자신)의 식별자
   */
  Optional<User> findActiveLandlordByPhoneNumberExcludingForUpdate(
      String phoneNumber, long excludedUserId);

  /**
   * 인증된 휴대폰 번호로 <b>웹 로그인이 가능한 계정 후보</b>를 찾는다 — 이메일 찾기(US-1-16)의 첫 홉이며 <b>잠금을 걸지 않는다</b>.
   *
   * <p>위 두 {@code ForUpdate} 조회와 이름을 다르게 두는 것은 의도다 — 그쪽은 check-then-act 앞단 전용이라 잠금이 호출부의 정합성 전제이고,
   * 이쪽은 permitAll 순수 조회라 잠금을 걸면 <b>익명 호출자가 남의 행을 붙잡는</b> 표면이 된다.
   *
   * <p><b>{@code ADMIN}도 대상에 넣는다.</b> 운영자 계정은 임대인 웹 가입으로 만든 뒤 수동 승격한 것이라 로그인 경로가 임대인과 완전히 같은데, 역할을
   * {@code LANDLORD}로 고정하면 관리자가 로그인 ID를 잊었을 때 복구 수단이 하나도 없다. 그 결과 같은 번호로 두 행이 잡힐 수 있어 반환이 {@code
   * List}이며, 그중 무엇을 고를지는 <b>웹 자격증명이 붙어 있는지</b>로 호출부(auth)가 판정한다.
   *
   * @param phoneNumber 숫자만 남긴 표준형 번호
   * @return 조건을 만족하는 회원 식별자들(없으면 빈 목록)
   */
  List<Long> findActiveWebUserIdsByPhoneNumber(String phoneNumber);

  /** 닉네임 전역 유니크 충돌 검사용(NicknameGenerator). */
  boolean existsByNickname(String nickname);

  /**
   * 회원 행을 <b>하드 삭제</b>한다. 유일한 호출부는 웹 계정 병합(US-1-15)이며, 지우는 대상은 사람의 계정이 아니라 <b>몇 분 전 소셜 로그인이 만든 빈
   * 껍데기</b>다 — 탈퇴({@code WITHDRAWN} 전이 + 익명화, ADR-0014)를 쓰지 않는 이유가 이것이다. 보존할 이력이 없고, 남겨 두면 번호도 비어
   * 있는 유령 계정이 하나 더 생길 뿐이다. 미완료 계정을 {@code DELETE}로 정리한 V21의 선례를 따른다.
   *
   * <p>없는 식별자면 아무 일도 하지 않는다(멱등) — 병합은 잠금 조회 뒤 같은 트랜잭션에서 부르므로 정상 경로에서 그런 일이 없지만, 여기서 예외를 던져 병합을 되돌릴
   * 이유도 없다.
   */
  void deleteById(long userId);

  /** 신규 저장·변경 저장(upsert). 신규는 식별자가 채워진 인스턴스를 반환한다. */
  User save(User user);
}
