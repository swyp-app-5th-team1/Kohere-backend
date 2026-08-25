package com.kohere.auth.application;

import com.kohere.auth.application.dto.FindEmailResponse;
import com.kohere.auth.domain.LocalAccount;
import com.kohere.auth.domain.LocalAccountRepository;
import com.kohere.auth.domain.WebAccountNotFoundException;
import com.kohere.common.request.PhoneNumbers;
import com.kohere.user.api.UserAccountService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 임대인 웹 <b>가입 이메일 찾기</b> 유스케이스(US-1-16 · 스펙 §1-7). 인증된 번호로 웹 계정을 특정하고 제출된 이름을 대조해 <b>마스킹된 로그인
 * 이메일</b>을 돌려준다.
 *
 * <p><b>응답 이메일은 {@code local_accounts.email}이다</b>({@code users.email}이 아니다). 표시 규칙(스펙 §개요 웹 임대인
 * 트랙)이 응답의 {@code email}을 {@code users}에서 가져오라고 하는 것과 어긋나 보이지만, 이 화면에서 사용자가 알아야 하는 것은 프로필 이메일이 아니라
 * <b>로그인 화면에 입력할 ID</b>다. 연동된 계정은 소셜 진본 이메일과 웹 로그인 ID가 다를 수 있어, 정본을 잘못 고르면 찾아 준 이메일로 로그인이 되지 않아 화면
 * 전체가 무용해진다.
 *
 * <p><b>단계 순서가 계약이다</b> — ① 번호 정규화 → ② 검증 마커 게이트 → ③ 번호로 회원 후보 조회({@code user::api}) → ④ 웹 자격증명이 붙은
 * 것만 남김 → ⑤ 정확히 1건인지 → ⑥ 이름 대조 → ⑦ 마커 소비 → ⑧ 마스킹. 마커 게이트가 가장 앞인 이유는 <b>번호가 비밀이 아니기 때문</b>이다. 뒤로 밀면
 * 인증 없이 임의 번호를 넣어 응답 코드만으로 "그 번호에 웹 계정이 있는지"를 읽어낼 수 있고, 그 순간 §1-5·§1-6의 SMS 게이트는 아무것도 지키지 않는다.
 *
 * <p><b>이름 대조 대상은 {@code local_accounts.name} 단독이며 {@code users.name} 폴백을 두지 않는다.</b> 웹 계정이 있다는 것은
 * 곧 {@code local_accounts} 행이 있다는 뜻이고 그 행의 {@code name}은 <b>가입 폼에 본인이 직접 적은 값</b>이라 사용자가 다시 적어 맞힐 수
 * 있다. 반면 연동된 계정의 {@code users.name}은 소셜 SDK 표기(로마자 등)라 본인도 무엇을 적어야 하는지 모른다 — 폴백을 두면 "둘 중 아무 이름이나
 * 하나만 맞으면 통과"가 되어 대조가 느슨해지기만 하고 얻는 것이 없다.
 *
 * <p><b>{@code @Transactional}을 달지 않는다</b> — 읽기뿐이고, {@code user::api}의 조회가 자체 {@code readOnly}
 * 트랜잭션을 연다. 여기서 트랜잭션을 열면 비로그인 permitAll 호출이 커넥션을 잡은 채 Redis 왕복까지 감싸게 된다.
 *
 * <p>시퀀스: docs/architecture/sequence-diagrams/01-auth-onboarding/us-1-16-find-email.md.
 */
@Service
@RequiredArgsConstructor
public class FindEmailService {

  private final FindEmailPhoneVerificationService findEmailPhoneVerificationService;
  private final UserAccountService userAccountService;
  private final LocalAccountRepository localAccountRepository;

