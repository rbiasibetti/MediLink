package br.com.medilink.service;

import br.com.medilink.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T14: a trilha de auditoria só aceita inserção (gatilho no SQL Server). */
@SpringBootTest
class AuditoriaTest {

	@Autowired AuditService auditoria;
	@Autowired AuditLogRepository repo;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager tx;

	@Test
	void inserirFuncionaMasAlterarEApagarFalham() {
		String recurso = "teste/" + UUID.randomUUID();
		auditoria.registrar("ator", "ACAO_DE_TESTE", recurso);
		assertThat(repo.findByRecursoOrderByMomento(recurso)).hasSize(1);

		assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET acao = 'ADULTERADO' WHERE recurso = ?", recurso))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("somente de insercao");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE recurso = ?", recurso))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("somente de insercao");

		assertThat(repo.findByRecursoOrderByMomento(recurso)).singleElement().satisfies(a -> assertThat(a.getAcao()).isEqualTo("ACAO_DE_TESTE"));
	}

	@Test
	void registroEmTransacaoPropriaSobreviveAoErroDeQuemChamou() {
		String recurso = "teste/" + UUID.randomUUID();
		try {
			new TransactionTemplate(tx).execute(s -> {
				auditoria.registrarEmTransacaoPropria("ator", "NEGADO", recurso);
				throw new IllegalStateException("a operação externa falhou");
			});
		}
		catch (IllegalStateException esperado) {
			// a transação externa foi desfeita
		}

		assertThat(repo.findByRecursoOrderByMomento(recurso)).hasSize(1);
	}
}
