output "instance_id" {
  description = "dev 호스트 EC2 인스턴스 ID(SSM 접속·배포 대상)"
  value       = aws_instance.host.id
}

output "public_ip" {
  description = "dev 호스트 EIP(고정 공인 IP) — Route53 A 레코드 대상"
  value       = aws_eip.host.public_ip
}

output "app_url" {
  description = "앱 접속 URL(HTTPS — 도메인 필수)"
  value       = "https://${var.domain_name}"
}
