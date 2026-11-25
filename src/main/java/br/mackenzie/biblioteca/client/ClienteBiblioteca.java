package br.mackenzie.biblioteca.client;

import br.mackenzie.biblioteca.grpc.AutorRequest;
import br.mackenzie.biblioteca.grpc.BibliotecaServiceGrpc;
import br.mackenzie.biblioteca.grpc.CadastroResponse;
import br.mackenzie.biblioteca.grpc.Emprestimo;
import br.mackenzie.biblioteca.grpc.Livro;
import br.mackenzie.biblioteca.grpc.MensagemUsuario;
import br.mackenzie.biblioteca.grpc.ResumoEmprestimos;
import br.mackenzie.biblioteca.grpc.SugestaoLivro;
import br.mackenzie.biblioteca.server.AuthInterceptor;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Demonstra os 4 tipos de RPC e os cenários de erro do roteiro de testes. */
public class ClienteBiblioteca {

    /** Deadline aplicado a cada chamada (bônus). */
    private static final long TIMEOUT_SEGUNDOS = 5;

    private final BibliotecaServiceGrpc.BibliotecaServiceBlockingStub blocking;
    private final BibliotecaServiceGrpc.BibliotecaServiceStub async;

    public ClienteBiblioteca(Channel canal) {
        this.blocking = BibliotecaServiceGrpc.newBlockingStub(canal);
        this.async = BibliotecaServiceGrpc.newStub(canal);
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : 50051;

        ManagedChannel canal = ManagedChannelBuilder.forAddress(host, porta).usePlaintext().build();
        try {
            Channel autenticado = ClientInterceptors.intercept(canal, new TokenClientInterceptor(AuthInterceptor.TOKEN));
            ClienteBiblioteca cliente = new ClienteBiblioteca(autenticado);

            List<String> ids = cliente.testeCadastro();                 // 1
            cliente.testeListarPorAutor("Machado de Assis");            // 2
            cliente.testeListarPorAutor("Autor Inexistente");           // 3
            cliente.testeEmprestimos(ids);                              // 4
            cliente.testeChat();                                        // 5
            cliente.testeIsbnDuplicado();                               // 6
            new ClienteBiblioteca(canal).testeSemToken();               // bônus: autenticação
        } finally {
            canal.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // 1) Unary: cadastrar 3 livros
    // ------------------------------------------------------------------
    List<String> testeCadastro() {
        titulo("1) UNARY — cadastrarLivro (3 livros)");
        List<Livro> livros = List.of(
                livro("Dom Casmurro", "Machado de Assis", 1899, "978-8535911664"),
                livro("Memórias Póstumas de Brás Cubas", "Machado de Assis", 1881, "978-8535908770"),
                livro("Clean Code", "Robert C. Martin", 2008, "978-0132350884"));

        List<String> ids = new ArrayList<>();
        for (Livro l : livros) {
            CadastroResponse r = blocking.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS).cadastrarLivro(l);
            System.out.printf("  OK  id=%s | status=%s | %s%n", r.getId(), r.getStatus(), r.getMensagem());
            ids.add(r.getId());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 2 e 3) Server streaming: autor existente / inexistente
    // ------------------------------------------------------------------
    void testeListarPorAutor(String autor) {
        titulo("2/3) SERVER STREAMING — listarLivrosPorAutor(\"" + autor + "\")");
        try {
            Iterator<Livro> it = blocking.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)
                    .listarLivrosPorAutor(AutorRequest.newBuilder().setAutor(autor).build());
            int n = 0;
            while (it.hasNext()) {
                Livro l = it.next();
                n++;
                System.out.printf("  livro #%d: %s | %s (%d) | ISBN %s | id=%s%n",
                        n, l.getTitulo(), l.getAutor(), l.getAno(), l.getIsbn(), l.getId());
            }
            System.out.println("  Total recebido: " + n);
        } catch (StatusRuntimeException e) {
            System.out.printf("  ERRO esperado: %s — %s%n", e.getStatus().getCode(), e.getStatus().getDescription());
        }
    }

    // ------------------------------------------------------------------
    // 4) Client streaming: 5 empréstimos consecutivos
    // ------------------------------------------------------------------
    void testeEmprestimos(List<String> ids) throws InterruptedException {
        titulo("4) CLIENT STREAMING — registrarEmprestimos (5 empréstimos)");
        CountDownLatch fim = new CountDownLatch(1);

        StreamObserver<ResumoEmprestimos> resposta = new StreamObserver<>() {
            @Override
            public void onNext(ResumoEmprestimos r) {
                System.out.printf("  RESUMO: registrados=%d | rejeitados=%d | tempo=%d ms%n",
                        r.getTotalRegistrados(), r.getTotalRejeitados(), r.getTempoTotalMs());
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("  ERRO: " + t.getMessage());
                fim.countDown();
            }

            @Override
            public void onCompleted() {
                fim.countDown();
            }
        };

        StreamObserver<Emprestimo> envio = async.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)
                .registrarEmprestimos(resposta);

        String[] usuarios = {"ana", "bruno", "carla", "diego", "elisa"};
        for (int i = 0; i < usuarios.length; i++) {
            String livroId = ids.get(i % ids.size());
            System.out.printf("  enviando: usuario=%s, livro_id=%s%n", usuarios[i], livroId);
            envio.onNext(Emprestimo.newBuilder().setUsuario(usuarios[i]).setLivroId(livroId).build());
        }
        envio.onCompleted();
        fim.await(TIMEOUT_SEGUNDOS + 2, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // 5) Bidirectional streaming: chat com 3 mensagens
    // ------------------------------------------------------------------
    void testeChat() throws InterruptedException {
        titulo("5) BIDIRECIONAL — chatBibliotecario (3 mensagens)");
        CountDownLatch fim = new CountDownLatch(1);

        StreamObserver<SugestaoLivro> resposta = new StreamObserver<>() {
            @Override
            public void onNext(SugestaoLivro s) {
                System.out.println("  Bibliotecário: " + s.getMensagem());
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("  ERRO: " + t.getMessage());
                fim.countDown();
            }

            @Override
            public void onCompleted() {
                System.out.println("  (chat encerrado)");
                fim.countDown();
            }
        };

        StreamObserver<MensagemUsuario> envio = async.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)
                .chatBibliotecario(resposta);

        String[] mensagens = {"Quero algo de Machado", "Recomende um livro de programação", "Tem algo sobre Java?"};
        for (String m : mensagens) {
            System.out.println("  Usuário: " + m);
            envio.onNext(MensagemUsuario.newBuilder().setTexto(m).build());
            Thread.sleep(300);
        }
        envio.onCompleted();
        fim.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // 6) ISBN duplicado -> ALREADY_EXISTS
    // ------------------------------------------------------------------
    void testeIsbnDuplicado() {
        titulo("6) ERRO — cadastrar livro com ISBN duplicado");
        try {
            blocking.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)
                    .cadastrarLivro(livro("Outro Título", "Outro Autor", 2020, "978-8535911664"));
            System.out.println("  ATENÇÃO: deveria ter falhado!");
        } catch (StatusRuntimeException e) {
            System.out.printf("  ERRO esperado: %s — %s%n", e.getStatus().getCode(), e.getStatus().getDescription());
        }
    }

    // ------------------------------------------------------------------
    // Bônus: chamada sem token -> UNAUTHENTICATED
    // ------------------------------------------------------------------
    void testeSemToken() {
        titulo("BÔNUS — chamada sem token de autenticação");
        try {
            blocking.withDeadlineAfter(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)
                    .listarLivrosPorAutor(AutorRequest.newBuilder().setAutor("Machado de Assis").build())
                    .hasNext();
            System.out.println("  ATENÇÃO: deveria ter falhado!");
        } catch (StatusRuntimeException e) {
            System.out.printf("  ERRO esperado: %s — %s%n", e.getStatus().getCode(), e.getStatus().getDescription());
        }
    }

    // ------------------------------------------------------------------
    private static Livro livro(String titulo, String autor, int ano, String isbn) {
        return Livro.newBuilder().setTitulo(titulo).setAutor(autor).setAno(ano).setIsbn(isbn).build();
    }

    private static void titulo(String texto) {
        System.out.println();
        System.out.println("=== " + texto + " ===");
    }
}
