package br.mackenzie.biblioteca.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;

import java.io.IOException;

public class ServidorBiblioteca {

    public static void main(String[] args) throws IOException, InterruptedException {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : 50051;

        // Em ServerInterceptors.intercept o ÚLTIMO interceptor é executado primeiro:
        // ordem de execução = Log -> Auth -> serviço (assim chamadas recusadas também são logadas).
        Server server = ServerBuilder.forPort(porta)
                .addService(ServerInterceptors.intercept(
                        new BibliotecaServiceImpl(), new AuthInterceptor(), new LogInterceptor()))
                .build()
                .start();

        System.out.println("Servidor da Biblioteca Digital iniciado na porta " + porta);
        System.out.println("Token esperado no header 'authorization': Bearer " + AuthInterceptor.TOKEN);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando servidor...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
