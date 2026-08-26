# dev 환경 배포 (저비용 단일 EC2)

이 문서 하나로 AWS dev 환경과 채팅 자동 번역용 Google WIF를 `apply`까지 끝낸다. dev는 매니지드 서비스 대신 **EC2 1대 위 docker-compose**(Caddy·app·MySQL·MongoDB·Redis)로 도는 저비용 구성이다([ADR-0021](../../../../docs/adr/0021-cost-optimization-profile.md)).

**결과물**: 고정 IP(EIP)에 도메인을 붙여 **항상 HTTPS**로 접속되는 앱, 콘텐츠 이미지용 S3 + CloudFront CDN(커스텀 도메인), `release` 브랜치 머지(push) 시 자동 재배포되는 CI/CD 배포 역할.

- 상위 문서: [infra/terraform/README.md](../../README.md) (전체 아키텍처·prod 포함)
- 원격 상태 부트스트랩: [bootstrap/README.md](../../bootstrap/README.md)

prod와 **독립적**이다 — 이 디렉터리만 `apply` 하면 dev가 완결된다. 같은 AWS 계정에서 prod와 공유하는 것은 (a) 원격 state 버킷(`key` 로 분리), (b) GitHub OIDC provider, (c) ECR 리포지토리 이름뿐이다.

---

## 1. 완성 후 아키텍처 한눈에

```
모바일 앱 ──HTTPS(443, 항상)──▶ EIP(+도메인) ──▶ EC2 1대 (docker-compose)
임대인 웹(브라우저) ──HTTPS──────▶  (같은 도메인)      ├─ caddy:2     (TLS 종단·자동 HTTPS, ADR-0022)
                                                  │                 ├ /api·/ws/chat·/swagger-ui·/actuator → app
                                                  │                 └ 나머지 → /opt/kohere/web/current (SPA)
                                                  ├─ app         (Spring Boot · ${app_image})
                                                  ├─ mysql:8.0   (auth·user)        ┐ 데이터는
                                                  ├─ mongo:7     (listing·diagnosis)┤ 암호화 EBS /data
                                                  └─ redis:7     (refresh 토큰)     ┘ 에 영속
                                                  └─ 시크릿 ─────▶ SSM Parameter Store(부팅 시 .env 주입, ADR-0023)
                                                  └─ 채팅 번역 ──▶ AWS 역할 ──WIF──▶ Google 서비스 계정 ──▶ Translation API
앱 이미지:     백엔드 레포 Actions ──OIDC──▶ ECR(:dev 이동 태그) ──▶ SSM run-command로 EC2 재배포
임대인 웹:     프론트 레포 Actions ──OIDC──▶ S3(releases/<sha> 불변 + current.txt 포인터)
                                        ──▶ SSM(전용 Document)로 호스트가 내려받아 심볼릭 링크 원자 교체
콘텐츠 이미지: S3 ──OAC──▶ CloudFront(커스텀 도메인 별칭, us-east-1 ACM) ──▶ 클라이언트 직접 로드
```

루트(`environments/dev/`)가 배선하는 모듈:

| 모듈 | 역할 |
| --- | --- |
| `modules/dev/network` | 미니 VPC·IGW·public subnet |
| `modules/dev/security` | SG(80/443 + 옵션 DB 포트 3306/27017) |
| `modules/dev/iam` | 인스턴스 프로파일(SSM·ECR·파라미터·S3 이미지·S3 프론트 릴리스) |
| `modules/dev/google-wif` | 기존 `kohere-dev-host` 역할을 Google 단기 토큰으로 교환하고 Translation 서비스 계정만 가장하도록 제한 |
| `modules/dev/web` | 임대인 웹 릴리스 아티팩트 S3(비공개 — `releases/<sha>` 불변 + `current.txt` 포인터) — **항상 생성** |
| `modules/dev/secrets` | SSM Parameter Store SecureString(앱·DB 시크릿) |
| `modules/dev/storage` | 데이터 EBS(mysql/mongo 영속) |
| `modules/dev/host` | EC2 + EIP + EBS attach, user_data(compose·Caddyfile·WIF 설정·refresh-env·reconcile-db·deploy-web) |
| `modules/dev/dns` | Route53 A 레코드(domain→EIP) — **항상 생성**(domain·zone 필수) |
| `modules/dev/monitoring` | CloudWatch 알람 + SNS → Discord(`discord_webhook_url`, SNS→Lambda) |
| `modules/shared/s3-cloudfront` | 콘텐츠 이미지 S3 + CloudFront(OAC, 커스텀 도메인 별칭) — **항상 생성** |
| `cdn_acm`(루트 `main.tf`) | 이미지 CDN 커스텀 도메인용 us-east-1 ACM + DNS 검증 — **항상 생성** |

> 도메인/HTTPS/CDN은 옵션이 아니라 **필수**다. `cdn_acm`·`s3_cloudfront`·`dns` 모듈은 `count`/조건 없이 항상 인스턴스화되며 `cdn_domain_name`·`route53_zone_id`·`domain_name` 을 그대로 받는다. 따라서 `providers.tf` 의 `aws.us_east_1` provider(CloudFront용 ACM)도 **항상 사용**된다.

