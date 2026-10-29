# FinanceBot

Bot de controle de despesas pessoais por áudio no Telegram. O usuário envia um áudio, o provedor de IA transcreve e interpreta a mensagem, e o back-end salva a despesa no PostgreSQL e confirma o lançamento.

O provedor de IA é **configurável por variáveis de ambiente** (base URL, chave e modelos): por padrão é a OpenAI, mas funciona também com provedores compatíveis como o **Groq (plano gratuito)** ou um Whisper auto-hospedado.

## O que o projeto faz

1. O usuário envia um **áudio** no bot do Telegram (ex.: *"gastei 50 reais no Starbucks, paguei no débito"*).
2. O bot baixa o áudio e **transcreve** com um modelo Whisper.
3. O texto é **interpretado** por um LLM via *function calling*, que extrai valor, descrição, categoria, meio de pagamento, estabelecimento e data — inferindo a categoria a partir do local citado.
4. O back-end **valida** os dados, **salva a despesa no PostgreSQL** e responde no chat com a confirmação.
5. Se o valor estiver ausente ou ambíguo, o bot **pergunta** em vez de gravar errado.

Detalhes de confiabilidade: *long polling* (não exige IP público nem webhook), deduplicação por `update_id`, tratamento de erros que não derruba o bot e provedor de IA trocável apenas por configuração.

## Stack

| Área | Tecnologia |
|---|---|
| Back-end | Java 25 LTS + Spring Boot 4.1.1 |
| Build | Maven 3.9.x via Maven Wrapper (`./mvnw`) — sem instalar Maven |
| Banco | PostgreSQL 18 + Flyway |
| IA | Whisper (transcrição) + LLM via *function calling* (extração) — provedor compatível com a API da OpenAI |
| Telegram | Bot API (long polling) |
| Infra local | Docker + Docker Compose |

## Pré-requisitos

- Docker Desktop (com daemon em execução)
- JDK 25+ para rodar os testes fora do Docker (opcional — o build também funciona 100% via Docker)
- IntelliJ IDEA (recomendado; detecta o `./mvnw` automaticamente)

## Como executar

```bash
# 1. Configurar variáveis (valores locais de desenvolvimento)
cp .env.example .env

# 2. Subir banco + aplicação (build da imagem incluído)
docker compose up --build

# 3. Verificar saúde
curl http://localhost:8080/actuator/health
# esperado: {"status":"UP"}
```

Para parar: `docker compose down`. Para apagar os dados: `docker compose down -v` (o volume `pgdata` preserva os dados entre `down`/`up` comuns).

## Como testar

Os testes usam o PostgreSQL local (porta 5432) publicado pelo Docker Compose, mas em um
**banco separado** (`financebot_test`, criado automaticamente em volumes novos pelo script
`docker/postgres-init/01-test-db.sql`) — assim o `./mvnw test` nunca escreve nem apaga dados
do banco de desenvolvimento:

```bash
docker compose up -d db          # só o banco
./mvnw -f backend test           # testes (ou use o Maven do IntelliJ)
```

Para recriar o banco de testes a partir do zero: `docker compose down -v && docker compose up -d db`.

## Provedor de IA

O back-end fala o **protocolo OpenAI**, então qualquer provedor compatível funciona — muda só
a configuração em `.env`, sem alterar código. Ao final do startup o log mostra qual está ativo:

```
Provedor de IA: https://api.groq.com/openai/v1 | transcrição=whisper-large-v3-turbo | extração=openai/gpt-oss-20b
```

| Variável | Padrão (OpenAI) | Groq (gratuito) |
|---|---|---|
| `AI_BASE_URL` | `https://api.openai.com/v1` | `https://api.groq.com/openai/v1` |
| `AI_API_KEY` | chave da OpenAI | chave do Groq |
| `AI_TRANSCRIPTION_MODEL` | `whisper-1` | `whisper-large-v3-turbo` |
| `AI_EXTRACTION_MODEL` | `gpt-4o-mini` | `openai/gpt-oss-20b` |
| `AI_TRANSCRIPTION_LANGUAGE` | vazio (auto) | `pt` |

Para usar o Groq: crie uma chave gratuita em <https://console.groq.com/keys> (sem cartão),
preencha as variáveis acima no `.env` e rode `docker compose up -d` (**não** `docker compose
restart`, que não relê o `.env`).

## Estrutura

```text
FinanceBot/
├── backend/                 # aplicação Spring Boot
│   ├── src/main/java/com/financebot/
│   │   ├── FinanceBotApplication.java
│   │   ├── ai/              # cliente do provedor de IA (transcrição + extração)
│   │   ├── config/          # properties (Telegram, IA) e RestClient
│   │   ├── dto/             # payloads do Telegram e da IA
│   │   ├── entity/          # User, Expense
│   │   ├── repository/      # Spring Data JPA
│   │   ├── service/         # orquestração do fluxo (despesa, usuário, datas)
│   │   └── telegram/        # cliente + polling da Bot API
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    # Flyway
│   ├── Dockerfile           # multi-stage (maven → temurin:25-jre)
│   └── mvnw                 # Maven Wrapper
├── docker/
│   └── postgres-init/       # script que cria o banco de testes
├── docker-compose.yml       # app + postgres
├── .env.example             # variáveis de ambiente (sem segredos)
└── README.md                # este arquivo
```

## Segredos

- Nunca commite tokens do Telegram, chaves de IA (OpenAI/Groq) ou o arquivo `.env`.
- `.env.example` contém apenas nomes e valores de exemplo.

## Status

- [x] Etapa 1 — Fundação do back-end (Spring Boot, PostgreSQL, Flyway, Docker, health check, testes)
- [x] Etapa 2 — Bot Telegram + transcrição/interpretação (long polling, Whisper, extração via function calling)
- [x] Migração para provedor de IA configurável (OpenAI → Groq gratuito, base URL/modelos por `.env`)
- [ ] Etapa 3 — Consultas e relatórios pelo Telegram
- [ ] Etapa 4 — Isolamento por usuário e preparação para outros usuários
- [ ] Etapa 5 — Dashboard React (design no Paper Design)
- [ ] Etapa 6 — Publicação e CI/CD

## Licença

Este projeto está sob a licença [MIT](LICENSE).

