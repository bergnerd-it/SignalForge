package com.bergnerd.signalforge.app.db.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Robust SQL script parser that splits SQL text into individual statements
 * while correctly respecting single-quoted strings, comments, and BEGIN...END blocks
 * used in SQLite triggers.
 */
public final class SqlScriptParser {

    private SqlScriptParser() {}

    public static List<String> parseStatements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        int blockDepth = 0;
        boolean inSingleQuotes = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        int len = script.length();
        for (int i = 0; i < len; i++) {
            char c = script.charAt(i);
            char next = (i + 1 < len) ? script.charAt(i + 1) : '\0';

            // 1. Line comment handling
            if (inLineComment) {
                current.append(c);
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            // 2. Block comment handling
            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            // 3. String literal handling
            if (inSingleQuotes) {
                current.append(c);
                if (c == '\'') {
                    if (next == '\'') {
                        // Escaped single quote: ''
                        current.append(next);
                        i++;
                    } else {
                        inSingleQuotes = false;
                    }
                }
                continue;
            }

            // 4. Check for start of comments or string literal
            if (c == '-' && next == '-') {
                current.append(c).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                current.append(c).append(next);
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '\'') {
                current.append(c);
                inSingleQuotes = true;
                continue;
            }

            // 5. Track BEGIN / END block keywords outside quotes and comments
            if (isWordBoundary(script, i, "BEGIN")) {
                blockDepth++;
                current.append("BEGIN");
                i += 4; // 'B' + 4 chars = "BEGIN"
                continue;
            }
            if (isWordBoundary(script, i, "END")) {
                if (blockDepth > 0) {
                    blockDepth--;
                }
                current.append("END");
                i += 2; // 'E' + 2 chars = "END"
                continue;
            }

            // 6. Statement terminator check
            if (c == ';' && blockDepth == 0) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            statements.add(remaining);
        }

        return statements;
    }

    private static boolean isWordBoundary(String text, int index, String word) {
        int wordLen = word.length();
        if (index + wordLen > text.length()) {
            return false;
        }

        // Check if prefix matches ignoring case
        if (!text.regionMatches(true, index, word, 0, wordLen)) {
            return false;
        }

        // Check before word boundary
        if (index > 0) {
            char prev = text.charAt(index - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_') {
                return false;
            }
        }

        // Check after word boundary
        if (index + wordLen < text.length()) {
            char next = text.charAt(index + wordLen);
            if (Character.isLetterOrDigit(next) || next == '_') {
                return false;
            }
        }

        return true;
    }
}
