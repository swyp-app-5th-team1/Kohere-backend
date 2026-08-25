# 시퀀스 다이어그램 — 소셜 로그인 · 온보딩

> 사용자 → 앱(클라이언트) → 백엔드(서버) 흐름. 관련: [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)

| 스토리 | 제목 | 다이어그램 |
| --- | --- | --- |
| US-1-1 | 소셜 로그인으로 진입해 서버 토큰 발급받기 | [us-1-1-social-login](us-1-1-social-login.md) |
| US-1-2 | 필수 온보딩 정보·약관 동의 제출하기 | [us-1-2-onboarding-submit](us-1-2-onboarding-submit.md) |
| US-1-3 | 만료된 access 토큰을 refresh로 재발급받기 | [us-1-3-token-reissue](us-1-3-token-reissue.md) |
| US-1-4 | 로그아웃·회원 탈퇴로 세션과 계정 정리하기 | [us-1-4-logout-withdraw](us-1-4-logout-withdraw.md) |
| US-1-5 | 내 프로필 조회·수정하기 | [us-1-5-profile-view-update](us-1-5-profile-view-update.md) |
| US-1-6 | 온보딩 중 이메일 인증하기(세입자 전용) | [us-1-6-email-verification](us-1-6-email-verification.md) |
| US-1-7 | 약관 동의 화면에서 약관 동의하기 | [us-1-7-terms-agreement](us-1-7-terms-agreement.md) |
| US-1-8 | 임대인 사업자등록번호 검증하기(온보딩 후 분리된 무상태 검증) | [us-1-8-business-verification](us-1-8-business-verification.md) |
| US-1-9 | 임대인 온보딩 정보 제출하기(약관+연락처 인증만으로 완료) | [us-1-9-landlord-onboarding](us-1-9-landlord-onboarding.md) |
| US-1-10 | 온보딩 중 연락처(휴대폰) 인증하기(임대인 전용) | [us-1-10-phone-verification](us-1-10-phone-verification.md) |
| US-1-11 | 임대인 웹 회원가입하기(단일 폼 · 기존 앱 계정 연동) | [us-1-11-web-signup](us-1-11-web-signup.md) |
| US-1-12 | 임대인 웹 로그인하기(계정 잠금 · refresh 쿠키 재발급) | [us-1-12-web-login](us-1-12-web-login.md) |
| US-1-13 | 가입용 휴대폰 인증하기(비로그인 · 임대인 웹) | [us-1-13-signup-phone-verification](us-1-13-signup-phone-verification.md) |
| US-1-15 | 앱 임대인 온보딩 시 기존 웹 계정과 병합하기 | [us-1-15-landlord-account-merge](us-1-15-landlord-account-merge.md) |
| US-1-16 | 가입한 이메일 찾기(임대인 웹 전용, 비로그인) | [us-1-16-find-email](us-1-16-find-email.md) |
| US-1-17 | 비밀번호 재설정으로 로그인 복구하기(임대인 웹 전용, 비로그인) | [us-1-17-password-reset](us-1-17-password-reset.md) |

> **US-1-14는 결번이다** — 임대인 웹 인증 범위를 확정하면서 쓰이지 않은 번호로, 대응하는 유저 스토리도 다이어그램도 없다(찾지 않아도 된다). 이미 배포된 번호를 흔들지 않으려고 뒤 번호를 당겨 재배열하지 않았으므로 US-1-13 다음은 US-1-15다. 스토리 원문은 [user-stories.md §1](../../../requirements/user-stories.md)에 같은 번호로 있다.
