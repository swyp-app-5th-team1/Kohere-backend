-- V27 — 채팅 TEXT 원문과 사용자별 자동 번역 작업을 분리해 저장한다.
--
-- chat_messages.content는 사용자가 보낸 원문 정본이다. 번역문은 원문을 덮어쓰지 않고 이 테이블에
-- 파생 데이터로 저장한다. 같은 행을 durable 작업 큐로도 사용하므로 서버가 재시작돼도 PENDING 작업을
-- 다시 찾을 수 있다. BOOKING_CARD는 고정 UI 라벨로 표시하므로 이 테이블에 행을 만들지 않는다.
CREATE TABLE chat_message_translations (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    message_id               BIGINT       NOT NULL,
    recipient_user_id        BIGINT       NOT NULL,
    target_language          VARCHAR(8)   NOT NULL,
    detected_source_language VARCHAR(8)   NULL,
    status                   VARCHAR(16)  NOT NULL,
    translated_content       TEXT         NULL,
    provider                 VARCHAR(32)  NOT NULL,
    model                    VARCHAR(32)  NOT NULL,
    attempt_count            INT          NOT NULL DEFAULT 0,
    lease_until              DATETIME(6)  NULL,
    last_failure_code        VARCHAR(64)  NULL,
    translated_at            DATETIME(6)  NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 한 메시지는 해당 수신자와 원문 저장 시점의 대상 언어에 대해 번역 작업을 한 번만 만든다.
    CONSTRAINT uq_chat_message_translations_message_recipient_language
        UNIQUE (message_id, recipient_user_id, target_language),
    CONSTRAINT ck_chat_message_translations_target_language
        CHECK (target_language IN ('ko', 'en')),
    CONSTRAINT ck_chat_message_translations_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'NOT_REQUIRED', 'FAILED')),
    CONSTRAINT ck_chat_message_translations_provider
        CHECK (provider = 'GOOGLE_CLOUD_TRANSLATION'),
    CONSTRAINT ck_chat_message_translations_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 5),
    -- 번역문은 성공 상태에서만 존재한다. 그 외 상태에서 이전 결과가 남으면 앱이 잘못 표시할 수 있다.
    CONSTRAINT ck_chat_message_translations_content_state
        CHECK (
            (status = 'SUCCEEDED' AND translated_content IS NOT NULL AND translated_at IS NOT NULL)
            OR (status <> 'SUCCEEDED' AND translated_content IS NULL)
        ),
    -- PROCESSING 작업만 lease를 가진다. Worker가 죽으면 이 시각이 지난 작업을 다른 실행이 회수한다.
    CONSTRAINT ck_chat_message_translations_lease_state
        CHECK (
            (status = 'PROCESSING' AND lease_until IS NOT NULL)
            OR (status <> 'PROCESSING' AND lease_until IS NULL)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 즉시 신호를 놓쳤거나 서버가 재시작됐을 때 처리 가능한 작업을 오래된 순서로 빠르게 찾는다.
CREATE INDEX idx_chat_message_translations_recovery
    ON chat_message_translations (status, lease_until, id);

-- REST 메시지 이력이 현재 사용자에게 저장된 번역 결과를 한 번에 붙일 때 사용한다.
CREATE INDEX idx_chat_message_translations_recipient_message
    ON chat_message_translations (recipient_user_id, message_id);
