package com.playdata.orderingservice.ordering.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.orderingservice.common.auth.TokenUserInfo;
import com.playdata.orderingservice.ordering.dto.OrderingListResDto;
import com.playdata.orderingservice.ordering.dto.UserResDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@Slf4j
@RequiredArgsConstructor
public class SseController implements MessageListener {

    // 구독을 요청한 각 사용자의 이메일을 키로 하여 emitter 객체를 저장.
    // ConcurrentHashMap: 멀티 스레드 기반 해시맵 (HashMap은 싱글 스레드 기반)
    Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @Qualifier("sse-template")
    private final RedisTemplate<String, Object> sseRedisTemplate;

    private final RedisMessageListenerContainer redisMessageListenerContainer;

    @GetMapping("/subscribe")
    public SseEmitter subscribe(@AuthenticationPrincipal TokenUserInfo userInfo) {
        SseEmitter emitter = new SseEmitter(1440 * 60 * 1000L); // 알림 서비스 구현 핵심 객체.
        String email = userInfo.getEmail();
        emitters.put(email, emitter); // 이메일을 키로 emitter 저장

        log.info("Subscribing to {}", email);

        // 클라이언트가 연결을 끊거나, emitter의 수명이 다하면 맵에서 제거.
        emitter.onCompletion(() -> emitters.remove(email));
        emitter.onTimeout(() -> emitters.remove(email));

        // 연결 성공 메세지 전송
        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("connected!!!"));

            // 30초마다 heartbeat 메시지를 전송하여 연결 유지
            // 클라이언트에서 사용하는 EventSourcePolyfill이 45초 동안 활동이 없으면 지맘대로 연결 종료
            Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data("keep-alive")); // 클라이언트 단이 살아있는지 확인
                } catch (IOException e) {
                    emitters.remove(email);
                    System.out.println("Failed to send heartbeat, removing emitter for email: " + email);
                }
            }, 30, 30, TimeUnit.SECONDS); // 30초마다 heartbeat 메시지 전송

            // redis에 대해서도 subscribe를 진행하자.
            subscribeChannel(email);

        } catch (IOException e) {
            emitters.remove(email);
        }

        return emitter;
    }

    // email에 해당되는 메시지를 listen하는 listener를 추가해 줄 것.
    private void subscribeChannel(String email) {
        // 메시지가 수신된다면 어떤 객체의 어떤 메서드로 처리할 것인지를 객체 생성 때 알려줘야 한다.
        MessageListenerAdapter adapter
                = new MessageListenerAdapter(this, "onMessage");
        redisMessageListenerContainer.addMessageListener(adapter, new PatternTopic(email));
    }

    public void sendOrderMessage(UserResDto userDto) {
        // 주문 처리가 완료되면 호출되는 메서드.
        // 레디스에다가 주문이 되었다고 메세지를 쏴 주자.
        sseRedisTemplate.convertAndSend(userDto.getEmail(), userDto);

    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // message 내용을 parsing (json -> java 객체로)
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            UserResDto dto
                    = objectMapper.readValue(message.getBody(), UserResDto.class);

            String s = new String(pattern, "UTF-8"); // 채널명
            SseEmitter emitter = emitters.get("admin@admin.com");
            emitter.send(SseEmitter.event()
                    .name("ordered")
                    .data(dto));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}












