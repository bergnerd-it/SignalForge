import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: 'demo', children: [] },
  { path: 'research', children: [] },
  { path: 'research/data', children: [] },
  { path: 'research/backtests', children: [] },
  { path: 'research/backtests/:id', children: [] },
];
