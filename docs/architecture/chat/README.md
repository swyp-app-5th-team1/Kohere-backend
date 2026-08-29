# Kohere 1:1 채팅 설계 문서

> 작성일: 2026-08-19
>
> 최종 수정: 2026-08-29
>
> 상태: 단계별 구현 진행 중
>
> 현재 구현: 채팅방 생성·조회·사용자별 삭제·상대 차단, 메시지 이력, STOMP 인증·구독, TEXT·INQUIRY_CARD·BOOKING_CARD 실시간 전달, iOS FCM 기기 등록·삭제와 채팅 푸시 발송 코드

이 폴더는 Kohere의 세입자·임대인 1:1 채팅 기능을 구현하기 위한 기준 문서다. 기존의 긴 단일 문서를 책임별로 나눴으며, 같은 내용을 여러 파일에 반복하지 않는다.

기존 문서에 남아 있는 읽음 처리, 1,000자 제한, 메시지 단위 신고가 이 폴더의 결정과 충돌하면 이번 채팅 구현에서는 이 폴더의 문서를 우선한다. 기존 문서와 실제 코드는 구현 단계에서 별도로 정합화한다.

채팅 번역은 원문 메시지와 분리한다. MySQL에 저장한 원문이 항상 정본이며, Google Cloud Translation으로 만든 사용자 언어별 번역본은 화면 표시를 돕는 파생 데이터다.

## 문서 구성

| 문서 | 내용 |
| --- | --- |
| [01-scope-and-architecture.md](01-scope-and-architecture.md) | 제품 범위, 표시 정책, 쉬운 용어, 전체 구조, 기존 코드 재사용 |
| [02-api-contracts.md](02-api-contracts.md) | REST API와 STOMP 경로, 주요 요청·응답 계약 |
| [03-websocket-stomp.md](03-websocket-stomp.md) | WebSocket/STOMP 인증, 구독, 전송, 재연결, 브로커 |
| [04-feature-flows.md](04-feature-flows.md) | 문의·신청·메시지·자동 번역·삭제·차단·신고 기능 흐름 |
| [05-sequence-diagrams.md](05-sequence-diagrams.md) | 기능별 Mermaid 시퀀스 다이어그램 |
| [06-data-model-and-retention.md](06-data-model-and-retention.md) | 현재 구현할 원문·번역·사용자별 숨김·신고 접수 데이터 모델 |
| [07-security-and-concurrency.md](07-security-and-concurrency.md) | 권한, 트랜잭션, 동시성, 외부 번역 API, 오류, 로깅 |
| [08-testing-and-implementation.md](08-testing-and-implementation.md) | 클라이언트 동작, 테스트, 단계별 개발 계획, 운영 전환 기준 |
| [09-frontend-stomp-guide.md](09-frontend-stomp-guide.md) | 프론트엔드용 WebSocket/STOMP 연결·구독·전송·ACK·재연결 안내 |
| [10-inquiry-card-frontend-guide.md](10-inquiry-card-frontend-guide.md) | 프론트엔드용 문의하기·INQUIRY_CARD 응답·UI·View Detail 연동 안내 |
| [11-frontend-push-notification-guide.md](11-frontend-push-notification-guide.md) | iOS 프론트엔드용 FCM 토큰 등록·갱신·로그아웃 삭제·푸시 클릭 채팅방 이동 안내 |
| [future/README.md](future/README.md) | 관리자 신고 처리·삭제 복구, 3개월 만료, 물리 삭제, iOS 채팅 푸시 등 후속 고도화 설계 |

### 문서의 역할과 우선순위

- 제품 범위와 화면 표시 정책은 `01-scope-and-architecture.md`가 정본이다.
- 외부 REST 계약은 `02-api-contracts.md`가 정본이다.
- 실시간 protocol 계약은 `03-websocket-stomp.md`가 정본이다.
- 현재 저장·사용자별 숨김 규칙은 `06-data-model-and-retention.md`가 정본이다.
- 관리자 처리와 자동 보존 만료·물리 삭제 규칙은 `future/` 문서가 정본이다.
- iOS 채팅 푸시의 기기 토큰·FCM payload·딥링크 계약은 `future/06-chat-notifications.md`가 정본이다.
- 공통 보안·동시성 규칙은 `07-security-and-concurrency.md`가 정본이다.
- `04-feature-flows.md`와 `05-sequence-diagrams.md`는 위 정본을 쉽게 설명하는 자료이며 새 정책을 선언하지 않는다.

## 핵심 결정

