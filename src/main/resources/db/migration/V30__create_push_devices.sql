-- V30 — iOS FCM 푸시를 보낼 앱 설치본과 사용자 관계를 저장한다.
--
-- FCM은 Kohere 사용자 ID를 알지 못하므로 백엔드가 user_id와 발송 주소인 fcm_token을 연결해 보관한다.
-- installation_id는 아이폰 하드웨어 ID가 아니라 앱이 만든 설치본 UUID이며, 토큰이 회전해도 같은 행을 갱신하는 기준이다.
CREATE TABLE push_devices (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    -- UUID 문자열 36자 대신 원래 128비트를 보관해 UNIQUE 인덱스 크기와 비교 비용을 줄인다.
    installation_id  BINARY(16)    NOT NULL,
    -- FCM 토큰은 opaque ASCII 문자열이다. 대소문자가 다른 토큰을 같은 값으로 접지 않도록 binary collation을 사용한다.
    fcm_token        VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    platform         VARCHAR(16)   NOT NULL,
    last_seen_at     DATETIME(6)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 한 설치본의 토큰이 바뀌면 새 행을 추가하지 않고 이 행을 갱신한다.
    CONSTRAINT uq_push_devices_installation UNIQUE (installation_id),
    -- 하나의 FCM 발송 주소가 여러 사용자·설치본에 중복돼 같은 알림을 반복 수신하지 않게 한다.
    CONSTRAINT uq_push_devices_fcm_token UNIQUE (fcm_token),
    CONSTRAINT ck_push_devices_user_id CHECK (user_id > 0),
    CONSTRAINT ck_push_devices_platform CHECK (platform = 'IOS'),
    CONSTRAINT ck_push_devices_fcm_token_length CHECK (CHAR_LENGTH(fcm_token) BETWEEN 1 AND 1024),
    CONSTRAINT ck_push_devices_timestamps CHECK (
        last_seen_at >= created_at AND updated_at >= created_at
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 새 채팅 수신자의 모든 등록 기기를 찾는 조회를 user_id 선두 인덱스로 지원한다.
CREATE INDEX idx_push_devices_user_id ON push_devices (user_id, id);
