# 후속 고도화 시퀀스 다이어그램

이 파일도 **다이어그램 하나에 하나의 시나리오만 표현한다.** 현재 구현 시퀀스는 [상위 문서](../05-sequence-diagrams.md)를 따른다.

## 한 사용자의 3개월 삭제 기한이 끝나기

```mermaid
sequenceDiagram
    participant JOB as 정기 삭제 작업
    participant DB as MySQL
    actor U as 삭제 사용자
    actor O as 상대방

    JOB->>DB: 삭제 후 3개월이 지난 사용자 조회
    DB-->>JOB: 만료된 사용자별 삭제 범위
    JOB->>DB: 해당 사용자의 물리 삭제 후보 경계 확정
    Note over U,DB: 삭제 사용자는 이 범위를 다시 볼 수 없음
    Note over O,DB: 상대방이 보는 메시지는 DB에 계속 유지
```

**쉽게 설명하면:** 사용자가 호출하는 API가 아니라 서버 정기 작업의 흐름이다. 3개월이 지나면 해당 사용자의 삭제 범위를 물리 삭제 후보로 확정하지만, 상대방이 아직 보는 메시지는 지우지 않는다.

**결과:** 한쪽의 3개월 만료만으로 공유 메시지를 물리 삭제하지 않는다.

## 양쪽 모두 버린 메시지를 물리 삭제하기

```mermaid
sequenceDiagram
    participant JOB as 정기 삭제 작업
    participant DB as MySQL

    JOB->>DB: 양쪽 모두 3개월 만료된 공통 범위 계산
    DB-->>JOB: 물리 삭제 가능한 messageId 상한
    JOB->>DB: 진행 중인 신고·분쟁 hold 확인

    alt 별도 보존 사유 없음
        JOB->>DB: 번역본과 원문 메시지 실제 삭제
    else 보존 사유 있음
        Note over JOB,DB: hold가 끝날 때까지 삭제 보류
    end
```

**쉽게 설명하면:** 서버는 두 사용자가 모두 버렸고 각자의 3개월 기한도 지난 공통 메시지만 계산한다. 신고나 분쟁 때문에 보관해야 하면 지우지 않고 다음 정리 작업으로 미룬다.

**결과:** 양쪽 모두 필요 없고 별도 보존 사유도 없는 데이터만 MySQL에서 삭제한다.

## 운영자가 신고를 처리하기

```mermaid
sequenceDiagram
    actor ADMIN as 운영자
    participant REPORT as Report Application
    participant DB as MySQL

    ADMIN->>REPORT: 신고 검토 시작
    REPORT->>DB: 검토 중 상태와 처리 이력 저장
    DB-->>REPORT: 저장 완료
    ADMIN->>REPORT: 최종 처리 결과 입력
    REPORT->>DB: 최종 상태·완료 시각·보관 만료일 저장
    DB-->>REPORT: COMMIT
    REPORT-->>ADMIN: 처리 완료
```

**쉽게 설명하면:** 운영자가 접수된 신고를 보고 검토 상태와 최종 결과를 기록하는 관리자 전용 흐름이다. 일반 사용자의 신고 접수 API와는 별개이며 관리자 인증이 준비된 뒤 구현한다.

**결과:** 신고 처리 결과, 완료 시각과 이후 자료 정리에 사용할 만료일이 기록된다.

## 보관기간이 끝난 신고 자료 정리하기

```mermaid
sequenceDiagram
    participant JOB as 신고 자료 정리 작업
    participant DB as MySQL

    JOB->>DB: 처리 완료 후 보관기간이 지난 신고 조회
    DB-->>JOB: 만료된 신고 목록
    JOB->>DB: 법적·분쟁 hold 확인

    alt 별도 보존 사유 없음
        JOB->>DB: 신고 증거·처리 이력·신고 자료 삭제
    else 별도 보존 사유 있음
        Note over JOB,DB: hold가 끝날 때까지 삭제 보류
    end
```

**쉽게 설명하면:** 이것도 사용자가 호출하는 API가 아니라 서버 정기 작업이다. 보관 만료일이 지났더라도 법적 분쟁 등 추가 보존 사유가 있으면 자료를 유지한다.

**결과:** 만료일이 지났고 별도 hold가 없는 신고 자료만 삭제한다.
