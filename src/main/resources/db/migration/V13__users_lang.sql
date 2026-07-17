-- V13 — 표시 언어(users.lang) 추가 + 국가→언어 도출·countries.lang 폐기 (#141, ADR-0029·0034 개정).
-- 표시 언어는 사용자가 고른 값(users.lang)이 유일한 출처다. 미설정(NULL)이면 런타임에서 영어(en)로 폴백한다(getLanguage).
-- 지원 언어는 en/ko/ja이며 서버가 화이트리스트로 검증한다. 국적(country)은 표시 언어와 무관한 사실값이 된다.
ALTER TABLE users ADD COLUMN lang VARCHAR(8) NULL;

-- 임대인: 한국인 사업자 전제 — 국적·표시 언어를 서버가 고정 부여한다(ADR-0034 "임대인 country 미수집" 개정).
-- 세입자는 백필하지 않는다 — 미선택(NULL)은 런타임에서 en으로 폴백하고, 이후 지구본에서 직접 고른다.
UPDATE users SET lang = 'ko', country = 'KR' WHERE user_type = 'LANDLORD';

-- 국가→언어 도출을 폐기하므로 V6에서 추가한 countries.lang 컬럼을 제거한다.
ALTER TABLE countries DROP COLUMN lang;
