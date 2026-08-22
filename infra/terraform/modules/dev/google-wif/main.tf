# EC2의 인스턴스 프로파일 자격증명을 Google 단기 토큰으로 교환하는 Workload Identity Federation 구성.
# 서비스 계정 JSON 키를 EC2에 저장하지 않고, 기존 kohere-dev-host 역할만 번역 서비스 계정을 가장할 수 있게 한다.

data "google_project" "current" {
  project_id = var.google_project_id

  # Resource Manager API가 꺼진 새 프로젝트에서도 먼저 API를 켠 뒤 프로젝트 번호를 조회한다.
  depends_on = [google_project_service.required]
}

locals {
  # WIF와 서비스 계정 가장, 번역 호출에 필요한 API만 활성화한다.
  # destroy 시 API를 다시 끄면 같은 프로젝트의 다른 워크로드가 영향을 받을 수 있으므로 disable_on_destroy=false로 둔다.
  required_services = toset([
    "cloudresourcemanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
    "translate.googleapis.com",
  ])

  # 공식 AWS attribute mapping대로 assumed-role ARN에서 역할 이름을 추출한다.
  # IAM 바인딩도 이 attribute.aws_role 값을 사용하므로 kohere-dev-host 이외의 역할은 서비스 계정을 가장할 수 없다.
  aws_role_principal_set = "principalSet://iam.googleapis.com/projects/${data.google_project.current.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.aws.workload_identity_pool_id}/attribute.aws_role/${var.aws_role_name}"
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.google_project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_iam_workload_identity_pool" "aws" {
  project                   = var.google_project_id
  workload_identity_pool_id = var.workload_identity_pool_id
  display_name              = "Kohere dev AWS workloads"
  description               = "Kohere dev EC2가 서비스 계정 키 없이 Google Cloud API를 호출하기 위한 WIF Pool"
  disabled                  = false

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "aws" {
  project                            = var.google_project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.aws.workload_identity_pool_id
  workload_identity_pool_provider_id = var.workload_identity_provider_id
  display_name                       = "Kohere dev EC2"
  description                        = "AWS ${var.aws_account_id}의 ${var.aws_role_name} 역할만 허용하는 WIF Provider"

  # 계정 ID는 AWS provider 자체에서도 검증하고, attribute_condition에서도 역할 ARN까지 다시 제한한다.
  aws {
    account_id = var.aws_account_id
  }

  attribute_mapping = {
    "google.subject"             = "assertion.arn"
    "attribute.aws_account"      = "assertion.account"
    "attribute.aws_role"         = "assertion.arn.extract('assumed-role/{role_name}/')"
    "attribute.aws_ec2_instance" = "assertion.arn.extract('assumed-role/{role_and_session}').extract('/{session}')"
  }

  attribute_condition = "assertion.account == '${var.aws_account_id}' && assertion.arn.startsWith('arn:aws:sts::${var.aws_account_id}:assumed-role/${var.aws_role_name}/')"
}

# WIF에서 확인된 kohere-dev-host 역할만 기존 서비스 계정의 짧은 액세스 토큰을 발급받을 수 있다.
resource "google_service_account_iam_member" "workload_identity_user" {
  service_account_id = "projects/${var.google_project_id}/serviceAccounts/${var.translation_service_account_email}"
  role               = "roles/iam.workloadIdentityUser"
  member             = local.aws_role_principal_set

  depends_on = [
    google_project_service.required,
    google_iam_workload_identity_pool_provider.aws,
  ]
}

# 가장된 서비스 계정에는 번역 호출 권한만 준다. 관리자 권한이나 서비스 계정 키 생성 권한은 주지 않는다.
resource "google_project_iam_member" "translation_user" {
  project = var.google_project_id
  role    = "roles/cloudtranslate.user"
  member  = "serviceAccount:${var.translation_service_account_email}"

  depends_on = [google_project_service.required]
}

locals {
  # Google 공식 create-cred-config --aws --enable-imdsv2 결과와 같은 external_account 설정이다.
  # 개인키가 아니라 "어디에서 AWS 임시 자격증명을 읽고 어떤 서비스 계정을 가장할지"만 적힌 공개 설정이다.
  credential_configuration = jsonencode({
    type                              = "external_account"
    audience                          = "//iam.googleapis.com/${google_iam_workload_identity_pool_provider.aws.name}"
    subject_token_type                = "urn:ietf:params:aws:token-type:aws4_request"
    service_account_impersonation_url = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${var.translation_service_account_email}:generateAccessToken"
    token_url                         = "https://sts.googleapis.com/v1/token"
    credential_source = {
      environment_id                 = "aws1"
      region_url                     = "http://169.254.169.254/latest/meta-data/placement/availability-zone"
      url                            = "http://169.254.169.254/latest/meta-data/iam/security-credentials"
      regional_cred_verification_url = "https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15"
      imdsv2_session_token_url       = "http://169.254.169.254/latest/api/token"
    }
  })
}
