package br.mackenzie.biblioteca.server;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Bônus: interceptor que registra automaticamente toda chamada recebida e seu status final. */
public class LogInterceptor implements ServerInterceptor {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String metodo = call.getMethodDescriptor().getFullMethodName();
        System.out.printf("[%s] [INTERCEPTOR] -> chamada recebida: %s (%s)%n",
                LocalTime.now().format(HORA), metodo, call.getMethodDescriptor().getType());

        ServerCall<ReqT, RespT> chamadaComLog = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                System.out.printf("[%s] [INTERCEPTOR] <- chamada finalizada: %s | status=%s%s%n",
                        LocalTime.now().format(HORA), metodo, status.getCode(),
                        status.getDescription() == null ? "" : " (" + status.getDescription() + ")");
                super.close(status, trailers);
            }
        };
        return next.startCall(chamadaComLog, headers);
    }
}
