package com.kohere.auth.application;

import com.kohere.auth.application.dto.LoginResponse;
import com.kohere.auth.application.dto.LoginResult;
import com.kohere.auth.application.dto.SignupResponse;
import com.kohere.auth.application.dto.SignupResult;
import com.kohere.auth.application.dto.TokenResponse;
import com.kohere.auth.domain.AccountLockedException;
import com.kohere.auth.domain.EmailAlreadyRegisteredException;
import com.kohere.auth.domain.InvalidCredentialsException;
import com.kohere.auth.domain.LocalAccount;
import com.kohere.auth.domain.LocalAccountRepository;
import com.kohere.auth.domain.LoginAttemptRateLimiter;
import com.kohere.auth.domain.PasswordHasher;
import com.kohere.auth.domain.RequiredAgreementMissingException;
import com.kohere.auth.domain.WebAccountAlreadyExistsException;
import com.kohere.auth.presentation.dto.LoginRequest;
import com.kohere.auth.presentation.dto.SignupRequest;
import com.kohere.common.request.PhoneNumbers;
import com.kohere.common.request.RequestDates;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 임대인 웹(로컬 자격증명) 인증 유스케이스 — 회원가입(US-1-11)·로그인(US-1-12). 소셜이 아니라 <b>이메일 + 비밀번호</b>로 들어오는 별개 진입점이며,
 * 가입은 "계정 생성"이 아니라 <b>"자격증명 추가"</b>다(ADR-0047 §2).
 *
 * <p><b>왜 {@link AuthService}에 넣지 않는가</b> — {@code AuthService}는 이미 소셜 로그인·약관·이메일/연락처 인증·온보딩·재발급을
 * 들고 있고, 협력자가 열 개에 가깝다. 여기에 자격증명 해시·잠금 카운터·쿠키 채널이 얹히면 "소셜로 시작하는 계정 생애주기"라는 한 줄 설명이 깨진다. 반대로 <b>토큰
 * 발급은 나눠 갖지 않는다</b> — {@link AuthService#issueFullTokens}를 그대로 부른다(ADR-0048 §3: 회전·재사용 탐지 규칙을 두 벌로
 * 만들지 않는다).
 *
 * <p><b>가입의 DB 쓰기 전체가 한 트랜잭션이다.</b> 웹에는 온보딩 재개 화면이 없어 {@code PENDING}·{@code TERMS_AGREED} 같은 부분 완료
 * 상태를 남기면 로그인해도 갈 곳이 없는 죽은 계정이 된다. 그래서 상태 체인은 앱과 똑같이 태우되(앱 계정과 데이터 모양이 같아야 연동이 성립한다) 한 트랜잭션 안에서
 * {@code ACTIVE}까지 연속 전이시킨다 — 어느 단계에서 실패해도 {@code users}만 있고 자격증명이 없는(로그인 불가) 계정도, 그 반대도 남지 않는다.
 *
 * <p><b>그 원자성은 MySQL 쓰기에만 걸린다.</b> 이 트랜잭션 안에서 부르는 {@link AuthService#issueFullTokens}는 refresh 해시를
 * <b>Redis</b>에 남기는데, Redis는 트랜잭션 밖이라 롤백되지 않는다 — {@code uq_users_phone_number} 경합처럼 <b>커밋 시점</b>에
 * 터지는 실패는 이미 토큰이 발급된 뒤라, DB는 깨끗이 되돌아가지만 <b>refresh 해시 하나가 14일 TTL로 남는다</b>. 원문은 409 응답으로 대체돼 클라이언트에
 * 닿지 않으므로 세션을 열 수 없고(악용 불가) TTL로 소멸하지만, 그 사이 {@code refresh:user:{id}} 인덱스가 실제 세션보다 많아 보인다 — <b>수용한
 * 한계</b>다(토큰 발급을 트랜잭션 밖으로 들어내는 재구성은 문제 크기에 비해 변경이 크다). 반대로 SMS 인증 마커 소비는 같은 이유로 <b>커밋 이후</b>에 미뤄
 * 두었다({@link #consumeVerificationAfterCommit}) — 그쪽은 남은 잔여물이 사용자를 막으므로 순서를 지킬 값이 있었다.
 *
 * <p><b>user 모듈에 새 생성 메서드를 만들지 않는다.</b> 기존 세 메서드({@code createPendingUser} → {@code agreeToTerms} →
 * {@code completeLandlordOnboarding})를 순서대로 부르면 {@code @Transactional(REQUIRED)} 전파로 이 트랜잭션에 참여해
 * 원자성이 그대로 성립한다. 웹 전용 생성 경로를 만들면 앱과 데이터 모양이 갈라질 여지만 생긴다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-3·§1-4 · 시퀀스 us-1-11-web-signup·us-1-12-web-login.
 */
@Service
@RequiredArgsConstructor
public class WebAuthService {

  /**
   * 웹 계정은 한 트랜잭션으로 완주하므로 응답 상태가 갈리지 않는다 — 항상 ACTIVE다. 응답에 싣는 값이자 로그인이 {@link #assertActive}로
   * <b>확인하는</b> 값이다(상수로 박아 놓고 검사하지 않으면 그 상수가 거짓말이 된다).
   */
  private static final String STATUS_ACTIVE = "ACTIVE";

  /** 웹 가입에는 온보딩 재개 분기가 없다 — 항상 false다. */
  private static final boolean ONBOARDING_REQUIRED = false;

  private final SignupPhoneVerificationService signupPhoneVerificationService;
  private final LocalAccountRepository localAccountRepository;
  private final PasswordHasher passwordHasher;
  private final UserAccountService userAccountService;
  private final AuthService authService;
  private final AuthProperties authProperties;
  private final LoginAttemptRateLimiter loginAttemptRateLimiter;

  /**
   * 임대인 웹 회원가입. 게이트를 <b>인증 마커 → 필수 약관 → 이메일 중복 → 번호 매칭</b> 순서로 통과시킨 뒤 연동(자격증명만 추가) 또는 신규 생성(상태 체인
   * 완주)으로 갈린다. 실패하면 <b>DB 쓰기는</b> 전부 롤백된다(Redis에 남는 refresh 해시 잔여물은 클래스 주석 참조).
   *
   * <p><b>게이트 순서는 계약이다</b>(스펙 §1-3의 검증 게이트 우선순위) — 인증 마커를 가장 먼저 보는 이유는 번호가 비밀이 아니기 때문이다. 마커 없이 이메일
   * 중복이나 번호 매칭을 먼저 판정하면, 남의 번호를 아는 사람이 응답 코드만으로 "그 번호에 계정이 있는지"를 읽어낼 수 있다.
   *
   * <p><b>연동 판정 키는 번호 단독</b>이고 이름은 조건이 아니다 — 소유 증명은 전적으로 SMS 인증이 담당하므로 이름을 더해도 막히는 공격은 없는 반면, 앱
   * 이름(소셜 SDK 표기)과 웹 이름(직접 입력)의 자연스러운 불일치로 <b>계정이 조용히 갈라진다</b>(ADR-0047 §3).
   *
   * @return 응답 본문과, 쿠키로만 내려갈 refresh 원문({@link SignupResult})
   */
  @Transactional
  public SignupResult signup(SignupRequest request) {
    // 번호는 여기서 한 번 접고 이후 마커 조회·계정 매칭·users 저장이 모두 같은 표준형을 쓴다(#229 D10).
    String phoneNumber = PhoneNumbers.normalize(request.phoneNumber());
    // 형식 검증은 게이트보다 먼저다 — 부수효과가 없고, 400(형식)과 422(비즈니스 규칙)가 뒤바뀌면 클라이언트가
    // "인증부터 다시 하라"는 안내를 형식 오류에 띄운다. birthDate만 Bean Validation이 못 보는 형식이라 여기서 판정한다.
    LocalDate birthDate = RequestDates.parsePast("birthDate", request.birthDate());

    signupPhoneVerificationService.assertVerified(phoneNumber);
    assertRequiredAgreements(request);
    // 로그인 ID 유일성만 본다 — users.email은 보지 않는다. 임대인 대다수가 소셜과 같은 이메일로 가입하므로
    // 거기까지 유일하게 걸면 본인이 본인 이메일로 가입하다 409를 맞는다(ADR-0047 §6).
    if (localAccountRepository.existsByEmail(request.email())) {
      throw new EmailAlreadyRegisteredException();
    }

    // 여기서부터 두 갈래다 — 같은 조회의 서로 다른 가지이며 응답의 linked가 그 결과를 그대로 나른다.
    Optional<Long> matched = userAccountService.findActiveLandlordIdByPhoneNumber(phoneNumber);
    boolean linked = matched.isPresent();
    long userId =
        linked ? linkExisting(matched.get()) : createLandlord(request, phoneNumber, birthDate);

    Instant now = Instant.now();
    localAccountRepository.save(
        LocalAccount.register(
            userId,
            request.email(),
            passwordHasher.hash(request.password()),
            request.name(),
            birthDate,
            now));

    // 표시 규칙 — 응답의 name·email은 언제나 users의 값이다. 연동 경로에서는 폼 값이 아니라 소셜 진본이 나간다(의도된 동작).
    UserAccountView account = userAccountService.getAccount(userId);
    TokenResponse tokens = authService.issueFullTokens(userId);
    consumeVerificationAfterCommit(phoneNumber);

    return new SignupResult(
        new SignupResponse(
            linked,
            ONBOARDING_REQUIRED,
            STATUS_ACTIVE,
            tokens.tokenType(),
            tokens.accessToken(),
            tokens.expiresIn(),
            account.email(),
            account.name()),
        tokens.refreshToken());
  }

  /**
   * 임대인 웹 로그인. {@code local_accounts.email}로 계정을 특정하고 <b>잠금 → 비밀번호</b> 순으로 판정한 뒤 앱과 같은 정식 토큰을
   * 발급한다(US-1-12).
   *
   * <p><b>잠금 판정이 비밀번호 대조보다 먼저다.</b> 순서를 뒤집으면 잠금이 사실상 무력해진다 — 비밀번호를 이미 알아낸 공격자에게는 잠긴 계정도 그대로 열려 잠금이
   * 막아 주는 것이 아무것도 없어진다. 그래서 {@code locked_at}이 채워져 있으면 <b>제출한 비밀번호가 맞아도</b> 423이다.
   *
   * <p><b>잠금 해제 경로는 없다</b> — 시간 경과 자동 해제도, 해제 API도 만들지 않는다. 운영자가 DB에서 {@code locked_at}을 비우는 것이 유일한
   * 해제 방법이며, 여기에 편의 메서드를 하나 열어 두는 순간 "해제 없음"이 정책이 아니라 권고가 된다(US-1-12 알려진 제약 — 잠긴 임대인의 연락 창구는 운영이
   * 정한다).
   *
   * <p><b>레이트리밋이 가장 먼저다</b>({@link LoginAttemptRateLimiter}) — 자격증명 조회보다도, BCrypt 대조보다도 앞이다. 이 경로는
   * <b>선행 조건 없이</b> 인증되지 않은 호출자가 해시 한 라운드를 강제할 수 있는 첫 permitAll 엔드포인트라(가입은 SMS 인증 마커 게이트 뒤에 있고 그 마커
   * 발급 자체가 이미 한도에 걸린다), 한도를 뒤에서 보면 막힌 요청도 이미 CPU를 다 쓴 뒤가 되어 <b>막으려던 증폭을 막지 못한다</b>. 두 축 중 IP는
   * {@code X-Forwarded-For}가 호출자 손에 있어 위조 가능하므로 비용 가드일 뿐이고, 잠금 DoS를 실제로 묶는 것은 <b>이메일 축</b>이다 — 남의
   * 계정을 잠그려면 그 이메일을 보내야만 하기 때문이다. 초과는 429 {@code TOO_MANY_REQUESTS}다.
   *
   * <p><b>이메일 미존재와 비밀번호 불일치는 코드·status·문구가 같다</b>. 다만 <b>{@code error.details}는 갈린다</b> — 비밀번호
   * 불일치에만 누적 실패 횟수와 상한을 싣는다(미등록 이메일에는 올릴 카운터가 없다). 그래서 <b>이 필드의 유무로 가입 여부를 알 수 있으며, 그 계정 열거는 수용한
   * 결과다</b>: 잠금에 해제 경로가 없는 이상 잠기기 <b>전에</b> 남은 시도를 알려 주지 않으면 임대인은 손쓸 수 없는 상태에서만 사실을 알게 되고, 잠긴 계정의
   * 423이 이미 계정 존재를 드러내고 있어 이 엔드포인트에서 열거를 막고 있다고 말할 수 없다(ADR-0047 Amended).
   *
   * <p><b>그래도 {@link #burnPasswordHash}는 남긴다.</b> 본문이 갈린다고 해서 시간까지 갈릴 이유는 없다 — 계정이 없을 때 해시 대조를 건너뛰면
   * 그 경로만 BCrypt 한 번(수십 ms)만큼 빨라져, {@code details}를 보지 않고 <b>응답 시간만으로</b> 등록 여부를 읽는 두 번째 통로가 생긴다.
   * 값이 공짜로 새는 통로를 하나 더 열 이유가 없으므로 계정이 없어도 제출 비밀번호를 <b>한 번 해싱하고 버린다</b>.
   *
   * <p><b>그래도 남는 시간 차가 하나 있다</b> — 비밀번호 불일치 경로는 실패 카운터를 올리려고 {@link LocalAccountRepository#save}를
   * 부르는데, 이 병합은 SELECT + UPDATE를 내고 <b>자체 트랜잭션을 커밋</b>한다(이 메서드에 {@code @Transactional}이 없어서다 — 아래
   * 참조). 반면 이메일 미존재 경로는 아무것도 쓰지 않는다. BCrypt만큼 크지는 않지만 <b>지터가 아니라 안정적으로 재현되는 차이</b>라 충분한 표본이면 등록 여부를
   * 읽어낼 수 있다. 없애려면 존재하지 않는 계정에도 더미 쓰기를 내야 하는데, 그건 익명 호출자가 DB 쓰기를 유발하는 더 큰 표면을 여는 교환이라 하지 않는다. 대신 위의
   * 레이트리밋이 <b>공격자가 모을 수 있는 표본 수 자체를 시간당 한도로 묶어</b> 통계적 구분을 실용적이지 않게 만든다.
   *
   * <p>반대로 <b>423은 계정의 존재를 드러낸다</b> — 그건 타이밍이 아니라 status code 자체이고 의도된 것이다. 잠긴 임대인에게 왜 못 들어가는지 알리지
   * 않으면 해제 경로가 없는 상태에서 문의조차 할 수 없다. 남의 이메일로 계정을 잠그는 DoS가 가능하다는 부작용은 US-1-12에서 이미 수용했다.
   *
   * <p><b>이 메서드에 {@code @Transactional}을 달면 잠금이 영원히 걸리지 않는다.</b> 실패 카운터는 <b>요청이 예외로 끝나는 바로 그때</b>
   * 남아야 하는데 트랜잭션은 정확히 그 반대를 보장한다 — {@link InvalidCredentialsException}이 나가면서 방금 올린 카운터까지 롤백돼 상한이
   * 영원히 오지 않는다(잠금 코드는 그대로 있는데 아무도 잠기지 않는, 눈에 띄지 않는 실패다). 같은 빈에 {@code REQUIRES_NEW} 헬퍼를 두는 흔한 처방도
   * 여기서는 듣지 않는다 — 자기호출은 프록시를 타지 않아 애너테이션이 조용히 죽는다. 그래서 <b>로그인은 트랜잭션 단위가 아니다</b>: 애너테이션을 붙이지 않고
   * {@link LocalAccountRepository#save}가 Spring Data 자체 트랜잭션으로 커밋을 마친 뒤 401을 던진다. 같은 이유로 <b>이 메서드를
   * 트랜잭션 안에서 호출해서도 안 된다</b> — {@code REQUIRED} 전파로 흡수되면 롤백 문제가 그대로 되살아난다.
   *
   * <p>성공 경로가 원자적이지 않은 것은 무해하다. 카운터 리셋만 커밋되고 토큰 발급이 실패하면 사용자는 깨끗한 카운터로 다시 로그인할 뿐이다. 같은 계정에 실패 요청이
   * <b>동시에</b> 몰리면 낙관적 락(버전 컬럼)이 없어 증가분이 겹쳐 사라질 수 있는데, 그래도 잠금은 몇 번 늦게 걸릴 뿐 결국 걸린다 — 무차별 대입을 늦추는 것이
   * 목적이라 정확한 횟수를 보장할 이유가 없다(V22 스키마에 버전 컬럼을 더할 만한 값이 아니다).
   *
   * <p><b>그 카운터가 응답으로 나가게 됐지만 클라이언트가 보는 계약은 깨지지 않는다.</b> 저장·잠금 판정·응답이 모두 {@code
   * LocalAccount#recordLoginFailure}가 만든 <b>같은 값 하나</b>를 쓰므로, 응답 숫자가 상한에 닿은 그 요청이 곧 잠금 시점이다. 오차는
   * 실제보다 <b>작아지는 방향으로만</b> 나므로 잠금이 늦게 걸릴 뿐, 안내한 횟수보다 빨리 잠기는 일은 없다. 이는 서버 내부 사정이라 스펙·문서에 적지 않는다.
   *
   * <p>응답의 {@code email}·{@code name}은 로그인 ID가 아니라 <b>{@code users}의 값</b>이다(표시 규칙) — 연동된 계정이면 로그인에
   * 쓴 주소가 아니라 소셜 진본 이메일이 나갈 수 있다.
   *
   * <p><b>비밀번호가 맞아도 {@code ACTIVE}가 아니면 401이다</b>({@link #assertActive}). 탈퇴는 {@code users} 행을 지우지
   * 않고 {@code WITHDRAWN}으로 익명화만 하며 {@code user_type}도 {@code LANDLORD} 그대로라, 자격증명이 남아 있으면 여기서
   * <b>권한이 온전한 정식 세션</b>이 나간다. 탈퇴 시 {@code local_accounts}를 지우는 것이 1차 방어이고({@link
   * UserWithdrawnEventListener}) 이 검사는 2차 방어다 — 그 삭제 이전에 만들어진 행, 수동 복구, 앞으로 생길 다른 정지 상태까지 덮는다. 응답의
   * {@code status}에 {@code "ACTIVE"}를 상수로 박고 있으니 <b>실제로 그런지 확인하는 쪽</b>이 맞다.
   *
   * <p><b>왜 401이고 403·404가 아닌가</b> — 여기서 코드를 가르면 "이 이메일은 실재하고 비밀번호도 맞다"까지 알려 주는 <b>계정 열거 오라클</b>이
   * 된다(비밀번호를 맞혀야 도달하는 지점이라 오히려 정보량이 크다). 401 하나로 수렴시키면 공격자가 보는 것은 다른 실패와 구분되지 않는 응답뿐이다. 탈퇴자 본인은 재가입
   * 경로가 있으므로(자격증명이 지워져 같은 이메일을 다시 쓸 수 있다) 별도 안내 코드가 없어도 막다른 길이 아니다.
   *
   * @return 응답 본문과, 쿠키로만 내려갈 refresh 원문({@link LoginResult})
   */
  public LoginResult login(LoginRequest request, String clientIp) {
    // 조회·해시보다 먼저다. 뒤로 밀면 막힌 요청도 이미 BCrypt 비용을 치른 뒤라 한도가 증폭을 막지 못한다.
    loginAttemptRateLimiter.recordAttempt(request.email(), clientIp);

    Optional<LocalAccount> found = localAccountRepository.findByEmail(request.email());
    if (found.isEmpty()) {
      burnPasswordHash(request.password());
      throw new InvalidCredentialsException();
    }
    LocalAccount account = found.get();
    if (account.isLocked()) {
      throw new AccountLockedException();
    }

    Instant now = Instant.now();
    if (!passwordHasher.matches(request.password(), account.getPasswordHash())) {
      // 상한은 도메인 상수가 아니라 설정값이다 — 잠금 강도는 운영이 조절하는 정책이라 배포 없이 바꿀 수 있어야 한다.
      int maxFailedAttempts = authProperties.getWeb().getLoginMaxFailedAttempts();
      LocalAccount failed = account.recordLoginFailure(maxFailedAttempts, now);
      // 저장을 먼저 끝내고 던진다 — 응답에 싣는 카운터가 커밋된 값과 어긋나면 안 된다.
      localAccountRepository.save(failed);
      throw new InvalidCredentialsException(failed.getFailedLoginAttempts(), maxFailedAttempts);
    }
    // 성공은 잠금 전에만 도달하므로 되돌릴 lockedAt이 없다 — 카운터만 0으로 되돌린다.
    localAccountRepository.save(account.recordLoginSuccess(now));

    long userId = account.getUserId();
    UserAccountView user = userAccountService.getAccount(userId);
    assertActive(user);
    TokenResponse tokens = authService.issueFullTokens(userId);
    return new LoginResult(
        new LoginResponse(
            ONBOARDING_REQUIRED,
            STATUS_ACTIVE,
            tokens.tokenType(),
            tokens.accessToken(),
            tokens.expiresIn(),
            user.email(),
            user.name()),
        tokens.refreshToken());
  }

  /**
   * 등록되지 않은 이메일일 때 <b>비용만</b> 치르는 해시 계산. 결과는 쓰지 않는다 — 목적은 값이 아니라 BCrypt 라운드를 실제로 한 번 도는 것이다.
   *
   * <p>{@code hash}와 {@code matches}는 같은 cost로 같은 키 스케줄을 돌므로 두 경로의 비용이 맞는다. 대신 <b>고정 더미 해시를 상수로 박지
   * 않는다</b> — 그러면 문자열에 cost가 굳어 버려서 인코더 강도를 올리는 날 두 경로가 조용히 어긋나고, 그때는 아무 테스트도 깨지지 않는다. 매번 해싱하면 강도는
   * 언제나 인코더의 현재 값이다.
   *
   * <p>미존재 요청도 실패 요청과 같은 CPU를 쓰므로 열거 시도가 공짜가 아니게 되는 부수 효과가 있다(증폭은 없다 — 정상 실패와 같은 비용이다).
   */
  private void burnPasswordHash(String rawPassword) {
    passwordHasher.hash(rawPassword);
  }

  /**
   * 토큰 발급 직전 계정 상태 게이트 — {@code ACTIVE}가 아니면 401이다. {@code user}가 소유한 판정이지만 {@code getAccount}는 상태를
   * 돌려줄 뿐 막지 않으므로(탈퇴자도 {@code status="WITHDRAWN"}으로 정상 반환된다) 세션을 내주는 쪽에서 확인한다.
   *
   * <p>실질 대상은 {@code WITHDRAWN}이지만 <b>화이트리스트로 적는다</b>({@code ACTIVE}만 통과) — 상태가 늘어날 때 블랙리스트는 빠뜨리는
   * 쪽으로 실패하고 화이트리스트는 막는 쪽으로 실패한다. 웹 계정은 가입이 한 트랜잭션으로 완주해 {@code PENDING}·{@code TERMS_AGREED}로 남을 수
   * 없으므로(§1-3) 정상 경로가 이 게이트에 걸리는 일은 없다 — 걸린다면 그것이 곧 이상 상태다.
   */
  private static void assertActive(UserAccountView user) {
    if (!STATUS_ACTIVE.equals(user.status())) {
      throw new InvalidCredentialsException();
    }
  }

  /**
   * 인증 마커 소비를 <b>커밋 이후로</b> 미룬다. Redis 삭제는 이 트랜잭션과 함께 롤백되지 않으므로, 트랜잭션 안에서 지우면 커밋 시점에 터지는 실패(제약 위반
   * 플러시·커넥션 단절)가 MySQL 쪽만 되돌리고 마커는 이미 사라진 상태를 남긴다 — 사용자는 만들어지지도 않은 계정 때문에 500을 받고, 재시도하면 422 {@code
   * AUTH_PHONE_NOT_VERIFIED}로 SMS 인증부터 다시 해야 한다.
   *
   * <p>반대 방향(계정은 커밋됐는데 마커가 남음)은 애초에 무해하다 — 마커 재사용으로 할 수 있는 일은 같은 번호로 또 가입하는 것뿐이고 그건 409로 막힌다. 그래서
   * <b>"커밋된 뒤에만 지운다"</b>가 양쪽을 다 만족시키는 유일한 순서다.
   */
  private void consumeVerificationAfterCommit(String phoneNumber) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // 트랜잭션 밖 호출(테스트 등) — 미룰 커밋이 없으므로 즉시 소비한다.
      signupPhoneVerificationService.consumeVerification(phoneNumber);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            signupPhoneVerificationService.consumeVerification(phoneNumber);
          }
        });
  }

  /**
   * 필수 약관 게이트. {@code @NotNull}은 필드의 <b>존재</b>만 강제하고 {@code false}는 형식이 아니라 비즈니스 규칙 위반이라 400이 아닌
   * 422다 — 앱의 {@code POST /auth/terms}(US-1-7)와 같은 규칙·같은 코드를 쓴다(#229 D16).
   */
  private static void assertRequiredAgreements(SignupRequest request) {
    if (!Boolean.TRUE.equals(request.termsOfServiceAgreed())
        || !Boolean.TRUE.equals(request.privacyPolicyAgreed())) {
      throw new RequiredAgreementMissingException();
    }
  }

  /**
   * 연동 경로 — 번호로 찾은 기존 계정에 <b>자격증명을 붙일 자리가 비어 있는지</b>만 확인하고 그 {@code userId}를 그대로 쓴다.
   *
   * <p>{@code users}는 <b>한 칼럼도 건드리지 않는다</b>. 폼의 이름·생년월일·이메일은 방금 입력한 미검증 값이고 기존 값은 온보딩을 마친 확정 값이라,
   * 덮어쓰면 "가입했더니 내 프로필이 바뀌었다"가 된다(ADR-0047 §6). 폼 값은 {@code local_accounts} 스냅샷에만 남는다.
   *
   * <p>자리가 이미 찼으면 409다 — 남은 동작은 기존 자격증명 덮어쓰기뿐인데 그건 가입이 아니라 로그인 ID까지 조용히 바꾸는 <b>자격증명 교체</b>다. 응답에는 그
   * 계정의 이메일을 마스킹해서도 싣지 않는다(#229 D4 — 공통 에러 스키마의 code·message만).
   */
  private long linkExisting(long userId) {
    if (localAccountRepository.existsByUserId(userId)) {
      throw new WebAccountAlreadyExistsException();
    }
    return userId;
  }

  /**
   * 신규 경로 — 앱과 <b>같은 도메인 메서드를 같은 순서로</b> 불러 {@code PENDING → TERMS_AGREED → ACTIVE}를 연속 전이시킨다. 서버
   * 고정값({@code country=KR}·{@code lang=ko}·닉네임 자동 배정·{@code userType=LANDLORD})도 앱과 같다.
   *
   * <p>신규 생성일 때만 폼의 이름·이메일이 {@code users}에도 들어간다(연동 경로와의 유일한 차이 — #229 D7). 정규화한 번호를 {@code
   * users.phone_number}에 남기는 것이 특히 중요하다: 그래야 반대 방향(앱 온보딩, US-1-15)에서 이 계정이 병합 후보로 잡힌다.
   */
  private long createLandlord(SignupRequest request, String phoneNumber, LocalDate birthDate) {
    long userId = userAccountService.createPendingUser(request.name(), request.email());
    userAccountService.agreeToTerms(userId, Boolean.TRUE.equals(request.marketingAgreed()));
    userAccountService.completeLandlordOnboarding(
        userId, new LandlordOnboardingProfile(phoneNumber, birthDate));
    return userId;
  }
}
