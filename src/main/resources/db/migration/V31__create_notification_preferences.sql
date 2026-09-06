-- V31 — 사용자 단위로 채팅 FCM 푸시 수신 여부를 저장한다.
--
-- 기존·신규 사용자의 행을 미리 만들지 않는다. 행이 없는 사용자는 애플리케이션이 true로 판단하고,
-- 사용자가 설정을 처음 변경할 때만 행을 upsert한다.
CREATE TABLE notification_preferences (
    user_id             BIGINT       NOT NULL,
    chat_push_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT ck_notification_preferences_user_id CHECK (user_id > 0),
    CONSTRAINT ck_notification_preferences_chat_push_enabled CHECK (chat_push_enabled IN (0, 1)),
    CONSTRAINT ck_notification_preferences_timestamps CHECK (updated_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
