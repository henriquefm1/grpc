# Biblioteca Digital — gRPC com Java

Atividade avaliativa de **Computação Distribuída** (Universidade Presbiteriana Mackenzie).

**Aluno:** Henrique Ferreira Marciano — **RA:** 10439797

## Descrição

Backend distribuído de um sistema de gerenciamento de biblioteca digital. Um servidor gRPC central expõe o `BibliotecaService`, com quatro operações, uma de cada tipo de comunicação gRPC. A persistência é em memória (`Acervo`, com `LinkedHashMap`/`List`).

| RPC | Tipo | Descrição |
|---|---|---|
| `cadastrarLivro` | Unary | Cadastra um livro; devolve ID gerado e status |
| `listarLivrosPorAutor` | Server streaming | Devolve, um a um, os livros de um autor |
| `registrarEmprestimos` | Client streaming | Recebe vários empréstimos e responde com um resumo (total e tempo) |
| `chatBibliotecario` | Bidirectional streaming | Para cada mensagem do usuário, responde com uma sugestão de livro |

### Tratamento de erros (`io.grpc.Status`)

| Situação | Status |
|---|---|
| Título/autor/ISBN vazios ou ano inválido | `INVALID_ARGUMENT` |
| ISBN já cadastrado (hífens e espaços são ignorados) | `ALREADY_EXISTS` |
| Autor sem livros no acervo | `NOT_FOUND` |
| Mensagem de chat vazia | `INVALID_ARGUMENT` |
| Token ausente/inválido (bônus) | `UNAUTHENTICATED` |

No client streaming, um empréstimo inválido (usuário vazio ou `livro_id` inexistente) **não derruba o stream**: ele é contado em `total_rejeitados` no resumo final.

### Bônus implementados

- **Interceptor de log** (`LogInterceptor`): registra toda chamada recebida e o status final.
- **Deadline** de 5 s em todas as chamadas do cliente.
- **Autenticação por token** no metadata (`authorization: Bearer biblioteca-token-123`), validada por `AuthInterceptor` e enviada pelo `TokenClientInterceptor`.

## Estrutura

```text
biblioteca-grpc/
├── pom.xml
└── src/main/
    ├── proto/biblioteca.proto
    └── java/br/mackenzie/biblioteca/
        ├── server/  BibliotecaServiceImpl, ServidorBiblioteca, LogInterceptor, AuthInterceptor
        ├── client/  ClienteBiblioteca, TokenClientInterceptor
        └── model/   Acervo
```

## Como compilar e executar

Requisitos: Java 17+ e Maven.

```bash
# compilar (gera as classes do .proto automaticamente)
mvn clean compile        # ou: mvn clean package

# terminal 1 — servidor (porta 50051 por padrão)
mvn exec:java -Dexec.mainClass=br.mackenzie.biblioteca.server.ServidorBiblioteca

# terminal 2 — cliente (executa o roteiro de testes completo)
mvn exec:java -Dexec.mainClass=br.mackenzie.biblioteca.client.ClienteBiblioteca
```

Argumentos opcionais: servidor `[porta]`; cliente `[host] [porta]`, por exemplo
`-Dexec.args="localhost 50051"`.

## Roteiro de testes executado pelo cliente

1. Cadastra 3 livros (Unary).
2. Lista os livros de "Machado de Assis" (Server streaming).
3. Lista os livros de um autor inexistente → `NOT_FOUND`.
4. Registra 5 empréstimos (Client streaming).
5. Chat com 3 mensagens (Bidirectional streaming).
6. Cadastra livro com ISBN duplicado → `ALREADY_EXISTS`.
7. (Bônus) Chamada sem token → `UNAUTHENTICATED`.

## Saída esperada

Cliente (os tempos podem variar):

```text
=== 1) UNARY — cadastrarLivro (3 livros) ===
  OK  id=LIV-001 | status=CADASTRADO | Livro 'Dom Casmurro' cadastrado com sucesso
  OK  id=LIV-002 | status=CADASTRADO | Livro 'Memórias Póstumas de Brás Cubas' cadastrado com sucesso
  OK  id=LIV-003 | status=CADASTRADO | Livro 'Clean Code' cadastrado com sucesso

=== 2/3) SERVER STREAMING — listarLivrosPorAutor("Machado de Assis") ===
  livro #1: Dom Casmurro | Machado de Assis (1899) | ISBN 978-8535911664 | id=LIV-001
  livro #2: Memórias Póstumas de Brás Cubas | Machado de Assis (1881) | ISBN 978-8535908770 | id=LIV-002
  Total recebido: 2

=== 2/3) SERVER STREAMING — listarLivrosPorAutor("Autor Inexistente") ===
  ERRO esperado: NOT_FOUND — Nenhum livro encontrado para o autor 'Autor Inexistente'

=== 4) CLIENT STREAMING — registrarEmprestimos (5 empréstimos) ===
  ...
  RESUMO: registrados=5 | rejeitados=0 | tempo=155 ms

=== 5) BIDIRECIONAL — chatBibliotecario (3 mensagens) ===
  Usuário: Quero algo de Machado
  Bibliotecário: Para "Quero algo de Machado", sugiro: Dom Casmurro (Machado de Assis)
  Usuário: Recomende um livro de programação
  Bibliotecário: Para "Recomende um livro de programação", sugiro: Clean Code (Robert C. Martin)
  Usuário: Tem algo sobre Java?
  Bibliotecário: Para "Tem algo sobre Java?", sugiro: Effective Java (Joshua Bloch)

=== 6) ERRO — cadastrar livro com ISBN duplicado ===
  ERRO esperado: ALREADY_EXISTS — Já existe um livro com o ISBN 978-8535911664

=== BÔNUS — chamada sem token de autenticação ===
  ERRO esperado: UNAUTHENTICATED — Token ausente ou inválido
```

> Os prints reais da execução ficam em `docs/prints/` (adicionar antes da entrega).

## Referências

- https://grpc.io/docs/languages/java/
- https://grpc.io/docs/what-is-grpc/core-concepts/
- https://protobuf.dev/programming-guides/proto3/
