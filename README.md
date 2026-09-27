# poc-backend

## 1. Contexto geral do teste

Esta é uma POC pessoal (conta AWS pessoal do autor, fora da empresa) para validar, antes de propor formalmente para a empresa Barte, se o desenho de autenticação de terminais via **step-ca + Keycloak + ALB com listener mTLS** funciona de ponta a ponta:

1. Um cliente (terminal POS) pede um token a **este serviço**, informando o serial number do hardware.
2. O cliente gera um CSR local e manda `{csr, access_token}` para o step-ca, que valida o token contra o Keycloak e assina, devolvendo um certificado x509.
3. O cliente usa esse certificado para chamar um endpoint **deste serviço** só acessível através de um listener **mTLS** do ALB.

Simplificado em relação ao plano corporativo real: sem domínio próprio, sem ALB dedicado/ACM, sem banco de dados persistente, sem HA. 3 repositórios independentes: `poc-keycloak` (também dono da infraestrutura compartilhada — ALB, cluster ECS, SGs, Cloud Map), `poc-certificate`, **`poc-backend`** (este).

## 2. Papel deste serviço (poc-backend)

É o único serviço com lógica de negócio própria (Spring Boot). Expõe 3 endpoints atrás do ALB compartilhado:
- `POST /auth/token` — porta 80 (HTTP), recebe o `serialNumber` do terminal, cria o usuário no Keycloak se necessário (username = serial number, com senha derivada — ver abaixo) e devolve um **ID Token OIDC** via **Direct Access Grant**.
- `GET /public/ping` — porta 80 (HTTP), sempre 200, sem exigir nada (prova que a porta "normal" funciona).
- `GET /consumer/ping` — só acessível pelo listener **mTLS** (8443); no listener 80 esse path é explicitamente bloqueado (404 fixo) pelo Terraform deste repositório.

**mTLS é uma propriedade da porta, não do path**: o ALB tem dois listeners diferentes pro mesmo backend — porta 80 (HTTP puro, sem certificado nenhum) e porta 8443 (HTTPS com `MutualAuthentication.Mode=verify`, exige certificado do cliente **antes até do handshake TLS terminar**). Cada listener só encaminha um subconjunto de paths (ver seção 3). Por isso `/public/ping` só funciona em `http://.../public/ping` (não está roteado na 8443) e `/consumer/ping` só funciona em `https://...:8443/consumer/ping` (bloqueado de propósito na 80). Tentar a combinação errada (ex.: `https://` na porta 80, ou `/public/ping` na 8443) não faz sentido nesse desenho — não é possível "ligar/desligar" mTLS por path numa mesma porta.

Fala com o Keycloak sempre via Cloud Map (`http://keycloak.poc-mtls.local:8080`), nunca por IP.

**Por que não é Token Exchange (e por que isso importa)**: a primeira versão deste serviço tentava emitir o token via **OAuth2 Token Exchange** (RFC 8693), impersonando o terminal com a identidade do próprio `poc-backend`. Validamos, testando ao vivo contra o Keycloak, que esse grant **nunca devolve `id_token`** — não importa `scope=openid`, `audience` ou `requested_token_type`, é uma limitação do mecanismo (Token Exchange v1 do Keycloak foi feito pra impersonação access-token-a-access-token, não pra emitir credenciais OIDC completas). O step-ca exige um **ID Token de verdade** (`aud` batendo no client `step-ca-oidc`) — sem isso ele recusa com `401`. A solução real: usar **Direct Access Grant** (`grant_type=password`), que é o único grant que autentica de fato um "usuário" e por isso emite `id_token`. Isso significa que o usuário-terminal **passa a ter senha** — ver `KeycloakService`.

**Como funciona, de ponta a ponta**: (1) o backend pega seu **próprio token** via `client_credentials` (usado só pra chamar a Admin API); (2) cria o usuário-terminal se ele ainda não existir (`findUserIdByUsername`/`createUser`) — com `firstName=<serialNumber>`, `lastName`/`email` mock, e uma **senha derivada** (`seed + serialNumber`, ver `KeycloakService.derivePassword` — o seed nasce uma única vez no Terraform, `poc-backend` nunca persiste senha nenhuma); (3) loga como esse terminal via `grant_type=password` **contra o client `step-ca-oidc`** (não `poc-backend`) com `scope=openid`, e devolve o `id_token` da resposta.

