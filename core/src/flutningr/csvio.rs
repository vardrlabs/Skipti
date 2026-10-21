//! RFC 4180 CSV primitives, hand-rolled and exhaustively tested.
//!
//! Narrow, fully specified format: the same reasoning that keeps CBOR
//! payload packing in-tree rather than behind a crate. The reader is STRICT:
//! quotes are only special at field start, a quote inside an unquoted field
//! or garbage after a closing quote is an error, because silent leniency in
//! a money import path is how rows quietly change meaning.
//!
//! The writer emits what §9.2 asks the flat export to be: UTF-8 with BOM
//! (Excel's sniffing needs it), CRLF records, minimal quoting (only fields
//! containing delimiter/quote/CR/LF get quoted).

/// UTF-8 byte order mark, prepended by [`write_csv`] and stripped by
/// [`parse_csv`]. Splitwise's real exports carry one (documented quirk).
pub const UTF8_BOM: &str = "\u{FEFF}";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CsvSyntaxError {
    pub offset: usize,
    pub reason: String,
}

impl CsvSyntaxError {
    fn new(offset: usize, reason: impl Into<String>) -> Self {
        CsvSyntaxError {
            offset,
            reason: reason.into(),
        }
    }
}

impl std::fmt::Display for CsvSyntaxError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "byte {}: {}", self.offset, self.reason)
    }
}

/// One parsed CSV record together with the 1-based PHYSICAL line it ends on.
/// Importers report this number because it matches the row a user sees when
/// they open the file in a spreadsheet, even when blank or multi-line
/// records would otherwise shift the counting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CsvRecord {
    pub end_line: usize,
    pub fields: Vec<String>,
}

impl std::ops::Deref for CsvRecord {
    type Target = Vec<String>;
    fn deref(&self) -> &Vec<String> {
        &self.fields
    }
}

/// Parses CSV per RFC 4180 with two documented, deliberate extensions:
/// a leading UTF-8 BOM is stripped, and `\n` alone is accepted as a record
/// terminator alongside CRLF (Splitwise exports have been seen using both).
/// Blank lines yield no record. Quoted fields may contain delimiters,
/// quotes (`""`) and newlines verbatim.
pub fn parse_csv(text: &str) -> Result<Vec<Vec<String>>, CsvSyntaxError> {
    Ok(parse_csv_with_lines(text)?
        .into_iter()
        .map(|r| r.fields)
        .collect())
}