CI/CD 배포 역할(`github_deploy`)은 루트의 `cicd.tf` 가 만든다. GitHub OIDC provider는 계정당 1개라 **bootstrap이 단일 생성·소유**하며, `cicd.tf` 는 이를 `data` 로 조회만 한다 — 따라서 1단계 bootstrap apply가 선행돼야 한다.

---

## 2. 사전 준비

### 2.1 AWS 계정 생성

1. <https://aws.amazon.com/> 에서 루트 계정을 만든다(이메일·결제 카드 필요).
2. 로그인 후 **루트 계정에 MFA**(가상 MFA 앱 등)를 즉시 활성화한다.
3. **Billing**에서 결제·예산 알림을 설정해 둔다(dev는 월 ~$32 수준이지만 안전장치).

### 2.2 초기 IAM — 루트 직접 사용 금지

**루트 계정은 일상 작업(Terraform apply 포함)에 절대 쓰지 않는다.** 루트는 계정 생성·결제·MFA 설정 같은 최초 1회 작업에만 쓰고, 이후에는 관리자 권한 ID를 하나 만들어 그걸로 작업한다.

- **권장**: IAM Identity Center(SSO)로 관리자 권한 세트를 만들고 SSO 로그인 — 단기 자격증명이라 키가 디스크에 남지 않는다.
- **간단**: IAM 사용자 1명 생성 → 부트스트랩 단계에서만 `AdministratorAccess` 부여 → **액세스 키** 발급.

> **최소 권한**: 운영을 길게 할 거라면 `AdministratorAccess` 대신, 이 dev 스택이 실제로 만지는 서비스(EC2·VPC·EIP·EBS·IAM·SSM·S3·CloudFront·ACM·Route53·CloudWatch·SNS·STS)로 좁힌 정책으로 교체한다. GitHub OIDC provider와 배포 역할은 **Terraform이 생성**하므로 사람이 수동으로 만들거나 별도 액세스 키를 발급할 필요가 없다. CI는 OIDC 단기 자격증명으로만 동작하며 **장기 액세스 키를 저장하지 않는다**.

### 2.3 로컬 도구 설치

| 도구 | 버전 | 용도 |
| --- | --- | --- |
| Terraform | **>= 1.10.0** | 필수. S3 native lockfile(`use_lockfile`) 사용 |
| AWS CLI | v2 | 자격증명·ECR·SSM |
| Google Cloud CLI | 최신 | Google Terraform Provider용 관리자 ADC 준비 |
| git | 최신 | 저장소 |
| Docker | 최신 | 첫 앱 이미지 build/push(4단계) |

> Terraform 1.10 미만에서는 backend 구성(`use_lockfile`)이 동작하지 않는다.

**Windows**

```powershell
winget install Hashicorp.Terraform
winget install Amazon.AWSCLI
winget install Git.Git
winget install Docker.DockerDesktop
```

**macOS**

```bash
brew install terraform awscli git
brew install --cask gcloud-cli
brew install --cask docker
```

설치 확인:

```bash
terraform version   # Terraform v1.10.x 이상
aws --version       # aws-cli/2.x
gcloud version      # Google Cloud SDK
```

### 2.4 AWS CLI 자격증명 구성

SSO(권장):

```bash
aws configure sso
aws sso login
```

액세스 키 방식:

```bash
aws configure
#   AWS Access Key ID     : <발급한 키>
#   AWS Secret Access Key : <발급한 시크릿>
#   Default region name   : ap-northeast-2
#   Default output format : json
```

리전은 **`ap-northeast-2`(서울)** 로 맞춘다.

연결 확인:

```bash
aws sts get-caller-identity   # Account / Arn 이 보이면 OK
```

### 2.5 Google Terraform 관리자 인증

Terraform은 WIF Pool·API·IAM 바인딩을 만들기 때문에, 최초 apply를 실행하는 사람에게 Google 프로젝트 관리 권한이 필요하다. 여기서 사용하는 ADC는 **EC2 런타임 서비스 계정 인증과 별개**다.

```bash
# 프로젝트 Owner 또는 필요한 IAM 관리 역할을 가진 사람 계정으로 로그인한다.
# 런타임용 kohere 서비스 계정을 --impersonate-service-account로 지정하지 않는다.
gcloud auth application-default login
gcloud auth application-default set-quota-project project-bdb9704d-3952-475b-a1c
```

최소 권한으로 운영한다면 실행자에게 Service Usage Admin, Workload Identity Pool Admin, Service Account Admin과 프로젝트 IAM 변경 권한이 필요하다. 앱이 실제로 실행될 때는 이 사람 계정을 사용하지 않고, EC2의 `kohere-dev-host` 역할이 WIF를 통해 `kohere@...` 서비스 계정의 짧은 토큰만 발급받는다.

### 2.6 필수 입력 체크리스트

apply 전에 아래 7개는 반드시 준비한다. 모두 `default`가 없어 비우면 `plan` 단계에서 실패한다.

