resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${var.project_name}-backend"
  retention_in_days = 3
}

# Seed usado pra derivar a senha de cada usuario-terminal no Keycloak
# (senha = seed + serialNumber, ver KeycloakService.derivePassword). Gerado
# uma unica vez pelo Terraform e guardado so como secret - nunca digitado/
# versionado, nunca persistido pelo poc-backend em lugar nenhum alem daqui.
resource "random_password" "terminal_auth_seed" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "terminal_auth_seed" {
  name  = "/${var.project_name}/backend/terminal-auth-seed"
  type  = "SecureString"
  value = random_password.terminal_auth_seed.result
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.project_name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                       = "256"
  memory                    = "512"
  execution_role_arn        = data.aws_iam_role.ecs_execution.arn

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = "${data.aws_ecr_repository.this.repository_url}:${var.image_tag}"
      essential = true
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]

      environment = [
        { name = "KEYCLOAK_BASE_URL", value = "http://keycloak.${var.project_name}.local:8080" },
        # usado pelo SecurityConfig (Resource Server) pra validar o Bearer
        # token do /consumer/ping - mesmo issuer que o poc-certificate ja
        # usa pra validar o "ott" no provisioner OIDC do step-ca.
        { name = "KEYCLOAK_ISSUER", value = "http://keycloak.${var.project_name}.local:8080/realms/poc-terminal" },
      ]

      secrets = [
        {
          name      = "KEYCLOAK_BACKEND_CLIENT_SECRET"
          valueFrom = data.aws_ssm_parameter.backend_client_secret.arn
        },
        {
          name      = "KEYCLOAK_STEPCA_CLIENT_SECRET"
          valueFrom = data.aws_ssm_parameter.stepca_client_secret.arn
        },
        {
          name      = "TERMINAL_AUTH_SEED"
          valueFrom = aws_ssm_parameter.terminal_auth_seed.arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "backend"
        }
      }
    }
  ])
}

resource "aws_lb_target_group" "backend_public" {
  name        = "${var.project_name}-backend-public-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"

  health_check {
    path = "/public/ping"
  }
}

resource "aws_lb_target_group" "backend_consumer" {
  name        = "${var.project_name}-backend-consumer-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"

  health_check {
    path = "/public/ping" # /consumer/ping exige mTLS, health check do ALB nao teria cert
  }
}

resource "aws_ecs_service" "backend" {
  name            = "${var.project_name}-backend"
  cluster         = data.aws_ecs_cluster.this.arn
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.public.ids
    security_groups  = [data.aws_security_group.ecs_tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend_public.arn
    container_name    = "backend"
    container_port    = 8080
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend_consumer.arn
    container_name    = "backend"
    container_port    = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [
    aws_lb_listener_rule.public_http,
    aws_lb_listener_rule.block_consumer_on_http,
    aws_lb_listener_rule.consumer_mtls,
  ]
}
