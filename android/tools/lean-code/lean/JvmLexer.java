// SPDX-License-Identifier: MIT
// Copyright 2026 Daniel Campos Ramos
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens;
import com.sun.tools.javac.util.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Compiler lexers, with UTF-16 offsets, over a line-framed private subprocess. */
public final class JvmLexer {
    private static String tokens(String language, String source) throws Exception {
        StringBuilder out = new StringBuilder();
        if (language.equals("java")) {
            var scanner = ScannerFactory.instance(new Context()).newScanner(source, true);
            int end = 0;
            for (;;) {
                scanner.nextToken();
                var token = scanner.token();
                if (token.kind == Tokens.TokenKind.ERROR || scanner.errPos() >= 0)
                    throw new IllegalArgumentException("javac lexical error");
                if (token.pos > end) out.append("GAP\t").append(end).append('\t').append(token.pos).append('\n');
                if (token.kind == Tokens.TokenKind.EOF) break;
                out.append(token.kind.name()).append('\t').append(token.pos).append('\t').append(token.endPos).append('\n');
                end = token.endPos;
            }
        } else if (language.equals("kotlin")) {
            Class<?> type = Class.forName("org.jetbrains.kotlin.lexer.KotlinLexer");
            Object lexer = type.getConstructor().newInstance();
            type.getMethod("start", CharSequence.class).invoke(lexer, source);
            for (;;) {
                Object token = type.getMethod("getTokenType").invoke(lexer);
                if (token == null) break;
                String name = token.toString();
                if (name.contains("BAD_CHARACTER")) throw new IllegalArgumentException("Kotlin lexical error");
                out.append(name).append('\t').append(type.getMethod("getTokenStart").invoke(lexer))
                    .append('\t').append(type.getMethod("getTokenEnd").invoke(lexer)).append('\n');
                type.getMethod("advance").invoke(lexer);
            }
        } else throw new IllegalArgumentException("Unknown language");
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            try {
                String[] parts = line.split("\t", 2);
                String source = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                String result = tokens(parts[0], source);
                System.out.println("OK\t" + Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));
            } catch (Throwable error) {
                System.out.println("ERROR\t" + Base64.getEncoder().encodeToString(error.toString().getBytes(StandardCharsets.UTF_8)));
            }
            System.out.flush();
        }
    }
}
