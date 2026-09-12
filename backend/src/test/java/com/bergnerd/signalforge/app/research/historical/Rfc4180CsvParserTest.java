package com.bergnerd.signalforge.app.research.historical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Rfc4180CsvParserTest {

    @Test
    void parsesSimpleCsvLines() {
        String csv = "a,b,c\n1,2,3\n4,5,6";
        List<List<String>> lines = Rfc4180CsvParser.parse(csv);
        assertEquals(3, lines.size());
        assertEquals(List.of("a", "b", "c"), lines.get(0));
        assertEquals(List.of("1", "2", "3"), lines.get(1));
        assertEquals(List.of("4", "5", "6"), lines.get(2));
    }

    @Test
    void parsesQuotedFieldsWithCommasAndNewlines() {
        String csv = "\"header, 1\",\"header 2\"\n\"line\nwith\nnewlines\",\"normal\"\n\"escaped \"\"quote\"\"\",last";
        List<List<String>> lines = Rfc4180CsvParser.parse(csv);
        assertEquals(3, lines.size());
        assertEquals(List.of("header, 1", "header 2"), lines.get(0));
        assertEquals(List.of("line\nwith\nnewlines", "normal"), lines.get(1));
        assertEquals(List.of("escaped \"quote\"", "last"), lines.get(2));
    }

    @Test
    void parsesRecordsWithHeaders() {
        String csv = "id,symbol,price\n1,AAPL,150.25\n2,MSFT,310.50\n";
        List<Map<String, String>> records = Rfc4180CsvParser.parseRecords(csv);
        assertEquals(2, records.size());
        assertEquals("AAPL", records.get(0).get("symbol"));
        assertEquals("150.25", records.get(0).get("price"));
        assertEquals("MSFT", records.get(1).get("symbol"));
    }

    @Test
    void rejectsUnclosedQuotesAtEof() {
        String csv = "a,b\n\"unclosed,123";
        assertThrows(Rfc4180CsvParser.CsvParseException.class, () -> Rfc4180CsvParser.parse(csv));
    }

    @Test
    void rejectsUnexpectedQuoteInsideUnquotedField() {
        String csv = "a,b\nfoo\"bar,123";
        assertThrows(Rfc4180CsvParser.CsvParseException.class, () -> Rfc4180CsvParser.parse(csv));
    }
}
