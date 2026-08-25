package com.kohere.user.infrastructure;

import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.UserType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Spring Data JPA 리포지토리(내부). 도메인 포트 {@code UserRepository}의 어댑터가 이를 사용한다. */
interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

  boolean existsByNickname(String nickname);

  /**
   * 연락처 + 상태 + 역할로 회원 한 행을 <b>쓰기 잠금(SELECT … FOR UPDATE)</b>과 함께 조회한다 — 웹 가입의 계정 연동 판정(US-1-11)이
   * check-then-act라 판정과 삽입 사이에 다른 트랜잭션이 끼어들지 못하게 막는다.
   *
   * <p><b>이 저장소의 첫 비관적 잠금</b>이다. 스칼라 프로젝션({@code select u.id …})이 아니라 엔티티를 그대로 읽는 파생 쿼리를 쓴 것은,
   * {@code @Lock} + 파생 쿼리가 Spring Data에서 가장 검증된 조합이라 잠금 렌더링에 기대할 것이 확실하기 때문이다. 한 행짜리 조회라 읽는 컬럼이 늘어도
   * 비용 차이가 없다.
   *
   * <p><b>영속성 컨텍스트에 올라온 엔티티가 {@code UserRepositoryImpl.save}와 충돌하지 않는다</b> — 그쪽은 도메인 객체를 새 엔티티로 조립해
   * {@code save}(=id가 있으면 {@code merge})를 부르는데, {@code merge}는 같은 식별자의 관리 인스턴스가 이미 있으면 <b>그 인스턴스에
   * 상태를 복사</b>할 뿐 새로 붙이지 않는다. 게다가 행 잠금은 인스턴스가 아니라 <b>DB 트랜잭션</b>이 쥐고 있어 커밋·롤백 전까지 유지된다.
   *
   * <p>{@code uq_users_phone_number}(V23)가 있어 결과는 최대 한 행이다 — {@code Optional} 반환이 성립하는 근거다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findByPhoneNumberAndStatusAndUserType(
      String phoneNumber, UserStatus status, UserType userType);

  /**
   * 위 조회에 <b>자기 자신 제외({@code IdNot})</b>를 더한 것 — 앱 임대인 온보딩의 웹 계정 병합(US-1-15)이 쓴다. 잠금·조건·반환 모양이 같아야
   * 두 방향(연동·병합)의 매칭 규칙이 갈리지 않으므로, 조건을 하나 더한 <b>형제 파생 쿼리</b>로 두고 위 메서드를 고치지 않는다 — 인자 하나를 더해 한 메서드로
   * 합치면 웹 가입(US-1-11) 쪽이 "제외 대상 없음"을 표현하려고 {@code null}이나 {@code -1} 같은 sentinel을 넘겨야 하고, 그
   * sentinel이 쿼리에 그대로 흘러 들어간다.
   *
   * <p>{@code status}·{@code userType}을 계속 명시하는 이유도 위와 같다 — 지금은 번호가 채워진 계정이 사실상 ACTIVE 임대인뿐이라 중복
   * 조건이지만, 다른 경로가 {@code PENDING} 계정이나 세입자에 번호를 채우는 날 <b>남의 계정을 흡수하는</b> 병합이 된다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findByPhoneNumberAndStatusAndUserTypeAndIdNot(
      String phoneNumber, UserStatus status, UserType userType, Long id);

  /**
   * 연락처 + 상태 + <b>역할 집합</b>으로 회원을 조회한다 — 이메일 찾기(US-1-16)가 쓰는 <b>잠금 없는 읽기</b>다.
   *
   * <p><b>위 두 메서드를 재사용하지 않는 이유는 잠금이다.</b> 그쪽은 {@code SELECT … FOR UPDATE}인데, 이메일 찾기는 인증되지 않은 호출자가
   * 부르는 permitAll 순수 조회다. 그대로 쓰면 익명 호출자가 임의 번호로 {@code users} 행에 <b>쓰기 잠금</b>을 걸어 그 사람의 가입·온보딩·병합
   * 트랜잭션과 락 경합을 유발할 수 있다(게다가 그 구현은 {@code readOnly}가 아닌 쓰기 트랜잭션을 연다).
   *
   * <p><b>{@code List}를 돌려주는 이유</b> — {@code uq_users_phone_number}는 V28에서 {@code (user_type,
   * phone_number)} 복합 유니크가 되었으므로 같은 번호로 {@code LANDLORD}와 {@code ADMIN} 두 행이 <b>정상적으로 공존할 수
   * 있다</b>(운영자 계정은 임대인 웹으로 가입해 수동 승격한다). {@code Optional}로 받으면 그 조합에서 {@code
   * IncorrectResultSizeDataAccessException}이 터진다.
   *
   * <p>성능 註 — 복합 유니크의 leftmost prefix가 {@code user_type}이라 <b>번호 단독 조건은 그 인덱스를 타지 못한다</b>. 역할 집합을
   * 함께 거는 이 조회도 마찬가지이며(IN은 prefix를 만족시키지 못한다), 호출 빈도가 낮아 수용한다.
   */
  List<UserJpaEntity> findByPhoneNumberAndStatusAndUserTypeIn(
      String phoneNumber, UserStatus status, Collection<UserType> userTypes);
}
