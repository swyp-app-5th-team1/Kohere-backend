-- V26 — 1:1 채팅방 신고 접수와 신고 당시 원문 증거를 저장한다.
--
-- chat_reports는 목록·상태 확인에 필요한 작은 접수 정보만 가진다.
-- chat_report_evidence는 최근 TEXT 원문을 담는 크고 민감한 JSON 스냅샷을 별도로 가진다.
-- 두 테이블을 나누면 후속 관리자 목록 조회가 증거 JSON을 매번 읽지 않아도 되고,
-- 증거에 대한 접근·보관·파기 정책도 신고 기본 정보와 분리해 관리할 수 있다.

CREATE TABLE chat_reports (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    chat_room_id                BIGINT       NOT NULL,
    reporter_id                 BIGINT       NOT NULL,
    reported_user_id            BIGINT       NOT NULL,
    reason                      VARCHAR(64)  NOT NULL,
    status                      VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    evidence_through_message_id BIGINT       NOT NULL,
    received_at                 DATETIME(6)  NOT NULL,
    retention_expires_at        DATETIME(6)  NOT NULL,
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_reports_reporter_room UNIQUE (reporter_id, chat_room_id),
    CONSTRAINT chk_chat_reports_distinct_users CHECK (reporter_id <> reported_user_id),
    CONSTRAINT chk_chat_reports_reason CHECK (
        reason IN (
            'ABUSE_HARASSMENT_DISCRIMINATION',
            'ILLEGAL_CONTENT',
            'SEXUAL_INAPPROPRIATE_CONTENT',
            'PERSONAL_INFORMATION',
            'SPAM',
            'OTHER'
        )
    ),
    CONSTRAINT chk_chat_reports_status CHECK (status = 'RECEIVED'),
    CONSTRAINT chk_chat_reports_evidence_message CHECK (evidence_through_message_id > 0),
    CONSTRAINT chk_chat_reports_retention CHECK (retention_expires_at > received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 후속 관리자 목록은 최신 신고 순으로 읽고, 후속 파기 작업은 만료 시각으로 대상을 찾는다.
CREATE INDEX idx_chat_reports_received ON chat_reports (received_at DESC, id DESC);
CREATE INDEX idx_chat_reports_reported_received
    ON chat_reports (reported_user_id, received_at DESC, id DESC);
CREATE INDEX idx_chat_reports_retention ON chat_reports (retention_expires_at, id);

CREATE TABLE chat_report_evidence (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    report_id                   BIGINT      NOT NULL,
    schema_version              INT         NOT NULL,
    evidence_through_message_id BIGINT      NOT NULL,
    snapshot                    JSON        NOT NULL,
    content_hash                VARCHAR(64) NOT NULL,
    captured_at                 DATETIME(6) NOT NULL,
    created_at                  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_report_evidence_report UNIQUE (report_id),
    CONSTRAINT chk_chat_report_evidence_schema CHECK (schema_version = 1),
    CONSTRAINT chk_chat_report_evidence_message CHECK (evidence_through_message_id > 0),
    CONSTRAINT chk_chat_report_evidence_snapshot CHECK (JSON_TYPE(snapshot) = 'OBJECT'),
    CONSTRAINT chk_chat_report_evidence_hash CHECK (CHAR_LENGTH(content_hash) = 64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- report_id는 같은 report 모듈의 값이지만 저장소의 no-FK 관례를 따라 숫자로 참조한다.
-- 신고와 증거는 같은 애플리케이션 트랜잭션에서 저장해 고아 행이 생기지 않게 한다.
CREATE INDEX idx_chat_report_evidence_captured ON chat_report_evidence (captured_at DESC, id DESC);
