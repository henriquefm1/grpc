package br.mackenzie.biblioteca.model;

import br.mackenzie.biblioteca.grpc.Emprestimo;
import br.mackenzie.biblioteca.grpc.Livro;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Repositório em memória do acervo (sem banco de dados).
 * Todos os métodos são sincronizados, pois o gRPC atende chamadas em várias threads.
 */
public class Acervo {

    private final Map<String, Livro> livrosPorId = new LinkedHashMap<>();
    private final Set<String> isbns = new HashSet<>();
    private final List<Emprestimo> emprestimos = new ArrayList<>();
    private int sequencia = 0;

    /** Sugestões fixas (palavra-chave -> livro) usadas quando o acervo não tem correspondência. */
    private final Map<String, Livro> sugestoesPorPalavra = new HashMap<>();

    public Acervo() {
        sugestoesPorPalavra.put("java", sugestao("Effective Java", "Joshua Bloch", 2018, "978-0134685991"));
        sugestoesPorPalavra.put("programacao", sugestao("Clean Code", "Robert C. Martin", 2008, "978-0132350884"));
        sugestoesPorPalavra.put("distribuido", sugestao("Designing Data-Intensive Applications", "Martin Kleppmann", 2017, "978-1449373320"));
        sugestoesPorPalavra.put("redes", sugestao("Computer Networking: A Top-Down Approach", "Kurose e Ross", 2016, "978-0133594140"));
        sugestoesPorPalavra.put("romance", sugestao("Dom Casmurro", "Machado de Assis", 1899, "978-8535911664"));
        sugestoesPorPalavra.put("ficcao", sugestao("1984", "George Orwell", 1949, "978-8535914849"));
    }

    private static Livro sugestao(String titulo, String autor, int ano, String isbn) {
        return Livro.newBuilder().setTitulo(titulo).setAutor(autor).setAno(ano).setIsbn(isbn).build();
    }

    /** Normaliza ISBN para comparação (remove hífens/espaços, caixa alta). */
    public static String normalizarIsbn(String isbn) {
        return isbn.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** Remove acentos e coloca em minúsculas. */
    public static String normalizar(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Cadastra o livro, gerando o ID. Retorna vazio se o ISBN já existir.
     * A verificação e a inserção são atômicas (synchronized).
     */
    public synchronized Optional<Livro> cadastrar(Livro livro) {
        String isbn = normalizarIsbn(livro.getIsbn());
        if (!isbns.add(isbn)) {
            return Optional.empty();
        }
        String id = String.format("LIV-%03d", ++sequencia);
        Livro salvo = livro.toBuilder().setId(id).build();
        livrosPorId.put(id, salvo);
        return Optional.of(salvo);
    }

    public synchronized boolean existe(String id) {
        return livrosPorId.containsKey(id);
    }

    public synchronized List<Livro> listarPorAutor(String autor) {
        String alvo = normalizar(autor);
        List<Livro> resultado = new ArrayList<>();
        for (Livro l : livrosPorId.values()) {
            if (normalizar(l.getAutor()).equals(alvo)) {
                resultado.add(l);
            }
        }
        return resultado;
    }

    public synchronized void registrarEmprestimo(Emprestimo emprestimo) {
        emprestimos.add(emprestimo);
    }

    public synchronized int totalEmprestimos() {
        return emprestimos.size();
    }

    /**
     * Sugere um livro a partir de um texto livre:
     * 1) procura no acervo por título/autor que contenha alguma palavra do texto;
     * 2) senão, usa o mapa de palavras-chave;
     * 3) senão, devolve uma sugestão padrão.
     */
    public synchronized Livro sugerir(String texto) {
        String[] palavras = normalizar(texto).split("[^a-z0-9]+");

        for (String p : palavras) {
            if (p.length() < 4) continue;
            for (Livro l : livrosPorId.values()) {
                if (normalizar(l.getTitulo()).contains(p) || normalizar(l.getAutor()).contains(p)) {
                    return l;
                }
            }
        }
        for (String p : palavras) {
            if (p.length() < 4) continue;
            for (Map.Entry<String, Livro> e : sugestoesPorPalavra.entrySet()) {
                if (p.contains(e.getKey()) || e.getKey().contains(p)) {
                    return e.getValue();
                }
            }
        }
        return sugestoesPorPalavra.get("romance");
    }
}
