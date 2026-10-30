import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { MedicoAuth } from './core/medico-auth';
import { LoginMedico } from './medico/login-medico';
import { Agendar } from './medico/agendar';
import { ConsultaMedico } from './medico/consulta-medico';
import { AcessoPaciente } from './paciente/acesso-paciente';
import { AdminAuth } from './core/admin-auth';
import { LoginAdmin } from './admin/login-admin';
import { PainelAdmin } from './admin/painel-admin';
import { AcessoMedico } from './medico/acesso-medico';
import { TranscricaoMedico } from './medico/transcricao-medico';

/** Rotas de médico exigem o login; sem ele volta para a tela de acesso. */
export const medicoGuard: CanActivateFn = () => {
  const auth = inject(MedicoAuth);
  return auth.logado() ? true : inject(Router).createUrlTree(['/medico']);
};

/** Área administrativa exige a sessão do administrador (senha + TOTP). */
export const adminGuard: CanActivateFn = () => {
  return inject(AdminAuth).logado() ? true : inject(Router).createUrlTree(['/admin']);
};

export const routes: Routes = [
  { path: 'admin', component: LoginAdmin },
  { path: 'admin/painel', component: PainelAdmin, canActivate: [adminGuard] },
  // O link enviado ao paciente: {public-base-url}/sala/{roomName}
  { path: 'sala/:roomName', component: AcessoPaciente },
  { path: 'medico', component: LoginMedico },
  // Link de uso único enviado pelo sistema de origem (T17): {base}/medico/acesso#t=<token>
  { path: 'medico/acesso', component: AcessoMedico },
  { path: 'medico/transcricao/:consultaId', component: TranscricaoMedico, canActivate: [medicoGuard] },
  { path: 'medico/agendar', component: Agendar, canActivate: [medicoGuard] },
  { path: 'medico/consulta/:agendamentoId', component: ConsultaMedico, canActivate: [medicoGuard] },
  { path: '', pathMatch: 'full', redirectTo: 'medico' },
  { path: '**', redirectTo: 'medico' },
];
