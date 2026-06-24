# US-2-2 — 진단 결과(추천 매물 + 지도 좌표) 조회

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant LIST as listing 모듈
    participant USER as user 모듈
    participant DB as MongoDB

    U->>C: 진단 결과 화면 진입
    C->>SEC: GET /api/v1/diagnoses/{diagnosisId}/recommendations<br/>?page=0&size=20&sort=recommended,desc<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    Note over DIAG: 진단 소유권 검증 후<br/>저장된 진단 조건 로드<br/>RecommendationCriteria 구성<br/>(지역·예산·conditions·대학/지역)
    DIAG->>DB: 진단 조회
    DB-->>DIAG: 저장된 진단 조건
    DIAG->>LIST: listing 공개 query 인터페이스 동기 호출<br/>recommendByCriteria(RecommendationCriteria)<br/>(ADR-0002 Decision 5)
    LIST->>DB: 조건(conditions 기반)에 맞는 매물 조회
    DB-->>LIST: 매칭 매물 + 좌표
    LIST-->>DIAG: 매물 요약(ListingSummaryResponse)+좌표
    Note over DIAG: 매칭 결과로 content·markers·page 집계
    alt 매칭 결과 있음
        DIAG-->>C: 200 OK<br/>data.content[] (ListingSummaryResponse),<br/>data.markers[] (listingId/lat/lng),<br/>data.page, suggestions null
        C-->>U: 매물 목록 + 지도 마커 표시
    else 매칭 0건(부산/경기·좁은 조건)
        DIAG->>USER: user 공개 query 동기 호출 getLanguage(userId)<br/>표시 언어 조회(user가 countries.lang으로 도출)
        USER-->>DIAG: 표시 언어 lang
        Note over DIAG: suggestions 구성 — reason·type은 enum(언어 무관),<br/>표시 언어로 diagnosisSuggestions 컬렉션의<br/>reason/type별 언어-키 맵에서<br/>사용자 언어 message·detail 선택<br/>(전용 컬렉션, 미지원=영어 en 폴백)
        DIAG-->>C: 200 OK<br/>content [], markers [],<br/>suggestions(reason NO_MATCH + message,<br/>actions[type + detail])
        C-->>U: 빈 결과 + (번역된) 완화 제안 표시
    end
```

## 흐름 요약

- `GET /api/v1/diagnoses/{diagnosisId}/recommendations`로 본인 진단 조건에 맞는 매물과 지도 좌표를 조회한다(공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 diagnosis 모듈로 전달하고, diagnosis 모듈이 소유권을 확인, 기본 정렬 `recommended,desc`).
- diagnosis 모듈이 MongoDB에서 진단 조건을 조회한 뒤 `RecommendationCriteria`(지역·예산·`conditions`·대학/지역) 값객체를 만들어 listing 모듈의 **공개 query 인터페이스를 동기 호출**한다(`recommendByCriteria` 류, ADR-0002 Decision 5 — 모듈 간 동기 query 협력). listing 내부 구현은 본 문서 범위가 아니며, listing 모듈은 진단 `conditions` 기반으로 MongoDB에서 매물을 조회해 매물 요약(`ListingSummaryResponse`)과 좌표를 반환하고 diagnosis 모듈이 결과를 집계한다.
- 결과가 있으면 `200 OK` + `content[]`(매물 요약 `ListingSummaryResponse`)·`markers[]`(listingId/lat/lng)·`page` 메타를, 0건이면 빈 목록 + `suggestions`(조정 제안)를 동일하게 `200 OK`로 반환한다. `suggestions`의 `reason`/`type`은 언어 무관 enum이고, 사람이 보는 `message`/`detail`은 `user` 공개 query(`getLanguage`)로 취득한 표시 언어(`user`가 `countries.lang`으로 도출)로 **서버가 제공**한다 — **MongoDB `diagnosisSuggestions` 컬렉션**의 `reason`/`type`별 인라인 언어-키 맵에서 사용자 언어 값을 골라 채우며, 해당 언어 키가 없으면 영어(`en`)로 폴백한다(US-2-6 일관). messageKey 평탄 컬렉션(`diagnosisMessages`) 방식은 쓰지 않는다(인라인 언어-키 맵 전용 컬렉션).
- 타인 진단은 `403 FORBIDDEN`, 없는 진단은 `404 DIAGNOSIS_NOT_FOUND`로 처리된다.