| 항목 | 변수 | 준비 방법 |
| --- | --- | --- |
| 앱 이미지 URI | `app_image` | 4단계에서 ECR 리포 생성 후 push할 `:dev` 태그 URI |
| 앱 도메인 | `domain_name` | 사용할 dev 도메인(예: `dev.kohere.app`) |
| Route53 호스팅 영역 ID | `route53_zone_id` | 해당 도메인의 **이미 존재하는** 호스팅 영역 Z-ID |
| 이미지 CDN 도메인 | `cdn_domain_name` | 이미지 서빙용 커스텀 도메인(예: `cdn.dev.kohere.app`) |
| 이미지 S3 버킷명 | `images_bucket_name` | 콘텐츠 이미지 버킷 이름(S3 전역 유일 — 직접 지정, 자동생성 없음) |
| Google 프로젝트 ID | `google_cloud_project_id` | Cloud Translation·Firebase와 WIF를 구성한 프로젝트 ID |
| 번역 서비스 계정 | `google_translation_service_account_email` | `kohere@...iam.gserviceaccount.com` 형식의 기존 서비스 계정 이메일 |

> **Route53 사전 조건(중요)**: `route53_zone_id` 가 필수이므로 apply 전에 그 호스팅 영역이 계정에 **이미 존재**하고, 도메인 등록·NS 위임이 끝나 있어야 한다. NS 위임이 안 끝났으면 `module.cdn_acm` 의 ACM **DNS 검증이 통과하지 못하고 무한 대기/타임아웃**한다.

---

## 3. 1단계: 원격 state 부트스트랩

dev state는 S3에 둔다. 그 버킷을 만드는 닭-달걀 절차는 [bootstrap/README.md](../../bootstrap/README.md)가 정본이다. **state 버킷이 아직 없는 상태에서 backend "s3" 블록을 활성화한 채 init하면 존재하지 않는 버킷을 가리켜 실패하므로**, 아래 순서를 지킨다. 요약:

```bash
# (1) 로컬 state로 버킷 생성 — backend.tf 의 backend "s3" 블록은 주석 처리 상태여야 한다
cd infra/terraform/bootstrap
terraform init
terraform apply -var="state_bucket_name=kohere-tfstate-<account_id>"   # state_bucket_name 은 필수(자동생성 없음)
#   → 지정한 버킷명을 기록한다 (예: kohere-tfstate-123456789012)

# (2) bootstrap 자기 state 를 방금 만든 S3 로 이전
#     backend.tf 의 주석을 풀고 bucket 을 위 출력값으로 채운 뒤:
terraform init -migrate-state
```

`state_bucket_name` 은 **자동생성되지 않으므로 직접 지정**해야 한다(S3는 전역 유일 — 예: `kohere-tfstate-<account_id>`). 잠금은 S3 native lockfile이라 DynamoDB 테이블은 만들지 않는다([ADR-0020](../../../../docs/adr/0020-terraform-remote-state-s3-dynamodb.md)). bootstrap은 **GitHub OIDC provider**와 **앱 이미지 ECR 리포지토리**(둘 다 dev·prod 공유)도 생성하므로, 4단계에서 ECR이 이미 존재하고 6단계 dev 배포 역할이 OIDC를 `data` 로 조회할 수 있다.

---

## 4. 2단계: dev 백엔드 연결

`environments/dev/backend.tf`에서 채울 곳은 **bucket 한 줄뿐**이다(나머지 `key`·`region`·`use_lockfile`·`encrypt`는 이미 채워져 있다).

```hcl
# infra/terraform/environments/dev/backend.tf
terraform {
  backend "s3" {
    bucket       = "REPLACE_WITH_BOOTSTRAP_STATE_BUCKET"  # ← 1단계 state_bucket_name 으로 교체
    key          = "dev/terraform.tfstate"
    region       = "ap-northeast-2"
    use_lockfile = true
    encrypt      = true
  }
}
```

교체 후 dev 디렉터리로 이동해 원격 백엔드를 연결한다(저장소 루트 기준 절대 경로 권장):

```bash
cd infra/terraform/environments/dev
terraform init -reconfigure
```

> `-migrate-state`(bootstrap 자기 state 이전)와 `-reconfigure`(dev 백엔드 연결)를 혼동하지 말 것. 세 환경(bootstrap/dev/prod)은 같은 버킷을 공유하고 `key` 로만 분리된다.

---

## 5. 3단계: 변수 설정

예시를 복사해 `terraform.tfvars` 를 만든다(`*.tfvars` 는 `.gitignore` 로 커밋에서 제외).

```bash
cp terraform.tfvars.example terraform.tfvars
```

`default`가 없는 필수 변수는 기존 AWS 5개와 Google 2개다. 하나라도 비우면 `terraform plan`이 실패한다.

- AWS: `app_image`, `domain_name`, `route53_zone_id`, `cdn_domain_name`, `images_bucket_name`
- Google: `google_cloud_project_id`, `google_translation_service_account_email`

**예시 `terraform.tfvars`** (`terraform.tfvars.example`과 동일하게 필수 값을 모두 채운다):

