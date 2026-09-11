import { Component, ElementRef, EventEmitter, Input, Output, ViewChild, AfterViewChecked, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatMessage } from '../../models/market.model';

@Component({
  selector: 'app-chat-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="chat-card">
      <div class="chat-header">
        <div class="header-left">
          <span class="ai-avatar">🤖</span>
          <div class="title-group">
            <span class="chat-title">AI COPILOT</span>
            <span class="chat-status">Autonomous Execution Active</span>
          </div>
        </div>
        <div class="header-right">
          <button
            type="button"
            class="btn-clear font-mono"
            (click)="onClear()"
            [disabled]="isThinking"
            title="Clear chat history"
            aria-label="Clear chat history"
          >
            CLEAR
          </button>
        </div>
      </div>

      <div class="messages-container" #scrollContainer (scroll)="onScroll($event)">
        <div *ngFor="let msg of messages" class="message-wrapper" [ngClass]="msg.role === 'user' ? 'user-wrapper' : 'assistant-wrapper'">
          <div class="message-bubble" [ngClass]="msg.role === 'user' ? 'user-bubble' : 'assistant-bubble'">
            <div class="sender-tag font-mono">{{ msg.role === 'user' ? 'YOU' : 'SIGNALFORGE AI' }}</div>
            <div class="message-text">{{ msg.content }}</div>

            <!-- Inline Action Confirmation Cards -->
            <div *ngIf="msg.actions && msg.actions.length > 0" class="actions-container font-mono">
              <div *ngFor="let action of msg.actions" class="action-card" [ngClass]="action.success ? 'action-success' : 'action-failed'">
                <div class="action-header">
                  <span class="action-icon">{{ action.success ? '✓' : '⚠' }}</span>
                  <span class="action-type">{{ action.type | uppercase }}</span>
                  <span class="action-ticker font-bold">{{ action.ticker }}</span>
                </div>
                <div class="action-details">{{ action.details }}</div>
                <div *ngIf="action.error" class="action-error">{{ action.error }}</div>
              </div>
            </div>
          </div>
        </div>

        <div *ngIf="isThinking" class="thinking-wrapper">
          <div class="thinking-bubble">
            <span class="dot-typing"></span>
            <span class="thinking-text font-mono">AI analyzing markets & portfolio...</span>
          </div>
        </div>
      </div>

      <!-- Quick prompt pills -->
      <div class="quick-prompts">
        <button type="button" class="prompt-pill" (click)="sendQuickPrompt('Analyze my portfolio risk and P&L')">
          📊 Portfolio Analysis
        </button>
        <button type="button" class="prompt-pill" (click)="sendQuickPrompt('Buy 10 shares of AAPL')">
          🟢 Buy 10 AAPL
        </button>
        <button type="button" class="prompt-pill" (click)="sendQuickPrompt('Add NVDA to watchlist')">
          ⭐ Add NVDA
        </button>
      </div>

      <form (ngSubmit)="onSend()" class="input-form">
        <input
          type="text"
          [(ngModel)]="userInput"
          name="userInput"
          placeholder="Ask AI to trade or analyze..."
          class="chat-input"
          [disabled]="isThinking"
        />
        <button type="submit" class="btn-send font-mono" [disabled]="!userInput.trim() || isThinking">
          SEND
        </button>
      </form>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      flex: 1;
      overflow: hidden;
    }

    .chat-card {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      flex: 1;
      overflow: hidden;
    }

    .chat-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: rgba(255, 255, 255, 0.02);
      flex-shrink: 0;
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .header-right {
      display: flex;
      align-items: center;
    }

    .btn-clear {
      background: transparent;
      border: 1px solid var(--border-color);
      border-radius: 4px;
      color: var(--text-dim);
      padding: 3px 8px;
      font-size: 10px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .btn-clear:hover:not(:disabled) {
      border-color: #ef4444;
      color: #f87171;
      background-color: rgba(239, 68, 68, 0.1);
    }
    .btn-clear:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .ai-avatar {
      font-size: 20px;
    }

    .title-group {
      display: flex;
      flex-direction: column;
    }

    .chat-title {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: var(--text-main);
    }

    .chat-status {
      font-size: 10px;
      color: var(--color-green);
      font-weight: 600;
    }

    .messages-container {
      flex: 1;
      min-height: 0;
      padding: 16px;
      overflow-y: auto;
      overflow-x: hidden;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .message-wrapper {
      display: flex;
      width: 100%;
    }
    .user-wrapper {
      justify-content: flex-end;
    }
    .assistant-wrapper {
      justify-content: flex-start;
    }

    .message-bubble {
      max-width: 88%;
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
      line-height: 1.45;
    }

    .user-bubble {
      background-color: #1f304d;
      border: 1px solid rgba(32, 157, 215, 0.3);
      color: #ffffff;
      border-bottom-right-radius: 2px;
    }

    .assistant-bubble {
      background-color: var(--bg-main);
      border: 1px solid var(--border-color);
      color: var(--text-main);
      border-bottom-left-radius: 2px;
    }

    .sender-tag {
      font-size: 9px;
      font-weight: 700;
      color: var(--text-dim);
      margin-bottom: 4px;
    }

    .user-bubble .sender-tag {
      color: var(--color-primary);
    }

    .actions-container {
      margin-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .action-card {
      padding: 8px 10px;
      border-radius: 4px;
      font-size: 11px;
    }

    .action-success {
      background-color: rgba(16, 185, 129, 0.12);
      border: 1px solid rgba(16, 185, 129, 0.3);
      color: #34d399;
    }

    .action-failed {
      background-color: rgba(239, 68, 68, 0.12);
      border: 1px solid rgba(239, 68, 68, 0.3);
      color: #f87171;
    }

    .action-header {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
      margin-bottom: 2px;
    }

    .action-icon {
      font-weight: 700;
    }

    .action-details {
      font-size: 11px;
    }

    .action-error {
      font-size: 10px;
      margin-top: 2px;
      opacity: 0.9;
    }

    .thinking-wrapper {
      display: flex;
      justify-content: flex-start;
    }

    .thinking-bubble {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background-color: var(--bg-main);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      color: var(--text-dim);
      font-size: 11px;
    }

    .quick-prompts {
      display: flex;
      gap: 6px;
      padding: 8px 12px;
      overflow-x: auto;
      border-top: 1px solid rgba(48, 54, 61, 0.5);
      background-color: rgba(0, 0, 0, 0.15);
      flex-shrink: 0;
    }

    .prompt-pill {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      color: var(--text-muted);
      padding: 4px 10px;
      font-size: 11px;
      white-space: nowrap;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .prompt-pill:hover {
      border-color: var(--color-primary);
      color: var(--text-main);
      background-color: var(--bg-card-hover);
    }

    .input-form {
      display: flex;
      padding: 12px;
      gap: 8px;
      border-top: 1px solid var(--border-color);
      background-color: var(--bg-card);
      flex-shrink: 0;
    }

    .chat-input {
      flex: 1;
      background-color: var(--bg-input);
      border: 1px solid var(--border-color);
      border-radius: 4px;
      padding: 8px 12px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .chat-input:focus {
      border-color: var(--color-primary);
    }

    .btn-send {
      background-color: var(--color-secondary);
      color: #ffffff;
      border: none;
      border-radius: 4px;
      padding: 0 16px;
      font-size: 12px;
      font-weight: 700;
      cursor: pointer;
      transition: background-color 0.15s ease;
    }
    .btn-send:hover:not(:disabled) {
      background-color: var(--color-secondary-hover);
    }
    .btn-send:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  `]
})
export class ChatPanelComponent implements AfterViewChecked, OnChanges {
  @Input() messages: ChatMessage[] = [];
  @Input() isThinking: boolean = false;

  @Output() readonly sendMessage = new EventEmitter<string>();
  @Output() readonly clearChat = new EventEmitter<void>();

  @ViewChild('scrollContainer') private scrollContainer!: ElementRef;

  public userInput: string = '';
  private shouldScrollToBottom: boolean = true;
  private prevMessageCount: number = 0;
  private prevThinking: boolean = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['messages'] && this.messages && this.messages.length !== this.prevMessageCount) {
      this.prevMessageCount = this.messages.length;
      this.shouldScrollToBottom = true;
    }
    if (changes['isThinking'] && this.isThinking !== this.prevThinking) {
      this.prevThinking = this.isThinking;
      this.shouldScrollToBottom = true;
    }
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  public onScroll(event: Event): void {
    const el = event.target as HTMLElement;
    if (el) {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 50;
      this.shouldScrollToBottom = atBottom;
    }
  }

  private scrollToBottom(): void {
    try {
      if (this.scrollContainer) {
        this.scrollContainer.nativeElement.scrollTop = this.scrollContainer.nativeElement.scrollHeight;
      }
    } catch (err) {}
  }

  public onSend(): void {
    if (this.userInput.trim() && !this.isThinking) {
      this.shouldScrollToBottom = true;
      this.sendMessage.emit(this.userInput.trim());
      this.userInput = '';
    }
  }

  public sendQuickPrompt(prompt: string): void {
    if (!this.isThinking) {
      this.shouldScrollToBottom = true;
      this.sendMessage.emit(prompt);
    }
  }

  public onClear(): void {
    this.clearChat.emit();
  }
}