**Pré-requisito de configuração no Keycloak**: o client `step-ca-oidc` precisa ter **Direct Access Grants habilitado** — sem isso o Keycloak recusa o grant `password`. E o Keycloak exige `email`/`firstName`/`lastName` preenchidos no usuário para considerar a conta "completa" — sem isso o login falha com `{"error":"invalid_grant","error_description":"Account is not fully set up"}` (erro real encontrado ao validar este fluxo, nada a ver com a senha em si).

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
| SSM: secret do client `step-ca-oidc` | `/poc-mtls/keycloak/stepca-client-secret` (SecureString) — lido também pelo `poc-backend`, que agora loga como esse client |
| SSM: seed da senha do terminal | `/poc-mtls/backend/terminal-auth-seed` (SecureString, gerado pelo Terraform deste repo) |
| Fórmula da senha do usuário-terminal | `seed + serialNumber` (concatenação simples — nunca persistida, sempre recalculada) |
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
- `KeycloakService` — `fetchServiceAccountToken` (client_credentials, só pra Admin API), `findUserIdByUsername`/`createUser` (Admin REST API, com `firstName`/`lastName`/`email` mock e senha derivada), `derivePassword` (`seed + serialNumber`), `loginAsTerminal` (`grant_type=password` contra o client `step-ca-oidc`, `scope=openid`, lê `id_token` da resposta).
- `terraform/ecs.tf` — task definition (env `KEYCLOAK_BASE_URL`, secrets `KEYCLOAK_BACKEND_CLIENT_SECRET`/`KEYCLOAK_STEPCA_CLIENT_SECRET`/`TERMINAL_AUTH_SEED` do SSM — o último gerado pelo próprio Terraform via `random_password`), 2 target groups (`backend-public-tg`, `backend-consumer-tg`), service com os 2 `load_balancer` blocks.
- `terraform/alb-rules.tf` — regra no listener 80 para `/auth/token` + `/public/ping` → target group público; regra de bloqueio (404) para `/consumer/*` no listener 80; regra no listener 8443 (mTLS) para `/consumer/*` → target group consumer.

**Pré-requisito de ordem**: este repositório só deve rodar depois que (a) `poc-keycloak` já publicou o SSM do client secret, e (b) `poc-keycloak` já fez a 2ª leva (`enable_mtls_listener=true`) — a regra do listener 8443 depende desse listener já existir.

## 5. Variáveis de ambiente / SSM

- Consome: `/poc-mtls/keycloak/backend-client-secret` e `/poc-mtls/keycloak/stepca-client-secret` (SSM SecureString, escritos pelo `poc-keycloak`).
- Produz: `/poc-mtls/backend/terminal-auth-seed` (SSM SecureString, gerado pelo próprio Terraform deste repo via `random_password` — ninguém digita/versiona esse valor em lugar nenhum).
- Env vars do container: `KEYCLOAK_BASE_URL=http://keycloak.poc-mtls.local:8080` (fixo), `KEYCLOAK_BACKEND_CLIENT_SECRET`, `KEYCLOAK_STEPCA_CLIENT_SECRET`, `TERMINAL_AUTH_SEED` (os 3 últimos via secret SSM).

## 6. Workflow de CI/CD (`.github/workflows/deploy.yml`)

Em push na `main`: assume a IAM role via OIDC → cria o repositório ECR se não existir → build (Maven multi-stage, `Dockerfile`)/tag/push da imagem → `terraform init` (key `backend/terraform.tfstate`) → `terraform apply -auto-approve` (`image_tag`).

## 7. Como testar isoladamente

```bash
curl -X POST http://<shared-alb-dns>/auth/token -H "Content-Type: application/json" \
  -d '{"serialNumber":"123456789"}'
# esperado: 200 {"accessToken":"eyJ..."} - decodificar o JWT e conferir
# "typ":"ID" e "aud":"step-ca-oidc" (nao "typ":"Bearer"/"aud":"account")

curl http://<shared-alb-dns>/public/ping
# esperado: 200 {"status":"ok"}

curl -i http://<shared-alb-dns>/consumer/ping
# esperado: 404 (bloqueado no listener HTTP)

# IMPORTANTE: precisa da cadeia completa (certificado + CA intermediaria),
# nao so o "crt" sozinho - senao o ALB derruba a conexao (ECONNRESET) na
# validacao do mTLS, mesmo com um certificado valido e dentro da validade
# (erro real encontrado ao testar isso). Concatene "crt" + "ca" da resposta
# do /1.0/sign (ver README do poc-certificate) num unico arquivo:
cat crt.pem ca.pem > chain.pem

curl --cert chain.pem --key client.key https://<shared-alb-dns>:8443/consumer/ping
# esperado (depois de ter um certificado valido emitido pelo step-ca): 200
# {"status":"ok","cert_subject":"CN=<uuid-do-usuario-no-keycloak>"}
# repare que o cert_subject NAO e o serialNumber - o step-ca usa o "sub" do
# id_token (o id interno do usuario no Keycloak) como identidade do
# certificado, ignorando o CN pedido no CSR. Se precisar recuperar o serial
# number a partir do cert_subject, e necessario consultar o Keycloak
# (GET /admin/realms/poc-terminal/users/{sub}) - o certificado sozinho nao
# carrega essa informacao.
```

## 8. Fora de escopo

Spring Security / validação de token no próprio backend (quem valida certificados é o ALB/trust store, não o código Java); qualquer banco de dados; múltiplas réplicas; rate limiting; rotação/complexidade de senha (a senha derivada — `seed + serialNumber` — não segue nenhuma política de senha, é só uma credencial de máquina).
