# US-7-1 — 콘텐츠 신고 접수

> 모듈: 신고 처리 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/07-reports.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant REPORT as report 모듈
    participant TGT as community/chat 모듈(신고 대상 보유)
    participant SQL as MySQL
    participant XDB as 저장소(추후 결정)

    U->>C: 게시글/댓글/메시지 신고(사유 선택)
    C->>SEC: POST /api/v1/reports<br/>Authorization: Bearer accessToken<br/>targetType=POST, targetId=101,<br/>reason=SPAM, detail=광고 링크 도배
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/위조
        SEC-->>C: 401 UNAUTHENTICATED
    else 토큰 만료
        SEC-->>C: 401 TOKEN_EXPIRED
    else 토큰 유효
        SEC->>REPORT: 인증된 요청 전달 (userId)
        Note over REPORT: 검증 순서<br/>레이트리밋(429) → 입력 검증(400) →<br/>대상 존재(404) →<br/>참여 권한·자기 신고(403/422) → 중복(409)
        REPORT->>TGT: 신고 대상 존재/참여 권한 조회<br/>(targetType, targetId)
        TGT->>SQL: 대상 존재 조회 (POST·댓글 = community)<br/>(targetType, targetId)
        SQL-->>TGT: 대상 레코드
        Note over TGT,XDB: 대상이 MESSAGE면 chat의 저장소(추후 결정)에서<br/>대상·채팅방 참여 여부 조회
        TGT-->>REPORT: 존재 여부·채팅방 참여 여부
        alt MESSAGE 신고이고 채팅방 미참여
            REPORT-->>C: 403 FORBIDDEN<br/>error.code=FORBIDDEN
        else 정상 접수
            Note over REPORT: (reporterId,targetType,targetId)<br/>유니크 제약으로 1건 저장<br/>status=RECEIVED 고정
            REPORT->>XDB: 신고 저장<br/>(reporterId,targetType,targetId) 유니크,<br/>status=RECEIVED
            XDB-->>REPORT: reportId=9001, createdAt(UTC ISO-8601)
            REPORT-->>C: 201 Created<br/>reportId=9001, targetType=POST,<br/>targetId=101, reason=SPAM,<br/>status=RECEIVED, createdAt(UTC ISO-8601)
            C-->>U: 신고가 접수되었습니다
        end
    end
```

## 흐름 요약

- 사용자가 콘텐츠와 사유를 선택하면 앱은 `POST /api/v1/reports`로 `targetType`/`targetId`/`reason`을 전송하며, `공통 보안 필터(SEC)`가 컨트롤러 앞단에서 JWT(서명·만료·클레임)를 검증한 뒤 `userId`와 함께 `report 모듈`로 전달한다(토큰 없음/위조 시 `401 UNAUTHENTICATED`, 만료 시 `401 TOKEN_EXPIRED`로 필터가 차단).
- `report 모듈`은 레이트리밋→입력 검증→대상 존재→참여 권한·자기 신고→중복 순으로 평가하며, 대상 존재·참여 권한 검증은 대상을 보유한 모듈이 자기 저장소에서 확인한다 — 게시글·댓글 대상은 `community`가 **MySQL**에서, 메시지 대상은 `chat`이 **저장소(추후 결정)**에서 조회하며, `MESSAGE` 신고는 채팅방 참여자가 아니면 `403 FORBIDDEN`을 반환한다.
- 정상 접수 시 `report 모듈`이 `저장소(추후 결정)`에 `(reporterId, targetType, targetId)` 유니크 제약으로 1건만 저장하고 `status=RECEIVED`로 `201 Created`를 응답한다(응답에 `reporterId`/`detail` 미노출).