```hcl
aws_region = "ap-northeast-2"

# --- Google 채팅 자동 번역(필수, 비밀키 아님) ---
google_cloud_project_id                   = "project-bdb9704d-3952-475b-a1c"
google_translation_service_account_email = "kohere@project-bdb9704d-3952-475b-a1c.iam.gserviceaccount.com"
# Firebase도 같은 프로젝트와 WIF 서비스 계정을 사용한다. APNs·앱 토큰 준비 뒤 실제 발송 환경에서 켠다.
# firebase_enabled = true

# --- AWS 필수 5개 (default 없음) ---
# CI가 ECR에 push할 dev 태그 이미지 (account_id 는 본인 것으로)
app_image          = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/kohere-backend:dev"
# 앱 도메인 + HTTPS(Caddy 자동 인증서, ADR-0022). 항상 Route53 A 레코드(domain→EIP)가 생성된다.
domain_name        = "dev.kohere.app"
route53_zone_id    = "Z0123456789ABCDEFGHIJ"
# 콘텐츠 이미지 CDN 커스텀 도메인(us-east-1 ACM + CloudFront 별칭)
cdn_domain_name    = "cdn.dev.kohere.app"
# 콘텐츠 이미지 S3 버킷명(전역 유일 — 자동생성 없음)
images_bucket_name = "kohere-dev-images-123456789012"

# --- 외부 OIDC / SMTP 시크릿 (옵션, SSM SecureString 으로 저장) ---
google_client_id = "..."
apple_client_id  = "..."
smtp_host        = "email-smtp.ap-northeast-2.amazonaws.com"   # 미설정 시 이메일 인증 플로우 미동작
smtp_port        = 587
smtp_username    = "..."
smtp_password    = "..."
mail_from        = "noreply@kohere.app"

# --- 네이버 지역 검색 API (지도 장소 검색, 옵션 — SSM SecureString) ---
# 미설정 시 앱은 정상 기동하되 장소 검색(GET /api/v1/listings/places)만 502. 네이버 개발자센터에서 검색 API 사용 등록 후 발급.
naver_search_client_id     = "..."   # sensitive
naver_search_client_secret = "..."   # sensitive

# --- NCP Maps Geocoding (도로명 주소 검색, 옵션 — SSM SecureString) ---
# 미설정 시 앱은 정상 기동하되 주소 검색(GET /api/v1/listings/addresses)만 502. NCP 콘솔에서 Maps Application 등록 후 발급(위 검색 API와 다른 키).
naver_geocode_client_id     = "..."   # sensitive
naver_geocode_client_secret = "..."   # sensitive

# --- 카카오 로컬 API (인근 역 검색 · 인근 대학 파생, 옵션 — SSM SecureString) ---
# 미설정 시 앱은 정상 기동하되 역 검색(GET /api/v1/listings/stations)만 502이고 등록의 인근 대학이 빈 배열.
# 카카오 개발자 콘솔에서 앱 생성 → 로컬 API 활성화 후 REST API 키 발급(네이버·NCP와 다른 콘솔).
kakao_rest_api_key = "..."   # sensitive

# --- CI/CD (모두 default 있음) ---
github_org           = "swyp-app-5th-team1"
github_repo          = "Kohere-backend"
github_deploy_branch = "release"
```

**보안 주의 — 시크릿 / DB 외부 개방**

- `mysql_password`·`mysql_root_password`·`mongo_password` 의 기본값은 평문 `"kohere"` 다. **외부에 노출되는 환경이면 반드시 강한 값으로 덮어쓴다.** 값을 안 적으면 약한 자격증명이 그대로 SSM에 저장된다.
- `JWT_SECRET`·`REFRESH_PEPPER`·`EMAIL_PEPPER` 는 **Terraform이 자동 생성**하므로 입력 불필요.
- DB 포트(3306/27017) 외부 개방은 `db_ingress_cidrs` 로 제어한다. 기본값은 **빈 목록=미개방**(앱 내부 접속만). 개방이 필요하면 **본인 IP `/32` 로 제한**한다:
  ```hcl
  db_ingress_cidrs = ["203.0.113.7/32"]
  ```
  > `0.0.0.0/0`(전체 개방)을 막는 validation은 **없다** — Terraform이 막아주지 않으니 직접 좁혀야 한다. 약한 DB 비번 + 전체 개방 조합은 매우 위험하다.
- `ingress_cidrs`(80/443, app 접속)는 `db_ingress_cidrs` 와 별개이며 기본값이 `["0.0.0.0/0"]`(dev 편의상 전체 개방)다. 필요하면 사무실 IP 등으로 좁힌다.
- `images_bucket_name` 은 **필수**다(S3는 전역 유일 — 직접 지정, 자동생성 없음).

---

## 6. 4단계: 앱 이미지 (release 머지로 CI가 채운다)

**ECR 리포지토리는 bootstrap 이 만든다**(dev·prod 공유 자원 — 상태 버킷·OIDC와 동일하게 bootstrap 단일 소유). dev는 `ecr_repository`(기본 `kohere-backend`) **이름만 참조**한다.

