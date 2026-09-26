# poc-backend

## 1. Contexto geral do teste

Esta é uma POC pessoal (conta AWS pessoal do autor, fora da empresa) para validar, antes de propor formalmente para a empresa Barte, se o desenho de autenticação de terminais via **step-ca + Keycloak + ALB com listener mTLS** funciona de ponta a ponta:

1. Um cliente (terminal POS) pede um token a **este serviço**, informando o serial number do hardware.
2. O cliente gera um CSR local e manda `{csr, access_token}` para o step-ca, que valida o token contra o Keycloak e assina, devolvendo um certificado x509.
3. O cliente usa esse certificado para chamar um endpoint **deste serviço** só acessível através de um listener **mTLS** do ALB.

Simplificado em relação ao plano corporativo real: sem domínio próprio, sem ALB dedicado/ACM, sem banco de dados persistente, sem HA. 3 repositórios independentes: `poc-keycloak` (também dono da infraestrutura compartilhada — ALB, cluster ECS, SGs, Cloud Map), `poc-certificate`, **`poc-backend`** (este).

## 2. Papel deste serviço (poc-backend)

É o único serviço com lógica de negócio própria (Spring Boot). Expõe 3 endpoints atrás do ALB compartilhado:
- `POST /auth/token` — porta 80 (HTTP), recebe o `serialNumber` do terminal, cria o usuário no Keycloak se necessário (username = serial number, **sem senha**) e devolve um token via **OAuth2 Token Exchange** (RFC 8693).
- `GET /public/ping` — porta 80 (HTTP), sempre 200, sem exigir nada (prova que a porta "normal" funciona).
- `GET /consumer/ping` — só acessível pelo listener **mTLS** (8443); no listener 80 esse path é explicitamente bloqueado (404 fixo) pelo Terraform deste repositório.

Fala com o Keycloak sempre via Cloud Map (`http://keycloak.poc-mtls.local:8080`), nunca por IP.

**Detalhe crítico de implementação (não pular)**: o step-ca valida um **ID Token OIDC** (JWT assinado pelo Keycloak), não um `access_token` opaco. Por isso `/auth/token` precisa pedir o token ao Keycloak com `scope=openid` e devolver o campo **`id_token`** da resposta do Keycloak no campo `accessToken`/`access_token` do contrato deste endpoint (com fallback pro `access_token` caso o `id_token` não venha). Se devolver o `access_token` "de verdade" do Keycloak em vez do `id_token`, o step-ca vai rejeitar a assinatura do CSR. Ver `KeycloakService.exchangeTokenForSubject`.

Cada terminal é modelado como **1 usuário único no Keycloak, sem senha nunca** — o `poc-backend` nunca autentica como o terminal (não existe `grant_type=password` nesse fluxo). Em vez disso: (1) o backend pega seu **próprio token** via `client_credentials` (usado tanto pra chamar a Admin API quanto como `subject_token`); (2) cria o usuário-terminal se ele ainda não existir (`findUserIdByUsername`/`createUser`, sem `email`/`credentials`); (3) troca esse token pelo do usuário-terminal via `grant_type=urn:ietf:params:oauth:grant-type:token-exchange` + `requested_subject=<serialNumber>` — é o "impersonate" via token exchange, não o Admin Impersonation API (são mecanismos diferentes, ver histórico de troubleshooting deste projeto).

**Pré-requisito de configuração no Keycloak (feito manualmente no console, documentado aqui pra não se perder)**: o realm `poc-terminal` precisa ter a feature `token-exchange` habilitada no servidor (`Dockerfile` do `poc-keycloak`, flag `--features=token-exchange,admin-fine-grained-authz`), e duas permissões configuradas: `Clients → poc-backend → Advanced → Permissions enabled` + a permissão `token-exchange` desse client com uma policy liberando o próprio `poc-backend`; e `Users → Permissions → impersonate` com a mesma policy. Sem isso, o Keycloak recusa com `{"error":"access_denied","error_description":"Client not allowed to exchange"}`.

## 3. Contrato entre serviços (fonte de verdade — igual nos 3 READMEs)

