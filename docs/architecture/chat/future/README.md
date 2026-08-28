# 채팅 후속 고도화 설계

> 상태: 현재 구현 범위에서 분리된 후속 계획

이 폴더는 지금 바로 구현하지 않을 관리자 기능, 자동 보존·물리 삭제, iOS 채팅 푸시 기능을 모아 둔다. 현재 사용자 채팅 API와 사용자별 논리 삭제는 상위 [채팅 설계 문서](../README.md)를 따른다.

## 현재와 후속 범위

| 구분 | 현재 구현 | 후속 고도화 |
| --- | --- | --- |
| 사용자 삭제 | 내 목록과 과거 이력 숨김, 사용자 복원 없음 | 제한적인 관리자 복구와 삭제 후 3개월 만료 확정 |
| DB 메시지 | TEXT 원문·번역본·BOOKING_CARD payload 유지 | 양쪽 모두 만료된 공통 범위 물리 삭제 |
| 신고 | 사용자 접수와 최초 증거 저장 | 관리자 검토·처리·상태 이력 |
| 신고 보존 | 자동 정리하지 않음 | 처리 완료 후 보관 만료와 정리 작업 |
| 채팅 푸시 | STOMP 실시간 전달, 푸시 없음 | iOS FCM 기기 토큰, 새 TEXT 푸시, 채팅방 딥링크 |

중요하게, **3개월 뒤 DB에서 삭제하는 기능은 외부 REST API가 아니다.** 사용자가 버튼을 눌러 실행하는 것이 아니라 서버 내부의 정기 작업이 만료 데이터를 찾아 처리한다.

## 문서 구성

| 문서 | 내용 |
| --- | --- |
| [01-admin-report-management.md](01-admin-report-management.md) | 관리자 신고 API, 상태 처리, 완료 시각, 신고 보존 |
| [02-retention-and-physical-deletion.md](02-retention-and-physical-deletion.md) | 3개월 만료, 양쪽 공통 범위, 물리 삭제 batch |
| [03-sequence-diagrams.md](03-sequence-diagrams.md) | 후속 기능별 시퀀스 다이어그램과 쉬운 설명 |
| [04-testing-and-operations.md](04-testing-and-operations.md) | 후속 기능 테스트, 구현 순서, 운영 지표 |
| [05-admin-chat-room-recovery.md](05-admin-chat-room-recovery.md) | 관리자 전용 최근 삭제 복구와 감사 기록 |
| [06-chat-notifications.md](06-chat-notifications.md) | iOS FCM 기기 토큰, notification/data payload, 새 TEXT 푸시, 채팅방 딥링크와 단계별 구현 계획 |

## 구현 전 선행 조건

- 관리자 계정과 `ADMIN` 인증·인가 방식 확정
- 관리자 복구를 시작하기 전 삭제 직전 경계를 보존하는 snapshot schema 배포
- 신고 상태별 실제 운영 절차와 처리 결과 code 확정
- 3개월의 정책 기산점과 시행일 확정
- 신고·분쟁·법적 보존 hold 운영 주체와 해제 절차 확정
- 정기 작업 실패 재처리와 모니터링 준비
- iOS 앱의 `installationId` 생성·보관 방식과 FCM 토큰 등록·해제 시점 확정
- dev/prod Firebase project와 APNs key 운영 주체 확정

위 조건이 준비되기 전에는 상위 문서의 논리 삭제 데이터나 신고 evidence를 임의로 물리 삭제하지 않는다.
