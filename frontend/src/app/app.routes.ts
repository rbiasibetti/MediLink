import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { MedicoAuth } from './core/medico-auth';
import { LoginMedico } from './medico/login-medico';
import { Agendar } from './medico/agendar';
import { ConsultaMedico } from './medico/consulta-medico';
import { AcessoPaciente } from './paciente/acesso-paciente';

/** Rotas de médico exigem o login; sem ele volta para a tela de acesso. */
export const medicoGuard: CanActivateFn = () => {
  const auth = inject(MedicoAuth);
  return auth.logado() ? true : inject(Router).createUrlTree(['/medico']);
};

export const routes: Routes = [
  // O link enviado ao paciente: {public-base-url}/sala/{roomName}
  { path: 'sala/:roomName', component: AcessoPaciente },
  { path: 'medico', component: LoginMedico },
  { path: 'medico/agendar', component: Agendar, canActivate: [medicoGuard] },
  { path: 'medico/consulta/:agendamentoId', component: ConsultaMedico, canActivate: [medicoGuard] },
  { path: '', pathMatch: 'full', redirectTo: 'medico' },
  { path: '**', redirectTo: 'medico' },
];
