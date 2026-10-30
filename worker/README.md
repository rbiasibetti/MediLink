# MediLink: worker de transcrição

Consome os jobs de transcrição da fila (Kafka/Redpanda), transcreve o áudio de cada participante com [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (modelo `turbo`, CPU, português, VAD) e publica o texto, rotulado por locutor, no tópico de resultados. A visão geral está no [README da raiz](../README.md); o contrato das mensagens, em [`docs/plan.md`](../docs/plan.md) ("Transcrição pós-consulta").

## Executar

```bash
cd worker
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt      # versões fixadas (veja a nota sobre o av abaixo)
../infra/dev/redpanda.sh up                        # broker de desenvolvimento em localhost:9092, com os dois tópicos
.venv/bin/python -m medilink_worker                # baixa o modelo (~1,6 GB) na primeira execução
```

O modelo é guardado em `worker/models/` (fora do git). O áudio é lido da pasta do sink (`MEDILINK_SINK_DIR`, a mesma do backend).

## Configuração (variáveis de ambiente)

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `MEDILINK_KAFKA_BOOTSTRAP` | `localhost:9092` | Broker |
| `MEDILINK_TOPICO_JOBS`, `MEDILINK_TOPICO_RESULTADOS` | `medilink.transcricao.jobs`, `medilink.transcricao.resultados` | Tópicos |
| `MEDILINK_WORKER_GRUPO` | `medilink-transcricao` | Grupo de consumo (vários workers dividem os jobs) |
| `MEDILINK_SINK_DIR` | `/tmp/medilink-audio` | Pasta dos arquivos de áudio transitórios |
| `MEDILINK_WHISPER_MODELO` | `turbo` | Modelo do faster-whisper |
| `MEDILINK_WHISPER_DIR` | `worker/models` | Onde guardar/ler o modelo |
| `MEDILINK_WHISPER_DEVICE`, `MEDILINK_WHISPER_COMPUTE` | `cpu`, `int8` | Dispositivo e precisão |
| `MEDILINK_WHISPER_IDIOMA` | `pt` | Idioma fixo (evita detecção errada em trechos curtos) |

## Testes

```bash
.venv/bin/python -m pytest -q      # 30 testes, sem modelo e sem broker (transcritor falso)
```

Verificação ponta a ponta com fala em português pelo Jitsi real: [`infra/jitsi/e2e/verify_t12.py`](../infra/jitsi/e2e/verify_t12.py) (os pré-requisitos estão no cabeçalho do script).

## Garantias e cuidados

- **Entrega pelo menos uma vez.** A mensagem do job só é confirmada depois de o resultado ser entregue ao broker; se o worker cair no meio, o job é reprocessado. O mesmo `jobId` repetido é ignorado.
- **Mensagem fora do contrato** é descartada sem travar a fila. **Falha inesperada** vira um resultado `FALHA` (só o tipo do erro) e o áudio é mantido.
- **O texto da consulta nunca vai para log.** O resultado carrega o texto (dado sensível de saúde): o tópico tem retenção curta (24 h) e precisa de TLS e ACL no broker.
- **Áudio cifrado (T14):** o sink grava `.opus.enc` (AES-256-GCM); o worker precisa de `MEDILINK_DATA_KEY` (a mesma do backend; `MEDILINK_DATA_KEY_ID`, `MEDILINK_DATA_KEYS_ANTERIORES` para rotação), decifra só em memória e recusa áudio sem cifra (`MEDILINK_AUDIO_CIFRADO_OBRIGATORIO=0` desliga). Para TLS até o broker: `MEDILINK_KAFKA_SECURITY_PROTOCOL=SSL` e `MEDILINK_KAFKA_CA`. Com o modelo já baixado, `HF_HUB_OFFLINE=1` evita qualquer acesso à internet.
- **Caminho do arquivo validado:** sem caminho absoluto, `..` nem link simbólico que saia da pasta do sink.
- **Desempenho medido:** ≈ 2× o tempo real por fonte em 8 núcleos de CPU (22 s para 45 s de áudio).

## Nota sobre o PyAV

O `faster-whisper` 1.2.1 declara apenas `av>=11`, mas chama `av.open(metadata_errors=...)`, argumento removido no PyAV 17+. Sem o pin `av==16.1.0` o `pip` instala o 19 e a transcrição falha com `TypeError`.
