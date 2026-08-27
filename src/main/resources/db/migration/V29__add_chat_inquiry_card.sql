-- V29 — 문의하기로 새 채팅방을 만들 때 매물 요약 INQUIRY_CARD를 첫 메시지로 저장한다.
--
-- 기존 BOOKING_CARD의 payload는 신청자·객실·금액처럼 구조가 전혀 다른 데이터다. 한 JSON 컬럼에 두 구조를 섞으면
-- Java 타입과 DB 검증이 복잡해지므로 문의서는 inquiry_payload에 따로 저장한다. 기존 TEXT와 BOOKING_CARD 행은
-- 변환하지 않으며 새 컬럼의 null 기본값으로 그대로 유지된다.

ALTER TABLE chat_messages
    ADD COLUMN inquiry_payload JSON NULL AFTER content;

-- 기존 CHECK는 아직 두 타입만 허용하므로 먼저 제거한 다음 세 타입 계약으로 다시 만든다.
ALTER TABLE chat_messages
    DROP CHECK ck_chat_messages_type_fields,
    DROP CHECK ck_chat_messages_type;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_type
        CHECK (type IN ('TEXT', 'INQUIRY_CARD', 'BOOKING_CARD')),
    ADD CONSTRAINT ck_chat_messages_type_fields
        CHECK (
            (
                type = 'TEXT'
                AND sender_id IS NOT NULL
                AND content IS NOT NULL
                AND CHAR_LENGTH(content) BETWEEN 1 AND 3000
                AND client_message_id IS NOT NULL
                AND inquiry_payload IS NULL
                AND booking_id IS NULL
                AND payload IS NULL
            )
            OR
            (
                type = 'INQUIRY_CARD'
                AND sender_id IS NULL
                AND content IS NULL
                AND client_message_id IS NULL
                AND inquiry_payload IS NOT NULL
                AND JSON_TYPE(inquiry_payload) = 'OBJECT'
                AND booking_id IS NULL
                AND payload IS NULL
            )
            OR
            (
                type = 'BOOKING_CARD'
                AND sender_id IS NULL
                AND content IS NULL
                AND client_message_id IS NULL
                AND inquiry_payload IS NULL
                AND booking_id IS NOT NULL
                AND payload IS NOT NULL
                AND JSON_TYPE(payload) = 'OBJECT'
            )
        );
