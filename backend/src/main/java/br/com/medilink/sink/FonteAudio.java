package br.com.medilink.sink;

import java.util.UUID;

/** Estado de uma fonte (um participante, identificado pela "tag" do JVB) numa conexão do audio sink. */
final class FonteAudio {

	final String tag;
	final String endpointId;
	final int canais;

	OggOpusWriter writer;
	UUID audioId;
	/** Timestamp RTP (48 kHz) do último pacote gravado; nulo quando a linha do tempo foi interrompida. */
	Long ultimoTimestamp;
	int amostrasUltimoPacote;
	long descartados;

	FonteAudio(String tag, String endpointId, int canais) {
		this.tag = tag;
		this.endpointId = endpointId;
		this.canais = canais;
	}

	/** Depois de um trecho descartado (sem consentimento), não se preenche o intervalo com silêncio. */
	void interromperLinhaDoTempo() {
		this.ultimoTimestamp = null;
	}
}
