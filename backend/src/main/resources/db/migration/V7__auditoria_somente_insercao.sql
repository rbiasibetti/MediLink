-- T14. A trilha de auditoria só cresce: UPDATE e DELETE em audit_log falham, para quem acessa o banco (inclusive a
-- própria aplicação) não poder reescrever o histórico por engano ou de propósito. Quem tem privilégio de DDL ainda
-- pode remover o gatilho, mas isso deixa rastro no log do SQL Server e exige ação explícita. A retenção da própria
-- auditoria é decisão do jurídico (prazo legal) e será feita por procedimento administrativo, não pela aplicação.
CREATE TRIGGER tr_audit_log_somente_insercao ON audit_log INSTEAD OF UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'audit_log e somente de insercao', 1;
END;