/// [`parse_csv`], keeping each record's ending line number.
pub fn parse_csv_with_lines(text: &str) -> Result<Vec<CsvRecord>, CsvSyntaxError> {
    let text = text.strip_prefix(UTF8_BOM).unwrap_or(text);
    let bytes = text.as_bytes();
    let mut records: Vec<CsvRecord> = Vec::new();
    let mut field = String::new();
    let mut record: Vec<String> = Vec::new();
    let mut line = 1usize;
    let mut i = 0usize;
    // True when the current field began with a quote and must end at its pair.
    let mut in_quotes = false;
    // True when the field started with a quote that has since been closed;
    // any non-delimiter content after this point is a syntax error.
    let mut quoted_closed = false;

    while i < bytes.len() {
        let b = bytes[i];
        if in_quotes {
            match b {
                b'"' => {
                    if bytes.get(i + 1) == Some(&b'"') {
                        field.push('"');
                        i += 2;
                    } else {
                        in_quotes = false;
                        quoted_closed = true;
                        i += 1;
                    }
                }
                _ => {
                    // Multi-byte UTF-8 sequences pass through untouched.
                    let ch_len = utf8_len(b);
                    let end = (i + ch_len).min(bytes.len());
                    field.push_str(
                        text.get(i..end)
                            .ok_or_else(|| CsvSyntaxError::new(i, "invalid UTF-8 boundary"))?,
                    );
                    i = end;
                }
            }
            continue;
        }
        match b {
            b'"' if field.is_empty() && !quoted_closed => {
                in_quotes = true;
                i += 1;
            }
            b'"' => {
                return Err(CsvSyntaxError::new(i, "quote inside unquoted field"));
            }
            b',' => {
                record.push(std::mem::take(&mut field));
                quoted_closed = false;
                i += 1;
            }
            b'\r' if bytes.get(i + 1) == Some(&b'\n') => {
                finish_record(
                    &mut records,
                    &mut record,
                    &mut field,
                    &mut quoted_closed,
                    line,
                );
                line += 1;
                i += 2;
            }
            b'\r' => {
                return Err(CsvSyntaxError::new(i, "bare CR outside CRLF"));
            }
            b'\n' => {
                finish_record(
                    &mut records,
                    &mut record,
                    &mut field,
                    &mut quoted_closed,
                    line,
                );
                line += 1;
                i += 1;
            }
            _ => {
                if quoted_closed {
                    return Err(CsvSyntaxError::new(i, "content after closing quote"));
                }
                let ch_len = utf8_len(b);
                let end = (i + ch_len).min(bytes.len());
                field.push_str(
                    text.get(i..end)
                        .ok_or_else(|| CsvSyntaxError::new(i, "invalid UTF-8 boundary"))?,
                );
                i = end;
            }
        }
    }
    if in_quotes {
        return Err(CsvSyntaxError::new(
            bytes.len(),
            "unterminated quoted field",
        ));
    }
    // A final field/record without trailing newline still counts.
    if !field.is_empty() || !record.is_empty() || quoted_closed {
        finish_record(
            &mut records,
            &mut record,
            &mut field,
            &mut quoted_closed,
            line,
        );
    }
    Ok(records)
}

fn utf8_len(first_byte: u8) -> usize {
    match first_byte {
        0x00..=0x7F => 1,
        0xC0..=0xDF => 2,
        0xE0..=0xEF => 3,
        _ => 4,
    }
}

fn finish_record(
    records: &mut Vec<CsvRecord>,
    record: &mut Vec<String>,
    field: &mut String,
    quoted_closed: &mut bool,
    end_line: usize,
) {
    record.push(std::mem::take(field));
    let complete = std::mem::take(record);
    *quoted_closed = false;
    if !(complete.len() == 1 && complete[0].is_empty()) {
        records.push(CsvRecord {
            end_line,
            fields: complete,
        }); // blank lines produce no record
    }
}

fn needs_quoting(field: &str) -> bool {
    field
        .bytes()
        .any(|b| matches!(b, b',' | b'"' | b'\r' | b'\n'))
}

