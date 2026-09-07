import { Injectable, NgZone, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { PriceTick } from '../models/market.model';

export type ConnectionStatus = 'connected' | 'reconnecting' | 'disconnected';

@Injectable({
  providedIn: 'root',
})
export class PriceStreamService implements OnDestroy {
  private eventSource: EventSource | null = null;
  private readonly pricesSubject = new BehaviorSubject<Record<string, PriceTick>>({});
  private readonly historySubject = new BehaviorSubject<Record<string, number[]>>({});
  private readonly statusSubject = new BehaviorSubject<ConnectionStatus>('disconnected');
  private reconnectTimeout: any = null;

  public readonly prices$: Observable<Record<string, PriceTick>> = this.pricesSubject.asObservable();
  public readonly history$: Observable<Record<string, number[]>> = this.historySubject.asObservable();
  public readonly status$: Observable<ConnectionStatus> = this.statusSubject.asObservable();

  constructor(private readonly zone: NgZone) {
    this.connect();
  }

  public connect(): void {
    if (this.eventSource) {
      this.eventSource.close();
    }

    this.statusSubject.next('reconnecting');

    try {
      this.eventSource = new EventSource('/api/stream/prices');

      this.eventSource.onopen = () => {
        this.zone.run(() => {
          this.statusSubject.next('connected');
        });
      };

      this.eventSource.addEventListener('prices', (event: MessageEvent) => {
        this.zone.run(() => {
          try {
            const ticks: PriceTick[] = JSON.parse(event.data);
            const currentPrices = { ...this.pricesSubject.value };
            const currentHistory = { ...this.historySubject.value };

            for (const tick of ticks) {
              currentPrices[tick.ticker] = tick;

              const tickerHistory = currentHistory[tick.ticker] ? [...currentHistory[tick.ticker]] : [];
              tickerHistory.push(tick.price);
              // Keep maximum 60 historical ticks for sparkline/chart
              if (tickerHistory.length > 60) {
                tickerHistory.shift();
              }
              currentHistory[tick.ticker] = tickerHistory;
            }

            this.pricesSubject.next(currentPrices);
            this.historySubject.next(currentHistory);
            this.statusSubject.next('connected');
          } catch (e) {
            console.error('Failed to parse price stream event:', e);
          }
        });
      });

      this.eventSource.onerror = () => {
        this.zone.run(() => {
          this.statusSubject.next('reconnecting');
          if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
          }
          if (!this.reconnectTimeout) {
            this.reconnectTimeout = setTimeout(() => {
              this.reconnectTimeout = null;
              this.connect();
            }, 3000);
          }
        });
      };
    } catch (err) {
      this.statusSubject.next('disconnected');
    }
  }

  ngOnDestroy(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
    }
  }
}