**앱 이미지를 미리 push할 필요는 없다.** user_data는 공용 이미지(MySQL·MongoDB·Redis·Caddy)를 **항상 기동**하고, 앱 이미지(ECR `:dev`)는 있으면 띄우고 **없으면 건너뛴다**(best-effort). 그래서 이미지 없이 바로 apply해도 **인프라·DB는 정상**이며, `refresh-env.sh`(시크릿 .env 주입)·`reconcile-db.sh`(DB 초기화)도 그대로 돈다 — **app 컨테이너만 빠진 상태**다.

→ 그냥 **apply(7단계)** 하고, **6단계에서 `AWS_DEPLOY_ROLE_ARN` 을 설정한 뒤 `release` 브랜치에 머지**하면 CI가 이미지를 빌드·push + SSM 재배포해 app을 살린다(`gh workflow run deploy.yml` 로 수동 트리거도 가능). DB가 이미 떠 있으니 배포는 app만 추가한다.

### (선택) 지금 바로 app까지 띄우려면 — 첫 이미지 직접 push

CI를 기다리지 않고 즉시 app을 띄우고 싶을 때만:

```bash
# ECR 로그인 (<account_id> 는 aws sts get-caller-identity 의 Account)
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com
docker build -t <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/kohere-backend:dev .
docker push <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/kohere-backend:dev
```

> `:dev` 태그는 `tfvars` 의 `app_image` · `deploy.yml` 의 `DEV_IMAGE_TAG` 와 모두 같아야 한다. 불일치 시 compose가 다른 이미지를 본다.

---

## 7. 5단계: plan & apply

```bash
terraform plan
terraform apply
```

> 첫 apply는 `module.cdn_acm` 의 ACM DNS 검증 때문에 수 분 걸릴 수 있다. NS 위임이 완료된 호스팅 영역이어야 검증이 통과한다(2.6 사전 조건 참고).

주요 output:

| output | 의미 |
| --- | --- |
| `app_url` | 앱 접속 URL — **항상 `https://<domain_name>`** |
| `public_ip` | dev 호스트 EIP(Route53 A 레코드 대상) |
| `instance_id` | EC2 인스턴스 ID(SSM 접속용) |
| `google_wif_provider_name` | EC2 역할을 검증하는 Google WIF Provider 전체 이름 |
| `github_deploy_role_arn` | GitHub Actions가 assume할 배포 역할 ARN → 리포 Variables `AWS_DEPLOY_ROLE_ARN` 에 설정 |
| `images_bucket` | 콘텐츠 이미지 S3 버킷명 |
| `images_cdn_domain` | 이미지 서빙 커스텀 도메인(`cdn_domain_name` 별칭, 항상 설정됨) |
| `web_artifacts_bucket` | 임대인 웹 릴리스 버킷 → **프론트** 리포 Variables `WEB_ARTIFACTS_BUCKET` |
| `github_web_deploy_role_arn` | 프론트 Actions가 assume할 배포 역할 ARN → **프론트** 리포 Variables `AWS_DEPLOY_ROLE_ARN` |
| `ssm_deploy_web_document` | 릴리스 적용 SSM Document 이름 → **프론트** 리포 Variables `SSM_DEPLOY_WEB_DOCUMENT` |

```bash
terraform output github_deploy_role_arn   # 다음 단계에서 사용
```

### 7.1 설정 변경을 서버에 반영하는 법

`user_data`는 새 EC2가 **처음 부팅할 때만** 실행되고, `user_data_replace_on_change = false`라 내용이 바뀌어도 terraform이 인스턴스를 갈아치우지 않는다. 무엇을 바꿨는지에 따라 반영 방법이 갈린다.

| 바꾼 것 | 반영 방법 |
| --- | --- |
| SSM으로 주입되는 값 — `.env` 항목(`APP_WEB_BASE_URL`·`SMTP_USERNAME`·`SOLAPI_*` 등) | `terraform apply` 후 서버에서 `refresh-env.sh` 재실행 + app 컨테이너 recreate |
| 그 밖의 `user_data` 내용 — compose 환경변수(`MAIL_FROM`·`SPRING_MAIL_HOST`·`APP_FIREBASE_*`)·Caddyfile·마운트·스크립트 | **EC2 교체** |

```bash
terraform plan  -replace="module.host.aws_instance.host"   # destroy 대상이 EC2 하나인지 확인
terraform apply -replace="module.host.aws_instance.host"
```

교체해도 **MySQL·MongoDB 데이터와 Caddy 인증서는 EBS(`/data`)에 있어 살아남고**, EIP도 유지되므로 도메인은 그대로다. 반면 **Redis는 인메모리라 비워진다** — 모든 세션이 로그아웃되고 인증 마커·레이트리밋 카운터가 초기화된다. 부팅과 이미지 pull 동안 몇 분간 dev가 내려간다.

