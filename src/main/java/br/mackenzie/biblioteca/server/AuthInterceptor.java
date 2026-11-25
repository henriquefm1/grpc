package br.mackenzie.biblioteca.server;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/** Bônus: autenticação simples por token no header "authorization" (metadata). */
public class AuthInterceptor implements ServerInterceptor {

    public static final String TOKEN = "biblioteca-token-123";

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String valor = headers.get(AUTHORIZATION);
        if (valor == null || !valor.equals("Bearer " + TOKEN)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Token ausente ou inválido"), new Metadata());
            return new ServerCall.Listener<>() { }; // ignora a chamada rejeitada
        }
        return next.startCall(call, headers);
    }
}
