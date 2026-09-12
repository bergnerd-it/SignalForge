package com.bergnerd.signalforge.app.research.historical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Robust RFC-4180 compliant CSV parser.
 * Handles quoted fields with embedded commas, quotes (""), and newlines.
 * Does not use naive comma splitting.
 */
public class Rfc4180CsvParser {

    public static class CsvParseException extends RuntimeException {
        private final int line;
        private final int column;

        public CsvParseException(String message, int line, int column) {
            super(String.format("CSV parse error at line %d, col %d: %s", line, column, message));
            this.line = line;
            this.column = column;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }
    }

    public static List<List<String>> parse(String input) {
        if (input == null) {
            return List.of();
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();

        boolean inQuotes = false;
        int line = 1;
        int col = 0;
        int fieldStartLine = 1;
        int fieldStartCol = 1;

        int length = input.length();
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            col++;

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && input.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i++;
                        col++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentField.append(c);
                    if (c == '\n') {
                        line++;
                        col = 0;
                    }
                }
            } else {
                if (c == '"') {
                    if (currentField.length() > 0) {
                        throw new CsvParseException("Unexpected quote character inside unquoted field", line, col);
                    }
                    inQuotes = true;
                    fieldStartLine = line;
                    fieldStartCol = col;
                } else if (c == ',') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                } else if (c == '\r') {
                    if (i + 1 < length && input.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    line++;
                    col = 0;
                } else if (c == '\n') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    line++;
                    col = 0;
                } else {
                    currentField.append(c);
                }
            }
        }

        if (inQuotes) {
            throw new CsvParseException("Unclosed quoted field reached EOF", fieldStartLine, fieldStartCol);
        }

        if (currentField.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentField.toString());
            rows.add(currentRow);
        }

        return rows;
    }

    public static List<Map<String, String>> parseRecords(String input) {
        List<List<String>> rawRows = parse(input);
        if (rawRows.isEmpty()) {
            return List.of();
        }

        List<String> headers = rawRows.get(0).stream().map(String::trim).toList();
        List<Map<String, String>> records = new ArrayList<>();

        for (int r = 1; r < rawRows.size(); r++) {
            List<String> row = rawRows.get(r);
            // Skip empty trailing lines
            if (row.size() == 1 && row.get(0).trim().isEmpty() && r == rawRows.size() - 1) {
                continue;
            }

            Map<String, String> record = new HashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String header = headers.get(c);
                String value = c < row.size() ? row.get(c) : "";
                record.put(header, value);
            }
            records.add(record);
        }

        return records;
    }
}