| 항목 | 결정 |
| --- | --- |
| 사용자 | 로그인과 온보딩을 완료한 `ROLE_USER` |
| 채팅 형태 | 세입자와 임대인의 1:1 채팅만 지원 |
| 채팅방 식별 | `(listingId, tenantId, landlordId)`마다 한 방 |
| 문의·신청 | 두 진입점 모두 같은 방을 사용 |
| 메시지 종류 | 사용자가 보내는 `TEXT`, 문의하기 시 서버가 필요 여부를 판단해 만드는 `INQUIRY_CARD`, 신청 완료 때 서버가 만드는 `BOOKING_CARD` |
| 신청 카드 | 기존 Booking 상세 데이터 조립 로직을 재사용하고 `bookingId`당 한 번만 같은 방에 저장 |
| 저장소 | MySQL이 채팅 기록의 정본 |
| 실시간 통신 | WebSocket 위에서 STOMP 사용 |
| 현재 브로커 | 단일 EC2·단일 JVM의 Spring Simple Broker |
| 사용자 메시지 | `TEXT`만 지원, 공백·줄바꿈 포함 Unicode code point 최대 3,000자 |
| 재전송 중복 | 클라이언트 UUID `clientMessageId`와 MySQL UNIQUE로 방지 |
| 자동 번역 | 받은 `TEXT`를 로그인 사용자의 표시 언어로 번역하며 현재 `ko/en`만 지원 |
| 번역 표시 | 받은 메시지는 번역본 우선, 원문 보기 제공; 내가 보낸 메시지는 원문 우선 |
| 번역 처리 | 원문 저장 후 Google Cloud Translation Advanced v3로 비동기 처리 |
| 읽음 기능 | 읽음 표시와 안 읽은 메시지 수는 후속 구현 |
| 삭제 | 요청자에게만 채팅방과 기존 이력을 숨기며 사용자 복원 기능은 제공하지 않음 |
| 차단 | 이전 대화는 유지하고 이후 양방향 전송만 차단 |
| 신고 | 개별 메시지가 아닌 채팅방과 상대 사용자를 신고 |
| 신고 입력 | 고정 사유 한 개만 선택하고 상세 사유는 받지 않음 |

## 이번 범위에서 제외

- 그룹 채팅
- 이미지·영상·파일 메시지
- 일반 `LISTING_CARD`, `SYSTEM` 메시지 (문의 전용 `INQUIRY_CARD`와 신청 전용 `BOOKING_CARD`는 범위에 포함)
- 읽음 위치, 안 읽은 메시지 수, 숫자 1 표시
- 메시지 수정·개별 삭제·전송 취소
- 사용자가 삭제한 채팅방의 Undo·복원
- 타이핑 중·접속 중 표시
- 채팅방 자동 시간 만료
- dev Firebase/APNs 연결과 실제 iPhone의 background·종료 상태 채팅 푸시·딥링크 E2E 검증 ([iOS 채팅 푸시 설계](future/06-chat-notifications.md))
- 다중 서버용 외부 broker relay
- 백업 데이터 파기 자동화
- 번역문 직접 수정과 사용자별 번역 용어 설정
- 언어 변경 전 과거 메시지의 자동 일괄 재번역
- 관리자 신고 목록·상세·상태 변경 API
- 관리자 전용 채팅방 삭제 복구 API
- 삭제 후 3개월 만료 확정과 메시지·방의 물리 삭제 작업
- 신고 처리 완료 후 보관 만료·물리 삭제 작업

위 관리자·보존 항목은 폐기한 요구가 아니라 [후속 고도화 문서](future/README.md)로 분리한 요구다. 특히 “3개월 후 DB에서 삭제”는 사용자가 호출하는 REST API가 아니라 서버가 정기적으로 실행할 내부 작업이다.

## 읽기 순서

- 제품 흐름을 이해하려면 `01 → 04 → 05` 순서로 읽는다.
- API를 연동하려면 `02 → 03`을 읽는다.
- 프론트에서 실시간 채팅을 연동하려면 `09`를 먼저 읽고 세부 계약은 `02 → 03`에서 확인한다.
- 프론트에서 문의서 카드만 연동하려면 `10`을 먼저 읽고 전체 실시간 연결은 `09`에서 확인한다.
- iOS에서 FCM 기기 등록과 채팅 푸시 딥링크를 연동하려면 `11`을 먼저 읽고 세부 백엔드 계획은 `future/06`에서 확인한다.
- 백엔드를 구현하려면 `01 → 02 → 03 → 06 → 07 → 08` 순서로 읽는다.
- 관리자 처리와 물리 삭제를 고도화할 때는 `future/README.md`부터 읽는다.
- iOS 프론트에서 채팅 푸시를 연동할 때는 `11-frontend-push-notification-guide.md`를 먼저 읽고, 백엔드 발송을 구현할 때는 `future/06-chat-notifications.md`를 읽는다.
