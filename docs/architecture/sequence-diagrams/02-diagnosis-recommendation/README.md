# 시퀀스 다이어그램 — 맞춤 진단 & 매물 추천

> 사용자 → 앱(클라이언트) → 백엔드(서버) 흐름. 관련: [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)
>
> **게스트 접근**(#181): **`/api/v2/diagnoses/**`에만 `permitAll` 매처를 신규 추가**해 비회원에게 챗봇 진단과 그 매물 추천을 연다 — 진단은 현재 전용 매처가 없어 `anyRequest().authenticated()`로 떨어지므로 퀴즈·생활팁과 달리 줄을 **새로 넣어야** 하지만, 그 대상은 v2 하나다. **진단 조회 3종(`GET /api/v1/diagnoses`·`/latest`·`/{diagnosisId}`)은 회원 전용으로 유지**한다 — 매처를 추가하지 않아 비로그인 호출은 그대로 `401`이므로, [us-2-3](us-2-3-diagnosis-history.md)에는 게스트 분기가 없다. 게스트 신원은 합성 userId가 아니라 `userId == null`(부재)이고, 대화 연속성은 `POST /api/v2/diagnoses/start`가 발급해 클라이언트가 **`X-Guest-Session-Id` 헤더로 에코**하는 세션 키(`anonymous<uuid>`)로 잇는다. 비회원 진단의 정본 경로는 v2 서버 주도 흐름 [us-2-7](us-2-7-v2-server-driven-flow.md)이며, 게스트 설계 전반(세션 키·`guestSessionId` 필드·partial 인덱스·소유권 확장·미확정 항목)도 그 문서에 모아 두었다. **게스트 접근을 여는 데 필요한** `listing` 모듈 코드 변경은 0건이다(매물 스키마 v4 개편에 따른 `listing` 변경은 [ADR-0039](../../../adr/0039-listing-schema-v4-registration-form.md)로 별개다).

| 스토리 | 제목 | 다이어그램 |
| --- | --- | --- |
| US-2-2 | 진단 결과(추천 매물 + 지도 마커) 조회 | [us-2-2-recommendations](us-2-2-recommendations.md) |
| US-2-3 | 진단 이력 조회 및 최근 진단 다시 보기 | [us-2-3-diagnosis-history](us-2-3-diagnosis-history.md) |
| US-2-7 (v2) | 지역 매물 부재 시 재질의·종료 + 서버 주도 진단 흐름 | [us-2-7-v2-server-driven-flow](us-2-7-v2-server-driven-flow.md) |
