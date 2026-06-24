-- V6 — countries에 표시 언어(lang) 컬럼 추가. 다국어 표시(진단 문항·추천 제안 등)에서 사용자 언어를 결정하는 단일 출처.
-- 값은 ISO 639-1 소문자(en/ko/ja/zh/vi ...). 미매핑·NULL이면 호출 측이 영어(en)로 폴백한다.
-- ADR-0029 개정: 국가→언어 매핑을 diagnosis 하드코딩에서 user `countries` 참조로 이전한다.
ALTER TABLE countries ADD COLUMN lang VARCHAR(8) NOT NULL DEFAULT 'en';

UPDATE countries SET lang = 'ko' WHERE code = 'KR';
UPDATE countries SET lang = 'ja' WHERE code = 'JP';
UPDATE countries SET lang = 'zh' WHERE code = 'CN';
UPDATE countries SET lang = 'vi' WHERE code = 'VN';
