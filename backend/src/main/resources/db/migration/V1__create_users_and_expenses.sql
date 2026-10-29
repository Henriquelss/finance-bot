-- V1: estrutura inicial do FinanceBot (usuários do Telegram e despesas)

CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT       NOT NULL UNIQUE,
    telegram_chat_id BIGINT,
    display_name     VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE expenses (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT         NOT NULL REFERENCES users (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    description        VARCHAR(200)   NOT NULL,
    category           VARCHAR(50)    NOT NULL,
    merchant           VARCHAR(100),
    payment_method     VARCHAR(50),
    expense_date       DATE           NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    source             VARCHAR(30)    NOT NULL,
    telegram_update_id BIGINT,
    status             VARCHAR(20)    NOT NULL DEFAULT 'CONFIRMED'
);

CREATE INDEX idx_expenses_user_id ON expenses (user_id);
CREATE INDEX idx_expenses_expense_date ON expenses (expense_date);

-- Unicidade para prevenir duplicações; múltiplos NULL são permitidos no PostgreSQL
CREATE UNIQUE INDEX uq_expenses_telegram_update_id ON expenses (telegram_update_id);