  /**
   * 이메일 찾기. 마커 게이트를 통과한 뒤 번호로 찾은 회원 후보 중 <b>웹 자격증명이 붙은 것</b>만 남기고, 정확히 하나일 때만 이름 대조로 넘어간다.
   *
   * <p><b>후보가 0건이든 2건 이상이든 같은 404다.</b> 번호로 찾은 후보가 다건일 수 있는 것은 {@code (user_type, phone_number)} 복합
   * 유니크(V28) 때문에 같은 번호로 {@code LANDLORD}·{@code ADMIN} 행이 정상 공존할 수 있어서인데, 그중 <b>웹 자격증명이 붙은 것</b>이
   * 둘이면 어느 쪽이 사용자가 찾는 로그인 ID인지 서버가 가릴 근거가 없다 — 아무거나 골라 주면 로그인되지 않는 ID를 알려 주는 것이고, 둘 다 알려 주면 운영 계정의
   * 존재까지 드러난다. 운영 문의로 보내는 편이 낫다.
   *
   * <p><b>이름 불일치도 같은 404다</b>({@link WebAccountNotFoundException}) — 계정 존재까지는 드러내되 명의자 이름은 드러내지
   * 않는다(이름 오라클 차단).
   *
   * <p><b>마커는 성공했을 때만 소비한다.</b> 실패한 조회가 마커를 태우면 이름 오타 한 번에 SMS 인증부터 다시 해야 한다 — 정상 사용자만 벌하는 셈이다. 그
   * 대가로 마커 TTL(30분) 안에서 이름을 바꿔 가며 재시도할 여지가 남는데, 자기 번호로만 열리는 창이라 <b>받아들인 한계</b>다.
   */
  public FindEmailResponse findEmail(String phoneNumber, String name) {
    // 마커 키·조회 키가 모두 표준형이라야 한다 — 이 경계에서 한 번 접고 이후는 이 값만 쓴다.
    String normalized = PhoneNumbers.normalize(phoneNumber);
    findEmailPhoneVerificationService.assertVerified(normalized);

    // 번호로는 회원 후보까지만 나온다 — 그중 무엇이 웹 로그인 계정인지는 local_accounts가 붙어 있는지로
    // 갈리고, 그 판정은 auth가 소유한다(user::api는 고르지 않고 후보만 돌려준다).
    List<LocalAccount> webAccounts =
        userAccountService.findActiveWebUserIdsByPhoneNumber(normalized).stream()
            .map(localAccountRepository::findByUserId)
            .flatMap(Optional::stream)
            .toList();
    if (webAccounts.size() != 1) {
      throw new WebAccountNotFoundException();
    }

    LocalAccount webAccount = webAccounts.get(0);
    if (!matchesName(webAccount.getName(), name)) {
      throw new WebAccountNotFoundException(); // 미존재와 같은 코드 — 이름 오라클 차단
    }

    findEmailPhoneVerificationService.consumeVerification(normalized);
    return new FindEmailResponse(Masks.maskEmail(webAccount.getEmail()));
  }

  /**
   * 이름 대조 — <b>모든 공백을 지우고 대소문자를 무시</b>해 비교한다. 사람이 다시 타이핑하는 값이라 {@code "홍 길동"}·{@code "홍길동"}, {@code
   * "Kim Minsu"}·{@code "kim minsu"}는 같은 이름으로 봐야 한다. 여기서 엄격하게 굴어 봐야 막히는 것은 본인뿐이다 — 이름은 소유 증명이 아니라
   * <b>SMS 인증을 이미 통과한 사람</b>에게 거는 추가 확인이고, 소유 증명은 전적으로 §1-5·§1-6이 담당한다.
   *
   * <p>저장된 이름이 {@code null}이면 <b>대조 실패</b>다. 어떤 입력과도 맞지 않는 값이라 통과시킬 근거가 없고, 통과시키면 이름 게이트가 없는 계정이
   * 생긴다.
   */
  private static boolean matchesName(String storedName, String submittedName) {
    if (storedName == null) {
      return false;
    }
    return storedName.replaceAll("\\s", "").equalsIgnoreCase(submittedName.replaceAll("\\s", ""));
  }
}