| Item | Valor exato |
|---|---|
| Realm Keycloak | `poc-terminal` |
| Client backend | `poc-backend` (confidential, `serviceAccountsEnabled=true`, `directAccessGrantsEnabled=true`) |
| Client do provisioner do step-ca | `step-ca-oidc` (confidential) |
| URL interna do Keycloak (via Cloud Map) | `http://keycloak.poc-mtls.local:8080` |
| Admin REST API (criar usuário) | `POST http://keycloak.poc-mtls.local:8080/admin/realms/poc-terminal/users` |
| Token endpoint | `POST http://keycloak.poc-mtls.local:8080/realms/poc-terminal/protocol/openid-connect/token` |
| SSM: secret do client `poc-backend` | `/poc-mtls/keycloak/backend-client-secret` (SecureString) |
| SSM: secret do client `step-ca-oidc` | `/poc-mtls/keycloak/stepca-client-secret` (SecureString) |
| Bucket S3 da CA raiz | criado pelo `poc-certificate`, objeto `root_ca.crt` |
| ALB (nome/tag) | `data "aws_lb" "shared"` por tag `Name=poc-mtls-shared-alb` |
| Cluster ECS | `data "aws_ecs_cluster" "this"` — nome fixo `poc-mtls-ECS` |
| Namespace Cloud Map | `poc-mtls.local` |
| Porta/health-check step-ca | `9000` (HTTPS interno), `GET /health` |
| Porta backend | `8080`; paths `/auth/token` (POST), `/public/ping` (GET), `/consumer/ping` (GET) |
| Path público do step-ca no ALB | `/1.0/sign` (POST), `/health` (GET) — listener 80 |
| Path bloqueado no listener 80 | `/consumer/*` → fixed-response 404 |
| Path liberado só no listener 8443 (mTLS) | `/consumer/*` → target group backend |
| TTL do certificado emitido | 5 minutos |

## 4. O que precisa ser implementado aqui

Já implementado neste repositório (Spring Boot 3 / Java 17 / Maven, sem Spring Security — desnecessário para o escopo):
- `AuthController` — `POST /auth/token`, recebe `{"serialNumber": "..."}`, devolve `{"accessToken": "<id_token do Keycloak>"}`.
- `PingController` — `GET /public/ping` (sempre 200) e `GET /consumer/ping` (ecoa o header `X-Amzn-Mtls-Clientcert-Subject` que o ALB injeta quando o mutual TLS é validado).
- `KeycloakService` — `fetchServiceAccountToken` (client_credentials), `findUserIdByUsername`/`createUser` (Admin REST API, sem email/credentials), `exchangeTokenForSubject` (grant `urn:ietf:params:oauth:grant-type:token-exchange`, `requested_subject=<serialNumber>`, `scope=openid`, lê `id_token` da resposta com fallback pra `access_token`).
- `terraform/ecs.tf` — task definition (env `KEYCLOAK_BASE_URL`, secret `KEYCLOAK_BACKEND_CLIENT_SECRET` do SSM), 2 target groups (`backend-public-tg`, `backend-consumer-tg`), service com os 2 `load_balancer` blocks.
- `terraform/alb-rules.tf` — regra no listener 80 para `/auth/token` + `/public/ping` → target group público; regra de bloqueio (404) para `/consumer/*` no listener 80; regra no listener 8443 (mTLS) para `/consumer/*` → target group consumer.

**Pré-requisito de ordem**: este repositório só deve rodar depois que (a) `poc-keycloak` já publicou o SSM do client secret, e (b) `poc-keycloak` já fez a 2ª leva (`enable_mtls_listener=true`) — a regra do listener 8443 depende desse listener já existir.

## 5. Variáveis de ambiente / SSM

- Consome: `/poc-mtls/keycloak/backend-client-secret` (SSM SecureString, escrito pelo `poc-keycloak`).
- Env vars do container: `KEYCLOAK_BASE_URL=http://keycloak.poc-mtls.local:8080` (fixo), `KEYCLOAK_BACKEND_CLIENT_SECRET` (via secret SSM).

## 6. Workflow de CI/CD (`.github/workflows/deploy.yml`)

Em push na `main`: assume a IAM role via OIDC → cria o repositório ECR se não existir → build (Maven multi-stage, `Dockerfile`)/tag/push da imagem → `terraform init` (key `backend/terraform.tfstate`) → `terraform apply -auto-approve` (`image_tag`).

## 7. Como testar isoladamente

```bash
curl -X POST http://<shared-alb-dns>/auth/token -H "Content-Type: application/json" \
  -d '{"serialNumber":"123456789"}'
# esperado: 200 {"accessToken":"eyJ..."}

curl http://<shared-alb-dns>/public/ping
# esperado: 200 {"status":"ok"}

curl -i http://<shared-alb-dns>/consumer/ping
# esperado: 404 (bloqueado no listener HTTP)

curl --cert client.crt --key client.key https://<shared-alb-dns>:8443/consumer/ping
# esperado (depois de ter um certificado valido emitido pelo step-ca): 200
```

## 8. Fora de escopo

Spring Security / validação de token no próprio backend (quem valida certificados é o ALB/trust store, não o código Java); qualquer banco de dados; múltiplas réplicas; rate limiting; validação de força de senha (senha temporária é só um UUID/base64 aleatório).
