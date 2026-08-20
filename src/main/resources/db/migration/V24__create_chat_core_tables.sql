-- V24 — 1:1 매물 채팅의 저장 기반을 만든다(#235 후속 2단계).
--
-- 이 migration은 사용자 화면 기능을 켜는 파일이 아니라, 이후 문의·신청·STOMP 단계가 공통으로 사용할
-- 채팅방·참여자·메시지 정본을 먼저 고정한다. 번역 작업과 신고 자료는 각 기능을 구현하는 시점의 별도
-- migration에서 추가한다. user·listing·booking 식별자는 모듈 경계를 넘는 값 참조이므로 FK를 만들지 않고,
-- 같은 chat 모듈의 식별자도 이 저장소의 일관된 no-FK 정책에 맞춰 애플리케이션 트랜잭션으로 정합성을 보장한다.

-- 채팅방은 (매물, 임차인, 임대인) 조합당 하나만 존재한다. 문의하기와 신청하기가 같은 UNIQUE 키를 사용해야
-- 어느 기능이 먼저 실행돼도 하나의 roomId로 수렴한다.
CREATE TABLE chat_rooms (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    category         VARCHAR(16)  NOT NULL DEFAULT 'LANDLORD',
    listing_id       VARCHAR(24)  NOT NULL,
    tenant_id        BIGINT       NOT NULL,
    landlord_id      BIGINT       NOT NULL,
    listing_snapshot JSON         NOT NULL,
    last_message_id  BIGINT       NULL,
    last_message_at  DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_chat_rooms_listing_participants
        UNIQUE (listing_id, tenant_id, landlord_id),
    -- 자기 자신에게 문의하는 방은 애플리케이션 검증을 우회해도 저장될 수 없어야 한다.
    CONSTRAINT ck_chat_rooms_distinct_participants
        CHECK (tenant_id <> landlord_id),
    -- 현재 외부 기능은 임대인-임차인 매물 채팅만 지원한다. 남아 있는 내부 enum의 확장은 별도 migration으로 연다.
    CONSTRAINT ck_chat_rooms_category
        CHECK (category = 'LANDLORD'),
    -- JSON 컬럼은 문법상 배열·스칼라도 허용하므로 헤더 필드를 담는 객체 형태까지 고정한다.
    CONSTRAINT ck_chat_rooms_listing_snapshot_object
        CHECK (JSON_TYPE(listing_snapshot) = 'OBJECT'),
    -- 마지막 메시지 번호와 시각은 한 쌍이다. 한쪽만 기록되면 목록 정렬과 preview가 서로 다른 메시지를 가리킬 수 있다.
    CONSTRAINT ck_chat_rooms_last_message_pair
        CHECK (
            (last_message_id IS NULL AND last_message_at IS NULL)
            OR (last_message_id IS NOT NULL AND last_message_at IS NOT NULL)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 참여자별 목록은 사용자 한 명의 방을 최근 활동순으로 읽으므로 두 역할을 각각 선두 컬럼으로 둔다.
CREATE INDEX idx_chat_rooms_tenant_last_message
    ON chat_rooms (tenant_id, last_message_at DESC, id DESC);
CREATE INDEX idx_chat_rooms_landlord_last_message
    ON chat_rooms (landlord_id, last_message_at DESC, id DESC);

-- 공유 채팅방을 사용자가 각자 숨길 수 있도록 방 상태와 사용자별 표시 상태를 분리한다.
-- 한 방을 삭제해도 상대방 행은 바뀌지 않으며, 새 메시지로 방만 다시 표시되더라도 과거 숨김 경계는 유지한다.
CREATE TABLE chat_room_members (
    id                                BIGINT       NOT NULL AUTO_INCREMENT,
    chat_room_id                      BIGINT       NOT NULL,
    user_id                           BIGINT       NOT NULL,
    counterpart_id                    BIGINT       NOT NULL,
    member_role                       VARCHAR(16)  NOT NULL,
    room_hidden_at                    DATETIME(6)  NULL,
    -- 0은 아직 숨긴 이력이 없다는 sentinel이다. NULL 비교의 UNKNOWN 때문에 조회 조건이 전체 누락되는 일을 막는다.
    history_hidden_through_message_id BIGINT       NOT NULL DEFAULT 0,
    delete_requested_at               DATETIME(6)  NULL,
    created_at                        DATETIME(6)  NOT NULL,
    updated_at                        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 같은 사용자의 표시 상태가 두 행으로 갈라지면 삭제 경계가 모호해지므로 방·사용자당 정확히 한 행만 허용한다.
    CONSTRAINT uq_chat_room_members_room_user
        UNIQUE (chat_room_id, user_id),
    CONSTRAINT ck_chat_room_members_distinct_counterpart
        CHECK (user_id <> counterpart_id),
    CONSTRAINT ck_chat_room_members_history_boundary
        CHECK (history_hidden_through_message_id >= 0),
    CONSTRAINT ck_chat_room_members_role
        CHECK (member_role IN ('TENANT', 'LANDLORD'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 방 목록 조회에서 먼저 user_id로 범위를 좁히고 room_hidden_at IS NULL 여부를 확인한다.
CREATE INDEX idx_chat_room_members_user_visibility
    ON chat_room_members (user_id, room_hidden_at, chat_room_id);

-- chat_messages는 전송 중인 임시 큐가 아니라 모든 채팅방에서 서버 저장이 끝난 불변 메시지의 정본이다.
-- 임차인용·임대인용 사본을 따로 만들지 않고 chat_room_id와 sender_id로 소속과 발신자를 구분한다.
CREATE TABLE chat_messages (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    chat_room_id      BIGINT       NOT NULL,
    sender_id         BIGINT       NULL,
    type              VARCHAR(32)  NOT NULL,
    content           TEXT         NULL,
    payload           JSON         NULL,
    booking_id        BIGINT       NULL,
    -- UUID 텍스트(36바이트) 대신 원래 128비트를 그대로 저장해 인덱스 크기와 비교 비용을 줄인다.
    client_message_id BINARY(16)   NULL,
    sent_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- TEXT 재전송은 같은 발신자·UUID 조합으로 판정한다. nullable 컬럼 덕분에 서버 카드는 이 UNIQUE와 충돌하지 않는다.
    CONSTRAINT uq_chat_messages_client_message
        UNIQUE (chat_room_id, sender_id, client_message_id),
    -- 같은 신청 이벤트가 재처리돼도 한 채팅방에는 bookingId당 카드가 한 장만 저장된다.
    CONSTRAINT uq_chat_messages_booking
        UNIQUE (chat_room_id, booking_id),
    CONSTRAINT ck_chat_messages_type
        CHECK (type IN ('TEXT', 'BOOKING_CARD')),
    -- 타입별 필드를 DB에서도 배타적으로 강제한다. 애플리케이션 검증에 결함이 생겨도
    -- 사용자 TEXT가 서버 카드로 위장하거나 본문 없는 메시지가 정본에 남지 않게 한다.
    CONSTRAINT ck_chat_messages_type_fields
        CHECK (
            (
                type = 'TEXT'
                AND sender_id IS NOT NULL
                AND content IS NOT NULL
                AND CHAR_LENGTH(content) BETWEEN 1 AND 3000
                AND client_message_id IS NOT NULL
                AND booking_id IS NULL
                AND payload IS NULL
            )
            OR
            (
                type = 'BOOKING_CARD'
                AND sender_id IS NULL
                AND content IS NULL
                AND client_message_id IS NULL
                AND booking_id IS NOT NULL
                AND payload IS NOT NULL
                AND JSON_TYPE(payload) = 'OBJECT'
            )
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 과거 cursor와 재연결 catch-up 모두 한 채팅방 안에서 messageId 범위를 읽으므로 같은 복합 인덱스를 공유한다.
CREATE INDEX idx_chat_messages_room_id_desc
    ON chat_messages (chat_room_id, id DESC);
