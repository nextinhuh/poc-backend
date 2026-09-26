# Retrigger (26/09): forca reapply pra sincronizar a regra do listener mTLS
# apos o listener 8443 do poc-keycloak ter sido recriado varias vezes hoje.
# Listener 80 (HTTP): /auth/token e /public/ping vao para o backend normalmente.
resource "aws_lb_listener_rule" "public_http" {
  listener_arn = data.aws_lb_listener.http.arn
  priority     = 200

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend_public.arn
  }

  condition {
    path_pattern {
      values = ["/auth/token", "/public/ping"]
    }
  }
}

# Listener 80 (HTTP): /consumer/* e bloqueado explicitamente - so pode ser
# acessado pelo listener mTLS (8443). Replica a decisao "block_on_public" do
# plano original de producao da empresa.
resource "aws_lb_listener_rule" "block_consumer_on_http" {
  listener_arn = data.aws_lb_listener.http.arn
  priority     = 201

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "not found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/consumer/*"]
    }
  }
}

# Listener 8443 (mTLS, trust store = CA do step-ca): so /consumer/* e liberado.
resource "aws_lb_listener_rule" "consumer_mtls" {
  listener_arn = data.aws_lb_listener.mtls.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend_consumer.arn
  }

  condition {
    path_pattern {
      values = ["/consumer/*"]
    }
  }
}
