HotAnalPDF
==========

HotAnalPDF is a PDF analysis and transformation application designed for the
needs of [HotCRP][].

Building requires Java 11 or later, [Apache Maven][], and [iText][].

```
$ mvn package
$ java -jar target/hotanalpdf.jar --help
usage: hotanalpdf [-A] [--add-blank <N>] [--append-blank <N>] [--bmargin <arg>]
       [--cfoot <arg>] [--embed-fonts] [-F] [--footer-font] [--footer-rule]
       [--footer-size] [--help] [-I <POS IMAGE>] [--input-json <FILE>] [-j] [-J]
       [--kpsewhich <PATH>] [--lefoot <arg>] [--lfoot <arg>] [--lmargin <arg>]
       [--lofoot <arg>] [-o <FILE>] [--only <PAGES>] [-p <PAGENO>]
       [--redact-permissions] [--refoot <arg>] [--rfoot <arg>] [--rmargin <arg>]
       [--rofoot <arg>] [--roman] [-s] [--two-sided] [--unmodified-status
       <STATUS>]
Check and/or paginate PDFs for HotCRP.
-A,--check-anonymous           check metadata for anonymity
   --add-blank <N>
   --append-blank <N>          add N blank pages at end
   --bmargin <arg>             bottom margin [28]
   --cfoot <arg>               center footer
   --embed-fonts               embed missing fonts when possible
-F,--check-fonts               check font embedding
   --footer-font               footer font file [Times Roman]
   --footer-rule               footer rule POSITION[,WIDTH] in points
   --footer-size               footer size [9]
   --help                      print this message
-I,--image <POS IMAGE>         add image to page
   --input-json <FILE>         read file arguments from JSON
-j,--json                      write JSON output to stdout
-J,--check-javascript          check for JavaScript actions
   --kpsewhich <PATH>          kpsewhich binary
   --lefoot <arg>              left-hand footer, even-numbered pages
   --lfoot <arg>               left-hand footer
   --lmargin <arg>             left margin [54]
   --lofoot <arg>              left-hand footer, odd-numbered pages
-o,--output <FILE>             write output PDF to FILE
   --only <PAGES>              include only PAGES
-p,--paginate <PAGENO>         paginate starting at PAGENO
   --redact-permissions        redact ACM permissions
   --refoot <arg>              right-hand footer, even-numbered pages
   --rfoot <arg>               right-hand footer
   --rmargin <arg>             right margin [54]
   --rofoot <arg>              right-hand footer, odd-numbered pages
   --roman                     paginate in lowercase Roman numerals
-s,--strip                     strip JS/metadata
   --two-sided                 swap left/right foot/margin on even pages
   --unmodified-status <STATUS>exit status if input unmodified

Please report issues at https://github.com/kohler/hotanalpdf
```

Input files
-----------

Non-option arguments name input PDF files; `-` means standard input. Multiple
files are merged in order. Two special argument forms are also recognized,
either on the command line or in the array read by `--input-json`:

* `@` marks where pagination begins. Pages from files that precede `@` are not
  given footers, and page numbering starts at the first page of the next file.
  If `@` comes after the last file, no pagination occurs.
* `#TITLE` adds an outline (bookmark) entry named TITLE that points to the
  first page of the next file. Use more `#` characters for deeper levels, as in
  `##TITLE`.

HotAnalPDF is distributed under the
[GNU Affero General Public License, version 3][license], in accordance with iText.

[HotCRP]: https://hotcrp.com/
[Apache Maven]: https://maven.apache.org/
[iText]: https://itextpdf.com/
[license]: https://www.gnu.org/licenses/agpl-3.0.en.html
