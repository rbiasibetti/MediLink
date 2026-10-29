package br.com.medilink.sink;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class AudioSinkConfig implements WebSocketConfigurer {

	private final AudioSinkHandler handler;
	private final SinkProperties props;

	AudioSinkConfig(AudioSinkHandler handler, SinkProperties props) {
		this.handler = handler;
		this.props = props;
	}

	/** O JVB conecta em /api/audio-sink/{meetingId}?sala=...; o Jicofo preenche {{MEETING_ID}} no url-template. */
	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/api/audio-sink/*").addInterceptors(new AudioSinkHandshake(props));
	}
}
