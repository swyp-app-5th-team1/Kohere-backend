-- V28 — users.phone_number UNIQUE를 (user_type, phone_number) 복합키로 완화 (#265, ADR-0047 Amended).
-- 관리자(ADMIN)는 가입 경로가 없어 운영자가 임대인 웹 가입 계정을 수동 승격해 만든다. 승격하면 그 계정은
-- 임대인 조회(user_type=LANDLORD 필터)에서 빠지면서도 phone_number는 계속 점유하므로, 같은 사람이 그
-- 번호로 임대인 계정을 따로 만들 수 없게 된다 — 개인 번호가 하나뿐인 사람에게 "관리자 전용 번호를 쓰라"는
-- 운영 규칙은 SMS 인증을 실제로 받아야 하는 이상 지키기 어렵다. 그래서 스키마로 푼다.
--
-- V23이 막으려던 경쟁은 그대로 막힌다: 웹 임대인 가입과 앱 임대인 온보딩이 동시에 들어와 각자 ACTIVE 행을
-- 만드는 시나리오는 두 INSERT가 모두 LANDLORD라 복합키에서도 (LANDLORD, 010X)로 충돌한다. 공존이
-- 허용되는 것은 유형이 다른 (ADMIN, 010X)뿐이다.
--
--   ⚠️ 제약 이름을 uq_users_phone_number 그대로 유지한다. GlobalExceptionHandler가 제약 이름
--      화이트리스트(uq_users_phone_number · uq_local_accounts_email · uq_local_accounts_user_id)로
--      이 위반을 409 RESOURCE_CONFLICT로 번역하므로, 이름을 바꾸면 계정 갈림 경합이 카탈로그에 없는
--      500 INTERNAL_ERROR로 떨어지고 재시도 신호도 사라진다(ADR-0047 §5).
--
-- 애플리케이션 코드 변경은 없다 — 번호로 조회하는 두 쿼리(findByPhoneNumberAndStatusAndUserType,
-- ...AndIdNot)가 이미 user_type으로 필터해 전역 유일성을 가정하지 않는다.
--
-- migration-policy §3상 제약 '완화'라 기존 행이 전부 새 제약을 자동으로 만족한다 — 선행 정리 쿼리가
-- 필요 없고 V23(제약 강화)과 반대 방향이다. MySQL UNIQUE는 NULL 중복을 허용하므로 세입자(NULL)와
-- 탈퇴자(익명화로 NULL)는 종전대로 영향받지 않는다.
-- docs/database/database-design.md §4-2 · docs/adr/0047-web-local-credentials-and-phone-based-account-linking.md.
ALTER TABLE users
    DROP INDEX uq_users_phone_number,
    ADD CONSTRAINT uq_users_phone_number UNIQUE (user_type, phone_number);
