package ru.spring.demo.reactive.bigbro.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rsocket.*;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import io.rsocket.util.DefaultPayload;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import ru.spring.demo.reactive.bigbro.services.LetterDecoder;
import ru.spring.demo.reactive.starter.speed.model.DecodedLetter;
import ru.spring.demo.reactive.starter.speed.model.Letter;

import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class ReactiveConfiguration {

    @Bean
    public Mono<RSocket> guardRSocket() {
        return RSocketFactory.connect()
                .transport(WebsocketClientTransport.create(
                        HttpClient.from(TcpClient.create()
                                .host("localhost")
                                .port(8082)),
                        "/rs"
                ))
                .start()
                .retry()
                .cache();
    }

    @Bean
    public SocketAcceptor ioRsocketSocketAcceptor(
            ThreadPoolExecutor letterProcessorExecutor,
            ObjectMapper objectMapper,
            LetterDecoder decoder,
            Mono<RSocket> guardRSocket
    ) {
        return ((setup, sendingSocket) -> Mono.just(new AbstractRSocket() {

            @Override
            public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
                return Flux.from(payloads)
//                        .onBackpressureBuffer(256)
//                        .onBackpressureDrop(payload -> log.error("Drop {}", payload.getDataUtf8()))
                        .map(this::convertToLetter)
                        .flatMap(letter -> Mono.fromCallable(() -> decoder.decode(letter))
                                        .subscribeOn(Schedulers.fromExecutor(letterProcessorExecutor)),
                                letterProcessorExecutor.getMaximumPoolSize() + 1)
                        .doOnRequest(value -> log.info("request seq {}", value))
                        .doOnError(throwable -> log.error("payloads error", throwable))
                        .log()
                        .map(this::convertToPayload)
                        .transform(decodedLetterFlux -> guardRSocket.flatMapMany(rSocket -> rSocket.requestChannel(decodedLetterFlux)))
                        .thenMany(Flux.empty());
            }

            @SneakyThrows
            private Letter convertToLetter(Payload payload) {
                return objectMapper.readValue(payload.getData().array(), Letter.class);
            }

            @SneakyThrows
            private Payload convertToPayload(DecodedLetter payload) {
                return DefaultPayload.create(objectMapper.writeValueAsBytes(payload));
            }
        }));
    }
}
