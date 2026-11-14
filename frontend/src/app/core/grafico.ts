import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, input, viewChild } from '@angular/core';
import Chart from 'chart.js/auto';

/** Paleta categórica (estilo SB Admin 2), legível em claro e escuro. A legenda com os valores fica no HTML (acessível). */
export const PALETA = ['#4e73df', '#1cc88a', '#f6c23e', '#e74a3b', '#36b9cc', '#858796', '#6f42c1', '#fd7e14'];

/**
 * Gráfico dinâmico (Chart.js) de uma série rotulada. Interativo (tooltips ao passar o mouse) e responsivo. O
 * texto com os valores vai no `aria-label` e numa legenda própria no componente que o usa (o canvas não é lido
 * por leitores de tela). Não desenha quando o canvas não tem contexto 2D (ambiente de testes).
 */
@Component({
  selector: 'app-grafico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="grafico-wrap"><canvas #cv role="img" [attr.aria-label]="rotulo()"></canvas></div>`,
})
export class Grafico implements OnDestroy {
  readonly tipo = input<'doughnut' | 'bar'>('doughnut');
  readonly labels = input<string[]>([]);
  readonly dados = input<number[]>([]);
  readonly titulo = input('');

  private readonly cv = viewChild.required<ElementRef<HTMLCanvasElement>>('cv');
  private chart: Chart<'doughnut' | 'bar', number[], string> | null = null;
  private pronto = false;

  constructor() {
    afterNextRender(() => { this.pronto = true; this.desenhar(); });
    effect(() => {
      this.labels(); this.dados(); this.tipo();  // redesenha quando os dados mudam
      if (this.pronto) this.desenhar();
    });
  }

  protected rotulo(): string {
    const l = this.labels();
    const d = this.dados();
    return `${this.titulo()}: ${l.map((x, i) => `${x} ${d[i]}`).join(', ') || 'sem dados'}`;
  }

  private desenhar(): void {
    const ctx = this.cv().nativeElement.getContext('2d');
    if (!ctx) return;  // sem canvas real (testes): fica só a legenda em HTML
    this.chart?.destroy();
    const cores = this.labels().map((_, i) => PALETA[i % PALETA.length]);
    const superficie = getComputedStyle(document.documentElement).getPropertyValue('--superficie').trim() || '#fff';
    const doughnut = this.tipo() === 'doughnut';
    this.chart = new Chart(ctx, {
      type: this.tipo(),
      data: {
        labels: this.labels(),
        datasets: [{ data: this.dados(), backgroundColor: cores, borderColor: superficie, borderWidth: doughnut ? 2 : 0, borderRadius: doughnut ? 0 : 4 }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: doughnut ? '62%' : undefined,
        plugins: { legend: { display: false } },
        scales: doughnut ? {} : { y: { beginAtZero: true, ticks: { precision: 0 } } },
      },
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }
}
