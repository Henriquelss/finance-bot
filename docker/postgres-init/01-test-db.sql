-- Banco de testes do FinanceBot.
-- Rodado automaticamente pelo entrypoint do Postgres quando o volume está vazio.
--
-- Motivo: os testes usam @SpringBootTest e, sem um banco próprio, escreveriam e
-- apagariam dados do banco de desenvolvimento (o ExpenseRepositoryTest faz
-- deleteAll() no setUp). O datasource de teste está em
-- backend/src/test/resources/application.properties.
SELECT 'CREATE DATABASE financebot_test'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'financebot_test')\gexec
