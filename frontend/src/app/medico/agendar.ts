import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AgendamentoCriado, Api } from '../core/api';

@Component({
  selector: 'app-agendar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, DatePipe],
  template: `
    <main class="pagina">
      <section class="card">
        <h1>Novo agendamento</h1>
        <form (ngSubmit)="criar()">
          <label for="nome">Nome do paciente</label>
          <input id="nome" name="nome" autocomplete="off" [(ngModel)]="nome" required maxlength="200" />
          <label for="cpf">CPF do paciente</label>
          <input id="cpf" name="cpf" inputmode="numeric" autocomplete="off" maxlength="14" placeholder="000.000.000-00" [(ngModel)]="cpf" required />
          <label for="email">E-mail do paciente (opcional)</label>
          <input id="email" name="email" type="email" autocomplete="off" [(ngModel)]="email" />
          <label for="quando">Data e hora</label>
          <input id="quando" name="quando" type="datetime-local" [(ngModel)]="quando" required />
          <label class="linha">
            <input type="checkbox" name="validar" [(ngModel)]="validarCpf" />
            Exigir confirmação do CPF pelo paciente
          </label>
          @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
          <button class="btn" type="submit" [disabled]="enviando() || !nome() || !cpf() || !quando()">Agendar</button>
        </form>
      </section>

      @if (criado(); as c) {
        <section class="card" aria-live="polite">
          <h2>Agendamento criado</h2>
          <p>{{ c.pacienteNome }} · {{ c.dataHora | date: 'dd/MM/yyyy HH:mm' }}</p>
          <label for="link">Link da consulta</label>
          <input id="link" readonly [value]="c.link" />
          <div class="acoes">
            <button class="btn secundario" type="button" (click)="copiar(c.link)">Copiar link</button>
            <button class="btn secundario" type="button" (click)="enviarEmail(c.id)" [disabled]="!temEmail()">Enviar por e-mail</button>
            <a class="btn" [routerLink]="['/medico/consulta', c.id]">Entrar na sala</a>
          </div>
          @if (aviso()) { <p role="status">{{ aviso() }}</p> }
        </section>
      }
    </main>
  `,
})
export class Agendar {
  private readonly api = inject(Api);

  protected readonly nome = signal('');
  protected readonly cpf = signal('');
  protected readonly email = signal('');
  protected readonly quando = signal('');
  protected readonly validarCpf = signal(true);
  protected readonly erro = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly enviando = signal(false);
  protected readonly criado = signal<AgendamentoCriado | null>(null);
  protected readonly temEmail = signal(false);

  protected criar(): void {
    this.enviando.set(true);
    this.erro.set(null);
    this.aviso.set(null);
    const email = this.email().trim();
    this.api.criarAgendamento({
      pacienteNome: this.nome().trim(),
      pacienteCpf: this.cpf().trim(),
      pacienteEmail: email || undefined,
      dataHora: new Date(this.quando()).toISOString(),
      cpfValidationEnabled: this.validarCpf(),
    }).subscribe({
      next: (c) => {
        this.enviando.set(false);
        this.criado.set(c);
        this.temEmail.set(email !== '');
        this.cpf.set('');
      },
      error: (e: HttpErrorResponse) => {
        this.enviando.set(false);
        this.erro.set(e.status === 400 ? 'Confira o CPF, o e-mail e a data (precisa ser futura).' : 'Não foi possível agendar. Tente novamente.');
      },
    });
  }

  protected copiar(link: string): void {
    void navigator.clipboard?.writeText(link).then(() => this.aviso.set('Link copiado.'));
  }

  protected enviarEmail(id: string): void {
    this.aviso.set(null);
    this.api.enviarEmail(id).subscribe({
      next: () => this.aviso.set('E-mail enviado ao paciente.'),
      error: () => this.aviso.set('Não foi possível enviar o e-mail. Copie o link e envie por outro meio.'),
    });
  }
}
