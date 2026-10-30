import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ALTURA_VIDEO_NIVEL_2, E2EE_ATIVA_POR_PADRAO } from './config';
import { JitsiRoom, Papel } from './jitsi-room';

/** Jitsi falso: guarda os ouvintes e os comandos para conferir o que a sala configura. */
class JitsiFalso {
  static ultimo: JitsiFalso;
  readonly ouvintes = new Map<string, (dados?: { id?: string }) => void>();
  readonly comandos: [string, unknown[]][] = [];
  disposed = false;
  constructor(readonly dominio: string, readonly opcoes: Record<string, unknown>) { JitsiFalso.ultimo = this; }
  addEventListener(evento: string, fn: (dados?: { id?: string }) => void) { this.ouvintes.set(evento, fn); }
  executeCommand(comando: string, ...args: unknown[]) { this.comandos.push([comando, args]); }
  dispose() { this.disposed = true; }
  disparar(evento: string, dados?: { id?: string }) { this.ouvintes.get(evento)?.(dados); }
  comandosNomeados() { return this.comandos.map(([c]) => c); }
}

async function montar(papel: Papel, e2ee?: boolean, transcrever?: boolean) {
  (window as unknown as { JitsiMeetExternalAPI: unknown }).JitsiMeetExternalAPI = JitsiFalso;
  const fixture = TestBed.createComponent(JitsiRoom);
  fixture.componentRef.setInput('roomName', 'consulta-abc');
  fixture.componentRef.setInput('jwt', 'JWT');
  fixture.componentRef.setInput('papel', papel);
  if (e2ee !== undefined) fixture.componentRef.setInput('e2ee', e2ee);
  if (transcrever !== undefined) fixture.componentRef.setInput('transcrever', transcrever);
  const eventos = { pronto: 0, saiu: 0, participante: [] as string[] };
  fixture.componentInstance.pronto.subscribe(() => eventos.pronto++);
  fixture.componentInstance.saiu.subscribe(() => eventos.saiu++);
  fixture.componentInstance.participante.subscribe((id) => eventos.participante.push(id));
  fixture.detectChanges();
  await vi.waitFor(() => expect(JitsiFalso.ultimo).toBeDefined());
  return { fixture, eventos, jitsi: JitsiFalso.ultimo };
}

