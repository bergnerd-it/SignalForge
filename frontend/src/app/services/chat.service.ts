import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { ChatMessage, ChatResponse } from '../models/market.model';
import { PortfolioService } from './portfolio.service';
import { WatchlistService } from './watchlist.service';

@Injectable({
  providedIn: 'root',
})
export class ChatService {
  private readonly messagesSubject = new BehaviorSubject<ChatMessage[]>([]);
  public readonly messages$: Observable<ChatMessage[]> = this.messagesSubject.asObservable();
  private readonly isThinkingSubject = new BehaviorSubject<boolean>(false);
  public readonly isThinking$: Observable<boolean> = this.isThinkingSubject.asObservable();

  constructor(
    private readonly http: HttpClient,
    private readonly portfolioService: PortfolioService,
    private readonly watchlistService: WatchlistService
  ) {
    this.loadHistory();
  }

  public loadHistory(): void {
    this.http.get<any[]>('/api/chat/history').subscribe({
      next: (rows) => {
        const msgs: ChatMessage[] = rows.map((r) => {
          let parsedActions;
          if (r.actions) {
            if (typeof r.actions === 'string') {
              try {
                parsedActions = JSON.parse(r.actions);
              } catch {
                parsedActions = undefined;
              }
            } else if (Array.isArray(r.actions)) {
              parsedActions = r.actions;
            }
          }
          return {
            id: r.id,
            role: r.role,
            content: r.content,
            actions: parsedActions,
            createdAt: r.createdAt,
          };
        });
        if (msgs.length === 0) {
          // Default initial greeting if history is empty
          this.messagesSubject.next([
            {
              role: 'assistant',
              content: 'Hello! I am FinAlly, your AI trading assistant. I can analyze your positions, suggest portfolio adjustments, and execute market trades or manage your watchlist directly. How can I help you today?',
              createdAt: new Date().toISOString(),
            },
          ]);
        } else {
          this.messagesSubject.next(msgs);
        }
      },
      error: (err) => console.error('Failed to load chat history:', err),
    });
  }

  public clearHistory(): Observable<void> {
    return this.http.delete<void>('/api/chat/history').pipe(
      tap({
        next: () => {
          this.messagesSubject.next([
            {
              role: 'assistant',
              content: 'Hello! I am FinAlly, your AI trading assistant. I can analyze your positions, suggest portfolio adjustments, and execute market trades or manage your watchlist directly. How can I help you today?',
              createdAt: new Date().toISOString(),
            },
          ]);
        },
      })
    );
  }

  public sendMessage(userMessage: string): Observable<ChatResponse> {
    const currentMsgs = this.messagesSubject.value;
    const now = new Date().toISOString();
    const userMsgObj: ChatMessage = {
      role: 'user',
      content: userMessage,
      createdAt: now,
    };

    this.messagesSubject.next([...currentMsgs, userMsgObj]);
    this.isThinkingSubject.next(true);

    return this.http.post<ChatResponse>('/api/chat', { message: userMessage }).pipe(
      tap({
        next: (res) => {
          this.isThinkingSubject.next(false);
          const assistantMsgObj: ChatMessage = {
            role: 'assistant',
            content: res.message,
            actions: res.actions,
            createdAt: res.createdAt || new Date().toISOString(),
          };
          this.messagesSubject.next([...this.messagesSubject.value, assistantMsgObj]);

          // Refresh portfolio and watchlist if actions executed
          if (res.actions && res.actions.length > 0) {
            this.portfolioService.refreshPortfolio();
            this.watchlistService.refreshWatchlist();
          }
        },
        error: (err) => {
          this.isThinkingSubject.next(false);
          const detail =
            (typeof err?.error === 'string' && err.error.trim()) ||
            err?.error?.message ||
            err?.error?.error ||
            (!err?.message?.startsWith('Http failure response') ? err?.message : null) ||
            'Server communication failed';
          const errorMsgObj: ChatMessage = {
            role: 'assistant',
            content: 'Sorry, I encountered an error communicating with the server: ' + detail,
            createdAt: new Date().toISOString(),
          };
          this.messagesSubject.next([...this.messagesSubject.value, errorMsgObj]);
        },
      })
    );
  }
}
