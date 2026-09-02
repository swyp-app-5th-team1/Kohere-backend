package com.kohere.listing.application;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;
import com.kohere.user.api.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임대인 계정 이름을 {@code user}에서 가져온다. <b>알 수 없으면 {@code null}</b>이고 호출자는 그 키를 응답에서 뺀다.
 *
 * <p><b>왜 서비스 안의 private 메서드가 아니라 별도 빈인가</b> — 이 조회는 <b>호출자의 트랜잭션 밖에서</b> 일어나야 하는데, 전파 설정은 프록시를 거칠
 * 때만 적용되므로 같은 클래스 안에서는 표현할 수 없다(자기 호출은 프록시를 타지 않는다). 이 타입의 존재 이유가 그 경계 하나다.
 *
 * <p><b>왜 트랜잭션 밖이어야 하는가 — 이 클래스의 핵심이다.</b> {@code getUserName}은 계정 행이 없으면 예외를 던진다. 그 예외를 호출자의 트랜잭션
 * <b>안에서</b> 잡으면 문제가 조용히 남는다: 예외가 잡히기 전에 이미 트랜잭션 인터셉터를 거치는데, 참여(participating) 트랜잭션의 실패는 스프링이 바깥
 * 트랜잭션을 <b>rollback-only로 표시</b>하기 때문이다({@code isGlobalRollbackOnParticipationFailure} 기본값). 잡아서 정상
 * 반환해도 바깥 메서드가 커밋할 때 {@code UnexpectedRollbackException}이 터지고, 전역 핸들러의 마지막 그물에 걸려 <b>500</b>이 나간다 —
 * 부재를 조용히 넘기려던 의도와 정반대다.
 *
 * <p>{@link Propagation#NOT_SUPPORTED}가 그 고리를 끊는다. 호출자의 트랜잭션을 잠시 밀어 두므로 {@code getUserName}이 <b>자기
 * 트랜잭션</b>을 새로 열고, 실패는 그 안에서 실제 롤백으로 끝난다. 밀어 뒀던 트랜잭션은 아무 표시 없이 되돌아온다.
 *
 * <p><b>예외 타입이 아니라 {@link ErrorCode}로 가려내는 이유는 모듈 경계다.</b> {@code UserNotFoundException}은 {@code
 * user}의 내부 도메인 타입이라 이 모듈이 부를 수 있는 이름이 아니다({@code user}가 노출하는 것은 {@code api} 하나뿐이고, 그 {@code
 * package-info}가 "내부 도메인/영속 타입은 노출하지 않는다"고 못박았다). 두 모듈이 함께 아는 것은 공유 커널의 {@link BusinessException}과
 * {@link ErrorCode}뿐이다.
 */
@Component
@RequiredArgsConstructor
class LandlordNameLookup {

  private final UserAccountService userAccountService;

  /**
   * 임대인 계정 이름. 알 수 없으면 {@code null}이다.
   *
   * <p>삼키는 것은 <b>"그런 계정이 없다" 하나뿐</b>이다. 그 밖의 코드는 그대로 다시 던져 조용한 실패로 덮지 않는다 — 심사 대상은 매물이고 이름은 표시
   * 보조값이지만, 저장소 장애까지 이름 없음으로 위장하면 관리자가 잘못된 화면을 보고 심사한다.
   *
   * <p>빈 문자열도 {@code null}로 접는다. {@code getUserName}은 이름 미설정(소셜 provider 미제공·탈퇴 익명화)을 빈 문자열로 주는데,
   * 그대로 실으면 "이름이 없다"와 "이름이 빈칸이다"가 응답에서 갈리지 않는다({@code null}은 계약상 오지 않지만 접는 규칙이 같아 함께 둔다).
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String nameOf(Long landlordId) {
    if (landlordId == null) {
      return null;
    }
    try {
      String name = userAccountService.getUserName(landlordId);
      return name == null || name.isBlank() ? null : name;
    } catch (BusinessException e) {
      if (e.getErrorCode() != ErrorCode.USER_NOT_FOUND) {
        throw e;
      }
      return null;
    }
  }
}
