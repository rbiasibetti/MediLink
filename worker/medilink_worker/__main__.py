import logging
import signal

from .config import Config
from .consumidor import Consumidor
from .transcritor import Transcritor


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = Config()
    logging.getLogger("medilink.worker").info("carregando o modelo %s (%s, %s)…", cfg.modelo, cfg.dispositivo, cfg.precisao)
    c = Consumidor(cfg, Transcritor(cfg))
    signal.signal(signal.SIGTERM, lambda *_: setattr(c, "parar", True))
    signal.signal(signal.SIGINT, lambda *_: setattr(c, "parar", True))
    c.rodar()


if __name__ == "__main__":
    main()
