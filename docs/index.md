# Documentation Index

## 목적

이 문서는 `docs/` 전체의 목차이자 네비게이션이다. "어떤 주제를 어디서 보는가"를 한눈에 안내한다.
처음 합류한 팀원은 이 페이지에서 시작해 필요한 영역으로 이동한다.

> 안내: 이 저장소는 백엔드 base repository이며, `docs/` 문서는 **템플릿 상태**다. 프로젝트/스택이 확정되면 각 문서의 `TBD`를 실제 값으로 채운다.

---

## 빠른 시작 (Reading Path)

| 나는 누구인가 | 먼저 볼 문서 |
| --- | --- |
| 협업을 시작하는 사람 | [collaboration-convention](convention/collaboration-convention.md) → [branch-convention](convention/branch-convention.md) → [commit-convention](convention/commit-convention.md) |
| 처음 합류한 백엔드 개발자 | [project-brief](project/project-brief.md) → [system-overview](architecture/system-overview.md) → [code-style](convention/code-style.md) |
| API를 만드는 사람 | [api-design-guide](api/api-design-guide.md) → [error-response-guide](api/error-response-guide.md) → [user-stories](requirements/user-stories.md) → [api/specs](api/specs/README.md) |
| DB/마이그레이션 담당 | [database-design](database/database-design.md) → [migration-policy](database/migration-policy.md) |
| 기술 결정을 남기는 사람 | [adr/README](adr/README.md) → [0000-adr-template](adr/0000-adr-template.md) |

---

## 폴더별 안내

### project — 프로젝트 개요/맥락

| 문서 | 설명 |
| --- | --- |
| [project-brief](project/project-brief.md) | 프로젝트 목적, 범위(In/Out of scope), 핵심 기능, KPI, 마일스톤 |

### requirements — 요구사항 정의

| 문서 | 설명 |
| --- | --- |
| [user-story-template](requirements/user-story-template.md) | 유저 스토리 + 인수 조건(Given/When/Then) 템플릿 |
| [user-stories](requirements/user-stories.md) | 핵심 기능 7종의 백엔드 유저 스토리 + 인수 조건(AC) |
| [non-functional-requirements](requirements/non-functional-requirements.md) | 성능/가용성/보안 등 비기능 요구사항 |

### convention — 협업 컨벤션

| 문서 | 설명 |
| --- | --- |
| [collaboration-convention](convention/collaboration-convention.md) | Fork 기반 PR 워크플로우(브랜치 전략·git 플로우·PR 작성/리뷰 규칙·Ruleset) |
| [code-style](convention/code-style.md) | Java/Spring 코드 스타일(Gradle·Spotless·네이밍·모듈러 모놀리식+DDD 계층·DI) |
| [branch-convention](convention/branch-convention.md) | 이슈 기반 브랜치 네이밍·머지 전략 |
| [commit-convention](convention/commit-convention.md) | 커밋 메시지 규칙(Conventional Commits) |

### api — API 설계

| 문서 | 설명 |
| --- | --- |
| [api-design-guide](api/api-design-guide.md) | REST API 설계 가이드 |
| [error-response-guide](api/error-response-guide.md) | 에러 응답 표준 포맷 |
| [api/specs](api/specs/README.md) | 도메인별 API 상세 스펙(핵심 기능 7종) |

### architecture — 아키텍처

| 문서 | 설명 |
| --- | --- |
| [system-overview](architecture/system-overview.md) | 시스템 전체 구성도/컴포넌트 |
| [domain-model](architecture/domain-model.md) | 모듈별 **애그리거트 카탈로그**(루트·식별자·불변식·저장소·협력) — 전술적 도메인 정본 |
| [sequence-diagrams](architecture/sequence-diagrams/README.md) | 유저 스토리별 사용자→앱→백엔드 **모듈** 시퀀스 다이어그램(모듈 분해·이벤트/호출 구분, 53종) — 매물 등록은 [us-3-6-listing-registration](architecture/sequence-diagrams/03-listings-favorites/us-3-6-listing-registration.md), 임대인 매물 수정은 [us-3-9-landlord-listing-update](architecture/sequence-diagrams/03-listings-favorites/us-3-9-landlord-listing-update.md), 임대인 웹 가입·로그인·병합(US-1-11~US-1-13·US-1-15)과 계정 복구(이메일 찾기 US-1-16·비밀번호 재설정 US-1-17)는 [01-auth-onboarding](architecture/sequence-diagrams/01-auth-onboarding/README.md) |

### database — 데이터베이스

| 문서 | 설명 |
| --- | --- |
| [database-design](database/database-design.md) | 폴리글랏 데이터 모델 — 모듈별 스키마(MySQL ERD·MongoDB 컬렉션·Redis 키스페이스) |
| [migration-policy](database/migration-policy.md) | 마이그레이션 정책 — MySQL Flyway·MongoDB Mongock·Redis([ADR-0008](adr/0008-mysql-migration-flyway.md), [ADR-0032](adr/0032-mongodb-migration-runner.md)) |

### adr — 아키텍처 결정 기록

| 문서 | 설명 |
| --- | --- |
| [adr/README](adr/README.md) | ADR 인덱스와 작성 방법 |
| [0000-adr-template](adr/0000-adr-template.md) | ADR 작성 템플릿 |
| [0047-web-local-credentials-and-phone-based-account-linking](adr/0047-web-local-credentials-and-phone-based-account-linking.md) | 웹 로컬 자격증명(`local_accounts`)을 `users`에서 분리하고 휴대폰 번호로 앱·웹 계정을 한 `user_id`에 공유(연동·병합) |
| [0048-web-refresh-token-httponly-cookie](adr/0048-web-refresh-token-httponly-cookie.md) | 웹 refresh 토큰을 HttpOnly 쿠키 채널로 내리고 `reissue`·`logout`을 쿠키 우선·본문 fallback으로 읽는 결정 |

---

## 문서를 추가/수정할 때

- 새 문서를 만들면 위 표에 한 줄 설명과 상대링크를 추가한다.
- 기술 결정이 바뀌면 [adr/README](adr/README.md)에 ADR을 추가하고 관련 문서를 갱신한다.
- 코드/정책 변경 시 영향받는 문서를 함께 갱신한다.
