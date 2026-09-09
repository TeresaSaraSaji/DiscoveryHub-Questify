import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ServiceStatus } from './shared/service-status';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ServiceStatus],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {}
