# Architecture Decision Records

> 중요한 기술/아키텍처 결정을 기록하는 ADR 폴더입니다.

## 목적

> (작성) ADR로 무엇을·왜 기록하는지 적는다.

## 언제 ADR을 작성하는가

> (작성) ADR로 남길 결정의 기준(되돌리기 비용이 큰 결정 등)을 적는다.

## 상태(Status) 정의

> (작성) Proposed/Accepted/Deprecated/Superseded/Rejected 등 사용할 상태를 정의한다.

## 파일 네이밍 규칙

> 파일명: `docs/adr/NNNN-kebab-case-title.md` (NNNN은 4자리 일련번호).
> 새 ADR은 [0000-adr-template.md](./0000-adr-template.md)를 복사해 작성한다.

## ADR 인덱스

| 번호 | 제목 | 상태 | 날짜 |
| --- | --- | --- | --- |
| [0000](./0000-adr-template.md) | ADR 템플릿 | — (템플릿) | — |
| [0001](./0001-bounded-context-module-decomposition.md) | 도메인(Bounded Context) 기준으로 모듈을 분해한다 | Accepted | 2026-06-15 |
| [0002](./0002-inter-module-communication-via-events.md) | 모듈 간 통신은 도메인 이벤트(Application Events) 기반으로 한다 | Accepted | 2026-06-15 |
| [0003](./0003-jwt-auth-after-oauth-login.md) | OAuth(OIDC) 로그인 이후 인증은 서버 발급 JWT(stateless) 방식으로 한다 | Accepted | 2026-06-15 |
| [0004](./0004-api-response-envelope.md) | API 응답을 공통 래퍼(`{ success, data, error }`)로 표준화한다 | Accepted | 2026-06-15 |
| [0005](./0005-polyglot-persistence.md) | 영속은 폴리글랏으로 — 데이터 특성에 따라 MongoDB와 MySQL로 나눈다 | Accepted | 2026-06-15 |
| [0006](./0006-refresh-token-store-redis.md) | 리프레시 토큰 저장소는 Redis로 둔다(ADR-0005 보완) | Accepted | 2026-06-15 |
| [0007](./0007-api-docs-spring-rest-docs.md) | API 문서는 테스트 기반 Spring REST Docs로 생성한다(Swagger 대비) | Accepted | 2026-06-16 |
| [0008](./0008-mysql-migration-flyway.md) | MySQL 스키마 마이그레이션은 Flyway로 관리한다(폴리글랏 전략) | Accepted | 2026-06-16 |
| [0009](./0009-jwt-signing-algorithm-hs256.md) | 서버 JWT 서명은 HS256으로 한다(MSA/외부 검증자 시 RS256+JWKS 전환) | Accepted | 2026-06-16 |
| [0010](./0010-jwt-authentication-filter.md) | JWT 검증은 common의 횡단 보안 필터로 처리한다(Spring Security 필터 체인·인증 컨텍스트·보호 경로) | Accepted | 2026-06-17 |
| [0011](./0011-token-lifetime-and-secret-policy.md) | 토큰 수명(TTL)과 HS256 시크릿 정책값을 확정한다 | Accepted | 2026-06-17 |
| [0012](./0012-terms-version-management.md) | 약관 버전(termsVersion)은 서버 설정값을 정본으로 기록한다 | Accepted | 2026-06-17 |
| [0013](./0013-response-auto-wrapping.md) | 성공 응답은 ResponseBodyAdvice로 공통 래퍼를 자동 적용한다 | Accepted | 2026-06-17 |
| [0014](./0014-withdrawal-pii-anonymization.md) | 회원 탈퇴는 상태 전이 + PII 즉시 익명화로 처리한다 | Accepted | 2026-06-17 |
| [0015](./0015-sensitive-column-encryption.md) | 민감정보 컬럼 암호화는 MVP에서 도입하지 않고 마스킹·저장소 암호화로 갈음한다 | Accepted | 2026-06-17 |
| [0016](./0016-downgrade-to-spring-boot-3.md) | Spring Boot를 4.1에서 3.5로 다운그레이드한다(생태계·도구 호환성) | Accepted | 2026-06-17 |
| [0017](./0017-openapi-swagger-ui-from-restdocs.md) | 테스트 기반 OpenAPI(restdocs-api-spec)로 Swagger UI를 서빙한다(ADR-0007 확장) | Accepted | 2026-06-17 |
| [0018](./0018-documentdb-for-mongodb-on-aws.md) | MongoDB 호환 저장소는 Amazon DocumentDB로 운영한다(Atlas 대비, ADR-0005 후속) | Accepted | 2026-06-21 |
| [0019](./0019-infrastructure-as-code-terraform.md) | AWS 인프라를 Terraform(IaC)으로 관리한다 | Accepted | 2026-06-21 |
| [0020](./0020-terraform-remote-state-s3-dynamodb.md) | Terraform 원격 상태는 S3 + native lockfile로 둔다(DynamoDB 불요, ADR-0019 세부) | Accepted | 2026-06-21 |
| [0021](./0021-cost-optimization-profile.md) | dev 환경은 단일 EC2(docker-compose) 비용 최소화 구성으로 둔다(매니지드는 과투자) | Accepted | 2026-06-21 |
| [0022](./0022-dev-https-caddy.md) | dev HTTPS 종단은 Caddy로 한다(nginx+certbot 대비 갱신 자동화) | Accepted | 2026-06-21 |
| [0023](./0023-secrets-in-ssm-parameter-store.md) | 시크릿은 SSM Parameter Store SecureString에 둔다(Secrets Manager 미사용) | Accepted | 2026-06-21 |
| [0024](./0024-secret-change-propagation.md) | 변경된 시크릿은 배포(CI/CD)에서 재조회·재생성으로 반영한다(prod·dev 대칭, ADR-0023 후속) | Accepted | 2026-06-22 |
| [0025](./0025-dev-db-credential-reconcile.md) | dev 자가호스팅 DB 자격증명은 데이터 보존하며 reconcile로 회전한다(마커 기반, ADR-0021 후속) | Accepted | 2026-06-22 |
| [0026](./0026-dev-host-memory-budget.md) | dev 단일 호스트(t3.small·2GB) 메모리 예산을 스왑 + 엔진 캡으로 맞춘다(ADR-0021 후속) | Accepted | 2026-06-22 |
| [0027](./0027-dev-discord-alerting.md) | 알람 통보는 Discord 웹훅으로 한다(SNS→Lambda 포워더, dev·prod) | Accepted | 2026-06-22 |
| [0028](./0028-diagnosis-questions-catalog-store.md) | 진단 문항·선택지 카탈로그는 MongoDB diagnosisQuestions 컬렉션에 저장·제공한다 | Accepted | 2026-06-23 |
| [0029](./0029-diagnosis-i18n-strategy.md) | 진단 i18n은 서버가 등록 국가→언어 매핑으로 표시 라벨을 번역한다(코드 enum 매핑/리소스 번들 대비 DB 채택) | Accepted | 2026-06-23 |
| [0030](./0030-error-message-i18n-resource-bundle.md) | 에러 응답 메시지 i18n은 리소스 번들(Spring MessageSource)로 번역한다(Accept-Language·영어 폴백) | Accepted | 2026-06-24 |
| [0031](./0031-apple-sign-in-authorization-code-flow.md) | Apple 로그인은 authorization code 방식으로 전환해 탈퇴 시 토큰을 폐기한다(Google은 idToken 유지) | Accepted | 2026-06-28 |
| [0032](./0032-mongodb-migration-runner.md) | MongoDB 시드·문서 1회성 변경은 모듈별 Mongock @ChangeUnit으로 1회 자동 적용한다 | Accepted | 2026-06-29 |
| [0033](./0033-business-registry-verification.md) | 임대인 사업자등록번호는 온보딩과 분리된 무상태 API로 비즈노 조회·검증한다 | Proposed | 2026-06-30 |
| [0034](./0034-landlord-phone-sms-verification.md) | 임대인 연락처는 SMS 인증번호로 검증하고 이메일은 수집하지 않는다 | Proposed | 2026-06-30 |
| [0035](./0035-gamification-quiz-random-stateless-catalog.md) | 학습 퀴즈를 무상태 랜덤 4지선다로 재설계하고 문항 카탈로그를 MongoDB에 언어-키 맵으로 저장 | Proposed | 2026-07-02 |

> 새 ADR을 추가하면 이 표에 한 행을 추가한다.

## 체크리스트

> (작성) ADR 작성 시 확인할 항목을 적는다.
