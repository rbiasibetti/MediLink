"""Laço da fila: consome jobs, processa e publica o resultado. A mensagem do job só é confirmada (commit) depois
que o resultado foi entregue ao broker; se o worker cair no meio, o job é reprocessado (entrega pelo menos uma vez)."""
import json
import logging
from collections import OrderedDict

from confluent_kafka import Consumer, KafkaError, Producer

from .config import Config
from .mensagens import Job, JobInvalido, ler_job, resultado_json
from .processador import processar

log = logging.getLogger("medilink.worker")


class Consumidor:
    def __init__(self, cfg: Config, transcritor):
        self.cfg, self.transcritor = cfg, transcritor
        self.consumidor = Consumer({
            **cfg.kafka_base(), "group.id": cfg.grupo, "enable.auto.commit": False,
            "auto.offset.reset": "earliest", "max.poll.interval.ms": 3_600_000,  # uma consulta longa leva minutos em CPU
        })
        self.produtor = Producer({**cfg.kafka_base(), "acks": "all", "enable.idempotence": True})
        self.vistos: OrderedDict[str, None] = OrderedDict()   # dedup do mesmo job reenviado pelo backend
        self.parar = False

    def _entregar(self, chave: str, valor: str) -> None:
        self.produtor.produce(self.cfg.topico_resultados, key=chave, value=valor.encode("utf-8"))
        if self.produtor.flush(30) > 0:
            raise RuntimeError("resultado não entregue ao broker")

    def _tratar(self, bruto: bytes) -> None:
        try:
            job: Job = ler_job(bruto)
        except JobInvalido as e:
            log.error("job descartado: %s", e)   # mensagem fora do contrato não tem como ser reprocessada
            return
        if job.job_id in self.vistos:
            log.info("job %s repetido; ignorado", job.job_id)
            return
        try:
            resultado = processar(job, self.transcritor, self.cfg)
        except Exception as e:  # noqa: BLE001 - qualquer falha vira resultado FALHA, o áudio é mantido
            log.error("job %s falhou (%s)", job.job_id, type(e).__name__)
            resultado = resultado_json(job, "FALHA", [], [], 0, erro=type(e).__name__)
        self._entregar(job.consulta_id, resultado)
        self.vistos[job.job_id] = None
        while len(self.vistos) > 1000:
            self.vistos.popitem(last=False)

    def rodar(self) -> None:
        self.consumidor.subscribe([self.cfg.topico_jobs])
        log.info("worker no ar: jobs=%s resultados=%s", self.cfg.topico_jobs, self.cfg.topico_resultados)
        try:
            while not self.parar:
                msg = self.consumidor.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    if msg.error().code() != KafkaError._PARTITION_EOF:
                        log.error("erro do broker: %s", msg.error().code())
                    continue
                self._tratar(msg.value())
                self.consumidor.commit(message=msg, asynchronous=False)
        finally:
            self.consumidor.close()