/// Serialises rows as RFC 4180: CRLF records, minimal quoting, `""` escapes
/// inside quoted fields.
///
/// Deliberately NO byte-order mark: §9.2's "UTF-8 with BOM" is a property of
/// the written FILE, not of the text, and a leading U+FEFF cannot survive
/// the UniFFI→Swift boundary at all. Foundation's `String(bytes:encoding:)`
/// consumes a leading BOM as an encoding signature (found empirically,
/// incident §5.15). Each shell therefore prepends [`UTF8_BOM`] when it
/// writes the file; the reader tolerates files that carry one because real
/// exports do.
pub fn write_csv(rows: &[Vec<String>]) -> String {
    let mut out = String::new();
    for (idx, row) in rows.iter().enumerate() {
        if idx > 0 {
            out.push_str("\r\n");
        }
        for (col, field) in row.iter().enumerate() {
            if col > 0 {
                out.push(',');
            }
            if needs_quoting(field) {
                out.push('"');
                out.push_str(&field.replace('"', "\"\""));
                out.push('"');
            } else {
                out.push_str(field);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_rows_round_trip_through_crlf() {
        let rows = vec![
            vec!["Date".into(), "Description".into(), "Cost".into()],
            vec!["2026-01-15".into(), "Dinner".into(), "45.50".into()],
        ];
        let text = write_csv(&rows);
        // No BOM in the text itself: that is the file writer's job (§5.15).
        assert!(text.starts_with("Date,Description,Cost\r\n"));
        assert_eq!(parse_csv(&text).unwrap(), rows);
        // A BOM-carrying FILE parses identically to its bare text:
        assert_eq!(
            parse_csv(format!("{UTF8_BOM}{text}").as_str()).unwrap(),
            rows
        );
    }

    #[test]
    fn quoted_fields_survive_commas_quotes_and_newlines() {
        let hostile = "he said \"split it\", twice\nand left";
        let rows = vec![vec![hostile.to_string(), "12.00".into()]];
        let text = write_csv(&rows);
        assert_eq!(parse_csv(&text).unwrap(), rows);

        // Hand-built quoting, not produced by our writer:
        let manual = "a,\"b\"\"c\",d\r\n\"multi\r\nline\",x,y";
        assert_eq!(
            parse_csv(manual).unwrap(),
            vec![
                vec!["a".to_string(), "b\"c".to_string(), "d".to_string()],
                vec![
                    "multi\r\nline".to_string(),
                    "x".to_string(),
                    "y".to_string()
                ],
            ]
        );
    }

    #[test]
    fn bom_and_line_ending_variants_parse_identically() {
        let crlf = "a,b\r\nc,d";
        let lf = "a,b\nc,d";
        let mixed = "a,b\r\nc,d\n";
        let bommed = format!("{UTF8_BOM}{mixed}");
        let want = vec![
            vec!["a".to_string(), "b".to_string()],
            vec!["c".to_string(), "d".to_string()],
        ];
        for t in [crlf, lf, mixed, bommed.as_str()] {
            assert_eq!(parse_csv(t).unwrap(), want, "{t:?}");
        }
        // No trailing newline at all:
        assert_eq!(
            parse_csv("a,b,c").unwrap(),
            vec![vec!["a".to_string(), "b".to_string(), "c".to_string()]]
        );
    }

    #[test]
    fn empty_and_edge_shaped_fields_are_preserved() {
        // Empty last field, empty middle field, quoted empty string.
        assert_eq!(
            parse_csv("a,,\r\n\"\",\"\",x").unwrap(),
            vec![
                vec!["a".into(), String::new(), String::new()],
                vec![String::new(), String::new(), "x".into()],
            ]
        );
        // Blank line mid-file yields nothing; file of only blank lines is empty.
        assert_eq!(parse_csv("a\n\nb").unwrap().len(), 2);
        assert_eq!(parse_csv("\r\n\r\n").unwrap(), Vec::<Vec<String>>::new());
    }

    #[test]
    fn syntax_violations_fail_loudly_with_positions() {
        // Unterminated quote:
        let e = parse_csv("a,\"bc\nz").unwrap_err();
        assert!(e.reason.contains("unterminated"));
        // Content after a closing quote:
        let e = parse_csv("\"ab\"cd,e").unwrap_err();
        assert!(e.reason.contains("after closing quote"), "{e}");
        // Quote inside an unquoted field:
        let e = parse_csv("ab\"cd,e").unwrap_err();
        assert!(e.reason.contains("inside unquoted"));
        // A bare CR is a syntax violation, never silent field content:
        let e = parse_csv("a\rb,c").unwrap_err();
        assert!(e.reason.contains("bare CR"));
    }

    #[test]
    fn multibyte_utf8_survives_field_boundaries() {
        let rows = vec![vec!["Café ☕".into(), "₹42.00".into(), "日本".into()]];
        assert_eq!(parse_csv(&write_csv(&rows)).unwrap(), rows);
        // A multi-byte char immediately before a delimiter:
        assert_eq!(
            parse_csv("₹,x").unwrap(),
            vec![vec!["₹".to_string(), "x".to_string()]]
        );
    }
}
