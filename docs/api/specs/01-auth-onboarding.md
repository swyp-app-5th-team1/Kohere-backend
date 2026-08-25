# 소셜 로그인 · 온보딩 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

소셜 로그인(Apple/Google) 검증 후 서버 자체 JWT(access+refresh)를 발급하고(이때 provider에서 받은 **이름·이메일을 `User`에 즉시 채운다** — 애플 심사 대응, #192), 신규 회원의 온보딩 필수정보 수집·약관 동의, **임대인 온보딩 중 연락처(SMS) 인증**, **정식(ACTIVE) 사용자의 이메일 인증**, **임대인 웹의 이메일·비밀번호 회원가입·로그인과 그 선행 단계인 가입용 연락처(SMS) 인증**, 토큰 재발급/로그아웃, 회원 탈퇴, 내 프로필 조회·수정, **내 차단 목록 조회·해제**를 다룬다. 인증 헤더는 `Authorization: Bearer <accessToken>`, 토큰 갱신은 `POST /api/v1/auth/reissue`다(웹은 refresh를 응답 본문이 아니라 **HttpOnly 쿠키**로 받아 같은 엔드포인트로 갱신한다 — 아래 §웹 임대인 트랙).

상태 모델: 사용자는 `PENDING`(소셜 검증만 완료) → `TERMS_AGREED`(약관 동의 완료) → `ACTIVE`(온보딩 완료) → `WITHDRAWN`(탈퇴)로 전이한다. **약관 동의와 온보딩은 분리된 단계**로, 약관 동의(`POST /auth/terms`)가 온보딩 제출(`POST /auth/onboarding`)을 선행한다. **임대인 웹 회원가입(§1-3)은 같은 체인을 한 트랜잭션 안에서 연속 전이시켜 `ACTIVE`까지 한 번에 완주**한다 — 상태 모델·도메인 전이는 앱과 동일하고(앱 계정과 데이터 모양이 같아야 계정 연동이 성립한다) 웹에 부분 완료 상태가 남지 않을 뿐이다.

### 임대인 트랙

사용자는 **세입자(`TENANT`, 외국인)** 와 **임대인(`LANDLORD`)** 두 역할로 나뉜다(운영용 **관리자(`ADMIN`)** 는 가입 경로가 없어 이 흐름에 나타나지 않는다 — 운영자가 임대인 웹 가입으로 만든 전용 계정을 수동 승격하며, 로그인·계정 연동 방식은 임대인과 완전히 동일하다). **관리자는 회원용 기능을 호출할 수 없다** — 로그인·재발급·로그아웃과 프로필 조회·수정은 그대로 쓰지만, 이메일 인증처럼 회원 본인의 연락 수단을 확인하는 절차는 `403 FORBIDDEN`이다. 각 서비스가 세입자·임대인만 통과시키는 허용 목록으로 거르며, 관리자가 쓰는 API는 [03-listings-favorites](03-listings-favorites.md)의 관리자 매물 심사뿐이다. **소셜 로그인·약관 동의까지는 두 역할이 공통 흐름**이고, **이후 온보딩 단계에서 분기**한다 — 세입자는 곧바로 `POST /auth/onboarding`(§5), 임대인은 연락처 SMS 인증(§4-1·§4-2) 후 `POST /auth/landlord/onboarding`(§5-2)으로 제출한다(세입자의 이메일은 소셜 로그인 시 provider 값으로 확정되므로 온보딩에서 재입력·인증하지 않는다 — #192). **임대인 온보딩은 약관 동의 + 연락처(SMS) 인증만으로 완료**되며, 사업자등록번호는 온보딩 제출에 포함하지 않는다 — 온보딩을 마친(ACTIVE) 임대인이 **매물 등록(`POST /api/v2/listings` — [03-listings-favorites](03-listings-favorites.md))에서 입력**하며, 등록 API는 이를 **형식 검증만 하고 매물 문서에 저장**한다(별도 검증 API(§5-1)를 자동 호출하지 않고, 진위는 관리자가 승인 심사에서 수동으로 확인한다). **`userType`은 온보딩 제출 엔드포인트로 확정되고 이후 불변**이다(소셜 로그인·약관 단계에서는 미확정). **임대인의 이메일은 세입자와 동일하게 소셜 로그인 시 provider(Apple/Google) 값으로 캡처·보유하되(더는 미수집 아님), 본인 확인은 연락처(휴대폰) SMS 인증으로 한다**([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) 개정(#192) — 이메일은 인증 대상 아닌 미검증 연락처). 관련 유저 스토리: US-1-8(사업자번호 검증)·US-1-9(임대인 온보딩)·US-1-10(임대인 연락처 인증).

### 웹 임대인 트랙(로컬 자격증명)

임대인은 앱과 별개로 **매물을 등록하는 웹 클라이언트**로도 들어온다. 웹은 소셜 로그인을 지원하지 않고 **이메일(로그인 ID) + 비밀번호**로 가입·로그인한다 — 앱 자격증명(`social_accounts`)과 웹 자격증명(`local_accounts`)이 **하나의 `users` 행에 병렬로 매달리는** 구조다([ADR-0047](../../adr/0047-web-local-credentials-and-phone-based-account-linking.md)). 웹 트랙의 엔드포인트는 §1-1(가입용 SMS 발송) · §1-2(가입용 SMS 확인) · §1-3(회원가입) · §1-4(로그인) · §1-5(이메일 찾기용 SMS 발송) · §1-6(이메일 찾기용 SMS 확인) · §1-7(이메일 찾기) · §1-8(재설정 링크 발송) · §1-9(재설정 토큰 확인) · §1-10(재설정 확정) 열이고, **모두 비로그인 진입점이라 인증이 불필요(permitAll)** 하다. 앞의 넷은 계정을 만들어 들어가는 경로이고, **뒤의 여섯은 들어가지 못하게 된 계정을 되찾는 경로**다 — §1-5~§1-7이 잊어버린 로그인 ID(이메일)를 찾아 주고, §1-8~§1-10이 비밀번호 재설정과 계정 잠금 해제를 **하나의 흐름으로** 처리한다(해제 전용 API를 따로 두지 않는 이유는 §1-8). 관련 유저 스토리: US-1-11(웹 회원가입)·US-1-12(웹 로그인)·US-1-13(가입용 연락처 인증)·US-1-15(앱 온보딩 시 웹 계정 병합)·US-1-16(이메일 찾기)·US-1-17(비밀번호 재설정으로 로그인 복구).

**계정 연동은 곧 `user_id` 공유다.** 웹에서 등록한 매물의 `landlordId`가 앱 소셜 로그인 시의 `userId`와 같아야 예약([04-booking-inquiry-chat](04-booking-inquiry-chat.md))·채팅이 앱에서 그대로 보인다. 그래서 웹 회원가입은 "계정 생성"이 아니라 **"자격증명 추가"** 로 설계한다 — 기존 계정을 찾으면 그 `user_id`에 `local_accounts` 행만 붙이고(§1-3 `linked=true`), 못 찾으면 새 `users` 행을 만든다. **매칭 키는 SMS 인증을 통과한 휴대폰 번호 단독**이며(이름은 조건이 아니다) 번호는 *조회 키*이지 인증 수단이 아니다 — 소유 증명은 전적으로 §1-2의 SMS 인증이 담당한다. 연동은 양방향이지만 판정 시점이 비대칭이다: **웹 → 앱**은 가입 제출(§1-3)에서 **연결**, **앱 → 웹**은 소셜 로그인 시점에 서버가 번호를 모르므로 임대인 온보딩 제출(§5-2)에서 **병합**한다. **어느 방향이든 그 사실은 응답의 `linked` 필드 하나로 알린다** — 구현(자격증명 INSERT / 계정 병합)은 다르지만 클라이언트에게는 둘 다 "계정이 하나로 합쳐졌다"는 같은 사실이라 어휘를 두 벌 두지 않는다. 두 필드 모두 응답에 **추가**된 것이라 하위 호환이 깨지지 않고 `/api/v1`을 유지한다.

> **표시 규칙 — 모든 응답의 `name`·`email`은 `users`의 값이다.** `local_accounts`가 보관하는 웹 폼 스냅샷(`name`·`birth_date`)은 **어떤 응답에도 싣지 않는다**(`social_accounts.name`과 같은 취급 — 저장은 하되 표시는 `users`). 따라서 **연동된 계정은 로그인에 쓴 웹 이메일이 아니라 소셜 진본 이메일이 응답에 나갈 수 있다 — 의도된 동작**이다. 신규 가입일 때만 폼 이메일이 `users.email`에도 기록되어 두 값이 같아진다.

**refresh 토큰 쿠키**(웹 전용 채널 — [ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md)): §1-3·§1-4는 refresh를 **응답 본문에 싣지 않고** `Set-Cookie`로만 내린다. 속성은 한 벌로 고정한다. 쿠키를 **지우는** 자리는 §7(로그아웃)·§10(탈퇴) 둘이고, 삭제 쿠키도 이름과 `Path`가 발급 때와 같아야 브라우저가 같은 쿠키로 보고 지운다.

| 속성 | 값 | 이유 |
| --- | --- | --- |
| 이름 | `refreshToken` | 본문 필드명과 같은 이름을 쓴다(§6·§7이 쿠키·본문 어느 쪽에서 읽든 같은 값) |
| `HttpOnly` | 설정 | 스크립트가 읽을 수 없다(XSS로 refresh가 유출되지 않는다) |
| `Secure` | 설정 | **`local` 프로파일에서만 끈다** — 로컬 개발이 평문 http라 켜 두면 브라우저가 쿠키를 저장하지 않는다. 기본 설정값은 `true`이고 `application-local.yml`에서만 `false`로 내린다 |
| `SameSite` | `Lax` | 크로스사이트 요청에 쿠키가 실리지 않는다 → 별도 CSRF 토큰을 두지 않고 `csrf.disable()`을 유지한다 |
| `Path` | `/api/v1/auth` | 재발급·로그아웃 경로에만 전송한다(다른 API 호출에 refresh가 딸려가지 않는다) |
| `Max-Age` | `1209600`(14일) | refresh TTL과 같은 값 — **앱과 동일**하며 별도 설정키를 두지 않는다(`app.auth.refresh-ttl-seconds` 재사용) |

> `SameSite=Lax`로 CSRF를 대신하는 것은 **웹과 API를 같은 오리진에 배치한다는 전제** 위에 성립한다. 이 전제는 현재 인프라 구성(`docker-compose.yml`·리버스 프록시)에 웹 클라이언트 서비스가 없어 **코드로 확인되지 않는다** — 다른 호스트로 배포하면 `csrf.disable()` + 쿠키 refresh가 CSRF 구멍이 되므로 배치 전에 반드시 확인한다([ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md)의 전제 조건).

**앱 동작은 전혀 바뀌지 않는다.** §6·§7은 쿠키를 **우선**해 읽고 없으면 **종전대로 본문**에서 읽으며, 본문으로 온 요청에는 회전된 refresh를 종전대로 본문에 담아 돌려준다. 하위 호환이 깨지지 않으므로 **v2를 신설하지 않고 `/api/v1`을 유지**한다.

**알려진 제약**(설계상 수용한 한계 — 해당 절에서 다시 짚는다):

- **번호 정규화 백필 없음** — 정규화(숫자만 남김)는 **입력 경로에만** 적용하고 기존 데이터는 손대지 않는다. 하이픈으로 저장된 기존 임대인 번호는 연동·병합 매칭에서 누락될 수 있다(§1-3·§5-2).
- **잠금은 본인이 비밀번호 재설정으로 푼다 · 시간 경과 자동 해제는 없다** — 10회 실패로 잠긴 계정은 §1-8~§1-10을 완주하면 비밀번호 교체와 함께 잠금·실패 카운터가 사라진다(재설정과 잠금 해제는 **같은 API**이고 화면만 둘이다). 그래도 **가만히 두면 절대 풀리지 않는다** — 해제는 언제나 본인의 명시적 행위여야 한다(§1-4). 메일함 접근을 잃어 링크를 받지 못하는 계정은 종전대로 운영자가 DB에서 `locked_at`을 비우며, **`locked_at`만 비우면 그것으로 완전한 해제**다 — `failed_login_attempts`를 함께 지울 필요가 없다(§1-4).
- **의도적 계정 잠금(DoS)** — 남의 이메일로 10회 틀리면 그 계정을 잠글 수 있다. 로그인 시도 레이트리밋(IP 60회/시간·이메일 20회/시간)으로 완화하나 완전히 막을 수 없다(§1-4).
- **계정 열거 가능** — 로그인 실패 응답의 `error.details` 유무로 그 이메일의 가입 여부를 알 수 있다. 잠기기 전에 남은 시도를 알려 주기 위해 수용한 결과다(§1-4).
- **세입자 → 임대인 전환 불가** — 세입자는 `phone_number`가 NULL이라 구조적으로 매칭 후보에서 빠진다. 앱 세입자가 웹에 임대인으로 가입하면 별개 계정이 생기며, 서버는 두 계정이 동일인인지 알 수 없다(§1-3).
- **양쪽 모두 완주한 계정은 자동 병합 없음** — 앱·웹 양쪽에 같은 번호의 `ACTIVE` 계정이 각각 있으면 병합 트리거가 없다. 운영 수동 처리 대상이다(§5-2).
- **병합 시 진단 기록 미삭제** — 병합은 임시 `users` 행만 삭제하고 진단 문서는 남긴다(§5-2).

시퀀스: [US-1-11](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-11-web-signup.md) · [US-1-12](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-12-web-login.md) · [US-1-13](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-13-signup-phone-verification.md) · [US-1-15](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-15-landlord-account-merge.md) · [US-1-16](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-16-find-email.md) · [US-1-17](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-17-password-reset.md).

### 핵심 개념·enum

| 개념 | 값 | 설명 |
| --- | --- | --- |
| 사용자 상태 `status` | `PENDING`, `TERMS_AGREED`, `ACTIVE`, `WITHDRAWN` | 소셜 검증만 완료 → 약관 동의 완료 → 온보딩 완료 → 탈퇴 |
| provider | `APPLE`, `GOOGLE` | 소셜 로그인 제공자 |
| 성별 `gender` | `MALE`, `FEMALE` | 온보딩 필수(세입자만) |
| 생년월일 `birthDate` | 날짜 문자열(`YYYY-MM-DD`) | 온보딩 필수(세입자·임대인 공통) · 과거 날짜만 허용(미래 불가) |
| 직업 `occupation` | `UNDERGRADUATE_STUDENT`(학부생), `GRADUATE_STUDENT`(대학원생), `EXCHANGE_STUDENT`(교환학생), `LANGUAGE_TEACHING`(어학·교육), `MANUFACTURING_PRODUCTION`(제조·생산), `BUSINESS_TRADE`(사업·무역), `ETC`(기타) | **온보딩 선택**(#187에서 필수→선택 완화 — 매물 추천·탐색에서 활용하지 않음) · 미전송이면 저장하지 않고(NULL) 응답에서 생략 · 요구사항 확정값(#93, #138 개편) |
| 비자정보 `visaType` | `SHORT_TERM_VISIT`(단기방문), `STUDENTS_TRAINEES`(유학·연수), `NON_PROFESSIONAL_WORKERS`(비전문취업), `WORKING_HOLIDAY_WORK_AND_VISIT`(워킹홀리데이·방문취업), `OVERSEAS_KOREANS`(재외동포), `FAMILY_MARRIAGE_MIGRANTS`(방문동거·거주·결혼이민), `PERMANENT_RESIDENTS`(영주), `PROFESSIONALS`(전문인력), `DIPLOMATIC_OFFICIAL_AND_OTHERS`(외교·공무·기타), `ETC`(기타) | 온보딩 필수 · 요구사항 확정값(#93, #138 개편). API는 상수명, DB 저장은 표시 라벨 |
| 국적 `country` | ISO 3166-1 alpha-2 코드(예: `VN`) | 온보딩 필수 · 클라이언트는 국가만 전송, 표시명·국기는 서버가 `countries` 참조로 확보(응답에 `countryName`·`countryFlag` 포함, **`countryFlag`는 국기 이미지 URL**) |
| 표시 언어 `lang` | ISO 639-1 소문자 코드 — 지원값 `en`, `ko`, `ja` | **세입자 온보딩·프로필 수정 모두 선택** · 사용자가 앱 지구본 아이콘에서 직접 고른다. `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`이다. 지원 목록(`en`·`ko`·`ja`)으로 서버가 검증하고 목록 밖 값은 `INVALID_INPUT`이다(값은 소문자 코드로 주고받되 서버는 내부적으로 `Language` enum으로 모델링한다). **임대인은 서버가 `ko`로 고정 부여하며 변경할 수 없다**([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) 개정(#141)) |
| 이메일 `email` | 이메일 문자열 | **세입자** 소셜 로그인 시 provider(Apple/Google) 진본으로 확정 — 요청 `email`이 토큰 `email` 클레임과 일치해야 하며(§1) 온보딩에서 재수집·재인증하지 않는다(#192). 정식(ACTIVE) 사용자의 이메일 인증 API는 §3·§4(접근만 ACTIVE 전용, 실제 이메일 변경 반영은 후속 이슈). 임대인도 소셜 로그인 시 provider 값을 `User.email`에 보유한다(더는 미수집·NULL 아님 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집" 결정을 개정(#192): 수집 폼이 아니라 소셜 로그인 provider 값 보유이며 인증 대상 아님, 수정은 후속 이슈). **임대인 웹 트랙에서는 이메일이 곧 로그인 ID**라 유일성이 필요하지만, 그 유일성은 **`local_accounts.email`에만** 걸고 **`users.email`에는 걸지 않는다**(§1-3 — 앱 소셜 이메일과 같은 주소로 웹 가입하는 것이 가장 흔한 정상 경로다) |
| 닉네임 `nickname` | `형용사 + 사물` 문자열 | 서버가 자동 배정(사용자 입력·수정 불가), 전역 유니크 |
| 사용자 역할 `userType` | `TENANT`(세입자·외국인), `LANDLORD`(임대인), `ADMIN`(관리자) | `TENANT`·`LANDLORD`는 온보딩 제출 엔드포인트(세입자 `/auth/onboarding` · 임대인 `/auth/landlord/onboarding`)로 확정·이후 불변. 소셜·약관 단계에서는 미확정. **`ADMIN`만 예외** — 가입 경로가 없고 운영자가 기존 계정을 온보딩 이후에 수동 승격한다([03-listings-favorites](03-listings-favorites.md) 관리자 매물 심사) |
| 이름 `name` | 문자열 | **세입자·임대인 공통** · 성·이름을 합친 **단일 이름**(#192에서 세입자의 `firstName`/`lastName`을 단일 `name`으로 통합해 임대인과 완전히 통일). 빈 문자열 불가. API 필드명·저장 모두 단일 `name`(`FullName` VO의 단일 `name` 속성 · `users.name` 컬럼). 세입자·임대인 모두 소셜 로그인 시 provider 값으로 채우고(§1) 이후 `PATCH /users/me`(§9)로 수정한다(#192에서 임대인도 온보딩 수집을 폐지해 세입자와 수집 시점까지 완전히 통일) |
| 연락처 `phoneNumber` | 전화번호 문자열 | **임대인 온보딩 필수** · SMS 인증번호로 사전 검증(§4-1·§4-2) 필요. 응답·로그 마스킹(예 `010-****-5678`). **웹 트랙에서는 계정 연동의 유일한 매칭 키**이므로 입력 경로에서 **숫자만 남겨 정규화**해 저장·조회한다(§1-1·§1-3, `users.phone_number` UNIQUE) |
| 비밀번호 `password` | 문자열 | **임대인 웹 전용**(소셜 트랙에는 없다) · 영문자(`A-Za-z`) 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, **길이 8~20**, 공백 불허(한글 불허). 위반은 `INVALID_INPUT`(400). **BCrypt 해시로만 보관**하고 원문은 저장·로그·응답 어디에도 남기지 않는다 |
| 연동 여부 `linked` | boolean | **웹 회원가입(§1-3) 응답 전용** · 같은 번호의 기존 앱 계정을 찾아 그 `user_id`에 자격증명만 붙였으면 `true`(새 `users` 행 없음), 못 찾아 새 계정을 만들었으면 `false` |
| 사업자등록번호 `businessRegistrationNumber` | 숫자 10자리 문자열 | **임대인 전용** · **온보딩 제출에는 미포함**(온보딩은 약관·연락처 인증만으로 완료). 온보딩 후 **매물 등록(`POST /api/v2/listings`) 요청 본문**으로 받아 **형식(숫자 10자리)만 검증하고 매물 문서에 저장**한다 — 등록 시점에 별도 검증 API(§5-1)를 자동 호출하지 않으며 진위는 관리자 승인 심사에서 수동 확인한다. 응답·로그 마스킹(매물 응답에서는 아예 제외) |

- 날짜만 표기는 `YYYY-MM-DD`(예: `birthDate`), 시각은 ISO-8601 UTC(예: `2026-06-15T08:30:00Z`).
- enum은 모두 UPPER_SNAKE_CASE 문자열로 노출한다. **예외: `lang`은 UPPER_SNAKE가 아니라 ISO 639-1 소문자 코드**다(`en`·`ko`·`ja`) — 값은 소문자로 주고받고 저장하되 서버는 닫힌 집합 `Language` enum으로 모델링·검증한다. 신규 언어는 카탈로그 콘텐츠 시드가 선행되어 어차피 배포를 수반하므로 enum이 손해가 아니다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)).
- **민감정보(토큰 원문·인증번호 원문·비자정보·이메일)는 로그·타 사용자 노출 시 마스킹**한다(error-response-guide §6). 본인 `GET /users/me`는 이메일을 평문으로 반환한다.
- **토큰 모델**: `accessToken`은 **JWT**(stateless — 매 요청 서명·만료를 검증, 저장 안 함). `refreshToken`은 **불투명(opaque) 랜덤 토큰**으로 발급하고 서버 저장소에 **해시로 보관**한다(회전·재사용 탐지·무효화 목적). 예시의 `rt_…`는 불투명 토큰을, `eyJ…`는 JWT를 나타낸다.

---

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/social-login` | 소셜 자격 검증 후 서버 JWT 발급(기존 로그인/신규 온보딩 분기) — Google은 `idToken`, **Apple은 `authorizationCode`**([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)) | 불필요 | 200 |
| POST | `/api/v1/auth/phone/signup/verification-code` | 가입용 연락처 SMS 인증번호 발송(임대인 웹·비로그인) — 번호 키 챌린지 | 불필요 | 200 |
| POST | `/api/v1/auth/phone/signup/verify` | 가입용 인증번호 확인(임대인 웹·비로그인) → 번호 키 검증 마커 저장 | 불필요 | 200 |
| POST | `/api/v1/auth/signup` | 임대인 웹 회원가입(한 트랜잭션으로 ACTIVE 완주 + 같은 번호의 기존 앱 계정과 연동), refresh는 쿠키 | 불필요 | 200 |
| POST | `/api/v1/auth/login` | 임대인 웹 로그인(이메일·비밀번호, 10회 실패 잠금), refresh는 쿠키 | 불필요 | 200 |
| POST | `/api/v1/auth/phone/find-email/verification-code` | 이메일 찾기용 연락처 SMS 인증번호 발송(임대인 웹·비로그인) — 가입용(§1-1)과 별도 키스페이스·별도 예산 | 불필요 | 200 |
| POST | `/api/v1/auth/phone/find-email/verify` | 이메일 찾기용 인증번호 확인 → 이메일 찾기 전용 검증 마커 저장(소비처는 §1-7뿐) | 불필요 | 200 |
| POST | `/api/v1/auth/email/find` | 가입 이메일 찾기(마커 소비 + 이름 대조) — 마스킹한 웹 로그인 ID 반환 | 불필요 | 200 |
| POST | `/api/v1/auth/password/reset-link` | 비밀번호 재설정 링크 메일 발송(**계정 잠금 해제 겸용 진입점**) — 가입 여부와 무관하게 같은 200 | 불필요 | 200 |
| POST | `/api/v1/auth/password/reset-token/verify` | 재설정 토큰 사전 확인(**토큰을 소비하지 않는다**) — 마스킹 이메일·남은 초 | 불필요 | 200 |
| POST | `/api/v1/auth/password/reset` | 비밀번호 재설정 확정 — 잠금 해제 + 실패 카운터 초기화 + 기존 세션 전량 무효화(새 세션 미발급) | 불필요 | 204 |
| POST | `/api/v1/auth/terms` | 약관 동의 제출(이용약관·개인정보처리방침·마케팅), 약관 동의 완료(TERMS_AGREED 전이) | 필수(온보딩 토큰) | 200 |
| POST | `/api/v1/auth/email/verification-code` | 이메일로 인증번호 발송(세입자) — 정식(ACTIVE) 사용자 전용(#192) | 필수(정식 토큰(ACTIVE, ROLE_USER)) | 200 |
| POST | `/api/v1/auth/email/verify` | 인증번호 확인(세입자) — 접근만 ACTIVE 전용, 실제 이메일 변경 반영은 후속 이슈 | 필수(정식 토큰(ACTIVE, ROLE_USER)) | 200 |
| POST | `/api/v1/auth/phone/verification-code` | 연락처로 SMS 인증번호 발송(임대인 전용) — 온보딩(US-1-10)·프로필 변경(US-1-5) 공용 | 필수(온보딩 토큰/정식 토큰) | 200 |
| POST | `/api/v1/auth/phone/verify` | 인증번호 확인 → 연락처 검증 완료 처리(임대인 전용) — 온보딩·프로필 변경 공용 | 필수(온보딩 토큰/정식 토큰) | 200 |
| POST | `/api/v1/auth/onboarding` | 세입자 온보딩 필수정보 제출(약관 동의 선행), 가입 완료(ACTIVE 전이) | 필수(온보딩 토큰, TERMS_AGREED) | 200 |
| POST | `/api/v1/auth/business/verify` | 사업자등록번호 외부 검증(임대인 전용·온보딩 완료 후 무상태 검증), 결과 미저장·응답 body에만 반환 | 필수(정식 토큰(ACTIVE, ROLE_USER)) | 200 |
| POST | `/api/v1/auth/landlord/onboarding` | 임대인 온보딩 제출(약관·연락처 인증 선행), 가입 완료(ACTIVE 전이 + userType=LANDLORD 확정) — 같은 번호의 웹 계정이 있으면 **병합** | 필수(온보딩 토큰, TERMS_AGREED) | 200 |
| POST | `/api/v1/auth/reissue` | refresh 토큰으로 access 토큰 재발급(refresh는 쿠키 우선 · 본문 fallback) | 불필요 | 200 |
| POST | `/api/v1/auth/logout` | 현재 세션 refresh 토큰 무효화(refresh는 쿠키 우선 · 본문 fallback, 쿠키로 온 요청은 삭제 쿠키 동반) | 필수 | 204 |
| GET | `/api/v1/users/me` | 내 프로필 조회 | 필수 | 200 |
| PATCH | `/api/v1/users/me` | 내 프로필 부분 수정 | 필수 | 200 |
| DELETE | `/api/v1/users/me` | 회원 탈퇴(WITHDRAWN 전이, 토큰 일괄 무효화, 삭제 쿠키 동반) | 필수 | 204 |
| GET | `/api/v1/users/me/blocks` | 내가 차단한 사용자 목록(해제용) | 필수 | 200 |
| DELETE | `/api/v1/users/me/blocks/{userId}` | 차단 해제(멱등) | 필수 | 204 |

> 차단 **생성**은 이 문서에 없다 — 예약 문맥 전용 `POST /api/v1/bookings/{bookingId}/block`([04-booking-inquiry-chat](04-booking-inquiry-chat.md))이 유일한 생성 경로이고, 목록 조회(§11)·해제(§12)만 `user` 모듈이 맡는다(§11 근거 블록쿼트 참조).
> `auth/onboarding`은 신규 리소스 생성이 아니라 약관 동의를 마친 `TERMS_AGREED` 사용자를 `ACTIVE`로 전이하는 상태 액션이므로 `200`을 쓴다(api-design-guide §1 — "생성 아닌 액션"). 임대인 웹 회원가입(§1-3)도 같은 이유로 `200`이다 — 응답이 리소스 URI가 아니라 토큰·세션이고, 기존 계정에 연동되는 경우에는 새 `users` 행조차 만들지 않는다.
> **웹 트랙 10개 경로(§1-1~§1-10)는 인증 불필요(permitAll)** 다 — 계정이 없거나, 로그인 이전 단계이거나, **로그인할 수 없게 된 상태에서 부르는 복구 경로**라서다. 보안 설정에서는 `SecurityConfig`의 permitAll 매처와 **`PublicPaths.ALL` 두 곳 모두에 등록**해야 하며, **경로를 새로 여는 커밋마다 두 곳을 함께 본다.** 한쪽만 넣으면 **토큰 없이 부르는 로컬·통합 테스트는 전부 초록이고**, 만료된 access 토큰이 남아 있는 브라우저에서만 `401 TOKEN_EXPIRED`가 난다 — 실패가 특정 클라이언트 상태에서만 재현돼 배포한 뒤에야 드러난다(#181이 고친 것과 같은 버그).
> 인증 "필수" 엔드포인트는 access 토큰 만료 시 `401 TOKEN_EXPIRED`로 재발급을 유도한다. **온보딩 토큰**(`ROLE_ONBOARDING` — `onboardingCompleted=false`, 상태 `PENDING`/`TERMS_AGREED` 공통)으로 `GET`/`PATCH /users/me`·`POST /auth/logout`·`POST /auth/email/verification-code`·`POST /auth/email/verify`(세입자 이메일 인증 — #192에서 온보딩 단계 전용→정식(ACTIVE) 전용으로 반전)(모두 `ROLE_USER` 필요) 보호 API에 접근하면 `403 AUTH_ONBOARDING_REQUIRED`를 반환한다(단, `DELETE /users/me`(탈퇴)·`POST /auth/terms`(약관 동의)·`POST /auth/phone/verification-code`·`POST /auth/phone/verify`(임대인 연락처 인증)·`POST /auth/onboarding`·`POST /auth/landlord/onboarding`(임대인 온보딩)은 온보딩 흐름이라 온보딩 토큰도 허용). 단 `/auth/phone/**`(연락처 인증)는 프로필 연락처 변경(US-1-5)을 위해 **정식 토큰(`ROLE_USER`)도 함께 허용**한다(온보딩 토큰·정식 토큰 양쪽 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8). 반대로 `POST /auth/business/verify`(사업자번호 검증)는 온보딩 흐름이 아니라 **온보딩을 완료한(ACTIVE) 임대인이 정식 토큰(`ROLE_USER`)으로만 호출**하는 무상태 검증 API로, 온보딩 토큰으로 접근하면 `403 AUTH_ONBOARDING_REQUIRED`다(§5-1). 상태 전이 순서는 `POST /auth/terms`(PENDING→TERMS_AGREED) → `POST /auth/onboarding`(TERMS_AGREED→ACTIVE)이며, 약관 미동의 상태(`PENDING`)에서 온보딩을 제출하면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`다.

---

## 상세

### 1. POST `/api/v1/auth/social-login` — 소셜 로그인/온보딩 분기

앱이 provider에서 받은 자격을 서버가 검증한다 — **Google은 `idToken`** 을 서명·`aud`·`iss`·`exp`로 검증하고, **Apple은 `authorizationCode`** 를 `POST https://appleid.apple.com/auth/token`에서 교환해 받은 `id_token`을 같은 방식으로 검증한 뒤 신원(`sub`·`email`)을 얻는다. **요청 본문의 `email`·`name`은 앱이 네이티브 SDK(Apple `ASAuthorization` / Google account)에서 받아 함께 보내는 선택 필드**다 — Apple은 이름·이메일을 **최초 인증 1회만** 클라에 주므로 재로그인 요청엔 없을 수 있다. 그래서 이 둘은 **최초 로그인(=신규 가입) 시에만 `User`에 영구 저장**하고, 재로그인(기존 회원)에서는 **요청 값으로 `User`를 덮지 않고 저장된 값을 쓴다**(사용자 편집 보호 — provider 스냅샷 `SocialAccount`는 로그인마다 갱신). **최초 로그인 시** 서버는 요청 `email`이 토큰의 `email` 클레임과 **일치하는지 교차 검증**하고(불일치 `422 AUTH_EMAIL_MISMATCH`, 토큰·요청 어느 쪽에도 `email`이 없으면 `422 AUTH_EMAIL_REQUIRED`) `email`을 provider 진본으로 확정하며, **`name`은 검증하지 않고 요청 값을 신뢰**한다(없으면 `null` — 이후 `PATCH /users/me`로 수정). Apple은 교환으로 받은 `refresh_token`을 저장해 **탈퇴 시 토큰 폐기**(§10)에 사용한다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)). 기존 `ACTIVE` 회원이면 로그인 처리하고 access+refresh 토큰을 발급한다(`status=ACTIVE`, `onboardingRequired=false`). 신규이거나 **가입을 끝내지 못한 회원(`PENDING`·`TERMS_AGREED`)** 이면 온보딩 전용 access 토큰(`onboardingCompleted=false` 클레임)과 `onboardingRequired=true`로 응답한다(refresh 토큰은 발급하지 않음). **신규면 이 시점에 `PENDING` User 레코드를 새로 만들며 요청 `name`·`email`을 즉시 채운다**(온보딩까지 미루지 않는다). `SocialAccount`(auth)에는 `provider`/`providerUserId`/`email`/`name`/`userId`를 저장한다 — `email`·`name`은 **provider가 준 값의 스냅샷**으로 로그인마다 최신 provider 값으로 upsert한다(Google은 갱신, Apple `name`은 최초값 유지·재로그인 시 미제공). 이는 `User`의 `name`·`email`(사용자 값 — 최초 로그인에만 세팅·이후 사용자 편집)과 **별개로 이중 관리**된다: 사용자 수정은 `User`만 건드리고, provider 변경은 `SocialAccount` 스냅샷에 반영한다(email은 심사계정 매칭 등에 활용).

응답의 **`status`로 클라이언트가 다음 화면을 분기**한다 — `PENDING`(소셜 로그인만 하고 약관 미동의)이면 **약관 동의 화면(§2)**, `TERMS_AGREED`(약관 동의했으나 온보딩 미완료)이면 **온보딩 화면(§5)**, `ACTIVE`이면 홈. 온보딩 토큰으로는 `GET /users/me`(ROLE_USER)가 `403`이라 상태를 따로 조회할 수 없으므로, 재개 지점은 이 응답의 `status`로 판단한다.

- **인증**: 불필요.
- Path/Query 파라미터: 없음.

#### Request Body

provider별로 **자격 필드 하나**를 채우고(Google은 `idToken`, Apple은 `authorizationCode` — 둘 다 단일 엔드포인트·동일 응답, [ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md) A안), **`email`·`name`은 provider 공통으로 앱이 네이티브 SDK에서 받아 함께 보내는 선택 필드**다 — **최초 로그인(신규 가입)에서만 캡처·영구 저장**하고 재로그인 요청 값은 무시한다(Apple은 최초 1회만 제공 — 애플 심사 대응, #192).

```json
// Google
{
  "provider": "GOOGLE",
  "idToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6...",
  "email": "minh@example.com",
  "name": "Minh Nguyen"
}
```

```json
// Apple
{
  "provider": "APPLE",
  "authorizationCode": "c1a2b3...",
  "email": "minh@example.com",
  "name": "Minh Nguyen"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `provider` | string(enum) | 필수 | `APPLE` \| `GOOGLE` 중 하나. 누락·빈값·허용 외 값 모두 `INVALID_INPUT`(#151에서 통일 — 요청 DTO가 String으로 받아 서버가 파싱한다) |
| `idToken` | string | provider별 | **Google 필수**. Google 발급 OIDC ID 토큰. Apple은 사용하지 않음 |
| `authorizationCode` | string | provider별 | **Apple 필수**. `ASAuthorizationAppleIDCredential.authorizationCode`(UTF-8 디코드한 문자열, 1회용·약 5분). Google은 사용하지 않음 |
| `email` | string | 선택(최초 로그인 필수) | 이메일 형식. 앱이 네이티브 SDK에서 받은 이메일. **최초 로그인(신규 가입)에서만 필요** — 이때 토큰의 `email` 클레임과 일치해야 하고(불일치 `AUTH_EMAIL_MISMATCH` 422, 토큰·요청 모두 email이 없으면 `AUTH_EMAIL_REQUIRED` 422) provider 진본으로 확정해 `User.email`에 영구 저장한다. 재로그인 요청 값은 무시(저장값 사용, 덮어쓰지 않음) |
| `name` | string | 선택 | 앱이 네이티브 SDK에서 받은 표시 이름(성·이름을 합친 단일 값). **검증하지 않고 요청 값을 신뢰**한다. Apple은 이름을 최초 로그인 1회만 반환 → **최초 로그인(신규 가입)에서만 캡처해 `User.name`에 영구 저장**하고(없으면 `null` → `PATCH /users/me`로 수정), 재로그인 요청 값은 무시(저장값 유지) |

> 필수 여부가 provider에 따라 달라(`idToken`↔`authorizationCode`) Bean Validation 대신 **application 계층에서 검증**한다 — 해당 provider의 자격 필드가 비어 있으면 `400 AUTH_MISSING_CREDENTIAL`. Apple `authorizationCode`는 1회용이므로 서버가 즉시 교환한다(재사용 시 `401 AUTH_INVALID_SOCIAL_TOKEN`).

#### 성공 Response — 기존 회원(ACTIVE) (200 OK)

```json
{
  "success": true,
  "data": {
    "onboardingRequired": false,
    "status": "ACTIVE",
    "email": "minh@example.com",
    "name": "Minh Nguyen",
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

#### 성공 Response — 신규·미완료 회원(PENDING·TERMS_AGREED) (200 OK)

```json
{
  "success": true,
  "data": {
    "onboardingRequired": true,
    "status": "PENDING",
    "email": "minh@example.com",
    "name": "Minh Nguyen",
    "tokenType": "Bearer",
    "accessToken": "eyJ...onboarding-scope",
    "refreshToken": null,
    "expiresIn": 1800
  },
  "error": null
}
```

> 신규 가입과 약관 미동의 상태로 재로그인한 회원은 `status="PENDING"`(→ 약관 동의 화면 §2). 약관까지 동의하고 온보딩만 못 끝낸 채 재로그인한 회원은 같은 형태로 `status="TERMS_AGREED"`(→ 온보딩 화면 §5)를 받는다. 두 경우 모두 `onboardingRequired=true`·`refreshToken=null`이다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `onboardingRequired` | boolean | 가입 미완료 여부(`status != ACTIVE`). 편의 플래그 |
| `status` | string(enum) | 사용자 현재 상태 `PENDING` \| `TERMS_AGREED` \| `ACTIVE`. 클라이언트는 이 값으로 다음 화면을 분기(PENDING→약관 동의 §2, TERMS_AGREED→온보딩 §5, ACTIVE→홈) |
| `email` | string | 사용자 이메일(provider 진본). **모든 분기(신규 PENDING·기존 ACTIVE·재로그인)에서 반환** — 온보딩 화면 프리필용. 값은 `User.email` |
| `name` | string \| null | 사용자 이름(단일 `name`). **모든 분기에서 반환** — 온보딩 화면 프리필용. 값은 `User.name`(아직 없으면 `null`) |
| `refreshToken` | string \| null | `ACTIVE` 로그인에서만 발급, 미완료(`PENDING`/`TERMS_AGREED`)는 `null` |

> `expiresIn`은 access 토큰 만료까지의 초(seconds). 미완료 회원에게 주는 access 토큰은 온보딩 흐름(약관 동의·임대인 연락처(SMS) 인증·온보딩) API만 통과시킨다(클레임 `onboardingCompleted=false`, refresh 미발급 — **세입자 이메일 인증(§3·§4)은 #192에서 온보딩 흐름에서 제외돼 정식(ACTIVE) 토큰 전용**이다). 온보딩 전용 임시 토큰 만료 1800초(30분), 정식 access 3600초(1시간) — [ADR-0011](../../adr/0011-token-lifetime-and-secret-policy.md)에서 확정.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `provider` 누락(null) (Bean Validation: `@NotNull`) |
| 400 | `MALFORMED_REQUEST` | 요청 본문을 JSON으로 해석할 수 없는 경우뿐이다 |
| 400 | `AUTH_MISSING_CREDENTIAL` | provider의 자격 필드 누락/빈값(Google `idToken` 또는 Apple `authorizationCode` 미전송) — application 계층 검증 |
| 401 | `AUTH_INVALID_SOCIAL_TOKEN` | Google `idToken`의 서명/`aud`/`iss`/`exp` 검증 실패, 또는 Apple 교환 실패(`invalid_grant`/`invalid_client` — 만료·재사용 코드, 잘못된 client_secret)와 교환으로 받은 `id_token` 검증 실패. **provider JWKS 조회 실패 등 OIDC 연동 오류도 현재 구현은 이 코드로 통합 처리**한다(아래 노트) |
| 422 | `AUTH_EMAIL_REQUIRED` | **최초 로그인(신규 가입) 시** 토큰의 `email` 클레임·요청 `email` 어느 쪽에도 이메일이 없음(provider 진본 이메일을 확정할 수 없음). 재로그인은 email 없이도 통과(저장값 사용) |
| 422 | `AUTH_EMAIL_MISMATCH` | **최초 로그인 시** 요청 `email`이 토큰의 `email` 클레임과 불일치(요청 값 위조 방어 — email은 provider 진본으로 확정) |
| 502 | `UPSTREAM_ERROR` | Apple `/auth/token` 인가코드 교환의 일시 장애(타임아웃·5xx·I/O). 자격 문제(401)가 아니므로 그대로 재시도할 수 있다(아래 노트) |

> **연동 실패 처리(현행)**: `OidcTokenVerifierImpl`은 JWKS 조회 실패·provider 응답 오류를 포함한 모든 OIDC 검증 실패를 `401 AUTH_INVALID_SOCIAL_TOKEN`으로 변환한다. Apple `/auth/token` 교환 호출의 인증 실패(`invalid_grant`/`invalid_client`)도 `401`로 통합하고, Apple 측 일시 장애·타임아웃 등 I/O·5xx는 `502 UPSTREAM_ERROR`로 분리한다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)). Google 경로는 종전대로 `502`/`503`을 내지 않는다(시퀀스 [US-1-1](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-1-social-login.md)·REST Docs 스니펫과 정합). 외부 연동 견고화(타임아웃·재시도·서킷브레이커) 확대는 [error-response-guide](../error-response-guide.md) §3 참고.

---

### 1-1. POST `/api/v1/auth/phone/signup/verification-code` — 가입용 연락처 인증번호 발송(임대인 웹·비로그인)

웹 회원가입(§1-3) **전에** 본인 소유 번호임을 증명하기 위해 입력한 연락처(휴대폰)로 SMS 인증번호를 발송한다(US-1-13). 인증번호 정책(6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 5회·재발송 간격 60초)과 `VerificationSmsSender` 포트는 임대인 온보딩용 §4-1과 **동일하게 재사용**하지만, **챌린지 키가 다르다** — §4-1은 `phone-verify:code:{userId}`라 로그인이 선행돼야 하는데 웹 가입자는 아직 계정이 없다. 그래서 이 경로는 **번호 키 챌린지**(`signup-phone:code:{정규화번호}`)를 쓰고 **인증 불필요(permitAll)** 경로로 연다. 서버는 입력 번호에서 **숫자만 남겨 정규화**한 뒤 키로 쓴다(하이픈 입력을 허용한다).

SMS는 §4-1과 같이 **동기 발송**하며 **발송에 성공한 뒤에만** 챌린지를 저장한다 — provider 장애·타임아웃이면 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다.

> **비로그인 permitAll 경로라 SMS 남용 방지가 필수다.** 문자 폭탄·발송비 남용 표면이므로 **번호 단위 + IP 단위 이중 레이트리밋**을 건다 — 재발송 간격 60초(§4-1과 동일), **같은 번호 5회/1시간**, **같은 IP 20회/1시간**. 초과는 모두 `429 TOO_MANY_REQUESTS`다.
> **계정 존재 여부를 노출하지 않는다** — 가입 이력이 있는 번호든 없는 번호든 동일하게 발송하고 동일한 응답을 준다. 이 응답으로 연동 대상 계정의 유무를 알 수 없다.
> **앱 심사용 고정 인증번호 우회는 이 경로에 적용하지 않는다** — 그 정책은 `userId`와 Google 소셜 계정을 기준으로 판정하는데 웹 가입은 둘 다 없는 단계다(앱 심사용 기능이라 웹과 무관하다).

- **인증**: 불필요(permitAll — 계정이 없는 가입 전 단계).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "01012345678"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 휴대폰 번호 형식(`@NotBlank` + `@Pattern`). **하이픈 허용** — 서버가 숫자만 남겨 정규화한 값을 챌린지 키로 쓴다. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `phoneNumber`는 마스킹해 반환한다(§4-1과 동일). 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber` 누락/빈값/형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 429 | `TOO_MANY_REQUESTS` | 재발송 간격 60초 미만, 같은 번호 5회/1시간 초과, 같은 IP 20회/1시간 초과 |
| 502 | `UPSTREAM_ERROR` | SMS 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 1-2. POST `/api/v1/auth/phone/signup/verify` — 가입용 인증번호 확인(임대인 웹·비로그인)

§1-1로 발송된 인증번호를 검증한다(US-1-13). 성공하면 **번호 키 검증 마커**(`signup-phone:verified:{정규화번호}`, TTL 1800초)를 저장하고, 가입 제출(§1-3)이 그 마커를 대조한 뒤 **소비(삭제)** 한다. 마커의 소비처가 §1-3 하나뿐이라 용도 구분 필드를 두지 않는다.

> **챌린지 부재(미발송·만료·이미 검증)**: 해당 번호의 챌린지(`signup-phone:code:{정규화번호}`)가 없으면 — 인증번호를 한 번도 요청하지 않았거나, TTL 만료, 이미 검증 완료로 소멸, 발송 실패(`502`)로 미저장 — 올릴 `attempts` 레코드 자체가 없으므로 **즉시 `422 AUTH_PHONE_VERIFICATION_FAILED`** 로 거절하고 인증번호 (재)요청(§1-1)을 유도한다.
> **불일치·만료·시도 상한(5회) 초과를 모두 `422` 한 코드로 응답**한다 — 시도 초과를 `429`로 분리하는 앱 트랙(§4-2)과 다른 점이다. 비로그인 경로라 챌린지의 존재·시도 상태를 응답으로 구분해 주지 않는다(계정·인증 시도 상태 비노출).

- **인증**: 불필요(permitAll — 계정이 없는 가입 전 단계).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "01012345678",
  "code": "482913"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 인증번호를 발송한 번호와 일치해야 함(정규화 후 비교 — 하이픈 유무는 무관) |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "verified": true
  },
  "error": null
}
```

> 검증 마커는 30분간 유효하다 — 그 안에 §1-3을 제출해야 하며, 만료되면 가입 제출이 `422 AUTH_PHONE_NOT_VERIFIED`로 거절되고 §1-1부터 다시 한다. **이 응답은 연동 대상 계정의 유무를 알려주지 않는다** — 웹 가입 폼은 연동 여부와 무관하게 항상 전체 필드를 받는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber`/`code` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치, 만료, **검증 시도 상한(5회) 초과**, 또는 챌린지 부재(미발송·만료·이미 검증) — 앱 트랙(§4-2)과 달리 시도 초과도 이 코드다 |

---

### 1-3. POST `/api/v1/auth/signup` — 임대인 웹 회원가입(로컬 자격증명·비로그인)

임대인 웹 가입 폼 한 페이지의 값을 받아 **한 트랜잭션에서 `ACTIVE`까지 완주**한다(US-1-11). 앱의 3단계(소셜 로그인 → 약관 → 온보딩)는 *소셜이 이름·이메일만 주고 나머지를 나중에 받아야 하는* 제약에서 나온 구조인데 웹 폼에는 그 제약이 없다 — 웹에 `PENDING`/`TERMS_AGREED` 같은 부분 완료 상태를 남기면 **온보딩 재개 화면이 없어 로그인해도 갈 곳이 없는 죽은 계정**이 된다. 다만 **상태 체인과 도메인 전이는 앱과 똑같이 태운다**(`PENDING` → `TERMS_AGREED` → `ACTIVE`를 한 트랜잭션 안에서 연속 전이) — 앱 계정과 데이터 모양이 같아야 계정 연동이 성립하기 때문이다. 그래서 응답은 항상 `onboardingRequired=false`·`status="ACTIVE"`다.

**선행 조건은 §1-1·§1-2의 SMS 인증**이다. 제출된 번호의 검증 마커가 없으면 `422 AUTH_PHONE_NOT_VERIFIED`로 거절하고 **계정 생성도 연동도 하지 않는다** — 번호는 비밀이 아니므로, 인증 없이 번호만으로 기존 계정에 자격증명을 붙일 수 있으면 그 계정의 매물·예약·신청자 PII까지 통째로 탈취된다. 소유 증명은 전적으로 SMS 인증이 담당한다.

**연동 판정**은 정규화된 번호 **단독**으로 한다(이름은 매칭 조건이 아니다 — 앱 이름은 소셜 SDK 표기, 웹 이름은 직접 입력이라 불일치가 자연스럽고, 불일치로 계정이 갈리면 사용자는 "앱에서 내 매물의 예약이 안 보인다"만 겪고 원인을 알 수 없다). 그 번호의 `ACTIVE`·`LANDLORD` `users` 행을 잠금 조회해서 **있으면 그 `user_id`에 `local_accounts` 행만 추가**하고(`linked=true`, `users`는 건드리지 않는다), **없으면 새 `users` 행을 만들어** 위 전이를 태운다(`linked=false`). 서버 고정값은 앱과 같다 — `country=KR` · `lang=ko` · 닉네임 자동 배정 · `userType=LANDLORD`. 사업자등록번호는 가입에서 받지 않는다 — 앱 임대인과 동일하게 **매물 등록(`POST /api/v2/listings`) 요청 본문**에 담는다([03-listings-favorites](03-listings-favorites.md)).

> **검증 게이트 우선순위**: ① 제출 `phoneNumber`의 가입용 인증 마커 부재 → `422 AUTH_PHONE_NOT_VERIFIED` → ② 필수 약관 2종 미동의 → `422 AUTH_REQUIRED_AGREEMENT_MISSING` → ③ `local_accounts.email` 중복 → `409 AUTH_EMAIL_ALREADY_REGISTERED` → ④ 번호로 매칭된 계정에 이미 `local_accounts` 행이 있음 → `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS` 순으로 판정한다. 어느 단계에서 실패하든 **DB 쓰기 전체를 롤백**해 `users` 행만 생기고 자격증명이 없는(= 로그인 불가) 상태나 그 반대를 남기지 않는다. 이 원자성은 **MySQL 쓰기에만** 걸린다 — 커밋 시점에 실패하는 요청(아래 `409 RESOURCE_CONFLICT`)은 토큰이 이미 발급된 뒤라 **쓰이지 않을 refresh 해시 하나가 Redis에 14일 TTL로 남는다**(원문은 응답으로 나가지 않아 악용 불가, 항목은 스스로 만료 — §5-2 알려진 제약과 같은 모양이다).
> **`linked=true`(성공)와 `AUTH_WEB_ACCOUNT_ALREADY_EXISTS`(409)는 같은 조회의 서로 다른 가지**다 — 번호로 기존 계정을 찾은 것까지는 같고, **그 계정에 웹 자격증명이 이미 붙어 있는지**에서 갈린다. 앱만 쓰던 사람이 웹에 처음 가입하면 붙일 자리가 비어 있으니 연동 성공이고, 웹 계정이 있는 사람이 또 가입하면 자리가 이미 찼으니 409다. 후자에 제출된 이메일·비밀번호로 할 수 있는 일은 기존 자격증명을 **덮어쓰는 것**뿐인데, 그건 가입이 아니라 자격증명 교체이며(로그인 ID까지 조용히 바뀐다) 가입 엔드포인트가 할 일이 아니다 — 로그인 화면(§1-4)으로 보낸다.
> **이메일 중복 검사는 `local_accounts.email`만 본다.** `users.email`은 보지 않고 UNIQUE도 걸지 않는다 — 임대인 대다수는 **앱 소셜 계정과 같은 이메일로 웹 가입**할 것이고, `users.email`까지 유일하게 걸면 본인이 본인 이메일로 가입하려다 409를 맞는다. 소셜 로그인은 `(provider, providerUserId)`로 판정하므로 `users.email`은 로그인에 쓰이지 않는다. 유일해야 하는 것은 **로그인 ID**뿐이고 그건 `local_accounts.email`이다.
> **폼의 `name`·`birthDate`는 `local_accounts`에 스냅샷으로 저장**하고(`social_accounts.name`과 같은 성격), **연동 시 `users`를 덮어쓰지 않는다** — 기존 값은 온보딩을 마친 확정 값이고 폼 값은 방금 입력한 미검증 값이다. 덮어쓰면 "가입했더니 내 프로필이 바뀌었다"는 놀라운 동작이 된다. 신규 가입일 때만 폼의 `name`·`email`·`birthDate`가 `users`에도 기록된다. **응답에는 언제나 `users`의 값이 나간다**(표시 규칙 — §개요 웹 임대인 트랙).

> **알려진 제약**
> — **온보딩 미완료 앱 계정은 매칭되지 않는다(정상 동작).** 소셜 로그인만 하고 임대인 온보딩을 마치지 않은 계정은 `phone_number`가 NULL이라 걸리지 않아 **새 계정으로 가입**된다(`linked=false`). 그 앱 계정이 나중에 임대인 온보딩을 마칠 때 §5-2가 병합하므로 최종적으로 하나로 수렴한다.
> — **세입자 계정은 매칭 대상이 아니다.** 세입자는 정의상 `phone_number`가 NULL이므로 구조적으로 후보에서 빠진다(역할 검사·거부 분기를 따로 두지 않는다). 앱 세입자가 웹에 임대인으로 가입하면 **별개 계정**이 생기고, 서버는 두 계정이 동일인인지 알 수 없어 안내도 하지 않는다(세입자→임대인 전환 미지원 — `userType`은 온보딩 확정 후 불변).
> — **번호 정규화 백필이 없다.** 정규화는 입력 경로에만 적용하므로 **하이픈으로 저장된 기존 임대인 번호는 매칭에서 누락**될 수 있다(연동되지 않고 별개 계정이 생긴다).
> — **남이 쓰는 소셜 이메일로 웹 가입하는 것을 막지 않는다.** 번호가 다르면 매칭되지 않아 별개 계정이 생기고 `users.email`이 같은 사용자가 둘 존재할 수 있다. 매물·예약은 `user_id`로 갈리므로 섞이지 않으며, 이메일은 **웹 로그인 ID일 뿐 계정 복구 수단이 아니라** 계정 탈취로 이어지지 않는다.

- **인증**: 불필요(permitAll — 계정이 없는 가입 단계).
- Path/Query 파라미터: 없음.

#### Request Body

**폼은 연동 여부와 무관하게 항상 전체 필드를 받는다** — 기획된 화면이 하나이고 분기가 없다.

```json
{
  "name": "김임대",
  "birthDate": "1990-01-01",
  "phoneNumber": "01012345678",
  "email": "kim@work.com",
  "password": "Kohere1!",
  "termsOfServiceAgreed": true,
  "privacyPolicyAgreed": true,
  "marketingAgreed": false
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `name` | string | 필수 | 성·이름을 합친 단일 이름(`@NotBlank`). 빈 문자열 불가. 신규 가입이면 `users.name`에 기록하고, **연동이면 `local_accounts.name` 스냅샷으로만 저장**한다(`users` 미갱신) |
| `birthDate` | string(date) | 필수 | `YYYY-MM-DD`, 과거 날짜만 허용(미래 불가) — 온보딩(§5·§5-2)과 동일 규칙. `name`과 같이 연동 시에는 스냅샷으로만 저장 |
| `phoneNumber` | string | 필수 | 휴대폰 번호 형식(하이픈 허용 — 서버가 숫자만 남겨 정규화). **§1-1·§1-2로 인증된 번호**여야 함(마커 부재·만료 `AUTH_PHONE_NOT_VERIFIED` 422). 신규 가입이면 정규화 값을 `users.phone_number`에 기록한다(그래야 반대 방향 §5-2에서 이 계정이 매칭 후보가 된다) |
| `email` | string | 필수 | 이메일 형식(`@NotBlank`·`@Email`). **웹 로그인 ID** — `local_accounts.email`에 UNIQUE이며 중복이면 `AUTH_EMAIL_ALREADY_REGISTERED`(409). **신규 가입일 때만** `users.email`에도 함께 기록하고, 연동이면 소셜 진본을 유지한다(`users.email` 미갱신) |
| `password` | string | 필수 | 영문자(`A-Za-z`) 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, **길이 8~20**, 공백 불허(`@NotBlank`·`@Pattern`). 위반은 `INVALID_INPUT`(400, `errors[].field=password`). **BCrypt 해시로만 보관**하고 원문은 저장·로그하지 않는다 |
| `termsOfServiceAgreed` | boolean | 필수 | 이용약관 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422). 앱 `POST /auth/terms`(§2)와 같은 3필드를 가입 폼이 대신 받는다 |
| `privacyPolicyAgreed` | boolean | 필수 | 개인정보처리방침 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의(기본 `false`). 이후 `PATCH /users/me`(§9)로 변경한다 |

> **화면 고지와 기록의 일치**: 웹 가입 화면에는 **개인정보 수집·이용 동의 체크박스만** 두고 서비스 이용약관은 **가입 버튼 문구**("가입하기를 누르면 서비스 이용약관에 동의하는 것으로 봅니다")로 갈음한다 — 화면에 고지가 있어야 `termsOfServiceAgreed=true` 기록이 허위가 아니다. 서버 기록은 앱(§2)과 동일하므로(`termsVersion`·`agreedAt` 포함) 계정 연동에도 영향이 없다.

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "linked": false,
    "onboardingRequired": false,
    "status": "ACTIVE",
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "expiresIn": 3600,
    "email": "kim@work.com",
    "name": "김임대"
  },
  "error": null
}
```

refresh 토큰은 **응답 본문에 없고** `Set-Cookie` 헤더로만 내려간다(속성은 §개요 웹 임대인 트랙의 표 — `local` 프로파일에서만 `Secure`를 뺀다).

```http
Set-Cookie: refreshToken=rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `linked` | boolean | 기존 앱 계정에 자격증명만 붙였으면 `true`(새 `users` 행을 만들지 않았다), 새 계정을 만들었으면 `false` |
| `onboardingRequired` | boolean | **항상 `false`** — 웹 가입은 한 트랜잭션으로 `ACTIVE`까지 완주한다 |
| `status` | string(enum) | **항상 `"ACTIVE"`** — 웹에는 부분 완료 상태가 없다 |
| `accessToken`·`tokenType`·`expiresIn` | string·string·number | 정식 access 토큰(`ROLE_USER`)과 만료까지의 초. 소셜 로그인과 같은 발급 로직을 쓴다 |
| `email` | string | **`users.email`** 값(표시 규칙) — 연동된 계정이면 폼에 적은 웹 이메일이 아니라 **소셜 진본 이메일**이 나갈 수 있다(의도된 동작). 폼 이메일은 `local_accounts.email`에만 남는다 |
| `name` | string \| null | **`users.name`** 값(표시 규칙). 연동 시에는 폼 값이 아니라 기존 값이 나가며, 기존 값이 없으면 `null` |

> 응답 본문에 `refreshToken` 필드를 두지 않는다 — 웹은 refresh를 스크립트가 읽을 수 없는 쿠키로만 보관한다. 이후 갱신은 §6, 로그아웃은 §7이며 둘 다 쿠키를 우선해 읽는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `name`·`birthDate`·`phoneNumber`·`email`·`password` 누락·빈값·형식 위반, 약관 필수 2종 누락(`@NotNull`). 위반 필드는 `errors[]`로 반환한다(비밀번호 정책 위반은 `field=password`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 409 | `AUTH_EMAIL_ALREADY_REGISTERED` | 같은 이메일의 `local_accounts` 행이 이미 있음(웹 로그인 ID 중복 — `users.email`은 검사하지 않는다) |
| 409 | `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` | 번호로 매칭된 계정에 이미 웹 자격증명이 붙어 있음 → 로그인(§1-4)으로 유도. **그 계정의 이메일은 마스킹해서도 응답에 싣지 않는다**(공통 에러 스키마의 `code`·`message`만) |
| 409 | `RESOURCE_CONFLICT` | 같은 번호의 앱 임대인 온보딩(§5-2)이 거의 동시에 계정을 확정해 `uq_users_phone_number`(V23)·`uq_local_accounts_*`(V22)에 걸림. 트랜잭션은 통째로 롤백되므로 계정이 갈라지지 않으며, **그대로 다시 제출하면** 상대가 만든 계정을 발견해 `linked=true`로 연동된다(재시도가 유효한 유일한 409다) |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | 제출 `phoneNumber`의 가입용 인증 마커가 없거나 만료(§1-1·§1-2 선행) — 계정 생성·연동 모두 하지 않는다 |
| 422 | `AUTH_REQUIRED_AGREEMENT_MISSING` | 필수 약관(이용약관/개인정보처리방침) 미동의 |

---

### 1-4. POST `/api/v1/auth/login` — 임대인 웹 로그인(이메일·비밀번호)

`local_accounts.email`로 계정을 특정하고 BCrypt 해시로 비밀번호를 검증한다(US-1-12). 기존 `/auth/social-login`(§1)에 `provider=LOCAL`을 끼워넣지 않는다 — 소셜 요청 DTO가 이미 provider별 조건부 자격 필드로 복잡해 `password`까지 섞으면 검증 분기가 3중이 된다. **토큰 발급·회전·재사용 탐지는 소셜 로그인과 같은 로직을 그대로 재사용**하며(규칙을 두 벌로 만들지 않는다), refresh만 응답 본문이 아니라 **HttpOnly 쿠키**로 내려간다.

**웹 계정은 항상 `ACTIVE`다** — 웹 가입이 한 트랜잭션으로 완주하므로(§1-3) 로그인에 **온보딩 재개 분기가 없다**. `onboardingRequired`는 항상 `false`, `status`는 항상 `"ACTIVE"`이며 앱처럼 `PENDING`/`TERMS_AGREED`로 로그인하는 경로가 존재하지 않는다.

> **자격증명 오류는 한 코드로 수렴한다** — 등록되지 않은 이메일과 비밀번호 불일치를 **동일하게 `401 AUTH_INVALID_CREDENTIALS`** 로 응답한다(status·code·문구가 같다). 다만 **`error.details`는 등록된 계정의 비밀번호 불일치에만 실리므로 그 유무로 가입 여부가 드러난다** — 아래 알려진 제약 참조.
> **비밀번호 10회 연속 실패 시 계정을 잠근다** — 10회째 실패에서 `failed_login_attempts=10`·`locked_at`을 기록하고, 이후에는 **비밀번호가 맞아도** `423 AUTH_ACCOUNT_LOCKED`다(잠금 판정이 자격증명 검증보다 우선한다). 로그인에 성공하면 실패 카운터를 0으로 되돌린다. 실패 횟수·잠금 시각은 **`local_accounts`의 컬럼**에 둔다 — 해제는 본인이 재설정(§1-10)을 완주하는 **명시적 행위로만** 일어나야 하고 시간이 지났다는 이유로 저절로 풀려서는 안 되는데, Redis TTL에 얹으면 그 구분이 사라진다. 게다가 컬럼이면 `locked_at`이 기록·소거된 시점 자체가 **"언제 잠겼고 언제 풀렸는가"의 감사 흔적**으로 남아 잠금 문의를 사후에 추적할 수 있다(TTL로 사라진 키는 아무것도 남기지 않는다). **V22 마이그레이션의 컬럼 주석에는 옛 근거("해제 기능 없음")가 그대로 남아 있다** — 적용된 마이그레이션은 체크섬 때문에 고칠 수 없으므로, 근거의 정본은 이 문서다.
> **시도 자체에 레이트리밋을 건다** — 자격증명 조회·해시 대조보다 **먼저** IP 60회/시간·이메일 20회/시간을 세고, 어느 한쪽이라도 넘으면 `429 TOO_MANY_REQUESTS`다. 두 가지를 동시에 막는다: ① 남의 이메일로 10회 틀려 잠그는 DoS(§1-8~§1-10으로 본인이 풀 수는 있지만, 매번 메일함을 다녀오게 만드는 것 자체가 피해다) ② 선행 조건 없이 BCrypt 라운드를 강제할 수 있는 `permitAll` 경로의 CPU 증폭(가입은 SMS 인증 마커 게이트 뒤에 있다). **한도를 먼저 보는 순서가 계약**이다 — 뒤에서 보면 막힌 요청도 이미 해시 비용을 치른 뒤라 ②를 막지 못한다. IP 축은 `X-Forwarded-For`가 호출자 손에 있어 위조 가능하므로 비용 가드일 뿐이고, 잠금 DoS를 실제로 묶는 것은 **이메일 축**이다.

> **알려진 제약**
> — **잠금은 본인이 풀 수 있지만, 저절로 풀리지는 않는다.** 잠긴 임대인은 §1-8로 재설정 링크를 받아 §1-10을 완주하면 비밀번호 교체와 동시에 잠금·실패 카운터가 사라진다. **별도 잠금 해제 API를 두지 않은 이유는 잠긴 사람이 아는 비밀번호가 이미 틀린 비밀번호이기 때문**이다 — 해제만 해 주면 같은 오타로 곧 다시 잠긴다. 시간 경과 자동 해제는 여전히 없다. **메일함 접근을 잃어 링크를 받지 못하는 계정**은 종전대로 운영자가 DB에서 `locked_at`을 비우는 길뿐이므로, 잠긴 임대인이 연락할 창구는 그대로 유지한다(코드 변경 없음). **`locked_at`만 비우면 그것으로 끝이다** — 잠기지 않았는데 카운터가 이미 상한 이상인 계정은 다음 실패를 `1`부터 다시 세므로, `failed_login_attempts`를 함께 지우는 것을 잊어 다음 오타 한 번에 재잠금되는 일은 없다.
> — **의도적 계정 잠금(DoS)이 가능하다.** 남의 이메일로 10회 틀리면 그 계정을 잠글 수 있다. 잠금 정책의 고전적 부작용이며 시도 레이트리밋(IP 60회/시간·이메일 20회/시간)으로 완화하나 완전히 막을 수 없다 — 수용하고 진행한다.
> — **계정 열거가 가능하다.** `error.details`가 등록된 계정의 비밀번호 불일치에만 실리므로, 임의의 비밀번호로 한 번 호출해 보면 그 이메일의 가입 여부를 알 수 있다. **해제 경로가 생긴 뒤에도 이 필드를 그대로 둔다** — 풀 수 있다는 것과 잠기지 않는 것은 다르고, 잠근 뒤에 메일함을 다녀오게 하는 것보다 **잠기기 전에 "두 번 남았다"를 보여 주어 멈추게 하는 편이 낫다.** 그 대가로 열거는 **다시 한 번 명시적으로 수용**한다. 잠긴 계정은 이미 `423`으로 존재를 드러내고 있고, 시도 레이트리밋은 이메일 축이 이메일 단위라 열거를 막지 못하며 IP 축은 위조 가능하므로 완화책이 아니다. **§1-8에서는 같은 선택을 하지 않는다** — 거기엔 선행 게이트가 없어 임의의 이메일로 부를 수 있으므로 가입 여부를 응답으로 가르지 않는다.

- **인증**: 불필요(permitAll — 로그인 이전 단계).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "kim@work.com",
  "password": "Kohere1!"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 이메일 형식(`@NotBlank`·`@Email`). 빈 문자열 불가. 누락·형식 위반은 `INVALID_INPUT`(400)이고, **형식은 맞지만 등록되지 않은 주소는 `AUTH_INVALID_CREDENTIALS`(401)** 다 |
| `password` | string | 필수 | 빈 문자열 불가(`@NotBlank`). 누락·빈값은 `INVALID_INPUT`(400), 값이 틀리면 `AUTH_INVALID_CREDENTIALS`(401) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "onboardingRequired": false,
    "status": "ACTIVE",
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "expiresIn": 3600,
    "email": "kim@work.com",
    "name": "김임대"
  },
  "error": null
}
```

```http
Set-Cookie: refreshToken=rt_3b1e7c5a2f9d04e8b6c1a07f5d2e93b4c8a16f0d; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600
```

> 필드 구성은 §1-3에서 `linked`만 뺀 것과 같다. `email`·`name`은 **`users`의 값**(표시 규칙)이라 **연동된 계정은 로그인에 쓴 이메일과 응답 `email`이 다를 수 있다** — 로그인 ID는 `local_accounts.email`이고 응답은 프로필의 정본을 보여 준다. refresh는 본문에 없고 쿠키로만 내려가며 속성은 §1-3과 동일하다.

#### 실패 Response — 401 (비밀번호 불일치)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AUTH_INVALID_CREDENTIALS",
    "message": "이메일 또는 비밀번호가 올바르지 않습니다.",
    "errors": [],
    "details": { "failedAttempts": 3, "maxFailedAttempts": 10 }
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `error.details.failedAttempts` | number | 이 계정에 누적된 연속 실패 횟수 |
| `error.details.maxFailedAttempts` | number | 계정을 잠그는 상한 |

> **`failedAttempts`가 `maxFailedAttempts`에 도달한 응답이 곧 잠금 시점이다** — 그 요청도 `401`이지만 그 시점에 `locked_at`이 기록되며, 다음 요청부터 `423`이다. 클라이언트는 두 값이 같아지면 잠금 안내를 띄운다.
>
> **`error.details`가 실리지 않는 실패** — 등록되지 않은 이메일, `ACTIVE`가 아닌 계정, `423`, `429`에서는 값이 `null`이 아니라 **필드 자체가 생략된다**.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email`/`password` 누락·빈값, `email` 형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `AUTH_INVALID_CREDENTIALS` | 등록되지 않은 이메일 **또는** 비밀번호 불일치 — code·status·문구가 같다. 비밀번호 불일치일 때만 `error.details`가 실린다 |
| 423 | `AUTH_ACCOUNT_LOCKED` | 비밀번호 10회 연속 실패로 잠긴 계정(`locked_at` 기록됨) — **비밀번호가 맞아도** 잠금이 우선한다. 해제는 §1-8~§1-10 재설정을 완주하는 것뿐이고, 시간이 지나도 풀리지 않는다 |
| 429 | `TOO_MANY_REQUESTS` | 로그인 시도 한도 초과 — 같은 IP 60회/시간 또는 같은 이메일 20회/시간. **자격증명을 조회하기 전에** 판정하므로 이메일 존재 여부와 무관하고, 어느 축에 걸렸는지도 구분해 알리지 않는다(한도 역산 방지) |

---

### 1-5. POST `/api/v1/auth/phone/find-email/verification-code` — 이메일 찾기용 연락처 인증번호 발송(임대인 웹·비로그인)

웹 로그인 ID(이메일)를 잊은 임대인이 본인 번호의 소유를 증명하도록 SMS 인증번호를 발송한다(US-1-16). 인증번호 정책(6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 5회·재발송 간격 60초), `VerificationSmsSender` 포트, 번호 정규화(숫자만 남김)와 응답 마스킹, **동기 발송에 성공한 뒤에만 챌린지를 저장하는 규칙**(실패는 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`)까지 **§1-1과 같다**. 앱 심사용 고정 인증번호 우회를 적용하지 않는 것도 같다. 다른 것은 셋뿐이다.

| 다른 점 | 이 경로의 값 |
| --- | --- |
| 챌린지 키 | `find-email:code:{정규화번호}` — §1-1의 `signup-phone:code:{정규화번호}`와 **다른 키스페이스** |
| 검증 마커 | `find-email:verified:{정규화번호}`(TTL 1800초) — 소비처는 **§1-7 하나뿐**이다(§1-3이 아니다) |
| 레이트리밋 예산 | `find-email:rate:phone:{정규화번호}` · `find-email:rate:ip:{ip}` — 번호 5회/1시간 · IP 20회/1시간 · 재발송 간격 60초. 가입용 버킷과 **합산하지 않는다** |

> **가입용 마커를 재사용하지 않는 이유는 마커가 「소유 증명」이 아니라 「용도가 붙은 소유 증명」이기 때문이다.** 하나의 마커로 §1-3(가입)과 §1-7(이메일 찾기)이 모두 열리면 두 방향으로 샌다 — 가입하려고 인증한 사람이 **그 번호에 이미 붙어 있는 남의 계정 이메일**을 덤으로 조회할 수 있고(중고 번호·가족 명의처럼 번호와 명의자가 어긋나는 경우가 실제로 있다), 반대로 복구 화면에서 받은 마커가 **가입 화면의 SMS 게이트를 대신 열어 준다**. 용도가 하나뿐이던 §1-2는 마커에 용도 구분 필드를 두지 않았는데, 용도가 둘이 된 지금 필드를 뒤늦게 추가하는 대신 **키스페이스를 나눈다** — 필드는 읽는 쪽이 검사하는 것을 잊을 수 있지만, 키가 다르면 조회 자체가 실패한다.
> **레이트리밋 예산을 나누는 이유도 같은 성격이다.** 가입 SMS를 5회 태워 막힌 사람이 이메일 찾기까지 막히면 복구 경로가 가입 실수의 인질이 되고, 반대로 복구 남용이 가입 발송 예산을 깎으면 정상 가입자가 막힌다. 발송비 방어는 용도별로 독립해야 한쪽의 남용이 다른 쪽의 정상 사용자를 차단하지 않는다.
> **계정 존재 여부를 노출하지 않는다** — 가입 이력이 있는 번호든 없는 번호든 동일하게 발송하고 동일한 응답을 준다. 이 경로에서 걸러 주면 SMS를 받아 볼 필요도 없이 **번호 열거**가 가능해진다. 그 번호로 가입된 웹 계정이 있는지는 §1-7이 판정한다.

- **인증**: 불필요(permitAll — 로그인 ID를 모르는 단계라 인증할 수단 자체가 없다).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 휴대폰 번호 형식(`@NotBlank` + `@Pattern`). **하이픈 허용** — 서버가 숫자만 남겨 정규화한 값을 챌린지 키로 쓴다. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `phoneNumber`는 마스킹해 반환한다(§1-1과 동일). 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber` 누락/빈값/형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 429 | `TOO_MANY_REQUESTS` | 재발송 간격 60초 미만, 같은 번호 5회/1시간 초과, 같은 IP 20회/1시간 초과 |
| 502 | `UPSTREAM_ERROR` | SMS 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 1-6. POST `/api/v1/auth/phone/find-email/verify` — 이메일 찾기용 인증번호 확인(임대인 웹·비로그인)

§1-5로 발송된 인증번호를 검증하고 **이메일 찾기 전용 검증 마커**(`find-email:verified:{정규화번호}`, TTL 1800초)를 저장한다(US-1-16). 검증 규칙과 **불일치·만료·검증 시도 상한(5회) 초과·챌린지 부재를 `422 AUTH_PHONE_VERIFICATION_FAILED` 한 코드로 묶는 것**까지 **§1-2와 같다**(앱 트랙 §4-2가 시도 초과를 `429`로 분리하는 것과 다른 점도 그대로다). 다른 것은 읽고 쓰는 키가 `find-email:*`라는 것과, 만든 마커의 **소비처가 §1-7 하나**라는 것이다.

> **한 코드로 묶는 이유도 §1-2와 같다** — 비로그인 경로에서 챌린지의 존재·시도 상태를 응답으로 구분해 주면 응답 자체가 상태 오라클이 된다.
> **이 응답은 계정의 유무를 말하지 않는다.** "번호를 검증했다"는 사실만 알린다 — 여기서 "그 번호로 가입된 계정이 없다"를 미리 알려 주면 **§1-5·§1-6 두 번만으로 번호 열거**가 성립하고, §1-7의 이름 대조 게이트를 통째로 건너뛰게 된다. 계정 판정은 이름까지 받아 보는 §1-7에서만 한다.

- **인증**: 불필요(permitAll — 로그인 ID를 모르는 단계).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678",
  "code": "482913"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | §1-5로 인증번호를 발송한 번호와 일치해야 함(정규화 후 비교 — 하이픈 유무는 무관) |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가. **자릿수·형식은 검증하지 않는다** — 검증이 해시 대조이고, §1-2가 같은 필드에 이미 같은 판단을 내려 두었다(형제 엔드포인트가 같은 입력에 다른 status를 내지 않게 한다) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "verified": true
  },
  "error": null
}
```

> 검증 마커는 30분간 유효하다 — 그 안에 §1-7을 제출해야 하며, 만료되면 `422 AUTH_PHONE_NOT_VERIFIED`로 거절되고 §1-5부터 다시 한다. **가입용 마커(§1-2)로는 §1-7을 통과할 수 없고 그 반대도 마찬가지다**(§1-5의 키스페이스 분리).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber`/`code` 누락·빈값, `phoneNumber` 형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치, 만료, **검증 시도 상한(5회) 초과**, 또는 챌린지 부재(미발송·만료·이미 검증) — §1-2와 동일하게 시도 초과도 이 코드다 |

---

### 1-7. POST `/api/v1/auth/email/find` — 가입 이메일 찾기(임대인 웹·비로그인)

§1-6이 만든 검증 마커를 소비하고 제출된 이름을 대조해, 그 번호로 가입된 웹 계정의 **마스킹된 로그인 이메일**을 돌려준다(US-1-16). 응답 이메일은 `local_accounts.email`이다 — 표시 규칙(§개요 웹 임대인 트랙)이 응답의 `email`을 `users`에서 가져오라고 하는 것과 어긋나 보이지만, 이 화면에서 사용자가 알아야 하는 것은 프로필 이메일이 아니라 **로그인 화면에 입력할 ID**다. 연동된 계정은 두 값이 다를 수 있으므로 정본을 잘못 고르면 찾아 준 이메일로 로그인이 되지 않아 화면 전체가 무용해진다.

이름 대조 대상은 **`local_accounts.name` 단독**이며 `users.name` 폴백을 두지 않는다. 웹 계정이 있다는 것은 곧 `local_accounts` 행이 있다는 뜻이고 그 행의 `name`은 **가입 폼에 본인이 직접 적은 값**이라 사용자가 다시 적어 맞힐 수 있다. 반면 연동된 계정의 `users.name`은 소셜 SDK 표기(로마자 등)라 본인도 무엇을 적어야 하는지 모른다 — 폴백을 두면 "둘 중 아무 이름이나 하나만 맞으면 통과"가 되어 대조가 느슨해지기만 하고 얻는 것이 없다.

> **여기서는 계정 존재를 드러낸다(`404`).** §1-8과 정반대 선택인데, 갈리는 지점은 **선행 게이트**다. 이 경로는 §1-5·§1-6의 SMS 인증 마커 뒤에 있어 호출자가 조회할 수 있는 번호는 **방금 소유를 증명한 자기 번호 하나**뿐이다. 열거 표면이 자기 번호로 닫혀 있으니 "없다"고 말해도 남의 정보가 새지 않고, 반대로 숨기면 사용자는 **"가입한 적이 없다"와 "이름을 잘못 적었다"를 구분하지 못해** 무엇을 고쳐야 할지 알 수 없다.
> **이름 불일치와 계정 미존재는 같은 `404`로 수렴시킨다.** 둘을 가르면 남는 것은 **이름 오라클**이다 — 자기 번호로 인증한 사람이 그 번호에 붙은 계정의 명의자 이름을 한 후보씩 확인할 수 있게 된다(번호는 손에 있지만 명의자를 모르는 경우 — 중고 번호·가족 명의). **계정 존재까지는 드러내되 명의자 이름은 드러내지 않는다**가 경계선이다.
> **성공하면 마커를 소비(삭제)한다** — 마커 하나로 무제한 반복 조회하는 것을 막는다. 다만 **실패한 시도까지 마커를 태우지는 않으므로**, 마커 TTL(30분) 안에서 이름을 바꿔 가며 재시도할 여지는 남는다. 그 창을 닫는 것은 TTL뿐이고, 애초에 자기 번호로만 열리는 창이라 **받아들인다**.

- **인증**: 불필요(permitAll — 로그인 ID를 모르는 단계).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678",
  "name": "김임대"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | **§1-5·§1-6으로 인증된 번호**여야 함(정규화 후 비교). 마커 부재·만료는 `AUTH_PHONE_NOT_VERIFIED`(422)이며 가입용 마커로는 통과하지 못한다 |
| `name` | string | 필수 | `@NotBlank` · `@Size(max = 200)`. **`local_accounts.name`과 대조**하며(`users.name` 폴백 없음), 불일치는 계정 미존재와 **같은 `404`** 다 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "ki***@work.com"
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `email` | string | **`local_accounts.email`**(웹 로그인 ID)을 마스킹한 값. 평문을 싣지 않는 것은 이 응답이 **본인 확인 결과가 아니라 번호 소유 증명 뒤의 힌트**여서다 — 로그인 화면에서 나머지를 기억해 채우는 데는 마스킹으로 충분하고, 그 이상은 번호를 손에 쥔 제3자에게도 그대로 넘어간다 |

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber`·`name` 누락·빈값·형식 위반·길이 초과 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 404 | `AUTH_WEB_ACCOUNT_NOT_FOUND` | 그 번호로 가입된 웹 계정이 없음 **또는** 제출한 이름이 `local_accounts.name`과 불일치 — **두 경우를 구분하지 않는다**(이름 오라클 차단) |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | 이메일 찾기용 검증 마커가 없거나 만료(§1-5·§1-6 선행). 가입용 마커(§1-2)는 이 자리에서 통하지 않는다 |

---

### 1-8. POST `/api/v1/auth/password/reset-link` — 비밀번호 재설정 링크 발송(잠금 해제 겸용 진입점)

로그인 ID(이메일)로 비밀번호 재설정 링크를 메일 발송한다(US-1-17). **"비밀번호를 잊었다"와 "계정이 잠겼다"(§1-4의 `423`)의 진입점이 같다** — 화면은 둘이지만 API는 하나다. 잠긴 사람이 아는 비밀번호는 이미 틀린 비밀번호라 잠금만 풀어 주면 같은 오타로 곧 다시 잠기고, 결국 **비밀번호를 바꾸는 것만이 상태를 실제로 되돌리는 행위**이기 때문이다. 그래서 독립 잠금 해제 API를 만들지 않는다.

링크는 `{app.web.base-url}` + `{app.auth.web.password-reset.path}` + `?token=…` 형태이고 도착 지점은 **프런트 SPA 페이지**(`/reset-password`)다. 토큰은 **일회용 불투명 토큰**이며 서버는 원문을 저장하지 않고 **해시만** 보관한다(TTL 30분 — 소비는 §1-10에서 단 한 번).

> **base URL을 요청 헤더로 조립하지 않는다.** `Host`·`X-Forwarded-Host`는 호출자가 채워 보낼 수 있는 값이라, 그것으로 링크를 만들면 공격자가 **자기 도메인이 박힌 재설정 링크를 피해자 메일함으로 보낼 수 있다** — 피해자가 클릭하는 순간 토큰이 공격자 서버로 넘어가고, 그것으로 §1-10을 부르면 계정이 통째로 넘어간다. base URL은 **설정값(`app.web.base-url`)에서만** 온다.
> **계정 존재를 드러내지 않는다.** 가입되지 않은 이메일에도 **같은 `200`**(`expiresIn`까지 동일)을 주고 메일만 보내지 않는다. §1-7과 반대로 가는 이유는 하나다 — **여기엔 선행 게이트가 없어 임의의 이메일로 부를 수 있다.** 응답을 가르는 순간 이 엔드포인트는 아무 자격 없이 무한히 두드릴 수 있는 **완전한 가입 여부 오라클**이 된다.
> **그 방어는 반쪽이고, 반쪽인 채로 받아들인다.** 메일 발송이 **동기**라 가입된 이메일은 SMTP 왕복 시간만큼 응답이 늦고 발송이 실패하면 `502`가 나가는 반면, 미가입 이메일은 아무 일도 하지 않고 즉시 `200`이다 — **응답 시간과 status 분포로 존재가 샌다.** 레이트리밋(이메일 5회/1시간 · IP 20회/1시간)은 이 누출의 완화책이 **아니다**. 그것은 발송비·메일 폭탄 남용을 막는 장치이고, 한 주소당 한 번만 재 보면 되는 관찰에는 아무 방해가 되지 않는다. 응답을 균일하게 만드는 정공법은 발송을 큐로 밀어내는 것이지만 이번 범위에서 도입하지 않는다 — **본문이 같다는 수준까지만 방어하고 타이밍 누출은 알면서 남긴다.**
> **`local`·`dev`에서만 켠다** — 토글 `app.auth.web.password-reset.enabled`가 base 설정에서 `false`이고 두 프로파일만 `true`다(prod 배포는 이번 범위 밖). 토글이 켜졌을 때만 기동 시 `app.web.base-url` 형식을 검증한다 — 값이 비어 있는 채로 켜지면 **깨진 링크가 담긴 메일이 조용히 나가고**, 그 사실은 사용자가 클릭한 뒤에야 드러난다.

- **인증**: 불필요(permitAll — 비밀번호를 모르거나 계정이 잠긴 상태라 인증할 수단이 없다).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "kim@work.com"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 이메일 형식(`@NotBlank` · `@Email` · `@Size(max = 255)`). **`local_accounts.email`(웹 로그인 ID)로 조회**하고 `users.email`은 보지 않는다 — 재설정 대상은 프로필이 아니라 웹 자격증명이다. **형식만 맞으면 등록 여부와 무관하게 `200`** 이다 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "expiresIn": 1800
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `expiresIn` | number | 링크 유효 시간(초). **가입 여부와 무관하게 항상 같은 값**이며, "계정을 찾지 못했다"를 담을 필드는 응답에 아예 없다 — 의도된 설계다 |

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email` 누락·빈값·형식 위반·길이 초과 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 429 | `TOO_MANY_REQUESTS` | 같은 이메일 5회/1시간 또는 같은 IP 20회/1시간 초과. 어느 축에 걸렸는지 구분해 알리지 않는다(한도 역산 방지) |
| 502 | `UPSTREAM_ERROR` | 메일 발송 실패(SMTP 장애·타임아웃). **가입된 이메일에서만 날 수 있어 그 자체가 존재 신호**이며, 위의 타이밍 누출과 같은 성격으로 수용한다 |

---

### 1-9. POST `/api/v1/auth/password/reset-token/verify` — 재설정 토큰 사전 확인(토큰 미소비)

SPA가 `/reset-password?token=…`에 도착하자마자 호출해 **링크가 아직 살아 있는지**와 **어느 계정의 링크인지**를 확인한다(US-1-17). 이 절이 없으면 사용자는 새 비밀번호를 두 번 입력하고 제출한 뒤에야 "만료된 링크"를 듣게 된다.

> **토큰을 소비하지 않는 것이 계약이다.** 메일 클라이언트의 링크 미리보기, 기업 메일 게이트웨이의 URL 안전 검사, SPA 개발 모드의 이중 렌더링처럼 **사용자가 클릭하기 전에 링크가 열리는 경우가 흔하다.** 여기서 토큰을 태우면 정작 본인이 클릭했을 때는 이미 죽은 링크이고, 사용자 눈에는 "메일을 받았는데 언제 눌러도 만료라고 한다"로 보인다 — 재현이 어렵고 원인은 서버 로그에도 정상 요청으로만 남는다. 소비는 오직 §1-10에서, 새 비밀번호와 함께 한 번만 일어난다.
> **그 대가로 이 경로는 토큰 대입 표면이 된다** — 소비하지 않으니 유효한 토큰을 찾을 때까지 두드릴 수 있다. 토큰이 `SecureRandom` 32바이트라 추측 비용이 현실적으로 성립하지 않는다는 데 기대며, 별도 레이트리밋을 두지 않는다.
> **응답 이메일은 마스킹한다.** 토큰만 있으면 부를 수 있는 경로라 평문을 실으면 **유출된 링크 하나가 계정 이메일까지 함께 넘긴다.** 화면이 "내 계정이 맞나"를 확인시키는 데는 마스킹으로 충분하다.

- **인증**: 불필요(permitAll — 로그인하지 못하는 상태에서 부르는 경로).
- Path/Query 파라미터: 없음. **토큰은 쿼리스트링이 아니라 본문으로 받는다** — 쿼리 파라미터는 액세스 로그·리퍼러에 원문 그대로 남는다.

#### Request Body

```json
{
  "token": "pr_9tWq3ZmK7xR2nB5vYcL8dH1sJgF4pA6uE0oT-Xi_Qz2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `token` | string | 필수 | §1-8이 메일로 보낸 링크의 `token` 값(`@NotBlank`). 서버는 해시로 대조하며 원문은 저장하지 않는다 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "ki***@work.com",
    "expiresIn": 1420
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `email` | string | 토큰이 가리키는 계정의 로그인 이메일을 **마스킹**한 값. 화면이 "이 계정의 비밀번호를 바꿉니다"를 보여 주는 용도다 |
| `expiresIn` | number | **남은 초**다 — 발급 시 고정값(1800)이 아니라 호출 시점 기준 잔여 시간이라 호출할 때마다 줄어든다. 화면의 카운트다운은 이 값에서 시작한다 |

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `token` 누락·빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 422 | `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 토큰 부재·만료·이미 사용됨 — **세 경우를 구분하지 않는다.** 구분하면 "존재했지만 이미 쓰였다"까지 알려 주는 오라클이 된다 |

---

### 1-10. POST `/api/v1/auth/password/reset` — 비밀번호 재설정 확정(잠금 해제 포함)

토큰과 새 비밀번호를 받아 **토큰 소비 · 비밀번호 교체 · 잠금 해제 · 실패 카운터 초기화 · 기존 세션 전량 무효화**를 한 번에 끝낸다(US-1-17). 비밀번호 정책은 가입(§1-3 `password`)과 **같은 규칙을 그대로 쓴다** — 복구 경로에서만 규칙이 다르면 가입 때 통과하던 비밀번호가 여기서 거부되거나 그 반대가 되고, 사용자는 규칙이 둘이라는 사실 자체를 알 길이 없다.

> **처리 순서가 계약이다 — ① 토큰 원자 소비(Redis) → ② 비밀번호 교체(MySQL) → ③ refresh 전량 무효화(Redis) → ④ 로그인 시도 카운터 삭제(Redis).** MySQL과 Redis에 걸친 원자성은 존재하지 않으므로, 어디서 끊기든 **남는 상태가 안전한 쪽**이 되도록 순서를 고정한다. 이 순서에서 중간 실패가 남기는 최악은 "토큰만 소비되고 비밀번호는 그대로"이고, 사용자는 §1-8에서 링크를 다시 받으면 된다 — **불편이지 취약점이 아니다.** 순서를 뒤집어 비밀번호부터 바꾸면 그사이 **이미 바뀐 비밀번호의 토큰이 아직 살아 있는 재사용 창**이 열린다.
> **토큰 소비는 원자적이어야 한다** — `GETDEL`(또는 Lua) 한 번으로 읽으면서 지운다. `조회 → 검증 → 삭제`로 쪼개면 같은 링크를 동시에 두 번 눌렀을 때 둘 다 통과해 **일회용이 아니게 된다.**
> **잠금 해제는 ②에 붙어 있다.** `local_accounts` 같은 행의 비밀번호 해시·`locked_at`·`failed_login_attempts`를 **한 번의 UPDATE로 함께** 되돌린다. 나눠 쓰면 비밀번호는 바뀌었는데 잠금이 남는 중간 상태가 생기고, 그 계정은 **맞는 비밀번호로도 `423`** 이다. **이 한 줄이 잠금 해제 API의 실체**이며 별도 해제 엔드포인트를 만들지 않은 이유이기도 하다.
> **④는 Redis의 로그인 시도 레이트리밋 카운터 중 이메일 축 하나**(`web-login:rate:email:{소문자 이메일}` — §1-4)다. 잠길 때까지 틀린 사람은 이미 시간당 한도를 상당히 태워 둔 상태라, 지우지 않으면 재설정을 마치고 돌아간 로그인 화면에서 `429`를 맞는다 — 복구를 끝내 놓고 문 앞에서 막는 셈이다.
> **IP 축은 지우지 않는다.** 이메일 축은 방금 토큰으로 **메일함 소유를 증명한 그 계정 하나**에 매인 카운터라 지워도 새는 것이 없지만, IP 축은 **그 IP를 쓰는 모든 호출자가 공유**한다. 재설정 완주로 IP 예산이 초기화되면, 계정 하나를 가진 공격자가 **자기 계정을 재설정하는 것만으로 남의 계정을 향한 시도 예산을 원하는 만큼 되살릴 수 있다** — 비용 가드가 통째로 무의미해진다. 대가로 「같은 IP에서 이미 60회를 태운 사용자는 재설정 직후에도 `429`」인 경우가 남지만, 그건 공유 IP의 정상 동작이고 한 시간이면 창이 지난다.
> **③를 빼먹으면 복구가 복구가 아니다.** 계정을 이미 빼앗긴 경우 공격자의 refresh 세션이 비밀번호를 바꾼 뒤에도 14일 그대로 살아남는다. 비밀번호 교체는 **새 로그인을 막는 일**일 뿐, 이미 열려 있는 세션을 닫지 않는다.
> **새 세션을 발급하지 않는다.** 응답은 `204`이고 refresh 쿠키를 싣지 않는다 — ③에서 방금 전량 무효화한 자리에 새 세션을 끼워 넣으면 **토큰 하나를 쥔 쪽이 그대로 로그인 상태가 된다**(유출된 링크를 주운 사람에게 세션까지 얹어 주는 셈이다). 새 비밀번호를 실제로 아는지는 로그인 화면(§1-4)에서 한 번 더 확인시킨다.

- **인증**: 불필요(permitAll — 로그인하지 못하는 상태에서 부르는 경로).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "token": "pr_9tWq3ZmK7xR2nB5vYcL8dH1sJgF4pA6uE0oT-Xi_Qz2",
  "newPassword": "Kohere2!"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `token` | string | 필수 | §1-8 링크의 토큰(`@NotBlank`). 부재·만료·이미 사용됨은 모두 `AUTH_PASSWORD_RESET_TOKEN_INVALID`(422) |
| `newPassword` | string | 필수 | **가입(§1-3) `password`와 동일한 정책** — 영문자(`A-Za-z`) 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, **길이 8~20**, 공백 불허(`@NotBlank` · `@Pattern`). 위반은 `INVALID_INPUT`(400, `errors[].field=newPassword`). **BCrypt 해시로만 보관**하고 원문은 저장·로그·응답 어디에도 남기지 않는다 |

> **정책 위반(400)은 토큰을 소비하기 전에 걸러진다** — Bean Validation이 컨트롤러 진입 시점에 판정하므로 위 처리 순서의 ①에 닿지 않는다. 오타 한 번에 링크가 죽으면 사용자는 규칙을 배우는 동안 메일을 반복해서 받아야 한다.

#### 성공 Response — 204 No Content

본문 없음. **`Set-Cookie`도 없다** — 재설정은 세션을 만드는 자리가 아니다(위 블록쿼트). 클라이언트는 `204`를 받으면 로그인 화면(§1-4)으로 보내고, 사용자는 새 비밀번호로 처음부터 로그인한다. 잠겨 있던 계정이라면 이 시점에 잠금과 실패 카운터가 이미 사라진 상태다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `token`·`newPassword` 누락·빈값, 비밀번호 정책 위반(`errors[].field=newPassword`) — 토큰은 소비되지 않는다 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 422 | `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 토큰 부재·만료·이미 사용됨(§1-9와 같은 코드·같은 비구분 원칙). 이 판정이 ①이므로 실패하면 비밀번호·잠금·세션 어느 것도 건드리지 않는다 |

---

### 2. POST `/api/v1/auth/terms` — 약관 동의

소셜 로그인 후 `PENDING` 사용자가 약관에 동의해 가입 흐름의 첫 단계를 마친다. 성공 시 `TERMS_AGREED`로 전이하고, 서버가 동의 시각(`agreedAt`)과 약관 버전(`termsVersion`)을 기록한다([ADR-0012](../../adr/0012-terms-version-management.md)). 이 단계 이후에야 온보딩 정보 제출(§5)이 가능하다.

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 토큰은 갱신하지 않는다(상태만 전이).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "termsOfServiceAgreed": true,
  "privacyPolicyAgreed": true,
  "marketingAgreed": false
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `termsOfServiceAgreed` | boolean | 필수 | 이용약관 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422) |
| `privacyPolicyAgreed` | boolean | 필수 | 개인정보처리방침 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의(기본 `false`). 세분화된 마케팅 동의 항목은 고도화 예정(확인 필요) |

> `termsVersion`은 클라이언트가 보내지 않고 서버가 설정값(`app.terms.version`)을 기록한다. 약관 버전·문구 변경 시 재동의 정책은 [ADR-0012](../../adr/0012-terms-version-management.md).

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "status": "TERMS_AGREED",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "agreedAt": "2026-06-15T08:25:00Z"
  },
  "error": null
}
```

> `PENDING`의 **최초 동의**만 `TERMS_AGREED`로 전이한다. 이미 `TERMS_AGREED`인 사용자가 (네트워크 재시도 등으로) 다시 호출하면 상태·동의를 바꾸지 않고 멱등하게 현재 상태(`200`)를 반환한다 — 의도적 재동의가 아닌 중복 요청 방어다. 동의 후 **마케팅 수신 동의 변경은 `PATCH /users/me`(§9)** 로 처리하며, 약관 버전 변경에 따른 재동의 정책은 [ADR-0012](../../adr/0012-terms-version-management.md)(확인 필요).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `termsOfServiceAgreed`/`privacyPolicyAgreed` 누락(`@NotNull`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩 완료(`ACTIVE`)된 사용자의 약관 동의 재요청 |
| 422 | `AUTH_REQUIRED_AGREEMENT_MISSING` | 필수 약관(이용약관/개인정보처리방침) 미동의 |

---

### 3. POST `/api/v1/auth/email/verification-code` — 이메일 인증번호 발송

**온보딩을 완료한(ACTIVE) 사용자**가 이메일 주소로 인증번호를 발송받는다(#192에서 온보딩 단계 전용→정식(ACTIVE) 전용으로 반전 — 온보딩 토큰으로는 호출할 수 없고 정식 access 토큰이 필요하다). 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`다. 같은 사용자에 미검증 인증 시도가 남아 있으면 새 인증번호로 대체한다. 인증번호는 서버에 **해시로만 보관**하고 일정 시간(예: 5분 — 확인 필요) 후 만료한다. 재발송은 레이트리밋으로 보호한다.

메일은 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SMTP)로 **동기 발송**하며, **발송에 성공한 뒤에만** 인증번호 챌린지를 저장한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(메일 템플릿·다국어, 동기/비동기 정책은 확인 필요).

- **인증**: 필수 — **정식 access 토큰(`ACTIVE`, `ROLE_USER`)**. 온보딩 스코프(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`(#192).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "minh@example.com"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 이메일 형식(`@Email`). 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "mi***@example.com",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `email`은 마스킹해 반환한다. 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email` 누락/빈값/형식 위반(`@NotBlank`/`@Email`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 정식 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로 호출(정식(ACTIVE) 토큰 필요 — 온보딩 완료 후 호출하는 API, #192 반전) |
| 429 | `TOO_MANY_REQUESTS` | 재발송 레이트리밋 초과(확인 필요: 임계값) |
| 502 | `UPSTREAM_ERROR` | 메일 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 4. POST `/api/v1/auth/email/verify` — 이메일 인증번호 확인

발송된 인증번호를 검증한다. 성공하면 인증 챌린지를 **검증 완료(VERIFIED)** 로 표시한다. **접근은 온보딩을 완료한(ACTIVE) 사용자 전용**이다(#192에서 온보딩 단계 전용→정식(ACTIVE) 전용으로 반전). **다만 이번 범위(#192)에서는 verify 성공이 `User.email`을 바꾸지 않는다** — 접근만 ACTIVE로 제한하고, 실제 이메일 변경 반영은 후속 이슈다. 검증 시도는 횟수 상한으로 보호한다.

> **챌린지 부재(미발송·만료·이미 검증)**: 해당 사용자의 인증 챌린지(`email-verify:code:{userId}`)가 없으면 — 인증번호를 한 번도 요청하지 않았거나, TTL 만료, 이미 검증 완료로 소멸, 발송 실패(`502`)로 미저장 — 올릴 `attempts` 레코드 자체가 없으므로 **즉시 `422 AUTH_EMAIL_VERIFICATION_FAILED`** 로 거절하고 인증번호 (재)요청(§3)을 유도한다. `attempts`는 **챌린지가 존재하는데 코드가 불일치**할 때만 증가하며, 상한 초과 시 `429 TOO_MANY_REQUESTS`다.

- **인증**: 필수 — **정식 access 토큰(`ACTIVE`, `ROLE_USER`)**. 온보딩 스코프(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰은 `403 AUTH_ONBOARDING_REQUIRED`(#192).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "minh@example.com",
  "code": "482915"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 인증번호를 발송한 이메일과 일치해야 함 |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "mi***@example.com",
    "verified": true
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email`/`code` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 정식 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로 호출(정식(ACTIVE) 토큰 필요, #192 반전) |
| 422 | `AUTH_EMAIL_VERIFICATION_FAILED` | 코드 불일치, 또는 챌린지 부재(미발송·만료·이미 검증) — 부재 시 `attempts` 증가 없이 즉시 거절 |
| 429 | `TOO_MANY_REQUESTS` | 챌린지 존재 + 코드 불일치 누적으로 검증 시도 상한 초과(확인 필요: 임계값) |

---

### 4-1. POST `/api/v1/auth/phone/verification-code` — 연락처 인증번호 발송(임대인 전용)

**임대인 온보딩(US-1-10)** 또는 **정식 회원의 프로필 연락처 변경(US-1-5)** 시 입력한 연락처(휴대폰)로 SMS 인증번호를 발송한다(세입자 이메일 인증 §3과 대칭 — 임대인 트랙의 본인 확인, [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). **약관 동의(§2, `TERMS_AGREED`) 이상**이면 진행한다 — 온보딩(`TERMS_AGREED`)·프로필 변경(`ACTIVE`) 두 컨텍스트 모두 허용하고, 약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`로 거절하고 약관 동의(§2)를 먼저 유도한다. 같은 사용자에 미검증 인증 시도가 남아 있으면 새 인증번호로 대체한다. **인증번호 정책은 이메일 인증(§3·§4)과 동일하다** — 인증번호 6자리, 서버에 **해시로만 보관**하고 코드 TTL 5분 후 만료, 검증 마커(VERIFIED) TTL 30분(온보딩 토큰 만료), 검증 시도 상한 5회, 재발송 간격 60초로 보호한다.

SMS는 아웃바운드 포트 `VerificationSmsSender`(인프라 어댑터: SMS API — 구체 provider는 [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md))로 **동기 발송**하며, **발송에 성공한 뒤에만** 인증번호 챌린지를 저장한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(인증번호 생성·해시·검증은 서버가 보유해 이메일 인증과 대칭 — 어댑터는 발송만 담당. 동기/비동기 정책·문자 템플릿은 확인 필요).

- **인증**: 필수 — **(온보딩 단계, US-1-10)** 소셜 로그인에서 받은 온보딩 토큰(`onboardingCompleted=false`), **또는 (프로필 연락처 변경, US-1-5) 정식 토큰(`ACTIVE`, `ROLE_USER`)**. `/auth/phone/**`는 두 티어 모두 허용한다(보안 경로 확장 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 전화번호 형식. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `phoneNumber`는 마스킹해 반환한다. 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber` 누락/빈값/형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩/정식 토큰 누락/위조 / 만료 |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태의 요청(약관 동의 §2 선행 필요) |
| 429 | `TOO_MANY_REQUESTS` | 재발송 레이트리밋 초과(이메일 인증과 동일 — 재발송 간격 60초) |
| 502 | `UPSTREAM_ERROR` | SMS 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 4-2. POST `/api/v1/auth/phone/verify` — 연락처 인증번호 확인(임대인 전용)

발송된 인증번호를 검증한다. 성공하면 해당 사용자의 연락처를 **검증 완료(VERIFIED)** 로 표시하고, 이후 임대인 온보딩 제출(§5-2) 시 같은 번호를 통과시킨다. 검증 시도는 횟수 상한으로 보호한다(이메일 인증번호 확인 §4와 대칭).

> **챌린지 부재(미발송·만료·이미 검증)**: 해당 사용자의 인증 챌린지(`phone-verify:code:{userId}`)가 없으면 — 인증번호를 한 번도 요청하지 않았거나, TTL 만료, 이미 검증 완료로 소멸, 발송 실패(`502`)로 미저장 — 올릴 `attempts` 레코드 자체가 없으므로 **즉시 `422 AUTH_PHONE_VERIFICATION_FAILED`** 로 거절하고 인증번호 (재)요청(§4-1)을 유도한다. `attempts`는 **챌린지가 존재하는데 코드가 불일치**할 때만 증가하며, 상한 초과 시 `429 TOO_MANY_REQUESTS`다.

- **인증**: 필수 — **(온보딩 단계, US-1-10)** 온보딩 토큰(`onboardingCompleted=false`), **또는 (프로필 연락처 변경, US-1-5) 정식 토큰(`ACTIVE`, `ROLE_USER`)**. `/auth/phone/**`는 두 티어 모두 허용한다([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678",
  "code": "482915"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 인증번호를 발송한 연락처와 일치해야 함 |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "verified": true
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber`/`code` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치, 또는 챌린지 부재(미발송·만료·이미 검증) — 부재 시 `attempts` 증가 없이 즉시 거절 |
| 429 | `TOO_MANY_REQUESTS` | 챌린지 존재 + 코드 불일치 누적으로 검증 시도 상한 초과(이메일 인증과 동일 — 검증 시도 5회) |

---

### 5. POST `/api/v1/auth/onboarding` — 온보딩 제출(가입 완료·세입자)

`TERMS_AGREED` 세입자가 필수 프로필을 제출해 가입을 완료한다. **약관 동의(§2)가 선행**되어야 한다 — 약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`. **온보딩은 이름(`name`)과 이메일(`email`)을 받지 않는다** — 소셜 로그인 시점에 이미 `User`에 채워졌다(§1, #192). 성공 시 `ACTIVE`로 전이하고, 닉네임을 자동 배정하며 정식 access/refresh 토큰을 발급한다. 사용자 단위로 멱등 처리해 동시 요청은 한 건만 성공한다.

> 약관 동의·`termsVersion`은 §2(약관 동의)에서 이미 기록되므로 이 요청 본문에는 약관 필드를 담지 않는다. `nickname`은 서버가 형용사 풀·사물 풀의 active 단어에서 골라 `형용사 + 사물`로 조합하고 전역 유니크를 보장(충돌 시 재조합 재시도, 상한 초과 시 fallback 예: 숫자 접미사)해 자동 배정하므로 요청 본문에 담지 않는다(사용자 입력·수정 불가). **`name`·`email`은 온보딩 요청에 포함하지 않는다** — 소셜 로그인 시점에 `User`에 채워졌고(§1), 온보딩 이후 `name`은 `PATCH /users/me`(§9)로만 수정한다(`email` 수정은 #192 범위 밖의 후속 이슈로, 당분간 소셜 로그인 값으로 고정). 응답의 `countryName`·`countryFlag`는 서버가 `country`(코드)로 `countries`에서 resolve한 값이다(저장은 `country` 코드만). `countryFlag`는 **국기 이미지 URL**(flagcdn.com SVG)이다. `lang`은 **선택** 필드로, 보내면 그 값을 그대로 저장하고 보내지 않으면 저장하지 않으며(NULL) 표시 시 `en`으로 폴백한다 — `lang`을 모르는 기존 클라이언트는 `en`으로 보인다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)). `occupation`도 **선택** 필드다(#187에서 필수→선택 완화 — 매물 추천·탐색에서 직업 정보를 활용하지 않는다) — 보내면 enum 검증 후 저장하고, 보내지 않으면 저장하지 않는다(NULL). **필수→선택 완화는 하위호환**이다: `occupation`을 보내던 기존 클라이언트의 요청은 그대로 유효하고, 값을 보낸 경우의 enum 검증도 종전과 동일하다.

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 상태는 `TERMS_AGREED`여야 한다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "gender": "MALE",
  "birthDate": "1998-04-12",
  "country": "VN",
  "lang": "en",
  "occupation": "UNDERGRADUATE_STUDENT",
  "visaType": "STUDENTS_TRAINEES"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `gender` | string(enum) | 필수 | `MALE` \| `FEMALE` |
| `birthDate` | string(date) | 필수 | `YYYY-MM-DD`, 과거 날짜만 허용(미래 불가) |
| `country` | string | 필수 | 국적 ISO 3166-1 alpha-2 코드(예: `VN`). `countries`에 존재해야 함(없으면 `INVALID_INPUT`) |
| `lang` | string | 선택 | 표시 언어 ISO 639-1 소문자 코드. 지원 목록 `en` \| `ko` \| `ja` 중 하나여야 함(목록 밖 값은 `INVALID_INPUT`). **미전송이면 저장하지 않고(NULL) 표시 시 `en`으로 폴백**한다(`lang`을 보내지 않는 앱은 `en`으로 보인다) |
| `occupation` | string(enum) | 선택 | `UNDERGRADUATE_STUDENT` \| `GRADUATE_STUDENT` \| `EXCHANGE_STUDENT` \| `LANGUAGE_TEACHING` \| `MANUFACTURING_PRODUCTION` \| `BUSINESS_TRADE` \| `ETC` 중 하나여야 함(목록 밖 값은 `INVALID_INPUT` — 값을 보낸 경우의 enum 검증은 종전과 동일, 빈 문자열 `""`도 목록 밖 값이라 `INVALID_INPUT`). **미전송 또는 `null` 명시 전송이면(동일 취급) 저장하지 않고(NULL) 응답에서 생략**한다(#187) |
| `visaType` | string(enum) | 필수 | `SHORT_TERM_VISIT` \| `STUDENTS_TRAINEES` \| `NON_PROFESSIONAL_WORKERS` \| `WORKING_HOLIDAY_WORK_AND_VISIT` \| `OVERSEAS_KOREANS` \| `FAMILY_MARRIAGE_MIGRANTS` \| `PERMANENT_RESIDENTS` \| `PROFESSIONALS` \| `DIPLOMATIC_OFFICIAL_AND_OTHERS` \| `ETC` |

> 약관 동의(`termsOfServiceAgreed`·`privacyPolicyAgreed`·`marketingAgreed`)는 이 요청에 포함하지 않는다 — 앞선 `POST /auth/terms`(§2)에서 처리·기록된다.

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "linked": false,
    "user": {
      "id": 1024,
      "name": "Minh Nguyen",
      "nickname": "BraveOtter",
      "gender": "MALE",
      "birthDate": "1998-04-12",
      "country": "VN",
      "countryName": "Vietnam",
      "countryFlag": "https://flagcdn.com/vn.svg",
      "lang": "en",
      "occupation": "UNDERGRADUATE_STUDENT",
      "email": "minh@example.com",
      "visaType": "STUDENTS_TRAINEES",
      "userType": "TENANT",
      "status": "ACTIVE",
      "marketingAgreed": false,
      "createdAt": "2026-06-15T08:30:00Z"
    },
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

> 응답의 `occupation`은 **미설정(NULL)이면 필드 자체가 생략**된다(응답 뷰가 null 필드를 직렬화하지 않음 — 프로필 조회 §8도 동일). 따라서 REST Docs의 응답 필드 `occupation`은 **optional**로 선언한다(`lang`과 동일 — #187).
>
> **`linked`는 세입자 응답에서 항상 `false`다 — 버그가 아니다.** 임대인 온보딩(§5-2)과 응답 타입을 공유해 필드가 함께 나갈 뿐이고, 계정 병합의 매칭 키는 SMS로 인증한 휴대폰 번호 단독인데 **세입자는 온보딩에서 번호를 수집하지 않아**(`phone_number`가 NULL) 대조할 열쇠 자체가 없다. 즉 세입자 트랙에는 병합 분기에 닿을 경로가 존재하지 않는다(§개요 웹 임대인 트랙의 "세입자 → 임대인 전환 불가"와 같은 구조적 이유다).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 필드 누락/형식·enum·날짜 위반(`gender`/`visaType` 불일치, **`occupation`은 선택이라 누락은 에러가 아니고 값을 보낸 경우 enum 목록 밖일 때만 해당**(#187), `birthDate` 형식·미래, `country` 빈값·형식, **`lang`이 지원 목록(`en`/`ko`/`ja`) 밖의 코드** 등) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 `ACTIVE`인 사용자의 온보딩 재요청(동시 요청 포함) |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 온보딩 제출(약관 동의 §2 선행 필요) |

---

### 5-1. POST `/api/v1/auth/business/verify` — 사업자등록번호 검증(임대인 전용)

온보딩을 마친(`ACTIVE`) **임대인 전용**으로, 입력한 사업자등록번호를 외부 사업자등록정보 검증 API(국세청 사업자등록정보 기반, 구체 provider는 [ADR-0033](../../adr/0033-business-registry-verification.md))로 진위·영업 상태까지 확인하는 **무상태(stateless) 검증 API**다. 온보딩(§5-2)과 분리되어 있으며, 정식 access 토큰(`ROLE_USER`)을 가진 임대인이 필요할 때 **직접** 호출한다. 형식(숫자 10자리) 위반은 외부 호출 전에 `400 INVALID_INPUT`으로 거른다. **정상(계속) 사업자면** `verified:true`를 응답 body로 돌려주고, 미등록·휴업·폐업 번호는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`로 거절한다.

> **매물 등록은 이 API를 호출하지 않는다.** 매물 등록(`POST /api/v2/listings` — [03-listings-favorites](03-listings-favorites.md))은 요청 본문의 사업자등록번호를 **형식(숫자 10자리)만 검증하고 매물 문서에 원문으로 저장**하며, **진위·영업 상태는 관리자가 승인 심사(`PENDING` → `PUBLISHED`/`REJECTED`, 후속)에서 수동으로 확인**한다. 등록 흐름에서 이 엔드포인트를 자동 호출하는 연동은 없고, 엔드포인트 자체는 임대인이 스스로 확인하는 경로로 유지된다.

**무상태**: 검증 결과를 서버에 저장하지 않는다 — Redis 마커·`user.businessRegistrationNumberHash` 컬럼 어느 쪽에도 쓰지 않으며, 결과는 응답(HTTP body)에만 담긴다. 온보딩 제출에서 이 결과를 대조하는 게이트도 없고, 매물 등록에서 이 결과를 요구하는 게이트도 없다. 검증은 아웃바운드 포트 `BusinessRegistryVerifier`(인프라 어댑터: 사업자등록정보 검증 API — 국세청 사업자등록정보 진위·상태 기반)로 **동기 호출**한다. 검증 API 장애·타임아웃·5xx 등 연동 실패는 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(공통 코드 — [error-response-guide](../error-response-guide.md) §3). 사업자등록번호 원문은 응답·로그에 노출하지 않고 마스킹한다.

- **인증**: 필수 — **정식 access 토큰(`ACTIVE`, `ROLE_USER`)**. 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) ACTIVE 사용자면 `403 FORBIDDEN`이다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "businessRegistrationNumber": "1234567890"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `businessRegistrationNumber` | string | 필수 | 숫자 10자리 또는 하이픈 형식(예 `123-45-67890`) — 어댑터가 하이픈을 제거해 조회·대조. 빈 문자열·형식 위반 불가(`INVALID_INPUT` — 외부 호출 전 거름) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "businessRegistrationNumber": "****567890",
    "verified": true
  },
  "error": null
}
```

> `businessRegistrationNumber`는 마스킹해 반환한다(예: `****567890` — 마스킹 형식 확인 필요). **검증 결과는 서버에 저장하지 않는다**(무상태) — Redis 마커·`user.businessRegistrationNumberHash` 어느 쪽에도 쓰지 않으며 응답 body(`verified:true`)로만 회신한다. 따라서 **이 응답을 매물 등록(`POST /api/v2/listings`)의 선행 조건으로 삼지 않는다** — 등록 API는 사업자등록번호를 형식 검증 후 매물 문서에 저장할 뿐이고 진위는 관리자 승인 심사에서 수동으로 확인한다. 검증 서비스가 회신한 상호·대표자 등 표시용 정보의 응답 노출 여부는 확인 필요.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `businessRegistrationNumber` 누락/빈값/형식(숫자 10자리) 위반 — 외부 호출 전 거름 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 정식 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출(정식 토큰 필요 — 온보딩 완료 후 호출하는 API) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) ACTIVE 사용자의 요청(임대인 전용) |
| 422 | `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` | 검증 서비스 조회 결과 미등록이거나 휴업·폐업 상태(진위·상태 검증 실패) |
| 429 | `TOO_MANY_REQUESTS` | 검증 시도 레이트리밋 초과(확인 필요: 시도 상한·간격 임계값) |
| 502 | `UPSTREAM_ERROR` | 사업자등록정보 검증 API 장애·타임아웃·5xx. 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 5-2. POST `/api/v1/auth/landlord/onboarding` — 임대인 온보딩 제출(임대인 전용·가입 완료)

`TERMS_AGREED` 사용자가 임대인 필수 프로필을 제출해 가입을 완료한다(세입자 온보딩 §5와 분리된 **임대인 전용 엔드포인트**). **약관 동의(§2)·연락처 인증(§4-1·§4-2)이 선행**되어야 한다 — **임대인 온보딩은 약관 동의 + 연락처(SMS) 인증만으로 완료**되며, 사업자등록번호는 수집·검증하지 않는다(온보딩 후 **매물 등록(`POST /api/v2/listings`) 요청 본문**으로 받아 형식 검증만 하고 매물 문서에 저장하며, 진위는 관리자 승인 심사에서 수동 확인 — §5-1 검증 API는 자동 호출하지 않는다). 성공 시 `ACTIVE`로 전이하고 **`userType`을 `LANDLORD`로 확정**하며, 닉네임을 자동 배정하고 정식 access/refresh 토큰을 발급한다(상태 전이 액션이므로 `200`). 사용자 단위로 멱등 처리해 동시 요청은 한 건만 성공한다. 임대인은 성별·직업·비자정보를 온보딩에서 수집하지 않으며(**이름 `name`과 이메일 `email`은 세입자와 동일하게 소셜 로그인 시 provider 값으로 이미 확정돼 있어 온보딩에서 재입력·재수집하지 않는다** — #192; 생년월일 `birthDate`은 세입자와 동일하게 필수 수집한다 — [#131](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/131)). 이름은 단일 `name`이다(#192에서 세입자의 `firstName`/`lastName`도 단일 `name`으로 통합돼 두 역할의 이름 모델·수집 시점이 완전히 통일됐다 — 이후 수정은 `PATCH /users/me` §9). **국적 `country`와 표시 언어 `lang`은 클라이언트가 보내지 않고 서버가 `country="KR"`·`lang="ko"`로 고정 부여**한다 — 임대인은 한국인 사업자를 전제하므로 국적을 묻지 않고, 한국어로만 서비스를 본다(변경 불가). 이는 [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 국적 미수집" 결정을 개정한 것으로, 요청 본문은 `{ phoneNumber, birthDate }` 두 필드이고 응답에는 소셜 로그인 시 확정된 `name`·`email`과 서버 고정 국적·국기·언어가 더해진다.

> **검증 게이트 우선순위**: 약관 미동의(`PENDING`) → `422 AUTH_TERMS_AGREEMENT_REQUIRED`(이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`) → 제출 `phoneNumber` 미검증·불일치 → `422 AUTH_PHONE_NOT_VERIFIED` 순으로 판정한다(약관 → 연락처, 사업자번호 게이트 없음). 약관 동의·`termsVersion`은 §2에서 이미 기록되므로 이 요청 본문에 약관 필드를 담지 않는다. `phoneNumber`는 §4-1·§4-2로 검증 완료된 값과 일치해야 한다. `nickname`은 서버가 자동 배정하므로 요청 본문에 담지 않는다(사용자 입력·수정 불가).

**웹 계정 병합(US-1-15)** — 위 게이트를 통과한 뒤 **병합 분기**가 하나 붙는다. 앱 → 웹 방향 연동은 소셜 로그인 시점에 서버가 휴대폰 번호를 몰라 판정할 수 없고(소셜은 `name`·`email`만 준다) 그때는 이미 임시 `users` 행이 만들어진 뒤이므로, **판정 지점이 로그인이 아니라 이 온보딩 제출이고 동작은 연결이 아니라 병합**이다(§개요 웹 임대인 트랙). 서버는 인증된 정규화 번호로 **자기 자신이 아닌 `ACTIVE`·`LANDLORD` `users` 행**을 `SELECT … FOR UPDATE`로 조회한다.

- **없으면 기존 동작 그대로** 자기 계정을 `ACTIVE`로 전이한다(US-1-9 무변경 — 앱만 쓰는 임대인의 정상 경로). 응답은 `linked=false`.
- **있으면** 그 계정이 웹에서 먼저 가입한 같은 사람이므로 병합한다 — 앱 로그인의 열쇠인 `social_accounts.user_id`를 대상 계정으로 옮기고, 방금 만들어진 임시 `users` 행을 **하드 삭제**한 뒤, **대상 계정 기준으로 토큰을 발급하고 응답의 `user`도 대상 계정 값으로 채우며 `linked=true`로 그 사실을 알린다**(요청 토큰의 `userId`가 아니다). 이후 앱 소셜 로그인은 항상 대상 계정으로 귀결돼, 웹에서 등록한 매물의 예약([04-booking-inquiry-chat](04-booking-inquiry-chat.md))이 앱에서 그대로 조회된다. 두 DB 쓰기(매핑 이전·행 삭제)는 한 트랜잭션이며 실패 시 함께 롤백한다 — `social_accounts`가 어느 쪽에도 붙지 않는 상태가 되면 앱 로그인이 영구히 깨진다. **토큰 발급은 그 트랜잭션에 들어오지 않는다**(아래 알려진 제약).

> **병합 여부는 응답 필드로 명시한다 — `user.id` 비교로 추론하게 두지 않는다.** 종전에는 클라이언트가 *자기가 보낸 토큰에 박힌 `userId`* 를 꺼내 응답 `data.user.id`와 대조해야만 병합을 알 수 있었는데, 그 비교를 빠뜨려도 화면은 정상으로 보이고 **다음 API 호출에서야** 사라진 계정의 토큰으로 401을 맞는다. 병합은 서버가 확실히 아는 사실이므로 서버가 말한다. 필드명은 웹 가입(§1-3)의 `linked`와 **같은 단어**다 — 방향(연결/병합)과 구현은 다르지만 클라이언트에게는 둘 다 "계정이 하나로 합쳐졌다"는 한 가지 사실이고, 같은 개념에 두 단어를 쓸 이유가 없다. **응답 필드 추가는 하위 호환이라 `/api/v1`을 유지한다**(버전 정책은 [api-design-guide §2-1](../api-design-guide.md)).

> 병합해도 옮길 것은 `social_accounts` 행뿐이다 — 임시 계정은 방금 소셜 로그인으로 만들어져 매물·예약·채팅이 하나도 없다. 옮기는 행 수는 단언하지 않는다(UPDATE가 N행이어도 안전하다). 대상 계정에 `social_accounts`가 여러 행이 되는 것은 **정상**이다(한 사람이 Google·Apple로 각각 앱 로그인해 차례로 병합한 경우). 인증 마커가 없으면 `422 AUTH_PHONE_NOT_VERIFIED`가 선행해 **병합도 하지 않는다** — 번호만 알면 남의 웹 계정을 흡수하는 경로가 생기지 않아야 한다. 조회 조건의 `status='ACTIVE' AND user_type='LANDLORD'`는 지금은 중복이지만(번호가 채워진 계정은 사실상 `ACTIVE` 임대인뿐이다) **명시적으로 건다** — 암묵적 불변식에 기대지 않는다. 동시에 도착한 웹 가입과 앱 온보딩이 계정을 갈라 놓는 것은 `users.phone_number` UNIQUE가 막는다 — 둘째 트랜잭션이 DB 제약으로 실패하며, 그 실패는 **`409 RESOURCE_CONFLICT`로 번역해 내려간다**(500이 아니다 — 재시도하면 성공하는 상황이라 클라이언트가 그 신호를 받아야 한다). 재시도하면 상대가 만든 계정을 발견해 정상 병합된다. 번역 대상은 **문서화된 UNIQUE 제약의 중복 위반뿐**이며 `NOT NULL` 위반 등 다른 제약 위반은 종전대로 `500`이다([error-response-guide §4](../error-response-guide.md)).

> **알려진 제약**
> — **롤백돼도 refresh 해시는 Redis에 남는다.** 한 트랜잭션 보장은 MySQL 쓰기에만 걸린다. 토큰 발급은 트랜잭션 안에서 일어나지만 refresh 해시는 Redis에 남고 롤백되지 않으므로, 위 `409 RESOURCE_CONFLICT`(커밋 시점 UNIQUE 위반)처럼 늦게 실패하는 요청은 **쓰이지 않을 해시 하나를 14일 TTL로 남긴다**. 원문은 응답으로 나가지 않아 세션을 열 수 없고(악용 불가) 항목은 스스로 만료된다 — 그때까지 `refresh:user:{id}` 인덱스가 실제 세션보다 많아 보이는 것이 유일한 영향이다. §1-3 웹 회원가입도 같다.
> — **임시 계정의 진단 기록은 삭제하지 않는다.** 병합은 `users` 행만 지우므로 사라진 계정을 가리키는 진단 문서가 남을 수 있다(조회 주체가 없어 실질 영향은 없다). 현재 탈퇴조차 진단을 지우지 않으므로, 병합이 탈퇴보다 공격적으로 지우는 비대칭을 만들지 않는다.
> — **앱·웹 양쪽 모두 완주한 계정은 자동 병합하지 않는다.** 같은 번호의 `ACTIVE` 계정이 양쪽에 각각 있으면 온보딩 경로를 다시 타지 않아 트리거가 없고, 양쪽이 매물·예약을 보유했을 수 있어 데이터 이관 판단이 필요하다 — 운영 수동 처리 대상이다(화면·코드를 두지 않는다).
> — **번호 정규화 백필이 없다.** 하이픈으로 저장된 기존 임대인 번호는 병합 조회에서 누락될 수 있다.
> — 매물 사진의 pending 업로드 S3 키가 `landlordId`를 품으므로, 병합으로 id가 바뀌면 **진행 중이던 pending 업로드는 고아가 된다**. 병합은 앱 가입 직후에만 일어나 그 시점에 진행 중 업로드가 없으므로 실무상 무해하다.

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 상태는 `TERMS_AGREED`여야 한다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678",
  "birthDate": "1998-04-12"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 전화번호 형식. **§4-1·§4-2로 사전 검증된 값과 일치**해야 함(미검증·불일치 `AUTH_PHONE_NOT_VERIFIED` 422) |
| `birthDate` | string(date) | 필수 | `YYYY-MM-DD`, 과거 날짜만 허용(미래 불가) — 세입자 온보딩(§5)과 동일 규칙 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "linked": false,
    "user": {
      "id": 2048,
      "name": "Kim Minsu",
      "nickname": "BraveOtter",
      "birthDate": "1998-04-12",
      "phoneNumber": "010-****-5678",
      "country": "KR",
      "countryName": "South Korea",
      "countryFlag": "https://flagcdn.com/kr.svg",
      "lang": "ko",
      "email": "minsu@example.com",
      "userType": "LANDLORD",
      "status": "ACTIVE",
      "marketingAgreed": false,
      "createdAt": "2026-06-15T08:30:00Z"
    },
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `linked` | boolean | **계정 병합 여부**(US-1-15). `true`면 같은 번호의 기존 웹 임대인 계정과 합쳐진 것이라 아래 `user`와 토큰이 **요청 토큰의 계정이 아니라 살아남은 웹 계정 기준**이고 `user.id`가 요청 토큰의 `userId`와 다르다. 병합 대상이 없던 일반 온보딩은 `false`이며, 세입자 온보딩(§5)은 병합 분기가 없어 언제나 `false`다 |
| `user` | object | 완성된 회원 프로필. `linked=true`면 **폼에 적은 값이 아니라 살아남은 계정의 값**이다(생년월일도 대상 계정 값 — 병합은 대상 프로필을 한 칼럼도 덮어쓰지 않는다) |
| `accessToken`·`refreshToken`·`tokenType`·`expiresIn` | string·string·string·number | 정식 토큰(`ROLE_USER`)과 만료까지의 초. **`linked=true`면 이 토큰은 요청에 쓴 계정이 아닌 다른 계정의 것**이므로 클라이언트는 반드시 저장 중인 토큰을 이 값으로 교체해야 한다 — 요청에 쓴 임시 계정 행은 이미 삭제됐다 |

> **클라이언트가 `linked=true`에서 할 일은 둘이다** — ① 저장된 access·refresh 토큰을 응답 값으로 교체하고 ② 프로필 화면의 이름·이메일을 응답의 `user` 값으로 갱신하며 "기존 웹 계정과 연결되었습니다" 류의 안내를 띄운다(방금 입력한 값이 그대로 반영되지 않는 것이 의도된 동작임을 사용자가 알아야 한다).
>
> 임대인 응답은 세입자와 달리 `gender`·`occupation`·`visaType`을 포함하지 않는다. **`email`은 세입자와 동일하게 소셜 로그인 시 provider 값으로 확정돼 임대인도 보유하므로 응답에 포함한다**([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집" 결정을 개정(#192) — 이메일은 인증 대상 아닌 미검증 연락처). **`birthDate`는 임대인도 온보딩에서 수집하므로 응답에 포함한다.** **`country`·`countryName`·`countryFlag`·`lang`은 서버가 고정 부여한 값(`KR`·`ko`)이라 임대인 응답에도 포함한다**(요청 본문에는 없다 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) 개정(#141)). `phoneNumber`는 마스킹해 반환한다(예: `010-****-5678` — 프로필 조회 §8은 본인이라 평문). `marketingAgreed`는 포함한다(약관 동의 시 확정). 사업자등록번호는 온보딩에서 수집하지 않으므로 응답에도 포함하지 않는다(온보딩 후 매물 등록에서 입력·저장 — §5-1은 임대인이 직접 부르는 별도 무상태 검증). 임대인 프로필 조회·수정은 `GET`(§8)·`PATCH`(§9) `/users/me`에서 `userType`에 따라 분기해 다룬다. **웹 계정 병합(US-1-15)이 일어난 경우 응답의 `user`와 토큰은 모두 대상(웹) 계정 기준**이므로 `user.id`가 요청 토큰의 `userId`와 다르며, 그 사실은 `linked=true`로 응답에 명시된다 — 클라이언트는 이 응답의 토큰으로 교체해야 하며, 그 뒤 앱 소셜 로그인은 항상 같은 계정으로 귀결된다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber` 누락·빈값·형식(전화번호) 위반, `birthDate` 누락·형식·미래 날짜 위반(`errors[]`로 위반 필드 반환) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 `ACTIVE`인 사용자의 온보딩 재요청(동시 요청 포함 — 한 요청만 성공) |
| 409 | `RESOURCE_CONFLICT` | 같은 번호의 웹 회원가입(§1-3)이 거의 동시에 계정을 확정해 `uq_users_phone_number`(V23)에 걸림 — **병합 대상이 아직 없을 때**의 경합이라 `SELECT … FOR UPDATE`로는 막을 수 없다(없는 행은 잠글 수 없다). 트랜잭션 전체가 롤백돼 계정이 갈라지지 않으며, **그대로 다시 제출하면** 상대가 만든 계정을 발견해 병합으로 수렴한다 |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 온보딩 제출(약관 동의 §2 선행 — 우선 판정) |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | 제출 `phoneNumber`가 미검증이거나 검증한 번호와 불일치(연락처 인증 §4-1·§4-2 선행) |

---

### 6. POST `/api/v1/auth/reissue` — 토큰 재발급

유효한 refresh 토큰으로 새 access 토큰을 재발급한다. 항상 회전한다 — 새 refresh 토큰도 함께 발급하고 제출한 refresh는 무효화(ROTATED)한다([ADR-0006](../../adr/0006-refresh-token-store-redis.md)). 폐기된 토큰을 다시 제출하는 재사용이 탐지되면 해당 사용자의 모든 refresh 토큰을 무효화한다.

**refresh는 쿠키(`refreshToken`) 우선 · 요청 본문 fallback으로 읽는다**([ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md)). 웹(브라우저)은 HttpOnly 쿠키가 자동 첨부되므로 **본문 없이** 호출하고, 앱은 종전대로 본문에 담아 보낸다. **응답도 요청이 온 채널을 따른다** — 쿠키로 왔으면 회전된 refresh를 다시 `Set-Cookie`로 내리고, 본문으로 왔으면 종전대로 응답 본문에 담는다. **앱 동작은 전혀 바뀌지 않으므로 v2를 신설하지 않고 v1을 유지**한다(회전·재사용 탐지 규칙도 채널과 무관하게 동일하다).

- **인증**: 불필요(헤더 access 토큰 없이 쿠키 또는 본문의 refresh 토큰으로 처리). 만료된 access 토큰 보유 클라이언트가 이 엔드포인트로 갱신한다.
- Path/Query 파라미터: 없음.

#### Request Body — 선택(쿠키로 보내면 생략)

```json
{
  "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `refreshToken` | string | 선택 | 서버가 발급·보관(해시) 중인 **불투명(opaque) refresh 토큰**. **쿠키 `refreshToken`이 있으면 쿠키 값을 쓰고 본문은 보지 않는다.** 쿠키·본문 어느 쪽에도 값이 없거나 공백이면 `400 INVALID_INPUT`(`errors[].field=refreshToken`) |

> 본문 자체를 생략할 수 있다(웹의 정상 경로 — 쿠키만 보낸다). **종전에는 본문 없는 요청이 `MALFORMED_REQUEST`였으나, 본문이 선택이 되면서 값을 어디서도 찾지 못한 경우는 `INVALID_INPUT`으로 바뀐다.** 반면 **본문을 보냈는데 JSON으로 해석할 수 없으면 종전대로 `MALFORMED_REQUEST`** 다 — 깨진 본문은 여전히 요청 자체가 깨진 것이다.

#### 성공 Response — 본문으로 제출(앱) (200 OK)

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "eyJ...new-access",
    "refreshToken": "rt_3b1e7c5a2f9d04e8b6c1a07f5d2e93b4c8a16f0d",
    "expiresIn": 3600
  },
  "error": null
}
```

#### 성공 Response — 쿠키로 제출(웹) (200 OK)

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "eyJ...new-access",
    "refreshToken": null,
    "expiresIn": 3600
  },
  "error": null
}
```

```http
Set-Cookie: refreshToken=rt_3b1e7c5a2f9d04e8b6c1a07f5d2e93b4c8a16f0d; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600
```

> reissue는 항상 회전한다: 제출한 refresh는 무효화(ROTATED)하고 새 access·refresh를 함께 발급한다([ADR-0006](../../adr/0006-refresh-token-store-redis.md)). **쿠키 경로에서는 응답 본문의 `refreshToken`을 비우고(`null`) 회전된 값을 `Set-Cookie`로만 내린다** — 스크립트가 읽을 수 있는 곳에 refresh를 두지 않는다. 쿠키 속성은 §개요 웹 임대인 트랙의 표와 동일하며, 회전할 때마다 `Max-Age`가 다시 14일로 갱신된다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 쿠키·본문 어느 쪽에도 `refreshToken`이 없거나 공백(`errors[].field=refreshToken`) |
| 400 | `MALFORMED_REQUEST` | 보낸 본문을 JSON으로 해석할 수 없음/타입 불일치. **본문을 아예 보내지 않는 것은 오류가 아니다**(쿠키 경로) |
| 401 | `AUTH_INVALID_REFRESH_TOKEN` | refresh 토큰 만료/위조/무효화/재사용 탐지 |

---

### 7. POST `/api/v1/auth/logout` — 로그아웃

전달된 refresh 토큰을 서버에서 무효화해 더는 재발급에 쓰지 못하게 한다. 이미 무효화된 토큰이면 멱등하게 `204`로 처리한다.

**refresh를 읽는 규칙은 §6과 같다 — 쿠키(`refreshToken`) 우선 · 요청 본문 fallback**이며 본문은 선택이다([ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md)). 요청이 **쿠키로 왔으면 서버 무효화와 함께 `Max-Age=0` 삭제 쿠키를 내려** 브라우저에서도 지운다 — 서버에서만 지우면 브라우저에 죽은 쿠키가 남아 다음 재발급이 `401`로 실패한다. 본문으로 온 요청(앱)에는 쿠키를 내리지 않는다. **앱 동작은 전혀 바뀌지 않는다.**

- **인증**: 필수.
- Path/Query 파라미터: 없음.

#### Request Body — 선택(쿠키로 보내면 생략)

```json
{
  "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `refreshToken` | string | 선택 | 무효화할 refresh 토큰. **쿠키 `refreshToken`이 있으면 쿠키 값을 쓰고 본문은 보지 않는다.** 쿠키·본문 어느 쪽에도 값이 없거나 공백이면 `400 INVALID_INPUT`(`errors[].field=refreshToken`) |

> §6과 동일하게, **본문 없는 요청은 오류가 아니고**(쿠키 경로) 값을 어디서도 찾지 못한 경우가 `INVALID_INPUT`이다(종전 `MALFORMED_REQUEST`에서 변경). **보낸 본문이 깨진 JSON이면 종전대로 `MALFORMED_REQUEST`** 다.

#### 성공 Response — 204 No Content

본문 없음. 이미 무효화된 토큰으로 재호출해도 멱등하게 `204`를 반환한다. 쿠키로 온 요청에는 삭제 쿠키를 함께 내린다(값을 비우고 `Max-Age=0` — 나머지 속성은 발급 때와 같아야 브라우저가 같은 쿠키로 인식해 지운다).

```http
Set-Cookie: refreshToken=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=0
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 쿠키·본문 어느 쪽에도 `refreshToken`이 없거나 공백(`errors[].field=refreshToken`) |
| 400 | `MALFORMED_REQUEST` | 보낸 본문을 JSON으로 해석할 수 없음/타입 불일치. **본문을 아예 보내지 않는 것은 오류가 아니다**(쿠키 경로) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | access 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근(logout은 `ROLE_USER` 필요) |

---

### 8. GET `/api/v1/users/me` — 내 프로필 조회

인증된 본인의 프로필을 조회한다. **응답 필드는 `userType`에 따라 갈린다** — 세입자(`TENANT`)는 이름(단일 `name`)·국적·직업·비자정보 등 외국인 프로필을, 임대인(`LANDLORD`)은 단일 `name`·연락처 중심 프로필을 받는다(#192에서 세입자·임대인 모두 단일 `name`으로 통일).

- **인증**: 필수(ACTIVE 사용자). PENDING 토큰 접근은 `403 AUTH_ONBOARDING_REQUIRED`.
- Path/Query 파라미터: 없음.

#### 성공 Response — 세입자(TENANT) (200 OK)

```json
{
  "success": true,
  "data": {
    "id": 1024,
    "userType": "TENANT",
    "name": "Minh Nguyen",
    "nickname": "BraveOtter",
    "gender": "MALE",
    "birthDate": "1998-04-12",
    "country": "VN",
    "countryName": "Vietnam",
    "countryFlag": "https://flagcdn.com/vn.svg",
    "lang": "en",
    "occupation": "UNDERGRADUATE_STUDENT",
    "email": "minh@example.com",
    "visaType": "STUDENTS_TRAINEES",
    "status": "ACTIVE",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 성공 Response — 임대인(LANDLORD) (200 OK)

```json
{
  "success": true,
  "data": {
    "id": 2048,
    "userType": "LANDLORD",
    "name": "Kim Minsu",
    "nickname": "BraveOtter",
    "birthDate": "1998-04-12",
    "phoneNumber": "010-1234-5678",
    "country": "KR",
    "countryName": "South Korea",
    "countryFlag": "https://flagcdn.com/kr.svg",
    "lang": "ko",
    "email": "minsu@example.com",
    "status": "ACTIVE",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

> 본인 프로필이므로 `phoneNumber`는 평문으로 반환한다(로그·타 사용자 노출 시에만 마스킹). **세입자·임대인 모두 이름은 단일 `name`으로 반환한다**(#192 — 세입자의 `firstName`/`lastName`은 단일 `name`으로 통합). 세입자 응답의 `occupation`은 온보딩에서 **선택**(#187)이라 **미설정이면 필드가 생략**된다(온보딩 응답 §5와 동일 — REST Docs 응답 필드는 optional로 선언).
> 임대인 응답은 세입자 전용 필드(`gender`·`occupation`·`visaType`)를 포함하지 않는다. **`email`은 세입자와 동일하게 소셜 로그인 시 provider 값으로 확정돼 임대인도 보유하므로 응답에 포함한다**([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집" 결정을 개정(#192) — 이메일은 인증 대상 아닌 미검증 연락처). **`birthDate`는 임대인도 온보딩에서 수집하므로 응답에 포함한다.** **`country`·`countryName`·`countryFlag`·`lang`은 임대인 응답에도 포함한다** — 온보딩에서 서버가 `KR`·`ko`로 고정 부여하기 때문이며(클라이언트가 보내지 않는다), 이는 [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 "임대인 국적 미수집" 결정을 개정한 것이다. **`businessRegistrationNumber`는 온보딩에서 수집하지 않으므로(온보딩 후 매물 등록에서 입력해 매물 문서에만 저장 · §5-1 검증은 결과 미저장) 응답에 포함하지 않는다.**

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근 |
| 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |

---

### 9. PATCH `/api/v1/users/me` — 내 프로필 부분 수정

본인 프로필을 부분 수정한다. 전송한 필드만 변경하고, 미전송 필드는 유지한다(미전송 ≠ 값 비움 — 현재 수정 대상 필드는 비움 불가). **수정 가능 필드는 `userType`에 따라 갈린다** — 세입자(`TENANT`)는 이름(단일 `name`)·국적·표시 언어(`lang`)·직업·비자정보·마케팅 동의를, 임대인(`LANDLORD`)은 `name`·`phoneNumber`·`marketingAgreed`만 수정한다(**임대인은 `lang`을 바꿀 수 없다** — `ko` 고정, [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) 개정(#141)).

- **인증**: 필수(ACTIVE 사용자). PENDING 토큰 접근은 `403 AUTH_ONBOARDING_REQUIRED`.
- Path/Query 파라미터: 없음.

#### Request Body — 세입자(TENANT) (모든 필드 선택)

```json
{
  "country": "KR",
  "lang": "ko",
  "occupation": "BUSINESS_TRADE",
  "visaType": "SHORT_TERM_VISIT",
  "marketingAgreed": true
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `name` | string | 선택 | 성·이름을 합친 단일 이름. 빈 문자열 불가 |
| `gender` | string(enum) | 선택 | `MALE` \| `FEMALE` |
| `birthDate` | string(date) | 선택 | `YYYY-MM-DD`, 과거 날짜만 |
| `country` | string | 선택 | 국적 ISO 코드(예: `KR`). `countries`에 존재해야 함 |
| `lang` | string | 선택 | 표시 언어 ISO 639-1 소문자 코드. 빈 문자열 불가. 지원 목록 `en` \| `ko` \| `ja` 중 하나여야 함(목록 밖 값은 `INVALID_INPUT`) |
| `occupation` | string(enum) | 선택 | 직업 enum(위 목록과 동일) |
| `visaType` | string(enum) | 선택 | 비자정보 enum(위 목록과 동일) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의 |

#### Request Body — 임대인(LANDLORD) (모든 필드 선택)

```json
{
  "name": "Kim Minsu",
  "phoneNumber": "010-1234-5678",
  "marketingAgreed": true
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `name` | string | 선택 | 성·이름을 합친 단일 이름. 빈 문자열 불가. 저장은 단일 `name`(§핵심 개념 표 참조) |
| `phoneNumber` | string | 선택 | 전화번호 형식. 빈 문자열 불가. **변경 시 SMS 재인증(§4-1·§4-2) 필요** — 새 번호가 VERIFIED일 때만 반영(미인증·불일치 `AUTH_PHONE_NOT_VERIFIED` 422) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의 |

> **세입자 전용 — `country`·`lang` 독립**: `country`와 `lang`은 서로 독립이다 — `country`만 바꿔도 `lang`은 그대로 유지되고(국적을 바꿔도 표시 언어는 따라 바뀌지 않는다), `lang`만 보내면 `country`는 그대로 둔다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)).
> 필수 약관 동의(`termsOfServiceAgreed`/`privacyPolicyAgreed`)는 이 엔드포인트로 철회할 수 없다(탈퇴 경로로만 처리). (확인 필요: 동의 철회 정책)
> `nickname`은 시스템 배정값이라 수정 대상이 아니다(세입자·임대인 공통 불변). **세입자·임대인 모두** `email` 변경은 **#192 범위 밖(후속 이슈)** 이라 이 엔드포인트로 수정하지 않는다 — 당분간 소셜 로그인 provider 값으로 고정한다(임대인도 소셜 로그인 시 provider email을 보유하며, 수정은 세입자와 동일하게 후속 이슈다). 정식(ACTIVE) 사용자의 이메일 인증 API(§3·§4)는 접근만 ACTIVE로 열어 두었고, 실제 이메일 변경 반영은 후속 이슈다.
> **임대인 전용**: `userType`은 온보딩으로 확정된 뒤 불변이다. `birthDate`는 온보딩에서 수집·확정하며 이 경로의 수정 대상이 아니다(임대인 조회 전용 — [#131](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/131)). `businessRegistrationNumber`는 온보딩·프로필에서 수집·저장하지 않으므로 이 경로의 수정 대상이 아니다(필요 시 별도 검증 API §5-1로 무상태 검증). **`phoneNumber` 변경은 SMS 재인증(§4-1·§4-2)이 필요하다** — 새 번호를 재인증(VERIFIED)한 뒤에만 반영하며, 미인증·불일치는 `422 AUTH_PHONE_NOT_VERIFIED`다(온보딩 시 연락처 인증과 동일한 발송·확인을 정식 토큰 컨텍스트에서 재사용 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). **클라이언트 계약**: 앱은 연락처 변경 시 **PATCH 이전에 새 번호 인증(§4-1·§4-2)을 먼저 수행**한다(정상 흐름). `422 AUTH_PHONE_NOT_VERIFIED`는 happy path가 아니라 **미인증·마커 TTL 만료·불일치 제출에 대한 서버 가드**다.

#### 성공 Response — 200 OK

수정된 프로필 전체를 `GET /users/me`와 동일 스키마의 공통 래퍼로 반환한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | **`gender`/`visaType`/`occupation`이 허용 목록 밖**, `birthDate`가 `YYYY-MM-DD` 형식이 아니거나 미래 날짜, `country` 미존재(`countries`에 없음), **`lang`이 지원 목록(`en`/`ko`/`ja`) 밖의 코드·빈 문자열** 등 값 검증 위반. 위반 필드는 `errors[]`로 반환한다 |
| 400 | `MALFORMED_REQUEST` | 요청 본문을 JSON으로 해석할 수 없는 경우뿐이다. **enum 후보(`gender`/`visaType`/`occupation`)와 `birthDate`는 요청 DTO가 String으로 받아 서버가 파싱하므로 값 위반도 `INVALID_INPUT`이다** — 온보딩(§5)과 같은 코드다(#151에서 통일) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근 |
| 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | (임대인) 새 `phoneNumber`로 변경 시 그 번호가 SMS 재인증(§4-1·§4-2)되지 않았거나 검증한 번호와 불일치 |

---

### 10. DELETE `/api/v1/users/me` — 회원 탈퇴

본인 계정을 탈퇴 처리한다. 사용자 상태를 `WITHDRAWN`으로 전이하고 모든 refresh 토큰을 무효화한다. PENDING(온보딩 미완료) 사용자도 탈퇴할 수 있다(온보딩 중단·정리 목적). **Apple 연동 계정은 저장된 `apple_refresh_token`으로 Apple `/auth/revoke`를 호출해 앱↔Apple ID 연동까지 폐기**한다(App Store 5.1.1(v), [ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)).

- **인증**: 필수.
- Path/Query 파라미터: 없음.
- Request Body: 없음.

#### 성공 Response — 204 No Content

본문 없음. **§7(로그아웃)과 마찬가지로 `Max-Age=0` 삭제 쿠키를 함께 내려** 브라우저에 남은 refresh 쿠키까지 지운다([ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md) §3).

```http
Set-Cookie: refreshToken=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=0
```

> **§7과 달리 조건 없이 내린다.** 로그아웃은 요청이 쿠키로 왔을 때만 붙이지만, 쿠키 `Path`가 `/api/v1/auth`라 브라우저는 이 요청(`/api/v1/users/me`)에 refresh 쿠키를 **애초에 싣지 않는다** — 요청만 봐서는 보유 여부를 알 수 없으므로 항상 내린다. **쿠키를 가진 적 없는 앱 클라이언트에는 아무 영향이 없다**(`Max-Age=0`은 「지금 만료」라 지울 것이 없다). 서버에서는 이미 모든 refresh가 무효화되므로 이 헤더는 보안이 아니라 **잔여물 정리**다 — 지우지 않으면 죽은 쿠키가 최대 14일 남아 재발급 재시도가 설명 불가능한 `401`을 받는다.

개인정보(세입자: 이름·생년월일·국적·표시 언어·직업·이메일·비자·닉네임 / 임대인: 이름·생년월일·연락처·국적·표시 언어·이메일·닉네임)는 탈퇴 시 즉시 익명화하고, 자격증명 두 벌(`social_accounts` 매핑 · 웹 `local_accounts` 행)을 **함께 삭제**한다([ADR-0014](../../adr/0014-withdrawal-pii-anonymization.md) · [ADR-0047](../../adr/0047-web-local-credentials-and-phone-based-account-linking.md)) — 웹 자격증명이 남으면 `users` 행이 보존되는 탈퇴 특성상 탈퇴한 임대인이 §1-4로 다시 로그인할 수 있고, `local_accounts.email` UNIQUE 때문에 같은 이메일 재가입도 막힌다. 임대인 `business_registration_number_hash` 컬럼도 익명화 대상에 포함하지만(방어적 처리 — [database-design](../../database/database-design.md) §4-2) **실제로는 온보딩·매물 등록 어느 경로에서도 채우지 않아 항상 NULL**이라 지울 값이 없다. 임대인이 매물 등록에 입력한 사업자등록번호는 매물 문서가 원문으로 보유하며, 탈퇴 시 매물 문서 PII 처리는 **후속** 설계 대상이다. Apple 연동은 매핑 삭제 전에 `/auth/revoke`로 폐기하며, **best-effort**(이미 폐기·Apple 장애여도 탈퇴는 완료)다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `USER_ALREADY_WITHDRAWN` | 이미 `WITHDRAWN`된 사용자의 탈퇴 재요청 |
| 404 | `USER_NOT_FOUND` | 사용자가 삭제되어 없음 |

---

### 11. GET `/api/v1/users/me/blocks` — 내 차단 목록

본인이 차단한 사용자 목록을 조회한다. **차단 해제(§12)를 위한 조회 경로**로, 차단한 시각(`blockedAt`) 내림차순 **오프셋 페이지네이션**(api-design-guide §4-1)이다. 차단은 `user_blocks(blocker_id, blocked_user_id)` 한 행으로 표현하며 `user` 모듈이 소유한다.

> **왜 사용자 단위 차단인가**: 차단 대상은 **예약이 아니라 사용자**다. 한 임대인은 **매물(`Listing.landlordId`)을 여러 개** 소유하고, 한 매물은 **방 상품(`Listing.roomOffers`)을 여러 개** 갖는다. 그래서 예약(방) 단위로 차단하면 상대는 **자기 다른 방 상품에 신청**하는 것만으로 새 예약 = 새 채팅방을 만들어 차단을 우회한다. 차단은 본질적으로 **사람**에 대한 것이라, 대상이 예약이면 상대가 방을 하나 더 가진 순간 무력해진다. 사용자 단위여야 상대의 모든 매물·방으로 효력이 미친다.
> — 보조 근거(중복 방지 반영): `bookings`에는 중복 방지 유니크 제약(`uq_bookings_tenant_room_offer` on `(tenant_id, room_offer_id)`)이 있어 **같은 방 상품 재신청은 `409 BOOKING_ALREADY_EXISTS`로 막힌다**. 그래도 임대인은 **매물·방 상품을 여러 개** 가지므로 상대는 **다른 방으로는 여전히 우회**할 수 있다 — 그래서 여전히 사용자 단위여야 한다. 이 보조 근거는 위 구조적 근거를 뒤집지 않는다(중복 제한 여부와 무관하게 사용자 단위 결론은 성립).
> **왜 해제가 예약이 아니라 여기 있는가**: 차단하면 그 상대와의 예약이 내 목록에서 전부 사라져(아래 의미론) **`bookingId`를 다시 얻을 수 없다**. `bookingId`를 경로에 요구하는 해제 API는 성립하지 않으므로, 해제 경로는 예약과 무관한 `/users/me/blocks`여야 한다. 목록(§11)이 해제(§12)의 유일한 대상 공급원이다.
> **왜 `is_active` 컬럼이 없는가**: **행의 존재가 곧 차단**이고 해제는 행 삭제다. 상태 플래그를 두면 "행은 있는데 차단이 아닌" 상태가 생겨 목록·필터 술어가 두 갈래로 갈린다.

**차단 의미론**(생성은 §04, 효과는 두 방향이 다르다 — 반드시 구분한다):

| 효과 | 방향 | 동작 |
| --- | --- | --- |
| 예약 목록·상세 숨김 | **단방향(차단자 기준)** | 내가 A를 차단하면 **A와의 모든 예약**이 내 목록·상세에서 사라진다(상세는 `404 BOOKING_NOT_FOUND`). **A의 목록은 그대로**라 A에게는 예약이 계속 보인다 |
| 신규 예약 신청 | **양방향** | 어느 한쪽이라도 차단 관계면 `POST /api/v1/listings/{listingId}/bookings`가 `403 FORBIDDEN`이다. 단방향으로 두면 상대가 신청은 성공(`201`)하는데 내 목록엔 영영 보이지 않는 **블랙홀 예약**이 생긴다 |

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통, 역할 `403` 없음).
- Path 파라미터: 없음.

> **차단 생성 경로는 여기에 없다** — 생성은 예약 문맥 전용 `POST /api/v1/bookings/{bookingId}/block`([04-booking-inquiry-chat](04-booking-inquiry-chat.md))뿐이고, **`userId`로 차단을 만드는 엔드포인트는 두지 않는다**. 차단 상대는 클라이언트가 보내지 않고 **서버가 예약에서 도출**(`요청자 == tenantId ? landlordId : tenantId`)하기 때문이다. 앱에는 임의의 사용자를 지목해 차단하는 화면 자체가 없다(차단은 예약으로 맺어진 상대에게만 성립).

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | int | 선택 | 0 | 0-base 페이지 번호 |
| `size` | int | 선택 | 20 | 페이지 크기(최대 100). 범위 초과는 `INVALID_INPUT`(400) |

> 정렬은 `blockedAt,desc` 고정(쿼리로 변경 불가). 상대 표시명(`name`)은 `user` 모듈 내부 조회로 채운다 — 노출은 확정이고 **마스킹 수준만 (확인 필요)**(아래 필드 표).

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "userId": 2048,
        "name": "Kim Minsu",
        "blockedAt": "2026-06-15T08:30:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | number | 차단한 상대의 사용자 ID. §12의 경로 변수로 그대로 쓴다 |
| `name` | string | 차단한 상대의 표시명(세입자·임대인 모두 단일 `name`). 해제 UI(§12)가 대상을 식별해야 하므로 **노출한다** — 다만 타 사용자 정보(PII)라 **마스킹 수준은 (확인 필요)**(원문 그대로 vs 부분 마스킹) |
| `blockedAt` | string(date-time) | 차단 시각(ISO-8601 UTC) |

> 차단이 하나도 없으면 `content: []` + `page.totalElements: 0` + `page.hasNext: false`(에러 아님). 차단한 상대가 탈퇴(`WITHDRAWN`)해도 행은 남으므로 목록에 나타난다 — 표시명 익명화는 탈퇴 시 익명화 정책([ADR-0014](../../adr/0014-withdrawal-pii-anonymization.md))을 따른다.

> **SecurityConfig 매처 주의**: 현행 `/api/v1/users/me` 매처(`SecurityConfig.java:61`)는 `**`가 아닌 **정확 경로**라 `/api/v1/users/me/blocks`·`/api/v1/users/me/blocks/*`를 **덮지 않는다**. 두 경로용 매처를 `hasRole("USER")`로 **명시 추가**해야 하며, 빠뜨리면 `anyRequest().authenticated()`로 떨어져 **온보딩 토큰(`ROLE_ONBOARDING`)이 그대로 통과**한다(`403 AUTH_ONBOARDING_REQUIRED`가 나가지 않는다). 같은 이유로 `DELETE /api/v1/users/me` 매처(`SecurityConfig.java:58`, 온보딩 토큰 허용 — 탈퇴)도 `/me/blocks/*`에는 적용되지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `page`/`size` 범위 위반(음수 `page`, `size` 1 미만·100 초과). 보정하지 않고 거절한다 |
| 400 | `MALFORMED_REQUEST` | `page`/`size`가 정수가 아님(쿼리 파라미터 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근 |

---

### 12. DELETE `/api/v1/users/me/blocks/{userId}` — 차단 해제

`{userId}`에 대한 내 차단을 해제한다. `user_blocks`에서 `(blocker_id=요청자, blocked_user_id={userId})` **행을 삭제**한다(`is_active` 플래그를 내리는 게 아니다 — §11 근거). 해제 즉시 그 상대와의 예약이 내 목록·상세에 다시 나타난다 — 단 내가 [04 §4](04-booking-inquiry-chat.md)로 **직접 삭제한 예약은 `*_deleted_at`이 남아 계속 숨겨진다**(차단과 삭제는 독립된 숨김 사유다). 신규 예약 신청은 **역방향 차단이 없을 때만** 다시 가능해진다 — 가드는 양방향이라 상대가 나를 차단한 행이 남아 있으면 여전히 `403 FORBIDDEN`이다(차단은 방향별로 별개 행이다 — §11 의미론).

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통).

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `userId` | number | 필수 | 해제할 상대의 사용자 ID. §11 목록의 `userId`를 그대로 쓴다 |

- Request Body: 없음.

#### 성공 Response — 204 No Content

본문 없음. **멱등** — 차단한 적이 없거나 이미 해제한 `userId`로 호출해도 `404`가 아니라 `204`다(삭제할 행이 없으면 아무것도 하지 않는다). "차단 아님"이라는 목표 상태가 이미 성립하므로 재시도·중복 탭이 실패로 보이지 않게 한다.

> 존재하지 않는 `userId`·탈퇴한 사용자에게도 `204`다 — 차단 여부는 **내 `user_blocks` 행의 유무**로만 판정하며, 상대 사용자의 실재 여부를 확인하지 않는다(확인하면 임의 `userId`를 넣어 계정 존재를 탐지할 수 있다). 같은 이유로 `USER_NOT_FOUND`(404)를 내지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | `userId`가 숫자가 아님(경로 변수 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근(§11의 SecurityConfig 매처 주의 참조) |

---

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `RESOURCE_NOT_FOUND` 등)는 [error-response-guide](../error-response-guide.md) §3·§4를 따르며 여기서 재정의하지 않는다. provider/idToken 등 입력 형식 위반은 별도 도메인 코드 없이 공통 코드로 처리한다 — Bean Validation 위반(누락·빈값)은 `INVALID_INPUT`, 역직렬화 실패(허용 외 enum 문자열 등)는 `MALFORMED_REQUEST`. 아래는 auth/user 도메인 고유 코드만 정의한다. prefix는 `AUTH` / `USER`. 임대인 웹 트랙에서 추가된 6코드(`AUTH_INVALID_CREDENTIALS`·`AUTH_ACCOUNT_LOCKED`·`AUTH_EMAIL_ALREADY_REGISTERED`·`AUTH_WEB_ACCOUNT_ALREADY_EXISTS`·`AUTH_WEB_ACCOUNT_NOT_FOUND`·`AUTH_PASSWORD_RESET_TOKEN_INVALID`)도 같은 카탈로그에 누적하며, **`AUTH_ACCOUNT_LOCKED`의 `423 Locked`는 이 프로젝트가 처음 쓰는 status**다(status↔코드 매핑은 [error-response-guide](../error-response-guide.md) §3·§4에도 함께 반영한다).

| code | status | 의미 |
| --- | --- | --- |
| `AUTH_MISSING_CREDENTIAL` | 400 | provider의 자격 필드 누락(Google `idToken` 또는 Apple `authorizationCode` 미전송) |
| `AUTH_INVALID_SOCIAL_TOKEN` | 401 | Google `idToken` 검증 실패(서명/`aud`/`iss`/`exp`), 또는 Apple `authorizationCode` 교환 실패·교환 `id_token` 검증 실패(위조·만료·앱 불일치·재사용 코드) |
| `AUTH_INVALID_CREDENTIALS` | 401 | 임대인 웹 로그인(`POST /auth/login`) 실패 — 등록되지 않은 이메일 **또는** 비밀번호 불일치(code·status·문구가 같고, 비밀번호 불일치일 때만 `error.details`가 실린다) |
| `AUTH_ACCOUNT_LOCKED` | 423 | 임대인 웹 로그인에서 비밀번호 10회 연속 실패로 잠긴 계정(비밀번호가 맞아도 잠금이 우선 — 해제는 본인이 비밀번호 재설정(§1-8~§1-10)을 완주하는 것뿐이고 시간 경과로는 풀리지 않는다) |
| `AUTH_EMAIL_REQUIRED` | 422 | 소셜 로그인(`POST /auth/social-login`) 시 토큰의 `email` 클레임·요청 `email` 어느 쪽에도 이메일이 없음(provider 진본 이메일 확정 불가) |
| `AUTH_EMAIL_MISMATCH` | 422 | 소셜 로그인 요청 `email`이 토큰의 `email` 클레임과 불일치(email은 provider 진본으로 확정) |
| `AUTH_EMAIL_VERIFICATION_FAILED` | 422 | 이메일 인증번호 불일치 또는 만료(미발송·만료·오입력) — 세입자(정식(ACTIVE) 사용자 이메일 인증 §3·§4) |
| `AUTH_PHONE_VERIFICATION_FAILED` | 422 | 연락처(SMS) 인증번호 불일치 또는 만료(미발송·만료·오입력) — 임대인. **번호 키 챌린지(가입용 §1-2 · 이메일 찾기용 §1-6)에서는 검증 시도 상한 초과도 이 코드**다(온보딩용 §4-2는 시도 초과를 `429`로 분리) |
| `AUTH_PHONE_NOT_VERIFIED` | 422 | 임대인 온보딩 제출 또는 프로필 연락처 변경 시 `phoneNumber`가 미검증이거나 검증한 번호와 불일치. **임대인 웹 회원가입(§1-3)에서 가입용 인증 마커가, 이메일 찾기(§1-7)에서 이메일 찾기용 마커가 없거나 만료된 경우도 포함**한다 — 두 마커는 키스페이스가 달라 서로를 대신하지 못한다 |
| `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 422 | 비밀번호 재설정 토큰이 없거나·만료·이미 사용됨(사전 확인 §1-9와 확정 §1-10 공용) — 세 경우를 구분하지 않는다(소비 여부까지 알려 주는 오라클 차단) |
| `AUTH_REQUIRED_AGREEMENT_MISSING` | 422 | 필수 약관(이용약관/개인정보처리방침) 미동의(약관 동의 `POST /auth/terms`) |
| `AUTH_TERMS_AGREEMENT_REQUIRED` | 422 | 약관 미동의(`PENDING`) 상태로 온보딩 제출 또는 연락처 인증(약관 동의 선행 필요) |
| `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` | 422 | 사업자번호 검증(`POST /auth/business/verify`) 시 검증 서비스 조회 결과 미등록·휴업·폐업(진위·상태 검증 실패) |
| `AUTH_ONBOARDING_REQUIRED` | 403 | 온보딩 미완료(`PENDING`/`TERMS_AGREED`) 상태로 보호 API 접근 |
| `AUTH_ONBOARDING_ALREADY_COMPLETED` | 409 | 이미 온보딩 완료(ACTIVE)된 사용자가 온보딩 재요청 |
| `AUTH_EMAIL_ALREADY_REGISTERED` | 409 | 임대인 웹 회원가입(`POST /auth/signup`)에서 그 이메일의 `local_accounts` 행이 이미 있음(웹 로그인 ID 중복 — `users.email`은 검사하지 않는다) |
| `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` | 409 | 임대인 웹 회원가입에서 번호로 매칭된 계정에 이미 웹 자격증명이 붙어 있음(연동이 이미 끝난 상태 → 로그인으로 유도. 그 계정의 이메일은 응답에 싣지 않는다) |
| `AUTH_WEB_ACCOUNT_NOT_FOUND` | 404 | 이메일 찾기(`POST /auth/email/find`)에서 인증한 번호로 가입된 웹 계정이 없음 **또는** 제출한 이름이 `local_accounts.name`과 불일치 — 두 경우를 구분하지 않는다(이름 오라클 차단) |
| `AUTH_INVALID_REFRESH_TOKEN` | 401 | refresh 토큰 만료/위조/무효화/재사용 탐지 |
| `USER_NOT_FOUND` | 404 | 대상 사용자가 없거나 탈퇴되어 조회 불가 |
| `USER_ALREADY_WITHDRAWN` | 409 | 이미 탈퇴(WITHDRAWN)된 사용자에 대한 탈퇴 재요청 |
