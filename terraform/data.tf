data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_ecs_cluster" "this" {
  cluster_name = "${var.project_name}-ECS"
}

data "aws_iam_role" "ecs_execution" {
  name = "${var.project_name}-ecs-execution"
}

data "aws_security_group" "ecs_tasks" {
  filter {
    name   = "tag:Name"
    values = ["${var.project_name}-ecs-tasks-sg"]
  }
}

data "aws_lb" "shared" {
  tags = {
    Name = "${var.project_name}-shared-alb"
  }
}

data "aws_lb_listener" "http" {
  load_balancer_arn = data.aws_lb.shared.arn
  port              = 80
}

# So existe depois que o poc-shared-infra rodou a 2a leva
# (enable_mtls_listener = true). Se este apply falhar aqui, e sinal de que a
# ordem de implementacao (ver README) nao foi respeitada.
data "aws_lb_listener" "mtls" {
  load_balancer_arn = data.aws_lb.shared.arn
  port              = 8443
}

data "aws_ssm_parameter" "backend_client_secret" {
  name            = "/${var.project_name}/keycloak/backend-client-secret"
  with_decryption = true
}

data "aws_ecr_repository" "this" {
  name = "${var.project_name}-backend"
}
