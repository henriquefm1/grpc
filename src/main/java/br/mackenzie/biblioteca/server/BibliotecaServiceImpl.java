package br.mackenzie.biblioteca.server;

import br.mackenzie.biblioteca.grpc.AutorRequest;
import br.mackenzie.biblioteca.grpc.BibliotecaServiceGrpc;
import br.mackenzie.biblioteca.grpc.CadastroResponse;
import br.mackenzie.biblioteca.grpc.Emprestimo;
import br.mackenzie.biblioteca.grpc.Livro;
import br.mackenzie.biblioteca.grpc.MensagemUsuario;
import br.mackenzie.biblioteca.grpc.ResumoEmprestimos;
import br.mackenzie.biblioteca.grpc.SugestaoLivro;
import br.mackenzie.biblioteca.model.Acervo;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class BibliotecaServiceImpl extends BibliotecaServiceGrpc.BibliotecaServiceImplBase {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Acervo acervo = new Acervo();

    private static void log(String metodo, String detalhes) {
        System.out.printf("[%s] [SERVICE] %s | %s%n", LocalTime.now().format(HORA), metodo, detalhes);
    }

    // ------------------------------------------------------------------
    // 1) Unary RPC
    // ------------------------------------------------------------------
    @Override
    public void cadastrarLivro(Livro request, StreamObserver<CadastroResponse> responseObserver) {
        log("cadastrarLivro", String.format("titulo='%s', autor='%s', ano=%d, isbn='%s'",
                request.getTitulo(), request.getAutor(), request.getAno(), request.getIsbn()));

        String erro = validar(request);
        if (erro != null) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(erro).asRuntimeException());
            return;
        }

        Optional<Livro> salvo = acervo.cadastrar(request);
        if (salvo.isEmpty()) {
            responseObserver.onError(Status.ALREADY_EXISTS
                    .withDescription("Já existe um livro com o ISBN " + request.getIsbn())
                    .asRuntimeException());
            return;
        }

        responseObserver.onNext(CadastroResponse.newBuilder()
                .setId(salvo.get().getId())
                .setStatus("CADASTRADO")
                .setMensagem("Livro '" + salvo.get().getTitulo() + "' cadastrado com sucesso")
                .build());
        responseObserver.onCompleted();
    }

    private static String validar(Livro l) {
        if (l.getTitulo().isBlank()) return "O título é obrigatório";
        if (l.getAutor().isBlank()) return "O autor é obrigatório";
        if (l.getIsbn().isBlank()) return "O ISBN é obrigatório";
        if (l.getAno() <= 0 || l.getAno() > 2100) return "Ano inválido: " + l.getAno();
        return null;
    }

    // ------------------------------------------------------------------
    // 2) Server streaming RPC
    // ------------------------------------------------------------------
    @Override
    public void listarLivrosPorAutor(AutorRequest request, StreamObserver<Livro> responseObserver) {
        log("listarLivrosPorAutor", "autor='" + request.getAutor() + "'");

        if (request.getAutor().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("O nome do autor é obrigatório").asRuntimeException());
            return;
        }

        List<Livro> livros = acervo.listarPorAutor(request.getAutor());
        if (livros.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Nenhum livro encontrado para o autor '" + request.getAutor() + "'")
                    .asRuntimeException());
            return;
        }

        for (Livro livro : livros) {
            responseObserver.onNext(livro); // um a um
        }
        responseObserver.onCompleted();
    }

    // ------------------------------------------------------------------
    // 3) Client streaming RPC
    // ------------------------------------------------------------------
    @Override
    public StreamObserver<Emprestimo> registrarEmprestimos(StreamObserver<ResumoEmprestimos> responseObserver) {
        log("registrarEmprestimos", "stream aberto");

        return new StreamObserver<>() {
            private final long inicio = System.nanoTime();
            private int registrados = 0;
            private int rejeitados = 0;

            @Override
            public void onNext(Emprestimo e) {
                log("registrarEmprestimos", String.format("usuario='%s', livro_id='%s'", e.getUsuario(), e.getLivroId()));

                // Empréstimos inválidos não derrubam o stream: são contados como rejeitados no resumo.
                if (e.getUsuario().isBlank() || !acervo.existe(e.getLivroId())) {
                    rejeitados++;
                    log("registrarEmprestimos", "  -> REJEITADO (usuário vazio ou livro inexistente)");
                    return;
                }
                simularProcessamento();
                acervo.registrarEmprestimo(e);
                registrados++;
            }

            @Override
            public void onError(Throwable t) {
                log("registrarEmprestimos", "erro do cliente: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                long ms = (System.nanoTime() - inicio) / 1_000_000;
                log("registrarEmprestimos", String.format("fim do stream: %d registrados, %d rejeitados, %d ms",
                        registrados, rejeitados, ms));
                responseObserver.onNext(ResumoEmprestimos.newBuilder()
                        .setTotalRegistrados(registrados)
                        .setTotalRejeitados(rejeitados)
                        .setTempoTotalMs(ms)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    /** Pequena pausa para que o tempo de processamento seja visível na demonstração. */
    private static void simularProcessamento() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 4) Bidirectional streaming RPC
    // ------------------------------------------------------------------
    @Override
    public StreamObserver<MensagemUsuario> chatBibliotecario(StreamObserver<SugestaoLivro> responseObserver) {
        log("chatBibliotecario", "chat aberto");

        return new StreamObserver<>() {
            private boolean encerrado = false;

            @Override
            public void onNext(MensagemUsuario msg) {
                if (encerrado) return;
                log("chatBibliotecario", "mensagem='" + msg.getTexto() + "'");

                if (msg.getTexto().isBlank()) {
                    encerrado = true;
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("A mensagem não pode ser vazia").asRuntimeException());
                    return;
                }

                Livro sugerido = acervo.sugerir(msg.getTexto());
                responseObserver.onNext(SugestaoLivro.newBuilder()
                        .setMensagem("Para \"" + msg.getTexto() + "\", sugiro: "
                                + sugerido.getTitulo() + " (" + sugerido.getAutor() + ")")
                        .setLivro(sugerido)
                        .build());
            }

            @Override
            public void onError(Throwable t) {
                log("chatBibliotecario", "erro do cliente: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (encerrado) return;
                log("chatBibliotecario", "chat encerrado pelo cliente");
                responseObserver.onCompleted();
            }
        };
    }
}