> 예전에는 교체 없이 compose를 1회 밀어넣는 SSM Document(`configure_chat_translation`)를 함께 두었으나, 그 스크립트가 compose를 base64로 통째로 품어 `user_data`가 16KB 한계를 넘기는 원인이 되어 제거했다(#274). 반영 경로는 위 두 가지로 통일한다.

---

## 8. 6단계: CI/CD 연결

apply가 만든 배포 역할 ARN을 GitHub 리포지토리 **Variables**(Secrets 아님 — Settings ▸ Secrets and variables ▸ Actions ▸ **Variables** 탭)에 넣는다. `deploy.yml` 이 참조하는 변수 전체:

| 변수 | 값(예시) | 비고 |
| --- | --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | `terraform output github_deploy_role_arn` 값 | **필수**. 비면 deploy job이 첫 스텝에서 에러로 실패한다 |
| `AWS_REGION` | `ap-northeast-2` | **필수**(기본값 없음) |
| `ECR_REPOSITORY` | `kohere-backend` | **필수**. `ecr_repository` 와 일치해야 함 |
| `DEV_IMAGE_TAG` | `dev` | **필수**. compose가 보는 이동 태그 |
| `DEV_HOST_NAME` | `kohere-dev-host` | **필수**. SSM 대상 EC2 Name 태그 |

```bash
gh variable set AWS_DEPLOY_ROLE_ARN --body "<role-arn>" --repo swyp-app-5th-team1/Kohere-backend
gh variable set AWS_REGION          --body "ap-northeast-2"   --repo swyp-app-5th-team1/Kohere-backend
gh variable set ECR_REPOSITORY      --body "kohere-backend"   --repo swyp-app-5th-team1/Kohere-backend
gh variable set DEV_IMAGE_TAG       --body "dev"              --repo swyp-app-5th-team1/Kohere-backend
gh variable set DEV_HOST_NAME       --body "kohere-dev-host"  --repo swyp-app-5th-team1/Kohere-backend
```

설정 후 `release` 브랜치 머지(또는 수동 `gh workflow run deploy.yml`)하면 배포된다: OIDC 자격증명 발급 → ECR push(`:dev`) → SSM run-command로 dev EC2에서 `ecr-login.sh`·`refresh-env.sh`·`reconcile-db.sh` 실행 후 `docker compose pull app` → `up -d --force-recreate app` → `docker image prune -f`.

```bash
gh workflow run deploy.yml --repo swyp-app-5th-team1/Kohere-backend
```

**GitHub OIDC provider 소유권**: provider는 계정당 1개라 **bootstrap이 단일 생성·소유**한다. dev `cicd.tf` 는 이를 `data` 로 **조회만** 하므로(별도 토글 변수 없음), **1단계 bootstrap apply가 선행**돼야 한다 — 안 돼 있으면 provider lookup이 실패한다. dev·prod는 같은 provider를 공유한다.

> 배포 역할 신뢰 정책은 `release` 브랜치 ref만 assume을 허용한다 — 다른 브랜치/PR에서는 거부된다(최소 권한·단기 자격증명).

### 8-1. 프론트엔드(임대인 웹) 리포 Variables

프론트는 **별도 역할**을 쓴다. 백엔드 역할에 레포를 한 줄 더 얹지 않은 이유는, 그러면 프론트 레포가 ECR push와 `AWS-RunShellScript`(호스트 root 임의 실행)까지 함께 얻기 때문이다. 프론트 역할이 호스트에서 할 수 있는 일은 **전용 SSM Document 하나**(= `deploy-web.sh`)뿐이다.

| 변수 | 값 | 비고 |
| --- | --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | `terraform output github_web_deploy_role_arn` | **필수**. 백엔드 것과 다른 ARN이다 |
| `AWS_REGION` | `ap-northeast-2` | **필수** |
| `WEB_ARTIFACTS_BUCKET` | `terraform output web_artifacts_bucket` | **필수**. 릴리스 업로드 대상 |
| `SSM_DEPLOY_WEB_DOCUMENT` | `terraform output ssm_deploy_web_document` | **필수**. 이 문서 외의 명령은 역할이 실행할 수 없다 |
| `DEV_HOST_NAME` | `kohere-dev-host` | **필수**. SSM 대상 EC2 Name 태그 |
| `DEV_URL` | `https://<domain_name>` | **필수**. 배포 후 스모크 체크 대상 |

`github_web_repo` 를 tfvars에 채워야 apply 된다(기본값 없음) — 이 값이 신뢰 정책의 `sub` 조건에 리터럴로 박히기 때문이다. 나중에 바꿔도 신뢰 정책만 in-place 갱신되고 역할 ARN은 그대로라, 프론트 리포 Variables를 다시 손댈 필요는 없다.

배포 흐름: `release` 머지 → build → `s3 sync dist/ s3://<bucket>/releases/<sha>/` → SSM(전용 Document)으로 호스트가 내려받아 `current` 심볼릭 링크 원자 교체 → `current.txt` 갱신. 워크플로는 **SSM 완료까지 폴링**해 실패 시 빨갛게 죽고, 이어서 루트·딥링크·API 스모크를 확인한다.

롤백은 이미 올라간 SHA를 수동 실행에 넣으면 된다 — 재빌드 없이 링크만 되돌아간다(보관 기간이 지나 아티팩트가 없으면 그 커밋을 재빌드해 복원한다).

---

## 9. 검증

```bash
# 1) 앱 접속 — app_url 은 항상 HTTPS
terraform output app_url            # https://dev.kohere.app
terraform output public_ip

# 2) 호스트 접속은 SSH가 아니라 SSM (포트 22 미개방)
aws ssm start-session --target $(terraform output -raw instance_id) --region ap-northeast-2

# --- 이하 SSM 세션 안에서 ---
# 3) 부팅 로그 확인
sudo cat /var/log/devhost-init.log

# 4) 컨테이너 상태 — app/mysql/mongo/redis/caddy 가 Up 이어야 함
cd /opt/kohere && sudo docker compose ps

# 5) WIF 파일과 번역·Firebase 환경변수 확인 — 파일 내용이나 토큰은 출력하지 않는다.
sudo test -r /opt/kohere/google-wif-credentials.json && echo "WIF 설정 파일 확인"
sudo docker inspect kohere-app --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E '^(GOOGLE_APPLICATION_CREDENTIALS|APP_CHAT_TRANSLATION_|APP_FIREBASE_)'
```

> `public_ip` 로 직접(`https://<IP>`) 접속하면 인증서가 도메인용이라 브라우저 경고가 뜬다 — 정상 접속 경로는 `app_url`(HTTPS) 이다. app만 빠져 있으면 보통 ECR에 `:dev` 이미지가 없는 것이다(6단계 대안 B). CI 배포를 한 번 돌리면 채워진다. SMTP(`smtp_host`)를 비워 둔 경우 이메일 인증 플로우는 dev에서 동작하지 않는다(dev엔 MailHog 등 로컬 SMTP가 없다). 마찬가지로 `naver_search_client_id`/`naver_search_client_secret` 를 비워 두면 지도 장소 검색(`GET /api/v1/listings/places`)이 502(`UPSTREAM_ERROR`)로 실패한다 — 키를 채우고 재배포하면 반영된다.

---

## 10. 운영 메모

- **시크릿**: SSM Parameter Store SecureString에 저장되고, 부팅 시 인스턴스 프로파일로 조회해 `/opt/kohere/.env`(0600)로 주입된다([ADR-0023](../../../../docs/adr/0023-secrets-in-ssm-parameter-store.md)). `tfvars` 의 시크릿만 바꿔 apply하면 SSM 파라미터만 갱신될 뿐 **실행 중 앱에는 자동 반영되지 않는다** — 배포 워크플로(`gh workflow run deploy.yml`)를 돌려야 반영된다([ADR-0024](../../../../docs/adr/0024-secret-change-propagation.md)).
- **DB 자격증명 회전**: 최초 init 이후 username/비번을 바꾸면 배포 시 `reconcile-db.sh` 가 EBS의 마커(`/data/.db-state-*`)를 근거로 **데이터를 보존한 채** live DB에 회전 적용한다([ADR-0025](../../../../docs/adr/0025-dev-db-credential-reconcile.md)).
- **비용**: 단일 EC2(t3.small 기본, 2vCPU/2GB) + EBS + EIP로 월 ~$17 수준([ADR-0021](../../../../docs/adr/0021-cost-optimization-profile.md)). prod 매니지드(~$370) 대비 저비용.
- **HTTPS**: `domain_name` 이 필수이므로 Caddy가 **항상** Let's Encrypt 인증서를 자동 발급·갱신한다([ADR-0022](../../../../docs/adr/0022-dev-https-caddy.md)). HTTP-only(:80) 모드는 코드상 존재하지 않는다.
- **시간**: 날짜/시간은 UTC 기준.
- **데이터 보존**: mysql/mongo 데이터가 들어있는 데이터 EBS 볼륨에는 `prevent_destroy = true`(`modules/dev/storage/main.tf`)가 걸려 있어, `terraform destroy` 가 그대로는 **에러로 전체 중단**된다(아래 정리 섹션 참고). 인스턴스를 교체해도 볼륨은 잔존한다. (참고: 볼륨 어태치먼트에 걸린 `skip_destroy = true` 는 볼륨 자체가 아니라 attach 관계만 보존하는 별개 설정이다.)

---

## 11. 트러블슈팅

| 증상 | 조치 |
| --- | --- |
| user_data 실패 / app 미기동 | `aws ssm start-session` 으로 접속 → `sudo cat /var/log/devhost-init.log` 확인 → `cd /opt/kohere && sudo docker compose ps`. 이미지 부재면 6단계, 시크릿이면 `sudo cat /opt/kohere/.env`(주의) 확인 후 재배포 |
| `terraform apply` 가 ACM 검증에서 멈춤/타임아웃 | `route53_zone_id` 호스팅 영역의 NS 위임 미완료. 도메인 등록·NS 위임을 끝낸 뒤 재시도(2.6 참고) |
| Google Provider가 `403`으로 WIF/API/IAM 생성을 거부 | 런타임 서비스 계정을 impersonate한 ADC가 아니라 프로젝트 관리 권한이 있는 사람 ADC로 `gcloud auth application-default login` 후 재시도 |
| 앱 번역 결과가 `GOOGLE_PERMISSION_DENIED` | SSM 1회 반영 성공 여부, 서비스 계정의 `roles/cloudtranslate.user`, WIF principalSet의 역할 이름이 `kohere-dev-host`인지 확인 |
| 컨테이너에서 EC2 자격증명을 읽지 못함 | `aws ec2 describe-instances`로 `HttpPutResponseHopLimit=2`인지 확인하고, Terraform apply가 끝났는지 확인 |
| `release` 브랜치 머지했는데 배포가 안 됨 | `AWS_DEPLOY_ROLE_ARN` Variable이 비면 deploy 런이 첫 스텝(`Verify required repo variable`)에서 **에러로 실패**한다(`::error::` 메시지). Variables 탭에 설정 |
| `cicd.tf` OIDC provider lookup 실패 | bootstrap이 OIDC provider를 아직 안 만들었다. **1단계 bootstrap을 먼저 apply** 하면 생성된다(provider는 bootstrap 단일 소유) |
| 디스크에 dangling 이미지 누적 | 배포는 `:dev` 이동 태그를 덮어써 옛 이미지가 dangling된다. 배포 워크플로가 `docker image prune -f` 로 정리하지만, 수동 정리는 SSM 세션에서 `sudo docker image prune -f` |
| DB에 외부 도구로 접속이 안 됨 | 기본은 미개방이다. `db_ingress_cidrs = ["<내 IP>/32"]` 추가 후 apply. 전체 개방(`0.0.0.0/0`)은 피한다 |
| `terraform destroy` 가 `prevent_destroy` 에러로 중단 | 의도된 데이터 보호다. 12절 절차 참고 |

---

## 12. 정리 (destroy)

데이터 EBS 볼륨에는 `prevent_destroy = true` 가 걸려 있어 `terraform destroy` 를 그대로 실행하면 **볼륨 리소스에서 에러가 나며 destroy 전체가 중단**된다(일부만 지워지고 조용히 건너뛰는 동작이 아니다).

**(A) 데이터는 보존하고 나머지만 정리** — 데이터 볼륨을 state에서 분리한 뒤 destroy:

```bash
# 볼륨을 Terraform 관리에서 떼어낸다(실제 AWS 볼륨은 남는다)
terraform state rm 'module.storage.aws_ebs_volume.data'
terraform destroy
# 남은 EBS 볼륨은 보존됨(나중에 재사용/수동 삭제)
```

**(B) 데이터까지 완전 삭제** — `modules/dev/storage/main.tf` 의 `lifecycle { prevent_destroy = true }` 블록을 제거(또는 (A)처럼 `state rm` 후)하고 destroy한 다음, 분리된 볼륨은 콘솔/CLI로 직접 지운다(되돌릴 수 없으니 주의):

```bash
terraform destroy
# (state rm 으로 분리했다면) 남은 볼륨을 직접 삭제
aws ec2 delete-volume --volume-id <vol-id> --region ap-northeast-2
```

기타 주의:

- 원격 **state 버킷**(`bootstrap/`)은 이 destroy 대상이 아니다 — 여러 환경이 공유하므로 그대로 둔다.
- ECR 리포지토리/이미지도 dev destroy 대상이 아니다(bootstrap 소유·prod와 공유).
- **콘텐츠 이미지 S3 버킷**(`module.s3_cloudfront`)은 destroy 대상이지만 `force_destroy` 가 없어 객체가 남아 있으면 `BucketNotEmpty` 로 실패한다. 앱이 매물 사진을 이 버킷에 올리므로([ADR-0041](../../../../docs/adr/0041-listing-image-upload-to-s3.md)) 먼저 비운다:

  ```bash
  BUCKET=$(terraform output -raw images_bucket)
  aws s3 rm "s3://$BUCKET" --recursive
  ```

  버전 관리를 켠 적이 있는 버킷이면(모듈 기본값은 이제 `enable_versioning = false` 지만 예전 기본값은 `true` 였다) 위 명령이 이전 버전·삭제 마커를 남긴다. `aws s3api list-object-versions` 로 뽑아 `aws s3api delete-objects` 로 지워야 destroy가 통과한다.

---

## 13. 참고 (ADR)

- [ADR-0019 Infrastructure as Code (Terraform)](../../../../docs/adr/0019-infrastructure-as-code-terraform.md)
- [ADR-0020 원격 상태 S3 + native lockfile](../../../../docs/adr/0020-terraform-remote-state-s3-dynamodb.md)
- [ADR-0021 dev 비용 최적화(단일 EC2 compose)](../../../../docs/adr/0021-cost-optimization-profile.md)
- [ADR-0022 dev HTTPS 종단(Caddy)](../../../../docs/adr/0022-dev-https-caddy.md)
- [ADR-0023 시크릿 SSM Parameter Store(SecureString)](../../../../docs/adr/0023-secrets-in-ssm-parameter-store.md)
- [ADR-0024 시크릿 변경 전파(배포 시 재조회·재생성)](../../../../docs/adr/0024-secret-change-propagation.md)
- [ADR-0025 dev DB 자격증명 reconcile](../../../../docs/adr/0025-dev-db-credential-reconcile.md)
