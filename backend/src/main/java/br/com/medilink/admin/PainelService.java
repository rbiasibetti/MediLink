package br.com.medilink.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Números do painel administrativo (T18): contagens e estados. NUNCA CPF, nome, e-mail nem texto de transcrição:
 * as consultas aparecem só pelo identificador opaco. As leituras são agregadas no banco (nada é carregado na memória).
 */
@Service
public class PainelService {

	public record Incidente(String consultaId, String tipo, Instant desde, String erro) {
	}

	public record PorSistema(String sistema, long total, long noPeriodo) {
	}

	private static final List<String> TIPOS_DE_INCIDENTE = List.of("SEM_AUDIO", "FALHA_NO_ENVIO", "FALHA_NO_WORKER", "EXPIRADO");

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public PainelService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public Map<String, Object> resumo(int dias) {
		Instant agora = Instant.now(clock);
		OffsetDateTime desde = agora.minus(dias, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("geradoEm", agora);
		r.put("periodoDias", dias);

		Map<String, Object> ag = new LinkedHashMap<>();
		ag.put("porStatus", contar("SELECT status, COUNT(*) FROM agendamento GROUP BY status"));
		ag.put("criadosNoPeriodo", numero("SELECT COUNT(*) FROM agendamento WHERE criado_em >= ?", desde));
		ag.put("porSistema", porSistema(desde));
		r.put("agendamentos", ag);

		Map<String, Object> co = new LinkedHashMap<>();
		co.put("porStatus", contar("SELECT status, COUNT(*) FROM consulta GROUP BY status"));
		co.put("encerradasNoPeriodo", numero("SELECT COUNT(*) FROM consulta WHERE fim_em >= ?", desde));
		r.put("consultas", co);

		Map<String, Object> tr = new LinkedHashMap<>();
		tr.put("jobsPorStatus", contar("SELECT status, COUNT(*) FROM transcricao_job GROUP BY status"));
		tr.put("transcricoesGravadas", numero("SELECT COUNT(*) FROM transcricao"));
		tr.put("audioAindaNoDisco", numero("SELECT COUNT(*) FROM audio_transitorio WHERE status <> 'DESCARTADO'"));
		Instant maisAntigo = jdbc.query("SELECT MIN(iniciado_em) FROM audio_transitorio WHERE status <> 'DESCARTADO'",
				(rs, i) -> rs.getObject(1, OffsetDateTime.class)).stream().filter(java.util.Objects::nonNull).map(OffsetDateTime::toInstant).findFirst().orElse(null);
		tr.put("audioMaisAntigoDesde", maisAntigo);
		r.put("transcricao", tr);

		r.put("webhooks", contar("SELECT status, COUNT(*) FROM webhook_entrega GROUP BY status"));
		r.put("incidentes", incidentes());
		return r;
	}

	/** Consultas que terminaram sem transcrição por motivo técnico. Toda consulta precisa ser registrada, então cada uma é investigada. */
	public List<Incidente> incidentes() {
		String lista = String.join(",", TIPOS_DE_INCIDENTE.stream().map(t -> "'" + t + "'").toList());
		return jdbc.query("SELECT TOP 50 consulta_id, status, atualizado_em, ultimo_erro FROM transcricao_job WHERE status IN (" + lista
				+ ") ORDER BY atualizado_em DESC", (rs, i) -> new Incidente(rs.getString(1).toLowerCase(), rs.getString(2),
				rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getString(4)));
	}

	private List<PorSistema> porSistema(OffsetDateTime desde) {
		List<PorSistema> l = new ArrayList<>(jdbc.query("""
				SELECT i.nome, COUNT(a.id), COALESCE(SUM(CASE WHEN a.criado_em >= ? THEN 1 ELSE 0 END), 0)
				FROM integrador i LEFT JOIN agendamento a ON a.integrador_id = i.id GROUP BY i.nome ORDER BY i.nome""",
				(rs, n) -> new PorSistema(rs.getString(1), rs.getLong(2), rs.getLong(3)), desde));
		l.add(new PorSistema("(médicos locais, sem sistema integrado)", numero("SELECT COUNT(*) FROM agendamento WHERE integrador_id IS NULL"),
				numero("SELECT COUNT(*) FROM agendamento WHERE integrador_id IS NULL AND criado_em >= ?", desde)));
		return l;
	}

	private Map<String, Long> contar(String sql) {
		Map<String, Long> m = new LinkedHashMap<>();
		jdbc.query(sql, rs -> {
			m.put(rs.getString(1), rs.getLong(2));
		});
		return m;
	}

	private long numero(String sql, Object... args) {
		Long n = jdbc.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}
}
