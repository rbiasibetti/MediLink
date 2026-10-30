"""Worker de transcrição do MediLink (T12): consome jobs da fila, transcreve o áudio de cada fonte com
faster-whisper e publica o texto, rotulado por locutor, no tópico de resultados."""
