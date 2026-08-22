output "instance_profile_name" {
  description = "EC2에 부여할 인스턴스 프로파일 이름"
  value       = aws_iam_instance_profile.host.name
}

output "role_arn" {
  description = "호스트 IAM 역할 ARN"
  value       = aws_iam_role.host.arn
}

output "role_name" {
  description = "Google WIF 등 외부 신뢰 조건에 사용할 호스트 IAM 역할 이름"
  value       = aws_iam_role.host.name
}
