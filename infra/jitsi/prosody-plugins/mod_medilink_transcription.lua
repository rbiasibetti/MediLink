-- MediLink: habilita a exportação de áudio do Jitsi para o nosso audio sink (T11).
--
-- Na arquitetura nova de transcrição o Jicofo só manda o JVB exportar o áudio quando o metadado da sala tem
-- asyncTranscription = true (e recording.isTranscribingEnabled = true, que o cliente do médico liga/desliga).
-- Esses campos são do servidor: clientes, nem moderadores, conseguem escrevê-los, e a versão aberta não traz
-- quem os defina. Este módulo marca toda sala nova.
--
-- O JVB só informa ao sink o id da reunião (meeting_id), não o nome da sala. O metadado transcription.urlParams
-- é acrescentado à URL como query string, então entregamos "sala" para o sink achar a consulta no banco.
--
-- Carregar no componente MUC principal (XMPP_MUC_MODULES=...,medilink_transcription).
local jid = require 'util.jid';

module:log('info', 'MediLink: salas marcadas com asyncTranscription');

module:hook('muc-room-created', function(event)
    local room = event.room;
    if not room.jitsiMetadata then
        room.jitsiMetadata = {};
    end
    room.jitsiMetadata.asyncTranscription = true;
    room.jitsiMetadata.transcription = { urlParams = { sala = jid.node(room.jid) } };
end, 1);
