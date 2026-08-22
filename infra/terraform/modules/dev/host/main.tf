# dev 호스트 — EC2 1대 + EIP + 데이터 EBS attach. user_data가 docker·compose·시크릿(.env)·스크립트를 부트스트랩.
# AMI는 SSM에서 최신 AL2023 x86_64를 항상 조회(외부 입력 없음). Caddy 자동 HTTPS(도메인 필수, :80 폴백 없음). (ADR-0021/0022)
# 시크릿(secrets 모듈)·SG·인스턴스 프로파일·EBS 볼륨은 환경 루트에서 주입. SSH 미개방(SSM 전용).
data "aws_ssm_parameter" "al2023_x86" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  ami_id     = data.aws_ssm_parameter.al2023_x86.value
  ssm_prefix = "/${var.name_prefix}"
  # Caddy 사이트 주소 — 도메인으로 자동 HTTPS(도메인 필수).
  caddy_site = var.domain_name

  # user_data와 기존 EC2용 SSM 명령이 완전히 같은 compose를 사용하도록 한 번만 렌더한다.
  compose_yml = templatefile("${path.module}/docker-compose.yml.tftpl", {
    aws_region                  = var.aws_region
    db_name                     = var.db_name
    app_image                   = var.app_image
    smtp_host                   = var.smtp_host
    smtp_port                   = var.smtp_port
    mail_from                   = var.mail_from
    images_bucket               = var.images_bucket
    images_cdn_domain           = var.images_cdn_domain
    mongo_username              = var.mongo_username
    mysql_username              = var.mysql_username
    chat_translation_enabled    = var.chat_translation_enabled
    chat_translation_project_id = var.chat_translation_project_id
    chat_translation_location   = var.chat_translation_location
  })

  # 현재 실행 중인 EC2는 user_data가 다시 실행되지 않으므로, 같은 내용을 SSM으로 1회 적용할 스크립트도 만든다.
  configure_chat_translation_script = templatefile("${path.module}/configure-chat-translation.sh.tftpl", {
    google_wif_credentials_base64 = base64encode(var.google_wif_credential_configuration)
    compose_yml_base64            = base64encode(local.compose_yml)
  })
}

resource "aws_instance" "host" {
  ami                    = local.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [var.security_group_id]
  iam_instance_profile   = var.instance_profile_name

  metadata_options {
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 2          # Docker 컨테이너까지 IMDSv2 응답이 도달하려면 한 홉을 더 허용해야 한다.
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  user_data_replace_on_change = false
  # user_data의 16KB 하드 리밋을 해결하기 위해 gzip + base64 사용.
  user_data_base64 = base64gzip(templatefile("${path.module}/user_data.sh.tftpl", {
    aws_region = var.aws_region
    account_id = var.account_id
    # 부팅 시 프론트 마지막 릴리스를 복원할 때 읽을 포인터 버킷(#232).
    web_artifacts_bucket = var.web_artifacts_bucket
    # 프론트 릴리스 적용(#232) — 배포·롤백·부팅 복원이 모두 이 스크립트 하나를 인자만 달리해 호출한다.
    deploy_web = templatefile("${path.module}/deploy-web.sh.tftpl", {
      aws_region = var.aws_region
      web_bucket = var.web_artifacts_bucket
    })
    # 시크릿 조회(SSM→.env)는 별도 스크립트로 분리 — 부팅·배포 양쪽에서 재호출(최신값 반영, ADR-0024).
    refresh_env = templatefile("${path.module}/refresh-env.sh.tftpl", {
      aws_region     = var.aws_region
      ssm_prefix     = local.ssm_prefix
      mysql_username = var.mysql_username
      mongo_username = var.mongo_username
      db_name        = var.db_name
    })
    # DB 자격증명 reconcile(데이터 보존 회전, ADR-0025) — Terraform 변수 없는 정적 스크립트.
    reconcile_db = templatefile("${path.module}/reconcile-db.sh.tftpl", {})
    # compose·Caddyfile은 standalone 템플릿으로 렌더해 주입(가시성·CI 재사용).
    caddyfile = templatefile("${path.module}/Caddyfile.tftpl", {
      caddy_site = local.caddy_site
    })
    # 로그 반출(ADR-0038 ⑥) — Agent가 /opt/kohere/logs/app.json을 tail한다. 게이트는 ADR-0026 메모리 예산.
    enable_cloudwatch_agent = var.enable_cloudwatch_agent
    cloudwatch_agent_config = templatefile("${path.module}/cloudwatch-agent.json.tftpl", {
      log_group_name = var.log_group_name
    })
    compose_yml                   = local.compose_yml
    google_wif_credentials_base64 = base64encode(var.google_wif_credential_configuration)
    configure_chat_translation    = local.configure_chat_translation_script
  }))

  lifecycle {
    ignore_changes = [ami]
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-host" })
}

resource "aws_eip" "host" {
  domain   = "vpc"
  instance = aws_instance.host.id
  tags     = merge(var.tags, { Name = "${var.name_prefix}-eip" })
}

resource "aws_volume_attachment" "data" {
  device_name  = "/dev/sdf"
  volume_id    = var.volume_id
  instance_id  = aws_instance.host.id
  skip_destroy = true
}

# Terraform apply 뒤 이 문서를 한 번 실행하면 EC2를 교체하지 않고도 현재 서버에 WIF/compose 설정이 반영된다.
# 새 EC2는 user_data가 같은 파일을 만들기 때문에 이 문서를 실행하지 않아도 된다.
resource "aws_ssm_document" "configure_chat_translation" {
  name            = "${var.name_prefix}-configure-chat-translation"
  document_type   = "Command"
  document_format = "YAML"

  content = yamlencode({
    schemaVersion = "2.2"
    description   = "기존 dev EC2에 Google WIF와 채팅 자동 번역 설정을 반영한다"
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "configureChatTranslation"
      inputs = {
        runCommand = [local.configure_chat_translation_script]
      }
    }]
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-configure-chat-translation" })
}
