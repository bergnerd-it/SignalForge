package com.bergnerd.signalforge.app.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceBroadcaster {

    private final MarketDataSource marketDataSource;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter registerEmitter() {
        // 30-minute timeout for SSE emitter
        SseEmitter emitter = new SseEmitter(1800000L);
        emitters.add(emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed");
            emitters.remove(emitter);
        });

        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out");
            emitter.complete();
            emitters.remove(emitter);
        });

        emitter.onError(e -> {
            log.debug("SSE emitter error: {}", e.getMessage());
            emitter.complete();
            emitters.remove(emitter);
        });

        // Send initial prices immediately
        try {
            Collection<PriceTick> initialTicks = marketDataSource.getAllPrices().values();
            emitter.send(SseEmitter.event()
                    .name("prices")
                    .data(initialTicks));
        } catch (IOException e) {
            log.debug("Failed to send initial prices: {}", e.getMessage());
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Scheduled(fixedRate = 500)
    public void broadcastPrices() {
        if (emitters.isEmpty()) {
            return;
        }

        Collection<PriceTick> ticks = marketDataSource.getAllPrices().values();
        if (ticks.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("prices")
                        .data(ticks));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    public int getActiveClientCount() {
        return emitters.size();
    }
}
