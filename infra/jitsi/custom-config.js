// Anexado ao final do config.js do Jitsi (o init do container lê ${CONFIG}/web/custom-config.js; o dev.sh
// up instala este arquivo). Define o que a sala do MediLink oferece: o resto fica desabilitado.
//
// Importante: isto limita a INTERFACE. O que o servidor impõe de verdade está no JWT (context.features,
// ver JitsiTokenService) e no JWT em si (sem token, ninguém entra). Veja docs/plan.md, seção "Configuração
// da sala", para a força de cada trava.

// Barra de ferramentas mínima: só o que a consulta precisa. Ficam de fora (= desabilitados): invite, chat,
// desktop (compartilhar tela), sharedvideo (compartilhar vídeo), raisehand, participants-pane (busca de
// participantes e salas de apoio), security (inclui o botão que desligaria a criptografia), recording,
// livestreaming, shareaudio, etherpad, whiteboard, stats, closedcaptions, entre outros.
config.toolbarButtons = [
    'microphone',
    'camera',
    'noisesuppression',   // supressão de ruído extra (RNNoise)
    'videoquality',       // "Definições de desempenho"; a sala inicia no nível 2 (ver JitsiRoom)
    'tileview',
    'fullscreen',
    'settings',           // escolha de microfone/câmera
    'hangup',
];

// Convite: "desabilitada" tanto para convidar alguém quanto para convidar pessoas (e a busca de pessoas
// do diálogo de convite).
config.disableInviteFunctions = true;

// Chat inteiro (inclui mensagens privadas, sons e notificações) e enquetes.
config.disableChat = true;
config.disablePolls = true;

// Erguer/abaixar a mão: sem o botão, sem atalho de teclado e sem o menu de reações (que também o oferece).
config.disableShortcuts = true;
config.disableReactions = true;

// Salas de apoio (breakout rooms): sem adicionar, sem atribuição automática, sem entrar.
config.breakoutRooms = {
    hideAddRoomButton: true,
    hideAutoAssignButton: true,
    hideJoinRoomButton: true,
};
config.hideAddRoomButton = true; // legado; mantido por compatibilidade

// Busca de participantes: o painel de participantes já está fora da barra; aqui também a estatística de
// quem fala (que tem campo de busca).
config.speakerStats = { disabled: true, disableSearch: true };

// Áudio: processamento do navegador (cancelamento de eco, controle de ganho e supressão de ruído) explicitamente
// ligado. A supressão de ruído "extra" (RNNoise) é ligada pelo cliente ao entrar (JitsiRoom).
config.disableAP = false;
config.disableAEC = false;
config.disableAGC = false;
config.disableNS = false;

// Mídia sempre pelo JVB: sem P2P (reforça ENABLE_P2P=0 do .env). Necessário para o audio sink da T11 receber
// o áudio mesmo em sala de duas pessoas.
config.p2p = Object.assign(config.p2p || {}, { enabled: false });

// Sem tela de pré-entrada: o participante entra direto na sala (a identidade e o papel vêm do JWT). A opção
// antiga "prejoinPageEnabled" não existe mais e é ignorada em silêncio; a atual é prejoinConfig.enabled.
config.prejoinConfig = Object.assign(config.prejoinConfig || {}, { enabled: false });
