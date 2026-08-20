-- V25 — 신청 저장 뒤 채팅방·BOOKING_CARD 처리가 누락되지 않도록 모듈 이벤트를 보관한다.
--
-- BookingCreatedEvent는 예약 저장 트랜잭션 안에서 이 테이블에 함께 기록된다. 예약은 커밋됐지만
-- 비동기 채팅 처리가 실패하거나 서버가 재시작되면 completion_date가 없는 행을 다시 전달할 수 있다.
-- listener가 성공하면 application.yml의 completion-mode=delete 정책에 따라 행을 지운다. 신청 카드의
-- 신청자 정보가 이벤트 로그에 불필요하게 오래 남지 않도록 완료 publication을 보관하지 않는 선택이다.
-- 저장소의 Hibernate 물리 이름 규칙이 엔티티의 EVENT_PUBLICATION을 소문자 event_publication으로 바꾸므로,
-- Linux MySQL의 대소문자 구분에서도 같은 테이블을 찾도록 실제 DDL 이름도 소문자로 맞춘다.
CREATE TABLE event_publication (
    -- Spring Modulith JPA가 UUID를 16바이트 이진값으로 저장하므로 VARCHAR(36)가 아니라 BINARY(16)을 사용한다.
    id               BINARY(16)    NOT NULL,
    publication_date DATETIME(6)   NOT NULL,
    listener_id      VARCHAR(255)  NOT NULL,
    serialized_event VARCHAR(4000) NOT NULL,
    event_type       VARCHAR(255)  NOT NULL,
    completion_date  DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 미완료 이벤트를 오래된 순서로 찾고 재처리하는 조회를 지원한다.
CREATE INDEX idx_event_publication_completion_date
    ON event_publication (completion_date, publication_date);
