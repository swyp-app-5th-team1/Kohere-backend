provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}

# Google Provider는 서비스 계정 키 파일을 사용하지 않는다.
# Terraform 실행자의 ADC(로컬 관리자 로그인)로 WIF 리소스를 만들고, 실제 EC2 앱은 별도의 WIF 설정을 사용한다.
provider "google" {
  project = var.google_cloud_project_id
}

# CloudFront 인증서(ACM)는 반드시 us-east-1에 있어야 한다 — CDN 커스텀 도메인용 별도 provider.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.common_tags
  }
}