describe('JitsiRoom', () => {
  afterEach(() => {
    delete (window as unknown as { JitsiMeetExternalAPI?: unknown }).JitsiMeetExternalAPI;
    (JitsiFalso as { ultimo?: JitsiFalso }).ultimo = undefined;
  });

  it('abre a sala com o JWT do backend e sem tela de pré-entrada', async () => {
    const { jitsi } = await montar('medico');

    expect(jitsi.opcoes['roomName']).toBe('consulta-abc');
    expect(jitsi.opcoes['jwt']).toBe('JWT');
    // A opção atual do Jitsi é prejoinConfig.enabled; a antiga (prejoinPageEnabled) é ignorada em silêncio.
    expect(jitsi.opcoes['configOverwrite']).toMatchObject({ prejoinConfig: { enabled: false } });
    expect(jitsi.opcoes['configOverwrite']).not.toHaveProperty('prejoinPageEnabled');
  });

  it('por padrão a E2EE fica desligada (a captura de áudio no servidor depende disso)', () => {
    expect(E2EE_ATIVA_POR_PADRAO).toBe(false);
  });

  it('ao entrar, o médico aplica qualidade nível 2 e supressão de ruído e NÃO liga a E2EE por padrão', async () => {
    const { jitsi, eventos } = await montar('medico');
    expect(jitsi.comandos).toEqual([]); // nada antes de entrar na conferência

    jitsi.disparar('videoConferenceJoined');

    expect(jitsi.comandos).toContainEqual(['setVideoQuality', [ALTURA_VIDEO_NIVEL_2]]);
    expect(jitsi.comandos).toContainEqual(['setNoiseSuppressionEnabled', [{ enabled: true }]]);
    expect(jitsi.comandosNomeados()).not.toContain('toggleE2EE');
    expect(ALTURA_VIDEO_NIVEL_2).toBe(360);
    expect(eventos.pronto).toBe(1);
  });

  it('com o flag da E2EE ligado, só o médico (moderador) a aciona', async () => {
    const medico = await montar('medico', true);
    medico.jitsi.disparar('videoConferenceJoined');
    expect(medico.jitsi.comandos).toContainEqual(['toggleE2EE', [true]]);
    (JitsiFalso as { ultimo?: JitsiFalso }).ultimo = undefined;

    const paciente = await montar('paciente', true);
    paciente.jitsi.disparar('videoConferenceJoined');
    expect(paciente.jitsi.comandosNomeados()).toEqual(['setVideoQuality', 'setNoiseSuppressionEnabled']);
  });

  it('o paciente aplica qualidade e ruído e nunca mexe na criptografia', async () => {
    const { jitsi, eventos } = await montar('paciente');

    jitsi.disparar('videoConferenceJoined');

    expect(jitsi.comandosNomeados()).toEqual(['setVideoQuality', 'setNoiseSuppressionEnabled']);
    expect(eventos.pronto).toBe(1);
  });

  it('com o flag ligado, nunca desliga a criptografia (reentrada só religa)', async () => {
    const { jitsi } = await montar('medico', true);
    jitsi.disparar('videoConferenceJoined');
    jitsi.disparar('videoConferenceJoined');

    const toggles = jitsi.comandos.filter(([c]) => c === 'toggleE2EE');
    expect(toggles.length).toBeGreaterThan(0);
    expect(toggles.every(([, a]) => a[0] === true)).toBe(true);
  });

  it('não pede transcrição por padrão (o paciente nunca pede)', async () => {
    const { jitsi } = await montar('paciente');
    jitsi.disparar('videoConferenceJoined');

    expect(jitsi.comandosNomeados()).not.toContain('setSubtitles');
  });

  it('pede a transcrição ao entrar se a captura já está autorizada', async () => {
    const { jitsi } = await montar('medico', undefined, true);
    jitsi.disparar('videoConferenceJoined');

    expect(jitsi.comandos).toContainEqual(['setSubtitles', [true, false]]);
  });

  it('liga e desliga a transcrição conforme o consentimento, sem repetir comandos', async () => {
    const { fixture, jitsi } = await montar('medico');
    jitsi.disparar('videoConferenceJoined');
    const contar = (valor: boolean) => jitsi.comandos.filter(([c, a]) => c === 'setSubtitles' && a[0] === valor).length;
    expect(contar(true) + contar(false)).toBe(0);

    fixture.componentRef.setInput('transcrever', true);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(contar(true)).toBe(1);

    fixture.componentRef.setInput('transcrever', true); // mesmo estado: nada novo
    fixture.detectChanges();
    await fixture.whenStable();
    expect(contar(true)).toBe(1);

    fixture.componentRef.setInput('transcrever', false); // consentimento retirado
    fixture.detectChanges();
    await fixture.whenStable();
    expect(contar(false)).toBe(1);
    expect(jitsi.comandos.at(-1)).toEqual(['setSubtitles', [false, false]]);
  });

  it('informa o próprio endpoint do Jitsi ao entrar (para rotular o áudio)', async () => {
    const { jitsi, eventos } = await montar('paciente');

    jitsi.disparar('videoConferenceJoined', { id: '7bc45eb8' });

    expect(eventos.participante).toEqual(['7bc45eb8']);
  });

  it('sem id no evento não informa participante nenhum', async () => {
    const { jitsi, eventos } = await montar('medico');

    jitsi.disparar('videoConferenceJoined');

    expect(eventos.participante).toEqual([]);
  });

  it('não manda comando de transcrição antes de entrar na conferência', async () => {
    const { fixture, jitsi } = await montar('medico');

    fixture.componentRef.setInput('transcrever', true);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(jitsi.comandos).toEqual([]);
    jitsi.disparar('videoConferenceJoined');
    expect(jitsi.comandos).toContainEqual(['setSubtitles', [true, false]]);
  });

  it('emite "saiu" uma única vez, mesmo com os dois eventos de saída do Jitsi', async () => {
    const { jitsi, eventos } = await montar('medico');

    jitsi.disparar('videoConferenceLeft');
    jitsi.disparar('readyToClose');

    expect(eventos.saiu).toBe(1);
  });

  it('libera a conferência ao destruir o componente', async () => {
    const { fixture, jitsi } = await montar('paciente');
    fixture.destroy();
    expect(jitsi.disposed).toBe(true);
  });
});
