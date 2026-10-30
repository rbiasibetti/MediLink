import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { AdminApi, Termo } from '../core/admin-api';

/**
 * Gestão do termo de consentimento (T18). Rascunho edita; publicar torna vigente e arquiva o anterior; versão publicada é
 * imutável (o texto que o paciente aceitou precisa continuar recuperável). O texto é exibido como texto puro.
 */
@Component({
  selector: 'app-termo-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe],
  template: `
    <h2>Termo de consentimento</h2>
    <p class="muted">Toda teleconsulta é registrada: o paciente só segue se aceitar o termo vigente. Alterar o termo é publicar uma nova versão.</p>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    <table><thead><tr><th>Versão</th><th>Situação</th><th>Criado por</th><th>Publicado em</th><th></th></tr></thead>
      <tbody>@for (t of termos(); track t.id) {
        <tr><td>{{ t.versao }}</td><td>{{ t.status }}</td><td>{{ t.criadoPor }}</td><td>{{ t.publicadoEm ? (t.publicadoEm | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
          <td><button class="btn secundario" type="button" (click)="abrir(t)">{{ t.status === 'RASCUNHO' ? 'Editar' : 'Ver' }}</button></td></tr> }</tbody></table>
    <button class="btn" type="button" (click)="novoRascunho()">Nova versão (a partir da vigente)</button>

    @if (aberto(); as t) {
      <section class="card">
        <h3>Versão {{ t.versao }} — {{ t.status }}</h3>
        <label for="texto">Texto (texto puro, sem HTML)</label>
        <textarea id="texto" name="texto" rows="18" [ngModel]="texto()" (ngModelChange)="texto.set($event)" [readOnly]="t.status !== 'RASCUNHO'"></textarea>
        <p class="muted">{{ texto().length }} caracteres · hash do texto salvo: <code>{{ t.hash }}</code></p>
        @if (t.status === 'RASCUNHO') {
          <div class="acoes">
            <button class="btn secundario" type="button" (click)="salvar(t)">Salvar rascunho</button>
            <button class="btn" type="button" (click)="publicar(t)">Publicar como vigente</button>
            <button class="btn secundario" type="button" (click)="excluir(t)">Excluir rascunho</button>
          </div>
        }
      </section>
    }
  `,
})
export class TermoAdmin {
  private readonly api = inject(AdminApi);
  protected readonly termos = signal<Termo[]>([]);
  protected readonly aberto = signal<Termo | null>(null);
  protected readonly texto = signal('');
  protected readonly erro = signal<string | null>(null);

  constructor() {
    this.recarregar();
  }

  private recarregar(): void {
    this.api.termos().subscribe({ next: (l) => this.termos.set(l), error: () => this.erro.set('Não foi possível carregar os termos.') });
  }

  protected abrir(t: Termo): void {
    this.erro.set(null);
    this.aberto.set(t);
    this.texto.set(t.texto);
  }

  protected novoRascunho(): void {
    const base = this.termos().find((t) => t.status === 'VIGENTE')?.texto ?? '';
    this.api.criarTermo(base).subscribe({ next: (t) => { this.recarregar(); this.abrir(t); }, error: () => this.erro.set('Não foi possível criar o rascunho.') });
  }

  protected salvar(t: Termo): void {
    this.api.editarTermo(t.id, this.texto()).subscribe({
      next: (n) => { this.recarregar(); this.abrir(n); },
      error: () => this.erro.set('Texto recusado: de 100 a 20.000 caracteres, sem caracteres de controle.'),
    });
  }

  protected publicar(t: Termo): void {
    this.api.publicarTermo(t.id).subscribe({ next: (n) => { this.recarregar(); this.abrir(n); }, error: () => this.erro.set('Não foi possível publicar.') });
  }

  protected excluir(t: Termo): void {
    this.api.excluirTermo(t.id).subscribe({ next: () => { this.aberto.set(null); this.recarregar(); }, error: () => this.erro.set('Não foi possível excluir.') });
  }
}
