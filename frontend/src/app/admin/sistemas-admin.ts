import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { AdminApi, ChaveApi, Integrador } from '../core/admin-api';

const ESCOPOS = ['agendamento:escrever', 'agendamento:ler', 'sala:iniciar'];
const POR_PAGINA = 8;

/** Sistemas integrados, suas API keys (mostradas uma única vez) e o webhook de devolução (T16/T18/T19). */
@Component({
  selector: 'app-sistemas-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe],
  template: `
    <h2>Sistemas integrados</h2>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }

    <form class="acoes" (ngSubmit)="criar()">
      <input name="nome" class="form-control w-auto" placeholder="Nome do novo sistema" [(ngModel)]="nome" required />
      <button class="btn" type="submit" [disabled]="!nome()"><i class="bi bi-plus-circle" aria-hidden="true"></i> Cadastrar</button>
    </form>

    <div class="lista-barra">
      <span class="busca">
        <i class="bi bi-search" aria-hidden="true"></i>
        <input class="form-control" type="search" [ngModel]="busca()" (ngModelChange)="buscar($event)" placeholder="Buscar por nome…" aria-label="Buscar sistema" />
      </span>
    </div>

    @if (pagina().length === 0) { <p class="muted">Nenhum sistema encontrado.</p> }
    @else {
      <div class="table-responsive"><table class="table table-hover table-striped align-middle"><thead><tr><th>Nome</th><th>Situação</th><th>Webhook</th><th class="text-end">Ações</th></tr></thead>
        <tbody>@for (i of pagina(); track i.id) {
          <tr><td>{{ i.nome }}</td><td>{{ i.ativo ? 'ativo' : 'desativado' }}</td>
            <td>{{ i.webhookAtivo ? i.webhookBaseUrl : '—' }}</td>
            <td class="text-end"><div class="acoes-tabela justify-content-end">
              <button class="btn-icone" type="button" (click)="abrir(i)" title="Abrir / editar" aria-label="Abrir"><i class="bi bi-pencil-square" aria-hidden="true"></i></button>
              <button class="btn-icone" type="button" (click)="alternar(i)" [title]="i.ativo ? 'Desativar' : 'Ativar'" [attr.aria-label]="i.ativo ? 'Desativar' : 'Ativar'"><i class="bi" [class.bi-toggle-on]="i.ativo" [class.bi-toggle-off]="!i.ativo" aria-hidden="true"></i></button>
            </div></td></tr> }</tbody></table></div>
      <div class="paginacao">
        <span class="muted">{{ filtrados().length }} sistema(s) · página {{ indice() + 1 }} de {{ totalPaginas() }}</span>
        <button class="btn-icone" type="button" [disabled]="indice() === 0" (click)="ir(indice() - 1)" title="Anterior" aria-label="Página anterior"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
        <button class="btn-icone" type="button" [disabled]="indice() + 1 >= totalPaginas()" (click)="ir(indice() + 1)" title="Próxima" aria-label="Próxima página"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
      </div>
    }

    @if (aberto(); as i) {
      <div class="modal-fundo" role="dialog" aria-modal="true" aria-labelledby="tituloSistema">
        <div class="modal-caixa modal-largo">
          @if (segredoNovo(); as s) {
            <div class="card destaque-segredo" role="status">
              <p><strong>Guarde agora: este valor não será mostrado de novo.</strong></p>
              <p>{{ s.rotulo }}:</p>
              <input class="form-control" readonly [value]="s.valor" (focus)="$any($event.target).select()" [attr.aria-label]="s.rotulo" />
              <button class="btn secundario mt-2" type="button" (click)="segredoNovo.set(null)">Já guardei</button>
            </div>
          }
          <div class="modal-cabecalho">
            <h2 id="tituloSistema">{{ i.nome }}</h2>
            <button class="btn-icone" type="button" (click)="fechar()" title="Fechar" aria-label="Fechar"><i class="bi bi-x-lg" aria-hidden="true"></i></button>
          </div>

          <h3>API keys</h3>
          <div class="table-responsive"><table class="table table-hover table-striped align-middle"><thead><tr><th>Nome</th><th>Escopos</th><th>Último uso</th><th>Situação</th><th class="text-end">Ações</th></tr></thead>
            <tbody>@for (k of chaves(); track k.id) {
              <tr><td>{{ k.nome }}</td><td>{{ k.escopos }}</td><td>{{ k.ultimoUsoEm ? (k.ultimoUsoEm | date: 'dd/MM HH:mm') : 'nunca' }}</td>
                <td>{{ k.ativa ? 'ativa' : 'revogada' }}</td>
                <td class="text-end">@if (k.ativa) { <button class="btn-icone perigo" type="button" (click)="revogar(k)" title="Revogar" aria-label="Revogar"><i class="bi bi-x-circle" aria-hidden="true"></i></button> }</td></tr>
            } @empty { <tr><td colspan="5" class="muted">Nenhuma key.</td></tr> }</tbody></table></div>
          <form (ngSubmit)="criarChave()">
            <input name="nomeKey" class="form-control" placeholder="Nome da key (ex.: produção)" [(ngModel)]="nomeKey" required />
            @for (e of escopos; track e) {
              <label class="linha"><input type="checkbox" [checked]="escoposMarcados().includes(e)" (change)="marcar(e)" /> {{ e }}</label>
            }
            <button class="btn" type="submit" [disabled]="!nomeKey() || escoposMarcados().length === 0"><i class="bi bi-key" aria-hidden="true"></i> Gerar key</button>
          </form>

          <h3 class="mt-3">Webhook de devolução</h3>
          <form (ngSubmit)="salvarWebhook()">
            <label for="base">URL base (HTTPS)</label>
            <input id="base" name="base" class="form-control" [(ngModel)]="base" placeholder="https://api.cliente.com.br/hooks/medilink" />
            <label class="linha"><input type="checkbox" name="wativo" [(ngModel)]="webhookAtivo" /> Ativo</label>
            <label class="linha"><input type="checkbox" name="novoSegredo" [(ngModel)]="novoSegredo" /> Gerar novo segredo de assinatura</label>
            <button class="btn" type="submit"><i class="bi bi-save" aria-hidden="true"></i> Salvar webhook</button>
          </form>

          <div class="modal-rodape">
            <button class="btn secundario" type="button" (click)="fechar()">Fechar</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class SistemasAdmin {
  private readonly api = inject(AdminApi);
  protected readonly escopos = ESCOPOS;
  protected readonly lista = signal<Integrador[]>([]);
  protected readonly busca = signal('');
  protected readonly indice = signal(0);
  protected readonly aberto = signal<Integrador | null>(null);
  protected readonly chaves = signal<ChaveApi[]>([]);
  protected readonly erro = signal<string | null>(null);
  protected readonly segredoNovo = signal<{ rotulo: string; valor: string } | null>(null);
  protected readonly nome = signal('');
  protected readonly nomeKey = signal('');
  protected readonly escoposMarcados = signal<string[]>(['agendamento:escrever', 'agendamento:ler', 'sala:iniciar']);
  protected readonly base = signal('');
  protected readonly webhookAtivo = signal(false);
  protected readonly novoSegredo = signal(false);

  protected readonly filtrados = computed(() => {
    const q = this.busca().trim().toLowerCase();
    const l = this.lista();
    return q ? l.filter((i) => i.nome.toLowerCase().includes(q)) : l;
  });
  protected readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.filtrados().length / POR_PAGINA)));
  protected readonly pagina = computed(() => this.filtrados().slice(this.indice() * POR_PAGINA, this.indice() * POR_PAGINA + POR_PAGINA));

  constructor() {
    this.recarregar();
  }

  private recarregar(): void {
    this.api.integradores().subscribe({ next: (l) => this.lista.set(l), error: () => this.erro.set('Não foi possível carregar os sistemas.') });
  }

  protected buscar(q: string): void {
    this.busca.set(q);
    this.indice.set(0);
  }

  protected ir(n: number): void {
    this.indice.set(Math.max(0, Math.min(n, this.totalPaginas() - 1)));
  }

  protected criar(): void {
    this.api.criarIntegrador(this.nome()).subscribe({ next: () => { this.nome.set(''); this.recarregar(); }, error: () => this.erro.set('Não foi possível cadastrar (o nome já existe?).') });
  }

  protected alternar(i: Integrador): void {
    this.api.alterarIntegrador(i.id, !i.ativo).subscribe({ next: () => this.recarregar(), error: () => this.erro.set('Não foi possível alterar.') });
  }

  /** Abre o modal de um sistema a partir da lista: limpa o segredo anterior e carrega as keys. */
  protected abrir(i: Integrador): void {
    this.segredoNovo.set(null);
    this.aberto.set(i);
    this.base.set(i.webhookBaseUrl ?? '');
    this.webhookAtivo.set(i.webhookAtivo);
    this.novoSegredo.set(false);
    this.recarregarChaves(i);
  }

  /** O modal só fecha pelo botão (fechamento explícito), nunca por clique fora. */
  protected fechar(): void {
    this.aberto.set(null);
    this.segredoNovo.set(null);
  }

  private recarregarChaves(i: Integrador): void {
    this.api.chaves(i.id).subscribe({ next: (c) => this.chaves.set(c), error: () => this.erro.set('Não foi possível listar as keys.') });
  }

  protected marcar(e: string): void {
    this.escoposMarcados.update((l) => (l.includes(e) ? l.filter((x) => x !== e) : [...l, e]));
  }

  protected criarChave(): void {
    const i = this.aberto();
    if (!i) return;
    this.api.criarChave(i.id, this.nomeKey(), this.escoposMarcados()).subscribe({
      next: (k) => { this.segredoNovo.set({ rotulo: 'API key', valor: k.chave }); this.nomeKey.set(''); this.recarregarChaves(i); },
      error: () => this.erro.set('Não foi possível gerar a key.'),
    });
  }

  protected revogar(k: ChaveApi): void {
    const i = this.aberto();
    if (i) {
      this.api.revogarChave(k.id).subscribe({ next: () => this.recarregarChaves(i), error: () => this.erro.set('Não foi possível revogar.') });
    }
  }

  protected salvarWebhook(): void {
    const i = this.aberto();
    if (!i) return;
    this.api.webhook(i.id, this.base().trim() || null, this.webhookAtivo(), this.novoSegredo()).subscribe({
      next: (r) => {
        if (r.segredo) this.segredoNovo.set({ rotulo: 'Segredo de assinatura do webhook', valor: r.segredo });
        this.recarregar();
        this.aberto.set(r.integrador);
        this.recarregarChaves(r.integrador);
      },
      error: () => this.erro.set('Webhook recusado: a URL base precisa ser HTTPS, sem usuário, parâmetros nem fragmento.'),
    });
  }
}
